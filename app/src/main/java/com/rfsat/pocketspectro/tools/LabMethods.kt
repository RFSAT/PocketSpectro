package com.rfsat.pocketspectro.tools

import kotlin.math.abs

/**
 * Standard absorbance methods for a cuvette: drink colour (beer EBC/SRM, wine colour intensity and
 * hue, honey Pfund grade), olive-oil UV indices (K232, K268, ΔK) and water quality in the UV
 * (nitrate, UV254 and SUVA). All take an absorbance spectrum against the blank.
 */
object Drinks {

    enum class Kind { BEER, WINE, HONEY }

    data class Beer(val ebc: Double, val srm: Double, val a430: Double, val a700: Double, val turbid: Boolean)

    /** EBC / ASBC methods: 10 mm path; EBC = 25 × A430 × dilution, SRM = 12.7 × A430 × dilution. */
    fun beer(wl: DoubleArray, abs: DoubleArray, pathMm: Double = 10.0, dilution: Double = 1.0): Beer? {
        if (!Spectra.covers(wl, 425.0, 705.0)) return null
        val k = 10.0 / pathMm * dilution
        val a430 = Spectra.bandMean(wl, abs, 429.0, 431.0) * k
        val a700 = Spectra.bandMean(wl, abs, 699.0, 701.0) * k
        // ASBC: the beer is clear enough when A700 ≤ 0.039 × A430.
        return Beer(25 * a430, 12.7 * a430, a430, a700, a700 > 0.039 * a430)
    }

    data class Wine(val intensity: Double, val hue: Double, val a420: Double, val a520: Double, val a620: Double,
                    /** Share of red, yellow and blue (Glories), %. */ val red: Double, val yellow: Double, val blue: Double)

    /** OIV colour intensity (A420 + A520 + A620) and hue (A420 / A520), reported for a 10 mm path. */
    fun wine(wl: DoubleArray, abs: DoubleArray, pathMm: Double = 1.0): Wine? {
        if (!Spectra.covers(wl, 415.0, 625.0)) return null
        val k = 10.0 / pathMm
        val a420 = Spectra.bandMean(wl, abs, 419.0, 421.0) * k
        val a520 = Spectra.bandMean(wl, abs, 519.0, 521.0) * k
        val a620 = Spectra.bandMean(wl, abs, 619.0, 621.0) * k
        val ci = a420 + a520 + a620
        return Wine(ci, if (a520 > 0) a420 / a520 else Double.NaN, a420, a520, a620,
            100 * a520 / ci, 100 * a420 / ci, 100 * a620 / ci)
    }

    enum class HoneyGrade(val maxPfund: Double) {
        WATER_WHITE(8.0), EXTRA_WHITE(17.0), WHITE(34.0), EXTRA_LIGHT_AMBER(50.0), LIGHT_AMBER(85.0), AMBER(114.0), DARK_AMBER(Double.MAX_VALUE)
    }

    data class Honey(val pfundMm: Double, val a635: Double, val grade: HoneyGrade)

    /** 50 % (w/v) honey solution against water, 10 mm: mm Pfund = −38.70 + 371.39 × A635. */
    fun honey(wl: DoubleArray, abs: DoubleArray, pathMm: Double = 10.0): Honey? {
        if (!Spectra.covers(wl, 630.0, 640.0)) return null
        val a = Spectra.bandMean(wl, abs, 634.0, 636.0) * 10.0 / pathMm
        val p = (-38.70 + 371.39 * a).coerceAtLeast(0.0)
        return Honey(p, a, HoneyGrade.entries.first { p <= it.maxPfund })
    }
}

object OliveOil {

    enum class Category { EXTRA_VIRGIN, VIRGIN, ABOVE_VIRGIN_LIMITS }

    data class Result(val k232: Double, val k264: Double, val k268: Double, val k272: Double, val deltaK: Double, val category: Category)

    /**
     * Specific extinction K_λ = A_λ / (c · s), c in g/100 mL (usually 1 g in 100 mL iso-octane), s path in cm.
     * Limits of Regulation (EEC) 2568/91 as amended: extra virgin K232 ≤ 2.50, K268 ≤ 0.22, |ΔK| ≤ 0.01;
     * virgin K232 ≤ 2.60, K268 ≤ 0.25, |ΔK| ≤ 0.01.
     */
    fun analyse(wl: DoubleArray, abs: DoubleArray, gPer100mL: Double = 1.0, pathCm: Double = 1.0): Result? {
        if (!Spectra.covers(wl, 230.0, 274.0)) return null
        val k = 1.0 / (gPer100mL * pathCm)
        fun at(nm: Double) = Spectra.bandMean(wl, abs, nm - 0.6, nm + 0.6) * k
        val k232 = at(232.0); val k264 = at(264.0); val k268 = at(268.0); val k272 = at(272.0)
        val dk = k268 - (k264 + k272) / 2
        val cat = when {
            k232 <= 2.50 && k268 <= 0.22 && abs(dk) <= 0.01 -> Category.EXTRA_VIRGIN
            k232 <= 2.60 && k268 <= 0.25 && abs(dk) <= 0.01 -> Category.VIRGIN
            else -> Category.ABOVE_VIRGIN_LIMITS
        }
        return Result(k232, k264, k268, k272, dk, cat)
    }
}

object WaterUv {

    /** Default nitrate response: absorbance at 220 nm per mg/L nitrate-nitrogen, 10 mm path. */
    const val NITRATE_A220_PER_MG_N = 0.236

    data class Result(
        val a220: Double, val a254: Double, val a275: Double,
        /** Nitrate-nitrogen from A220 − 2·A275 (Standard Methods 4500-NO3⁻ B), mg/L. */
        val nitrateN: Double,
        /** UV254 absorption coefficient, 1/m. */
        val uv254: Double,
        /** Specific UV absorbance (L mg⁻¹ m⁻¹) when DOC is given. */
        val suva: Double?,
    ) {
        val nitrate get() = nitrateN * 4.427
    }

    fun analyse(wl: DoubleArray, abs: DoubleArray, pathMm: Double = 10.0, factor: Double = NITRATE_A220_PER_MG_N, docMgL: Double? = null): Result? {
        if (!Spectra.covers(wl, 218.0, 277.0)) return null
        val k = 10.0 / pathMm
        val a220 = Spectra.bandMean(wl, abs, 219.0, 221.0) * k
        val a254 = Spectra.bandMean(wl, abs, 253.0, 255.0) * k
        val a275 = Spectra.bandMean(wl, abs, 274.0, 276.0) * k
        val n = ((a220 - 2 * a275) / factor).coerceAtLeast(0.0)
        val uv = a254 * 100.0
        return Result(a220, a254, a275, n, uv, docMgL?.takeIf { it > 0 }?.let { uv / it })
    }

    /** New A220-per-mg/L factor from a standard of [knownN] mg/L nitrate-nitrogen. */
    fun factorFromStandard(r: Result, knownN: Double) = if (knownN > 0) (r.a220 - 2 * r.a275) / knownN else null
}
