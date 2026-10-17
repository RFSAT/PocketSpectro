package com.rfsat.pocketspectro.obp

import com.rfsat.pocketspectro.usb.ByteTransport
import com.rfsat.pocketspectro.usb.readFully
import java.io.IOException

/**
 * STS command set over OBP, ported from the SeaBreeze exchanges used by the Pi daemon:
 * OBPTransaction (query / command-with-ACK), OBPRequestSpectrumExchange +
 * OBPReadSpectrumExchange, wave-cal, nonlinearity, integration time, binning and shutter.
 *
 * Not thread-safe: the caller serialises access (one acquisition loop owns the device).
 */
class StsProtocol(private val io: ByteTransport, private val protocolVersion: Int = ObpMessage.PROTOCOL_VERSION) {
    /** Spectrum request code; switches to GET_RAW_SPECTRUM_NOW if a unit refuses the original one. */
    private var spectrumCode = ObpTypes.GET_SPECTRUM_NOW

    /** Sends a query and returns the response data, or null on NACK / unexpected type. */
    fun query(type: Int, data: ByteArray = ByteArray(0), timeoutMs: Int = CONTROL_TIMEOUT_MS): ByteArray? {
        io.write(ObpMessage(type, protocolVersion = protocolVersion, data = data).toBytes(), timeoutMs)
        val reply = readMessage(timeoutMs)
        if (reply.isNack || reply.messageType != type) return null
        return reply.data
    }

    /** Sends a command with ACK requested; true when the device acknowledged it. */
    fun command(type: Int, data: ByteArray = ByteArray(0), timeoutMs: Int = CONTROL_TIMEOUT_MS): Boolean {
        io.write(ObpMessage(type, flags = ObpMessage.FLAG_ACK_REQUESTED, protocolVersion = protocolVersion, data = data).toBytes(), timeoutMs)
        val reply = readMessage(timeoutMs)
        return !reply.isNack && reply.messageType == type && reply.isAck
    }

    /** Reads one complete OBP message: the 64-byte block, then whatever it says remains. */
    fun readMessage(timeoutMs: Int): ObpMessage {
        val head = io.readFully(ObpMessage.MIN_SIZE, timeoutMs)
        val total = try {
            ObpMessage.totalLengthFromHeader(head)
        } catch (e: IllegalArgumentException) {
            throw IOException("Bad OBP reply: ${e.message}")
        }
        val full = if (total > head.size) head + io.readFully(total - head.size, timeoutMs) else head
        return try {
            ObpMessage.parse(full)
        } catch (e: IllegalArgumentException) {
            throw IOException("Bad OBP reply: ${e.message}")
        }
    }

    // ---- device information ------------------------------------------------------------

    fun serialNumber(): String? =
        query(ObpTypes.GET_SERIAL_NUMBER)?.let { bytes ->
            String(bytes.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.US_ASCII).trim()
        }

    /** The four wavelength polynomial coefficients c0..c3 (lambda = c0 + c1 p + c2 p^2 + c3 p^3). */
    fun wavelengthCoefficients(): DoubleArray? {
        val out = DoubleArray(4)
        for (i in 0 until 4) {
            val d = query(ObpTypes.GET_WL_COEFF, byteArrayOf(i.toByte())) ?: return null
            if (d.size < 4) return null
            out[i] = ObpMessage.floatLe(d).toDouble()
        }
        return out
    }

    /** Nonlinearity correction polynomial k0..kn, or null when the unit has none programmed. */
    fun nonlinearityCoefficients(): DoubleArray? {
        val countData = query(ObpTypes.GET_NL_COEFF_COUNT) ?: return null
        if (countData.isEmpty()) return null
        val count = countData[0].toInt() and 0xFF
        if (count == 0 || count > 16) return null
        val out = DoubleArray(count)
        for (i in 0 until count) {
            val d = query(ObpTypes.GET_NL_COEFF, byteArrayOf(i.toByte())) ?: return null
            if (d.size < 4) return null
            out[i] = ObpMessage.floatLe(d).toDouble()
        }
        return out
    }

    fun binningFactor(): Int? = query(ObpTypes.GET_BINNING_FACTOR)?.firstOrNull()?.toInt()?.and(0xFF)

    fun maxBinningFactor(): Int? = query(ObpTypes.GET_MAX_BINNING_FACTOR)?.firstOrNull()?.toInt()?.and(0xFF)

    // ---- control -----------------------------------------------------------------------

    /** Integration time in microseconds; the caller clamps to the model's limits (STS: 10 us .. 85 s). */
    fun setIntegrationTimeMicros(us: Long): Boolean =
        command(ObpTypes.SET_ITIME_USEC, ObpMessage.u32le(us.coerceIn(1L, 0xFFFFFFFFL).toInt()))

    fun setBinningFactor(factor: Int): Boolean = command(ObpTypes.SET_BINNING_FACTOR, byteArrayOf(factor.toByte()))

    /** Built-in shutter (true = open). Older units may NACK this. */
    fun setShutter(open: Boolean): Boolean = command(ObpTypes.SET_SHUTTER, byteArrayOf(if (open) 1 else 0))

    /** 0 = normal (software) trigger. */
    fun setTriggerMode(mode: Int): Boolean = command(ObpTypes.SET_TRIG_MODE, byteArrayOf(mode.toByte()))

    // ---- spectra -----------------------------------------------------------------------

    /**
     * Requests and reads one spectrum ("get spectrum now"): 64-byte request without ACK,
     * reply of 2*pixels + 64 bytes holding little-endian u16 counts.
     */
    fun readSpectrum(pixels: Int, integrationUs: Long): IntArray {
        val timeout = (integrationUs / 1000L).toInt() + SPECTRUM_MARGIN_MS
        io.write(ObpMessage(spectrumCode, protocolVersion = protocolVersion).toBytes(), CONTROL_TIMEOUT_MS)
        var reply = readMessage(timeout)
        if (reply.isNack && spectrumCode == ObpTypes.GET_SPECTRUM_NOW) {
            // The 2015 SeaBreeze used by the Pi asks for 0x00101000; current python-seabreeze asks STS-class
            // units for 0x00101100. Fall back once and remember which one this unit answers.
            spectrumCode = ObpTypes.GET_RAW_SPECTRUM_NOW
            io.write(ObpMessage(spectrumCode, protocolVersion = protocolVersion).toBytes(), CONTROL_TIMEOUT_MS)
            reply = readMessage(timeout)
        }
        if (reply.isNack || reply.messageType != spectrumCode) {
            throw IOException("Unexpected reply 0x%08X to spectrum request".format(reply.messageType))
        }
        val d = reply.data
        if (d.size < pixels * 2) throw IOException("Spectrum reply too short: ${d.size} bytes for $pixels pixels")
        return IntArray(pixels) { i -> (d[2 * i].toInt() and 0xFF) or ((d[2 * i + 1].toInt() and 0xFF) shl 8) }
    }

    companion object {
        const val VENDOR_ID = 0x2457
        const val PRODUCT_ID_STS = 0x4000
        const val UNBINNED_PIXELS = 1024
        const val MAX_COUNTS = 16383
        const val INTEGRATION_MIN_US = 10L
        const val INTEGRATION_MAX_US = 85_000_000L
        const val CONTROL_TIMEOUT_MS = 1000
        const val SPECTRUM_MARGIN_MS = 2000
    }
}
