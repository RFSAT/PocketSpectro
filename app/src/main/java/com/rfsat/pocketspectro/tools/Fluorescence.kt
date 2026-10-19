package com.rfsat.pocketspectro.tools

/**
 * Sun-induced chlorophyll fluorescence (SIF) by the Fraunhofer Line Discrimination method.
 * Inside an oxygen absorption band sunlight is dim, so the plant's own faint red/far-red glow fills
 * the band in partly. Comparing the depth of the band in the plant spectrum with its depth in the
 * white-reference spectrum separates fluorescence from reflected light (three-band FLD, Maier 2003).
 *
 * At the 1–2 nm resolution of a pocket spectrometer the result is biased low and should be read as a
 * relative indicator: compare plants measured the same way, not with satellite values.
 */
object Fluorescence {

    enum class Band(val inNm: Double, val leftNm: Double, val rightNm: Double, val searchLo: Double, val searchHi: Double) {
        /** O2-A band, far-red fluorescence (F760). */
        O2A(760.5, 754.5, 771.5, 758.5, 763.0),
        /** O2-B band, red fluorescence (F687). */
        O2B(687.0, 684.0, 694.0, 685.5, 689.0),
    }

    data class Result(
        val band: Band,
        /** Fluorescence radiance in the units of the target counts. */
        val f: Double,
        /** Fluorescence relative to the irradiance (white reference) outside the band, % ("apparent reflectance"). */
        val percentOfIrradiance: Double,
        /** Fluorescence as % of the target's reflected radiance outside the band. */
        val percentOfRadiance: Double,
        /** Apparent reflectance outside the band, for context. */
        val reflectance: Double,
        /** Band depth in the white reference (0..1); shallow bands mean poor resolution or no sunlight. */
        val referenceDepth: Double,
    )

    fun supported(wl: DoubleArray, b: Band) = Spectra.covers(wl, b.leftNm - 2, b.rightNm + 2)

    /**
     * [target] and [white] are dark-corrected counts of the plant and of the white panel (reflectance
     * [whiteReflectance]) under the same sunlight.
     */
    fun measure(wl: DoubleArray, target: DoubleArray, white: DoubleArray, b: Band, whiteReflectance: Double = 0.98): Result? {
        if (!supported(wl, b)) return null
        // The bottom of the band in the reference fixes the "in" pixel for both spectra.
        var iIn = -1
        for (i in wl.indices) if (wl[i] in b.searchLo..b.searchHi && (iIn < 0 || white[i] < white[iIn])) iIn = i
        if (iIn < 0) return null
        val lIn = mean3(target, iIn); val eIn = mean3(white, iIn) / whiteReflectance
        val lL = Spectra.bandMean(wl, target, b.leftNm - 1, b.leftNm + 1)
        val lR = Spectra.bandMean(wl, target, b.rightNm - 1, b.rightNm + 1)
        val eL = Spectra.bandMean(wl, white, b.leftNm - 1, b.leftNm + 1) / whiteReflectance
        val eR = Spectra.bandMean(wl, white, b.rightNm - 1, b.rightNm + 1) / whiteReflectance
        val x = wl[iIn]
        val wL = (b.rightNm - x) / (b.rightNm - b.leftNm); val wR = (x - b.leftNm) / (b.rightNm - b.leftNm)
        val eOut = wL * eL + wR * eR; val lOut = wL * lL + wR * lR
        if (eOut <= 0 || eOut - eIn <= 0) return null
        val f = (eOut * lIn - eIn * lOut) / (eOut - eIn)
        val refl = (lOut - f) / eOut
        return Result(b, f, 100 * f / eOut, if (lOut > 0) 100 * f / lOut else Double.NaN, refl, 1 - eIn / eOut)
    }

    private fun mean3(y: DoubleArray, i: Int): Double {
        var s = 0.0; var n = 0
        for (k in i - 1..i + 1) if (k in y.indices) { s += y[k]; n++ }
        return s / n
    }
}
