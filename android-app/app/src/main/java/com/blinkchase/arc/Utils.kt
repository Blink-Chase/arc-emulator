package com.blinkchase.arc

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

object Utils {
    fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) result = cursor.getString(index)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    fun scanInstalledCores(context: Context): List<String> {
        val cores = mutableSetOf<String>()
        val internalDir = File(context.filesDir, "cores")
        internalDir.listFiles()?.forEach {
            if (it.name.endsWith(".so")) {
                cores.add(it.name.removePrefix("lib").removeSuffix(".so"))
            }
        }
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        nativeDir.listFiles()?.forEach {
            if (it.name.endsWith(".so")) {
                cores.add(it.name.removePrefix("lib").removeSuffix(".so"))
            }
        }
        return cores.toList().sorted()
    }

    fun getLibArchitecture(file: File): String {
        if (!file.exists()) return "Missing"
        if (file.isDirectory) return "Error: Is Directory"
        
        try {
            RandomAccessFile(file, "r").use { raf ->
                // Check ELF magic
                val magic = ByteArray(4)
                raf.read(magic)
                if (!magic.contentEquals(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))) {
                    return "Not ELF"
                }
                
                // Skip to machine type at offset 18
                raf.seek(18)
                val machineType = raf.read() or (raf.read() shl 8)
                
                return when (machineType) {
                    0x03 -> "x86"
                    0x3E -> "x86-64"
                    0x28 -> "ARM"
                    0xB7 -> "ARM64"
                    0x08 -> "MIPS"
                    else -> "Unknown ($machineType)"
                }
            }
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
    }

    fun captureGameScreenshot(view: View, file: File, onComplete: ((Boolean) -> Unit)? = null) {
        try {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            if (view is SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PixelCopy.request(view as SurfaceView, bitmap, PixelCopy.OnPixelCopyFinishedListener { result ->
                    if (result == PixelCopy.SUCCESS) {
                        try {
                            FileOutputStream(file).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            onComplete?.invoke(true)
                        } catch (e: java.lang.Exception) {
                            e.printStackTrace()
                            onComplete?.invoke(false)
                        }
                    } else {
                        onComplete?.invoke(false)
                    }
                }, Handler(Looper.getMainLooper()))
            } else {
                val canvas = android.graphics.Canvas(bitmap)
                view.draw(canvas)
                try {
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    onComplete?.invoke(true)
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                    onComplete?.invoke(false)
                }
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            onComplete?.invoke(false)
        }
    }

    fun saveScreenshotToGallery(context: Context, view: View) {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val filename = "Arc_$timestamp.png"
        val tempFile = File(context.cacheDir, filename)
        
        captureGameScreenshot(view, tempFile) { success ->
            if (success && tempFile.exists()) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val itemUri = context.contentResolver.insert(collection, values)
                if (itemUri != null) {
                    try {
                        context.contentResolver.openOutputStream(itemUri).use { out ->
                            java.io.FileInputStream(tempFile).copyTo(out!!)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(MediaStore.Images.Media.IS_PENDING, 0)
                            context.contentResolver.update(itemUri, values, null, null)
                        }
                        android.widget.Toast.makeText(context, "Screenshot saved", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        android.widget.Toast.makeText(context, "Failed to save screenshot", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                tempFile.delete()
            } else {
                android.widget.Toast.makeText(context, "Failed to capture screenshot", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Helper to prevent log spam by grouping identical messages.
     * This version is more aggressive: it suppresses repeats until they stop or the message changes.
     */
    object Logger {
        private var lastMsg: String? = null
        private var lastTag: String? = null
        private var lastLevel: Int = 0
        private var count = 0
        private var firstTime = 0L

        fun i(tag: String, msg: String) = log(android.util.Log.INFO, tag, msg)
        fun d(tag: String, msg: String) = log(android.util.Log.DEBUG, tag, msg)
        fun w(tag: String, msg: String) = log(android.util.Log.WARN, tag, msg)
        fun e(tag: String, msg: String) = log(android.util.Log.ERROR, tag, msg)

        @Synchronized
        private fun log(level: Int, tag: String, msg: String) {
            // Normalize message: strip changing memory addresses/hex to allow grouping
            val normalized = msg.replace(Regex("0x[0-9a-fA-F]{4,16}"), "0x...")
                               .replace(Regex("\\{[0-9a-fA-F]{4,16}\\}"), "{...}")

            if (normalized == lastMsg && tag == lastTag && level == lastLevel) {
                count++
                // Periodically flush even if repeating (every 5 seconds) to show life
                if (System.currentTimeMillis() - firstTime > 5000) {
                    flush()
                    firstTime = System.currentTimeMillis()
                }
            } else {
                flush()
                lastMsg = normalized
                lastTag = tag
                lastLevel = level
                count = 1
                firstTime = System.currentTimeMillis()
                // Print the first instance immediately
                Log.println(level, tag, msg)
            }
        }

        private fun flush() {
            if (count > 1 && lastMsg != null) {
                Log.println(lastLevel, lastTag!!, "!!! REPEAT x$count: $lastMsg")
            }
            count = 0
        }
    }
}

object LibraryDiagnostics {
    
    fun getDeviceArchitecture(): String {
        return Build.SUPPORTED_ABIS.joinToString(", ")
    }
    
    fun getPrimaryAbi(): String {
        return Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    }
    
    fun checkLibraryArchitecture(file: File): ArchitectureCheckResult {
        if (!file.exists()) {
            return ArchitectureCheckResult("ERROR", "File not found", null)
        }
        
        try {
            val libArch = Utils.getLibArchitecture(file)
            if (libArch == "Error" || libArch.startsWith("Error:")) {
                return ArchitectureCheckResult("ERROR", libArch, null)
            }
            
            if (libArch == "Not ELF") {
                return ArchitectureCheckResult("ERROR", "Not an ELF file", null)
            }
            
            val devicePrimaryArch = getPrimaryAbi()
            
            // Match architecture
            val isMatch = when {
                devicePrimaryArch.contains("x86_64") && libArch == "x86-64" -> true
                devicePrimaryArch.contains("x86") && !devicePrimaryArch.contains("64") && libArch == "x86" -> true
                devicePrimaryArch.contains("arm64") && libArch == "ARM64" -> true
                devicePrimaryArch.contains("armeabi") && libArch == "ARM" -> true
                else -> false
            }
            
            val status = if (isMatch) "MATCH" else "MISMATCH"
            return ArchitectureCheckResult(
                status, 
                "Device: $devicePrimaryArch, Library: $libArch",
                libArch
            )
        } catch (e: Exception) {
            return ArchitectureCheckResult("ERROR", e.message ?: "Unknown error", null)
        }
    }
    
    fun scanDirectory(dir: File): List<LibraryInfo> {
        val results = mutableListOf<LibraryInfo>()
        
        if (!dir.exists() || !dir.isDirectory) {
            return results
        }
        
        dir.listFiles()?.filter { it.name.endsWith(".so") }?.forEach { file ->
            val checkResult = checkLibraryArchitecture(file)
            results.add(
                LibraryInfo(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    checkResult = checkResult
                )
            )
        }
        
        return results
    }
    
    fun generateReport(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== ARC ARCHITECTURE REPORT ===")
        sb.appendLine()
        sb.appendLine("Device:")
        sb.appendLine("  Model: ${Build.MODEL}")
        sb.appendLine("  Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("  ABIs: ${getDeviceArchitecture()}")
        sb.appendLine("  Primary: ${getPrimaryAbi()}")
        sb.appendLine()
        
        // Check Downloads (where cores should be)
        val downloads = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        sb.appendLine("Downloads Folder Cores:")
        val downloadCores = scanDirectory(downloads)
        if (downloadCores.isEmpty()) {
            sb.appendLine("  No cores found - download from RetroArch buildbot!")
        } else {
            downloadCores.forEach { info ->
                val statusColor = when (info.checkResult.status) {
                    "MATCH" -> "✓"
                    "MISMATCH" -> "✗"
                    else -> "?"
                }
                sb.appendLine("  $statusColor ${info.name}")
                sb.appendLine("     ${info.checkResult.message}")
            }
        }
        sb.appendLine()
        
        // Check internal cores directory
        val internalCores = File(context.filesDir, "cores")
        sb.appendLine("Internal Cores (Loaded):")
        val internalList = scanDirectory(internalCores)
        if (internalList.isEmpty()) {
            sb.appendLine("  No cores installed")
        } else {
            internalList.forEach { info ->
                val statusColor = when (info.checkResult.status) {
                    "MATCH" -> "✓"
                    "MISMATCH" -> "✗"
                    else -> "?"
                }
                sb.appendLine("  $statusColor ${info.name}")
                sb.appendLine("     ${info.checkResult.message}")
            }
        }
        sb.appendLine()
        
        sb.appendLine("Instructions:")
        sb.appendLine("1. Download x86_64 cores from:")
        sb.appendLine("   https://buildbot.libretro.com/nightly/")
        sb.appendLine("   android/latest/x86_64/")
        sb.appendLine("2. Extract .so files to Downloads folder")
        sb.appendLine("3. Restart Arc Emulator")
        sb.appendLine("4. Scan Cores - cores auto-copied")
        
        return sb.toString()
    }
}

fun detectConsolePlatform(file: File): Platform =
    detectConsolePlatform(file.name, file.absolutePath, file.length(), file)

internal fun detectConsolePlatform(
    fileName: String,
    filePath: String,
    fileSize: Long,
    archiveFile: File? = null
): Platform {
    val name = fileName.lowercase()
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val path = filePath.lowercase()
    val normalizedPath = path.replace('\\', '/')
    val isPs2 = Regex("(?i)(?:ps2|playstation[\\s_-]*2)").containsMatchIn("$name $path")

    val isC64Directory = normalizedPath.split('/').any { it.contains(Regex("(?i)(^|[^a-z0-9])(c64|commodore\\s*64|commodore64)([^a-z0-9]|$)")) }
    val cleanNameForMatch = name.replace(Regex("[^a-z0-9]"), "")
    if (isC64Directory || cleanNameForMatch.contains("bombfusion", true)) {
        return Platform.C64
    }

    if (ext == "wbfs" || ext == "wii") return Platform.WII
    if (ext == "gcm" || ext == "gc") return Platform.GAMECUBE
    if (ext == "nds") return Platform.DS
    if (ext == "gba") return Platform.GBA
    if (ext == "gb") return Platform.GB
    if (ext == "gbc") return Platform.GBC
    if (ext == "sfc" || ext == "smc") return Platform.SNES
    if (ext == "pce" || ext == "sgx") return Platform.TURBOGRAFX
    if (ext == "ws" || ext == "wsc") return Platform.WONDERSWAN
    if (ext == "n64" || ext == "z64" || ext == "v64") return Platform.N64
    if (ext == "md" || ext == "gen" || ext == "smd") return Platform.GENESIS
    if (ext == "gdi" || ext == "cdi") return Platform.DREAMCAST
    if (ext == "ss") return Platform.SATURN
    if (ext == "adf" || ext == "adz" || ext == "dms" || ext == "fdi" || ext == "hdf" || ext == "ipf" || ext == "lha") return Platform.AMIGA
    if (ext == "a26") return Platform.ATARI
    if (ext == "a78") return Platform.ATARI_7800
    if (ext == "lnx") return Platform.LYNX
    if (ext in setOf("crt", "d2m", "d4m", "d64", "d6z", "d71", "d7z", "d80", "d81", "d82", "d8z", "g41", "g4z", "g64", "g6z", "nib", "nbz", "p00", "prg", "t64", "tap")) {
        return Platform.C64
    }

    if (ext == "zip" && archiveFile != null) {
        try {
            java.util.zip.ZipFile(archiveFile).use { zip ->
                val entries = zip.entries()
                val entryPlatforms = mutableListOf<Platform>()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val entryName = entry.name.lowercase()
                    val entryExt = entryName.substringAfterLast('.', "")
                    val entryPlatform = when (entryExt) {
                        "gbc" -> Platform.GBC
                        "gb" -> Platform.GB
                        "gba" -> Platform.GBA
                        "sfc", "smc" -> Platform.SNES
                        "pce", "sgx" -> Platform.TURBOGRAFX
                        "ws", "wsc" -> Platform.WONDERSWAN
                        "nds" -> Platform.DS
                        "n64", "z64", "v64" -> Platform.N64
                        "gen", "md", "smd" -> Platform.GENESIS
                        "gcm", "gc", "rvz" -> Platform.GAMECUBE
                        "wbfs", "wii" -> Platform.WII
                        "gdi", "cdi" -> Platform.DREAMCAST
                        "ss" -> Platform.SATURN
                        "a26" -> Platform.ATARI
                        "a78" -> Platform.ATARI_7800
                        "lnx" -> Platform.LYNX
                        "d64", "d71", "d81", "g64", "nib", "p00", "prg", "t64", "tap" -> Platform.C64
                        "cue", "chd", "pbp", "iso" -> when {
                            entryName.contains("ps2", true) -> Platform.PS2
                            entryName.contains("saturn", true) -> Platform.SATURN
                            else -> Platform.PS1
                        }
                        else -> Platform.UNKNOWN
                    }
                    if (entryPlatform != Platform.UNKNOWN) entryPlatforms.add(entryPlatform)
                }
                entryPlatforms.groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.key?.let { return it }
            }
        } catch (_: Exception) {
            // Use folder hints for unreadable or content-ambiguous archives.
        }
    }

    val pathParts = normalizedPath.split('/')
    if (pathParts.any {
            it.contains(Regex("(?i)(^|[^a-z0-9])(?:atari[\\s_-]*)?lynx([^a-z0-9]|$)"))
        }) {
        return Platform.LYNX
    }
    if (pathParts.any {
            it.contains(Regex("(?i)(^|[^a-z0-9])(?:atari[\\s_-]*)?7800([^a-z0-9]|$)"))
        }) {
        return Platform.ATARI_7800
    }
    if (pathParts.any {
            it.contains(Regex("(?i)(^|[^a-z0-9])(?:atari[\\s_-]*)?2600([^a-z0-9]|$)"))
        }) {
        return Platform.ATARI
    }

    val isWonderSwanDirectory = normalizedPath
        .split('/')
        .dropLast(1)
        .any { it.matches(Regex("(?i)wonder[\\s_-]*swan(?:[\\s_-]*color)?")) }
    if (isWonderSwanDirectory && ext == "bin") return Platform.WONDERSWAN

    val isAmigaDirectory = normalizedPath
        .split('/')
        .any { it.contains(Regex("(?i)(^|[^a-z0-9])amiga([^a-z0-9]|$)")) }
    if (isAmigaDirectory && ext in setOf("bin", "chd", "cue", "iso", "m3u", "zip")) {
        return Platform.AMIGA
    }
    if (pathParts.any {
            it.contains(Regex("(?i)(^|[^a-z0-9])atari([^a-z0-9]|$)"))
        } && ext in setOf("bin", "zip")) {
        return Platform.ATARI
    }
    if (normalizedPath.split('/').any {
            it.contains(Regex("(?i)(^|[^a-z0-9])(c64|commodore\\s*64)([^a-z0-9]|$)"))
        } && ext in setOf("bin", "zip")) {
        return Platform.C64
    }

    return when {
        name.contains("wii", true) -> Platform.WII
        name.contains("saturn", true) || path.contains("saturn", true) -> Platform.SATURN
        name.contains("dreamcast", true) || path.contains("dreamcast", true) -> Platform.DREAMCAST
        name.contains("n64", true) || path.contains("n64", true) -> Platform.N64
        name.contains("snes", true) || path.contains("snes", true) -> Platform.SNES
        name.contains("ds", true) && !name.contains("ps2", true) && !name.contains("ps1", true) -> Platform.DS
        name.contains(".nkit.iso", true) -> {
            if (fileSize <= 1_500_000_000L && (path.contains("gamecube", true) || name.contains("gc", true) || name.contains("gamecube", true))) {
                Platform.GAMECUBE
            } else if (fileSize <= 1_500_000_000L && !path.contains("wii", true)) {
                Platform.GAMECUBE
            } else {
                Platform.WII
            }
        }
        name.contains("gc", true) || name.contains("gamecube", true) -> Platform.GAMECUBE
        ext == "cue" || ext == "chd" || ext == "pbp" -> {
            if (isPs2) Platform.PS2
            else if (name.contains("saturn", true) || path.contains("saturn", true)) Platform.SATURN
            else Platform.PS1
        }
        ext == "bin" -> {
            if (isPs2) Platform.PS2
            else if (name.contains("saturn", true) || path.contains("saturn", true)) Platform.SATURN
            else Platform.PS1
        }
        name.contains(".iso", true) || ext == "iso" -> {
            if (name.contains("gc", true) || name.contains("gamecube", true)) Platform.GAMECUBE
            else if (isPs2) Platform.PS2
            else if (fileSize < 900 * 1024 * 1024L || name.contains("ps1", true) || path.contains("ps1", true)) Platform.PS1
            else Platform.PS2
        }
        else -> Platform.UNKNOWN
    }
}

data class ArchitectureCheckResult(
    val status: String,  // "MATCH", "MISMATCH", "ERROR"
    val message: String,
    val architecture: String?
)

data class LibraryInfo(
    val name: String,
    val path: String,
    val size: Long,
    val checkResult: ArchitectureCheckResult
)