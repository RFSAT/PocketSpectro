package com.rfsat.pocketspectro.sim

import com.rfsat.pocketspectro.core.DeviceInfo
import com.rfsat.pocketspectro.core.Spectrometer
import com.rfsat.pocketspectro.obp.StsProtocol
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * Stand-in STS-NIR for demonstration, UI work and store review (reviewers have no hardware).
 * Produces a dark offset, a broadband daylight/vegetation-like continuum scaled by
 * integration time, shot/read noise, and optionally the K I doublet (766.5 / 769.9 nm)
 * that a biomass flame emits.
 */
class SimulatedSpectrometer(seed: Int = 1, val model: SimModel = SimModel.NIR) : Spectrometer {
    constructor(seed: Int = 1, nir: Boolean) : this(seed, if (nir) SimModel.NIR else SimModel.VIS)

    private val rnd = Random(seed)
    private var integrationUs = 100_000L
    private var shutterOpen = true

    /** Fire incidents: manual bursts, a held flame, or random bursts (see [SimFireInjector]). */
    val fire = SimFireInjector(seed.toLong() + 7)

    /** Held flame on/off (manual). Kept for callers that only need a steady flame. */
    var fireOn: Boolean
        get() = fire.hold
        set(v) { fire.hold = v }
    /** Places a sample with water (970 nm) and C-H (915 nm) absorption bands in the beam (field scene). */
    @Volatile var sampleOn = false

    /** What the probe is looking at; the tools switch it (see [SimScene]). */
    @Volatile var scene: SimScene = SimScene.Field

    /** Where the simulated sun shines (the engine passes the phone's position or the tool's site). */
    @Volatile var siteLat = 53.35
    @Volatile var siteLon = -6.26

    /** Hot pixels: dark current far above the rest, as most CMOS detectors have a few. */
    private val hotPixels = mapOf(137 to 420.0, 702 to 260.0)

    private val lightNorm = HashMap<SimLight, Double>()
    private val emitNorm = HashMap<SimEmitter, Double>()

    private var current = DeviceInfo(
        model = "${model.label} (simulated)",
        serial = "SIM00001",
        unbinnedPixels = StsProtocol.UNBINNED_PIXELS,
        binningFactor = 0,
        maxBinningFactor = 3,
        wavelengthCoefficients = model.coefficients,
        nonlinearityCoefficients = doubleArrayOf(1.0, 1.2e-6, -1.0e-10, 0.0, 0.0, 0.0, 0.0, 0.0),
        maxCounts = StsProtocol.MAX_COUNTS,
        simulated = true,
        fireCapable = model == SimModel.NIR,
        interfaceLabel = "simulated, no USB",
    )

    override val info get() = current

    override fun setIntegrationTimeMicros(us: Long) {
        integrationUs = us.coerceIn(StsProtocol.INTEGRATION_MIN_US, StsProtocol.INTEGRATION_MAX_US)
    }

    override fun setBinningFactor(factor: Int) {
        current = current.copy(binningFactor = factor.coerceIn(0, current.maxBinningFactor))
    }

    override fun setShutter(open: Boolean): Boolean { shutterOpen = open; return true }

    override fun acquire(): IntArray {
        // Pace like real hardware (integration time, minimum ~5 ms per scan).
        Thread.sleep((integrationUs / 1000L).coerceIn(5L, 2000L))
        val wl = current.wavelengths()
        val bin = 1 shl current.binningFactor
        val tScale = integrationUs / 100_000.0
        val now = System.currentTimeMillis()
        val flame = fire.level(now)
        val sc = scene
        return IntArray(wl.size) { i ->
            val l = wl[i]
            var signal = 0.0
            if (shutterOpen && sc !is SimScene.Field) {
                signal = sceneSignal(l, sc, now) * tScale
            } else if (shutterOpen) {
                // Smooth continuum peaking near 850 nm with a vegetation edge around 720 nm.
                val cont = 3200.0 * exp(-((l - 860.0) / 210.0).pow(2)) * (1.0 / (1.0 + exp(-(l - 715.0) / 12.0)) * 0.7 + 0.3)
                val trans = if (sampleOn) 0.55 * exp(-0.9 * band(l, 970.0, 28.0) - 0.4 * band(l, 915.0, 14.0) - 0.15 * band(l, 740.0, 30.0)) else 1.0
                signal += cont * tScale * trans
                if (flame > 0.0) {
                    signal += 900.0 * flame * tScale * (line(l, 766.5) + 0.65 * line(l, 769.9))
                    // Glowing soot continuum of the flame (about 1250 K), seen through the detector response.
                    signal += 3000.0 * flame * tScale * det(l) * flameShape(l)
                }
            }
            var dark = 1480.0 + 12.0 * tScale
            hotPixels[i]?.let { dark += 30.0 + it * tScale }
            val noise = rnd.nextDouble(-1.0, 1.0) * (8.0 + kotlin.math.sqrt(signal.coerceAtLeast(0.0)) * 0.6)
            ((dark + signal) * bin / bin + noise).toInt().coerceIn(0, StsProtocol.MAX_COUNTS)
        }
    }

    private fun band(l: Double, centre: Double, width: Double) = exp(-((l - centre) / width).pow(2))

    /** Counts per 100 ms for the non-field scenes (the detector response is part of every path). */
    private fun sceneSignal(l: Double, sc: SimScene, now: Long): Double = when (sc) {
        is SimScene.Reflect -> 11_000.0 * det(l) / norm(sc.light) * (SimOptics.light(l, sc.light) *
            SimOptics.reflectance(l, sc.target, now) +
            // Chlorophyll fluorescence, driven by the absorbed light (visible part of the source).
            SimOptics.fluorescenceYield(sc.target, now) * SimOptics.fluorescence(l) * par(sc.light))
        is SimScene.Cuvette -> {
            val a = if (sc.sample == SimSample.DYE) SimOptics.dyeAbsorptivity(l) * (sc.reaction?.concentration(now) ?: sc.concentration)
            else SimOptics.sampleAbsorbance(l, sc.sample)
            11_000.0 * det(l) * SimOptics.cuvetteLight(l) / cuvetteNorm * 0.9 * Math.pow(10.0, -a)
        }
        is SimScene.Transmit -> 11_000.0 * det(l) * SimOptics.cuvetteLight(l) / cuvetteNorm * 0.95 * SimOptics.filter(l, sc.filter)
        is SimScene.Film -> 11_000.0 * det(l) * SimOptics.light(l, SimLight.HALOGEN) / norm(SimLight.HALOGEN) *
            com.rfsat.pocketspectro.tools.FilmThickness.model(l, sc.n, sc.thicknessNm, sc.substrateN) * 1.6
        is SimScene.Fruit -> 11_000.0 * det(l) * SimOptics.light(l, SimLight.HALOGEN) / norm(SimLight.HALOGEN) *
            SimOptics.fruitReflectance(l, fruitProps(sc.seed)).coerceAtLeast(0.002)
        is SimScene.Sun -> {
            val t = sc.clock.at(now)
            val pos = com.rfsat.pocketspectro.tools.Solar.position(siteLat, siteLon, t)
            if (pos.zenithDeg >= 89.0) 0.0 else {
                val m = com.rfsat.pocketspectro.tools.Solar.airmass(pos.zenithDeg)
                9_000.0 * det(l) * SimOptics.solarE0(l) / sunRef * com.rfsat.pocketspectro.tools.Solar.distanceFactor(t) *
                    SimOptics.directTransmission(l, m, sc)
            }
        }
        is SimScene.SunUv -> {
            val t = sc.clock.at(now)
            val pos = com.rfsat.pocketspectro.tools.Solar.position(siteLat, siteLon, t)
            val cosZ = kotlin.math.cos(Math.toRadians(pos.zenithDeg))
            val m = com.rfsat.pocketspectro.tools.Solar.airmass(pos.zenithDeg)
            9_000.0 * det(l) * SimOptics.globalUv(l, cosZ, m, com.rfsat.pocketspectro.tools.Solar.distanceFactor(t), sc) / sunRef
        }
        is SimScene.Raman -> ramanSignal(l, sc)
        is SimScene.Culture -> cuvette(l, sc.od(now) * (600.0 / l).pow(1.5))
        is SimScene.Kit -> cuvette(l, SimOptics.kitAbsorbance(l, sc.method, sc.concentration))
        is SimScene.Indicator -> cuvette(l, SimOptics.indicator(l, sc.dye, sc.pH))
        is SimScene.Fluor -> 11_000.0 * det(l) * (SimOptics.fluorLed(l, sc.preset) + sc.concentration * SimOptics.fluorEmission(l, sc.preset))
        is SimScene.WaterSurface -> 11_000.0 * det(l) * SimOptics.light(l, SimLight.DAYLIGHT) / norm(SimLight.DAYLIGHT) * SimOptics.water(l, sc.water)
        is SimScene.LeafClip -> 11_000.0 * det(l) * SimOptics.cuvetteLight(l) / cuvetteNorm * 0.9 * SimOptics.leafTransmission(l, sc.chlorophyll)
        is SimScene.Gem -> if (sc.fluorescence) 9_000.0 * det(l) * SimOptics.gem(l, sc.name, true)
            else 11_000.0 * det(l) * SimOptics.cuvetteLight(l) / cuvetteNorm * 0.95 * SimOptics.gem(l, sc.name, false)
        is SimScene.Pigment -> 11_000.0 * det(l) * SimOptics.light(l, SimLight.HALOGEN) / norm(SimLight.HALOGEN) * SimOptics.pigment(l, sc.name)
        is SimScene.Skin -> 11_000.0 * det(l) * SimOptics.light(l, SimLight.HALOGEN) / norm(SimLight.HALOGEN) * SimOptics.skin(l, sc.melanin, sc.blood, sc.carotenoid)
        is SimScene.Finger -> 11_000.0 * det(l) * SimOptics.cuvetteLight(l) / cuvetteNorm * SimOptics.finger(l) * 5.0 * fingerPulse(sc, now)
        is SimScene.Display -> 9_000.0 * det(l) * SimOptics.display(l, sc.display, sc.patch) / displayNorm(sc.display)
        is SimScene.Day -> {
            val h = ((sc.clock.at(now) % 86_400_000L + 86_400_000L) % 86_400_000L) / 3_600_000.0
            5_000.0 * det(l) * SimOptics.dayLight(l, h) / dayNorm(h) * SimOptics.dayLux(h) / 400.0
        }
        is SimScene.Emit -> SimOptics.emitterGain(sc.emitter) * sc.intensity * det(l) *
            SimOptics.emission(l, sc.emitter) / norm(sc.emitter)
        SimScene.Field -> 0.0
    }

    /** Detector response of the simulated model (the STS-UV has a UV-enhanced detector). */
    private fun det(l: Double) = if (model == SimModel.UV) SimOptics.detectorUv(l) else SimOptics.detector(l)

    private val cuvetteNorm by lazy { (185..1100).maxOf { det(it.toDouble()) * SimOptics.cuvetteLight(it.toDouble()) } }
    private val sunRef by lazy { SimOptics.solarE0(500.0) }
    private val par = HashMap<SimLight, Double>()
    private fun par(s: SimLight) = par.getOrPut(s) { (450..650).map { SimOptics.light(it.toDouble(), s) }.average() }
    private val fruitCache = HashMap<Long, SimOptics.FruitProps>()
    private fun fruitProps(seed: Long) = synchronized(fruitCache) { fruitCache.getOrPut(seed) { SimOptics.fruit(seed) } }
    private val flameRef = com.rfsat.pocketspectro.tools.Spectra.planck(1100.0, 1250.0)
    private fun flameShape(l: Double) = com.rfsat.pocketspectro.tools.Spectra.planck(l, 1250.0) / flameRef

    /** Raman bands of the material, its fluorescence background and the residual laser line behind a long-pass filter. */
    private fun ramanSignal(l: Double, sc: SimScene.Raman): Double {
        val ref = com.rfsat.pocketspectro.tools.Raman.LIBRARY.firstOrNull { it.name == sc.material } ?: return 0.0
        var raman = 0.0
        for ((shift, w) in ref.bands) {
            val c = com.rfsat.pocketspectro.tools.Raman.wavelength(shift, sc.laserNm)
            raman += w * exp(-((l - c) / 0.95).pow(2))
        }
        val fluor = when {
            sc.material.startsWith("PET") || sc.material.startsWith("Paracetamol") -> 1.0
            sc.material.startsWith("Calcite") || sc.material.startsWith("Diamond") || sc.material.startsWith("Silicon") -> 0.15
            else -> 0.4
        }
        val edge = 1.0 / (1.0 + exp(-(l - (sc.laserNm + 11.0)) / 1.2))
        return det(l) * (edge * (1800.0 * raman + 900.0 * fluor * exp(-((l - 880.0) / 180.0).pow(2))) +
            45.0 * exp(-((l - sc.laserNm) / 0.75).pow(2)))
    }

    private fun cuvette(l: Double, a: Double) = 11_000.0 * det(l) * SimOptics.cuvetteLight(l) / cuvetteNorm * 0.9 * Math.pow(10.0, -a)

    private val dispNorm = HashMap<SimDisplay, Double>()
    private fun displayNorm(d: SimDisplay) = dispNorm.getOrPut(d) { (380..780).maxOf { det(it.toDouble()) * SimOptics.display(it.toDouble(), d, 0) } }
    private val dayNorms = HashMap<Int, Double>()
    private fun dayNorm(h: Double) = synchronized(dayNorms) { dayNorms.getOrPut((h * 2).toInt()) { (380..1000).maxOf { det(it.toDouble()) * SimOptics.dayLight(it.toDouble(), h) } } }

    /** Blood-volume pulse: beats with slightly varying intervals, a dicrotic notch and slow breathing drift. */
    private fun fingerPulse(sc: SimScene.Finger, now: Long): Double {
        val t = (now - sc.startMs) / 1000.0
        val rnd = java.util.Random(sc.seed.toLong())
        var beat = 0.0; var k = 0; var rr = 60.0 / sc.bpm
        while (true) {
            rr = 60.0 / sc.bpm * (1 + 0.04 * kotlin.math.sin(2 * Math.PI * k / 7.0) + 0.02 * rnd.nextGaussian())
            if (beat + rr > t || k > 100_000) break
            beat += rr; k++
        }
        val ph = ((t - beat) / rr).coerceIn(0.0, 1.0)
        val w = exp(-((ph - 0.15) / 0.08).pow(2)) + 0.4 * exp(-((ph - 0.45) / 0.12).pow(2))
        return (1 - 0.02 * w) * (1 + 0.004 * kotlin.math.sin(2 * Math.PI * t / 4.5))
    }

    private fun norm(s: SimLight) = lightNorm.getOrPut(s) {
        (185..1100).maxOf { det(it.toDouble()) * SimOptics.light(it.toDouble(), s) }
    }

    private fun norm(e: SimEmitter) = emitNorm.getOrPut(e) {
        val wl = current.wavelengths()
        var m = 1e-12
        var l = wl.first(); while (l <= wl.last()) { m = maxOf(m, det(l) * SimOptics.emission(l, e)); l += 0.1 }
        m
    }

    private fun line(l: Double, centre: Double) = exp(-((l - centre) / 0.75).pow(2))

    override fun close() {}
}
