package com.rfsat.pocketspectro.tools

import kotlin.math.ln
import kotlin.math.log10

/** Beer-Lambert concentration: calibration line from standards, then the unknown. */
object BeerLambert {

    data class Standard(val concentration: Double, val absorbance: Double)

    data class Calibration(val fit: Spectra.LineFit, val n: Int) {
        /** Concentration for absorbance [a]; NaN for a flat calibration. */
        fun concentration(a: Double) = if (fit.slope != 0.0) (a - fit.intercept) / fit.slope else Double.NaN
    }

    fun calibrate(stds: List<Standard>): Calibration? {
        if (stds.size < 2 || stds.map { it.concentration }.distinct().size < 2) return null
        val f = Spectra.lineFit(stds.map { it.concentration }.toDoubleArray(), stds.map { it.absorbance }.toDoubleArray()) ?: return null
        return Calibration(f, stds.size)
    }

    /** Absorbance -log10(sample / blank) averaged over [centre] ± [halfWidth] nm. */
    fun absorbance(wl: DoubleArray, sample: DoubleArray, blank: DoubleArray, centre: Double, halfWidth: Double): Double {
        val s = Spectra.bandMean(wl, sample, centre - halfWidth, centre + halfWidth)
        val b = Spectra.bandMean(wl, blank, centre - halfWidth, centre + halfWidth)
        return if (s > 0 && b > 0) -log10(s / b) else Double.NaN
    }

    /** Wavelength of the strongest absorption in a spectrum (for choosing the analysis wavelength). */
    fun peakAbsorbanceNm(wl: DoubleArray, sample: DoubleArray, blank: DoubleArray): Double? {
        var best = -1; var bestA = Double.NEGATIVE_INFINITY
        // Ignore the noisy ends where the blank is weak.
        val bmax = blank.maxOrNull() ?: return null
        for (i in wl.indices) {
            if (blank[i] < 0.08 * bmax || sample[i] <= 0) continue
            val a = -log10(sample[i] / blank[i])
            if (a > bestA) { bestA = a; best = i }
        }
        return if (best >= 0) wl[best] else null
    }
}

/** Time series of one quantity, with a first-order (exponential) rate fit for kinetics. */
object Kinetics {

    data class Point(val tSec: Double, val value: Double)

    data class FirstOrder(val k: Double, val halfLifeSec: Double, val r2: Double, val v0: Double) {
        fun value(tSec: Double) = v0 * kotlin.math.exp(-k * tSec)
    }

    /** Fits value(t) = v0 exp(-k t) through ln(value); needs positive values. */
    fun firstOrder(points: List<Point>): FirstOrder? {
        val p = points.filter { it.value > 0 && it.value.isFinite() }
        if (p.size < 5) return null
        val fit = Spectra.lineFit(p.map { it.tSec }.toDoubleArray(), p.map { ln(it.value) }.toDoubleArray()) ?: return null
        val k = -fit.slope
        return FirstOrder(k, if (k > 0) ln(2.0) / k else Double.POSITIVE_INFINITY, fit.r2, kotlin.math.exp(fit.intercept))
    }

    /** Initial rate: slope of the first [window] seconds (value per second). */
    fun initialRate(points: List<Point>, window: Double = 30.0): Double? {
        if (points.size < 3) return null
        val t0 = points.first().tSec
        val p = points.filter { it.tSec - t0 <= window }
        return Spectra.lineFit(p.map { it.tSec }.toDoubleArray(), p.map { it.value }.toDoubleArray())?.slope
    }
}

/**
 * Light-source metrics: peak, width, centroid; with the visible range covered, chromaticity and
 * colour temperature. Raw counts carry the detector's own spectral response, so colour figures are
 * corrected with a response curve when one has been taken from a lamp of known temperature
 * ([responseFromLamp]); lux and PPFD additionally need a scale factor from a reference meter.
 */
object LightMetrics {

    data class Result(
        val peak: Spectra.Peak?,
        val xy: Pair<Double, Double>?,
        val cct: Double?,
        val parFraction: Double?,
        val relLux: Double?,
        val relPpfd: Double?,
    )

    /** Relative response = counts / Planck(T): divide later measurements by it. */
    fun responseFromLamp(wl: DoubleArray, counts: DoubleArray, kelvin: Double): DoubleArray {
        val p = DoubleArray(wl.size) { Spectra.planck(wl[it], kelvin) }
        val pm = p.max()
        val r = DoubleArray(wl.size) { (counts[it] / (p[it] / pm)).coerceAtLeast(0.0) }
        val rm = r.max().takeIf { it > 0 } ?: 1.0
        return DoubleArray(wl.size) { (r[it] / rm).coerceAtLeast(1e-3) }
    }

    fun corrected(counts: DoubleArray, response: DoubleArray?): DoubleArray =
        if (response == null || response.size != counts.size) counts else DoubleArray(counts.size) { counts[it] / response[it] }

    fun analyse(wl: DoubleArray, spd: DoubleArray): Result {
        val pk = Spectra.peak(wl, spd)
        val vis = Colorimetry.supported(wl)
        var xy: Pair<Double, Double>? = null; var cct: Double? = null; var lux: Double? = null
        if (vis) {
            val c = Colorimetry.emissionXYZ(wl, spd)
            xy = c.chromaticity.takeIf { it.first.isFinite() }
            cct = xy?.let { Colorimetry.cct(it.first, it.second) }?.takeIf { it in 1500.0..20000.0 }
            var y = 0.0
            for (l in Spectra.grid(380.0, 780.0, 5.0)) y += Spectra.at(wl, spd, l).coerceAtLeast(0.0) * Colorimetry.yBar(l)
            lux = 683.0 * y * 5.0
        }
        // Photons in 400-700 nm (PAR) versus the whole range: E/hc ∝ λ.
        var par: Double? = null; var ppfd: Double? = null
        if (Spectra.covers(wl, 400.0, 700.0)) {
            var inPar = 0.0; var all = 0.0
            for (i in 1 until wl.size) {
                val q = spd[i].coerceAtLeast(0.0) * wl[i] * (wl[i] - wl[i - 1])
                all += q; if (wl[i] in 400.0..700.0) inPar += q
            }
            par = if (all > 0) inPar / all else null
            ppfd = inPar * 1e-9 / (6.62607e-34 * 2.99792e8 * 6.02214e23) * 1e6
        }
        return Result(pk, xy, cct, par, lux, ppfd)
    }
}
