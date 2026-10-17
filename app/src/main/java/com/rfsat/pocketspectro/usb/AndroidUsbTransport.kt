package com.rfsat.pocketspectro.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * Bulk-transfer pipe to an STS over Android's USB host API.
 *
 * SeaBreeze notes that on the STS endpoints 1 and 2 are bidirectional and equivalent, so
 * rather than hard-coding addresses we take the first bulk OUT / bulk IN pair found.
 */
class AndroidUsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint,
) : ByteTransport {

    override fun write(data: ByteArray, timeoutMs: Int) {
        var sent = 0
        while (sent < data.size) {
            val n = connection.bulkTransfer(epOut, data, sent, data.size - sent, timeoutMs)
            if (n <= 0) throw IOException("USB write failed ($sent of ${data.size} bytes)")
            sent += n
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(epIn, buffer, offset, length, timeoutMs.coerceAtLeast(1))

    override fun close() {
        try { connection.releaseInterface(iface) } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }

    companion object {
        @Throws(IOException::class)
        fun open(manager: UsbManager, device: UsbDevice): AndroidUsbTransport {
            if (!manager.hasPermission(device)) throw IOException("No USB permission for ${device.deviceName}")
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var out: UsbEndpoint? = null
                var inp: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_OUT && out == null) out = ep
                    if (ep.direction == UsbConstants.USB_DIR_IN && inp == null) inp = ep
                }
                if (out != null && inp != null) {
                    val conn = manager.openDevice(device) ?: throw IOException("openDevice() returned null")
                    if (!conn.claimInterface(iface, true)) {
                        conn.close()
                        throw IOException("Could not claim USB interface $i")
                    }
                    return AndroidUsbTransport(conn, iface, out, inp)
                }
            }
            throw IOException("No bulk endpoint pair on ${device.deviceName}")
        }
    }
}
