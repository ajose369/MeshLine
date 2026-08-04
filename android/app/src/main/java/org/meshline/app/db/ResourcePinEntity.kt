package org.meshline.app.db

data class ResourcePinEntity(
    val pinId: String,
    val pinType: String, // WaterPoint, Shelter, MedicalStation, Hazard, Roadblock
    val latitude: Float,
    val longitude: Float,
    val label: String,
    val createdAt: Long,
    val expiresAt: Long,
    val creatorPubkey: String,
    val signatureHex: String,
    val verifiedCount: Int
)
