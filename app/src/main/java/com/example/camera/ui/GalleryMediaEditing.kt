package com.example.camera.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class GalleryPhotoEditSettings(
    val exposure: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val warmth: Float = 0f,
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val cropRatio: String = "Original"
)

internal object GalleryMediaActions {
    private const val FAVORITES_PREFS = "gallery_media_favorites"
    private const val FAVORITES_KEY = "uris"

    fun isFavorite(context: Context, uri: Uri): Boolean {
        return context.getSharedPreferences(FAVORITES_PREFS, Context.MODE_PRIVATE)
            .getStringSet(FAVORITES_KEY, emptySet())
            .orEmpty()
            .contains(uri.toString())
    }

    fun setFavorite(context: Context, uri: Uri, favorite: Boolean) {
        val prefs = context.getSharedPreferences(FAVORITES_PREFS, Context.MODE_PRIVATE)
        val updated = prefs.getStringSet(FAVORITES_KEY, emptySet()).orEmpty().toMutableSet()
        if (favorite) updated.add(uri.toString()) else updated.remove(uri.toString())
        prefs.edit().putStringSet(FAVORITES_KEY, updated).apply()
    }

    fun deleteMedia(context: Context, uri: Uri): Boolean {
        return try {
            if (uri.scheme == "file") {
                uri.path?.let(::File)?.delete() == true
            } else {
                context.contentResolver.delete(uri, null, null) > 0
            }
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun saveEditedPhoto(context: Context, bitmap: Bitmap, originalName: String): Uri? {
        val baseName = originalName.substringBeforeLast('.', originalName).ifBlank { "VCamera_photo" }
        val displayName = "${baseName}_EDITED_${System.currentTimeMillis()}.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VCamera/Edited")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            ) ?: return null
            return try {
                val output = context.contentResolver.openOutputStream(uri, "w")
                    ?: throw IllegalStateException("Could not open image output")
                output.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it) }
                val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, ready, null, null)
                uri
            } catch (t: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw t
            }
        }

        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "VCamera/Edited")
        if (!directory.exists() && !directory.mkdirs()) return null
        val file = File(directory, displayName)
        return try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it) }
            Uri.fromFile(file)
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun exportVideoCopy(context: Context, source: Uri, originalName: String, suffix: String): Uri? {
        val safeBase = originalName.substringBeforeLast('.', originalName)
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .ifBlank { "VCamera_Special" }
        val displayName = "${safeBase}_${suffix}_${System.currentTimeMillis()}.mp4"
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VCamera/Exports")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val destination = resolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            ) ?: return null
            return try {
                openGalleryInput(context, source).use { input ->
                    resolver.openOutputStream(destination, "w")?.use { output ->
                        input.copyTo(output, bufferSize = 256 * 1024)
                        output.flush()
                    } ?: throw IllegalStateException("Could not open video output")
                }
                val ready = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(destination, ready, null, null)
                destination
            } catch (t: Throwable) {
                runCatching { resolver.delete(destination, null, null) }
                throw t
            }
        }

        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VCamera/Exports")
        if (!directory.exists() && !directory.mkdirs()) return null
        val file = File(directory, displayName)
        return try {
            openGalleryInput(context, source).use { input ->
                file.outputStream().buffered().use { output -> input.copyTo(output, 256 * 1024) }
            }
            Uri.fromFile(file)
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun shareMedia(context: Context, uri: Uri, isVideo: Boolean, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newUri(context.contentResolver, title, uri)
        }
        context.startActivity(Intent.createChooser(intent, "Share $title"))
    }

    private fun openGalleryInput(context: Context, uri: Uri) =
        if (uri.scheme == "file") {
            File(uri.path ?: throw IllegalArgumentException("Missing file path")).inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open source media")
        }
}

internal fun renderGalleryPhotoEdits(
    source: Bitmap,
    settings: GalleryPhotoEditSettings
): Bitmap {
    val ratio = when (settings.cropRatio) {
        "1:1" -> 1f
        "4:3" -> 4f / 3f
        "16:9" -> 16f / 9f
        "3:4" -> 3f / 4f
        else -> 0f
    }
    var cropped = source
    if (ratio > 0f) {
        val sourceRatio = source.width.toFloat() / source.height.coerceAtLeast(1)
        cropped = if (sourceRatio > ratio) {
            val width = (source.height * ratio).toInt().coerceIn(1, source.width)
            Bitmap.createBitmap(source, (source.width - width) / 2, 0, width, source.height)
        } else {
            val height = (source.width / ratio).toInt().coerceIn(1, source.height)
            Bitmap.createBitmap(source, 0, (source.height - height) / 2, source.width, height)
        }
    }

    val transform = Matrix().apply {
        if (settings.rotationDegrees != 0) postRotate(settings.rotationDegrees.toFloat())
        if (settings.flipHorizontal) postScale(-1f, 1f)
    }
    val transformed = if (settings.rotationDegrees != 0 || settings.flipHorizontal) {
        Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, transform, true)
    } else {
        cropped
    }

    val exposureScale = (1f + settings.exposure * 0.28f).coerceIn(0.55f, 1.45f)
    val contrast = settings.contrast.coerceIn(0.5f, 1.8f)
    val offset = 128f * (1f - contrast) + settings.exposure * 20f
    val warmth = settings.warmth.coerceIn(-1f, 1f) * 0.14f
    val colorMatrix = ColorMatrix().apply {
        set(floatArrayOf(
            exposureScale * contrast, 0f, 0f, 0f, offset + warmth * 90f,
            0f, exposureScale * contrast, 0f, 0f, offset,
            0f, 0f, exposureScale * contrast, 0f, offset - warmth * 90f,
            0f, 0f, 0f, 1f, 0f
        ))
        postConcat(ColorMatrix().apply {
            setSaturation(settings.saturation.coerceIn(0f, 2f))
        })
    }

    val output = Bitmap.createBitmap(transformed.width, transformed.height, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(colorMatrix)
    }
    Canvas(output).drawBitmap(transformed, 0f, 0f, paint)

    if (transformed !== source && !transformed.isRecycled && transformed !== cropped) transformed.recycle()
    if (cropped !== source && cropped !== transformed && !cropped.isRecycled) cropped.recycle()
    return output
}

@Composable
internal fun GalleryPhotoEditorScreen(
    source: Bitmap,
    displayName: String,
    onBack: () -> Unit,
    onSaved: (Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeTool by rememberSaveable { mutableStateOf("Light") }
    var exposure by rememberSaveable { mutableFloatStateOf(0f) }
    var contrast by rememberSaveable { mutableFloatStateOf(1f) }
    var saturation by rememberSaveable { mutableFloatStateOf(1f) }
    var warmth by rememberSaveable { mutableFloatStateOf(0f) }
    var rotationDegrees by rememberSaveable { mutableIntStateOf(0) }
    var flipHorizontal by rememberSaveable { mutableStateOf(false) }
    var cropRatio by rememberSaveable { mutableStateOf("Original") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val settings = GalleryPhotoEditSettings(
        exposure = exposure,
        contrast = contrast,
        saturation = saturation,
        warmth = warmth,
        rotationDegrees = rotationDegrees,
        flipHorizontal = flipHorizontal,
        cropRatio = cropRatio
    )
    val preview by produceState<Bitmap?>(initialValue = null, source, settings) {
        value = withContext(Dispatchers.Default) {
            runCatching { renderGalleryPhotoEdits(source, settings) }.getOrNull()
        }
    }
    DisposableEffect(preview) {
        onDispose {
            preview?.takeIf { it !== source && !it.isRecycled }?.recycle()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                "Edit photo",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                exposure = 0f
                contrast = 1f
                saturation = 1f
                warmth = 0f
                rotationDegrees = 0
                flipHorizontal = false
                cropRatio = "Original"
            }) {
                androidx.compose.material3.Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White)
                Text("Reset", color = Color.White)
            }
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                preview != null -> Image(
                    bitmap = preview!!.asImageBitmap(),
                    contentDescription = "Edited photo preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
                else -> CircularProgressIndicator(color = Color.White)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Light", "Color", "Crop").forEach { tool ->
                FilterChip(
                    selected = activeTool == tool,
                    onClick = { activeTool = tool },
                    label = { Text(tool) }
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            color = Color(0xFF15191E),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                when (activeTool) {
                    "Light" -> {
                        GalleryEditSlider("Exposure", exposure, -1f..1f, { exposure = it }, "%.1f EV")
                        GalleryEditSlider("Contrast", contrast, 0.5f..1.8f, { contrast = it }, "%.2f")
                    }
                    "Color" -> {
                        GalleryEditSlider("Saturation", saturation, 0f..2f, { saturation = it }, "%.2f")
                        GalleryEditSlider("Warmth", warmth, -1f..1f, { warmth = it }, "%.1f")
                    }
                    else -> {
                        Text("Crop ratio", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Original", "1:1", "4:3", "16:9", "3:4").forEach { ratio ->
                                FilterChip(
                                    selected = cropRatio == ratio,
                                    onClick = { cropRatio = ratio },
                                    label = { Text(ratio) }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { rotationDegrees = (rotationDegrees + 90) % 360 }) {
                                androidx.compose.material3.Icon(Icons.Filled.RotateRight, contentDescription = null)
                                Text("Rotate")
                            }
                            TextButton(onClick = { flipHorizontal = !flipHorizontal }) {
                                Text(if (flipHorizontal) "Flip: On" else "Flip horizontal")
                            }
                        }
                    }
                }
            }
        }

        if (saveError != null) {
            Text(saveError.orEmpty(), color = Color(0xFFFF8A80), modifier = Modifier.padding(horizontal = 16.dp))
        }
        Button(
            onClick = {
                if (!saving) {
                    saving = true
                    saveError = null
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            val rendered = renderGalleryPhotoEdits(source, settings)
                            try {
                                GalleryMediaActions.saveEditedPhoto(context, rendered, displayName)
                            } finally {
                                if (!rendered.isRecycled) rendered.recycle()
                            }
                        }
                        saving = false
                        if (saved != null) onSaved(saved) else saveError = "Could not save edited photo."
                    }
                }
            },
            enabled = !saving && preview != null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Saving…")
            } else {
                androidx.compose.material3.Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Save copy")
            }
        }
    }
}

@Composable
private fun GalleryEditSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    format: String
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(String.format(Locale.getDefault(), format, value), color = Color.White.copy(alpha = 0.72f))
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

internal fun formatGalleryMediaDate(timestamp: Long): Pair<String, String> {
    if (timestamp <= 0L) return "Unknown date" to ""
    val date = Date(timestamp)
    return SimpleDateFormat("dd MMM", Locale.getDefault()).format(date) to
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
}


@Composable
internal fun GalleryVideoExportDialog(
    displayName: String,
    mainUri: Uri,
    ultraWideUri: Uri?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }

    fun export(selection: String) {
        if (exporting) return
        exporting = true
        resultMessage = null
        exportError = null
        scope.launch {
            val exported = withContext(Dispatchers.IO) {
                runCatching {
                    when (selection) {
                        "main" -> listOfNotNull(
                            GalleryMediaActions.exportVideoCopy(context, mainUri, displayName, "MAIN")
                        )
                        "ultra" -> ultraWideUri?.let {
                            listOfNotNull(GalleryMediaActions.exportVideoCopy(context, it, displayName, "ULTRAWIDE"))
                        }.orEmpty()
                        else -> {
                            val main = GalleryMediaActions.exportVideoCopy(context, mainUri, displayName, "MAIN")
                            val ultra = ultraWideUri?.let {
                                GalleryMediaActions.exportVideoCopy(context, it, displayName, "ULTRAWIDE")
                            }
                            listOfNotNull(main, ultra)
                        }
                    }
                }
            }
            exporting = false
            exported.onSuccess { uris ->
                resultMessage = when (uris.size) {
                    2 -> "Both camera files exported separately to Movies/VCamera/Exports."
                    1 -> "Video exported to Movies/VCamera/Exports."
                    else -> null
                }
                if (uris.isEmpty()) exportError = "No video was exported."
            }.onFailure {
                exportError = it.message?.takeIf(String::isNotBlank) ?: "Video export failed."
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!exporting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            color = Color(0xFF171B20),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Special Video Editor", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(
                    "The recording contains two synchronized camera files. Export either view or save both as separate videos without re-encoding.",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = { export("main") },
                    enabled = !exporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Export Main video")
                }
                if (ultraWideUri != null) {
                    Button(
                        onClick = { export("ultra") },
                        enabled = !exporting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export Ultra-wide video")
                    }
                    Button(
                        onClick = { export("both") },
                        enabled = !exporting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export both separately")
                    }
                }
                if (exporting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Exporting original-quality files…", color = Color.White.copy(alpha = 0.82f))
                    }
                }
                resultMessage?.let { Text(it, color = Color(0xFF8CE4C0)) }
                exportError?.let { Text(it, color = Color(0xFFFF8A80)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !exporting) {
                        Text("Done", color = Color.White)
                    }
                }
            }
        }
    }
}
