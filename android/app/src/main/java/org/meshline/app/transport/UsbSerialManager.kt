package org.meshline.app.transport

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import org.meshline.app.bridge.MeshCoreBridge
import java.io.ByteArrayOutputStream

class UsbSerialManager(private val context: Context) {

    private val usbManager: UsbManager? by lazy {
        context.getSystemService(Context.USB_SERVICE) as? UsbManager
    }

    var isLoraBridgeConnected: Boolean = false
        private set

    var connectedDeviceName: String = "No LoRa Hardware Connected"
        private set

    fun scanConnectedDevices(): List<String> {
        val devices = usbManager?.deviceList?.values ?: emptyList()
        val loraDevices = mutableListOf<String>()

        for (device in devices) {
            // Check for common LoRa USB VENDOR IDs (CP210x, CH340, FTDI used on Heltec V3 / T-Beam)
            if (isLoraRadioVendor(device.vendorId)) {
                isLoraBridgeConnected = true
                connectedDeviceName = "Heltec V3 / LilyGO T-Beam (LoRa 915MHz)"
                loraDevices.add(connectedDeviceName)
            }
        }

        if (loraDevices.isEmpty()) {
            isLoraBridgeConnected = false
            connectedDeviceName = "No LoRa Hardware Connected"
        }

        return loraDevices
    }

    private fun isLoraRadioVendor(vendorId: Int): Boolean {
        // 0x10C4 = CP210x, 0x1A86 = CH340, 0x0403 = FTDI
        return vendorId == 0x10C4 || vendorId == 0x1A86 || vendorId == 0x0403
    }

    fun processIncomingLoraFrame(rawFrame: ByteArray): ByteArray? {
        if (rawFrame.size < 4) return null

        // Parse Meshtastic SLIP / Header frame
        val headerMarker = rawFrame[0].toInt() and 0xFF
        if (headerMarker == 0x94) {
            // Unpack payload and send to MeshCoreBridge
            val payload = rawFrame.copyOfRange(4, rawFrame.size)
            return MeshCoreBridge.processIncomingPacket(payload)
        }
        return null
    }

    fun wrapPacketForLoraTx(packetBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x94) // Meshtastic bridge magic header
        out.write(0x01) // Version 1
        out.write((packetBytes.size shr 8) and 0xFF)
        out.write(packetBytes.size and 0xFF)
        out.write(packetBytes)
        return out.toByteArray()
    }
}
