package com.rfsat.pocketspectro.tools

import kotlin.math.max

/**
 * Recognises the lamp technology from its emission spectrum, for street-light and light-pollution
 * surveys: low- and high-pressure sodium, mercury vapour, metal halide, fluorescent, LED and
 * incandescent/halogen. Uses spectral fingerprints that survive an uncorrected detector response:
 * the sodium D lines (and their self-reversal in high-pressure lamps), mercury lines at 436/546 nm,
 * the europium line at 611 nm of fluorescent phosphors, the blue pump peak of white LEDs, and the
 * smooth continuum of thermal sources.
 */
object LampClassifier {

    enum class Type(val colour: String) {
        LOW_PRESSURE_SODIUM("#FF8F00"),
        HIGH_PRESSURE_SODIUM("#F4511E"),
        MERCURY("#5E35B1"),
        METAL_HALIDE("#00897B"),
        FLUORESCENT("#43A047"),
        LED("#1E88E5"),
        INCANDESCENT("#FDD835"),
        DAYLIGHT("#90CAF9"),
        UNKNOWN("#757575"),
    }

    data class Features(
        val naCore: Double, val naBroad: Double, val naDip: Double,
        val hg436: Double, val hg546: Double, val eu611: Double,
        val lines: Int, val ledBlue: Boolean, val maxContrast: Double,
    )

    data class Result(val type: Type, val features: Features)

    fun supported(wl: DoubleArray) = Spectra.covers(wl, 430.0, 620.0)

    /** [spd]: dark-corrected counts (response-corrected when available, which also allows telling daylight apart). */
    fun classify(wl: DoubleArray, spd: DoubleArray, cct: Double? = null): Result? {
        if (!supported(wl)) return null
        val lo = maxOf(400.0, wl.first()); val hi = minOf(700.0, wl.last())
        var total = 0.0; var core = 0.0; var broad = 0.0; var smax = 0.0
        for (i in wl.indices) {
            val l = wl[i]; val v = spd[i].coerceAtLeast(0.0)
            if (l in lo..hi) { total += v; smax = max(smax, v) }
            if (l in 586.0..592.5) core += v
            if (l in 560.0..620.0) broad += v
        }
        if (total <= 0 || smax <= 0) return null
        val floor = 0.02 * smax
        fun contrast(c: Double): Double {
            if (!Spectra.covers(wl, c - 9, c + 9)) return 0.0
            val p = Spectra.bandMean(wl, spd, c - 0.8, c + 0.8).let { v -> wl.indices.filter { kotlin.math.abs(wl[it] - c) <= 1.5 }.maxOfOrNull { spd[it] } ?: v }
            val left = wl.indices.filter { wl[it] in c - 9..c - 4 }.minOfOrNull { spd[it] } ?: 0.0
            val right = wl.indices.filter { wl[it] in c + 4..c + 9 }.minOfOrNull { spd[it] } ?: 0.0
            val cont = (left + right) / 2
            return ((p - cont) / (cont + floor)).coerceAtLeast(0.0)
        }
        val dip = run {
            val c = Spectra.bandMean(wl, spd, 588.5, 590.0)
            val side = max(Spectra.bandMean(wl, spd, 579.0, 584.0), Spectra.bandMean(wl, spd, 595.0, 600.0))
            if (side > 0) c / side else 1.0
        }
        // Narrow lines anywhere in 400-700 nm.
        var lines = 0; var maxC = 0.0
        val sm = Spectra.smooth(spd, 0)
        for (i in 3 until wl.size - 3) {
            if (wl[i] !in lo..hi) continue
            var top = true
            for (k in i - 3..i + 3) if (k != i && sm[k] > sm[i]) { top = false; break }
            if (!top) continue
            val c = contrast(wl[i])
            maxC = max(maxC, c)
            if (c > 0.35 && sm[i] > 0.04 * smax) lines++
        }
        val blue = Spectra.peak(wl, spd, 435.0, 470.0)
        val led = blue != null && blue.height > 0.15 * smax && blue.fwhmNm in 8.0..40.0 && run {
            val valley = wl.indices.filter { wl[it] in 470.0..505.0 }.minOfOrNull { spd[it] } ?: Double.MAX_VALUE
            val phosphor = wl.indices.filter { wl[it] in 530.0..640.0 }.maxOfOrNull { spd[it] } ?: 0.0
            valley < 0.75 * blue.height && phosphor > 0.25 * blue.height
        }
        val f = Features(core / total, broad / total, dip, contrast(435.8), contrast(546.1), contrast(611.5), lines, led, maxC)
        val type = when {
            f.naCore > 0.45 -> Type.LOW_PRESSURE_SODIUM
            f.naBroad > 0.35 && (f.naDip < 0.9 || f.naCore < 0.2) && f.hg546 < 0.5 && !f.ledBlue -> Type.HIGH_PRESSURE_SODIUM
            f.hg436 > 0.4 && f.hg546 > 0.4 && f.eu611 > 0.4 -> Type.FLUORESCENT
            f.hg436 > 0.4 && f.hg546 > 0.4 -> if (f.lines >= 7) Type.METAL_HALIDE else Type.MERCURY
            f.lines >= 5 -> Type.METAL_HALIDE
            f.ledBlue -> Type.LED
            f.maxContrast < 0.15 -> if (cct != null && cct > 4000) Type.DAYLIGHT else Type.INCANDESCENT
            else -> Type.UNKNOWN
        }
        return Result(type, f)
    }
}
