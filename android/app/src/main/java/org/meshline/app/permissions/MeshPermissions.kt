package org.meshline.app.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permissions MeshLine needs, and how to check them.
 *
 * Every Bluetooth call in this app is guarded by these checks. Calling a BLE API
 * without the matching runtime grant throws [SecurityException] on Android 12+,
 * which is fatal inside the relay service, so nothing may assume a grant.
 */
object MeshPermissions {

    /**
     * Permissions without which the mesh genuinely cannot carry a single packet.
     * The app refuses to start the relay service until all of these are granted.
     *
     * Location is deliberately **not** here on Android 12+. With
     * `neverForLocation` on the scan permission, BLE discovery does not need it,
     * and a phone that can still relay someone else's SOS is far more useful
     * than one that refuses to start because coordinates are unavailable. Below
     * Android 12 it stays blocking, because there a scan without location
     * silently returns zero results — the mesh really is dead without it.
     */
    fun required(): Array<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 moved Bluetooth to runtime permissions.
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }

        return permissions.distinct().toTypedArray()
    }

    /**
     * Permissions that degrade the app without disabling it.
     *
     * Location is here on Android 12+: without it an SOS still broadcasts and
     * still relays, it just carries no coordinates, and the SOS screen says so
     * rather than pretending. Users often grant it as "only this time", which
     * expires silently — so nothing may treat it as permanently held.
     */
    fun optional(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    fun all(): Array<String> = (required() + optional()).distinct().toTypedArray()

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    /** True when every blocking permission has been granted. */
    fun hasRequired(context: Context): Boolean =
        required().all { isGranted(context, it) }

    fun missingRequired(context: Context): List<String> =
        required().filterNot { isGranted(context, it) }

    /** Guards [android.bluetooth.le.BluetoothLeScanner] calls. */
    fun canScan(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(context, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Guards [android.bluetooth.le.BluetoothLeAdvertiser] calls. */
    fun canAdvertise(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(context, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            true
        }

    /** Guards GATT connect/server calls. */
    fun canConnect(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }

    fun canUseLocation(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Human-readable label for the permission rationale screen. */
    fun label(permission: String): String = when (permission) {
        Manifest.permission.BLUETOOTH_SCAN -> "Find nearby devices"
        Manifest.permission.BLUETOOTH_ADVERTISE -> "Be discoverable to nearby devices"
        Manifest.permission.BLUETOOTH_CONNECT -> "Connect to nearby devices"
        Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        else -> permission.substringAfterLast('.')
    }

    /** Why the app needs it, in the user's terms rather than Android's. */
    fun rationale(permission: String): String = when (permission) {
        Manifest.permission.BLUETOOTH_SCAN ->
            "Discovers other phones running MeshLine so messages can hop between them."
        Manifest.permission.BLUETOOTH_ADVERTISE ->
            "Lets other phones find this one and relay your messages onward."
        Manifest.permission.BLUETOOTH_CONNECT ->
            "Opens the short-range links that carry mesh traffic."
        Manifest.permission.ACCESS_FINE_LOCATION ->
            "Attaches your coordinates to an SOS so responders know where to look. " +
                "Your location is only ever sent when you choose to send an SOS or place a pin."
        Manifest.permission.POST_NOTIFICATIONS ->
            "Shows whether the mesh relay is running and alerts you to incoming SOS traffic."
        else -> "Required for mesh networking."
    }
}
