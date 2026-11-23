package com.example.camera.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    onOpenSystemGallery: () -> Unit,
    onMediaChanged: () -> Unit = {}
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
                    onClose = onDismiss,
                    onMediaChanged = onMediaChanged
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
private fun MediaGalleryItemViewer(
    item: MediaGalleryItem,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onMediaChanged: () -> Unit
) {
    if (item.isVideo) {
        MediaGalleryPlayer(video = item, onBack = onBack, onClose = onClose, onMediaChanged = onMediaChanged)
    } else {
        MediaGalleryPhotoViewer(photo = item, onBack = onBack, onClose = onClose, onMediaChanged = onMediaChanged)
    }
}

private data class GalleryPhotoLoadState(
    val isLoading: Boolean,
    val bitmap: Bitmap?,
    val expandedBitmap: Bitmap? = null
)

@Composable
private fun MediaGalleryPhotoViewer(
    photo: MediaGalleryItem,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onMediaChanged: () -> Unit
) {
    val context = LocalContext.current
    val actionScope = rememberCoroutineScope()
    var isExpanded by rememberSaveable(photo.uri.toString()) { mutableStateOf(false) }
    var isEditing by rememberSaveable(photo.uri.toString()) { mutableStateOf(false) }
    var isFavorite by remember(photo.uri.toString()) {
        mutableStateOf(GalleryMediaActions.isFavorite(context, photo.uri))
    }
    var addToExpanded by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    val dateParts = remember(photo.timestamp) { formatGalleryMediaDate(photo.timestamp) }

    val photoLoadState by produceState(
        initialValue = GalleryPhotoLoadState(isLoading = true, bitmap = null),
        key1 = "${photo.uri}|${photo.ultraWideUri}"
    ) {
        val decoded = try {
            withContext(Dispatchers.IO) {
                val main = decodeGalleryImage(context, photo.uri, 1800)
                val ultraWide = photo.ultraWideUri?.let { decodeGalleryImage(context, it, 1800) }
                Pair(main, ultraWide)
            }
        } catch (e: Exception) {
            Log.e(GALLERY_PHOTO_DECODE_TAG, "Photo load failed for ${photo.uri}", e)
            Pair(null, null)
        }
        val expanded = if (decoded.first != null && decoded.second != null) {
            try {
                withContext(Dispatchers.Default) {
                    mergeSpecialPhotoBitmaps(decoded.first!!, decoded.second!!)
                }
            } catch (e: Exception) {
                Log.e(GALLERY_PHOTO_DECODE_TAG, "Special Photo merge failed for ${photo.displayName}", e)
                null
            }
        } else null
        value = GalleryPhotoLoadState(
            isLoading = false,
            bitmap = decoded.first,
            expandedBitmap = expanded
        )
    }
    DisposableEffect(photoLoadState) {
        val ownedMain = photoLoadState.bitmap
        val ownedExpanded = photoLoadState.expandedBitmap
        onDispose {
            runCatching { ownedMain?.takeIf { !it.isRecycled }?.recycle() }
            runCatching { ownedExpanded?.takeIf { !it.isRecycled }?.recycle() }
        }
    }

    val mainPhotoBitmap = photoLoadState.bitmap
    val expandedPhotoBitmap = photoLoadState.expandedBitmap
    val selectedBitmap = if (isExpanded) expandedPhotoBitmap else mainPhotoBitmap
    if (isEditing && selectedBitmap != null) {
        GalleryPhotoEditorScreen(
            source = selectedBitmap,
            displayName = if (isExpanded) "${photo.displayName.substringBeforeLast('.')}_EXPANDED.jpg" else photo.displayName,
            onBack = { isEditing = false },
            onSaved = { savedUri ->
                isEditing = false
                Toast.makeText(context, "Edited copy saved", Toast.LENGTH_SHORT).show()
                onMediaChanged()
            }
        )
    } else {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back to media", tint = Color.White)
                }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(dateParts.first, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (dateParts.second.isNotBlank()) {
                        Text(dateParts.second, color = Color.White.copy(alpha = 0.86f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                IconButton(onClick = {
                    isFavorite = !isFavorite
                    GalleryMediaActions.setFavorite(context, photo.uri, isFavorite)
                    Toast.makeText(context, if (isFavorite) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                        tint = if (isFavorite) Color(0xFFFFD166) else Color.White
                    )
                }
                Box {
                    IconButton(onClick = { moreMenuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = Color.White)
                    }
                    DropdownMenu(expanded = moreMenuExpanded, onDismissRequest = { moreMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Open system gallery") },
                            onClick = {
                                moreMenuExpanded = false
                                runCatching {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(photo.uri, "image/*")
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Close viewer") },
                            onClick = { moreMenuExpanded = false; onClose() }
                        )
                    }
                }
            }

            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    photoLoadState.isLoading -> CircularProgressIndicator(color = Color.White)
                    photoLoadState.bitmap == null -> Text("Unable to load photo", color = Color.White.copy(alpha = 0.75f))
                    else -> Crossfade(
                        targetState = isExpanded && expandedPhotoBitmap != null,
                        animationSpec = tween(durationMillis = 480),
                        label = "special-photo-expand-transition"
                    ) { expanded ->
                        val displayBitmap = if (expanded) expandedPhotoBitmap else mainPhotoBitmap
                        if (displayBitmap != null) {
                            Image(
                                bitmap = displayBitmap.asImageBitmap(),
                                contentDescription = if (expanded) "Expanded stitched photo" else photo.displayName,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                        }
                    }
                }

                if (photo.ultraWideUri != null && expandedPhotoBitmap != null) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
                        color = Color(0xCC171B20),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.clickable { isExpanded = !isExpanded }.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Text(if (isExpanded) "Collapse" else "Expand", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (photo.ultraWideUri != null && expandedPhotoBitmap == null && !photoLoadState.isLoading) {
                    Text(
                        "Wide-frame stitch unavailable",
                        color = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GalleryViewerAction(Modifier.weight(1f), Icons.Filled.Share, "Share") {
                    actionScope.launch {
                        runCatching {
                            val expandedBitmap = expandedPhotoBitmap
                            val shareUri = if (isExpanded && expandedBitmap != null) {
                                withContext(Dispatchers.IO) {
                                    GalleryMediaActions.saveEditedPhoto(
                                        context,
                                        expandedBitmap,
                                        "${photo.displayName.substringBeforeLast('.')}_EXPANDED.jpg"
                                    )
                                }
                            } else photo.uri
                            if (shareUri == null) throw IllegalStateException("Could not prepare photo for sharing")
                            GalleryMediaActions.shareMedia(context, shareUri, false, photo.displayName)
                        }.onFailure {
                            Toast.makeText(context, "Unable to share this photo", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                GalleryViewerAction(Modifier.weight(1f), Icons.Filled.Edit, "Edit") {
                    if (selectedBitmap != null) isEditing = true
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    GalleryViewerAction(Modifier.fillMaxWidth(), Icons.Filled.Add, "Add to") {
                        addToExpanded = !addToExpanded
                    }
                    DropdownMenu(expanded = addToExpanded, onDismissRequest = { addToExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(if (isFavorite) "Remove from favorites" else "Add to favorites") },
                            onClick = {
                                isFavorite = !isFavorite
                                GalleryMediaActions.setFavorite(context, photo.uri, isFavorite)
                                addToExpanded = false
                                Toast.makeText(context, if (isFavorite) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
                            }
                        )
                        if (photo.ultraWideUri != null && photoLoadState.expandedBitmap != null) {
                            DropdownMenuItem(
                                text = { Text("Save expanded panorama") },
                                onClick = {
                                    addToExpanded = false
                                    actionScope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val expandedBitmap = expandedPhotoBitmap
                                                    ?: throw IllegalStateException("Expanded image is not ready")
                                                GalleryMediaActions.saveEditedPhoto(
                                                    context,
                                                    expandedBitmap,
                                                    "${photo.displayName.substringBeforeLast('.')}_EXPANDED.jpg"
                                                )
                                            }
                                        }
                                        Toast.makeText(
                                            context,
                                            if (result.getOrNull() != null) "Expanded photo saved" else "Could not save expanded photo",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        if (result.getOrNull() != null) onMediaChanged()
                                    }
                                }
                            )
                        }
                    }
                }
                GalleryViewerAction(Modifier.weight(1f), Icons.Filled.Delete, "Bin") {
                    val deleted = GalleryMediaActions.deleteMedia(context, photo.uri)
                    if (photo.ultraWideUri != null) GalleryMediaActions.deleteMedia(context, photo.ultraWideUri)
                    Toast.makeText(
                        context,
                        if (deleted) "Photo deleted" else "Could not delete photo",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (deleted) {
                        onMediaChanged()
                        onBack()
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryViewerAction(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private const val SPECIAL_PHOTO_ULTRAWIDE_EQUIVALENT_FOCAL_MM = 16f
private const val SPECIAL_PHOTO_MAIN_EQUIVALENT_FOCAL_MM = 23f

/**
 * Normalizes both decoded JPEGs to the same 4:3 sensor aspect (3:4 in portrait).
 * Some HALs expose only a 16:9 still output; this defensive crop prevents the expanded result
 * from inheriting a 9:16 canvas if a device ignores the preferred still stream aspect.
 */
private fun cropSpecialPhotoToAspect(source: Bitmap, targetAspect: Float): Bitmap {
    val sourceAspect = source.width.toFloat() / source.height.coerceAtLeast(1).toFloat()
    if (abs(sourceAspect - targetAspect) < 0.002f) return source

    return if (sourceAspect > targetAspect) {
        val cropWidth = (source.height * targetAspect).roundToInt().coerceIn(1, source.width)
        Bitmap.createBitmap(
            source,
            (source.width - cropWidth) / 2,
            0,
            cropWidth,
            source.height
        )
    } else {
        val cropHeight = (source.width / targetAspect).roundToInt().coerceIn(1, source.height)
        Bitmap.createBitmap(
            source,
            0,
            (source.height - cropHeight) / 2,
            source.width,
            cropHeight
        )
    }
}

/**
 * Builds a luminance-gradient image. Gradients are less sensitive than RGB values to different
 * exposure and white-balance responses from the two physical camera modules.
 */
private fun specialPhotoGradient(bitmap: Bitmap): FloatArray {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val luminance = FloatArray(pixels.size)
    for (index in pixels.indices) {
        val pixel = pixels[index]
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        luminance[index] = ((red * 77 + green * 150 + blue * 29) shr 8).toFloat()
    }

    val gradient = FloatArray(pixels.size)
    if (width < 3 || height < 3) return gradient
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val index = y * width + x
            val horizontal = abs(luminance[index + 1] - luminance[index - 1])
            val vertical = abs(luminance[index + width] - luminance[index - width])
            gradient[index] = horizontal + vertical
        }
    }
    return gradient
}

/**
 * Estimates the small global translation between the focal-ratio-scaled Main frame and the
 * Ultra-Wide frame. It searches only near the optical centre and falls back to centred alignment
 * when the shared overlap has too little matching detail.
 */
private fun estimateSpecialPhotoAlignment(
    main: Bitmap,
    ultraWide: Bitmap,
    overlayWidth: Int,
    overlayHeight: Int
): Pair<Float, Float> {
    var widePreview: Bitmap? = null
    var mainPreview: Bitmap? = null
    try {
        val previewScale = minOf(1f, 360f / maxOf(ultraWide.width, ultraWide.height).toFloat())
        val previewWidth = (ultraWide.width * previewScale).roundToInt().coerceAtLeast(3)
        val previewHeight = (ultraWide.height * previewScale).roundToInt().coerceAtLeast(3)
        val patchWidth = (overlayWidth * previewWidth.toFloat() / ultraWide.width).roundToInt().coerceAtLeast(3)
        val patchHeight = (overlayHeight * previewHeight.toFloat() / ultraWide.height).roundToInt().coerceAtLeast(3)
        if (patchWidth >= previewWidth || patchHeight >= previewHeight) return 0f to 0f

        widePreview = if (previewWidth == ultraWide.width && previewHeight == ultraWide.height) {
            ultraWide
        } else {
            Bitmap.createScaledBitmap(ultraWide, previewWidth, previewHeight, true)
        }
        mainPreview = Bitmap.createScaledBitmap(main, patchWidth, patchHeight, true)

        val wideGradient = specialPhotoGradient(widePreview)
        val mainGradient = specialPhotoGradient(mainPreview)
        val baseLeft = (previewWidth - patchWidth) / 2
        val baseTop = (previewHeight - patchHeight) / 2
        val maxShiftX = minOf(
            (previewWidth - patchWidth) / 2 - 2,
            (previewWidth * 0.045f).roundToInt()
        ).coerceAtLeast(0)
        val maxShiftY = minOf(
            (previewHeight - patchHeight) / 2 - 2,
            (previewHeight * 0.045f).roundToInt()
        ).coerceAtLeast(0)
        val insetX = (patchWidth * 0.18f).roundToInt().coerceIn(1, patchWidth / 3)
        val insetY = (patchHeight * 0.18f).roundToInt().coerceIn(1, patchHeight / 3)
        val sampleStep = maxOf(2, minOf(patchWidth, patchHeight) / 100)

        fun scoreOffset(dx: Int, dy: Int): Float {
            var sumMain = 0.0
            var sumWide = 0.0
            var sumMainSquared = 0.0
            var sumWideSquared = 0.0
            var sumProduct = 0.0
            var sampleCount = 0
            for (y in insetY until patchHeight - insetY step sampleStep) {
                val wideRow = (baseTop + dy + y) * previewWidth + baseLeft + dx
                val mainRow = y * patchWidth
                for (x in insetX until patchWidth - insetX step sampleStep) {
                    val mainValue = mainGradient[mainRow + x].toDouble()
                    val wideValue = wideGradient[wideRow + x].toDouble()
                    sumMain += mainValue
                    sumWide += wideValue
                    sumMainSquared += mainValue * mainValue
                    sumWideSquared += wideValue * wideValue
                    sumProduct += mainValue * wideValue
                    sampleCount++
                }
            }
            if (sampleCount < 16) return -1f
            val count = sampleCount.toDouble()
            val covariance = sumProduct - (sumMain * sumWide / count)
            val mainVariance = sumMainSquared - (sumMain * sumMain / count)
            val wideVariance = sumWideSquared - (sumWide * sumWide / count)
            if (mainVariance <= 1.0 || wideVariance <= 1.0) return -1f
            return (covariance / sqrt(mainVariance * wideVariance)).toFloat()
        }

        var bestX = 0
        var bestY = 0
        var bestScore = scoreOffset(0, 0)
        val coarseX = maxOf(1, maxShiftX / 5)
        val coarseY = maxOf(1, maxShiftY / 5)
        for (dy in -maxShiftY..maxShiftY step coarseY) {
            for (dx in -maxShiftX..maxShiftX step coarseX) {
                val score = scoreOffset(dx, dy)
                if (score > bestScore) {
                    bestScore = score
                    bestX = dx
                    bestY = dy
                }
            }
        }

        val refineMinX = (bestX - coarseX).coerceAtLeast(-maxShiftX)
        val refineMaxX = (bestX + coarseX).coerceAtMost(maxShiftX)
        val refineMinY = (bestY - coarseY).coerceAtLeast(-maxShiftY)
        val refineMaxY = (bestY + coarseY).coerceAtMost(maxShiftY)
        for (dy in refineMinY..refineMaxY) {
            for (dx in refineMinX..refineMaxX) {
                val score = scoreOffset(dx, dy)
                if (score > bestScore) {
                    bestScore = score
                    bestX = dx
                    bestY = dy
                }
            }
        }

        if (bestScore < 0.16f) return 0f to 0f
        return Pair(
            bestX * ultraWide.width.toFloat() / previewWidth,
            bestY * ultraWide.height.toFloat() / previewHeight
        )
    } catch (_: Throwable) {
        return 0f to 0f
    } finally {
        widePreview?.takeIf { it !== ultraWide && !it.isRecycled }?.recycle()
        mainPreview?.takeIf { it !== main && !it.isRecycled }?.recycle()
    }
}

/**
 * The 23mm Main frame remains centred and the 16mm Ultra-Wide frame contributes the wider field.
 * A 16/23 focal-length ratio determines the overlay size; image-gradient registration corrects
 * small module offsets before a feathered seam blends the two views.
 */
private fun mergeSpecialPhotoBitmaps(main: Bitmap, ultraWide: Bitmap): Bitmap {
    val targetAspect = if (main.width <= main.height) 3f / 4f else 4f / 3f
    val mainFrame = cropSpecialPhotoToAspect(main, targetAspect)
    val ultraWideFrame = cropSpecialPhotoToAspect(ultraWide, targetAspect)
    var fittedMain: Bitmap? = null

    try {
        val outputWidth = ultraWideFrame.width.coerceAtLeast(1)
        val outputHeight = ultraWideFrame.height.coerceAtLeast(1)
        val opticalScale = (
            SPECIAL_PHOTO_ULTRAWIDE_EQUIVALENT_FOCAL_MM /
                SPECIAL_PHOTO_MAIN_EQUIVALENT_FOCAL_MM
            ).coerceIn(0.5f, 0.9f)
        val overlayWidth = (outputWidth * opticalScale).roundToInt().coerceIn(1, outputWidth)
        val overlayHeight = (outputHeight * opticalScale).roundToInt().coerceIn(1, outputHeight)
        val alignment = estimateSpecialPhotoAlignment(
            mainFrame,
            ultraWideFrame,
            overlayWidth,
            overlayHeight
        )
        val left = (
            (outputWidth - overlayWidth) / 2f + alignment.first
        ).roundToInt().coerceIn(0, outputWidth - overlayWidth)
        val top = (
            (outputHeight - overlayHeight) / 2f + alignment.second
        ).roundToInt().coerceIn(0, outputHeight - overlayHeight)

        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val outputCanvas = Canvas(output)
        val filteringPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        outputCanvas.drawBitmap(ultraWideFrame, 0f, 0f, filteringPaint)

        fittedMain = Bitmap.createScaledBitmap(mainFrame, overlayWidth, overlayHeight, true)
        val pixels = IntArray(overlayWidth * overlayHeight)
        fittedMain.getPixels(pixels, 0, overlayWidth, 0, 0, overlayWidth, overlayHeight)
        val featherX = (overlayWidth * 0.025f).coerceAtLeast(1f)
        val featherY = (overlayHeight * 0.025f).coerceAtLeast(1f)

        for (y in 0 until overlayHeight) {
            val yInside = minOf(y + 0.5f, overlayHeight - y - 0.5f)
            val fadeY = (yInside / featherY).coerceIn(0f, 1f)
            for (x in 0 until overlayWidth) {
                val index = y * overlayWidth + x
                val existingAlpha = (pixels[index] ushr 24) and 0xFF
                if (existingAlpha == 0) continue

                val xInside = minOf(x + 0.5f, overlayWidth - x - 0.5f)
                val linearFade = minOf(1f, xInside / featherX, fadeY)
                val feather = linearFade * linearFade * (3f - 2f * linearFade)
                val alpha = (existingAlpha * feather).roundToInt().coerceIn(0, 255)
                pixels[index] = (alpha shl 24) or (pixels[index] and 0x00FFFFFF)
            }
        }
        fittedMain.setPixels(pixels, 0, overlayWidth, 0, 0, overlayWidth, overlayHeight)
        outputCanvas.drawBitmap(fittedMain, left.toFloat(), top.toFloat(), filteringPaint)
        return output
    } finally {
        fittedMain?.takeIf { it !== mainFrame && !it.isRecycled }?.recycle()
        mainFrame.takeIf { it !== main && !it.isRecycled }?.recycle()
        ultraWideFrame.takeIf { it !== ultraWide && !it.isRecycled }?.recycle()
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
    onClose: () -> Unit,
    onMediaChanged: () -> Unit
) {
    var isUltraWide by rememberSaveable(video.id) { mutableStateOf(false) }
    var playbackPosition by rememberSaveable(video.id) { mutableStateOf(0) }
    var shouldResumePlayback by rememberSaveable(video.id) { mutableStateOf(true) }
    val activeTextureView = remember(video.id) { mutableStateOf<TextureView?>(null) }
    val hasLensPair = video.ultraWideUri != null
    val context = LocalContext.current
    val switchScope = rememberCoroutineScope()
    var isFavorite by remember(video.uri.toString()) {
        mutableStateOf(GalleryMediaActions.isFavorite(context, video.uri))
    }
    var addToExpanded by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var showVideoExportDialog by rememberSaveable(video.id) { mutableStateOf(false) }
    val dateParts = remember(video.timestamp) { formatGalleryMediaDate(video.timestamp) }
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
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(dateParts.first, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (dateParts.second.isNotBlank()) {
                    Text(dateParts.second, color = Color.White.copy(alpha = 0.86f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            IconButton(onClick = {
                isFavorite = !isFavorite
                GalleryMediaActions.setFavorite(context, video.uri, isFavorite)
                Toast.makeText(context, if (isFavorite) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
            }) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) Color(0xFFFFD166) else Color.White
                )
            }
            Box {
                IconButton(onClick = { moreMenuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = Color.White)
                }
                DropdownMenu(expanded = moreMenuExpanded, onDismissRequest = { moreMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Export video copies") },
                        onClick = { moreMenuExpanded = false; showVideoExportDialog = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Close viewer") },
                        onClick = { moreMenuExpanded = false; onClose() }
                    )
                }
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 16.dp, top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GalleryViewerAction(Modifier.weight(1f), Icons.Filled.Share, "Share") {
                switchScope.launch {
                    val sourceUri = if (isUltraWide) video.ultraWideUri ?: video.uri else video.uri
                    runCatching {
                        GalleryMediaActions.shareMedia(context, sourceUri, true, video.displayName)
                    }.onFailure {
                        Toast.makeText(context, "Unable to share video", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            GalleryViewerAction(Modifier.weight(1f), Icons.Filled.Edit, "Edit") {
                showVideoExportDialog = true
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                GalleryViewerAction(Modifier.fillMaxWidth(), Icons.Filled.Add, "Add to") {
                    addToExpanded = !addToExpanded
                }
                DropdownMenu(expanded = addToExpanded, onDismissRequest = { addToExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isFavorite) "Remove from favorites" else "Add to favorites") },
                        onClick = {
                            isFavorite = !isFavorite
                            GalleryMediaActions.setFavorite(context, video.uri, isFavorite)
                            addToExpanded = false
                            Toast.makeText(context, if (isFavorite) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Export both camera files") },
                        onClick = { addToExpanded = false; showVideoExportDialog = true }
                    )
                }
            }
            GalleryViewerAction(Modifier.weight(1f), Icons.Filled.Delete, "Bin") {
                val deleted = GalleryMediaActions.deleteMedia(context, video.uri)
                if (video.ultraWideUri != null) GalleryMediaActions.deleteMedia(context, video.ultraWideUri)
                Toast.makeText(context, if (deleted) "Video deleted" else "Could not delete video", Toast.LENGTH_SHORT).show()
                if (deleted) {
                    onMediaChanged()
                    onBack()
                }
            }
        }
    }

    if (showVideoExportDialog) {
        GalleryVideoExportDialog(
            displayName = video.displayName,
            mainUri = video.uri,
            ultraWideUri = video.ultraWideUri,
            onDismiss = { showVideoExportDialog = false }
        )
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
