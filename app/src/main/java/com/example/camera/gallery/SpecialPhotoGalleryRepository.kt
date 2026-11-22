package com.example.camera.gallery

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class SpecialPhotoPair(
    val id: String,
    val displayName: String,
    val mainUri: Uri,
    val ultraWideUri: Uri,
    val timestamp: Long,
    val sensorTimestampSkewNs: Long
)

/** Persists the relationship between the 1x and 0.5x JPEGs from one Special Photo shutter press. */
class SpecialPhotoGalleryRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun getPairs(): List<SpecialPhotoPair> {
        val raw = preferences.getString(KEY_PAIRS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val main = item.optString(KEY_MAIN_URI).takeIf { it.isNotBlank() } ?: continue
                    val ultraWide = item.optString(KEY_ULTRAWIDE_URI).takeIf { it.isNotBlank() } ?: continue
                    add(
                        SpecialPhotoPair(
                            id = item.optString(KEY_ID, main),
                            displayName = item.optString(KEY_DISPLAY_NAME, "Special Photo"),
                            mainUri = Uri.parse(main),
                            ultraWideUri = Uri.parse(ultraWide),
                            timestamp = item.optLong(KEY_TIMESTAMP, 0L),
                            sensorTimestampSkewNs = item.optLong(KEY_SKEW_NS, -1L).coerceAtLeast(-1L)
                        )
                    )
                }
            }.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addPair(
        mainUri: Uri,
        ultraWideUri: Uri,
        displayName: String,
        timestamp: Long,
        sensorTimestampSkewNs: Long
    ) {
        val pair = SpecialPhotoPair(
            id = mainUri.toString(),
            displayName = displayName,
            mainUri = mainUri,
            ultraWideUri = ultraWideUri,
            timestamp = timestamp,
            sensorTimestampSkewNs = sensorTimestampSkewNs
        )
        val updated = (listOf(pair) + getPairs().filterNot { it.id == pair.id }).take(MAX_SAVED_PAIRS)
        val array = JSONArray()
        updated.forEach { item ->
            array.put(
                JSONObject().apply {
                    put(KEY_ID, item.id)
                    put(KEY_DISPLAY_NAME, item.displayName)
                    put(KEY_MAIN_URI, item.mainUri.toString())
                    put(KEY_ULTRAWIDE_URI, item.ultraWideUri.toString())
                    put(KEY_TIMESTAMP, item.timestamp)
                    put(KEY_SKEW_NS, item.sensorTimestampSkewNs)
                }
            )
        }
        preferences.edit().putString(KEY_PAIRS, array.toString()).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "special_photo_gallery"
        private const val KEY_PAIRS = "paired_photos"
        private const val KEY_ID = "id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_MAIN_URI = "main_uri"
        private const val KEY_ULTRAWIDE_URI = "ultra_wide_uri"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_SKEW_NS = "sensor_timestamp_skew_ns"
        private const val MAX_SAVED_PAIRS = 200
    }
}
