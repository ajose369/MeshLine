package org.meshline.app.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.meshline.app.permissions.MeshPermissions

/**
 * Supplies the device's real position for SOS broadcasts and resource pins.
 *
 * Uses the platform [LocationManager] rather than Play Services, because the
 * whole premise of this app is working where there is no network and possibly no
 * Google Play services either.
 *
 * There is no default or placeholder coordinate. An earlier version of the SOS
 * screen displayed a hardcoded San Francisco fix regardless of where the device
 * actually was, which would send rescuers to the wrong continent. When no fix is
 * available this returns null and the UI says so plainly.
 */
class MeshLocationProvider(private val context: Context) {

    companion object {
        private const val TAG = "MeshLocation"

        /** A cached fix older than this is treated as unusable for an emergency. */
        private const val MAX_FIX_AGE_MILLIS = 5 * 60 * 1000L

        /** Minimum interval between active position updates. */
        private const val UPDATE_INTERVAL_MILLIS = 5_000L

        /** Report every update regardless of how little the device moved. */
        private const val UPDATE_MIN_DISTANCE_M = 0f
    }

    /** A position fix good enough to attach to an emergency broadcast. */
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val ageMillis: Long
    ) {
        fun formatted(): String {
            val ns = if (latitude >= 0) "N" else "S"
            val ew = if (longitude >= 0) "E" else "W"
            return "%.4f° %s, %.4f° %s (±%.0fm)".format(
                kotlin.math.abs(latitude), ns,
                kotlin.math.abs(longitude), ew,
                accuracyMeters
            )
        }
    }

    /**
     * Returns the best recent fix, or null when location is unavailable,
     * permission is denied, or every provider's fix is too stale to trust.
     */
    fun lastKnownFix(): Fix? {
        if (!MeshPermissions.canUseLocation(context)) return null

        val manager = context.getSystemService<LocationManager>() ?: return null

        val candidates = buildList {
            for (provider in safeProviders(manager)) {
                try {
                    manager.getLastKnownLocation(provider)?.let { add(it) }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Location permission revoked while reading $provider.", e)
                    return null
                } catch (e: IllegalArgumentException) {
                    // Provider not present on this device; try the next one.
                }
            }
        }

        val now = System.currentTimeMillis()
        return candidates
            .filter { now - it.time <= MAX_FIX_AGE_MILLIS }
            .minByOrNull { it.accuracy.takeIf { a -> a > 0f } ?: Float.MAX_VALUE }
            ?.toFix(now)
    }

    /**
     * Actively requests position updates for as long as the flow is collected.
     *
     * This exists because [lastKnownFix] alone is not enough. `getLastKnownLocation`
     * returns whatever some app happened to request earlier, and on a device where
     * nothing has asked recently it is simply null — which for an emergency app
     * means SOS broadcasts routinely go out with no coordinates attached. Asking
     * for a fix ourselves, while a screen that needs one is open, is the only way
     * to reliably have coordinates when the user hits send.
     *
     * Emits the last known fix immediately (if any) so the UI is not blank while
     * the first real update arrives.
     */
    fun positionUpdates(): Flow<Fix?> = callbackFlow {
        if (!MeshPermissions.canUseLocation(context)) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val manager = context.getSystemService<LocationManager>()
        if (manager == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        trySend(lastKnownFix())

        val listener = LocationListener { location ->
            trySend(location.toFix(System.currentTimeMillis()))
        }

        val registered = mutableListOf<String>()
        try {
            for (provider in safeProviders(manager)) {
                if (!manager.isProviderEnabled(provider)) continue
                manager.requestLocationUpdates(
                    provider,
                    UPDATE_INTERVAL_MILLIS,
                    UPDATE_MIN_DISTANCE_M,
                    listener,
                    Looper.getMainLooper()
                )
                registered += provider
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission revoked while subscribing.", e)
        } catch (e: Exception) {
            Log.w(TAG, "Could not subscribe to position updates.", e)
        }

        if (registered.isEmpty()) {
            Log.i(TAG, "No enabled location provider; falling back to cached fixes only.")
        }

        awaitClose {
            try {
                manager.removeUpdates(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove location updates.", e)
            }
        }
    }

    /** True when the user has location switched off entirely. */
    fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService<LocationManager>() ?: return false
        return try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    private fun safeProviders(manager: LocationManager): List<String> = try {
        // Ordered best-first; GPS is the only one that works with no network.
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { manager.allProviders.contains(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun Location.toFix(now: Long) = Fix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        ageMillis = (now - time).coerceAtLeast(0)
    )
}
