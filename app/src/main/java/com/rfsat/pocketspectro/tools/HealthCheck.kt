package com.rfsat.pocketspectro.tools

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Spectrometer self-test: dark level and read noise, hot pixels, linearity against integration time,
 * and the wavelength calibration against a mercury-argon lamp. Gives a dated report for maintenance
 * or support.
 */
object HealthCheck {

    data class Dark(
        /** Median dark level, counts. */
        val level: Double,
        /** Median per-pixel standard deviation over the frames (read noise), counts. */
        val noise: Double,
        /** Pixels whose dark level stands far above the rest. */
        val hotPixels: List<Int>,
        val frames: Int,
        val integrationMs: Double,
    )

    /** Statistics of [frames] raw dark scans (shutter closed or input covered). */
    fun dark(frames: List<DoubleArray>, integrationMs: Double): Dark? {
        if (frames.size < 3) return null
        val n = frames[0].size
        val mean = DoubleArray(n) { j -> frames.sumOf { it[j] } / frames.size }
        val sd = DoubleArray(n) { j -> sqrt(frames.sumOf { (it[j] - mean[j]) * (it[j] - mean[j]) } / (frames.size - 1)) }
        val med = Spectra.median(mean)
        val mad = Spectra.median(DoubleArray(n) { abs(mean[it] - med) }) * 1.4826
        val noise = Spectra.median(sd)
        val limit = med + maxOf(10 * mad, 8 * noise, 30.0)
        return Dark(med, noise, (0 until n).filter { mean[it] > limit }, frames.size, integrationMs)
    }

    data class LinearityPoint(val integrationMs: Double, val signal: Double)

    data class Linearity(
        val points: List<LinearityPoint>,
        /** Counts per millisecond at each step. */
        val ratePerMs: List<Double>,
        /** Largest deviation of the per-millisecond rate from the mean, %. */
        val maxDeviationPercent: Double,
    )

    fun linearity(points: List<LinearityPoint>): Linearity? {
        val p = points.filter { it.integrationMs > 0 && it.signal > 0 }
        if (p.size < 3) return null
        val rates = p.map { it.signal / it.integrationMs }
        val m = rates.average()
        return Linearity(p, rates, rates.maxOf { abs(it - m) / m * 100 })
    }

    data class Wavelength(val lines: Int, val meanShiftNm: Double, val maxShiftNm: Double)

    /** Shift of the mercury-argon lines found (needs the lamp in front of the input). */
    fun wavelength(wl: DoubleArray, counts: DoubleArray): Wavelength? {
        val m = EmissionLines.measure(wl, counts, EmissionLines.Config(EmissionLines.Preset.LAMP)).filter { it.present && it.shiftNm.isFinite() }
        if (m.size < 2) return null
        return Wavelength(m.size, m.map { it.shiftNm }.average(), m.maxOf { abs(it.shiftNm) })
    }

    /** Pass limits used in the report. */
    const val MAX_LINEARITY_DEV = 2.0
    const val MAX_WAVELENGTH_SHIFT = 0.5
}
