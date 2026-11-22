package com.example.camera.ui

import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.camera.gallery.SpecialVideoClip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val SPECIAL_VIDEO_GALLERY_LENS_SWITCH_DURATION_MS = 3_000

private class GalleryVideoPlayerState(
    val uri: String,
    var isPrepared: Boolean = false,
    var wasActive: Boolean = false
)

@Composable
fun SpecialVideoGalleryDialog(
    clips: List<SpecialVideoClip>,
    onDismiss: () -> Unit,
    onOpenSystemGallery: () -> Unit
) {
    var selectedClip by remember { mutableStateOf<SpecialVideoClip?>(null) }

    BackHandler(enabled = selectedClip != null) {
        selectedClip = null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF08090C),
            shape = RoundedCornerShape(0.dp)
        ) {
            val clip = selectedClip
            if (clip == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Special Video",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Close gallery", tint = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    if (clips.isEmpty()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No Special Video clips yet.",
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(clips, key = { it.id }) { item ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedClip = item },
                                    color = Color(0xFF191C22),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(46.dp),
                                            shape = CircleShape,
                                            color = Color(0xFF303640)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Filled.PlayArrow,
                                                    contentDescription = null,
                                                    tint = Color.White
                                                )
                                            }
                                        }
                                        Column(
                                            modifier = Modifier.weight(1f).padding(start = 14.dp)
                                        ) {
                                            Text(
                                                item.displayName,
                                                color = Color.White,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(
                                                formatTimestamp(item.timestamp),
                                                color = Color.White.copy(alpha = 0.58f),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            Text(
                                                "Main + Ultra-wide",
                                                color = Color(0xFF9CC5FF),
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                        Text(
                                            "PLAY",
                                            color = Color(0xFF9CC5FF),
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = onOpenSystemGallery,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Open system gallery", color = Color.White.copy(alpha = 0.78f))
                    }
                }
            } else {
                SpecialVideoPlayer(
                    clip = clip,
                    onBack = { selectedClip = null },
                    onClose = onDismiss
                )
            }
        }
    }
}

@Composable
private fun SpecialVideoPlayer(
    clip: SpecialVideoClip,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    var isUltraWide by rememberSaveable(clip.id) { mutableStateOf(false) }
    var playbackPosition by rememberSaveable(clip.id) { mutableStateOf(0) }
    var shouldResumePlayback by rememberSaveable(clip.id) { mutableStateOf(true) }
    val videoViewState = remember(clip.id) { mutableStateOf<VideoView?>(null) }
    val currentUri = if (isUltraWide) clip.ultraWideUri else clip.mainUri

    // Snapshot and pause the current player before changing its source. VideoView otherwise
    // keeps a PLAYING target state and may briefly autoplay the replacement from time zero.
    val selectLens: (Boolean) -> Unit = { useUltraWide ->
        if (isUltraWide != useUltraWide) {
            videoViewState.value?.let { videoView ->
                val viewState = videoView.tag as? GalleryVideoPlayerState
                if (viewState?.isPrepared == true) {
                    playbackPosition = videoView.currentPosition.coerceAtLeast(0)
                    shouldResumePlayback = videoView.isPlaying
                    videoView.pause()
                }
                viewState?.wasActive = false
            }
            isUltraWide = useUltraWide
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
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to clips", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(clip.displayName, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isUltraWide) "Ultra-wide camera" else "Main camera",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close player", tint = Color.White)
            }
        }

        Crossfade(
            targetState = isUltraWide,
            animationSpec = tween(durationMillis = SPECIAL_VIDEO_GALLERY_LENS_SWITCH_DURATION_MS),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = "special-video-gallery-lens-switch"
        ) { targetIsUltraWide ->
            val targetUri = if (targetIsUltraWide) clip.ultraWideUri else clip.mainUri

            // Each lens keeps its own prepared player during the crossfade. The outgoing
            // player is paused at the saved timestamp; the incoming player seeks first and
            // only then resumes, so switching never restarts the clip from zero.
            val restorePlayback: (VideoView, GalleryVideoPlayerState) -> Unit = { videoView, viewState ->
                val lastSafePosition = (videoView.duration - 250).coerceAtLeast(0)
                val targetPosition = playbackPosition.coerceAtMost(lastSafePosition)
                val resumeAfterSeek = shouldResumePlayback
                if (targetPosition > 0) {
                    videoView.setOnSeekCompleteListener { seekedPlayer ->
                        seekedPlayer.setOnSeekCompleteListener(null)
                        if (targetIsUltraWide == isUltraWide && viewState.wasActive) {
                            playbackPosition = seekedPlayer.currentPosition.coerceAtLeast(0)
                            videoViewState.value = videoView
                            if (resumeAfterSeek) videoView.start() else videoView.pause()
                        } else {
                            videoView.pause()
                        }
                    }
                    videoView.seekTo(targetPosition)
                } else {
                    playbackPosition = 0
                    if (targetIsUltraWide == isUltraWide && viewState.wasActive) {
                        videoViewState.value = videoView
                        if (resumeAfterSeek) videoView.start() else videoView.pause()
                    } else {
                        videoView.pause()
                    }
                }
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                factory = { viewContext ->
                    VideoView(viewContext).apply {
                        val viewState = GalleryVideoPlayerState(
                            uri = targetUri.toString(),
                            wasActive = targetIsUltraWide == isUltraWide
                        )
                        tag = viewState
                        if (viewState.wasActive) videoViewState.value = this

                        val controller = MediaController(viewContext)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setOnPreparedListener { player ->
                            player.isLooping = false
                            viewState.isPrepared = true
                            if (targetIsUltraWide == isUltraWide) {
                                viewState.wasActive = true
                                videoViewState.value = this
                                restorePlayback(this, viewState)
                            } else {
                                viewState.wasActive = false
                                pause()
                            }
                        }
                        setVideoURI(targetUri)
                    }
                },
                update = { videoView ->
                    val viewState = videoView.tag as? GalleryVideoPlayerState
                    if (viewState != null) {
                        val shouldBeActive = targetIsUltraWide == isUltraWide
                        if (shouldBeActive && !viewState.wasActive) {
                            viewState.wasActive = true
                            videoViewState.value = videoView
                            if (viewState.isPrepared) restorePlayback(videoView, viewState)
                        } else if (shouldBeActive) {
                            videoViewState.value = videoView
                        } else {
                            viewState.wasActive = false
                        }
                    }
                },
                onRelease = { videoView ->
                    if (videoViewState.value === videoView) videoViewState.value = null
                    videoView.setOnPreparedListener(null)
                    videoView.setOnSeekCompleteListener(null)
                    videoView.setMediaController(null)
                    videoView.stopPlayback()
                }
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 8.dp, bottom = 12.dp),
            color = Color(0xFF20242B),
            shape = RoundedCornerShape(22.dp)
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LensSwitchButton(
                    label = "0.5x",
                    isSelected = isUltraWide
                ) { selectLens(true) }
                LensSwitchButton(
                    label = "1x",
                    isSelected = !isUltraWide
                ) { selectLens(false) }
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
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "Saved video"
    return SimpleDateFormat("dd MMM yyyy · hh:mm a", Locale.getDefault())
        .format(Date(timestamp))
}
