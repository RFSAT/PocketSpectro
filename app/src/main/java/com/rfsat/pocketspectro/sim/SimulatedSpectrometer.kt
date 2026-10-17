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
class SimulatedSpectrometer(seed: Int = 1, private val nir: Boolean = true) : Spectrometer {
    private val rnd = Random(seed)
    private var integrationUs = 100_000L
    private var shutterOpen = true

    @Volatile var fireOn = false
    /** Places a sample with water (970 nm) and C-H (915 nm) absorption bands in the beam. */
    @Volatile var sampleOn = false

    private var current = DeviceInfo(
        model = if (nir) "STS-NIR (simulated)" else "STS-VIS (simulated)",
        serial = "SIM00001",
        unbinnedPixels = StsProtocol.UNBINNED_PIXELS,
        binningFactor = 0,
        maxBinningFactor = 3,
        wavelengthCoefficients = if (nir) doubleArrayOf(636.0, 0.473, -2.0e-5, 0.0) else doubleArrayOf(338.0, 0.46, -1.5e-5, 0.0),
        nonlinearityCoefficients = doubleArrayOf(1.0, 1.2e-6, -1.0e-10, 0.0, 0.0, 0.0, 0.0, 0.0),
        maxCounts = StsProtocol.MAX_COUNTS,
        simulated = true,
        fireCapable = nir,
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
        return IntArray(wl.size) { i ->
            val l = wl[i]
            var signal = 0.0
            if (shutterOpen) {
                // Smooth continuum peaking near 850 nm with a vegetation edge around 720 nm.
                val cont = 3200.0 * exp(-((l - 860.0) / 210.0).pow(2)) * (1.0 / (1.0 + exp(-(l - 715.0) / 12.0)) * 0.7 + 0.3)
                val trans = if (sampleOn) 0.55 * exp(-0.9 * band(l, 970.0, 28.0) - 0.4 * band(l, 915.0, 14.0) - 0.15 * band(l, 740.0, 30.0)) else 1.0
                signal += cont * tScale * trans
                if (fireOn) {
                    signal += 900.0 * tScale * (line(l, 766.5) + 0.65 * line(l, 769.9))
                }
            }
            val dark = 1480.0 + 12.0 * tScale
            val noise = rnd.nextDouble(-1.0, 1.0) * (8.0 + kotlin.math.sqrt(signal.coerceAtLeast(0.0)) * 0.6)
            ((dark + signal) * bin / bin + noise).toInt().coerceIn(0, StsProtocol.MAX_COUNTS)
        }
    }

    private fun band(l: Double, centre: Double, width: Double) = exp(-((l - centre) / width).pow(2))

    private fun line(l: Double, centre: Double) = exp(-((l - centre) / 0.75).pow(2))

    override fun close() {}
}
