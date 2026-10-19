package com.rfsat.pocketspectro.sim

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Which spectrometer the simulator pretends to be. */
enum class SimModel(val label: String, val coefficients: DoubleArray) {
    NIR("STS-NIR", doubleArrayOf(636.0, 0.473, -2.0e-5, 0.0)),   // ~636-1099 nm
    VIS("STS-VIS", doubleArrayOf(338.0, 0.46, -1.5e-5, 0.0)),    // ~338-793 nm
    UV("STS-UV", doubleArrayOf(185.0, 0.46, -1.3e-5, 0.0)),      // ~185-642 nm
}

/** Illumination of a reflectance or transmission measurement. */
enum class SimLight { HALOGEN, DAYLIGHT }

/** Surfaces the simulated probe can look at (reflectance 0..1 as a function of wavelength). */
enum class SimTarget {
    WHITE, LEAF_HEALTHY, LEAF_STRESSED, DRY_GRASS, SOIL, FIELD_TRANSECT,
    PAPER, COTTON, POLYESTER, POLYETHYLENE, NYLON,
    TILE_RED, TILE_ORANGE, TILE_YELLOW, TILE_GREEN, TILE_BLUE, TILE_GREY, TOMATO_UNRIPE, TOMATO_RIPE,
}

/** Light sources looked at directly (emission). */
enum class SimEmitter {
    FLAME_NA, FLAME_LI, FLAME_K, FLAME_SR, FLAME_CA, FLAME_MIX, HG_AR_LAMP,
    LASER_405, LASER_532, LASER_650, LASER_785, LASER_808,
    LED_WHITE_COOL, LED_WHITE_WARM, LED_RED, LED_GREEN, LED_BLUE, LED_GROW, LED_IR_850, LED_IR_940,
    LAMP_2856K,
}

/** What the simulated spectrometer sees. */
sealed interface SimScene {
    /** Outdoor field view used by fire detection (the original simulator continuum). */
    data object Field : SimScene

    data class Reflect(val target: SimTarget, val light: SimLight = SimLight.HALOGEN) : SimScene

    /** Cuvette with a blue dye (methylene-blue-like, peak 664 nm) at [concentration] mg/L; a reaction bleaches it. */
    data class Cuvette(val concentration: Double, val reaction: Reaction? = null) : SimScene

    data class Emit(val emitter: SimEmitter, val intensity: Double = 1.0) : SimScene

    /** First-order bleaching of the dye from [c0] mg/L at [kPerSec], started at [startMs]. */
    data class Reaction(val c0: Double, val kPerSec: Double, val startMs: Long) {
        fun concentration(nowMs: Long) = c0 * exp(-kPerSec * (nowMs - startMs).coerceAtLeast(0L) / 1000.0)
    }
}

/** Spectral models behind the scenes. All functions take wavelength in nm. */
object SimOptics {

    private fun gauss(l: Double, c: Double, w: Double) = exp(-((l - c) / w).pow(2))
    private fun sig(l: Double, c: Double, w: Double) = 1.0 / (1.0 + exp(-(l - c) / w))

    /** Relative detector response (silicon CCD with its grating): low in the UV and beyond 1000 nm. */
    fun detector(l: Double) = (0.08 + 0.92 * exp(-((l - 640) / 300).pow(2))) * sig(l, 300.0, 25.0)

    private fun planck(l: Double, t: Double): Double {
        val x = 1.438777e7 / (l * t)
        return 1.0 / (l.pow(5) * (exp(x) - 1.0))
    }

    private val halogenPeak = (300..1100).maxOf { planck(it.toDouble(), 3000.0) }
    private val sunPeak = (300..1100).maxOf { planck(it.toDouble(), 5800.0) }

    fun light(l: Double, s: SimLight) = when (s) {
        SimLight.HALOGEN -> planck(l, 3000.0) / halogenPeak
        // Sunlight with the oxygen A band (760 nm) and water vapour (720, 820, 940 nm) absorptions.
        SimLight.DAYLIGHT -> planck(l, 5800.0) / sunPeak * sig(l, 310.0, 8.0) *
            (1 - 0.55 * gauss(l, 761.0, 2.0) - 0.25 * gauss(l, 765.0, 3.0)) *
            (1 - 0.15 * gauss(l, 723.0, 8.0) - 0.25 * gauss(l, 820.0, 12.0) - 0.55 * gauss(l, 940.0, 22.0))
    }

    /** Reflectance of a target; FIELD_TRANSECT mixes canopy and soil as time passes (a drive past fields). */
    fun reflectance(l: Double, t: SimTarget, nowMs: Long): Double = when (t) {
        SimTarget.WHITE -> 0.98
        SimTarget.LEAF_HEALTHY -> leaf(l, chl = 1.0, water = 1.0)
        SimTarget.LEAF_STRESSED -> leaf(l, chl = 0.45, water = 0.6)
        SimTarget.DRY_GRASS -> 0.10 + 0.32 * sig(l, 600.0, 60.0) - 0.04 * gauss(l, 970.0, 30.0)
        SimTarget.SOIL -> 0.08 + 0.00028 * (l - 400).coerceAtLeast(0.0) - 0.02 * gauss(l, 960.0, 40.0)
        SimTarget.FIELD_TRANSECT -> {
            val s = nowMs / 1000.0
            val cover = (0.55 + 0.30 * sin(s / 23.0) + 0.15 * sin(s / 7.3 + 1.0)).coerceIn(0.0, 1.0)
            val stress = (0.5 + 0.5 * sin(s / 41.0 + 2.0))
            cover * (stress * leaf(l, 0.45, 0.6) + (1 - stress) * leaf(l, 1.0, 1.0)) + (1 - cover) * reflectance(l, SimTarget.SOIL, nowMs)
        }
        // Paper: brightened blue end, mineral fillers give a falling NIR slope, little water.
        SimTarget.PAPER -> 0.84 - 0.25 * (1 - sig(l, 420.0, 12.0)) - 0.02 * gauss(l, 975.0, 25.0) - 0.00025 * (l - 650).coerceAtLeast(0.0)
        // Cotton: cellulose and bound water bands at 975 and 1040 nm on a slowly rising NIR.
        SimTarget.COTTON -> 0.72 - 0.10 * (1 - sig(l, 440.0, 25.0)) - 0.08 * gauss(l, 975.0, 22.0) - 0.06 * gauss(l, 1040.0, 20.0) + 0.0001 * (l - 650).coerceAtLeast(0.0)
        SimTarget.POLYESTER -> 0.70 - 0.05 * gauss(l, 875.0, 9.0) - 0.08 * gauss(l, 1010.0, 14.0) - 0.04 * gauss(l, 1130.0, 15.0)
        SimTarget.POLYETHYLENE -> 0.66 - 0.10 * gauss(l, 930.0, 10.0) - 0.06 * gauss(l, 1040.0, 15.0) - 0.03 * gauss(l, 760.0, 10.0)
        SimTarget.NYLON -> 0.72 - 0.06 * gauss(l, 915.0, 12.0) - 0.09 * gauss(l, 1020.0, 18.0) - 0.04 * (1 - sig(l, 450.0, 30.0))
        SimTarget.TILE_RED -> 0.06 + 0.70 * sig(l, 600.0, 10.0)
        SimTarget.TILE_ORANGE -> 0.06 + 0.72 * sig(l, 575.0, 12.0)
        SimTarget.TILE_YELLOW -> 0.07 + 0.75 * sig(l, 505.0, 14.0)
        SimTarget.TILE_GREEN -> 0.06 + 0.45 * gauss(l, 530.0, 40.0) + 0.55 * sig(l, 720.0, 15.0)
        SimTarget.TILE_BLUE -> 0.05 + 0.55 * gauss(l, 455.0, 45.0) + 0.50 * sig(l, 730.0, 15.0)
        SimTarget.TILE_GREY -> 0.40
        SimTarget.TOMATO_UNRIPE -> 0.05 + 0.18 * gauss(l, 550.0, 35.0) + 0.02 * gauss(l, 640.0, 20.0) + 0.50 * sig(l, 705.0, 12.0) - 0.15 * gauss(l, 975.0, 25.0)
        SimTarget.TOMATO_RIPE -> 0.04 + 0.55 * sig(l, 605.0, 12.0) - 0.15 * gauss(l, 975.0, 25.0) + 0.05 * gauss(l, 680.0, 30.0)
    }

    /** Green leaf: chlorophyll absorption (blue and red), green peak, red edge, NIR plateau and water bands. */
    private fun leaf(l: Double, chl: Double, water: Double): Double {
        val vis = 0.05 + 0.10 * gauss(l, 550.0, 28.0) * chl + (1 - chl) * 0.10 * sig(l, 560.0, 25.0)
        val edge = 0.44 * sig(l, 700.0 + 18.0 * chl, 9.0)
        return vis + edge - water * (0.10 * gauss(l, 970.0, 22.0) + 0.04 * gauss(l, 1070.0, 30.0) + 0.02 * gauss(l, 840.0, 15.0))
    }

    /** Absorbance per mg/L of the dye (1 cm path). */
    fun dyeAbsorptivity(l: Double) = 0.08 * (gauss(l, 664.0, 22.0) + 0.45 * gauss(l, 612.0, 22.0) + 0.25 * gauss(l, 292.0, 15.0))

    /** Emission spectra, relative radiance (multiplied by the detector response later). */
    fun emission(l: Double, e: SimEmitter): Double {
        fun line(c: Double, a: Double) = a * gauss(l, c, 0.75)
        fun band(c: Double, fwhm: Double, a: Double) = a * gauss(l, c, fwhm / 1.665)
        fun flameBg() = 0.03 * planck(l, 1900.0) / planck(1100.0, 1900.0)
        return when (e) {
            SimEmitter.FLAME_NA -> flameBg() + line(589.3, 1.6)
            SimEmitter.FLAME_LI -> flameBg() + line(670.8, 1.1) + line(610.4, 0.06)
            SimEmitter.FLAME_K -> flameBg() + line(766.5, 0.8) + line(769.9, 0.5) + line(404.5, 0.05)
            SimEmitter.FLAME_SR -> flameBg() + line(460.7, 0.5) + band(606.0, 12.0, 0.45) + band(682.0, 12.0, 0.25)
            SimEmitter.FLAME_CA -> flameBg() + line(422.7, 0.4) + band(554.0, 14.0, 0.35) + band(622.0, 14.0, 0.45)
            SimEmitter.FLAME_MIX -> flameBg() + line(589.3, 1.2) + line(670.8, 0.6) + line(766.5, 0.5) + line(769.9, 0.3)
            SimEmitter.HG_AR_LAMP -> line(253.7, 1.2) + line(404.7, 0.35) + line(435.8, 0.8) + line(546.1, 1.0) + line(578.0, 0.25) +
                line(696.5, 0.20) + line(706.7, 0.12) + line(750.4, 0.35) + line(763.5, 0.55) + line(811.5, 0.45) + line(842.5, 0.25) + line(912.3, 0.3)
            SimEmitter.LASER_405 -> line(405.0, 3.0)
            SimEmitter.LASER_532 -> line(532.0, 3.0)
            SimEmitter.LASER_650 -> line(650.0, 3.0)
            SimEmitter.LASER_785 -> line(785.0, 3.0)
            SimEmitter.LASER_808 -> line(808.0, 3.0)
            SimEmitter.LED_BLUE -> band(455.0, 20.0, 1.0)
            SimEmitter.LED_GREEN -> band(525.0, 32.0, 1.0)
            SimEmitter.LED_RED -> band(630.0, 18.0, 1.0)
            SimEmitter.LED_IR_850 -> band(850.0, 40.0, 1.0)
            SimEmitter.LED_IR_940 -> band(940.0, 45.0, 1.0)
            SimEmitter.LED_WHITE_COOL -> band(451.0, 20.0, 1.0) + band(555.0, 115.0, 0.62)
            SimEmitter.LED_WHITE_WARM -> band(451.0, 20.0, 0.45) + band(595.0, 130.0, 0.85)
            SimEmitter.LED_GROW -> band(450.0, 20.0, 0.65) + band(660.0, 20.0, 1.0) + band(730.0, 30.0, 0.12)
            SimEmitter.LAMP_2856K -> planck(l, 2856.0) / planck(1015.0, 2856.0)
        }
    }

    /** Peak brightness scale of each emitter so the strongest feature sits well inside the detector range. */
    fun emitterGain(e: SimEmitter) = when (e) {
        SimEmitter.LASER_405, SimEmitter.LASER_532, SimEmitter.LASER_650, SimEmitter.LASER_785, SimEmitter.LASER_808 -> 3500.0
        SimEmitter.LAMP_2856K -> 11000.0
        else -> 9000.0
    }
}
