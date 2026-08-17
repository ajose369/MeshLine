package org.meshline.app

import android.app.Application
import android.util.Log
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.security.SecureStateStore

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
            Log.e(TAG, "Mesh core unavailable: ${MeshCoreBridge.status}")
            return
        }

        // Order matters: the core must hold its restored sessions and group keys
        // before the store reads a group list off it, or the first launch after
        // a restart would show no groups and offer to re-handshake with peers we
        // already have sessions with.
        if (SecureStateStore.restore(this)) {
            Log.i(TAG, "Restored sessions and group keys from encrypted storage.")
        }
        StoreAndForwardManager.getInstance(this)
    }

    /**
     * The process is going away. Anything not written now is lost, so this is
     * the last chance to keep sessions across the restart.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN && MeshCoreBridge.isReady()) {
            StoreAndForwardManager.getInstance(this).persistNow()
        }
    }

    private companion object {
        const val TAG = "MeshLineApplication"
    }
}
