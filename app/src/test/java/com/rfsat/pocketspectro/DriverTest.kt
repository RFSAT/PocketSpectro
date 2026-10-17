package com.rfsat.pocketspectro

import com.rfsat.pocketspectro.core.DeviceCatalog
import com.rfsat.pocketspectro.core.Obp2Spectrometer
import com.rfsat.pocketspectro.core.OoiReadout
import com.rfsat.pocketspectro.core.OoiSpectrometer
import com.rfsat.pocketspectro.core.SpectrometerFactory
import com.rfsat.pocketspectro.obp.ObpMessage
import com.rfsat.pocketspectro.usb.ByteTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake classic (OOI) spectrometer with per-endpoint reply queues, answering the commands
 * the way python-seabreeze expects: EEPROM on the low-speed endpoint, frames on 0x82 / 0x86.
 */
class FakeOoi(
    private val pixels: Int,
    private val readout: OoiReadout,
    private val highSpeed: Boolean = true,
    private val slots: Map<Int, String> = mapOf(
        0 to "USB2+F12345", 1 to "339.5", 2 to "0.3815", 3 to "-1.6e-05", 4 to "-2.1e-10",
        14 to "2", 6 to "0.95", 7 to "1.2e-05", 8 to "-3.0e-10",
    ),
    private val saturation: Int = 0,
) : ByteTransport {
    private val queues = HashMap<Int, ArrayDeque<Byte>>()
    val sent = ArrayList<ByteArray>()
    var integrationUnits = -1L

    private fun q(ep: Int) = queues.getOrPut(ep) { ArrayDeque() }
    private fun push(ep: Int, b: ByteArray) = b.forEach { q(ep).addLast(it) }
    fun pixelValue(i: Int) = (100 + i) and if (readout == OoiReadout.OOI_2K) 0x0FFF else 0xFFFF

    override fun write(data: ByteArray, timeoutMs: Int) = writeTo(0x01, data, timeoutMs)

    override fun writeTo(endpoint: Int, data: ByteArray, timeoutMs: Int) {
        sent += data
        val low = if (readout == OoiReadout.OOI_2K) 0x87 else 0x81
        when (data[0].toInt() and 0xFF) {
            0x02 -> integrationUnits = (data[1].toLong() and 0xFF) or ((data[2].toLong() and 0xFF) shl 8) or
                ((data[3].toLong() and 0xFF) shl 16) or ((data[4].toLong() and 0xFF) shl 24)
            0x05 -> {
                val slot = data[1].toInt() and 0xFF
                val r = ByteArray(17); r[0] = 0x05; r[1] = slot.toByte()
                if (slot == 17) { r[6] = (saturation and 0xFF).toByte(); r[7] = (saturation shr 8).toByte() }
                else (slots[slot] ?: "").toByteArray().copyInto(r, 2)
                push(low, r)
            }
            0xFE -> { val r = ByteArray(16); r[14] = if (highSpeed) 0x80.toByte() else 0; push(low, r) }
            0x09 -> {
                val px = ByteArray(pixels * 2)
                for (i in 0 until pixels) { val v = pixelValue(i); px[2 * i] = v.toByte(); px[2 * i + 1] = (v shr 8).toByte() }
                val frame = if (readout == OoiReadout.OOI_2K) {
                    // Re-pack into 64 low bytes then 64 high bytes per 128-byte block.
                    val f = ByteArray(px.size)
                    for (i in px.indices) f[(i / 2) % 64 + (i % 2) * 64 + (i / 128) * 128] = px[i]
                    f
                } else px
                val full = frame + byteArrayOf(0x69)
                when {
                    readout == OoiReadout.FPGA_4K && highSpeed -> { push(0x86, full.copyOfRange(0, 2048)); push(0x82, full.copyOfRange(2048, full.size)) }
                    (readout == OoiReadout.FPGA || readout == OoiReadout.FPGA_4K) && !highSpeed -> push(0x81, full)
                    else -> push(0x82, full)
                }
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int) = readFrom(0x81, buffer, offset, length, timeoutMs)

    override fun readFrom(endpoint: Int, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val qq = q(endpoint)
        if (qq.isEmpty()) return -1
        val n = minOf(length, 512, qq.size)
        for (i in 0 until n) buffer[offset + i] = qq.removeFirst()
        return n
    }

    override fun hasEndpoint(endpoint: Int) = true
    override fun close() {}
}

/** Fake OBP2 (Ocean ST) answering with protocol version 0x2000 frames. */
class FakeObp2(private val pixels: Int = 1516) : ByteTransport {
    private val pending = ArrayDeque<Byte>()
    var integrationUnits = -1
    var lastVersion = 0

    override fun write(data: ByteArray, timeoutMs: Int) {
        val m = ObpMessage.parse(data)
        lastVersion = m.protocolVersion
        fun resp(d: ByteArray) = ObpMessage(m.messageType, ObpMessage.FLAG_RESPONSE, protocolVersion = 0x2000, data = d)
        val ack = ObpMessage(m.messageType, ObpMessage.FLAG_RESPONSE or ObpMessage.FLAG_ACK, protocolVersion = 0x2000)
        fun floats(vararg f: Float) = f.fold(ByteArray(0)) { acc, v -> acc + ObpMessage.u32le(java.lang.Float.floatToIntBits(v)) }
        val reply = when (m.messageType) {
            Obp2Spectrometer.GET_SERIAL -> resp("ST01234".toByteArray())
            Obp2Spectrometer.SET_ITIME -> { integrationUnits = ObpMessage.getU32(m.data, 0); ack }
            Obp2Spectrometer.SET_TRIG_MODE -> ack
            Obp2Spectrometer.GET_WL_COEFFS -> resp(floats(4f, 641.2f, 0.295f, -1.1e-5f, 0f))
            Obp2Spectrometer.GET_NL_COEFFS -> resp(floats(3f, 1f, 2e-6f))
            Obp2Spectrometer.GET_SPECTRUM -> {
                val d = ByteArray(32 + pixels * 2)
                for (i in 0 until pixels) { val v = 500 + i; d[32 + 2 * i] = v.toByte(); d[33 + 2 * i] = (v shr 8).toByte() }
                resp(d)
            }
            else -> ObpMessage(m.messageType, ObpMessage.FLAG_RESPONSE or ObpMessage.FLAG_NACK, protocolVersion = 0x2000)
        }
        reply.toBytes().forEach { pending.addLast(it) }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (pending.isEmpty()) return -1
        val n = minOf(length, 512, pending.size)
        for (i in 0 until n) buffer[offset + i] = pending.removeFirst()
        return n
    }

    override fun close() {}
}

class DriverTest {
    private fun model(pid: Int, vid: Int = DeviceCatalog.OCEAN_VID) = DeviceCatalog.find(vid, pid)!!

    @Test fun usb2000PlusReadsEepromAndHighSpeedFrame() {
        val fake = FakeOoi(2048, OoiReadout.FPGA)
        val dev = SpectrometerFactory.open(fake, model(0x101E))
        assertEquals("USB2+F12345", dev.info.serial)
        assertEquals(339.5, dev.info.wavelengthCoefficients!![0], 1e-9)
        assertEquals(3, dev.info.nonlinearityCoefficients!!.size)
        dev.setIntegrationTimeMicros(100_000)
        assertEquals(100_000L, fake.integrationUnits)
        val s = dev.acquire()
        assertEquals(2048, s.size)
        assertEquals(100, s[0]); assertEquals(2147, s[2047])
        assertFalse(dev.info.fireCapable) // VIS calibration starting at 339.5 nm
    }

    @Test fun usb4000SplitsFrameAcrossEndpoints() {
        val dev = SpectrometerFactory.open(FakeOoi(3840, OoiReadout.FPGA_4K), model(0x1022))
        val s = dev.acquire()
        assertEquals(3840, s.size)
        assertEquals(100 + 3839, s[3839])
    }

    @Test fun lowSpeedFallsBackToEndpoint81() {
        val dev = SpectrometerFactory.open(FakeOoi(2048, OoiReadout.FPGA, highSpeed = false), model(0x101E))
        assertEquals(2147, dev.acquire()[2047])
    }

    @Test fun usb650UnshufflesPacketsAndUsesMillisecondBase() {
        val fake = FakeOoi(2048, OoiReadout.OOI_2K)
        val dev = SpectrometerFactory.open(fake, model(0x1014))
        dev.setIntegrationTimeMicros(50_000)
        assertEquals(50L, fake.integrationUnits) // 1 ms units
        val s = dev.acquire()
        for (i in listOf(0, 63, 64, 1000, 2047)) assertEquals(fake.pixelValue(i), s[i])
    }

    @Test fun gainNormalisationFromSaturationSlot() {
        val dev = SpectrometerFactory.open(FakeOoi(2048, OoiReadout.FPGA, saturation = 32767), model(0x101E))
        val s = dev.acquire()
        assertEquals(Math.round(100 * 65535.0 / 32767).toInt(), s[0])
    }

    @Test fun nirCalibratedOoiUnitOffersFireDetection() {
        val nir = mapOf(0 to "USB2+NIR01", 1 to "650.0", 2 to "0.22", 3 to "0", 4 to "0", 14 to "0", 6 to "1.0")
        val dev = OoiSpectrometer.open(FakeOoi(2048, OoiReadout.FPGA, slots = nir), model(0x101E))
        assertTrue(dev.info.fireCapable)
    }

    @Test fun oceanStOverObp2() {
        val fake = FakeObp2()
        val dev = SpectrometerFactory.open(fake, model(0x1000, DeviceCatalog.OCEAN_VID_OBP2))
        assertEquals(0x2000, fake.lastVersion)
        assertEquals("ST01234", dev.info.serial)
        assertEquals(1516, dev.info.pixels)
        assertArrayEquals(doubleArrayOf(641.2, 0.295, -1.1e-5, 0.0), dev.info.wavelengthCoefficients!!, 1e-3)
        assertEquals(2, dev.info.nonlinearityCoefficients!!.size)
        dev.setIntegrationTimeMicros(100_000)
        assertEquals(10_000, fake.integrationUnits) // 10 us units on the ST
        val s = dev.acquire()
        assertEquals(500, s[0]); assertEquals(500 + 1515, s[1515])
        assertTrue(dev.info.fireCapable) // ST-NIR range 641-1080 nm
    }

    @Test fun plannedModelsAreRefused() {
        val e = runCatching { SpectrometerFactory.open(FakeObp2(), model(0x4004)) }.exceptionOrNull()
        assertTrue(e?.message?.contains("planned") == true)
    }
}
