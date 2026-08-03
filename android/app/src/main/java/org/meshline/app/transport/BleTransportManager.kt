package org.meshline.app.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import java.util.*

@SuppressLint("MissingPermission")
class BleTransportManager(private val context: Context) {
    companion object {
        val MESHLINE_SERVICE_UUID: UUID = UUID.fromString("0000FE60-0000-1000-8000-00805F9B34FB")
        val MESHLINE_CHAR_UUID: UUID = UUID.fromString("0000FE61-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null

    var activePeersCount: Int = 0
        private set

    fun startMeshTransport() {
        bluetoothAdapter?.let { adapter ->
            if (adapter.isEnabled) {
                bleAdvertiser = adapter.bluetoothLeAdvertiser
                bleScanner = adapter.bluetoothLeScanner

                startAdvertising()
                startScanning()
                initGattServer()
            }
        }
    }

    fun stopMeshTransport() {
        try {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
            bleScanner?.stopScan(scanCallback)
            gattServer?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(MESHLINE_SERVICE_UUID))
            .build()

        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun startScanning() {
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MESHLINE_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private fun initGattServer() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        gattServer = manager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    activePeersCount++
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    activePeersCount = (activePeersCount - 1).coerceAtLeast(0)
                }
            }
        })

        val service = BluetoothGattService(MESHLINE_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val char = BluetoothGattCharacteristic(
            MESHLINE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(char)
        gattServer?.addService(service)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            println("MeshLine BLE Advertising started successfully")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                // Discover & queue BLE mesh packet exchange
            }
        }
    }
}
