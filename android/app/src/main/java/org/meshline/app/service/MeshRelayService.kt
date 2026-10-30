package org.meshline.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.meshline.app.MainActivity
import org.meshline.app.R
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.permissions.MeshPermissions
import org.meshline.app.transport.BleTransportManager

/**
 * Foreground service that keeps this device participating in the mesh.
 *
 * The service refuses to start rather than crashing when prerequisites are
 * missing. Both failure modes here were previously fatal: an unguarded JNI call
 * threw [UnsatisfiedLinkError] when the native library was absent, and BLE
 * startup threw [SecurityException] without runtime permissions.
 */
class MeshRelayService : LifecycleService() {

    companion object {
        private const val TAG = "MeshRelayService"
        private const val CHANNEL_ID = "meshline_relay_channel"
        private const val NOTIFICATION_ID = 1001

        /** How often stale peers are pruned and pins re-synced. */
        private const val HOUSEKEEPING_INTERVAL_MS = 30_000L

        const val ACTION_STOP = "org.meshline.app.action.STOP_RELAY"
    }

    private var bleManager: BleTransportManager? = null
    private var housekeepingJob: Job? = null
    private var batteryReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) {
                (level * 100f / scale).toInt()
            } else {
                100
            }
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            // Guarded wrapper: a missing native library must not kill the service.
            MeshCoreBridge.updateBattery(percent, isCharging)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Enter the foreground immediately. Android gives a started foreground
        // service a few seconds to post its notification before killing it.
        startForegroundCompat(getString(R.string.relay_notification_starting))

        if (!MeshCoreBridge.initialise(this)) {
            Log.e(TAG, "Mesh core unavailable: ${MeshCoreBridge.status}")
            updateNotification(getString(R.string.relay_notification_core_failed))
            stopSelf()
            return
        }

        if (!MeshPermissions.hasRequired(this)) {
            Log.w(TAG, "Missing permissions: ${MeshPermissions.missingRequired(this)}")
            updateNotification(getString(R.string.relay_notification_needs_permission))
            stopSelf()
            return
        }

        registerBatteryReceiver()

        val manager = BleTransportManager(this)
        bleManager = manager
        if (!manager.startMeshTransport()) {
            Log.w(TAG, "BLE transport unavailable: ${manager.lastError}")
            updateNotification(
                manager.lastError ?: getString(R.string.relay_notification_no_transport)
            )
        } else {
            updateNotification(getString(R.string.relay_notification_active))
        }

        startHousekeeping()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Restart if the system kills us: staying on the mesh is the whole point.
        return START_STICKY
    }

    private fun startHousekeeping() {
        housekeepingJob?.cancel()
        housekeepingJob = lifecycleScope.launch {
            val store = StoreAndForwardManager.getInstance(this@MeshRelayService)
            while (isActive) {
                delay(HOUSEKEEPING_INTERVAL_MS)
                try {
                    store.prunePeers()
                    store.syncResourcePins()
                    val peers = bleManager?.activePeersCount ?: 0
                    updateNotification(
                        resources.getQuantityString(
                            R.plurals.relay_notification_peers,
                            peers,
                            peers
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Housekeeping pass failed.", e)
                }
            }
        }
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // ACTION_BATTERY_CHANGED is a protected system broadcast, but apps
        // targeting API 34+ must still state export intent explicitly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(batteryReceiver, filter)
        }
        batteryReceiverRegistered = true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.relay_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.relay_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopRelay = PendingIntent.getService(
            this,
            1,
            Intent(this, MeshRelayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.relay_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mesh_notification)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.relay_notification_stop), stopRelay)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
        } catch (e: Exception) {
            // On API 34+ this throws if the service was started from the
            // background without an allowed exemption.
            Log.e(TAG, "Could not enter the foreground.", e)
            stopSelf()
        }
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied: the relay keeps running silently.
            Log.d(TAG, "Notification update suppressed; permission not granted.")
        }
    }

    override fun onDestroy() {
        housekeepingJob?.cancel()
        if (batteryReceiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Battery receiver was already unregistered.", e)
            }
            batteryReceiverRegistered = false
        }
        bleManager?.stopMeshTransport()
        bleManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
