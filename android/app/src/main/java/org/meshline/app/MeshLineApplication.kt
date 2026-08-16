package org.meshline.app

import android.app.Application
import android.util.Log
import org.meshline.app.bridge.MeshCoreBridge

/**
 * Initialises the secure mesh core once, as early as possible.
 *
 * Doing this here rather than in an activity means the core is ready before the
 * relay service starts, including when the system restarts a sticky service with
 * no activity in sight.
 */
class MeshLineApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (!MeshCoreBridge.initialise(this)) {
            // Not fatal here: the UI surfaces the reason and disables sending.
            // Crashing would leave the user with no explanation at all.
            Log.e("MeshLineApplication", "Mesh core unavailable: ${MeshCoreBridge.status}")
        }
    }
}
