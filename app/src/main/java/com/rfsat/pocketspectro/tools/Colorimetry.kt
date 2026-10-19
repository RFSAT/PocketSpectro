package com.rfsat.pocketspectro.tools

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CIE colorimetry for the Colour and Light tools.
 * Colour-matching functions: the CIE 1931 2° observer, using the multi-lobe Gaussian fit of
 * Wyman, Sloan & Shirley (2013), accurate to about 1 % of the tabulated functions.
 * Illuminant: CIE D65 (tabulated at 10 nm, 380-780 nm).
 */
object Colorimetry {

    data class XYZ(val x: Double, val y: Double, val z: Double) {
        val chromaticity: Pair<Double, Double> get() { val s = x + y + z; return if (s > 0) (x / s) to (y / s) else Double.NaN to Double.NaN }
    }

    data class Lab(val l: Double, val a: Double, val b: Double) {
        val chroma get() = sqrt(a * a + b * b)
        val hueDeg get() = (Math.toDegrees(atan2(b, a)) + 360.0) % 360.0
    }

    private fun g(l: Double, mu: Double, s1: Double, s2: Double): Double {
        val t = (l - mu) / (if (l < mu) s1 else s2)
        return exp(-0.5 * t * t)
    }

    fun xBar(l: Double) = 1.056 * g(l, 599.8, 37.9, 31.0) + 0.362 * g(l, 442.0, 16.0, 26.7) - 0.065 * g(l, 501.1, 20.4, 26.2)
    fun yBar(l: Double) = 0.821 * g(l, 568.8, 46.9, 40.5) + 0.286 * g(l, 530.9, 16.3, 31.1)
    fun zBar(l: Double) = 1.217 * g(l, 437.0, 11.8, 36.0) + 0.681 * g(l, 459.0, 26.0, 13.8)

    private val D65_10NM = doubleArrayOf(
        49.98, 54.65, 82.75, 91.49, 93.43, 86.68, 104.86, 117.01, 117.81, 114.86, 115.92, 108.81, 109.35, 107.80,
        104.79, 107.69, 104.41, 104.05, 100.00, 96.33, 95.79, 88.69, 90.01, 89.60, 87.70, 83.29, 83.70, 80.03,
        80.21, 82.28, 78.28, 69.72, 71.61, 74.35, 61.60, 69.89, 75.09, 63.59, 46.42, 66.81, 63.38,
    )

    fun d65(l: Double): Double {
        val p = ((l - 380.0) / 10.0).coerceIn(0.0, 40.0)
        val i = p.toInt().coerceAtMost(39)
        val t = p - i
        return D65_10NM[i] + t * (D65_10NM[i + 1] - D65_10NM[i])
    }

    /** The whole visible range (380-780 nm) is covered: full colour measurement. */
    fun supported(wl: DoubleArray) = Spectra.covers(wl, 380.0, 780.0)

    /** At least 40 nm of the visible range is covered: colour differences inside it can be monitored. */
    fun partlySupported(wl: DoubleArray) = wl.isNotEmpty() && minOf(wl.last(), 780.0) - maxOf(wl.first(), 380.0) >= 40.0

    /** Part of 380-780 nm the spectrometer covers. */
    fun coveredRange(wl: DoubleArray) = maxOf(wl.first(), 380.0)..minOf(wl.last(), 780.0)

    /**
     * Share (0..1) of the eye's colour response (D65 x CMFs, summed over x, y and z) that falls inside
     * the covered range. Outside it the reflectance is taken as the nearest measured value, so colour
     * values are approximate and only differences inside the covered range are seen.
     */
    fun coverage(wl: DoubleArray): Double {
        val r = coveredRange(wl)
        var inside = 0.0; var all = 0.0
        for (l in GRID) { val w = d65(l) * (xBar(l) + yBar(l) + zBar(l)); all += w; if (l in r) inside += w }
        return if (all > 0) inside / all else 0.0
    }

    /** Hue bands of the visible spectrum, for telling the user which colours a partial unit can follow. */
    enum class HueBand(val lo: Double, val hi: Double) {
        VIOLET(380.0, 450.0), BLUE(450.0, 495.0), GREEN(495.0, 570.0), YELLOW(570.0, 590.0), ORANGE(590.0, 620.0), RED(620.0, 780.0)
    }

    /** Hue bands at least half covered by the spectrometer. */
    fun coveredBands(wl: DoubleArray): List<HueBand> {
        val r = coveredRange(wl)
        return HueBand.entries.filter { b -> (minOf(b.hi, r.endInclusive) - maxOf(b.lo, r.start)) >= 0.5 * (b.hi - b.lo) }
    }

    private val GRID = Spectra.grid(380.0, 780.0, 5.0)

    /** Tristimulus values of a reflectance (0..1) under D65, scaled so a perfect white has Y = 100. */
    fun reflectanceXYZ(wl: DoubleArray, r: DoubleArray): XYZ {
        var x = 0.0; var y = 0.0; var z = 0.0; var k = 0.0
        for (l in GRID) {
            val s = d65(l); val rv = Spectra.at(wl, r, l)
            x += s * xBar(l) * rv; y += s * yBar(l) * rv; z += s * zBar(l) * rv; k += s * yBar(l)
        }
        return XYZ(100 * x / k, 100 * y / k, 100 * z / k)
    }

    /** White point of D65 for the same observer approximation (Y = 100). */
    val whiteD65: XYZ by lazy {
        var x = 0.0; var y = 0.0; var z = 0.0
        for (l in GRID) { val s = d65(l); x += s * xBar(l); y += s * yBar(l); z += s * zBar(l) }
        XYZ(100 * x / y, 100.0, 100 * z / y)
    }

    /** Tristimulus values of an emission spectrum (relative units), normalised to Y = 100. */
    fun emissionXYZ(wl: DoubleArray, spd: DoubleArray): XYZ {
        var x = 0.0; var y = 0.0; var z = 0.0
        for (l in GRID) { val s = Spectra.at(wl, spd, l).coerceAtLeast(0.0); x += s * xBar(l); y += s * yBar(l); z += s * zBar(l) }
        return if (y > 0) XYZ(100 * x / y, 100.0, 100 * z / y) else XYZ(0.0, 0.0, 0.0)
    }

    fun lab(c: XYZ, white: XYZ = whiteD65): Lab {
        fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
        val fx = f(c.x / white.x); val fy = f(c.y / white.y); val fz = f(c.z / white.z)
        return Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** sRGB (0..255, clipped) for a colour swatch. */
    fun srgb(c: XYZ): IntArray {
        val x = c.x / 100; val y = c.y / 100; val z = c.z / 100
        val lin = doubleArrayOf(
            3.2406 * x - 1.5372 * y - 0.4986 * z,
            -0.9689 * x + 1.8758 * y + 0.0415 * z,
            0.0557 * x - 0.2040 * y + 1.0570 * z,
        )
        return IntArray(3) {
            val v = lin[it].coerceIn(0.0, 1.0)
            val g = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
            (g * 255 + 0.5).toInt().coerceIn(0, 255)
        }
    }

    /** CIEDE2000 colour difference (Sharma, Wu & Dalal 2005). */
    fun deltaE2000(p: Lab, q: Lab): Double {
        val c1 = p.chroma; val c2 = q.chroma
        val cm = (c1 + c2) / 2
        val g = 0.5 * (1 - sqrt(cm.pow(7) / (cm.pow(7) + 25.0.pow(7))))
        val a1 = (1 + g) * p.a; val a2 = (1 + g) * q.a
        val c1p = sqrt(a1 * a1 + p.b * p.b); val c2p = sqrt(a2 * a2 + q.b * q.b)
        fun hp(b: Double, a: Double) = if (a == 0.0 && b == 0.0) 0.0 else (Math.toDegrees(atan2(b, a)) + 360) % 360
        val h1 = hp(p.b, a1); val h2 = hp(q.b, a2)
        val dl = q.l - p.l
        val dc = c2p - c1p
        val dh = when {
            c1p * c2p == 0.0 -> 0.0
            abs(h2 - h1) <= 180 -> h2 - h1
            h2 - h1 > 180 -> h2 - h1 - 360
            else -> h2 - h1 + 360
        }
        val dH = 2 * sqrt(c1p * c2p) * sin(Math.toRadians(dh / 2))
        val lm = (p.l + q.l) / 2
        val cmp = (c1p + c2p) / 2
        val hm = when {
            c1p * c2p == 0.0 -> h1 + h2
            abs(h1 - h2) <= 180 -> (h1 + h2) / 2
            h1 + h2 < 360 -> (h1 + h2 + 360) / 2
            else -> (h1 + h2 - 360) / 2
        }
        val t = 1 - 0.17 * cos(Math.toRadians(hm - 30)) + 0.24 * cos(Math.toRadians(2 * hm)) +
            0.32 * cos(Math.toRadians(3 * hm + 6)) - 0.20 * cos(Math.toRadians(4 * hm - 63))
        val dTheta = 30 * exp(-((hm - 275) / 25).pow(2))
        val rc = 2 * sqrt(cmp.pow(7) / (cmp.pow(7) + 25.0.pow(7)))
        val sl = 1 + 0.015 * (lm - 50).pow(2) / sqrt(20 + (lm - 50).pow(2))
        val sc = 1 + 0.045 * cmp
        val sh = 1 + 0.015 * cmp * t
        val rt = -sin(Math.toRadians(2 * dTheta)) * rc
        return sqrt((dl / sl).pow(2) + (dc / sc).pow(2) + (dH / sh).pow(2) + rt * (dc / sc) * (dH / sh))
    }

    /** Correlated colour temperature from chromaticity (McCamy 1992), valid for about 2000-12500 K. */
    fun cct(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        return 449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33
    }
}
