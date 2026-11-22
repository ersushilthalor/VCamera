package com.example.camera.gallery

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.Locale

data class MediaGalleryVideo(
    val id: String,
    val displayName: String,
    val uri: Uri,
    val timestamp: Long,
    val durationMs: Long,
    val isVCameraRecording: Boolean,
    val ultraWideUri: Uri? = null
)

/**
 * Reads the shared camera-video library. The DCIM/Camera folder is included so
 * recordings made with older VCamera builds remain visible after access is granted.
 * App-owned MediaStore videos are also included even when they were saved elsewhere.
 */
class MediaGalleryRepository(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun getVideos(specialClips: List<SpecialVideoClip>): List<MediaGalleryVideo> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.DATE_TAKEN)
            add(MediaStore.Video.Media.DATE_ADDED)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.DESCRIPTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Video.Media.RELATIVE_PATH)
                add(MediaStore.Video.Media.OWNER_PACKAGE_NAME)
            } else {
                add(MediaStore.Video.Media.DATA)
            }
        }.toTypedArray()

        // Query the full shared video collection instead of filtering to DCIM/Camera.
        // Older VCamera builds and imported/copied recordings may have different paths or names.


        // MediaStore can return one item under different volume aliases
        // ("external" while querying and "external_primary" while saving).
        // Match the collection path + row ID instead of the full URI string.
        val specialByMainIdentity = specialClips.mapNotNull { clip ->
            mediaStoreIdentity(clip.mainUri)?.let { identity -> identity to clip }
        }.toMap()
        val pairedUltraWideIdentities = specialClips.mapNotNull {
            mediaStoreIdentity(it.ultraWideUri)
        }.toSet()
        // Keep exact-URI matching for older/unusual content providers.
        val specialByMainUri = specialClips.associateBy { it.mainUri.toString() }
        val pairedUltraWideUris = specialClips.map { it.ultraWideUri.toString() }.toSet()
        val videos = ArrayList<MediaGalleryVideo>()

        try {
            resolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_TAKEN} DESC, ${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val dateTakenColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                val dateAddedColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val descriptionColumn = cursor.getColumnIndex(MediaStore.Video.Media.DESCRIPTION)
                val ownerColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Video.Media.OWNER_PACKAGE_NAME)
                } else -1

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(collection, mediaId)
                    val mediaIdentity = mediaStoreIdentity(uri)
                    if ((mediaIdentity != null && mediaIdentity in pairedUltraWideIdentities) ||
                        uri.toString() in pairedUltraWideUris) {
                        continue
                    }

                    val name = if (nameColumn >= 0) cursor.getString(nameColumn).orEmpty() else ""
                    val specialClip = mediaIdentity?.let { specialByMainIdentity[it] }
                        ?: specialByMainUri[uri.toString()]
                    val ownerPackage = if (ownerColumn >= 0) cursor.getString(ownerColumn) else null
                    val description = if (descriptionColumn >= 0) cursor.getString(descriptionColumn).orEmpty() else ""
                    val dateTaken = if (dateTakenColumn >= 0) cursor.getLong(dateTakenColumn) else 0L
                    val dateAdded = if (dateAddedColumn >= 0) cursor.getLong(dateAddedColumn) else 0L
                    val timestamp = if (dateTaken > 0L) dateTaken else dateAdded * 1000L
                    val duration = if (durationColumn >= 0) cursor.getLong(durationColumn).coerceAtLeast(0L) else 0L

                    videos += MediaGalleryVideo(
                        id = mediaId.toString(),
                        displayName = name.ifBlank { "Video" },
                        uri = uri,
                        timestamp = timestamp,
                        durationMs = duration,
                        isVCameraRecording = ownerPackage == appContext.packageName ||
                            name.startsWith("VCAM_", ignoreCase = true) ||
                            description.contains("VCamera", ignoreCase = true) ||
                            specialClip != null,
                        ultraWideUri = specialClip?.ultraWideUri
                    )
                }
            }
        } catch (securityException: SecurityException) {
            Log.w(TAG, "Video library access has not been granted", securityException)
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to query the video library", exception)
        }

        // Older fallback saves can live under Android/data/<package>/files/Movies,
        // which is app-private and therefore is not indexed in the shared MediaStore.
        val knownUris = videos.mapTo(HashSet()) { it.uri.toString() }
        getAppSpecificVideos().forEach { appVideo ->
            if (knownUris.add(appVideo.uri.toString())) videos += appVideo
        }

        return videos.sortedByDescending { it.timestamp }
    }

    private fun getAppSpecificVideos(): List<MediaGalleryVideo> {
        val directories = buildList {
            try {
                addAll(appContext.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).filterNotNull())
            } catch (exception: Exception) {
                Log.w(TAG, "Unable to inspect app-specific external Movies directories", exception)
            }
            add(File(appContext.filesDir, "Movies"))
        }.distinctBy { it.absolutePath }

        val discovered = LinkedHashMap<String, MediaGalleryVideo>()
        directories.forEach { directory ->
            val files = try {
                directory.listFiles().orEmpty()
            } catch (exception: Exception) {
                Log.w(TAG, "Unable to list app-specific video directory: " + directory, exception)
                emptyArray()
            }
            files.forEach { file ->
                val extension = file.extension.lowercase(Locale.ROOT)
                if (!file.isFile || file.length() <= 0L || extension !in VIDEO_EXTENSIONS) return@forEach

                val duration = readVideoDuration(file)
                val uri = Uri.fromFile(file)
                discovered[file.absolutePath] = MediaGalleryVideo(
                    id = "file:" + file.absolutePath,
                    displayName = file.name,
                    uri = uri,
                    timestamp = file.lastModified(),
                    durationMs = duration,
                    isVCameraRecording = true
                )
            }
        }
        return discovered.values.toList()
    }

    private fun readVideoDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to read duration for app-specific video: " + file.name, exception)
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun mediaStoreIdentity(uri: Uri): String? {
        val segments = uri.pathSegments
        if (segments.size < 3) return null
        val collectionPath = segments.takeLast(3)
        if (collectionPath[0] !in setOf("video", "image", "audio")) return null
        return "${uri.authority}/${collectionPath.joinToString("/")}"
    }

    companion object {
        private const val TAG = "MediaGalleryRepository"
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm")
    }
}
