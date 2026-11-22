package com.example.camera.gallery

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class SpecialVideoClip(
    val id: String,
    val displayName: String,
    val mainUri: Uri,
    val ultraWideUri: Uri,
    val timestamp: Long
)

/**
 * Stores the pairing between the two MediaStore videos created by Special Video.
 * The actual MP4 files remain normal videos in DCIM/Camera, so they are also visible
 * to other gallery applications.
 */
class SpecialVideoGalleryRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun getClips(): List<SpecialVideoClip> {
        val raw = preferences.getString(KEY_CLIPS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val main = item.optString(KEY_MAIN_URI).takeIf { it.isNotBlank() } ?: continue
                    val ultraWide = item.optString(KEY_ULTRAWIDE_URI).takeIf { it.isNotBlank() } ?: continue
                    add(
                        SpecialVideoClip(
                            id = item.optString(KEY_ID, main),
                            displayName = item.optString(KEY_DISPLAY_NAME, "Special Video"),
                            mainUri = Uri.parse(main),
                            ultraWideUri = Uri.parse(ultraWide),
                            timestamp = item.optLong(KEY_TIMESTAMP, 0L)
                        )
                    )
                }
            }.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addClip(
        mainUri: Uri,
        ultraWideUri: Uri,
        displayName: String,
        timestamp: Long
    ) {
        val clip = SpecialVideoClip(
            id = mainUri.toString(),
            displayName = displayName,
            mainUri = mainUri,
            ultraWideUri = ultraWideUri,
            timestamp = timestamp
        )
        val existing = getClips().filterNot { it.id == clip.id }
        val updated = (listOf(clip) + existing).take(MAX_SAVED_CLIPS)
        val array = JSONArray()
        updated.forEach { item ->
            array.put(
                JSONObject().apply {
                    put(KEY_ID, item.id)
                    put(KEY_DISPLAY_NAME, item.displayName)
                    put(KEY_MAIN_URI, item.mainUri.toString())
                    put(KEY_ULTRAWIDE_URI, item.ultraWideUri.toString())
                    put(KEY_TIMESTAMP, item.timestamp)
                }
            )
        }
        preferences.edit().putString(KEY_CLIPS, array.toString()).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "special_video_gallery"
        private const val KEY_CLIPS = "paired_clips"
        private const val KEY_ID = "id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_MAIN_URI = "main_uri"
        private const val KEY_ULTRAWIDE_URI = "ultra_wide_uri"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val MAX_SAVED_CLIPS = 100
    }
}
