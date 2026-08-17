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

        /**
         * Largest single GATT write we will accept: exactly what the largest
         * negotiable MTU can carry.
         *
         * Derived rather than written as a literal. The previous hard-coded 512
         * was two bytes short of a full write at the maximum MTU, which would
         * have rejected precisely the frames a well-negotiated link produces.
         */
        private const val MAX_ATT_PAYLOAD =
            PacketFraming.MAX_MTU - PacketFraming.ATT_WRITE_OVERHEAD
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

    /** One BLE write, and which packet it belongs to. */
    private data class Frame(val packetIndex: Int, val bytes: ByteArray)

    private data class WriteQueueState(
        val packets: List<ByteArray>,
        val frames: List<Frame>,
        var index: Int,
        val characteristic: BluetoothGattCharacteristic
    )

    private val writeStates = ConcurrentHashMap<String, WriteQueueState>()

    /** Negotiated ATT MTU per peer, until one is agreed. */
    private val negotiatedMtu = ConcurrentHashMap<String, Int>()

    /**
     * Inbound reassembly. Guarded by its own lock rather than a concurrent map,
     * because completing a packet is a read-modify-write across several fields
     * and BLE callbacks arrive on a binder thread.
     */
    private val reassembler = PacketReassembler()
    private val reassemblyLock = Any()

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
            negotiatedMtu.clear()
            synchronized(reassemblyLock) { reassembler.clear() }
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

        // The grant is checked above, but it can be revoked between that check
        // and this call, which would take the relay service down with it.
        try {
            bleScanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Scan permission revoked before the scan started.", e)
        }
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
                    if (!MeshPermissions.canConnect(context)) return

                    // Ask for the largest MTU before anything else. Every packet
                    // this app produces is larger than the 23-byte default, so
                    // at the default a packet takes 20 fragments where it could
                    // take one. Fragmentation still covers us if this fails.
                    val requested = try {
                        gatt.requestMtu(PacketFraming.MAX_MTU)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "MTU permission revoked.", e)
                        false
                    }

                    if (!requested) {
                        Log.w(TAG, "MTU request refused for $address; using the default.")
                        discoverServicesQuietly(gatt)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    peerCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                    connectedDevices.remove(address)
                    negotiatedMtu.remove(address)
                    // Anything half-written is requeued rather than lost: these
                    // packets were removed from the store's outbound queue when
                    // the connection began.
                    requeueUnsent(address)
                    synchronized(reassemblyLock) { reassembler.forgetDevice(address) }
                    closeQuietly(gatt)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            gatt ?: return
            val address = try {
                gatt.device?.address ?: return
            } catch (e: SecurityException) {
                return
            }

            // A failed negotiation is not fatal; it just means smaller frames.
            val agreed = if (status == BluetoothGatt.GATT_SUCCESS) mtu else PacketFraming.DEFAULT_MTU
            negotiatedMtu[address] = agreed
            Log.i(TAG, "MTU for $address is $agreed (${PacketFraming.payloadCapacity(agreed)} B per frame).")

            discoverServicesQuietly(gatt)
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

            val address = try {
                gatt.device.address
            } catch (e: SecurityException) {
                return
            }

            val packets = StoreAndForwardManager.getInstance(context).drainOutbound()
            if (packets.isEmpty()) {
                disconnectQuietly(gatt)
                return
            }

            val mtu = negotiatedMtu[address] ?: PacketFraming.DEFAULT_MTU
            val frames = ArrayList<Frame>()
            packets.forEachIndexed { packetIndex, packet ->
                val fragments = PacketFraming.fragment(packet, mtu)
                if (fragments.isEmpty()) {
                    // Refusing is the honest outcome: a packet this size cannot
                    // be delivered, and sending a prefix of it would produce a
                    // signature failure at the far end instead of an error here.
                    Log.w(TAG, "Dropping a ${packet.size}-byte packet that cannot be framed.")
                } else {
                    fragments.forEach { frames += Frame(packetIndex, it) }
                }
            }

            if (frames.isEmpty()) {
                disconnectQuietly(gatt)
                return
            }

            val state = WriteQueueState(packets, frames, 0, characteristic)
            writeStates[address] = state
            sendNextFrame(gatt, state)
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
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Carrying on would send the remaining fragments of a packet
                // whose earlier fragments never arrived, which can only ever
                // reassemble into rubbish. Stop and put the rest back.
                Log.w(TAG, "Write to $address failed with status $status; requeuing the rest.")
                requeueUnsent(address)
                writeStates.remove(address)
                disconnectQuietly(gatt)
                return
            }

            sendNextFrame(gatt, state)
        }
    }

    /**
     * Returns packets that were drained for a connection but never fully
     * written, so a dropped link delays delivery instead of destroying it.
     */
    private fun requeueUnsent(address: String) {
        val state = writeStates.remove(address) ?: return
        val firstUnsent = state.frames.getOrNull(state.index)?.packetIndex ?: return
        val store = StoreAndForwardManager.getInstance(context)
        for (i in firstUnsent until state.packets.size) {
            store.queueRawPacket(state.packets[i])
        }
    }

    private fun discoverServicesQuietly(gatt: BluetoothGatt) {
        if (!MeshPermissions.canConnect(context)) return
        try {
            gatt.discoverServices()
        } catch (e: SecurityException) {
            Log.w(TAG, "Discover permission revoked.", e)
        }
    }

    private fun sendNextFrame(gatt: BluetoothGatt, state: WriteQueueState) {
        if (!MeshPermissions.canConnect(context)) return

        if (state.index >= state.frames.size) {
            try {
                writeStates.remove(gatt.device.address)
            } catch (e: SecurityException) {
                // Nothing to clean up if we cannot read the address.
            }
            disconnectQuietly(gatt)
            return
        }

        val frame = state.frames[state.index].bytes
        state.index++

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    state.characteristic,
                    frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                @Suppress("DEPRECATION")
                state.characteristic.value = frame
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

        // As with scanning, the grant checked above can disappear underneath
        // this call. A relay that cannot open its server is degraded; a relay
        // that crashes takes every carried packet with it.
        try {
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
        } catch (e: SecurityException) {
            Log.w(TAG, "Connect permission revoked before the GATT server opened.", e)
            gattServer = null
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val address = try {
                device?.address
            } catch (e: SecurityException) {
                null
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> peerCount.incrementAndGet()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    peerCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                    // A peer that vanishes mid-packet must not leave its
                    // fragments occupying the reassembly table.
                    address?.let { addr ->
                        synchronized(reassemblyLock) { reassembler.forgetDevice(addr) }
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            val address = try {
                device?.address ?: return
            } catch (e: SecurityException) {
                return
            }
            negotiatedMtu[address] = mtu
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
                // Frames are reassembled here, but nothing is trusted here: the
                // result goes straight to the native core, which decides
                // authenticity. Keying by device address means one peer cannot
                // interfere with another's part-built packets.
                val deviceKey = try {
                    device?.address ?: "unknown"
                } catch (e: SecurityException) {
                    "unknown"
                }

                val packet = synchronized(reassemblyLock) {
                    reassembler.accept(deviceKey, value!!, System.currentTimeMillis())
                }

                if (packet != null) {
                    StoreAndForwardManager.getInstance(context)
                        .processIncomingPacket(packet, transport = "Bluetooth LE")
                }
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
