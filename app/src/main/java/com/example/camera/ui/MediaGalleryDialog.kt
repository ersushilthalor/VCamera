package com.example.camera.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.MediaController
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.camera.gallery.MediaGalleryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal const val GALLERY_LENS_SWITCH_DURATION_MS = 300

/**
 * Returns the lens handoff zoom applied inside the fixed video viewport.
 * Both axes use the same magnification, preserving each source frame's aspect ratio.
 */
internal fun calculateGalleryLensSwitchZoomScale(
    isUltraWidePlayer: Boolean,
    transitionFromUltraWide: Boolean,
    progress: Float,
    isTransitionRunning: Boolean
): Float {
    if (!isUltraWidePlayer || !isTransitionRunning) return 1f
    val normalizedProgress = progress.coerceIn(0f, 1f)
    return if (transitionFromUltraWide) 1f + normalizedProgress else 2f - normalizedProgress
}

private val galleryBackground = Color(0xFF0D1413)
private val galleryAccent = Color(0xFF71F0E5)

private data class GalleryMediaSection(
    val title: String,
    val videos: List<MediaGalleryItem>
)

private class GalleryPlayerState(
    var isPrepared: Boolean = false,
    var wasActive: Boolean = false,
    var mediaPlayer: MediaPlayer? = null,
    var outputSurface: Surface? = null,
    var mediaController: MediaController? = null,
    var videoWidth: Int = 0,
    var videoHeight: Int = 0
)

private class GalleryMediaPlayerControl(
    private val state: GalleryPlayerState
) : MediaController.MediaPlayerControl {
    override fun start() { if (state.isPrepared) runCatching { state.mediaPlayer?.start() } }
    override fun pause() { if (state.isPrepared) runCatching { state.mediaPlayer?.pause() } }
    override fun getDuration(): Int = runCatching { state.mediaPlayer?.duration ?: 0 }.getOrDefault(0)
    override fun getCurrentPosition(): Int = runCatching { state.mediaPlayer?.currentPosition ?: 0 }.getOrDefault(0)
    override fun isPlaying(): Boolean =
        state.isPrepared && runCatching { state.mediaPlayer?.isPlaying == true }.getOrDefault(false)
    override fun seekTo(position: Int) { if (state.isPrepared) runCatching { state.mediaPlayer?.seekTo(position) } }
    override fun getBufferPercentage(): Int = 0
    override fun canPause(): Boolean = true
    override fun canSeekBackward(): Boolean = true
    override fun canSeekForward(): Boolean = true
    override fun getAudioSessionId(): Int = runCatching { state.mediaPlayer?.audioSessionId ?: 0 }.getOrDefault(0)
}

/**
 * Fits the decoded frame into the fixed gallery viewport, then zooms uniformly around its
 * center. The player bounds never change and the source's aspect ratio is not distorted.
 */
private fun applyGalleryVideoZoom(view: TextureView, state: GalleryPlayerState, zoomScale: Float) {
    val width = view.width.toFloat()
    val height = view.height.toFloat()
    val videoWidth = state.videoWidth.toFloat()
    val videoHeight = state.videoHeight.toFloat()
    if (width <= 0f || height <= 0f || videoWidth <= 0f || videoHeight <= 0f) return

    val viewRect = RectF(0f, 0f, width, height)
    val videoRatio = videoWidth / videoHeight
    val viewRatio = width / height
    val fittedRect = if (videoRatio > viewRatio) {
        val fittedHeight = width / videoRatio
        RectF(0f, (height - fittedHeight) / 2f, width, (height + fittedHeight) / 2f)
    } else {
        val fittedWidth = height * videoRatio
        RectF((width - fittedWidth) / 2f, 0f, (width + fittedWidth) / 2f, height)
    }
    val matrix = Matrix()
    matrix.setRectToRect(viewRect, fittedRect, Matrix.ScaleToFit.FILL)
    val scale = zoomScale.coerceAtLeast(0.01f)
    matrix.postScale(scale, scale, width / 2f, height / 2f)
    view.setTransform(matrix)
}

@Composable
fun MediaGalleryDialog(
    videos: List<MediaGalleryItem>,
    hasMediaPermission: Boolean,
    onRequestMediaPermission: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSystemGallery: () -> Unit
) {
    var selectedMedia by remember { mutableStateOf<MediaGalleryItem?>(null) }

    BackHandler(enabled = selectedMedia != null) {
        selectedMedia = null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = galleryBackground,
            shape = RoundedCornerShape(0.dp)
        ) {
            val currentMedia = selectedMedia
            if (currentMedia == null) {
                MediaGridScreen(
                    videos = videos,
                    hasMediaPermission = hasMediaPermission,
                    onRequestMediaPermission = onRequestMediaPermission,
                    onMediaSelected = { selectedMedia = it },
                    onDismiss = onDismiss,
                    onOpenSystemGallery = onOpenSystemGallery
                )
            } else {
                MediaGalleryItemViewer(
                    item = currentMedia,
                    onBack = { selectedMedia = null },
                    onClose = onDismiss
                )
            }
        }
    }
}

@Composable
private fun MediaGridScreen(
    videos: List<MediaGalleryItem>,
    hasMediaPermission: Boolean,
    onRequestMediaPermission: () -> Unit,
    onMediaSelected: (MediaGalleryItem) -> Unit,
    onDismiss: () -> Unit,
    onOpenSystemGallery: () -> Unit
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var oldestFirst by rememberSaveable { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }

    val filteredVideos = videos
        .filter {
            searchQuery.isBlank() ||
                it.displayName.contains(searchQuery, ignoreCase = true) ||
                (it.isVCameraRecording && "VCamera".contains(searchQuery, ignoreCase = true))
        }
        .let { list -> if (oldestFirst) list.sortedBy { it.timestamp } else list.sortedByDescending { it.timestamp } }

    val today = LocalDate.now()
    val sections = filteredVideos
        .groupBy { video ->
            runCatching {
                Instant.ofEpochMilli(video.timestamp.coerceAtLeast(0L))
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            }.getOrDefault(today)
        }
        .entries
        .let { entries -> if (oldestFirst) entries.sortedBy { it.key } else entries.sortedByDescending { it.key } }
        .map { (date, dayVideos) ->
            val title = when (date) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> date.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()))
            }
            GalleryMediaSection(title, dayVideos)
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(galleryBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 20.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Media",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                searchOpen = !searchOpen
                if (!searchOpen) searchQuery = ""
            }) {
                Icon(Icons.Filled.Search, contentDescription = "Search videos", tint = Color.White)
            }
            Box {
                IconButton(onClick = { settingsExpanded = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Gallery settings", tint = Color.White)
                }
                DropdownMenu(
                    expanded = settingsExpanded,
                    onDismissRequest = { settingsExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (oldestFirst) "Sort: newest first" else "Sort: oldest first") },
                        onClick = {
                            oldestFirst = !oldestFirst
                            settingsExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Open system gallery") },
                        onClick = {
                            settingsExpanded = false
                            onOpenSystemGallery()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Close media") },
                        onClick = {
                            settingsExpanded = false
                            onDismiss()
                        }
                    )
                }
            }
        }

        if (searchOpen) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = { Text("Search VCamera media") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1B2624),
                    unfocusedContainerColor = Color(0xFF1B2624),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = galleryAccent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        }

        if (!hasMediaPermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = galleryAccent,
                    modifier = Modifier.size(52.dp)
                )
                Text(
                    "Allow access to photos and videos",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "VCamera uses media access to show photos and videos saved by this app.",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp, bottom = 18.dp)
                )
                androidx.compose.material3.Button(onClick = onRequestMediaPermission) {
                    Text("Grant media access")
                }
            }
        } else if (sections.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (videos.isEmpty()) "No VCamera photos or videos found yet." else "No VCamera media matches this search.",
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 10.dp,
                    bottom = 18.dp
                ),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                sections.forEachIndexed { sectionIndex, section ->
                    item(key = "section-${sectionIndex}") {
                        Text(
                            text = section.title,
                            color = galleryAccent,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 8.dp)
                        )
                    }
                    items(
                        items = section.videos.chunked(3),
                        key = { row -> "row-${row.first().id}" }
                    ) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            row.forEach { video ->
                                GalleryMediaTile(
                                    video = video,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onMediaSelected(video) }
                                )
                            }
                            repeat(3 - row.size) {
                                Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryMediaTile(
    video: MediaGalleryItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = video.uri) {
        value = withContext(Dispatchers.IO) { loadGalleryThumbnail(context, video) }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0xFF202A28))
            .clickable(onClick = onClick)
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!.asImageBitmap(),
                contentDescription = video.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (video.isVideo) "VIDEO" else "PHOTO", color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
            }
        }

        if (video.isVCameraRecording) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(5.dp),
                color = Color(0xD9008F88),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    "VCAM",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
                )
            }
        }

        if (video.isVideo) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
                color = Color(0xC9000000),
                shape = RoundedCornerShape(7.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Text(formatGalleryDuration(video.durationMs), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}


@Composable
private fun MediaGalleryItemViewer(item: MediaGalleryItem, onBack: () -> Unit, onClose: () -> Unit) {
    if (item.isVideo) MediaGalleryPlayer(video = item, onBack = onBack, onClose = onClose)
    else MediaGalleryPhotoViewer(photo = item, onBack = onBack, onClose = onClose)
}

private data class GalleryPhotoLoadState(
    val isLoading: Boolean,
    val bitmap: Bitmap?
)

@Composable
private fun MediaGalleryPhotoViewer(photo: MediaGalleryItem, onBack: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val photoLoadState by produceState(
        initialValue = GalleryPhotoLoadState(isLoading = true, bitmap = null),
        key1 = photo.uri
    ) {
        val decodedBitmap = try {
            withContext(Dispatchers.IO) {
                decodeGalleryImage(context, photo.uri, 2200)
            }
        } catch (e: Exception) {
            Log.e(GALLERY_PHOTO_DECODE_TAG, "Photo load failed for ${photo.uri}", e)
            null
        }
        value = GalleryPhotoLoadState(isLoading = false, bitmap = decodedBitmap)
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to media", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("Photo", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(photo.displayName, color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close photo", tint = Color.White)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                photoLoadState.isLoading -> CircularProgressIndicator(color = Color.White)
                photoLoadState.bitmap != null -> Image(
                    bitmap = photoLoadState.bitmap.asImageBitmap(),
                    contentDescription = photo.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
                else -> Text("Unable to load photo", color = Color.White.copy(alpha = 0.75f))
            }
        }
    }
}

private fun loadGalleryThumbnail(context: Context, item: MediaGalleryItem): Bitmap? {
    return try {
        if (item.isVideo && item.uri.scheme == "file") {
            val filePath = item.uri.path ?: return null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(filePath)
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally { retriever.release() }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(item.uri, Size(360, 360), null)
        } else if (item.isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, item.uri)
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally { retriever.release() }
        } else decodeGalleryImage(context, item.uri, 360)
    } catch (_: Exception) { null }
}

private const val GALLERY_PHOTO_DECODE_TAG = "GalleryPhotoDecoder"

/**
 * Decodes MediaStore and file-backed photos at a bounded size. ImageDecoder is preferred on
 * Android 9+ because it supports newer still-image formats and applies EXIF orientation.
 * BitmapFactory plus a file-descriptor fallback keeps older Android versions supported.
 */
private fun decodeGalleryImage(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
    val targetLimit = maxDimension.coerceAtLeast(1)
    val resolver = context.contentResolver

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
            val source = if (uri.scheme == "file") {
                val path = uri.path ?: return null
                ImageDecoder.createSource(File(path))
            } else {
                ImageDecoder.createSource(resolver, uri)
            }
            return ImageDecoder.decodeBitmap(source) { decoder, imageInfo, _ ->
                val sourceWidth = imageInfo.size.width.coerceAtLeast(1)
                val sourceHeight = imageInfo.size.height.coerceAtLeast(1)
                val scale = minOf(
                    1f,
                    targetLimit.toFloat() / maxOf(sourceWidth, sourceHeight).toFloat()
                )
                decoder.setTargetSize(
                    (sourceWidth * scale).roundToInt().coerceAtLeast(1),
                    (sourceHeight * scale).roundToInt().coerceAtLeast(1)
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } catch (e: Exception) {
            Log.w(GALLERY_PHOTO_DECODE_TAG, "ImageDecoder could not decode $uri; trying BitmapFactory", e)
        }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decodeGalleryImageWithBitmapFactory(context, uri, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        Log.e(
            GALLERY_PHOTO_DECODE_TAG,
            "Unsupported, empty, or inaccessible photo: uri=$uri, mime=${runCatching { resolver.getType(uri) }.getOrNull()}"
        )
        return null
    }

    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > targetLimit) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inDither = false
    }
    return decodeGalleryImageWithBitmapFactory(context, uri, options).also { bitmap ->
        if (bitmap == null) {
            Log.e(
                GALLERY_PHOTO_DECODE_TAG,
                "BitmapFactory could not decode photo: uri=$uri, mime=${runCatching { resolver.getType(uri) }.getOrNull()}"
            )
        }
    }
}

/** Uses a seekable descriptor when a provider does not supply a usable InputStream. */
private fun decodeGalleryImageWithBitmapFactory(
    context: Context,
    uri: Uri,
    options: BitmapFactory.Options
): Bitmap? {
    if (uri.scheme == "file") {
        val path = uri.path ?: return null
        return BitmapFactory.decodeFile(path, options)
    }

    val resolver = context.contentResolver
    try {
        val bitmap = resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
        if (bitmap != null || (options.inJustDecodeBounds && options.outWidth > 0 && options.outHeight > 0)) {
            return bitmap
        }
    } catch (e: Exception) {
        Log.d(GALLERY_PHOTO_DECODE_TAG, "InputStream decode failed for $uri; trying file descriptor", e)
    }

    return try {
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, options)
        }
    } catch (e: Exception) {
        Log.e(GALLERY_PHOTO_DECODE_TAG, "File-descriptor decode failed for $uri", e)
        null
    }
}

@Composable
private fun MediaGalleryPlayer(
    video: MediaGalleryItem,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    var isUltraWide by rememberSaveable(video.id) { mutableStateOf(false) }
    var playbackPosition by rememberSaveable(video.id) { mutableStateOf(0) }
    var shouldResumePlayback by rememberSaveable(video.id) { mutableStateOf(true) }
    val activeTextureView = remember(video.id) { mutableStateOf<TextureView?>(null) }
    val hasLensPair = video.ultraWideUri != null
    val switchScope = rememberCoroutineScope()
    val zoomProgress = remember(video.id) { Animatable(1f) }
    val currentZoomProgress = zoomProgress.value
    var transitionFromUltraWide by rememberSaveable(video.id) { mutableStateOf(false) }
    var isLensTransitionRunning by rememberSaveable(video.id) { mutableStateOf(false) }
    var isLensSwitchLocked by remember(video.id) { mutableStateOf(false) }

    val selectLens: (Boolean) -> Unit = { useUltraWide ->
        if (hasLensPair && isUltraWide != useUltraWide && !isLensSwitchLocked) {
            isLensSwitchLocked = true
            val sourceWasUltraWide = isUltraWide
            activeTextureView.value?.let { view ->
                val state = view.tag as? GalleryPlayerState
                val player = state?.mediaPlayer
                if (state?.isPrepared == true && player != null) {
                    playbackPosition = runCatching { player.currentPosition }.getOrDefault(0).coerceAtLeast(0)
                    shouldResumePlayback = runCatching { player.isPlaying }.getOrDefault(false)
                    runCatching { player.pause() }
                }
                state?.wasActive = false
            }

            switchScope.launch {
                // Start the zoom animation from the matching field of view before switching sources.
                zoomProgress.snapTo(0f)
                transitionFromUltraWide = sourceWasUltraWide
                isLensTransitionRunning = true
                isUltraWide = useUltraWide
                zoomProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = GALLERY_LENS_SWITCH_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                )
                isLensTransitionRunning = false
                isLensSwitchLocked = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to media", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Video", color = Color.White, style = MaterialTheme.typography.titleLarge)
                val subtitle = when {
                    hasLensPair && isUltraWide -> "Ultra-wide camera"
                    hasLensPair -> "Main camera"
                    !video.isVideo -> "VCamera photo"
                    else -> "VCamera recording"
                }
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close player", tint = Color.White)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
        ) {
            Crossfade(
                targetState = isUltraWide,
                animationSpec = tween(durationMillis = GALLERY_LENS_SWITCH_DURATION_MS),
                modifier = Modifier.fillMaxSize(),
                label = "gallery-video-lens-switch"
            ) { targetIsUltraWide ->
                val targetUri = if (targetIsUltraWide) video.ultraWideUri ?: video.uri else video.uri
                val zoomScale = calculateGalleryLensSwitchZoomScale(
                    isUltraWidePlayer = targetIsUltraWide,
                    transitionFromUltraWide = transitionFromUltraWide,
                    progress = currentZoomProgress,
                    isTransitionRunning = isLensTransitionRunning
                )

                val restorePlayback: (TextureView, GalleryPlayerState) -> Unit = { view, state ->
                    val player = state.mediaPlayer
                    if (state.isPrepared && player != null) {
                        val lastSafePosition = (player.duration - 250).coerceAtLeast(0)
                        val targetPosition = playbackPosition.coerceAtMost(lastSafePosition)
                        val resumeAfterSeek = shouldResumePlayback
                        if (targetPosition > 0) {
                            player.setOnSeekCompleteListener { seekedPlayer ->
                                seekedPlayer.setOnSeekCompleteListener(null)
                                if (targetIsUltraWide == isUltraWide && state.wasActive) {
                                    playbackPosition = seekedPlayer.currentPosition.coerceAtLeast(0)
                                    activeTextureView.value = view
                                    if (resumeAfterSeek) seekedPlayer.start() else seekedPlayer.pause()
                                } else {
                                    seekedPlayer.pause()
                                }
                            }
                            player.seekTo(targetPosition)
                        } else if (targetIsUltraWide == isUltraWide && state.wasActive) {
                            activeTextureView.value = view
                            if (resumeAfterSeek) player.start() else player.pause()
                        } else {
                            player.pause()
                        }
                    }
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    factory = { viewContext ->
                        TextureView(viewContext).apply {
                            val viewState = GalleryPlayerState(wasActive = targetIsUltraWide == isUltraWide)
                            tag = viewState
                            isOpaque = false
                            if (viewState.wasActive) activeTextureView.value = this

                            val controller = MediaController(viewContext)
                            viewState.mediaController = controller
                            controller.setAnchorView(this)
                            controller.setMediaPlayer(GalleryMediaPlayerControl(viewState))
                            controller.isEnabled = false
                            setOnClickListener {
                                if (controller.isShowing) controller.hide() else controller.show(3_000)
                            }

                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    surfaceTexture: SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    val outputSurface = Surface(surfaceTexture)
                                    viewState.outputSurface = outputSurface
                                    val player = MediaPlayer()
                                    viewState.mediaPlayer = player
                                    try {
                                        player.setSurface(outputSurface)
                                        player.setOnVideoSizeChangedListener { _, videoWidth, videoHeight ->
                                            viewState.videoWidth = videoWidth
                                            viewState.videoHeight = videoHeight
                                            applyGalleryVideoZoom(this@apply, viewState, zoomScale)
                                        }
                                        player.setOnPreparedListener { preparedPlayer ->
                                            preparedPlayer.isLooping = false
                                            viewState.isPrepared = true
                                            viewState.videoWidth = preparedPlayer.videoWidth
                                            viewState.videoHeight = preparedPlayer.videoHeight
                                            applyGalleryVideoZoom(this@apply, viewState, zoomScale)
                                            controller.isEnabled = true

                                            if (targetIsUltraWide == isUltraWide) {
                                                viewState.wasActive = true
                                                activeTextureView.value = this@apply
                                                restorePlayback(this@apply, viewState)
                                            } else {
                                                viewState.wasActive = false
                                                preparedPlayer.pause()
                                            }
                                        }
                                        player.setOnErrorListener { _, _, _ ->
                                            viewState.isPrepared = false
                                            true
                                        }
                                        player.setDataSource(viewContext, targetUri)
                                        player.prepareAsync()
                                    } catch (_: Exception) {
                                        viewState.isPrepared = false
                                        viewState.mediaPlayer = null
                                        runCatching { player.release() }
                                    }
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    surfaceTexture: SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    applyGalleryVideoZoom(this@apply, viewState, zoomScale)
                                }

                                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                    viewState.mediaPlayer?.let { player ->
                                        runCatching { player.setOnSeekCompleteListener(null) }
                                        runCatching { player.setOnPreparedListener(null) }
                                        runCatching { player.setOnVideoSizeChangedListener(null) }
                                        runCatching { player.setSurface(null) }
                                        runCatching { player.release() }
                                    }
                                    viewState.mediaPlayer = null
                                    viewState.isPrepared = false
                                    viewState.outputSurface?.release()
                                    viewState.outputSurface = null
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                            }
                        }
                    },
                    update = { view ->
                        val viewState = view.tag as? GalleryPlayerState
                        if (viewState != null) {
                            applyGalleryVideoZoom(view, viewState, zoomScale)
                            val shouldBeActive = targetIsUltraWide == isUltraWide
                            if (shouldBeActive && !viewState.wasActive) {
                                viewState.wasActive = true
                                activeTextureView.value = view
                                if (viewState.isPrepared) restorePlayback(view, viewState)
                            } else if (shouldBeActive) {
                                activeTextureView.value = view
                            } else {
                                viewState.wasActive = false
                            }
                        }
                    },
                    onRelease = { view ->
                        if (activeTextureView.value === view) activeTextureView.value = null
                        view.surfaceTextureListener = null
                        view.setOnClickListener(null)
                        (view.tag as? GalleryPlayerState)?.let { viewState ->
                            viewState.mediaController?.hide()
                            viewState.mediaPlayer?.let { player ->
                                runCatching { player.setOnSeekCompleteListener(null) }
                                runCatching { player.setOnPreparedListener(null) }
                                runCatching { player.setOnVideoSizeChangedListener(null) }
                                runCatching { player.setSurface(null) }
                                runCatching { player.release() }
                            }
                            viewState.mediaPlayer = null
                            viewState.outputSurface?.release()
                            viewState.outputSurface = null
                            viewState.mediaController = null
                            viewState.isPrepared = false
                        }
                    }
                )
            }

            // Keep the lens selector above the playback toolbar, not at the bottom edge.
            if (hasLensPair) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 88.dp),
                    color = Color(0xE820242B),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LensSwitchButton(label = "0.5x", isSelected = isUltraWide) { selectLens(true) }
                        LensSwitchButton(label = "1x", isSelected = !isUltraWide) { selectLens(false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LensSwitchButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .height(34.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (isSelected) Color(0xFF2D65B8) else Color.Transparent,
        contentColor = Color.White
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = label, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun formatGalleryDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
