package com.rfsat.pocketspectro

import com.rfsat.pocketspectro.obp.ObpMessage
import com.rfsat.pocketspectro.obp.ObpTypes
import com.rfsat.pocketspectro.obp.StsProtocol
import com.rfsat.pocketspectro.usb.ByteTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake STS answering OBP requests the way the SeaBreeze exchanges expect. */
class FakeSts(
    val serial: String = "S01234",
    val wl: FloatArray = floatArrayOf(636f, 0.473f, -2.0e-5f, 0f),
    val nl: FloatArray? = floatArrayOf(1f, 1e-6f),
    val pixels: Int = 1024,
    /** Deliver replies in chunks of this size to exercise reassembly. */
    val chunk: Int = 64,
    /** Emulate a unit that only answers the newer GET_RAW_SPECTRUM_NOW (0x00101100). */
    val rawSpectrumOnly: Boolean = false,
) : ByteTransport {
    val sent = ArrayList<ObpMessage>()
    private val pending = ArrayDeque<Byte>()
    var integrationUs = -1
    var shutterOpen = true

    override fun write(data: ByteArray, timeoutMs: Int) {
        val m = ObpMessage.parse(data)
        sent += m
        val ackFlags = ObpMessage.FLAG_RESPONSE or ObpMessage.FLAG_ACK
        val reply: ObpMessage = when (m.messageType) {
            ObpTypes.GET_SERIAL_NUMBER -> resp(m, serial.toByteArray())
            ObpTypes.GET_WL_COEFF -> resp(m, ObpMessage.u32le(java.lang.Float.floatToIntBits(wl[m.data[0].toInt()])))
            ObpTypes.GET_NL_COEFF_COUNT -> if (nl == null) nack(m) else resp(m, byteArrayOf(nl.size.toByte()))
            ObpTypes.GET_NL_COEFF -> resp(m, ObpMessage.u32le(java.lang.Float.floatToIntBits(nl!![m.data[0].toInt()])))
            ObpTypes.GET_BINNING_FACTOR -> resp(m, byteArrayOf(0))
            ObpTypes.GET_MAX_BINNING_FACTOR -> resp(m, byteArrayOf(3))
            ObpTypes.SET_ITIME_USEC -> { integrationUs = ObpMessage.getU32(m.data, 0); ObpMessage(m.messageType, ackFlags) }
            ObpTypes.SET_SHUTTER -> { shutterOpen = m.data[0] != 0.toByte(); ObpMessage(m.messageType, ackFlags) }
            ObpTypes.SET_TRIG_MODE, ObpTypes.SET_BINNING_FACTOR -> ObpMessage(m.messageType, ackFlags)
            ObpTypes.GET_SPECTRUM_NOW, ObpTypes.GET_RAW_SPECTRUM_NOW -> if (rawSpectrumOnly && m.messageType == ObpTypes.GET_SPECTRUM_NOW) nack(m) else {
                val d = ByteArray(pixels * 2)
                for (i in 0 until pixels) { val v = 1000 + i; d[2 * i] = (v and 0xFF).toByte(); d[2 * i + 1] = (v shr 8).toByte() }
                resp(m, d)
            }
            else -> nack(m)
        }
        reply.toBytes().forEach { pending.addLast(it) }
    }

    private fun resp(m: ObpMessage, d: ByteArray) = ObpMessage(m.messageType, ObpMessage.FLAG_RESPONSE, data = d)
    private fun nack(m: ObpMessage) = ObpMessage(m.messageType, ObpMessage.FLAG_RESPONSE or ObpMessage.FLAG_NACK)

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (pending.isEmpty()) return -1
        val n = minOf(length, chunk, pending.size)
        for (i in 0 until n) buffer[offset + i] = pending.removeFirst()
        return n
    }

    override fun close() {}
}

class ObpTest {

    @Test fun headerMatchesSeaBreezeLayout() {
        val b = ObpMessage(ObpTypes.SET_ITIME_USEC, flags = ObpMessage.FLAG_ACK_REQUESTED, data = ObpMessage.u32le(200_000)).toBytes()
        assertEquals(64, b.size)
        assertEquals(0xC1.toByte(), b[0]); assertEquals(0xC0.toByte(), b[1])
        assertEquals(0x00, b[2].toInt()); assertEquals(0x11, b[3].toInt()) // protocol 0x1100 LE
        assertEquals(4, b[4].toInt()) // ACK requested
        assertArrayEquals(byteArrayOf(0x10, 0x00, 0x11, 0x00), b.copyOfRange(8, 12)) // 0x00110010 LE
        assertEquals(4, b[23].toInt()) // immediate length
        assertArrayEquals(ObpMessage.u32le(200_000), b.copyOfRange(24, 28))
        assertEquals(20, ObpMessage.getU32(b, 40)) // checksum + footer
        assertArrayEquals(byteArrayOf(0xC5.toByte(), 0xC4.toByte(), 0xC3.toByte(), 0xC2.toByte()), b.copyOfRange(60, 64))
    }

    @Test fun largeDataGoesToPayload() {
        val data = ByteArray(2048) { it.toByte() }
        val b = ObpMessage(ObpTypes.GET_SPECTRUM_NOW, data = data).toBytes()
        assertEquals(2048 + 64, b.size)
        assertEquals(0, b[23].toInt())
        assertEquals(2048 + 20, ObpMessage.getU32(b, 40))
        assertArrayEquals(data, ObpMessage.parse(b).data)
    }

    @Test fun deviceInterrogation() {
        val fake = FakeSts()
        val sts = StsProtocol(fake)
        assertEquals("S01234", sts.serialNumber())
        val wl = sts.wavelengthCoefficients()!!
        assertEquals(636.0, wl[0], 1e-3); assertEquals(0.473, wl[1], 1e-6)
        assertEquals(2, sts.nonlinearityCoefficients()!!.size)
        assertEquals(3, sts.maxBinningFactor())
        assertTrue(sts.setIntegrationTimeMicros(200_000))
        assertEquals(200_000, fake.integrationUs)
        assertTrue(sts.setShutter(false)); assertFalse(fake.shutterOpen)
        // commands ask for ACK, queries don't
        assertTrue(fake.sent.first { it.messageType == ObpTypes.SET_ITIME_USEC }.flags and ObpMessage.FLAG_ACK_REQUESTED != 0)
        assertEquals(0, fake.sent.first { it.messageType == ObpTypes.GET_SERIAL_NUMBER }.flags)
    }

    @Test fun missingNonlinearityIsNull() {
        assertNull(StsProtocol(FakeSts(nl = null)).nonlinearityCoefficients())
    }

    @Test fun spectrumReassembledFromSmallChunks() {
        val sts = StsProtocol(FakeSts(chunk = 64))
        val s = sts.readSpectrum(1024, 10_000)
        assertEquals(1024, s.size)
        assertEquals(1000, s[0]); assertEquals(2023, s[1023])
    }

    private val stsModel get() = com.rfsat.pocketspectro.core.DeviceCatalog.find(0x2457, 0x4000)!!

    @Test fun spectrumRequestFallsBackToRawCode() {
        val fake = FakeSts(rawSpectrumOnly = true)
        val sts = StsProtocol(fake)
        assertEquals(1024, sts.readSpectrum(1024, 10_000).size)
        assertEquals(1024, sts.readSpectrum(1024, 10_000).size) // remembers the working code
        assertEquals(1, fake.sent.count { it.messageType == ObpTypes.GET_SPECTRUM_NOW })
    }

    @Test fun integrationTimeClampedToModel() {
        val fake = FakeSts()
        com.rfsat.pocketspectro.core.ObpSpectrometer.open(fake, stsModel).setIntegrationTimeMicros(1)
        assertEquals(10, fake.integrationUs)
    }

    @Test fun stsVariantNamedFromCalibration() {
        val dev = com.rfsat.pocketspectro.core.ObpSpectrometer.open(FakeSts(), stsModel)
        assertEquals("STS-NIR", dev.info.model)
        assertEquals("S01234", dev.info.serial)
        val vis = com.rfsat.pocketspectro.core.ObpSpectrometer.open(FakeSts(wl = floatArrayOf(338f, 0.46f, 0f, 0f)), stsModel)
        assertEquals("STS-VIS", vis.info.model)
        assertTrue(dev.info.fireCapable)
        assertFalse(vis.info.fireCapable)
    }

    @Test fun catalogueKnowsOceanFamilies() {
        val c = com.rfsat.pocketspectro.core.DeviceCatalog
        assertTrue(c.find(0x2457, 0x4000)!!.usable)
        assertTrue(c.find(0x2457, 0x1022)!!.usable) // USB4000: OOI driver (experimental)
        assertEquals(com.rfsat.pocketspectro.core.DeviceCatalog.Protocol.OBP2, c.find(0x0999, 0x1000)!!.protocol) // Ocean ST
        assertNull(c.find(0x2457, 0x9999))
    }
}
