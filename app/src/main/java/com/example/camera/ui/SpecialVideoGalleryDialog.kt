package com.example.camera.ui

import android.media.MediaPlayer
import android.os.Build
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
import kotlin.math.abs

internal const val SPECIAL_VIDEO_GALLERY_LENS_SWITCH_DURATION_MS = 300

private class GalleryVideoPlayerState(
    val uri: String,
    var isPrepared: Boolean = false,
    var wasActive: Boolean = false,
    var mediaPlayer: MediaPlayer? = null
)

/** Uses the closest video frame on API 26+, with the legacy seek API as a fallback. */
private fun MediaPlayer.seekToClosest(positionMs: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        seekTo(positionMs.toLong(), MediaPlayer.SEEK_CLOSEST)
    } else {
        seekTo(positionMs)
    }
}

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
    
    // Keep the outgoing player running while the replacement prepares and seeks.
    // Its live position is sampled again in OnSeekComplete to compensate for preparation delay.
    val outgoingVideoViewState = remember(clip.id) { mutableStateOf<VideoView?>(null) }

    val selectLens: (Boolean) -> Unit = { useUltraWide ->
        if (isUltraWide != useUltraWide) {
            videoViewState.value?.let { videoView ->
                val viewState = videoView.tag as? GalleryVideoPlayerState
                if (viewState?.isPrepared == true) {
                    playbackPosition = videoView.currentPosition.coerceAtLeast(0)
                    shouldResumePlayback = videoView.isPlaying
                    outgoingVideoViewState.value = videoView
                } else {
                    outgoingVideoViewState.value = null
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

            // Keep the outgoing decoder alive until the incoming decoder has completed
            // its seek and started. The outgoing player's live timestamp is the sync reference.
            val restorePlayback: (VideoView, GalleryVideoPlayerState) -> Unit = { videoView, viewState ->
                val lastSafePosition = (videoView.duration - 50).coerceAtLeast(0)
                val mediaPlayer = viewState.mediaPlayer
                val outgoingVideoView = outgoingVideoViewState.value?.takeIf { it !== videoView }
                val outgoingState = outgoingVideoView?.tag as? GalleryVideoPlayerState
                val livePositionAtPrepare = if (outgoingState?.isPrepared == true) {
                    outgoingVideoView.currentPosition.coerceAtLeast(0)
                } else {
                    null
                }
                val targetPosition = (livePositionAtPrepare ?: playbackPosition)
                    .coerceAtMost(lastSafePosition)
                val resumeAfterSeek = shouldResumePlayback

                fun finishHandoff(seekedPlayer: MediaPlayer?) {
                    if (targetIsUltraWide == isUltraWide && viewState.wasActive) {
                        playbackPosition = (seekedPlayer?.currentPosition ?: targetPosition)
                            .coerceAtLeast(0)
                        videoViewState.value = videoView

                        // Start the incoming player before pausing and releasing the outgoing one.
                        if (resumeAfterSeek) {
                            videoView.start()
                        } else {
                            videoView.pause()
                        }

                        if (outgoingVideoView != null) {
                            outgoingVideoView.pause()
                            outgoingState?.wasActive = false
                            outgoingState?.mediaPlayer?.setOnSeekCompleteListener(null)
                            outgoingVideoView.stopPlayback()
                            outgoingState?.mediaPlayer = null
                            outgoingState?.isPrepared = false
                        }
                        if (outgoingVideoViewState.value === outgoingVideoView) {
                            outgoingVideoViewState.value = null
                        }
                    } else {
                        videoView.pause()
                    }
                }

                if (mediaPlayer == null) {
                    finishHandoff(null)
                } else {
                    var outgoingFrozenPosition: Int? = null
                    var correctionAttempts = 0

                    fun finishWhenAligned(seekedPlayer: MediaPlayer) {
                        val handoffTarget = (outgoingFrozenPosition ?: playbackPosition)
                            .coerceAtMost(lastSafePosition)
                        val incomingPosition = seekedPlayer.currentPosition.coerceAtLeast(0)

                        // Once the old decoder is paused, align the new decoder to that exact
                        // frozen playhead. Retry within a frame-sized tolerance, not 40 ms.
                        if (abs(handoffTarget - incomingPosition) > 20 && correctionAttempts < 3) {
                            correctionAttempts++
                            seekedPlayer.setOnSeekCompleteListener { correctedPlayer ->
                                finishWhenAligned(correctedPlayer)
                            }
                            seekedPlayer.seekToClosest(handoffTarget)
                        } else {
                            seekedPlayer.setOnSeekCompleteListener(null)
                            finishHandoff(seekedPlayer)
                        }
                    }

                    mediaPlayer.setOnSeekCompleteListener { seekedPlayer ->
                        if (outgoingFrozenPosition == null) {
                            if (
                                outgoingVideoView != null &&
                                outgoingState?.isPrepared == true
                            ) {
                                outgoingVideoView.pause()
                                outgoingFrozenPosition = outgoingVideoView.currentPosition
                                    .coerceAtLeast(0)
                            } else {
                                outgoingFrozenPosition = playbackPosition.coerceAtLeast(0)
                            }
                        }
                        finishWhenAligned(seekedPlayer)
                    }
                    mediaPlayer.seekToClosest(targetPosition)
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
                            viewState.mediaPlayer = player
                            viewState.isPrepared = true
                            if (targetIsUltraWide == isUltraWide) {
                                viewState.wasActive = true
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
                            if (viewState.isPrepared) restorePlayback(videoView, viewState)
                        } else if (!shouldBeActive) {
                            viewState.wasActive = false
                        }
                    }
                },
                onRelease = { videoView ->
                    if (videoViewState.value === videoView) videoViewState.value = null
                    videoView.setOnPreparedListener(null)
                    videoView.setMediaController(null)

                    val isPendingOutgoing = outgoingVideoViewState.value === videoView
                    if (!isPendingOutgoing) {
                        (videoView.tag as? GalleryVideoPlayerState)?.let { viewState ->
                            viewState.mediaPlayer?.setOnSeekCompleteListener(null)
                            viewState.mediaPlayer = null
                            viewState.isPrepared = false
                            viewState.wasActive = false
                        }
                        videoView.stopPlayback()

                        // If the incoming view is released before handoff completion,
                        // release the retained outgoing decoder as part of dialog cleanup.
                        outgoingVideoViewState.value?.let { outgoingVideoView ->
                            if (outgoingVideoView !== videoView) {
                                outgoingVideoView.pause()
                                (outgoingVideoView.tag as? GalleryVideoPlayerState)?.let { outgoingState ->
                                    outgoingState.mediaPlayer?.setOnSeekCompleteListener(null)
                                    outgoingState.mediaPlayer = null
                                    outgoingState.isPrepared = false
                                    outgoingState.wasActive = false
                                }
                                outgoingVideoView.stopPlayback()
                            }
                            outgoingVideoViewState.value = null
                        }
                    }
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
