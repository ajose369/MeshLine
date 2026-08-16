package org.meshline.app.db

/**
 * A resource pin that has already passed creator-signature verification in the
 * native core. Nothing reaches this type unverified, so the UI may render it
 * without further qualification.
 */
data class ResourcePinEntity(
    val pinId: String,
    val pinType: String, // WaterPoint, Shelter, MedicalStation, Hazard, Roadblock
    val latitude: Float,
    val longitude: Float,
    val label: String,
    /** Seconds since epoch, as carried on the wire. */
    val createdAtSecs: Long,
    val expiresAtSecs: Long,
    /** Mesh node id of whoever signed this pin. */
    val creatorId: String
) {
    fun isExpired(nowSecs: Long): Boolean = expiresAtSecs <= nowSecs
}
