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

data class MediaGalleryItem(
    val id: String,
    val displayName: String,
    val uri: Uri,
    val timestamp: Long,
    val durationMs: Long,
    val isVCameraRecording: Boolean,
    val isVideo: Boolean,
    val ultraWideUri: Uri? = null
)

/** Returns one date-sortable feed containing all accessible device media and VCamera private clips. */
class GalleryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun getMedia(specialClips: List<SpecialVideoClip>): List<MediaGalleryItem> {
        val media = ArrayList<MediaGalleryItem>()
        media += getVideos(specialClips)
        media += getPhotos()
        val knownUris = media.mapTo(HashSet()) { it.uri.toString() }
        getAppSpecificVideos().forEach { if (knownUris.add(it.uri.toString())) media += it }
        return media.distinctBy { it.uri.toString() }.sortedByDescending { it.timestamp }
    }

    private fun getVideos(specialClips: List<SpecialVideoClip>): List<MediaGalleryItem> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.DATE_TAKEN)
            add(MediaStore.Video.Media.DATE_ADDED)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.DESCRIPTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Video.Media.OWNER_PACKAGE_NAME)
            else add(MediaStore.Video.Media.DATA)
        }.toTypedArray()

        val specialByMainIdentity = specialClips.mapNotNull { clip ->
            mediaStoreIdentity(clip.mainUri)?.let { it to clip }
        }.toMap()
        val pairedUltraWideIdentities = specialClips.mapNotNull { mediaStoreIdentity(it.ultraWideUri) }.toSet()
        val specialByMainUri = specialClips.associateBy { it.mainUri.toString() }
        val pairedUltraWideUris = specialClips.map { it.ultraWideUri.toString() }.toSet()
        val videos = ArrayList<MediaGalleryItem>()

        try {
            resolver.query(collection, projection, null, null,
                "${MediaStore.Video.Media.DATE_TAKEN} DESC, ${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val takenCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                val addedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val descCol = cursor.getColumnIndex(MediaStore.Video.Media.DESCRIPTION)
                val ownerCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    cursor.getColumnIndex(MediaStore.Video.Media.OWNER_PACKAGE_NAME) else -1

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, mediaId)
                    val identity = mediaStoreIdentity(uri)
                    if ((identity != null && identity in pairedUltraWideIdentities) ||
                        uri.toString() in pairedUltraWideUris) continue

                    val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    if (name.endsWith("_ULTRAWIDE.mp4", ignoreCase = true)) continue
                    val description = if (descCol >= 0) cursor.getString(descCol).orEmpty() else ""
                    val owner = if (ownerCol >= 0) cursor.getString(ownerCol) else null
                    val specialClip = identity?.let { specialByMainIdentity[it] } ?: specialByMainUri[uri.toString()]
                    val isVCamera = owner == appContext.packageName ||
                        name.startsWith("VCAM_", true) ||
                        description.contains("VCamera", true) || specialClip != null
                    val dateTaken = if (takenCol >= 0) cursor.getLong(takenCol) else 0L
                    val dateAdded = if (addedCol >= 0) cursor.getLong(addedCol) else 0L
                    videos += MediaGalleryItem(
                        id = mediaId.toString(),
                        displayName = name.ifBlank { "Video" },
                        uri = uri,
                        timestamp = if (dateTaken > 0L) dateTaken else dateAdded * 1000L,
                        durationMs = if (durationCol >= 0) cursor.getLong(durationCol).coerceAtLeast(0L) else 0L,
                        isVCameraRecording = isVCamera,
                        isVideo = true,
                        ultraWideUri = specialClip?.ultraWideUri
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Video library access has not been granted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to query VCamera videos", e)
        }
        return videos
    }

    private fun getPhotos(): List<MediaGalleryItem> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.DESCRIPTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Images.Media.OWNER_PACKAGE_NAME)
            else add(MediaStore.Images.Media.DATA)
        }.toTypedArray()

        data class PhotoRow(val id: String, val name: String, val uri: Uri, val timestamp: Long, val isVCamera: Boolean)
        val rows = ArrayList<PhotoRow>()

        try {
            resolver.query(collection, projection, null, null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val descCol = cursor.getColumnIndex(MediaStore.Images.Media.DESCRIPTION)
                val ownerCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    cursor.getColumnIndex(MediaStore.Images.Media.OWNER_PACKAGE_NAME) else -1

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, mediaId)
                    val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val description = if (descCol >= 0) cursor.getString(descCol).orEmpty() else ""
                    val owner = if (ownerCol >= 0) cursor.getString(ownerCol) else null
                    val isVCamera = owner == appContext.packageName ||
                        name.startsWith("VCAM_", true) || description.contains("VCamera", true)

                    val dateTaken = if (takenCol >= 0) cursor.getLong(takenCol) else 0L
                    val dateAdded = if (addedCol >= 0) cursor.getLong(addedCol) else 0L
                    rows += PhotoRow(
                        id = mediaId.toString(),
                        name = name.ifBlank { "Photo" },
                        uri = uri,
                        timestamp = if (dateTaken > 0L) dateTaken else dateAdded * 1000L,
                        isVCamera = isVCamera
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Photo library access has not been granted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to query VCamera photos", e)
        }

        val byName = rows.associateBy { it.name.lowercase(Locale.ROOT) }
        return rows
            // The companion is reachable from its 1× image; never show it as a second tile.
            .filterNot { it.isVCamera && it.name.endsWith("_ULTRAWIDE.jpg", ignoreCase = true) }
            .map { row ->
                val ultraWideName = if (row.isVCamera && row.name.endsWith("_MAIN.jpg", ignoreCase = true)) {
                    row.name.dropLast("_MAIN.jpg".length) + "_ULTRAWIDE.jpg"
                } else null
                val ultraWideUri = ultraWideName?.let { byName[it.lowercase(Locale.ROOT)]?.uri }
                MediaGalleryItem(
                    id = row.id,
                    displayName = row.name.ifBlank { "Photo" },
                    uri = row.uri,
                    timestamp = row.timestamp,
                    durationMs = 0L,
                    isVCameraRecording = row.isVCamera,
                    isVideo = false,
                    ultraWideUri = ultraWideUri
                )
            }
    }

    private fun getAppSpecificVideos(): List<MediaGalleryItem> {
        val directories = buildList {
            try { addAll(appContext.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).filterNotNull()) }
            catch (e: Exception) { Log.w(TAG, "Unable to inspect app Movies directories", e) }
            add(File(appContext.filesDir, "Movies"))
        }.distinctBy { it.absolutePath }
        val result = LinkedHashMap<String, MediaGalleryItem>()
        directories.forEach { directory ->
            val files = try { directory.listFiles() ?: emptyArray() }
            catch (e: Exception) { Log.w(TAG, "Unable to list app video directory: $directory", e); emptyArray() }
            files.forEach { file ->
                if (file.isFile && file.length() > 0L && file.extension.lowercase(Locale.ROOT) in VIDEO_EXTENSIONS) {
                    val uri = Uri.fromFile(file)
                    result[file.absolutePath] = MediaGalleryItem(
                        id = "file:" + file.absolutePath,
                        displayName = file.name,
                        uri = uri,
                        timestamp = file.lastModified(),
                        durationMs = readVideoDuration(file),
                        isVCameraRecording = true,
                        isVideo = true
                    )
                }
            }
        }
        return result.values.toList()
    }

    private fun readVideoDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read duration for ${file.name}", e)
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
        private const val TAG = "GalleryRepository"
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm")
    }
}
