package com.rfsat.pocketspectro.core

import com.rfsat.pocketspectro.obp.ObpMessage
import com.rfsat.pocketspectro.obp.StsProtocol
import com.rfsat.pocketspectro.usb.ByteTransport
import com.rfsat.pocketspectro.usb.drain
import java.io.IOException

/**
 * Driver for the Ocean Binary Protocol 2 generation (Ocean ST, SR2/4/6, HR2/4/6; USB-C, vendor 0x0999).
 * Same framing as OBP with protocol version 0x2000 and new message codes, ported from python-seabreeze
 * (protocol.py OBP2Protocol, features/spectrometer.py SeaBreezeSpectrometerFeatureOBP2/SR2/ST,
 * features/nonlinearity.py NonlinearityCoefficientsFeatureOBP2):
 *   0x00000100 serial, 0x00000C01 + u32 integration (in the model's time base, 10 us on the ST),
 *   0x00000D01 + u8 trigger mode, 0x00001100 wavelength coefficients and 0x00001200 nonlinearity
 *   coefficients (float32 arrays whose first value is skipped), 0x00001C00 spectrum (32-byte metadata
 *   block, then little-endian u16 counts).
 */
class Obp2Spectrometer private constructor(
    private val io: ByteTransport,
    private val obp: StsProtocol,
    private val model: DeviceCatalog.Model,
    override val info: DeviceInfo,
) : Spectrometer {
    private var integrationUs = 100_000L

    override fun setIntegrationTimeMicros(us: Long) {
        val t = us.coerceIn(model.integrationMinUs, model.integrationMaxUs)
        val units = (t / model.integrationBaseUs).coerceAtLeast(1)
        if (!obp.command(SET_ITIME, ObpMessage.u32le(units.toInt()))) throw IOException("${model.name} refused integration time $t us")
        integrationUs = units * model.integrationBaseUs
    }

    override fun setBinningFactor(factor: Int) {}

    override fun setShutter(open: Boolean) = false

    override fun acquire(): IntArray {
        val d = obp.query(GET_SPECTRUM, timeoutMs = (integrationUs / 1000L).toInt() + 2000)
            ?: throw IOException("${model.name} refused the spectrum request")
        return decode(d, info.pixels)
    }

    override fun close() = io.close()

    companion object {
        const val PROTOCOL_VERSION = 0x2000
        const val GET_SERIAL = 0x00000100
        const val SET_ITIME = 0x00000C01
        const val SET_TRIG_MODE = 0x00000D01
        const val GET_WL_COEFFS = 0x00001100
        const val GET_NL_COEFFS = 0x00001200
        const val GET_SPECTRUM = 0x00001C00
        const val METADATA_BYTES = 32

        @Throws(IOException::class)
        fun open(io: ByteTransport, model: DeviceCatalog.Model): Obp2Spectrometer {
            io.drain()
            val obp = StsProtocol(io, PROTOCOL_VERSION)
            val serial = obp.query(GET_SERIAL)?.let { b -> String(b.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.US_ASCII).trim() }
                ?: throw IOException("${model.name} did not answer the serial-number query")
            obp.command(SET_TRIG_MODE, byteArrayOf(0))
            val wl = floats(obp.query(GET_WL_COEFFS))
            val nl = try { floats(obp.query(GET_NL_COEFFS)) } catch (_: Exception) { null }
            // Learn the real pixel count from one short spectrum; the catalogue value is only a guide.
            val shortUs = maxOf(model.integrationMinUs, 10_000L)
            obp.command(SET_ITIME, ObpMessage.u32le((shortUs / model.integrationBaseUs).toInt()))
            val first = obp.query(GET_SPECTRUM, timeoutMs = 3000)
            val pixels = first?.let { (it.size - METADATA_BYTES) / 2 }?.takeIf { it > 16 } ?: model.pixels
            val info = DeviceInfo(
                model = model.name, serial = serial, unbinnedPixels = pixels, binningFactor = 0, maxBinningFactor = 0,
                wavelengthCoefficients = wl, nonlinearityCoefficients = nl, maxCounts = model.maxCounts, simulated = false,
                interfaceLabel = model.protocol.label,
            ).let { it.copy(fireCapable = DeviceCatalog.coversPotassium(it)) }
            return Obp2Spectrometer(io, obp, model, info)
        }

        /** float32 array with the leading value dropped, as python-seabreeze does; null if too short. */
        fun floats(d: ByteArray?): DoubleArray? {
            if (d == null || d.size < 8 || d.size % 4 != 0) return null
            val n = d.size / 4
            return DoubleArray(n - 1) { i -> ObpMessage.floatLe(d, 4 * (i + 1)).toDouble() }
        }

        fun decode(d: ByteArray, pixels: Int): IntArray {
            if (d.size < METADATA_BYTES + 2 * pixels) throw IOException("Spectrum reply too short: ${d.size} bytes for $pixels pixels")
            return IntArray(pixels) { i ->
                val o = METADATA_BYTES + 2 * i
                (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
            }
        }
    }
}
