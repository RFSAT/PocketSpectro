package com.rfsat.pocketspectro.tools

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Spectral (multi-wavelength) pyrometry: the temperature of a glowing grey body from the shape of its
 * thermal emission. A grey body's emissivity does not change the shape, so it cancels. The spectrometer's
 * own response must be divided out first (lamp calibration, as in the Light tool), otherwise the
 * fitted temperature describes the detector rather than the source.
 */
object Pyrometry {

    data class Result(
        val kelvin: Double,
        /** Goodness of fit of the log spectrum (1 = perfect grey body). */
        val r2: Double,
        val pixels: Int,
        /** Wavelength span used, nm. */
        val fromNm: Double,
        val toNm: Double,
    ) {
        val celsius get() = kelvin - 273.15
        /** A fit hotter than ~4000 K is sunlight (5800 K) or a lamp, not a flame. */
        val sunLike get() = kelvin > 4000.0
    }

    /** Wavelength bands to leave out of flame fits: K I lines with the O2 A band, and water vapour. */
    val FIRE_EXCLUDE = listOf(755.0..775.0, 585.0..595.0, 925.0..965.0, 810.0..830.0)

    /**
     * Fits Planck's law to [signal] / [response] between [lo] and [hi] nm, skipping [exclude] bands,
     * saturated or weak pixels (below [minFraction] of the maximum).
     */
    fun fit(
        wl: DoubleArray, signal: DoubleArray, response: DoubleArray?, lo: Double = wl.first(), hi: Double = wl.last(),
        exclude: List<ClosedFloatingPointRange<Double>> = emptyList(), minFraction: Double = 0.04,
        saturated: BooleanArray? = null,
    ): Result? {
        // Smoothed first: choosing pixels on noisy values would favour upward noise in the weak tail and bias T high.
        val y = Spectra.smooth(DoubleArray(wl.size) { i ->
            val r = response?.getOrNull(i) ?: 1.0
            if (r > 1e-3) signal[i] / r else Double.NaN
        }, 4)
        var ymax = 0.0
        for (i in wl.indices) if (wl[i] in lo..hi && exclude.none { wl[i] in it } && y[i].isFinite()) ymax = maxOf(ymax, y[i])
        if (ymax <= 0) return null
        val idx = wl.indices.filter { i ->
            wl[i] in lo..hi && exclude.none { wl[i] in it } && y[i].isFinite() && y[i] > minFraction * ymax && saturated?.get(i) != true
        }
        if (idx.size < 12) return null
        val x = DoubleArray(idx.size) { wl[idx[it]] }
        val ly = DoubleArray(idx.size) { ln(y[idx[it]]) }
        val w = DoubleArray(idx.size) { y[idx[it]] / ymax }

        fun cost(t: Double): Double {
            var sw = 0.0; var sr = 0.0
            val r = DoubleArray(x.size) { ly[it] - ln(Spectra.planck(x[it], t)) }
            for (i in r.indices) { sw += w[i]; sr += w[i] * r[i] }
            val c = sr / sw
            var e = 0.0
            for (i in r.indices) { val d = r[i] - c; e += w[i] * d * d }
            return e
        }
        // Golden-section search on log T between 500 K and 20 000 K, after a coarse scan.
        var best = 500.0; var bestC = Double.MAX_VALUE
        var t = 500.0
        while (t < 20_000.0) { val c = cost(t); if (c < bestC) { bestC = c; best = t }; t *= 1.04 }
        var a = ln(best / 1.05); var b = ln(best * 1.05)
        val g = (sqrt(5.0) - 1) / 2
        var c1 = b - g * (b - a); var c2 = a + g * (b - a)
        var f1 = cost(exp(c1)); var f2 = cost(exp(c2))
        repeat(60) {
            if (f1 < f2) { b = c2; c2 = c1; f2 = f1; c1 = b - g * (b - a); f1 = cost(exp(c1)) }
            else { a = c1; c1 = c2; f1 = f2; c2 = a + g * (b - a); f2 = cost(exp(c2)) }
        }
        val tk = exp((a + b) / 2)
        // Weighted R² in log space.
        var sw = 0.0; var sm = 0.0
        for (i in ly.indices) { sw += w[i]; sm += w[i] * ly[i] }
        val mean = sm / sw
        var tot = 0.0
        for (i in ly.indices) { val d = ly[i] - mean; tot += w[i] * d * d }
        val r2 = if (tot > 0) 1 - cost(tk) / tot else 1.0
        return Result(tk, r2, idx.size, x.first(), x.last())
    }
}
