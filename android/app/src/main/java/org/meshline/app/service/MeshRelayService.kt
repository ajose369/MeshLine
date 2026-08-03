package org.meshline.app.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.transport.BleTransportManager

class MeshRelayService : Service() {

    private lateinit var bleManager: BleTransportManager

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 100
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                MeshCoreBridge.updateBatteryState(batteryPct, isCharging)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        bleManager = BleTransportManager(this)
        bleManager.startMeshTransport()
        MeshCoreBridge.initNode()

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)

        startForegroundServiceWithNotification()
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "meshline_relay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "MeshLine Active Relay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MeshLine Emergency Relay Active")
            .setContentText("Participating in phone-to-phone offline mesh routing.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
        bleManager.stopMeshTransport()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
