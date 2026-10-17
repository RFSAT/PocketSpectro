package com.rfsat.pocketspectro.core

import com.rfsat.pocketspectro.usb.ByteTransport
import com.rfsat.pocketspectro.usb.drain
import com.rfsat.pocketspectro.usb.readFullyFrom
import java.io.IOException
import kotlin.math.roundToInt

/** How a classic (OOI-protocol) model delivers its spectrum. From python-seabreeze features/spectrometer.py. */
enum class OoiReadout {
    /** USB2000+, HR2000+, Maya, Torus: whole frame from the high-speed endpoint (0x82) when on USB 2.0. */
    FPGA,
    /** USB4000, HR4000: first 2048 bytes from 0x86, the rest from 0x82. */
    FPGA_4K,
    /** USB2000, USB650: 64-byte packets alternate low and high bytes; 12-bit values. */
    OOI_2K,
    /** NIRQuest, Jaz: whole frame from the high-speed endpoint. */
    PLAIN,
}

/** Gain normalisation from EEPROM slot 17 (saturation level), as in python-seabreeze. */
enum class OoiGain { NONE, U16_AT_6, U32_AT_6, U16_AT_2_ALT }

data class OoiSpec(
    val readout: OoiReadout,
    val epOut: Int,
    val epLowIn: Int,
    val epHighIn: Int,
    val epHighIn2: Int? = null,
    val syncByte: Boolean = true,
    val gain: OoiGain = OoiGain.NONE,
)

/**
 * Driver for the classic Ocean Optics command protocol ("OOI": USB2000+/Flame-S, USB4000/Flame-T,
 * USB650, USB2000, HR2000+, HR4000, Maya2000 Pro, Torus, Jaz, NIRQuest). Ported from python-seabreeze
 * (protocol.py OOIProtocol, features/spectrometer.py, features/eeprom.py, features/nonlinearity.py).
 *
 * Commands are single opcodes followed by little-endian arguments:
 *   0x01 initialise, 0x02 + u32 integration (in the model's time base), 0x05 + slot read EEPROM
 *   (17-byte reply on the low-speed endpoint), 0x09 request spectrum, 0x0A + u16 trigger mode,
 *   0xFE status (16 bytes; byte 14 = 0x80 when running at USB 2.0 high speed).
 * EEPROM slots: 0 serial, 1-4 wavelength coefficients, 6-13 nonlinearity coefficients, 14 their order,
 * 17 saturation level (raw).
 */
class OoiSpectrometer private constructor(
    private val io: ByteTransport,
    private val model: DeviceCatalog.Model,
    private val spec: OoiSpec,
    private var highSpeed: Boolean,
    private val normalisation: Double,
    override val info: DeviceInfo,
) : Spectrometer {
    private var integrationUs = 100_000L

    override fun setIntegrationTimeMicros(us: Long) {
        val t = us.coerceIn(model.integrationMinUs, model.integrationMaxUs)
        val units = (t / model.integrationBaseUs).coerceAtLeast(1)
        send(byteArrayOf(0x02, units.toByte(), (units shr 8).toByte(), (units shr 16).toByte(), (units shr 24).toByte()))
        integrationUs = units * model.integrationBaseUs
    }

    /** No hardware binning in this family. */
    override fun setBinningFactor(factor: Int) {}

    /** No controllable shutter in this family. */
    override fun setShutter(open: Boolean) = false

    override fun acquire(): IntArray {
        val raw = model.rawLength
        val timeout = (integrationUs / 1000L).toInt() + 2000
        send(byteArrayOf(0x09))
        val frame = when {
            spec.readout == OoiReadout.FPGA_4K && highSpeed && spec.epHighIn2 != null -> {
                val first = io.readFullyFrom(spec.epHighIn2, 2048, timeout)
                first + io.readFullyFrom(spec.epHighIn, raw - 2048, timeout)
            }
            (spec.readout == OoiReadout.FPGA || spec.readout == OoiReadout.FPGA_4K) && !highSpeed ->
                io.readFullyFrom(spec.epLowIn, raw, timeout)
            else -> io.readFullyFrom(spec.epHighIn, raw, timeout)
        }
        if (spec.syncByte && frame.last() != SYNC) {
            io.drain()
            throw IOException("Spectrum frame lost sync (last byte 0x%02X)".format(frame.last()))
        }
        return decode(frame, model.pixels, spec.readout, normalisation, model.maxCounts)
    }

    override fun close() = io.close()

    private fun send(cmd: ByteArray) = io.writeTo(spec.epOut, cmd, 1000)

    companion object {
        private const val SYNC: Byte = 0x69

        @Throws(IOException::class)
        fun open(io: ByteTransport, model: DeviceCatalog.Model): OoiSpectrometer {
            val spec = model.ooi ?: throw IOException("${model.name}: no OOI description")
            io.writeTo(spec.epOut, byteArrayOf(0x01), 1000) // initialise
            Thread.sleep(100)
            io.drain()
            for (ep in listOf(spec.epLowIn, spec.epHighIn) + listOfNotNull(spec.epHighIn2)) drainEndpoint(io, ep)

            val serial = readSlot(io, spec, 0).trim().ifEmpty { throw IOException("${model.name} did not return a serial number") }
            val wl = (1..4).map { readSlot(io, spec, it).trim().toDoubleOrNull() ?: Double.NaN }
            val wlCoeffs = if (wl.all { it.isFinite() }) wl.toDoubleArray() else null
            val nl = try {
                val order = readSlot(io, spec, 14).trim().toDoubleOrNull()?.toInt() ?: -1
                if (order in 0..7) (6..6 + order).map { readSlot(io, spec, it).trim().toDouble() }.toDoubleArray() else null
            } catch (_: Exception) { null }

            var highSpeed = true
            if (spec.readout == OoiReadout.FPGA || spec.readout == OoiReadout.FPGA_4K) {
                io.writeTo(spec.epOut, byteArrayOf(0xFE.toByte()), 1000)
                val status = io.readFullyFrom(spec.epLowIn, 16, 1000)
                highSpeed = (status[14].toInt() and 0xFF) == 0x80
            }
            val norm = saturationNormalisation(io, spec, model.maxCounts)
            io.writeTo(spec.epOut, byteArrayOf(0x0A, 0, 0), 1000) // normal trigger mode

            val info = DeviceInfo(
                model = model.name, serial = serial, unbinnedPixels = model.pixels, binningFactor = 0, maxBinningFactor = 0,
                wavelengthCoefficients = wlCoeffs, nonlinearityCoefficients = nl, maxCounts = model.maxCounts, simulated = false,
                interfaceLabel = model.protocol.label,
            ).let { it.copy(fireCapable = DeviceCatalog.coversPotassium(it)) }
            return OoiSpectrometer(io, model, spec, highSpeed, norm, info)
        }

        /** EEPROM slot as text: reply is 0x05, slot, then ASCII up to the first NUL. */
        fun readSlot(io: ByteTransport, spec: OoiSpec, slot: Int): String {
            io.writeTo(spec.epOut, byteArrayOf(0x05, slot.toByte()), 1000)
            val r = io.readFullyFrom(spec.epLowIn, 17, 1000)
            if (r[0] != 0x05.toByte() || (r[1].toInt() and 0xFF) != slot) throw IOException("EEPROM slot $slot: unexpected reply")
            return readRawSlotText(r)
        }

        fun readRawSlotText(r: ByteArray): String {
            val end = (2 until r.size).firstOrNull { r[it] == 0.toByte() } ?: r.size
            return String(r, 2, end - 2, Charsets.US_ASCII)
        }

        private fun saturationNormalisation(io: ByteTransport, spec: OoiSpec, maxCounts: Int): Double {
            if (spec.gain == OoiGain.NONE) return 1.0
            return try {
                io.writeTo(spec.epOut, byteArrayOf(0x05, 17), 1000)
                val r = io.readFullyFrom(spec.epLowIn, 17, 1000)
                val sat = when (spec.gain) {
                    OoiGain.U16_AT_6 -> u16(r, 6)
                    OoiGain.U32_AT_6 -> u16(r, 6) or (u16(r, 8) shl 16)
                    OoiGain.U16_AT_2_ALT -> u16(r, 2)
                    OoiGain.NONE -> 0
                }
                val unset = if (spec.gain == OoiGain.U16_AT_2_ALT) sat <= 32768 || sat > maxCounts else sat == 0
                if (unset) 1.0 else maxCounts.toDouble() / sat
            } catch (_: Exception) { 1.0 }
        }

        private fun drainEndpoint(io: ByteTransport, ep: Int) {
            if (!io.hasEndpoint(ep)) return
            val b = ByteArray(4096)
            repeat(8) { if ((try { io.readFrom(ep, b, 0, b.size, 15) } catch (_: Exception) { 0 }) <= 0) return }
        }

        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        /** Frame bytes to counts. OOI_2K packets interleave 64 low bytes then 64 high bytes. */
        fun decode(frame: ByteArray, pixels: Int, readout: OoiReadout, norm: Double, maxCounts: Int): IntArray {
            val n = pixels * 2
            val bytes = if (readout == OoiReadout.OOI_2K) {
                ByteArray(n) { i -> frame[(i / 2) % 64 + (i % 2) * 64 + (i / 128) * 128] }
            } else frame
            return IntArray(pixels) { p ->
                var v = (bytes[2 * p].toInt() and 0xFF) or ((bytes[2 * p + 1].toInt() and 0xFF) shl 8)
                if (readout == OoiReadout.OOI_2K) v = v and 0x0FFF
                if (norm != 1.0) (v * norm).roundToInt().coerceIn(0, maxCounts) else v
            }
        }
    }
}
