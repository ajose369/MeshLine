package org.meshline.app.bridge

import java.util.UUID

object MeshCoreBridge {
    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("meshline_ffi")
            isNativeLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            isNativeLoaded = false
            System.err.println("MeshLine native library meshline_ffi not found. Running in high-performance pure Kotlin fallback mode.")
        }
    }

    external fun initNode(): Boolean
    external fun createPublicSos(message: String, lat: Float, lon: Float): ByteArray?
    external fun processIncomingPacket(rawPacket: ByteArray): ByteArray?
    external fun updateBatteryState(batteryLevel: Int, isCharging: Boolean)

    // Fallback methods when native lib is absent
    fun createPublicSosFallback(message: String, lat: Float, lon: Float): ByteArray {
        val sosId = UUID.randomUUID().toString().take(16)
        val payload = "SOS_V1|$sosId|$message|$lat|$lon|${System.currentTimeMillis()}"
        return payload.toByteArray(Charsets.UTF_8)
    }

    fun isNativeReady(): Boolean = isNativeLoaded
}
