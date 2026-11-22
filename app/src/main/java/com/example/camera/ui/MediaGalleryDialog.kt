package com.example.camera.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.util.Size
import android.widget.MediaController
import android.widget.VideoView
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
import com.example.camera.gallery.MediaGalleryVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val GALLERY_LENS_SWITCH_DURATION_MS = 300

/**
 * Calculates the actual crop applied to the ultra-wide player while the two gallery sources
 * are handed off. A nominal 0.5x ultra-wide view needs a 2x crop to match the main 1x view.
 *
 * Main -> ultra-wide: crop 2x down to 1x (zoom out).
 * Ultra-wide -> main: crop 1x up to 2x (zoom in), then hand off to the 1x main source.
 */
internal fun calculateGalleryLensSwitchCropScale(
    isUltraWidePlayer: Boolean,
    transitionFromUltraWide: Boolean,
    progress: Float,
    isTransitionRunning: Boolean
): Float {
    if (!isUltraWidePlayer || !isTransitionRunning) return 1f
    val normalizedProgress = progress.coerceIn(0f, 1f)
    return if (transitionFromUltraWide) {
        1f + normalizedProgress
    } else {
        2f - normalizedProgress
    }
}

private val galleryBackground = Color(0xFF0D1413)
private val galleryAccent = Color(0xFF71F0E5)

private data class GalleryVideoSection(
    val title: String,
    val videos: List<MediaGalleryVideo>
)

private class GalleryPlayerState(
    var isPrepared: Boolean = false,
    var wasActive: Boolean = false,
    var mediaPlayer: MediaPlayer? = null
)

@Composable
fun MediaGalleryDialog(
    videos: List<MediaGalleryVideo>,
    hasVideoPermission: Boolean,
    onRequestVideoPermission: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSystemGallery: () -> Unit
) {
    var selectedVideo by remember { mutableStateOf<MediaGalleryVideo?>(null) }

    BackHandler(enabled = selectedVideo != null) {
        selectedVideo = null
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
            val currentVideo = selectedVideo
            if (currentVideo == null) {
                MediaGridScreen(
                    videos = videos,
                    hasVideoPermission = hasVideoPermission,
                    onRequestVideoPermission = onRequestVideoPermission,
                    onVideoSelected = { selectedVideo = it },
                    onDismiss = onDismiss,
                    onOpenSystemGallery = onOpenSystemGallery
                )
            } else {
                MediaGalleryPlayer(
                    video = currentVideo,
                    onBack = { selectedVideo = null },
                    onClose = onDismiss
                )
            }
        }
    }
}

@Composable
private fun MediaGridScreen(
    videos: List<MediaGalleryVideo>,
    hasVideoPermission: Boolean,
    onRequestVideoPermission: () -> Unit,
    onVideoSelected: (MediaGalleryVideo) -> Unit,
    onDismiss: () -> Unit,
    onOpenSystemGallery: () -> Unit
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var onlyVCamera by rememberSaveable { mutableStateOf(false) }
    var oldestFirst by rememberSaveable { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }

    val filteredVideos = videos
        .filter { !onlyVCamera || it.isVCameraRecording }
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
            GalleryVideoSection(title, dayVideos)
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
            IconButton(onClick = { onlyVCamera = !onlyVCamera }) {
                Icon(
                    Icons.Filled.FilterList,
                    contentDescription = if (onlyVCamera) "Show all videos" else "Show VCamera videos",
                    tint = if (onlyVCamera) galleryAccent else Color.White
                )
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
                placeholder = { Text("Search videos") },
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

        if (!hasVideoPermission) {
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
                    "Allow access to videos",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "VCamera uses video access to show recordings already saved in DCIM/Camera, including older clips.",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp, bottom = 18.dp)
                )
                androidx.compose.material3.Button(onClick = onRequestVideoPermission) {
                    Text("Grant video access")
                }
            }
        } else if (sections.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (videos.isEmpty()) "No videos found in DCIM/Camera yet." else "No videos match this search or filter.",
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
                                GalleryVideoTile(
                                    video = video,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onVideoSelected(video) }
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
private fun GalleryVideoTile(
    video: MediaGalleryVideo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = video.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                if (video.uri.scheme == "file") {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(video.uri.path ?: return@withContext null)
                        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally {
                        retriever.release()
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(video.uri, Size(360, 360), null)
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, video.uri)
                        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally {
                        retriever.release()
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
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
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
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
                Text(
                    formatGalleryDuration(video.durationMs),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun MediaGalleryPlayer(
    video: MediaGalleryVideo,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    var isUltraWide by rememberSaveable(video.id) { mutableStateOf(false) }
    var playbackPosition by rememberSaveable(video.id) { mutableStateOf(0) }
    var shouldResumePlayback by rememberSaveable(video.id) { mutableStateOf(true) }
    val activeVideoView = remember(video.id) { mutableStateOf<VideoView?>(null) }
    val hasLensPair = video.ultraWideUri != null
    val switchScope = rememberCoroutineScope()
    val cropProgress = remember(video.id) { Animatable(1f) }
    val currentCropProgress = cropProgress.value
    var transitionFromUltraWide by rememberSaveable(video.id) { mutableStateOf(false) }
    var isLensTransitionRunning by rememberSaveable(video.id) { mutableStateOf(false) }
    var isLensSwitchLocked by remember(video.id) { mutableStateOf(false) }

    val selectLens: (Boolean) -> Unit = { useUltraWide ->
        if (hasLensPair && isUltraWide != useUltraWide && !isLensSwitchLocked) {
            isLensSwitchLocked = true
            val sourceWasUltraWide = isUltraWide
            activeVideoView.value?.let { view ->
                val state = view.tag as? GalleryPlayerState
                if (state?.isPrepared == true) {
                    playbackPosition = view.currentPosition.coerceAtLeast(0)
                    shouldResumePlayback = view.isPlaying
                    view.pause()
                }
                state?.wasActive = false
            }

            switchScope.launch {
                // Reset the crop before changing the target player so the first visible frame
                // starts at the matching field of view instead of flashing at the final scale.
                cropProgress.snapTo(0f)
                transitionFromUltraWide = sourceWasUltraWide
                isLensTransitionRunning = true
                isUltraWide = useUltraWide
                cropProgress.animateTo(
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
                    video.isVCameraRecording -> "VCamera recording"
                    else -> "Camera video"
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
                val cropScale = calculateGalleryLensSwitchCropScale(
                    isUltraWidePlayer = targetIsUltraWide,
                    transitionFromUltraWide = transitionFromUltraWide,
                    progress = currentCropProgress,
                    isTransitionRunning = isLensTransitionRunning
                )

                val restorePlayback: (VideoView, GalleryPlayerState) -> Unit = { view, state ->
                    val lastSafePosition = (view.duration - 250).coerceAtLeast(0)
                    val targetPosition = playbackPosition.coerceAtMost(lastSafePosition)
                    val resumeAfterSeek = shouldResumePlayback
                    val player = state.mediaPlayer
                    if (targetPosition > 0 && player != null) {
                        player.setOnSeekCompleteListener { seekedPlayer ->
                            seekedPlayer.setOnSeekCompleteListener(null)
                            if (targetIsUltraWide == isUltraWide && state.wasActive) {
                                playbackPosition = seekedPlayer.currentPosition.coerceAtLeast(0)
                                activeVideoView.value = view
                                if (resumeAfterSeek) view.start() else view.pause()
                            } else {
                                view.pause()
                            }
                        }
                        player.seekTo(targetPosition)
                    } else if (targetIsUltraWide == isUltraWide && state.wasActive) {
                        activeVideoView.value = view
                        if (resumeAfterSeek) view.start() else view.pause()
                    } else {
                        view.pause()
                    }
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    factory = { viewContext ->
                        VideoView(viewContext).apply {
                            val state = GalleryPlayerState(
                                wasActive = targetIsUltraWide == isUltraWide
                            )
                            tag = state
                            if (state.wasActive) activeVideoView.value = this

                            val controller = MediaController(viewContext)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            setOnPreparedListener { player ->
                                player.isLooping = false
                                state.mediaPlayer = player
                                state.isPrepared = true
                                if (targetIsUltraWide == isUltraWide) {
                                    state.wasActive = true
                                    activeVideoView.value = this
                                    restorePlayback(this, state)
                                } else {
                                    state.wasActive = false
                                    pause()
                                }
                            }
                            setVideoURI(targetUri)
                        }
                    },
                    update = { view ->
                        // VideoView is backed by SurfaceView. API 26+ supports synchronized
                        // SurfaceView scaling, so this changes the actual displayed crop,
                        // not merely the gallery controls or the player container.
                        view.pivotX = view.width / 2f
                        view.pivotY = view.height / 2f
                        view.scaleX = cropScale
                        view.scaleY = cropScale

                        val state = view.tag as? GalleryPlayerState
                        if (state != null) {
                            val shouldBeActive = targetIsUltraWide == isUltraWide
                            if (shouldBeActive && !state.wasActive) {
                                state.wasActive = true
                                activeVideoView.value = view
                                if (state.isPrepared) restorePlayback(view, state)
                            } else if (shouldBeActive) {
                                activeVideoView.value = view
                            } else {
                                state.wasActive = false
                            }
                        }
                    },
                    onRelease = { view ->
                        if (activeVideoView.value === view) activeVideoView.value = null
                        view.setOnPreparedListener(null)
                        (view.tag as? GalleryPlayerState)?.mediaPlayer?.setOnSeekCompleteListener(null)
                        view.setMediaController(null)
                        view.stopPlayback()
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
