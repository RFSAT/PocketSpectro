package com.rfsat.pocketspectro.usb

import java.io.IOException

/**
 * Minimal bidirectional byte pipe to the spectrometer. The Android USB implementation
 * is [AndroidUsbTransport]; unit tests use an in-memory fake.
 */
interface ByteTransport {
    /** Writes all bytes or throws. */
    @Throws(IOException::class)
    fun write(data: ByteArray, timeoutMs: Int)

    /**
     * Reads up to [length] bytes into [buffer] at [offset]. Returns the number of bytes read
     * (> 0), or a value <= 0 on timeout.
     */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    fun close()
}

/** Reads exactly [length] bytes or throws [IOException] once [timeoutMs] has elapsed. */
fun ByteTransport.readFully(length: Int, timeoutMs: Int): ByteArray {
    val buf = ByteArray(length)
    var got = 0
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (got < length) {
        val left = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
        if (left <= 0) throw IOException("USB read timed out ($got of $length bytes)")
        val n = read(buf, got, length - got, left)
        if (n > 0) got += n
    }
    return buf
}

/** Discards anything already queued on the IN endpoint (stale replies after a reconnect). */
fun ByteTransport.drain() {
    val scratch = ByteArray(4096)
    repeat(32) {
        val n = try { read(scratch, 0, scratch.size, 20) } catch (_: IOException) { 0 }
        if (n <= 0) return
    }
}
