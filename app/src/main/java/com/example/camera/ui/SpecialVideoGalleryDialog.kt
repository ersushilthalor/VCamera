package com.example.camera.ui

import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
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
    var playerPrepared by remember(clip.id) { mutableStateOf(false) }
    val videoViewState = remember(clip.id) { mutableStateOf<VideoView?>(null) }
    val currentUri = if (isUltraWide) clip.ultraWideUri else clip.mainUri

    // Snapshot and pause the current player before changing its source. VideoView otherwise
    // keeps a PLAYING target state and may briefly autoplay the replacement from time zero.
    val selectLens: (Boolean) -> Unit = { useUltraWide ->
        if (isUltraWide != useUltraWide) {
            videoViewState.value?.let { videoView ->
                if (playerPrepared) {
                    playbackPosition = videoView.currentPosition.coerceAtLeast(0)
                    shouldResumePlayback = videoView.isPlaying
                    videoView.pause()
                }
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

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp),
            factory = { viewContext ->
                VideoView(viewContext).apply {
                    videoViewState.value = this
                    val controller = MediaController(viewContext)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setOnPreparedListener { player ->
                        player.isLooping = false
                        playerPrepared = true
                        playbackPosition = 0
                        shouldResumePlayback = true
                        start()
                    }
                    setVideoURI(currentUri)
                    tag = currentUri.toString()
                }
            },
            update = { videoView ->
                videoViewState.value = videoView
                val newKey = currentUri.toString()
                if (videoView.tag != newKey) {
                    val restorePosition = playbackPosition
                    val resumeAfterSwitch = shouldResumePlayback
                    playerPrepared = false

                    videoView.setOnPreparedListener { player ->
                        player.isLooping = false
                        val lastSafePosition = (player.duration - 250).coerceAtLeast(0)
                        val targetPosition = restorePosition.coerceAtMost(lastSafePosition)

                        if (targetPosition > 0) {
                            // Do not start until the seek has completed; otherwise the first
                            // frames of the replacement lens can replay from the beginning.
                            player.setOnSeekCompleteListener { seekedPlayer ->
                                seekedPlayer.setOnSeekCompleteListener(null)
                                playbackPosition = seekedPlayer.currentPosition.coerceAtLeast(0)
                                playerPrepared = true
                                if (resumeAfterSwitch) videoView.start()
                            }
                            player.seekTo(targetPosition)
                        } else {
                            playbackPosition = 0
                            playerPrepared = true
                            if (resumeAfterSwitch) videoView.start()
                        }
                    }

                    videoView.tag = newKey
                    videoView.setVideoURI(currentUri)
                }
            }
        )

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
