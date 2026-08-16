package org.meshline.app.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.permissions.MeshPermissions

/**
 * BLE mesh transport: advertises the MeshLine service, scans for peers, and
 * exchanges packets over a GATT characteristic.
 *
 * Every Bluetooth entry point checks its runtime permission first. The previous
 * version applied `@SuppressLint("MissingPermission")` to the whole class, which
 * hid the lint warning without granting anything — on Android 12+ that meant a
 * `SecurityException` thrown from the relay service's `onCreate`, taking the
 * service down on launch.
 */
class BleTransportManager(private val context: Context) {

    companion object {
        private const val TAG = "MeshBle"
        val MESHLINE_SERVICE_UUID: UUID = UUID.fromString("0000FE60-0000-1000-8000-00805F9B34FB")
        val MESHLINE_CHAR_UUID: UUID = UUID.fromString("0000FE61-0000-1000-8000-00805F9B34FB")

        /** Conservative ATT payload budget; larger frames are chunked by the caller. */
        private const val MAX_ATT_PAYLOAD = 512
    }

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager?.adapter }

    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null

    private val connectedDevices = Collections.synchronizedSet(mutableSetOf<String>())
    private val peerCount = AtomicInteger(0)

    private data class WriteQueueState(
        val packets: List<ByteArray>,
        var index: Int,
        val characteristic: BluetoothGattCharacteristic
    )

    private val writeStates = ConcurrentHashMap<String, WriteQueueState>()

    val activePeersCount: Int get() = peerCount.get()

    /** Why the transport is not running, for display in the UI. */
    var lastError: String? = null
        private set

    fun isRunning(): Boolean = gattServer != null

    /**
     * Starts advertising, scanning, and the GATT server.
     * Returns false without side effects when prerequisites are missing.
     */
    fun startMeshTransport(): Boolean {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            lastError = "This device has no Bluetooth adapter."
            return false
        }
        if (!adapter.isEnabled) {
            lastError = "Bluetooth is turned off."
            return false
        }
        if (!MeshPermissions.canConnect(context)) {
            lastError = "Nearby devices permission is required."
            return false
        }

        return try {
            bleAdvertiser = adapter.bluetoothLeAdvertiser
            bleScanner = adapter.bluetoothLeScanner

            initGattServer()
            startAdvertising()
            startScanning()
            lastError = null
            true
        } catch (e: SecurityException) {
            // Possible if a permission is revoked between the check and the call.
            Log.e(TAG, "Bluetooth permission revoked mid-start.", e)
            lastError = "Nearby devices permission was revoked."
            stopMeshTransport()
            false
        } catch (e: Exception) {
            Log.e(TAG, "BLE transport failed to start.", e)
            lastError = "Bluetooth could not start."
            stopMeshTransport()
            false
        }
    }

    fun stopMeshTransport() {
        try {
            if (MeshPermissions.canAdvertise(context)) {
                bleAdvertiser?.stopAdvertising(advertiseCallback)
            }
            if (MeshPermissions.canScan(context)) {
                bleScanner?.stopScan(scanCallback)
            }
            if (MeshPermissions.canConnect(context)) {
                gattServer?.close()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission lost during shutdown.", e)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping BLE transport.", e)
        } finally {
            gattServer = null
            bleAdvertiser = null
            bleScanner = null
            connectedDevices.clear()
            writeStates.clear()
            peerCount.set(0)
        }
    }

    private fun startAdvertising() {
        if (!MeshPermissions.canAdvertise(context)) {
            Log.w(TAG, "Skipping advertising: permission not granted.")
            return
        }
        val advertiser = bleAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "This device cannot advertise over BLE; it can still scan and relay.")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            // The device name would leak an identifier to every passive listener
            // in range, and the mesh identity is carried inside signed packets.
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(MESHLINE_SERVICE_UUID))
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun startScanning() {
        if (!MeshPermissions.canScan(context)) {
            Log.w(TAG, "Skipping scan: permission not granted.")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MESHLINE_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private fun connectToPeer(device: BluetoothDevice) {
        if (!MeshPermissions.canConnect(context)) return
        try {
            device.connectGatt(context, false, gattClientCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Connect permission revoked.", e)
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            gatt ?: return
            val address = try {
                gatt.device?.address ?: return
            } catch (e: SecurityException) {
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    peerCount.incrementAndGet()
                    if (MeshPermissions.canConnect(context)) {
                        try {
                            gatt.discoverServices()
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Discover permission revoked.", e)
                        }
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    peerCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                    connectedDevices.remove(address)
                    writeStates.remove(address)
                    closeQuietly(gatt)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            gatt ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnectQuietly(gatt)
                return
            }

            val characteristic = gatt.getService(MESHLINE_SERVICE_UUID)
                ?.getCharacteristic(MESHLINE_CHAR_UUID)
            if (characteristic == null) {
                disconnectQuietly(gatt)
                return
            }

            val packets = StoreAndForwardManager.getInstance(context)
                .drainOutbound()
                .filter { it.size <= MAX_ATT_PAYLOAD }

            if (packets.isEmpty()) {
                disconnectQuietly(gatt)
                return
            }

            val address = try {
                gatt.device.address
            } catch (e: SecurityException) {
                return
            }
            val state = WriteQueueState(packets, 0, characteristic)
            writeStates[address] = state
            sendNextPacket(gatt, state)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            gatt ?: return
            val address = try {
                gatt.device.address
            } catch (e: SecurityException) {
                return
            }
            val state = writeStates[address]
            if (state == null) {
                disconnectQuietly(gatt)
            } else {
                sendNextPacket(gatt, state)
            }
        }
    }

    private fun sendNextPacket(gatt: BluetoothGatt, state: WriteQueueState) {
        if (!MeshPermissions.canConnect(context)) return

        if (state.index >= state.packets.size) {
            try {
                writeStates.remove(gatt.device.address)
            } catch (e: SecurityException) {
                // Nothing to clean up if we cannot read the address.
            }
            disconnectQuietly(gatt)
            return
        }

        val packet = state.packets[state.index]
        state.index++

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    state.characteristic,
                    packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                @Suppress("DEPRECATION")
                state.characteristic.value = packet
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(state.characteristic)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Write permission revoked.", e)
            disconnectQuietly(gatt)
        }
    }

    private fun initGattServer() {
        if (!MeshPermissions.canConnect(context)) return
        val manager = bluetoothManager ?: return

        gattServer = manager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(
            MESHLINE_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val characteristic = BluetoothGattCharacteristic(
            MESHLINE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> peerCount.incrementAndGet()
                BluetoothProfile.STATE_DISCONNECTED ->
                    peerCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val accepted = characteristic?.uuid == MESHLINE_CHAR_UUID &&
                value != null &&
                value.size <= MAX_ATT_PAYLOAD

            if (accepted) {
                // Authenticity is decided by the native core, not by us.
                StoreAndForwardManager.getInstance(context)
                    .processIncomingPacket(value!!, transport = "Bluetooth LE")
            }

            if (responseNeeded && device != null && MeshPermissions.canConnect(context)) {
                try {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                        offset,
                        null
                    )
                } catch (e: SecurityException) {
                    Log.w(TAG, "Response permission revoked.", e)
                }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Advertising started.")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertising failed with code $errorCode.")
            lastError = when (errorCode) {
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                    "This device cannot advertise over Bluetooth LE."
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                    "Too many Bluetooth advertisers are active."
                else -> null
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val address = try {
                device.address
            } catch (e: SecurityException) {
                return
            }
            if (connectedDevices.add(address)) {
                connectToPeer(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed with code $errorCode.")
        }
    }

    private fun disconnectQuietly(gatt: BluetoothGatt) {
        try {
            gatt.disconnect()
        } catch (e: SecurityException) {
            Log.w(TAG, "Disconnect permission revoked.", e)
        }
    }

    private fun closeQuietly(gatt: BluetoothGatt) {
        try {
            gatt.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "Close permission revoked.", e)
        }
    }
}
