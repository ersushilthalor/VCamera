package com.example.camera.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.camera.stabilization.EisPlusDiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun EisPlusDiagnosticLogsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var contents by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    fun loadFile(file: File) {
        scope.launch {
            loading = true
            contents = withContext(Dispatchers.IO) {
                runCatching { file.readText(Charsets.UTF_8) }
                    .getOrElse { "Unable to read ${file.name}: ${it.message}" }
            }
            selectedFile = file
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        files = withContext(Dispatchers.IO) { EisPlusDiagnosticLog.listFiles(context) }
        files.firstOrNull()?.let { first ->
            contents = withContext(Dispatchers.IO) {
                runCatching { first.readText(Charsets.UTF_8) }
                    .getOrElse { "Unable to read ${first.name}: ${it.message}" }
            }
            selectedFile = first
        }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF151515),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("EIS+ Diagnostic Logs", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Sensor timing, matched frames, stabilization output and failure reasons",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 11.sp,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                if (files.isEmpty() && !loading) {
                    Text(
                        "No EIS+ logs found. Enable Photon EIS+ in Video Settings, then record and stop a standard SDR video.",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                } else {
                    Text("Saved reports", color = Color(0xFFFFA04A), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 126.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(files, key = { it.absolutePath }) { file ->
                            TextButton(onClick = { loadFile(file) }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    (if (file == selectedFile) "●  " else "○  ") + file.name,
                                    color = if (file == selectedFile) Color(0xFFFFA04A) else Color.White,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            selectedFile?.name ?: "Select a report",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            enabled = selectedFile != null && !loading,
                            onClick = {
                                val file = selectedFile ?: return@OutlinedButton
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "VCamera EIS+ diagnostics: ${file.name}")
                                    putExtra(Intent.EXTRA_TEXT, contents)
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(shareIntent, "Share EIS+ diagnostic report"))
                                }
                            },
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Text(" Share", fontSize = 12.sp)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp, max = 360.dp)
                            .background(Color(0xFF090909), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = when {
                                loading -> "Loading report…"
                                contents.isBlank() -> "This report is empty."
                                else -> contents.takeLast(70_000)
                            },
                            color = Color(0xFFE0E0E0),
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    }
                    Text(
                        "CSV = bounded raw gyro/native trace. LOG = sampled timeline for the complete recording session.",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 10.sp,
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}
