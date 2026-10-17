package com.rfsat.pocketspectro.obp

/**
 * Ocean Binary Protocol (OBP) message framing, ported from SeaBreeze
 * (OBPMessage.cpp, MIT licence, Ocean Optics 2014).
 *
 * Wire layout (all multi-byte fields little-endian):
 *   0  2  header           0xC1 0xC0
 *   2  2  protocol version 0x1100
 *   4  2  flags            bit0 response, bit1 ACK, bit2 ACK requested, bit3 NACK, bit4 exception
 *   6  2  error number
 *   8  4  message type
 *  12  4  regarding
 *  16  6  reserved
 *  22  1  checksum type    (0 = none)
 *  23  1  immediate data length (0..16)
 *  24 16  immediate data
 *  40  4  bytes remaining  = payload + 16 (checksum) + 4 (footer)
 *  44  n  payload          (only when data > 16 bytes)
 *  44+n 16 checksum        (zeros)
 *  60+n 4  footer          0xC5 0xC4 0xC3 0xC2
 *
 * A message is therefore always 64 + payload bytes long.
 */
class ObpMessage(
    val messageType: Int,
    val flags: Int = 0,
    val errorNumber: Int = 0,
    val regarding: Int = 0,
    val protocolVersion: Int = PROTOCOL_VERSION,
    /** Data carried by the message: <= 16 bytes travel as immediate data, larger as payload. */
    val data: ByteArray = ByteArray(0),
) {
    val isResponse get() = flags and FLAG_RESPONSE != 0
    val isAck get() = flags and FLAG_ACK != 0
    val isNack get() = flags and FLAG_NACK != 0
    val isException get() = flags and FLAG_EXCEPTION != 0

    fun toBytes(): ByteArray {
        val payload = if (data.size > IMMEDIATE_MAX) data else ByteArray(0)
        val immediate = if (data.size <= IMMEDIATE_MAX) data else ByteArray(0)
        val out = ByteArray(HEADER_SIZE + payload.size + CHECKSUM_SIZE + FOOTER_SIZE)
        out[0] = 0xC1.toByte(); out[1] = 0xC0.toByte()
        putU16(out, 2, protocolVersion)
        putU16(out, 4, flags)
        putU16(out, 6, errorNumber)
        putU32(out, 8, messageType)
        putU32(out, 12, regarding)
        // 16..21 reserved = 0
        out[22] = 0 // checksum type: none
        out[23] = immediate.size.toByte()
        immediate.copyInto(out, 24)
        putU32(out, 40, payload.size + CHECKSUM_SIZE + FOOTER_SIZE)
        payload.copyInto(out, HEADER_SIZE)
        // checksum left as zeros
        val f = HEADER_SIZE + payload.size + CHECKSUM_SIZE
        out[f] = 0xC5.toByte(); out[f + 1] = 0xC4.toByte(); out[f + 2] = 0xC3.toByte(); out[f + 3] = 0xC2.toByte()
        return out
    }

    companion object {
        const val PROTOCOL_VERSION = 0x1100
        const val HEADER_SIZE = 44
        const val CHECKSUM_SIZE = 16
        const val FOOTER_SIZE = 4
        /** Smallest complete message (no payload). */
        const val MIN_SIZE = HEADER_SIZE + CHECKSUM_SIZE + FOOTER_SIZE // 64
        const val IMMEDIATE_MAX = 16

        const val FLAG_RESPONSE = 1 shl 0
        const val FLAG_ACK = 1 shl 1
        const val FLAG_ACK_REQUESTED = 1 shl 2
        const val FLAG_NACK = 1 shl 3
        const val FLAG_EXCEPTION = 1 shl 4

        /** Total message length announced by a 64-byte (or longer) header block. */
        fun totalLengthFromHeader(header: ByteArray): Int {
            checkHeader(header)
            return HEADER_SIZE + getU32(header, 40)
        }

        private fun checkHeader(b: ByteArray) {
            require(b.size >= MIN_SIZE) { "OBP block too short: ${b.size}" }
            require(b[0] == 0xC1.toByte() && b[1] == 0xC0.toByte()) { "OBP header not found" }
            val remaining = getU32(b, 40)
            require(remaining >= CHECKSUM_SIZE + FOOTER_SIZE) { "Invalid bytes-remaining field: $remaining" }
        }

        /** Parse a complete message (header + payload + checksum + footer). */
        fun parse(b: ByteArray): ObpMessage {
            checkHeader(b)
            val remaining = getU32(b, 40)
            val payloadLen = remaining - CHECKSUM_SIZE - FOOTER_SIZE
            val total = HEADER_SIZE + remaining
            require(b.size >= total) { "OBP message truncated: have ${b.size}, need $total" }
            val f = HEADER_SIZE + payloadLen + CHECKSUM_SIZE
            require(
                b[f] == 0xC5.toByte() && b[f + 1] == 0xC4.toByte() &&
                    b[f + 2] == 0xC3.toByte() && b[f + 3] == 0xC2.toByte()
            ) { "OBP footer not found" }
            val immLen = b[23].toInt() and 0xFF
            val data = if (immLen > 0) {
                b.copyOfRange(24, 24 + immLen.coerceAtMost(IMMEDIATE_MAX))
            } else {
                b.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen)
            }
            return ObpMessage(
                messageType = getU32(b, 8),
                flags = getU16(b, 4),
                errorNumber = getU16(b, 6),
                regarding = getU32(b, 12),
                protocolVersion = getU16(b, 2),
                data = data,
            )
        }

        fun putU16(b: ByteArray, o: Int, v: Int) {
            b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte()
        }

        fun putU32(b: ByteArray, o: Int, v: Int) {
            for (i in 0 until 4) b[o + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }

        fun getU16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        fun getU32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        fun u32le(v: Int) = ByteArray(4).also { putU32(it, 0, v) }

        fun floatLe(b: ByteArray, o: Int = 0): Float = java.lang.Float.intBitsToFloat(getU32(b, o))
    }
}

/** OBP message types used by the STS (from SeaBreeze OBPMessageTypes.h). */
object ObpTypes {
    const val GET_SERIAL_NUMBER = 0x00000100
    const val GET_SPECTRUM_NOW = 0x00101000
    const val GET_RAW_SPECTRUM_NOW = 0x00101100
    const val SET_ITIME_USEC = 0x00110010
    const val SET_TRIG_MODE = 0x00110110
    const val GET_BINNING_FACTOR = 0x00110280
    const val GET_MAX_BINNING_FACTOR = 0x00110281
    const val SET_BINNING_FACTOR = 0x00110290
    const val SET_SHUTTER = 0x00110610
    const val GET_WL_COEFF = 0x00180101
    const val GET_NL_COEFF_COUNT = 0x00181100
    const val GET_NL_COEFF = 0x00181101
}
