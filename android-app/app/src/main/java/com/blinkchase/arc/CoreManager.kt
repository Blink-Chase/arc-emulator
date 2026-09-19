package com.blinkchase.arc

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object CoreManager {
    private const val TAG = "CoreManager"
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/blinkchase/arc-emulator/main/cores_manifest.json"

    sealed class DownloadState {
        object Idle : DownloadState()
        object FetchingManifest : DownloadState()
        object Preparing : DownloadState()
        data class Downloading(val progress: Float) : DownloadState()
        object Extracting : DownloadState()
        object Verifying : DownloadState()
        object Success : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    data class CoreItem(
        val coreId: String,
        val displayName: String,
        val platform: String,
        val version: String,
        val isRecommended: Boolean,
        val description: String,
        val downloadUrls: Map<String, String>
    )

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    suspend fun fetchManifest(): List<CoreItem> = withContext(Dispatchers.IO) {
        _downloadState.value = DownloadState.FetchingManifest
        val items = mutableListOf<CoreItem>()
        try {
            val url = URL(MANIFEST_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonText)
                val array = root.getJSONArray("cores")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val urlsObj = obj.getJSONObject("download_urls")
                    val urlsMap = mutableMapOf<String, String>()
                    urlsObj.keys().forEach { key ->
                        urlsMap[key] = urlsObj.getString(key)
                    }
                    
                    items.add(
                        CoreItem(
                            coreId = obj.getString("core_id"),
                            displayName = obj.getString("display_name"),
                            platform = obj.getString("platform"),
                            version = obj.getString("version"),
                            isRecommended = obj.optBoolean("is_recommended", false),
                            description = obj.optString("description", ""),
                            downloadUrls = urlsMap
                        )
                    )
                }
                _downloadState.value = DownloadState.Idle
            } else {
                items.addAll(getLibretroBuildbotManifest())
                _downloadState.value = DownloadState.Idle
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching online manifest, switching directly to official Libretro Buildbot mirror list: ${e.message}")
            items.addAll(getLibretroBuildbotManifest())
            _downloadState.value = DownloadState.Idle
        }
        return@withContext items
    }

    fun getInstalledCores(context: Context): List<String> {
        val coresDir = File(context.filesDir, "cores")
        if (!coresDir.exists()) return emptyList()
        return coresDir.listFiles { _, name -> name.endsWith(".so") }?.map { it.name } ?: emptyList()
    }

    suspend fun downloadAndInstallCore(context: Context, coreItem: CoreItem): Boolean = withContext(Dispatchers.IO) {
        _downloadState.value = DownloadState.Preparing
        try {
            // Global 45 second timeout for the entire operation
            withTimeout(45000L) {
                val abi = getSupportedAbi()
                val primaryUrl = coreItem.downloadUrls[abi] ?: coreItem.downloadUrls["arm64-v8a"]
                if (primaryUrl == null) {
                    _downloadState.value = DownloadState.Error("No compatible architecture package found for $abi")
                    return@withTimeout false
                }

                // Generate fallback URL if it doesn't already have _libretro_android suffix
                val fallbackUrl = if (!primaryUrl.contains("_libretro_android")) {
                    primaryUrl.replace(".so.zip", "_libretro_android.so.zip")
                } else null

                val urlsToTry = listOfNotNull(primaryUrl, fallbackUrl)
                var lastError = ""

                for (downloadUrlString in urlsToTry) {
                    try {
                        Log.d(TAG, "Attempting core download from: $downloadUrlString")
                        val url = URL(downloadUrlString)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 10000 // 10s connect timeout
                        connection.readTimeout = 15000    // 15s read timeout
                        connection.connect()

                        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                            Log.w(TAG, "Download attempt failed for URL: $downloadUrlString - HTTP ${connection.responseCode}")
                            lastError = "HTTP ${connection.responseCode}"
                            continue // Try fallback URL if available
                        }

                        val fileLength = connection.contentLength
                        Log.d(TAG, "Connected successfully. Content length: $fileLength bytes")
                        val input = connection.inputStream
                        val tempFile = File(context.cacheDir, "${coreItem.coreId}.tmp")
                        val output = FileOutputStream(tempFile)

                        val data = ByteArray(4096)
                        var total: Long = 0
                        var count: Int
                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            if (fileLength > 0) {
                                _downloadState.value = DownloadState.Downloading(total.toFloat() / fileLength.toFloat())
                            }
                            output.write(data, 0, count)
                        }
                        output.flush()
                        output.close()
                        input.close()

                        Log.d(TAG, "Downloaded total $total bytes. Starting extraction...")
                        _downloadState.value = DownloadState.Extracting
                        val coresDir = File(context.filesDir, "cores").also { it.mkdirs() }
                        
                        val targetFileName = when (coreItem.coreId) {
                            "mupen64plus_next_gles3" -> "mupen64plus_next_gles3.so"
                            else -> "${coreItem.coreId}.so"
                        }
                        val finalCoreFile = File(coresDir, targetFileName)

                        if (downloadUrlString.endsWith(".zip", ignoreCase = true)) {
                            Log.d(TAG, "Extracting zip...")
                            val zipInput = ZipInputStream(tempFile.inputStream())
                            var entry = zipInput.nextEntry
                            var extracted = false
                            while (entry != null) {
                                if (!entry.isDirectory && entry.name.endsWith(".so", ignoreCase = true)) {
                                    FileOutputStream(finalCoreFile).use { fos ->
                                        zipInput.copyTo(fos)
                                    }
                                    extracted = true
                                    break
                                }
                                entry = zipInput.nextEntry
                            }
                            zipInput.close()
                            if (!extracted) {
                                Log.w(TAG, "Exact naming not found, extracting first .so found...")
                                val fallbackZipInput = ZipInputStream(tempFile.inputStream())
                                var fallbackEntry = fallbackZipInput.nextEntry
                                while (fallbackEntry != null) {
                                    if (!fallbackEntry.isDirectory && fallbackEntry.name.endsWith(".so", ignoreCase = true)) {
                                        FileOutputStream(finalCoreFile).use { fos ->
                                            fallbackZipInput.copyTo(fos)
                                        }
                                        extracted = true
                                        break
                                    }
                                    fallbackEntry = fallbackZipInput.nextEntry
                                }
                                fallbackZipInput.close()
                            }
                            
                            if (!extracted) {
                                _downloadState.value = DownloadState.Error("No library found in zip package")
                                return@withTimeout false
                            }
                        } else {
                            tempFile.copyTo(finalCoreFile, overwrite = true)
                        }

                        tempFile.delete()

                        _downloadState.value = DownloadState.Verifying
                        val diagCheck = LibraryDiagnostics.checkLibraryArchitecture(finalCoreFile)
                        if (diagCheck.status == "MISMATCH") {
                            finalCoreFile.delete()
                            _downloadState.value = DownloadState.Error("Architecture mismatch: ${diagCheck.message}")
                            return@withTimeout false
                        }

                        _downloadState.value = DownloadState.Success
                        return@withTimeout true
                    } catch (e: Exception) {
                        Log.e(TAG, "Attempt failed: ${e.message}")
                        lastError = e.message ?: "Unknown error"
                        continue 
                    }
                }

                _downloadState.value = DownloadState.Error("All download links failed. Last error: $lastError")
                return@withTimeout false
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Download timed out after 45 seconds")
            _downloadState.value = DownloadState.Error("Operation timed out. Please check your internet connection.")
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Global error: ${e.message}", e)
            _downloadState.value = DownloadState.Error(e.message ?: "Unknown installation failure")
            return@withContext false
        }
    }

    fun deleteCore(context: Context, coreId: String): Boolean {
        val file = File(File(context.filesDir, "cores"), "${coreId}.so")
        return if (file.exists()) file.delete() else false
    }

    private fun getSupportedAbi(): String {
        return if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "arm64-v8a"
    }

    private fun getLibretroBuildbotManifest(): List<CoreItem> {
        val list = mutableListOf<CoreItem>()
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        fun createUrls(coreName: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            abis.forEach { abi ->
                map[abi] = "https://buildbot.libretro.com/nightly/android/latest/$abi/${coreName}.so.zip"
            }
            return map
        }

        // SNES
        list.add(CoreItem("snes9x_libretro_android", "Snes9x", "SNES", "1.62.1", true, "Recommended core for modern SNES emulation. High compatibility, excellent performance, and supports cheats and fast-forward.", createUrls("snes9x")))
        list.add(CoreItem("snes9x2010_libretro_android", "Snes9x 2010", "SNES", "1.52.4", false, "Lightweight SNES core optimized for low-end or older device architectures.", createUrls("snes9x2010")))

        // GBA
        list.add(CoreItem("mgba_libretro_android", "mGBA", "GBA", "0.10.2", true, "Highly accurate, feature-rich Game Boy Advance emulator core with perfect frame pacing and audio sync.", createUrls("mgba")))
        list.add(CoreItem("vba_next_libretro_android", "VBA-Next", "GBA", "1.0.2", false, "High-performance Game Boy Advance core tailored for extreme power savings or legacy hardware profiles.", createUrls("vba_next")))

        // Game Boy / Game Boy Color
        list.add(CoreItem("gambatte_libretro_android", "Gambatte", "GBC", "0.5.0", true, "The absolute gold standard for high-accuracy Game Boy and Game Boy Color emulation.", createUrls("gambatte")))

        // SEGA Genesis / Mega Drive
        list.add(CoreItem("genesis_plus_gx_libretro_android", "Genesis Plus GX", "GENESIS", "1.7.4", true, "Flawless Sega Genesis/Mega Drive, Master System, and Game Gear emulation with full audio filter fidelity.", createUrls("genesis_plus_gx")))
        list.add(CoreItem("picodrive_libretro_android", "PicoDrive", "GENESIS", "1.92", false, "Fast, lightweight SEGA console emulator core with 32X and Sega CD add-on support.", createUrls("picodrive")))

        // --- Nintendo 64 Cores ---
        // Mupen64Plus-Next (GLES3): High-definition N64 emulation supporting upscale rendering features. Best choice for modern mid/high-tier processors.
        list.add(CoreItem("mupen64plus_next_gles3", "Mupen64Plus-Next (GLES3)", "N64", "2.5", true, "Advanced Nintendo 64 core with high-definition GLES3 hardware rendering. Perfect for modern high/mid-tier devices.", createUrls("mupen64plus_next_gles3_libretro_android")))
        // ParaLLEl N64: Lightweight alternative focused on compatibility and lower device overhead requirements.
        list.add(CoreItem("parallel_n64_libretro_android", "ParaLLEl N64", "N64", "2.0", false, "Robust Nintendo 64 emulator core utilizing standard plugin structures for lightweight compatibility.", createUrls("parallel_n64")))

        // --- PlayStation 1 Cores ---
        // SwanStation: Modern PS1 emulation wrapper supporting full internal coordinate upscaling, Vulkan API pipelines, and 60FPS hacks.
        list.add(CoreItem("swanstation_libretro_android", "SwanStation", "PS1", "1.0", true, "Premium PlayStation 1 emulator core featuring advanced internal resolution upscaling, texture filtering, and Vulkan rendering support.", createUrls("swanstation")))
        // PCSX ReARMed: Blazing fast interpretation optimization pipeline for low-power chipsets.
        list.add(CoreItem("pcsx_rearmed_libretro_android", "PCSX ReARMed", "PS1", "2.3", false, "Blazing-fast PlayStation 1 core with neon hardware plugin optimizations for lightweight devices.", createUrls("pcsx_rearmed")))

        // --- Nintendo DS Cores ---
        // MelonDS: Recommended option for standard dual-screen layouts, high emulation fidelity, and touch stylus support.
        list.add(CoreItem("melonds_libretro_android", "MelonDS", "DS", "0.9.5", true, "Modern, high-performance Nintendo DS core featuring highly accurate dual-screen presentation modes and layout swapping controls.", createUrls("melonds")))
        // DeSmuME: Alternative core with comprehensive save state mapping metrics and debug mechanics tools.
        list.add(CoreItem("desmume_libretro_android", "DeSmuME", "DS", "0.9.13", false, "Feature-rich Nintendo DS emulator core with advanced configuration tweaks and save state mechanics.", createUrls("desmume")))

        // --- GameCube / Wii Cores ---
        // Dolphin: Full libretro bridge mapping GC and Wii architectures to high-end 64-bit systems.
        list.add(CoreItem("dolphin_libretro_android", "Dolphin", "GAMECUBE", "5.0", true, "State-of-the-art GameCube and Wii emulator core wrapper layer requiring high-end 64-bit hardware processing layers.", createUrls("dolphin")))

        // --- PlayStation 2 Cores ---
        // PCSX2: 64-bit vector register compilation layer supporting demanding PlayStation 2 game emulation sequences.
        list.add(CoreItem("pcsx2_libretro_android", "PCSX2", "PS2", "1.7.0", true, "Advanced PlayStation 2 libretro core mapping for 64-bit vector structures.", createUrls("pcsx2")))

        // --- Sega Saturn Cores ---
        // Yabause: Focuses on native layout rendering translation hooks for standard Sega Saturn compatibility.
        list.add(CoreItem("yabause_libretro_android", "Yabause", "SATURN", "0.9.15", true, "Sega Saturn core focusing on core architectural compatibility mapping layers.", createUrls("yabause")))
        // Beetle Saturn: Highly precise accuracy preservation layer for perfect asset rendering lines.
        list.add(CoreItem("beetle_saturn_libretro_android", "Beetle Saturn", "SATURN", "1.24", false, "High accuracy Sega Saturn core offering superior preservation rendering logic lines.", createUrls("beetle_saturn")))

        return list
    }
}
