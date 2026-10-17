package com.rfsat.pocketspectro.core

import com.rfsat.pocketspectro.obp.StsProtocol
import com.rfsat.pocketspectro.proc.Calibration
import com.rfsat.pocketspectro.usb.ByteTransport
import com.rfsat.pocketspectro.usb.drain
import java.io.IOException

data class DeviceInfo(
    val model: String,
    val serial: String,
    val unbinnedPixels: Int,
    val binningFactor: Int,
    val maxBinningFactor: Int,
    val wavelengthCoefficients: DoubleArray?,
    val nonlinearityCoefficients: DoubleArray?,
    val maxCounts: Int,
    val simulated: Boolean,
    /** K I fire detection is offered only on the NIR variant (STS-NIR), identified from its calibration. */
    val fireCapable: Boolean = false,
    /** USB protocol in use, for display. */
    val interfaceLabel: String = "",
) {
    val pixels: Int get() = unbinnedPixels shr binningFactor

    /** Wavelength of each (binned) pixel; pixel index when the unit holds no wave-cal. */
    fun wavelengths(): DoubleArray = Calibration.binnedWavelengths(wavelengthCoefficients, unbinnedPixels, binningFactor)
}

/** One physical or simulated spectrometer. Calls come from a single acquisition thread. */
interface Spectrometer {
    val info: DeviceInfo
    @Throws(IOException::class) fun setIntegrationTimeMicros(us: Long)
    @Throws(IOException::class) fun setBinningFactor(factor: Int)
    /** Returns false when the unit has no controllable shutter. */
    @Throws(IOException::class) fun setShutter(open: Boolean): Boolean
    /** One raw scan in counts, length = info.pixels. */
    @Throws(IOException::class) fun acquire(): IntArray
    fun close()
}

/**
 * Driver for Ocean Binary Protocol units whose spectrum arrives as one "get spectrum now"
 * reply of little-endian u16 counts: the STS family (supported) and the Spark (experimental).
 */
class ObpSpectrometer private constructor(
    private val io: ByteTransport,
    private val obp: StsProtocol,
    private val model: DeviceCatalog.Model,
    private var current: DeviceInfo,
) : Spectrometer {
    private var integrationUs = 100_000L

    override val info: DeviceInfo get() = current

    override fun setIntegrationTimeMicros(us: Long) {
        val t = us.coerceIn(model.integrationMinUs, model.integrationMaxUs)
        if (!obp.setIntegrationTimeMicros(t)) throw IOException("Spectrometer refused integration time $t us")
        integrationUs = t
    }

    override fun setBinningFactor(factor: Int) {
        val f = factor.coerceIn(0, current.maxBinningFactor)
        if (current.maxBinningFactor == 0) { current = current.copy(binningFactor = 0); return }
        if (!obp.setBinningFactor(f)) throw IOException("Spectrometer refused binning factor $f")
        current = current.copy(binningFactor = obp.binningFactor() ?: f)
    }

    override fun setShutter(open: Boolean): Boolean = obp.setShutter(open)

    override fun acquire(): IntArray = obp.readSpectrum(current.pixels, integrationUs)

    override fun close() = io.close()

    companion object {
        /** Opens and interrogates the unit: serial, wave-cal, nonlinearity, binning. */
        @Throws(IOException::class)
        fun open(io: ByteTransport, model: DeviceCatalog.Model): ObpSpectrometer {
            io.drain()
            val obp = StsProtocol(io)
            val serial = obp.serialNumber() ?: throw IOException("${model.name} did not answer the serial-number query")
            obp.setTriggerMode(0)
            val maxBin = obp.maxBinningFactor() ?: 0
            // Start unbinned so pixel count and calibration line up predictably.
            if (maxBin > 0 && (obp.binningFactor() ?: 0) != 0) obp.setBinningFactor(0)
            val wl = obp.wavelengthCoefficients()
            var name = model.name
            if (model.productId == StsProtocol.PRODUCT_ID_STS && Calibration.isPlausible(wl, model.pixels)) {
                val w = Calibration.binnedWavelengths(wl, model.pixels, 0)
                name = DeviceCatalog.stsVariant(w.first(), w.last())
            }
            val fireCapable = name == "STS-NIR" || (model.productId != StsProtocol.PRODUCT_ID_STS &&
                DeviceCatalog.coversPotassium(DeviceInfo(name, serial, model.pixels, 0, 0, wl, null, model.maxCounts, false)))
            val info = DeviceInfo(
                model = name,
                serial = serial,
                unbinnedPixels = model.pixels,
                binningFactor = 0,
                maxBinningFactor = maxBin,
                wavelengthCoefficients = wl,
                nonlinearityCoefficients = obp.nonlinearityCoefficients(),
                maxCounts = model.maxCounts,
                simulated = false,
                fireCapable = fireCapable,
                interfaceLabel = model.protocol.label,
            )
            return ObpSpectrometer(io, obp, model, info)
        }
    }
}

/** Picks the driver for a catalogued model, or explains why it can't be used yet. */
object SpectrometerFactory {
    @Throws(IOException::class)
    fun open(io: ByteTransport, model: DeviceCatalog.Model): Spectrometer {
        if (!model.usable) throw IOException("${model.name} recognised (${model.protocol.label}); driver planned")
        return when (model.protocol) {
            DeviceCatalog.Protocol.OBP -> ObpSpectrometer.open(io, model)
            DeviceCatalog.Protocol.OOI -> OoiSpectrometer.open(io, model)
            DeviceCatalog.Protocol.OBP2 -> Obp2Spectrometer.open(io, model)
        }
    }
}
