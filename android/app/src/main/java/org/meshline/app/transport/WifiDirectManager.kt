package org.meshline.app.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager

@SuppressLint("MissingPermission")
class WifiDirectManager(private val context: Context) {
    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var channel: WifiP2pManager.Channel? = null

    fun initWifiDirect() {
        manager?.let { m ->
            channel = m.initialize(context, context.mainLooper, null)
        }
    }

    fun discoverPeers(onPeersFound: (Int) -> Unit) {
        channel?.let { ch ->
            manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onPeersFound(1)
                }

                override fun onFailure(reason: Int) {
                    onPeersFound(0)
                }
            })
        }
    }
}
