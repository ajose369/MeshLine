package org.meshline.app.gis

import android.content.Context
import java.io.File
import java.io.FileOutputStream

class OfflineTileManager(private val context: Context) {

    fun getOfflineMapPath(): String {
        val mapFile = File(context.filesDir, "maps/disaster_zone.mbtiles")
        if (!mapFile.exists()) {
            mapFile.parentFile?.mkdirs()
            // Create dummy MBTiles vector container if asset extract is not yet unzipped
            FileOutputStream(mapFile).use { out ->
                out.write("MBTILES_V1_VECTOR_EXTRACT_HEADER".toByteArray(Charsets.UTF_8))
            }
        }
        return mapFile.absolutePath
    }

    fun isOfflineMapAvailable(): Boolean {
        val mapFile = File(context.filesDir, "maps/disaster_zone.mbtiles")
        return mapFile.exists() && mapFile.length() > 0
    }
}
