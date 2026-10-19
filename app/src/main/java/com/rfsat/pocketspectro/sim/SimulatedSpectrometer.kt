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
                }
            }
            val dark = 1480.0 + 12.0 * tScale
            val noise = rnd.nextDouble(-1.0, 1.0) * (8.0 + kotlin.math.sqrt(signal.coerceAtLeast(0.0)) * 0.6)
            ((dark + signal) * bin / bin + noise).toInt().coerceIn(0, StsProtocol.MAX_COUNTS)
        }
    }

    private fun band(l: Double, centre: Double, width: Double) = exp(-((l - centre) / width).pow(2))

    /** Counts per 100 ms for the non-field scenes (the detector response is part of every path). */
    private fun sceneSignal(l: Double, sc: SimScene, now: Long): Double = when (sc) {
        is SimScene.Reflect -> 11_000.0 * SimOptics.detector(l) * SimOptics.light(l, sc.light) / norm(sc.light) *
            SimOptics.reflectance(l, sc.target, now)
        is SimScene.Cuvette -> {
            val c = sc.reaction?.concentration(now) ?: sc.concentration
            11_000.0 * SimOptics.detector(l) * SimOptics.light(l, SimLight.HALOGEN) / norm(SimLight.HALOGEN) *
                0.9 * Math.pow(10.0, -SimOptics.dyeAbsorptivity(l) * c)
        }
        is SimScene.Emit -> SimOptics.emitterGain(sc.emitter) * sc.intensity * SimOptics.detector(l) *
            SimOptics.emission(l, sc.emitter) / norm(sc.emitter)
        SimScene.Field -> 0.0
    }

    private fun norm(s: SimLight) = lightNorm.getOrPut(s) {
        (185..1100).maxOf { SimOptics.detector(it.toDouble()) * SimOptics.light(it.toDouble(), s) }
    }

    private fun norm(e: SimEmitter) = emitNorm.getOrPut(e) {
        val wl = current.wavelengths()
        var m = 1e-12
        var l = wl.first(); while (l <= wl.last()) { m = maxOf(m, SimOptics.detector(l) * SimOptics.emission(l, e)); l += 0.1 }
        m
    }

    private fun line(l: Double, centre: Double) = exp(-((l - centre) / 0.75).pow(2))

    override fun close() {}
}
