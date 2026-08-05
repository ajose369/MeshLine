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
    external fun createSignedPinPacket(
        pinIdBytes: ByteArray,
        pinTypeVal: Int,
        lat: Float,
        lon: Float,
        label: String,
        expiresInSecs: Long
    ): ByteArray?
    external fun getActivePinsJson(): String?
    external fun processIncomingPacket(rawPacket: ByteArray): ByteArray?
    external fun updateBatteryState(batteryLevel: Int, isCharging: Boolean)
    external fun createChatPacket(message: String, recipientIdHex: String): ByteArray?
    external fun parsePacketJson(rawPacket: ByteArray): String?

    // Safe wrappers with fallbacks
    fun createChatPacketSafe(message: String, recipientIdHex: String): ByteArray? {
        if (!isNativeReady()) return null
        return try {
            createChatPacket(message, recipientIdHex)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    fun parsePacketJsonSafe(rawPacket: ByteArray): String? {
        if (!isNativeReady()) return null
        return try {
            parsePacketJson(rawPacket)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }
    fun initNodeSafe(): Boolean {
        if (!isNativeLoaded) return false
        return try {
            initNode()
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    fun createPublicSosSafe(message: String, lat: Float, lon: Float): ByteArray {
        if (isNativeLoaded) {
            try {
                val packet = createPublicSos(message, lat, lon)
                if (packet != null) return packet
            } catch (e: UnsatisfiedLinkError) {
                // fall through to fallback
            }
        }
        return createPublicSosFallback(message, lat, lon)
    }

    fun createSignedPinPacketSafe(
        pinId: ByteArray,
        pinTypeVal: Int,
        lat: Float,
        lon: Float,
        label: String,
        expiresInSecs: Long
    ): ByteArray {
        if (isNativeLoaded) {
            try {
                val packet = createSignedPinPacket(pinId, pinTypeVal, lat, lon, label, expiresInSecs)
                if (packet != null) return packet
            } catch (e: UnsatisfiedLinkError) {
                // fall through to fallback
            }
        }
        return createSignedPinPacketFallback(pinId, pinTypeVal, lat, lon, label, expiresInSecs)
    }

    fun getActivePinsJsonSafe(): String? {
        if (!isNativeLoaded) return null
        return try {
            getActivePinsJson()
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    fun processIncomingPacketSafe(rawPacket: ByteArray): ByteArray? {
        if (!isNativeLoaded) return null
        return try {
            processIncomingPacket(rawPacket)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    fun updateBatteryStateSafe(batteryLevel: Int, isCharging: Boolean) {
        if (isNativeLoaded) {
            try {
                updateBatteryState(batteryLevel, isCharging)
            } catch (e: UnsatisfiedLinkError) {
                // ignore
            }
        }
    }

    // Fallback methods when native lib is absent
    fun createPublicSosFallback(message: String, lat: Float, lon: Float): ByteArray {
        val sosId = UUID.randomUUID().toString().take(16)
        val payload = "SOS_V1|$sosId|$message|$lat|$lon|${System.currentTimeMillis()}"
        return payload.toByteArray(Charsets.UTF_8)
    }

    fun createSignedPinPacketFallback(
        pinId: ByteArray,
        pinTypeVal: Int,
        lat: Float,
        lon: Float,
        label: String,
        expiresInSecs: Long
    ): ByteArray {
        val pinIdStr = pinId.joinToString("") { "%02x".format(it) }
        val typeStr = when (pinTypeVal) {
            1 -> "WaterPoint"
            2 -> "Shelter"
            3 -> "MedicalStation"
            4 -> "Hazard"
            5 -> "Roadblock"
            else -> "WaterPoint"
        }
        val payload = "PIN_V1|$pinIdStr|$typeStr|$lat|$lon|$label|${System.currentTimeMillis()}|${System.currentTimeMillis() + expiresInSecs * 1000}"
        return payload.toByteArray(Charsets.UTF_8)
    }

    fun isNativeReady(): Boolean = isNativeLoaded
}
