package com.rfsat.pocketspectro.tools

import kotlin.math.pow

/**
 * UV index from a solar UV spectrum (ISO 17166 / CIE erythemal action spectrum):
 * UVI = 40 m²/W × ∫ E(λ) · w(λ) dλ over 250–400 nm, E in W m⁻² nm⁻¹.
 * The spectrometer's counts become irradiance through one scale factor taken against a reference
 * UV index (a calibrated meter, or an official measurement close by at the same time).
 */
object UvIndex {

    /** CIE erythemal action spectrum. */
    fun erythemal(l: Double): Double = when {
        l < 250 || l > 400 -> 0.0
        l <= 298 -> 1.0
        l <= 328 -> 10.0.pow(0.094 * (298 - l))
        else -> 10.0.pow(0.015 * (140 - l))
    }

    data class Result(
        /** Erythemally weighted signal (relative units). */
        val weighted: Double,
        /** UV-B (280–315 nm) share of the UV (280–400 nm) signal, %. */
        val uvbPercent: Double,
        /** Share of the erythemal effect coming from UV-B, %. */
        val erythemaFromUvb: Double,
        /** UV index once a scale is set. */
        val uvi: Double?,
    )

    fun supported(wl: DoubleArray) = Spectra.covers(wl, 290.0, 400.0)

    /** [spd] is dark-corrected counts per second (response corrected when available). */
    fun analyse(wl: DoubleArray, spd: DoubleArray, scale: Double?): Result? {
        if (!supported(wl)) return null
        var w = 0.0; var wB = 0.0; var uv = 0.0; var uvb = 0.0
        var l = 290.0
        while (l <= 400.0) {
            val e = Spectra.at(wl, spd, l).coerceAtLeast(0.0)
            val ew = e * erythemal(l)
            w += ew; uv += e
            if (l <= 315) { wB += ew; uvb += e }
            l += 1.0
        }
        return Result(w, if (uv > 0) 100 * uvb / uv else Double.NaN, if (w > 0) 100 * wB / w else Double.NaN, scale?.let { it * w })
    }

    /** Scale factor (UVI per weighted unit) from a reference UV index. */
    fun scaleFrom(r: Result, referenceUvi: Double) = if (r.weighted > 0 && referenceUvi > 0) referenceUvi / r.weighted else null

    enum class Risk(val upTo: Double) { LOW(2.5), MODERATE(5.5), HIGH(7.5), VERY_HIGH(10.5), EXTREME(Double.MAX_VALUE) }

    fun risk(uvi: Double) = Risk.entries.first { uvi < it.upTo }
}
