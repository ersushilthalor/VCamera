package com.example.camera.ui

import android.media.MediaController
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    val currentUri = if (isUltraWide) clip.ultraWideUri else clip.mainUri
    val context = LocalContext.current

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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LensSwitchButton(
                label = "MAIN",
                isSelected = !isUltraWide,
                modifier = Modifier.weight(1f)
            ) { isUltraWide = false }
            LensSwitchButton(
                label = "ULTRA-WIDE",
                isSelected = isUltraWide,
                modifier = Modifier.weight(1f)
            ) { isUltraWide = true }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp),
            factory = { viewContext ->
                VideoView(viewContext).apply {
                    setMediaController(MediaController(viewContext))
                    setOnPreparedListener { player ->
                        player.isLooping = false
                        start()
                    }
                    setVideoURI(currentUri)
                    tag = currentUri.toString()
                }
            },
            update = { videoView ->
                val newKey = currentUri.toString()
                if (videoView.tag != newKey) {
                    val previousPosition = videoView.currentPosition
                    val wasPlaying = videoView.isPlaying
                    videoView.setOnPreparedListener { player ->
                        val safePosition = previousPosition.coerceAtMost(
                            (player.duration - 250).coerceAtLeast(0)
                        )
                        if (safePosition > 0) videoView.seekTo(safePosition)
                        if (wasPlaying || previousPosition == 0) videoView.start()
                    }
                    videoView.setVideoURI(currentUri)
                    videoView.tag = newKey
                }
            }
        )

        Text(
            text = "Switch lenses while keeping the playback position.",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(12.dp)
        )
    }
}

@Composable
private fun LensSwitchButton(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) Color(0xFF2D65B8) else Color(0xFF20242B),
            contentColor = Color.White
        )
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "Saved video"
    return SimpleDateFormat("dd MMM yyyy · hh:mm a", Locale.getDefault())
        .format(Date(timestamp))
}
