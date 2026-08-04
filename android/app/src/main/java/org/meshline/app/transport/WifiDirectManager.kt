package org.meshline.app.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

@SuppressLint("MissingPermission")
class WifiDirectManager(private val context: Context) {

    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var channel: WifiP2pManager.Channel? = null
    private var serverSocket: ServerSocket? = null

    fun initWifiDirect() {
        manager?.let { m ->
            channel = m.initialize(context, context.mainLooper, null)
        }
    }

    fun discoverPeers(onPeersFound: (List<WifiP2pDevice>) -> Unit) {
        channel?.let { ch ->
            manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    manager?.requestPeers(ch) { peerList ->
                        onPeersFound(peerList.deviceList.toList())
                    }
                }

                override fun onFailure(reason: Int) {
                    onPeersFound(emptyList())
                }
            })
        }
    }

    fun connectToDevice(device: WifiP2pDevice, onConnected: (Boolean) -> Unit) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        channel?.let { ch ->
            manager?.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onConnected(true)
                }

                override fun onFailure(reason: Int) {
                    onConnected(false)
                }
            })
        }
    }

    fun startPayloadServerSocket(port: Int = 8888, onPayloadReceived: (ByteArray) -> Unit) {
        thread {
            try {
                serverSocket = ServerSocket(port)
                while (!serverSocket!!.isClosed) {
                    val client: Socket = serverSocket!!.accept()
                    val input: InputStream = client.getInputStream()
                    val buffer = input.readBytes()
                    onPayloadReceived(buffer)
                    client.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun sendPayloadAsync(hostIp: String, port: Int = 8888, data: ByteArray, onComplete: (Boolean) -> Unit) {
        thread {
            try {
                val socket = Socket(hostIp, port)
                val output: OutputStream = socket.getOutputStream()
                output.write(data)
                output.flush()
                socket.close()
                onComplete(true)
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete(false)
            }
        }
    }

    fun stopPayloadServerSocket() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
