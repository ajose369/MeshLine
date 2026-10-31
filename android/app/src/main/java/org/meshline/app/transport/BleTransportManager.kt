package org.meshline.app.transport

import android.annotation.SuppressLint
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
import android.os.Handler
import android.os.Looper
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

        /** Default deadline for a single GATT write operation to complete. */
        const val DEFAULT_WRITE_TIMEOUT_MS = 5000L

        /** Default deadline for GATT service discovery to complete. */
        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 10000L
    }

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager?.adapter }

    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null

    private val deviceRssi = ConcurrentHashMap<String, Int>()

    /** One BLE write, and which packet it belongs to. */
    internal data class Frame(val packetIndex: Int, val bytes: ByteArray)

    internal data class WriteQueueState(
        val packets: List<ByteArray>,
        val frames: List<Frame>,
        @Volatile var submittedIndex: Int = -1,
        @Volatile var acknowledgedIndex: Int = -1,
        val characteristic: BluetoothGattCharacteristic,
        var timeoutRunnable: Runnable? = null
    ) {
        /**
         * Returns the index of the first packet that was not fully acknowledged.
         * If the final fragment of a packet failed, the entire packet is uncompleted
         * and must be requeued.
         */
        fun firstUncompletedPacketIndex(): Int {
            if (frames.isEmpty()) return packets.size
            if (acknowledgedIndex < 0) return 0

            val lastAckedPacket = frames[acknowledgedIndex].packetIndex
            var lastFrameOfAckedPacket = acknowledgedIndex
            while (lastFrameOfAckedPacket + 1 < frames.size &&
                frames[lastFrameOfAckedPacket + 1].packetIndex == lastAckedPacket
            ) {
                lastFrameOfAckedPacket++
            }

            return if (acknowledgedIndex >= lastFrameOfAckedPacket) {
                lastAckedPacket + 1
            } else {
                lastAckedPacket
            }
        }
    }

    internal val connectedDevices = Collections.synchronizedSet(mutableSetOf<String>())
    internal val peerCount = AtomicInteger(0)
    internal val writeStates = ConcurrentHashMap<String, WriteQueueState>()
    internal val discoveryTimeouts = ConcurrentHashMap<String, Runnable>()
    internal var writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS
    internal var discoveryTimeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS
    internal var timeoutHandler: Handler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    private fun defaultWriteCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val res = gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            res == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    private fun defaultDiscoverServices(gatt: BluetoothGatt): Boolean {
        return gatt.discoverServices()
    }

    @SuppressLint("MissingPermission")
    private fun defaultDisconnect(gatt: BluetoothGatt) {
        gatt.disconnect()
    }

    @SuppressLint("MissingPermission")
    private fun defaultClose(gatt: BluetoothGatt) {
        gatt.close()
    }

    internal var writeCharacteristicInvoker: (gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) -> Boolean =
        ::defaultWriteCharacteristic

    internal var discoverServicesInvoker: (gatt: BluetoothGatt) -> Boolean =
        ::defaultDiscoverServices

    internal var disconnectInvoker: (gatt: BluetoothGatt) -> Unit =
        ::defaultDisconnect

    internal var closeInvoker: (gatt: BluetoothGatt) -> Unit =
        ::defaultClose

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
            StoreAndForwardManager.getInstance(context).setTransportActive(true)
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
            deviceRssi.clear()
            discoveryTimeouts.values.forEach { timeoutHandler.removeCallbacks(it) }
            discoveryTimeouts.clear()
            val inFlight = writeStates.values.toList()
            writeStates.clear()
            inFlight.forEach { requeueUnsent(it) }
            negotiatedMtu.clear()
            synchronized(reassemblyLock) { reassembler.clear() }
            peerCount.set(0)
            StoreAndForwardManager.getInstance(context).setTransportActive(false)
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

    internal val gattClientCallback = object : BluetoothGattCallback() {
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

                    // Schedule discovery deadline
                    scheduleDiscoveryTimeout(gatt, address)

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
                        val ok = discoverServicesQuietly(gatt)
                        if (!ok) {
                            cancelDiscoveryTimeout(address)
                            connectedDevices.remove(address)
                            negotiatedMtu.remove(address)
                        }
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    cancelDiscoveryTimeout(address)
                    peerCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                    connectedDevices.remove(address)
                    negotiatedMtu.remove(address)
                    deviceRssi.remove(address)
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

            val ok = discoverServicesQuietly(gatt)
            if (!ok) {
                cancelDiscoveryTimeout(address)
                connectedDevices.remove(address)
                negotiatedMtu.remove(address)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            gatt ?: return
            val address = try {
                gatt.device?.address
            } catch (e: SecurityException) {
                null
            }

            if (address != null) {
                cancelDiscoveryTimeout(address)
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (address != null) {
                    connectedDevices.remove(address)
                    negotiatedMtu.remove(address)
                }
                disconnectQuietly(gatt)
                return
            }

            val characteristic = gatt.getService(MESHLINE_SERVICE_UUID)
                ?.getCharacteristic(MESHLINE_CHAR_UUID)
            if (characteristic == null) {
                if (address != null) {
                    connectedDevices.remove(address)
                    negotiatedMtu.remove(address)
                }
                disconnectQuietly(gatt)
                return
            }

            if (address == null) {
                disconnectQuietly(gatt)
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

            val state = WriteQueueState(
                packets = packets,
                frames = frames,
                submittedIndex = -1,
                acknowledgedIndex = -1,
                characteristic = characteristic
            )
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

            cancelTimeout(state)

            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Carrying on would send the remaining fragments of a packet
                // whose earlier fragments never arrived, which can only ever
                // reassemble into rubbish. Stop and put the rest back.
                Log.w(TAG, "Write to $address failed with status $status; requeuing the rest.")
                requeueUnsent(address)
                disconnectQuietly(gatt)
                return
            }

            // Mark the submitted frame as successfully acknowledged
            state.acknowledgedIndex = state.submittedIndex

            // If this was the last frame of the entire queue, finish and disconnect
            if (state.acknowledgedIndex >= state.frames.size - 1) {
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
    internal fun requeueUnsent(address: String) {
        val state = writeStates.remove(address) ?: return
        requeueUnsent(state)
    }

    internal fun requeueUnsent(state: WriteQueueState) {
        cancelTimeout(state)
        val firstUncompleted = state.firstUncompletedPacketIndex()
        val store = StoreAndForwardManager.getInstance(context)
        for (i in firstUncompleted until state.packets.size) {
            store.queueRawPacket(state.packets[i])
        }
    }

    private fun scheduleTimeout(gatt: BluetoothGatt, address: String, state: WriteQueueState) {
        cancelTimeout(state)
        val runnable = Runnable {
            Log.w(TAG, "Write operation timed out after ${writeTimeoutMs}ms for $address; requeuing.")
            requeueUnsent(address)
            disconnectQuietly(gatt)
        }
        state.timeoutRunnable = runnable
        timeoutHandler.postDelayed(runnable, writeTimeoutMs)
    }

    private fun cancelTimeout(state: WriteQueueState) {
        state.timeoutRunnable?.let {
            timeoutHandler.removeCallbacks(it)
            state.timeoutRunnable = null
        }
    }

    internal fun scheduleDiscoveryTimeout(gatt: BluetoothGatt, address: String) {
        cancelDiscoveryTimeout(address)
        val runnable = Runnable {
            Log.w(TAG, "Service discovery timed out after ${discoveryTimeoutMs}ms for $address; disconnecting.")
            discoveryTimeouts.remove(address)
            peerCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            connectedDevices.remove(address)
            negotiatedMtu.remove(address)
            disconnectQuietly(gatt)
            closeQuietly(gatt)
        }
        discoveryTimeouts[address] = runnable
        timeoutHandler.postDelayed(runnable, discoveryTimeoutMs)
    }

    internal fun cancelDiscoveryTimeout(address: String) {
        discoveryTimeouts.remove(address)?.let {
            timeoutHandler.removeCallbacks(it)
        }
    }

    internal fun discoverServicesQuietly(gatt: BluetoothGatt): Boolean {
        val address = try { gatt.device?.address } catch (e: SecurityException) { null }
        if (!MeshPermissions.canConnect(context)) {
            if (address != null) {
                cancelDiscoveryTimeout(address)
                connectedDevices.remove(address)
            }
            disconnectQuietly(gatt)
            return false
        }
        return try {
            val ok = discoverServicesInvoker(gatt)
            if (!ok) {
                Log.w(TAG, "discoverServices() returned false; disconnecting.")
                if (address != null) {
                    cancelDiscoveryTimeout(address)
                    connectedDevices.remove(address)
                }
                disconnectQuietly(gatt)
            }
            ok
        } catch (e: SecurityException) {
            Log.w(TAG, "Discover permission revoked.", e)
            if (address != null) {
                cancelDiscoveryTimeout(address)
                connectedDevices.remove(address)
            }
            disconnectQuietly(gatt)
            false
        }
    }

    internal fun sendNextFrame(gatt: BluetoothGatt, state: WriteQueueState) {
        val address = try {
            gatt.device?.address ?: "unknown"
        } catch (e: SecurityException) {
            "unknown"
        }

        if (!MeshPermissions.canConnect(context)) {
            requeueUnsent(address)
            disconnectQuietly(gatt)
            return
        }

        val nextIndex = state.submittedIndex + 1
        if (nextIndex >= state.frames.size) {
            // All frames have been submitted. If all frames have been acknowledged, disconnect.
            if (state.acknowledgedIndex >= state.frames.size - 1) {
                writeStates.remove(address)
                disconnectQuietly(gatt)
            }
            return
        }

        val frame = state.frames[nextIndex].bytes
        state.submittedIndex = nextIndex

        // Schedule operation deadline before writing
        scheduleTimeout(gatt, address, state)

        val writeInitiated = try {
            writeCharacteristicInvoker(gatt, state.characteristic, frame)
        } catch (e: SecurityException) {
            Log.w(TAG, "Write permission revoked.", e)
            false
        }

        if (!writeInitiated) {
            Log.w(TAG, "writeCharacteristic() rejected immediately for $address; requeuing.")
            requeueUnsent(address)
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
                    // fragments occupying the reassembly table or keep stale RSSI.
                    address?.let { addr ->
                        deviceRssi.remove(addr)
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
                    val directRssi = if (deviceKey != "unknown") deviceRssi[deviceKey] else null
                    StoreAndForwardManager.getInstance(context)
                        .processIncomingPacket(
                            packet,
                            transport = "Bluetooth LE",
                            directLinkRssi = directRssi
                        )
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
            StoreAndForwardManager.getInstance(context).setTransportActive(false)
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
            deviceRssi[address] = result.rssi
            if (connectedDevices.add(address)) {
                connectToPeer(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed with code $errorCode.")
            StoreAndForwardManager.getInstance(context).setTransportActive(false)
        }
    }

    private fun disconnectQuietly(gatt: BluetoothGatt) {
        try {
            disconnectInvoker(gatt)
        } catch (e: SecurityException) {
            Log.w(TAG, "Disconnect permission revoked.", e)
        }
    }

    private fun closeQuietly(gatt: BluetoothGatt) {
        try {
            closeInvoker(gatt)
        } catch (e: SecurityException) {
            Log.w(TAG, "Close permission revoked.", e)
        }
    }
}
