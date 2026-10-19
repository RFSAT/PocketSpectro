package com.rfsat.pocketspectro.tools

import kotlin.math.exp
import kotlin.math.pow

/**
 * Non-visual effects of light. Melanopic quantities follow CIE S 026:2018: the melanopic action
 * spectrum is rebuilt here from its definition (Govardovskii opsin template, λmax 480 nm, filtered by
 * the lens of a 32-year-old observer, van de Kraats & van Norren 2007). It reproduces the standard's
 * equal-energy ratio (0.906) and D65 efficacy (1.326 mW/lm) to within 0.3 %.
 * Blue-light hazard uses the B(λ) weighting of ICNIRP / IEC 62471.
 */
object LightHealth {

    private fun govardovskii(l: Double, lm: Double = 480.0): Double {
        val x = lm / l
        val a = 0.8795 + 0.0459 * exp(-(lm - 300).pow(2) / 11940)
        val alpha = 1 / (exp(69.7 * (a - x)) + exp(28.0 * (0.922 - x)) + exp(-14.9 * (1.104 - x)) + 0.674)
        val lmb = 189 + 0.315 * lm; val bb = -40.5 + 0.195 * lm
        return alpha + 0.26 * exp(-((l - lmb) / bb).pow(2))
    }

    private fun lensDensity(l: Double, age: Double = 32.0): Double =
        (0.446 + 0.000031 * age * age) * (400 / l).pow(4) +
            14.19 * 10.68 * exp(-(0.057 * (l - 273)).pow(2)) +
            (0.998 - 0.000063 * age * age) * 2.13 * exp(-(0.029 * (l - 370)).pow(2)) +
            (0.059 + 0.000186 * age * age) * 11.95 * exp(-(0.021 * (l - 325)).pow(2)) +
            (0.016 + 0.000132 * age * age) * 1.43 * exp(-(0.008 * (l - 325)).pow(2)) + 0.111

    private val GRID = Spectra.grid(380.0, 780.0, 1.0)
    private val melRaw = DoubleArray(GRID.size) { govardovskii(GRID[it]) * 10.0.pow(-lensDensity(GRID[it])) }
    private val melMax = melRaw.max()

    /** Melanopic action spectrum s_mel(λ), 1 at its peak (490 nm). */
    fun melanopic(l: Double): Double = if (l < 380 || l > 780) 0.0 else Spectra.at(GRID, melRaw, l) / melMax

    /** Blue-light hazard weighting B(λ) (ICNIRP), 300–700 nm. */
    fun blueHazard(l: Double): Double = when {
        l < 380 -> 0.01 * 10.0.pow((l - 380) / 30.0).coerceAtMost(1.0)
        l <= 500 -> Spectra.at(BLH_NM, BLH, l)
        l <= 600 -> 10.0.pow((450 - l) / 50.0)
        l <= 700 -> 0.001
        else -> 0.0
    }

    private val BLH_NM = Spectra.grid(380.0, 500.0, 5.0)
    private val BLH = doubleArrayOf(0.01, 0.013, 0.025, 0.05, 0.10, 0.20, 0.40, 0.80, 0.90, 0.95, 0.98, 1.0, 1.0,
        0.97, 0.94, 0.90, 0.80, 0.70, 0.62, 0.55, 0.45, 0.40, 0.22, 0.16, 0.10)

    data class Result(
        /** Melanopic daylight efficacy ratio (D65 = 1). Melanopic EDI = illuminance × DER. */
        val melanopicDer: Double,
        /** Melanopic efficacy of luminous radiation, mW/lm. */
        val melanopicElr: Double,
        /** Blue-light hazard efficacy of luminous radiation, mW/lm. */
        val blueHazardElr: Double,
        /** Share of radiant power between 380 and 500 nm, %. */
        val bluePercent: Double,
    )

    private val d65Ratio: Double by lazy {
        var m = 0.0; var v = 0.0
        for (l in GRID) { m += Colorimetry.d65(l) * melanopic(l); v += Colorimetry.d65(l) * Colorimetry.yBar(l) }
        m / v
    }

    /** From a spectral power distribution (relative units, response-corrected) covering 380–780 nm. */
    fun analyse(wl: DoubleArray, spd: DoubleArray): Result? {
        if (!Colorimetry.supported(wl)) return null
        var m = 0.0; var v = 0.0; var b = 0.0; var blue = 0.0; var all = 0.0
        for (l in GRID) {
            val e = Spectra.at(wl, spd, l).coerceAtLeast(0.0)
            m += e * melanopic(l); v += e * Colorimetry.yBar(l); b += e * blueHazard(l)
            all += e; if (l <= 500) blue += e
        }
        if (v <= 0) return null
        return Result((m / v) / d65Ratio, 1000 * m / (683 * v), 1000 * b / (683 * v), if (all > 0) 100 * blue / all else Double.NaN)
    }
}
