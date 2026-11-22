package com.example.camera.stabilization

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Persistent asynchronous event log for one Photon EIS+ recording attempt. */
internal object EisPlusDiagnosticLog {
    const val LOG_DIRECTORY_NAME = "eis-plus-logs"
    private const val TAG = "EisPlusDiagnostics"
    private const val MAX_RETAINED_FILES = 30

    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "EIS+DiagnosticWriter").apply { isDaemon = true }
    }
    private var activeFile: File? = null
    private var activeDirectory: File? = null
    private var sessionStartElapsedNs = 0L
    private var eventSequence = 0L

    fun beginIfNeeded(context: Context, details: String) {
        synchronized(lock) {
            if (activeFile != null) {
                eventLocked("LIFECYCLE", "SESSION_ALREADY_OPEN", details)
            } else {
                beginLocked(context.applicationContext, details)
            }
        }
    }

    fun begin(context: Context, details: String) {
        synchronized(lock) {
            activeFile?.let { appendLocked(it, formatLineLocked("WARN", "LIFECYCLE", "PREVIOUS_SESSION_REPLACED", it.name)) }
            activeFile = null
            beginLocked(context.applicationContext, details)
        }
    }

    private fun beginLocked(context: Context, details: String) {
        val directory = File(context.filesDir, LOG_DIRECTORY_NAME)
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Could not create diagnostic directory: ${directory.absolutePath}")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val suffix = UUID.randomUUID().toString().take(8)
        val file = File(directory, "eis-plus-$stamp-$suffix.log")
        try {
            if (!file.createNewFile()) return
        } catch (error: Throwable) {
            Log.e(TAG, "Could not create diagnostic log", error)
            return
        }
        activeDirectory = directory
        activeFile = file
        sessionStartElapsedNs = SystemClock.elapsedRealtimeNanos()
        eventSequence = 0L
        appendLocked(file, "# VCamera Photon EIS+ diagnostic session")
        appendLocked(file, "# device=${Build.MANUFACTURER} ${Build.MODEL}, androidApi=${Build.VERSION.SDK_INT}")
        eventLocked("SESSION", "BEGIN", details)
        Log.i(TAG, "EIS+ diagnostic session started: ${file.name}")
    }

    fun event(component: String, name: String, details: String = "", level: String = "INFO") {
        synchronized(lock) { eventLocked(component, name, details, level) }
    }

    private fun eventLocked(component: String, name: String, details: String, level: String = "INFO") {
        val file = activeFile ?: return
        appendLocked(file, formatLineLocked(level, component, name, details))
    }

    private fun formatLineLocked(level: String, component: String, name: String, details: String): String {
        eventSequence += 1L
        val elapsed = if (sessionStartElapsedNs > 0L) {
            (SystemClock.elapsedRealtimeNanos() - sessionStartElapsedNs) / 1_000_000_000.0
        } else 0.0
        val safe = details.replace("\r", " ").replace("\n", " ").replace("|", "/")
        return String.format(Locale.US, "%06d | +%.3fs | %-5s | %-16s | %-28s | %s",
            eventSequence, elapsed, level, component, name, safe)
    }

    private fun appendLocked(file: File, line: String) {
        writer.execute {
            try {
                FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { output ->
                    output.append(line)
                    output.newLine()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Failed writing EIS+ diagnostic event to ${file.name}", error)
            }
        }
    }

    fun finish(reason: String) {
        synchronized(lock) {
            val file = activeFile ?: return
            eventLocked("SESSION", "END", reason)
            activeFile = null
            writer.execute { pruneOldFiles(activeDirectory) }
            Log.i(TAG, "EIS+ diagnostic session closed: ${file.name}")
        }
    }

    /** Wait for queued writes before the viewer reads the completed session. Call from an IO thread. */
    fun flush() {
        val latch = CountDownLatch(1)
        writer.execute { latch.countDown() }
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun listFiles(context: Context): List<File> {
        flush()
        val directory = File(context.applicationContext.filesDir, LOG_DIRECTORY_NAME)
        return directory.listFiles().orEmpty()
            .filter { it.isFile && (it.extension.equals("log", true) || it.extension.equals("csv", true)) }
            .sortedWith(
                compareByDescending<File> { it.extension.equals("log", true) }
                    .thenByDescending { it.lastModified() },
            )
    }

    private fun pruneOldFiles(directory: File?) {
        directory?.listFiles().orEmpty()
            .filter { it.isFile && (it.extension == "log" || it.extension == "csv") }
            .sortedByDescending(File::lastModified)
            .drop(MAX_RETAINED_FILES)
            .forEach { runCatching { it.delete() } }
    }
}
