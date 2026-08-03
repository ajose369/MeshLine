package org.meshline.app.transport

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

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
            // Check for common LoRa USB VENDOR IDs (e.g. CP210x, CH340, FTDI used on Heltec V3 / T-Beam)
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
}
