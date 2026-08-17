package org.meshline.app.db

/**
 * A private group as this device knows it.
 *
 * Mirrors what the native core holds; the core is authoritative and this is a
 * read-only projection for the UI. In particular the group key never appears
 * here, or anywhere else in Kotlin.
 */
data class GroupEntity(
    val groupId: String,
    val name: String,
    /**
     * Bumped whenever the key is rotated. Members on an older epoch cannot read
     * anything sent on a newer one, which is what makes removal real.
     */
    val epoch: Int,
    val adminId: String,
    /** True when this device may add or remove members. */
    val isAdmin: Boolean,
    val members: List<String>,
    val createdAtSecs: Long
) {
    val memberCount: Int get() = members.size
}
