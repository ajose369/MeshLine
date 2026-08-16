package org.meshline.app.permissions

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * These assertions exist because the previous build shipped with no runtime
 * permission handling at all, which made the relay service throw
 * SecurityException on every Android 12+ device.
 */
@RunWith(RobolectricTestRunner::class)
class MeshPermissionsTest {

    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    fun `modern android requires the three runtime bluetooth permissions`() {
        val required = MeshPermissions.required().toList()
        assertTrue(required.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(required.contains(Manifest.permission.BLUETOOTH_ADVERTISE))
        assertTrue(required.contains(Manifest.permission.BLUETOOTH_CONNECT))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O])
    fun `legacy android requires fine location for ble scanning`() {
        val required = MeshPermissions.required().toList()
        assertTrue(
            "Below API 31 a BLE scan silently returns nothing without location",
            required.contains(Manifest.permission.ACCESS_FINE_LOCATION)
        )
        assertFalse(required.contains(Manifest.permission.BLUETOOTH_SCAN))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    fun `location does not block the mesh on modern android`() {
        // With neverForLocation, BLE discovery works without it. A phone that
        // can still relay someone else's SOS beats one that refuses to start.
        assertFalse(
            "Denying location must not disable relaying",
            MeshPermissions.required().contains(Manifest.permission.ACCESS_FINE_LOCATION)
        )
        assertTrue(
            "But it is still requested, so an SOS can carry coordinates",
            MeshPermissions.optional().contains(Manifest.permission.ACCESS_FINE_LOCATION)
        )
        assertTrue(MeshPermissions.all().contains(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O])
    fun `location does block the mesh on legacy android`() {
        // Below API 31 a scan without location silently returns nothing, so
        // there is no useful degraded mode to fall back to.
        assertTrue(
            MeshPermissions.required().contains(Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    fun `bluetooth grants alone are enough to start relaying`() {
        // Mirrors the real-world case of a user granting location as
        // "only this time" and it silently expiring.
        val required = MeshPermissions.required().toSet()
        assertEquals(
            setOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            ),
            required
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    fun `notifications are optional and never block startup`() {
        assertTrue(
            MeshPermissions.optional().contains(Manifest.permission.POST_NOTIFICATIONS)
        )
        assertFalse(
            "A denied notification permission must not stop the mesh relay",
            MeshPermissions.required().contains(Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    fun `no duplicate permissions are requested`() {
        val all = MeshPermissions.all().toList()
        assertTrue(all.size == all.distinct().size)
    }

    @Test
    fun `every requested permission has a user facing rationale`() {
        MeshPermissions.all().forEach { permission ->
            val rationale = MeshPermissions.rationale(permission)
            assertTrue(
                "Missing rationale for $permission",
                rationale.isNotBlank() && rationale != "Required for mesh networking."
            )
            assertTrue(MeshPermissions.label(permission).isNotBlank())
        }
    }

    @Test
    fun `location rationale states when position leaves the device`() {
        val rationale = MeshPermissions.rationale(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(
            "Play's data safety review expects an explicit disclosure here",
            rationale.contains("only ever sent", ignoreCase = true)
        )
    }
}
