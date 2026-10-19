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
    // Street and indoor lamps (lamp survey)
    LAMP_LPS, LAMP_HPS, LAMP_MERCURY, LAMP_METAL_HALIDE, LAMP_FLUORESCENT,
    // Glowing objects (pyrometer)
    BODY_1100K, BODY_1500K, BODY_1900K,
    // Physics lab: discharge tubes and extra LEDs
    H_TUBE, HE_TUBE, NE_TUBE, LED_YELLOW, LED_UV,
    // Greenhouse light
    SUNLIGHT, CANOPY_SHADE,
}

/** Liquids for the cuvette (absorbance against the blank). */
enum class SimSample {
    DYE,
    BEER_LAGER, BEER_ALE, BEER_STOUT_DILUTED, WINE_RED_1MM, WINE_ROSE, HONEY_LIGHT, HONEY_AMBER,
    OLIVE_EXTRA_VIRGIN, OLIVE_VIRGIN, OLIVE_OXIDISED,
    WATER_CLEAN, WATER_NITRATE, WATER_PEATY,
    DNA_PURE, DNA_PROTEIN, DNA_GUANIDINE, PROTEIN_BSA,
}

/** Water surfaces seen from above (reflectance against a white panel, daylight). */
enum class SimWater { CLEAR_OCEAN, COASTAL, ALGAL_BLOOM, CYANO_BLOOM, HUMIC_LAKE }

/** Displays for the display check. */
enum class SimDisplay { LCD, OLED }

/** Filters and eyewear in the beam (transmittance against the open beam). */
enum class SimFilter { OPEN, SUNGLASS_CAT3, BLUE_BLOCK, UV400_CLEAR, RED_LONGPASS, LASER_GOGGLE_532, ND_1 }

/**
 * Virtual clock for the sun scenes: shows the sky at another time of day, optionally running faster
 * (a whole morning in minutes for a Langley calibration).
 */
data class SimClock(val realStartMs: Long, val virtualStartMs: Long, val speed: Double = 1.0) {
    fun at(realMs: Long) = virtualStartMs + ((realMs - realStartMs) * speed).toLong()
}

/** What the simulated spectrometer sees. */
sealed interface SimScene {
    /** Outdoor field view used by fire detection (the original simulator continuum). */
    data object Field : SimScene

    data class Reflect(val target: SimTarget, val light: SimLight = SimLight.HALOGEN) : SimScene

    /** Cuvette with a blue dye (methylene-blue-like, peak 664 nm) at [concentration] mg/L; a reaction bleaches it. */
    data class Cuvette(val concentration: Double, val reaction: Reaction? = null, val sample: SimSample = SimSample.DYE) : SimScene

    data class Emit(val emitter: SimEmitter, val intensity: Double = 1.0) : SimScene

    /** Direct sun through a collimator and ND filter: aerosol (AOD at 500 nm, Ångström α) and water vapour (cm). */
    data class Sun(val aod500: Double, val alpha: Double, val pwvCm: Double, val clock: SimClock, val ozoneDu: Double = 300.0,
                   /** NO2 vertical column, Dobson units (0.1–1 DU in polluted cities). */ val no2Du: Double = 0.0) : SimScene

    /** Global solar UV through a cosine corrector (UV index), with the ozone column and cloud transmission. */
    data class SunUv(val ozoneDu: Double, val clock: SimClock, val cloud: Double = 1.0) : SimScene

    /** Raman spectrum of a library material with a [laserNm] laser and a long-pass filter. */
    data class Raman(val material: String, val laserNm: Double = 785.0) : SimScene

    /** Transparent film of [thicknessNm] (index [n]) on a substrate of index [substrateN], halogen light. */
    data class Film(val thicknessNm: Double, val n: Double = 1.5, val substrateN: Double = 3.7) : SimScene

    data class Transmit(val filter: SimFilter) : SimScene

    /** A piece of fruit with its own sugar content, water, skin colour and scattering (seeded). */
    data class Fruit(val seed: Long) : SimScene

    /** Bacterial culture growing in the cuvette (Gompertz curve), time running [speed] × faster. */
    data class Culture(val startMs: Long, val speed: Double = 60.0, val od0: Double = 0.02, val odMax: Double = 1.6, val muPerHour: Double = 1.2, val lagHours: Double = 0.6) : SimScene {
        fun hours(nowMs: Long) = (nowMs - startMs) * speed / 3_600_000.0
        fun od(nowMs: Long): Double {
            val t = hours(nowMs); val a = kotlin.math.ln(odMax / od0)
            return od0 * kotlin.math.exp(a * kotlin.math.exp(-kotlin.math.exp(muPerHour * kotlin.math.E / a * (lagHours - t) + 1)))
        }
    }

    /** Water sample after a test-kit reagent: [method] index of TestKits.Method, concentration in its unit. */
    data class Kit(val method: Int, val concentration: Double) : SimScene

    /** pH indicator dye ([dye] index of Indicator.Dye) in a sample of [pH]. */
    data class Indicator(val dye: Int, val pH: Double) : SimScene

    /** Fluorescence cuvette at 90° with an LED: [preset] index of Fluorimetry.Preset. */
    data class Fluor(val preset: Int, val concentration: Double) : SimScene

    data class WaterSurface(val water: SimWater) : SimScene

    /** Leaf in a clip (transmission), chlorophyll in µg/cm² (0 = empty clip). */
    data class LeafClip(val chlorophyll: Double) : SimScene

    /** Gemstone by library name, in transmission or fluorescence. */
    data class Gem(val name: String, val fluorescence: Boolean = false) : SimScene

    /** Painted area of a historical pigment (library name), seen with a reflection probe. */
    data class Pigment(val name: String) : SimScene

    /** Skin seen with a reflection probe: melanin, blood (erythema) and carotenoid levels. */
    data class Skin(val melanin: Double, val blood: Double, val carotenoid: Double) : SimScene

    /** Fingertip on the probe (light through the finger), pulse at [bpm]. */
    data class Finger(val bpm: Double, val startMs: Long, val seed: Int = 3) : SimScene

    data class Display(val display: SimDisplay, val patch: Int) : SimScene

    /** A simulated day of personal light exposure (home, commute, office, evening), on a virtual clock. */
    data class Day(val clock: SimClock) : SimScene

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

    /** UV-enhanced detector of the STS-UV: usable down to about 200 nm. */
    fun detectorUv(l: Double) = (0.35 + 0.65 * exp(-((l - 450) / 250).pow(2))) * sig(l, 200.0, 8.0)

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
            (1 - 0.55 * gauss(l, 761.0, 2.0) - 0.25 * gauss(l, 765.0, 3.0)) * (1 - 0.30 * gauss(l, 687.4, 1.4)) *
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

    /** Chlorophyll fluorescence emission shape (red 686 nm and far-red 740 nm peaks). */
    fun fluorescence(l: Double) = 0.35 * gauss(l, 686.0, 9.0) + gauss(l, 740.0, 22.0)

    /** Fluorescence yield of a target (share of the absorbed light re-emitted, scaled). */
    fun fluorescenceYield(t: SimTarget, nowMs: Long): Double = when (t) {
        SimTarget.LEAF_HEALTHY -> 0.018
        SimTarget.LEAF_STRESSED -> 0.008
        SimTarget.TOMATO_UNRIPE -> 0.004
        SimTarget.FIELD_TRANSECT -> {
            val s = nowMs / 1000.0
            (0.55 + 0.30 * sin(s / 23.0) + 0.15 * sin(s / 7.3 + 1.0)).coerceIn(0.0, 1.0) * (0.008 + 0.010 * (0.5 - 0.5 * sin(s / 41.0 + 2.0)))
        }
        else -> 0.0
    }

    /** Light source with a deuterium lamp added for the UV (cuvette and filter scenes, like a DH-2000). */
    fun cuvetteLight(l: Double) = planck(l, 3000.0) / halogenPeak + 1.2 * exp(-((l - 230.0) / 80.0).pow(2)) * sig(l, 195.0, 4.0)

    /** Absorbance (10 mm) of the cuvette samples. */
    fun sampleAbsorbance(l: Double, s: SimSample): Double = when (s) {
        SimSample.DYE -> 0.0
        // Beer colour: steep exponential fall from the blue; EBC 8 / 30, stout 120 diluted 1:10.
        SimSample.BEER_LAGER -> 0.32 * exp(-(l - 430.0) / 55.0) + 0.004
        SimSample.BEER_ALE -> 1.20 * exp(-(l - 430.0) / 60.0) + 0.006
        SimSample.BEER_STOUT_DILUTED -> 0.48 * exp(-(l - 430.0) / 65.0) + 0.004
        // Red wine in a 1 mm cell (anthocyanins at 520, yellow-brown tail in the blue).
        SimSample.WINE_RED_1MM -> 0.47 * gauss(l, 525.0, 55.0) + 0.30 * exp(-(l - 400.0) / 70.0) + 0.06 * gauss(l, 620.0, 60.0)
        SimSample.WINE_ROSE -> 0.30 * gauss(l, 515.0, 50.0) + 0.25 * exp(-(l - 400.0) / 60.0)
        // Honey, 50 % w/v solution: Pfund ≈ 30 mm (white) and ≈ 100 mm (amber).
        SimSample.HONEY_LIGHT -> 0.185 * exp(-(l - 635.0) / 110.0)
        SimSample.HONEY_AMBER -> 0.373 * exp(-(l - 635.0) / 120.0)
        // Olive oil, 1 g / 100 mL iso-octane: dienes at 232 nm, trienes at 268 nm (with 262 / 278 shoulders).
        SimSample.OLIVE_EXTRA_VIRGIN -> olive(l, dienes = 1.75, trienes = 0.0, broad = 0.12)
        SimSample.OLIVE_VIRGIN -> olive(l, dienes = 2.52, trienes = 0.002, broad = 0.20)
        SimSample.OLIVE_OXIDISED -> olive(l, dienes = 2.90, trienes = 0.07, broad = 0.32)
        // Water: nitrate band below 240 nm (0.236 A per mg/L N at 220 nm) and organic matter (exponential).
        SimSample.WATER_CLEAN -> water(l, nitrateN = 0.5, uv254 = 0.02)
        SimSample.WATER_NITRATE -> water(l, nitrateN = 8.0, uv254 = 0.05)
        SimSample.WATER_PEATY -> water(l, nitrateN = 1.0, uv254 = 0.40)
        SimSample.DNA_PURE, SimSample.DNA_PROTEIN, SimSample.DNA_GUANIDINE, SimSample.PROTEIN_BSA -> nucleic(l, s)
    }

    private fun olive(l: Double, dienes: Double, trienes: Double, broad: Double) =
        dienes * gauss(l, 233.0, 11.0) + broad * gauss(l, 270.0, 22.0) + 0.05 * exp(-(l - 220.0) / 25.0) +
            trienes * (gauss(l, 268.0, 2.2) + 0.55 * gauss(l, 278.0, 2.2) + 0.45 * gauss(l, 259.0, 2.2))

    private fun water(l: Double, nitrateN: Double, uv254: Double) =
        nitrateN * 0.236 * gauss(l, 203.0, 14.0) / gauss(220.0, 203.0, 14.0) + uv254 * exp(-0.0126 * (l - 254.0))

    /** Filter transmittance (0..1). */
    fun filter(l: Double, f: SimFilter): Double = when (f) {
        SimFilter.OPEN -> 1.0
        SimFilter.SUNGLASS_CAT3 -> sig(l, 410.0, 2.0) * (0.07 + 0.07 * sig(l, 520.0, 40.0))
        SimFilter.BLUE_BLOCK -> 0.92 * sig(l, 402.0, 3.0) * (1 - 0.55 * gauss(l, 440.0, 22.0))
        SimFilter.UV400_CLEAR -> 0.92 * sig(l, 405.0, 2.0)
        SimFilter.RED_LONGPASS -> 0.0005 + 0.90 * sig(l, 610.0, 5.0)
        SimFilter.LASER_GOGGLE_532 -> 0.75 * (1 - 0.9999 * exp(-((l - 532.0) / 16.0).pow(8))) * (0.85 + 0.15 * sig(l, 600.0, 30.0))
        SimFilter.ND_1 -> 0.10
    }

    /** Properties of a simulated fruit from its seed: sugar °Brix, water, scattering, skin colour, offset. */
    data class FruitProps(val brix: Double, val water: Double, val scatter: Double, val colour: Double, val offset: Double)

    fun fruit(seed: Long): FruitProps {
        val r = java.util.Random(seed * 7919 + 13)
        val brix = 8.0 + 10.0 * r.nextDouble()
        return FruitProps(
            brix = brix,
            water = 1.05 - 0.012 * (brix - 13.0) + 0.03 * r.nextGaussian(),
            scatter = 0.85 + 0.30 * r.nextDouble(),
            colour = r.nextDouble(),
            offset = 0.02 * (r.nextDouble() - 0.5),
        )
    }

    fun fruitReflectance(l: Double, p: FruitProps): Double {
        val vis = (1 - p.colour) * 0.12 * gauss(l, 550.0, 35.0) + p.colour * 0.30 * sig(l, 600.0, 12.0)
        return p.scatter * (0.06 + vis + 0.52 * sig(l, 705.0, 12.0)) - p.water * 0.20 * gauss(l, 970.0, 26.0) -
            p.brix * (0.0035 * gauss(l, 910.0, 20.0) + 0.002 * gauss(l, 840.0, 15.0)) + p.offset
    }

    /** Extraterrestrial solar spectral irradiance, W m⁻² nm⁻¹ (5778 K black body, UV line blanketing). */
    fun solarE0(l: Double): Double {
        val m = l * 1e-9
        val b = 2 * 6.62607e-34 * 2.99792e8 * 2.99792e8 / (m * m * m * m * m * (exp(1.438777e-2 / (m * 5778.0)) - 1.0))
        return Math.PI * b * 2.163e-5 * 1e-9 * (0.7 + 0.3 * sig(l, 420.0, 15.0))
    }

    /** Direct-sun transmission of the atmosphere: Rayleigh, ozone, aerosol, water vapour and O2. */
    fun directTransmission(l: Double, m: Double, s: SimScene.Sun): Double {
        val tau = com.rfsat.pocketspectro.tools.Solar.rayleighTau(l) + com.rfsat.pocketspectro.tools.Solar.ozoneTau(l, s.ozoneDu) +
            s.aod500 * (l / 500.0).pow(-s.alpha)
        val kw = 0.72 * gauss(l, 940.0, 20.0) + 0.18 * gauss(l, 823.0, 10.0) + 0.10 * gauss(l, 723.0, 7.0)
        val o2 = 0.9 * gauss(l, 761.0, 1.6) + 0.35 * gauss(l, 765.5, 2.5) + 0.3 * gauss(l, 687.4, 1.4)
        val no2 = if (s.no2Du > 0) com.rfsat.pocketspectro.tools.GasColumns.syntheticNo2(l) * s.no2Du * com.rfsat.pocketspectro.tools.GasColumns.MOLEC_PER_DU * m else 0.0
        return exp(-m * tau) * exp(-kw * (m * s.pwvCm).pow(0.6)) * exp(-o2 * m.pow(0.55)) * exp(-no2)
    }

    /** Global UV irradiance on a horizontal cosine corrector, W m⁻² nm⁻¹. */
    fun globalUv(l: Double, cosZ: Double, m: Double, distFactor: Double, s: SimScene.SunUv): Double {
        if (cosZ <= 0.0) return 0.0
        val tO3 = exp(-m * com.rfsat.pocketspectro.tools.Solar.ozoneTau(l, s.ozoneDu))
        // Direct beam plus about half of the Rayleigh-scattered light (diffuse sky), light aerosol.
        val tR = exp(-0.5 * m * com.rfsat.pocketspectro.tools.Solar.rayleighTau(l))
        val tA = exp(-0.6 * m * 0.10 * (l / 500.0).pow(-1.3))
        return solarE0(l) * distFactor * cosZ * tO3 * tR * tA * s.cloud
    }

    /** UV index a calibrated meter would show for the scene. */
    fun trueUvIndex(cosZ: Double, m: Double, distFactor: Double, s: SimScene.SunUv): Double {
        var w = 0.0; var l = 280.0
        while (l <= 400.0) { w += globalUv(l, cosZ, m, distFactor, s) * com.rfsat.pocketspectro.tools.UvIndex.erythemal(l); l += 0.5 }
        return 40.0 * w * 0.5
    }

    // ---- 1.8.0 scenes ------------------------------------------------------------------------------

    fun dna(l: Double) = gauss(l, 259.0, 26.8) + 0.15 * exp(-(l - 230.0) / 8.0).coerceAtMost(30.0)
    fun protein(l: Double) = gauss(l, 277.0, 22.0) + 2.0 * exp(-(l - 215.0) / 9.0).coerceAtMost(30.0)

    fun nucleic(l: Double, s: SimSample): Double = when (s) {
        SimSample.DNA_PURE -> dna(l)
        SimSample.DNA_PROTEIN -> 0.8 * dna(l) + 0.3 * protein(l)
        SimSample.DNA_GUANIDINE -> dna(l) + 2.85 * exp(-(l - 220.0) / 6.0).coerceAtMost(30.0)
        SimSample.PROTEIN_BSA -> 0.66 * protein(l)
        else -> 0.0
    }

    /** Test-kit colour: absorbance band of the method at its concentration (molybdenum blue has two bands). */
    fun kitAbsorbance(l: Double, method: Int, c: Double): Double {
        val m = com.rfsat.pocketspectro.tools.TestKits.Method.entries[method]
        return if (m == com.rfsat.pocketspectro.tools.TestKits.Method.PHOSPHATE)
            c * m.perUnit * (gauss(l, 880.0, 90.0) + 0.6 * gauss(l, 710.0, 60.0))
        else c * m.perUnit * gauss(l, m.nm, 38.0)
    }

    fun indicatorAcid(l: Double, d: com.rfsat.pocketspectro.tools.Indicator.Dye) = 0.6 * gauss(l, d.acidNm, 40.0)
    fun indicatorBase(l: Double, d: com.rfsat.pocketspectro.tools.Indicator.Dye) = 1.2 * gauss(l, d.baseNm, 45.0)
    fun indicator(l: Double, dye: Int, pH: Double): Double {
        val d = com.rfsat.pocketspectro.tools.Indicator.Dye.entries[dye]
        val fb = 1.0 / (1.0 + Math.pow(10.0, d.pKa - pH))
        return (1 - fb) * indicatorAcid(l, d) + fb * indicatorBase(l, d)
    }

    /** Fluorescence emission per unit concentration (relative) and the LED scattered into the detector. */
    fun fluorEmission(l: Double, preset: Int): Double = when (com.rfsat.pocketspectro.tools.Fluorimetry.Preset.entries[preset]) {
        com.rfsat.pocketspectro.tools.Fluorimetry.Preset.CHLOROPHYLL_A -> 0.10 * (gauss(l, 683.0, 12.0) + 0.3 * gauss(l, 735.0, 20.0))
        com.rfsat.pocketspectro.tools.Fluorimetry.Preset.PHYCOCYANIN -> 0.10 * gauss(l, 650.0, 14.0)
        com.rfsat.pocketspectro.tools.Fluorimetry.Preset.FLUORESCEIN -> 0.10 * gauss(l, 515.0, 18.0)
        com.rfsat.pocketspectro.tools.Fluorimetry.Preset.QUININE -> 1.0 * gauss(l, 450.0, 35.0)
        com.rfsat.pocketspectro.tools.Fluorimetry.Preset.OIL -> 1.0 * gauss(l, 440.0, 50.0)
        com.rfsat.pocketspectro.tools.Fluorimetry.Preset.SECURITY_UV -> 0.6 * gauss(l, 440.0, 30.0) + 0.8 * gauss(l, 612.0, 2.5) + 0.3 * gauss(l, 545.0, 4.0)
    }
    fun fluorLed(l: Double, preset: Int) = 0.25 * gauss(l, com.rfsat.pocketspectro.tools.Fluorimetry.Preset.entries[preset].ledNm, 10.0)

    /** Water-leaving reflectance (fraction) seen above the surface. */
    fun water(l: Double, w: SimWater): Double = when (w) {
        SimWater.CLEAR_OCEAN -> 0.022 * exp(-(l - 410.0) / 55.0) + 0.0008
        SimWater.COASTAL -> 0.004 + 0.012 * gauss(l, 560.0, 45.0) + 0.003 * gauss(l, 470.0, 40.0)
        SimWater.ALGAL_BLOOM -> 0.004 + 0.012 * gauss(l, 565.0, 30.0) - 0.0035 * gauss(l, 675.0, 12.0) + 0.010 * gauss(l, 708.0, 14.0) + 0.0015 * sig(l, 720.0, 20.0)
        SimWater.CYANO_BLOOM -> 0.004 + 0.012 * gauss(l, 565.0, 30.0) - 0.004 * gauss(l, 622.0, 10.0) + 0.003 * gauss(l, 650.0, 10.0) -
            0.0035 * gauss(l, 675.0, 12.0) + 0.011 * gauss(l, 708.0, 14.0) + 0.002 * sig(l, 720.0, 20.0)
        SimWater.HUMIC_LAKE -> 0.0012 + 0.007 * gauss(l, 640.0, 70.0)
    }

    fun leafTransmission(l: Double, chl: Double) =
        if (chl <= 0) 1.0 else 0.5 * exp(-chl * (0.046 * gauss(l, 665.0, 22.0) / gauss(650.0, 665.0, 22.0) + 0.06 * gauss(l, 440.0, 30.0)) - 0.15 * gauss(l, 970.0, 25.0))

    /** Gemstone transmission (absorption lines) or fluorescence emission. */
    fun gem(l: Double, name: String, fluorescence: Boolean): Double {
        fun line(c: Double, d: Double, w: Double) = d * gauss(l, c, w)
        if (fluorescence) return when {
            name.startsWith("Ruby") -> line(692.8, 1.0, 0.9) + line(694.2, 0.7, 0.9) + line(450.0, 0.2, 10.0)
            name.startsWith("Red spinel") -> line(675.0, 0.5, 1.5) + line(685.5, 1.0, 1.2) + line(698.0, 0.6, 1.5) + line(708.0, 0.4, 2.0) + line(450.0, 0.2, 10.0)
            else -> line(450.0, 0.2, 10.0)
        }
        val a = when {
            name.startsWith("Ruby") -> line(405.0, 1.2, 25.0) + line(550.0, 1.4, 40.0) + line(693.5, 0.4, 2.0)
            name.startsWith("Emerald") -> line(637.0, 0.5, 6.0) + line(681.5, 0.9, 2.5) + line(600.0, 0.6, 30.0) + line(430.0, 0.8, 30.0)
            name.startsWith("Alexandrite") -> line(645.0, 0.3, 3.0) + line(655.0, 0.4, 3.0) + line(665.0, 0.5, 3.0) + line(679.5, 0.8, 2.0) + line(580.0, 0.7, 40.0)
            name.startsWith("Blue sapphire") -> line(450.0, 0.9, 3.0) + line(460.0, 0.4, 3.0) + line(471.0, 0.3, 3.0) + line(600.0, 0.8, 60.0)
            name.startsWith("Peridot") -> line(453.0, 0.6, 4.0) + line(477.0, 0.5, 4.0) + line(493.0, 0.5, 5.0)
            name.startsWith("Almandine") -> line(505.0, 0.7, 7.0) + line(527.0, 0.8, 7.0) + line(576.0, 0.7, 8.0)
            name.startsWith("Zircon") -> line(589.0, 0.3, 2.5) + line(653.5, 0.8, 1.5) + line(660.5, 0.4, 1.5)
            name.startsWith("Cape") -> line(415.5, 0.7, 2.5)
            name.startsWith("Cobalt") -> line(540.0, 0.8, 12.0) + line(580.0, 0.9, 12.0) + line(635.0, 0.8, 14.0)
            name.startsWith("Didymium") -> line(520.0, 0.5, 4.0) + line(580.5, 1.0, 4.0) + line(740.0, 0.5, 5.0) + line(800.0, 0.4, 6.0)
            else -> 0.0
        }
        return 0.7 * exp(-a)
    }

    /** Reflectance of historical pigments, built from their published spectral features. */
    fun pigment(l: Double, name: String): Double = when {
        name.startsWith("Ultramarine") -> 0.10 + 0.32 * gauss(l, 455.0, 60.0) + 0.55 * sig(l, 700.0, 22.0) - 0.10 * gauss(l, 600.0, 45.0)
        name.startsWith("Azurite") -> 0.05 + 0.40 * gauss(l, 465.0, 55.0) + 0.08 * gauss(l, 800.0, 70.0) - 0.10 * gauss(l, 650.0, 50.0)
        name.startsWith("Prussian") -> 0.03 + 0.10 * gauss(l, 450.0, 40.0) + 0.15 * sig(l, 820.0, 30.0)
        name.startsWith("Smalt") -> 0.25 + 0.25 * gauss(l, 450.0, 50.0) + 0.4 * sig(l, 700.0, 30.0) - 0.08 * (gauss(l, 545.0, 8.0) + gauss(l, 590.0, 8.0) + gauss(l, 640.0, 8.0))
        name.startsWith("Indigo") -> 0.08 + 0.05 * gauss(l, 450.0, 60.0) - 0.04 * gauss(l, 660.0, 35.0) + 0.55 * sig(l, 715.0, 12.0)
        name.startsWith("Malachite") -> 0.06 + 0.35 * gauss(l, 525.0, 40.0)
        name.startsWith("Vermilion") -> 0.05 + 0.65 * sig(l, 600.0, 7.0)
        name.startsWith("Red lead") -> 0.08 + 0.70 * sig(l, 570.0, 8.0)
        name.startsWith("Cadmium red") -> 0.05 + 0.70 * sig(l, 615.0, 8.0)
        name.startsWith("Red ochre") -> 0.06 + 0.45 * sig(l, 580.0, 15.0) - 0.15 * gauss(l, 870.0, 40.0)
        name.startsWith("Yellow ochre") -> 0.10 + 0.50 * sig(l, 545.0, 15.0) - 0.18 * gauss(l, 925.0, 45.0)
        name.startsWith("Cadmium yellow") -> 0.08 + 0.75 * sig(l, 505.0, 9.0)
        name.startsWith("Lead-tin") -> 0.12 + 0.75 * sig(l, 492.0, 8.0)
        name.startsWith("Carmine") -> 0.15 + 0.60 * sig(l, 600.0, 15.0) - 0.10 * (gauss(l, 525.0, 15.0) + gauss(l, 565.0, 15.0))
        name.startsWith("Madder") -> 0.15 + 0.60 * sig(l, 590.0, 15.0) - 0.10 * (gauss(l, 510.0, 12.0) + gauss(l, 540.0, 12.0))
        name.startsWith("White") -> 0.88 - 0.25 * (1 - sig(l, 410.0, 10.0))
        name.startsWith("Black") -> 0.04
        else -> 0.5
    }

    /** Skin reflectance from melanin, haemoglobin and carotenoid absorption. */
    fun skin(l: Double, melanin: Double, blood: Double, carotenoid: Double): Double {
        val a = 0.25 * melanin * (l / 500.0).pow(-3.0) +
            blood * (0.9 * gauss(l, 415.0, 14.0) + 0.35 * gauss(l, 542.0, 10.0) + 0.38 * gauss(l, 577.0, 9.0) + 0.05 * exp(-(l - 600.0) / 60.0).coerceAtMost(1.0)) +
            carotenoid * 0.05 * (gauss(l, 455.0, 14.0) + gauss(l, 482.0, 14.0))
        return 0.62 * Math.pow(10.0, -a) + 0.02
    }

    /** Light through a fingertip (deep red and near infrared pass). */
    fun finger(l: Double) = 0.15 * exp(-2.0 * gauss(l, 560.0, 60.0) - 1.0 * gauss(l, 420.0, 30.0))

    /** Display patch spectra: LCD (white LED backlight and colour filters) or OLED (narrow emitters). */
    fun display(l: Double, d: SimDisplay, patch: Int): Double {
        val r: Double; val g: Double; val b: Double
        if (d == SimDisplay.LCD) {
            val bl = gauss(l, 450.0, 10.0) + 0.55 * gauss(l, 560.0, 55.0)
            r = bl * sig(l, 590.0, 10.0); g = bl * gauss(l, 530.0, 40.0); b = bl * (1 - sig(l, 500.0, 12.0))
        } else { r = gauss(l, 620.0, 15.0); g = 0.9 * gauss(l, 530.0, 15.0); b = 1.1 * gauss(l, 460.0, 12.0) }
        return when (patch) { 1 -> r; 2 -> g; 3 -> b; else -> r + g + b }
    }

    /** Personal-light day: what the wearer sees at a given local hour, and its illuminance (lx). */
    fun dayLight(l: Double, hour: Double): Double {
        val warm = planck(l, 2700.0) / planck(560.0, 2700.0)
        val office = gauss(l, 451.0, 12.0) * 0.55 + gauss(l, 560.0, 70.0) * 0.75
        val sky = planck(l, 6500.0) / planck(560.0, 6500.0)
        return when {
            hour < 6.5 || hour >= 23.5 -> warm
            hour < 8.0 -> warm
            hour < 9.0 -> sky
            hour < 17.0 -> 0.8 * office + 0.2 * sky
            hour < 18.0 -> sky
            hour < 21.0 -> warm
            else -> 0.6 * warm + 0.4 * office
        }
    }
    fun dayLux(hour: Double): Double = when {
        hour < 6.5 || hour >= 23.5 -> 0.5
        hour < 8.0 -> 150.0
        hour < 9.0 -> 6000.0
        hour < 17.0 -> 450.0
        hour < 18.0 -> 3000.0
        hour < 21.0 -> 120.0
        else -> 35.0
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
            SimEmitter.LAMP_LPS -> line(589.0, 1.0) + line(589.6, 1.0) + line(819.5, 0.25) + line(818.3, 0.15) + 0.004
            SimEmitter.LAMP_HPS -> (band(572.0, 30.0, 0.40) + band(606.0, 34.0, 0.50)) * (1 - 0.65 * gauss(l, 589.3, 2.5)) +
                line(568.8, 0.12) + line(615.4, 0.10) + line(498.3, 0.05) + line(466.5, 0.04) + line(819.5, 0.30) +
                0.06 * planck(l, 2100.0) / planck(800.0, 2100.0)
            SimEmitter.LAMP_MERCURY -> line(404.7, 0.35) + line(435.8, 0.8) + line(546.1, 1.0) + line(577.0, 0.4) + line(579.1, 0.4) +
                line(1014.0, 0.2) + 0.02 * band(500.0, 250.0, 1.0)
            SimEmitter.LAMP_METAL_HALIDE -> band(560.0, 220.0, 0.30) + line(451.1, 0.4) + line(535.0, 0.9) + line(589.3, 0.6) +
                line(670.8, 0.3) + line(435.8, 0.35) + line(546.1, 0.45) + line(577.0, 0.15) + line(498.0, 0.25) + line(475.0, 0.2) +
                line(510.5, 0.2) + line(625.0, 0.2) + line(819.5, 0.15)
            SimEmitter.LAMP_FLUORESCENT -> line(404.7, 0.12) + line(435.8, 0.5) + line(546.1, 0.85) + line(578.0, 0.08) +
                band(611.5, 3.0, 1.0) + band(487.0, 6.0, 0.30) + band(543.0, 8.0, 0.35) + band(590.0, 8.0, 0.18) + band(450.0, 50.0, 0.22) +
                band(707.0, 6.0, 0.06)
            SimEmitter.BODY_1100K -> planck(l, 1100.0) / planck(1100.0, 1100.0)
            SimEmitter.BODY_1500K -> planck(l, 1500.0) / planck(1100.0, 1500.0)
            SimEmitter.BODY_1900K -> planck(l, 1900.0) / planck(1100.0, 1900.0)
            SimEmitter.H_TUBE -> line(656.28, 1.0) + line(486.13, 0.45) + line(434.05, 0.25) + line(410.17, 0.12) + 0.01 * band(560.0, 300.0, 1.0)
            SimEmitter.HE_TUBE -> line(447.15, 0.3) + line(471.31, 0.15) + line(492.19, 0.2) + line(501.57, 0.45) + line(587.56, 1.0) + line(667.82, 0.4) +
                line(706.52, 0.5) + line(728.13, 0.15)
            SimEmitter.NE_TUBE -> line(540.06, 0.15) + line(585.25, 0.8) + line(588.19, 0.5) + line(594.48, 0.6) + line(603.0, 0.3) + line(607.43, 0.45) +
                line(609.62, 0.5) + line(614.31, 0.6) + line(616.36, 0.4) + line(621.73, 0.35) + line(626.65, 0.5) + line(630.48, 0.4) + line(633.44, 0.6) +
                line(638.30, 0.7) + line(640.22, 1.0) + line(650.65, 0.7) + line(659.90, 0.5) + line(692.95, 0.6) + line(703.24, 0.8) + line(717.39, 0.3) +
                line(724.52, 0.4) + line(743.89, 0.3)
            SimEmitter.LED_YELLOW -> band(590.0, 15.0, 1.0)
            SimEmitter.LED_UV -> band(395.0, 12.0, 1.0)
            SimEmitter.SUNLIGHT -> light(l, SimLight.DAYLIGHT)
            SimEmitter.CANOPY_SHADE -> light(l, SimLight.DAYLIGHT) * (0.05 + 0.10 * gauss(l, 550.0, 28.0) + 0.45 * sig(l, 718.0, 9.0))
        }
    }

    /** Peak brightness scale of each emitter so the strongest feature sits well inside the detector range. */
    fun emitterGain(e: SimEmitter) = when (e) {
        SimEmitter.LASER_405, SimEmitter.LASER_532, SimEmitter.LASER_650, SimEmitter.LASER_785, SimEmitter.LASER_808 -> 3500.0
        SimEmitter.LAMP_2856K, SimEmitter.BODY_1100K, SimEmitter.BODY_1500K, SimEmitter.BODY_1900K -> 11000.0
        else -> 9000.0
    }
}
