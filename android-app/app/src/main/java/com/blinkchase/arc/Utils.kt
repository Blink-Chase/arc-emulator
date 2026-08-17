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