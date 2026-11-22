package com.example.camera.gallery

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
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
        val previous = getClips()
        val existing = previous.filterNot { it.id == clip.id }
        val updated = (listOf(clip) + existing).take(MAX_SAVED_CLIPS)
        persistClips(updated)

        // Remove private companion files whose paired clips were pruned from the 100-item list.
        val retainedIds = updated.mapTo(HashSet()) { it.id }
        previous.filterNot { it.id in retainedIds }.forEach { removed ->
            deletePrivateCompanion(removed.ultraWideUri)
        }
    }

    /**
     * Moves legacy Special Video ultra-wide MP4s out of public MediaStore.
     * A companion is rewritten to a file:// URI only after its bytes are copied successfully.
     */
    fun migrateLegacyUltraWideClipsToPrivateStorage() {
        val clips = getClips()
        var changed = false
        val migrated = clips.map { clip ->
            val publicUri = clip.ultraWideUri
            if (publicUri.scheme != "content") {
                clip
            } else {
                val directory = File(preferencesStorageRoot(), "special_video_companions")
                if (!directory.exists() && !directory.mkdirs()) {
                    clip
                } else {
                    val safeTimestamp = clip.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
                    val suffix = Integer.toHexString(publicUri.toString().hashCode())
                    val destination = File(directory, "VCAM_SPECIAL_ULTRAWIDE_${safeTimestamp}_${suffix}.mp4")
                    try {
                        if (!destination.exists() || destination.length() <= 0L) {
                            val input = appContext.contentResolver.openInputStream(publicUri)
                            if (input == null) {
                                return@map clip
                            }
                            input.use { source ->
                                destination.outputStream().buffered().use { output ->
                                    source.copyTo(output)
                                    output.flush()
                                }
                            }
                        }

                        if (!destination.exists() || destination.length() <= 0L) {
                            destination.delete()
                            clip
                        } else {
                            try {
                                // These URIs are the app's own legacy _ULTRAWIDE companion rows.
                                appContext.contentResolver.delete(publicUri, null, null)
                            } catch (t: Throwable) {
                                Log.w(TAG, "Could not remove legacy public companion $publicUri", t)
                            }
                            changed = true
                            clip.copy(ultraWideUri = Uri.fromFile(destination))
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Could not migrate legacy ultra-wide companion $publicUri", t)
                        try { destination.delete() } catch (_: Throwable) {}
                        clip
                    }
                }
            }
        }
        if (changed) persistClips(migrated)
    }

    private fun preferencesStorageRoot(): File = File(appContext.filesDir, "")

    private fun deletePrivateCompanion(uri: Uri) {
        if (uri.scheme != "file") return
        try {
            uri.path?.let { File(it).delete() }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not delete pruned private companion $uri", t)
        }
    }

    private fun persistClips(clips: List<SpecialVideoClip>) {
        val array = JSONArray()
        clips.forEach { item ->
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
