package com.rfsat.pocketspectro.tools

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Thickness of a transparent film or coating from the interference fringes in its reflectance
 * (or transmittance) spectrum. Fringes are evenly spaced in wavenumber with a period 1/(2 n d);
 * the Fourier transform of the spectrum against 1/λ finds that period, and a thin-film model fit
 * refines it (and handles films too thin to show a full fringe).
 */
object FilmThickness {

    enum class Substrate(val n: Double?) { GLASS(1.52), SILICON(3.7), WATER(1.333), METAL(12.0), UNKNOWN(null) }

    enum class Method { FOURIER, MODEL }

    data class Result(val nm: Double, val method: Method, val fringes: Double, val fitR2: Double?) {
        val micrometres get() = nm / 1000.0
    }

    /** Normal-incidence reflectance of a film (index [n], thickness [d] nm) on a substrate of index [ns]. */
    fun model(l: Double, n: Double, d: Double, ns: Double): Double {
        val r01 = (1 - n) / (1 + n); val r12 = (n - ns) / (n + ns)
        val ph = -4 * PI * n * d / l
        val er = cos(ph); val ei = sin(ph)
        // (r01 + r12 e) / (1 + r01 r12 e)
        val nr = r01 + r12 * er; val ni = r12 * ei
        val dr = 1 + r01 * r12 * er; val di = r01 * r12 * ei
        return (nr * nr + ni * ni) / (dr * dr + di * di)
    }

    fun measure(wl: DoubleArray, r: DoubleArray, n: Double, sub: Substrate, lo: Double = wl.first(), hi: Double = wl.last()): Result? {
        val idx = wl.indices.filter { wl[it] in lo..hi && r[it].isFinite() }
        if (idx.size < 64) return null
        val x = DoubleArray(idx.size) { wl[idx[it]] }; val y = DoubleArray(idx.size) { r[idx[it]] }
        val kMin = 1 / x.last(); val kMax = 1 / x.first(); val span = kMax - kMin
        val fourier = fourierThickness(x, y, n)
        val ns = sub.n
        if (ns == null) return fourier?.let { Result(it, Method.FOURIER, 2 * n * it * span, null) }
        val (dLo, dHi, step) = if (fourier != null) Triple(fourier * 0.75, fourier * 1.25, fourier * 0.002) else Triple(15.0, 1.6 / (2 * n * span), 1.0)
        var best = Double.NaN; var bestR2 = -1.0
        var d = dLo
        while (d <= dHi) {
            val m = DoubleArray(x.size) { model(x[it], n, d, ns) }
            val c = Spectra.correlation(m, y)
            if (c > bestR2) { bestR2 = c; best = d }
            d += step
        }
        if (best.isNaN() || bestR2 < 0.5) return fourier?.let { Result(it, Method.FOURIER, 2 * n * it * span, null) }
        return Result(best, if (fourier != null) Method.FOURIER else Method.MODEL, 2 * n * best * span, bestR2 * bestR2)
    }

    /** Fourier estimate (nm), or null when fewer than 1.5 fringes are visible. */
    fun fourierThickness(x: DoubleArray, y: DoubleArray, n: Double): Double? {
        val kMin = 1 / x.last(); val kMax = 1 / x.first(); val span = kMax - kMin
        val m = 1024
        val k = DoubleArray(m) { kMin + span * it / (m - 1) }
        val v = DoubleArray(m) { Spectra.at(x, y, 1 / k[it]) }
        // Remove the slow trend (quadratic) and taper the ends.
        val ks = DoubleArray(m) { 2.0 * it / (m - 1) - 1 }
        val c = Spectra.polyFit(ks, v, 2) ?: return null
        val pad = 16384
        val re = DoubleArray(pad); val im = DoubleArray(pad)
        for (i in 0 until m) re[i] = (v[i] - Spectra.polyVal(c, ks[i])) * (0.5 - 0.5 * cos(2 * PI * i / (m - 1)))
        fft(re, im)
        val dk = span / (m - 1)
        val fMin = 1.5 / span
        var best = -1; var bestP = 0.0; var total = 0.0; var cnt = 0
        for (j in 1 until pad / 2) {
            val f = j / (pad * dk)
            if (f < fMin) continue
            // Fringes finer than ~4 pixels are beyond the spectrometer's resolution.
            if (f > (m / 8) / span) break
            val p = re[j] * re[j] + im[j] * im[j]
            total += p; cnt++
            if (p > bestP) { bestP = p; best = j }
        }
        if (best <= 0 || cnt == 0 || bestP < 12 * total / cnt) return null
        val a = mag(re, im, best - 1); val b = mag(re, im, best); val cc = mag(re, im, best + 1)
        val den = a - 2 * b + cc
        val off = if (den != 0.0) (0.5 * (a - cc) / den).coerceIn(-0.5, 0.5) else 0.0
        val f = (best + off) / (pad * dk)
        return f / (2 * n)
    }

    private fun mag(re: DoubleArray, im: DoubleArray, j: Int) = sqrt(re[j] * re[j] + im[j] * im[j])

    /** In-place radix-2 FFT (size must be a power of two). */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Transmittance test for filters and eyewear: luminous transmittance and sunglass category
 * (ISO 12312-1), UV and blue-light blocking, cut-on wavelength and optical density at a chosen
 * wavelength (laser goggles). Input: transmittance against the open beam (blank).
 */
object FilterTest {

    data class Result(
        /** Luminous transmittance τv (D65, 2°), %; null when 380–780 nm is not covered. */
        val luminousPercent: Double?,
        val sunglassCategory: Int?,
        /** Mean transmittance 315–380 nm (UV-A), %. */
        val uvaPercent: Double?,
        /** Mean transmittance 380–400 nm, %; UV400 (blocks 99 % of UV up to 400 nm) when ≤ 1 %. */
        val uv380to400Percent: Double?,
        /** Mean transmittance 415–455 nm (high-energy blue), %. */
        val bluePercent: Double?,
        /** First wavelength where transmittance rises through 50 % (long-pass edge), nm. */
        val cutOnNm: Double?,
        val minPercent: Double, val minNm: Double,
        val meanPercent: Double,
    ) {
        val uv400 get() = uv380to400Percent?.let { it <= 1.0 }
    }

    fun analyse(wl: DoubleArray, t: DoubleArray): Result {
        // Noise can make a blocked band read slightly below zero: transmittance is reported from 0 %.
        fun mean(lo: Double, hi: Double) = if (Spectra.covers(wl, lo, hi)) (100 * Spectra.bandMean(wl, t, lo, hi)).coerceAtLeast(0.0) else null
        val lum = if (Colorimetry.supported(wl)) {
            var a = 0.0; var b = 0.0; var l = 380.0
            while (l <= 780.0) { val w = Colorimetry.d65(l) * Colorimetry.yBar(l); a += w * Spectra.at(wl, t, l); b += w; l += 5.0 }
            100 * a / b
        } else null
        val cat = lum?.let { when { it > 80 -> 0; it > 43 -> 1; it > 18 -> 2; it > 8 -> 3; it > 3 -> 4; else -> 5 } }
        val sm = Spectra.smooth(t, 2)
        var cut: Double? = null
        for (i in 1 until wl.size) if (sm[i - 1] < 0.5 && sm[i] >= 0.5) { cut = wl[i - 1] + (0.5 - sm[i - 1]) / (sm[i] - sm[i - 1]) * (wl[i] - wl[i - 1]); break }
        var iMin = 0; for (i in wl.indices) if (sm[i] < sm[iMin]) iMin = i
        return Result(lum, cat, mean(315.0, 380.0), mean(380.0, 400.0), mean(415.0, 455.0), cut, (100 * sm[iMin]).coerceAtLeast(0.0), wl[iMin], 100 * Spectra.bandMean(wl, sm, wl.first(), wl.last()))
    }

    data class Density(val od: Double, val atLimit: Boolean)

    /** Optical density at [nm]; when the signal is at the noise floor, the result is a lower bound. */
    fun opticalDensity(wl: DoubleArray, counts: DoubleArray, blank: DoubleArray, nm: Double, halfWidth: Double = 2.0): Density? {
        val s = Spectra.bandMean(wl, counts, nm - halfWidth, nm + halfWidth)
        val b = Spectra.bandMean(wl, blank, nm - halfWidth, nm + halfWidth)
        if (b <= 0) return null
        // Noise floor: 3 σ of the band mean, σ from the pixels around the wavelength (read noise where the filter blocks).
        val local = wl.indices.filter { kotlin.math.abs(wl[it] - nm) <= 25.0 }.map { counts[it] }.toDoubleArray()
        val k = wl.count { it in nm - halfWidth..nm + halfWidth }.coerceAtLeast(1)
        val floor = (3 * (Spectra.noise(local).takeIf { it.isFinite() } ?: 1.0) / sqrt(k.toDouble())).coerceAtLeast(0.5)
        return if (s <= floor) Density(log10(b / floor), true) else Density(log10(b / s), false)
    }

}
