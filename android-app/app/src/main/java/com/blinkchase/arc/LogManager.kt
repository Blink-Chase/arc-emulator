package com.blinkchase.arc

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

data class LogEntry(
    val timestamp: String,
    val pid: String,
    val tid: String,
    val level: String,
    val tag: String,
    val message: String,
    var count: Int = 1,
    var startTime: Long = System.currentTimeMillis(),
    var endTime: Long = System.currentTimeMillis()
) {
    override fun toString(): String {
        val countStr = if (count > 1) " [x$count]" else ""
        return "$timestamp $level/$tag: $message$countStr"
    }

    fun toExportString(): String {
        val df = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val start = df.format(Date(startTime))
        val end = df.format(Date(endTime))
        val timeRange = if (count > 1) " ($start -> $end)" else ""
        val countStr = if (count > 1) " [x$count]" else ""
        return "$timestamp $level/$tag: $message$countStr$timeRange"
    }
}

object LogManager {
    private const val MAX_BUFFER_SIZE = 500
    private val logBuffer = ConcurrentLinkedQueue<LogEntry>()
    
    private val _isObserving = MutableStateFlow(false)
    val isObserving = _isObserving.asStateFlow()

    private val _isNuclearModeEnabled = MutableStateFlow(true)
    val isNuclearModeEnabled = _isNuclearModeEnabled.asStateFlow()

    fun setNuclearMode(enabled: Boolean) {
        _isNuclearModeEnabled.value = enabled
    }

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startObserving(context: Context) {
        if (_isObserving.value) return
        _isObserving.value = true

        job = scope.launch {
            try {
                // Clear existing logcat buffer to start fresh for this session
                Runtime.getRuntime().exec("logcat -c").waitFor()
                
                val process = Runtime.getRuntime().exec("logcat -v time")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                
                var lastEntry: LogEntry? = null
                
                while (isActive) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    
                    val entry = parseLogLine(line) ?: continue
                    
                    // --- NUCLEAR SYSTEM FILTERING ---
                    if (_isNuclearModeEnabled.value) {
                        val ignoredTags = listOf(
                            "View", "VRI", "HWUI", "ImeFocusController", 
                            "InputMethodManager", "InputMethodManagerUtils", 
                            "InsetsController", "ImeTracker", "InputTransport", 
                            "Kumiho-Kumiho", "ProfileInstaller", "WindowManager", 
                            "SurfaceFlinger", "WindowOrga", "BLASTBufferQueue"
                        )
                        if (ignoredTags.any { entry.tag.contains(it, ignoreCase = true) } ||
                            entry.message.contains("!!! REPEAT", true) ||
                            entry.message.contains("frameRate", true) ||
                            entry.message.contains("getPackageName") ||
                            entry.message.contains("mWNT", true)) {
                            continue // Drop these immediately
                        }
                    }

                    if (lastEntry != null && 
                        lastEntry!!.tag == entry.tag && 
                        lastEntry!!.level == entry.level && 
                        lastEntry!!.message == entry.message) {
                        
                        lastEntry!!.count++
                        lastEntry!!.endTime = System.currentTimeMillis()
                    } else {
                        addEntryToBuffer(entry)
                        lastEntry = entry
                    }
                }
            } catch (e: Exception) {
                Log.e("LogManager", "Logcat observation failed", e)
            } finally {
                _isObserving.value = false
            }
        }
    }

    private fun addEntryToBuffer(entry: LogEntry) {
        logBuffer.add(entry)
        while (logBuffer.size > MAX_BUFFER_SIZE) {
            logBuffer.poll()
        }
    }

    private fun parseLogLine(line: String): LogEntry? {
        // Format: 09-19 02:33:12.121 I/Tag(PID): Message
        // Or with -v time: 09-19 02:33:12.121 I/Tag  ( 10546): Message
        try {
            val parts = line.split(Regex("\\s+"), 3)
            if (parts.size < 3) return null
            
            val timestamp = "${parts[0]} ${parts[1]}"
            val remaining = parts[2]
            
            val level = remaining.take(1)
            val tagAndMeta = remaining.substringAfter("/").substringBefore(":")
            val tag = tagAndMeta.substringBefore("(").trim()
            val message = remaining.substringAfter(":").trim()
            
            return LogEntry(
                timestamp = timestamp,
                pid = "", // Simplified for now
                tid = "",
                level = level,
                tag = tag,
                message = message
            )
        } catch (e: Exception) {
            return null
        }
    }

    fun getBufferedLogs(): List<LogEntry> = logBuffer.toList()

    fun exportLogs(context: Context): String? {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val fileName = "arc_log_$timestamp.txt"
        
        val logsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Arc/logs")
        if (!logsDir.exists() && !logsDir.mkdirs()) return null
        
        val file = File(logsDir, fileName)
        return try {
            file.bufferedWriter().use { writer ->
                writer.write("--- Arc Emulator Session Log ---\n")
                writer.write("Device: ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n")
                writer.write("Exported at: ${Date()}\n\n")
                
                logBuffer.forEach { entry ->
                    writer.write(entry.toExportString() + "\n")
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to export logs", e)
            null
        }
    }

    fun saveCrashLog(context: Context, throwable: Throwable): String? {
        val logsDir = File(context.cacheDir, "logs").also { it.mkdirs() }
        val file = File(logsDir, "last_crash.txt")
        
        return try {
            file.bufferedWriter().use { writer ->
                writer.write("FATAL EXCEPTION: ${throwable.javaClass.simpleName}\n")
                writer.write("Message: ${throwable.message}\n\n")
                writer.write("--- STACK TRACE ---\n")
                writer.write(Log.getStackTraceString(throwable))
                writer.write("\n\n--- PRE-CRASH LOGS ---\n")
                
                logBuffer.forEach { entry ->
                    writer.write(entry.toExportString() + "\n")
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
