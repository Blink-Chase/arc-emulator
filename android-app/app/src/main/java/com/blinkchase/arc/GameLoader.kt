package com.blinkchase.arc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

object GameLoader {
    private const val TAG = "GameLoader"
    
    data class LoadResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val coreUsed: String? = null
    )
    
    /**
     * Safely loads a game with comprehensive error handling
     */
    suspend fun loadGame(
        context: Context,
        mainActivity: MainActivity?,
        platform: Platform,
        gamePath: String,
        preferredCorePath: String,
        storageDir: File
    ): LoadResult = withContext(Dispatchers.IO) {
        
        if (mainActivity == null) {
            return@withContext LoadResult(false, "MainActivity is null")
        }
        
        Log.d(TAG, "=== Starting Game Load ===")
        Log.d(TAG, "Platform: $platform")
        Log.d(TAG, "Game: $gamePath")
        Log.d(TAG, "Preferred Core: $preferredCorePath")
        
        // Step 1: Verify device architecture
        val deviceArch = LibraryDiagnostics.getPrimaryAbi()
        Log.d(TAG, "Device Architecture: $deviceArch")
        
        // Step 2: Check if game file exists
        val gameFile = File(gamePath)
        if (!gameFile.exists()) {
            return@withContext LoadResult(false, "Game file not found: $gamePath")
        }
        Log.d(TAG, "Game file exists: ${gameFile.length()} bytes")

        // Step 3: Clean up previous state
        Log.d(TAG, "Cleaning up previous state...")
        try {
            // No delay here, MainActivity.loadGame handles native cleanup
            mainActivity.resetAudio()
        } catch (e: Exception) {
            Log.w(TAG, "Error during cleanup: ${e.message}")
        }
        
        // Step 4: Set optimal sample rate for platform
        mainActivity.targetSampleRate = when (platform) {
            Platform.SNES -> 32000
            Platform.PS1, Platform.N64, Platform.DS -> 44100
            Platform.GAMECUBE, Platform.WII, Platform.PS2 -> 48000
            else -> 48000
        }
        Log.d(TAG, "Sample rate set to: ${mainActivity.targetSampleRate}")

        // Step 4.5: Specialized System Directory Setup for high-end cores
        setupSystemDirectories(context, platform, storageDir)

        if (platform == Platform.PS2) {
            val missingBios = checkForMissingBios(platform, storageDir)
            if (missingBios.isNotEmpty()) {
                return@withContext LoadResult(
                    false,
                    "Missing BIOS files: ${missingBios.joinToString(", ")}. Import a PS2 BIOS in BIOS Manager first."
                )
            }
            val ps2Bios = File(storageDir, "system/pcsx2/bios/scph39001.bin")
            if (!ps2Bios.isFile || ps2Bios.length() != 4L * 1024L * 1024L) {
                return@withContext LoadResult(
                    false,
                    "Invalid PS2 BIOS: expected a 4 MiB SCPH-39001 dump at ${ps2Bios.absolutePath}."
                )
            }
            Log.d(TAG, "PCSX2 BIOS preflight passed: ${ps2Bios.absolutePath} (${ps2Bios.length()} bytes)")
        }
        
        // Step 5: Prepare cores list
        val internalCoresDir = File(context.filesDir, "cores")
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val lastCrashedCore = prefs.getString(MainActivity.KEY_LAST_CRASHED_CORE, null)
        
        Log.d(TAG, "Last crashed core: $lastCrashedCore")
        
        // Step 6: Load libc++_shared if needed
        val libCppFile = File(internalCoresDir, "libc++_shared.so")
        // CRITICAL FIX: Default to true. The app loads the correct bundled libc++_shared.so 
        // in MainActivity.companion. We don't need the user to provide it in the cores folder.
        var libCppLoaded = true
        
        if (libCppFile.exists()) {
            if (lastCrashedCore == "libc++_shared.so") {
                Log.w(TAG, "Skipping libc++_shared.so (previously crashed)")
            } else {
                val archCheck = LibraryDiagnostics.checkLibraryArchitecture(libCppFile)
                Log.d(TAG, "libc++_shared.so arch check: ${archCheck.status} - ${archCheck.message}")
                
                if (archCheck.status == "MATCH") {
                    try {
                        // Write crash marker
                        File(context.filesDir, "native_crash_marker").writeText("libc++_shared.so")
                        
                        System.load(libCppFile.absolutePath)
                        libCppLoaded = true
                        
                        // Clear crash marker on success
                        File(context.filesDir, "native_crash_marker").delete()
                        Log.d(TAG, "Successfully loaded libc++_shared.so")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to load libc++_shared.so: ${e.message}")
                        e.printStackTrace()
                    }
                } else {
                    Log.w(TAG, "libc++_shared.so architecture mismatch - skipping")
                }
            }
        } else {
            Log.w(TAG, "libc++_shared.so not found")
        }
        
        // Step 7: Build cores list
        val coresToTry = buildCoresList(
            context,
            platform,
            preferredCorePath,
            internalCoresDir,
            libCppLoaded
        )
        
        Log.d(TAG, "Cores to try: ${coresToTry.size}")
        coresToTry.forEachIndexed { index, core ->
            Log.d(TAG, "  $index: ${core.name} (${core.path})")
        }
        
        // Step 8: Try loading cores
        var loadSuccess = false
        var lastError: String? = null
        var successfulCore: String? = null
        
        for (coreInfo in coresToTry) {
            // Skip if this core crashed previously
            if (coreInfo.path == lastCrashedCore) {
                Log.w(TAG, "Skipping ${coreInfo.name} (previously crashed)")
                lastError = "Skipped: Previously caused crash"
                continue
            }
            
            // Check architecture
            val archCheck = coreInfo.archCheck
            if (archCheck.status == "MISMATCH") {
                Log.w(TAG, "Skipping ${coreInfo.name}: ${archCheck.message}")
                lastError = "Architecture mismatch: ${archCheck.message}"
                continue
            }
            
            // Skip complex cores if libc++ is not loaded
            if (!libCppLoaded && coreInfo.requiresLibCpp) {
                Log.w(TAG, "Skipping ${coreInfo.name}: requires libc++_shared.so")
                lastError = "Missing dependency: libc++_shared.so"
                continue
            }
            
            Log.d(TAG, "Attempting to load ${coreInfo.name}...")
            
            // Write crash marker
            try {
                File(context.filesDir, "native_crash_marker").writeText(coreInfo.path)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write crash marker: ${e.message}")
            }
            
            // Try loading the core
            try {
                val errorMsg = mainActivity.loadCore(coreInfo.path)
                
                if (errorMsg == null) {
                    Log.d(TAG, "Core loaded successfully, attempting to load game...")
                    
                    // Try loading the game
                    if (mainActivity.loadGame(gamePath)) {
                        // Success!
                        loadSuccess = true
                        successfulCore = coreInfo.name
                        
                        // Clear crash marker
                        try {
                            File(context.filesDir, "native_crash_marker").delete()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete crash marker: ${e.message}")
                        }
                        
                        Log.d(TAG, "=== Game loaded successfully with ${coreInfo.name} ===")
                        break
                    } else {
                        lastError = "Core loaded but game failed to start"
                        
                        // Check for missing BIOS files
                        val missingBios = checkForMissingBios(platform, storageDir)
                        if (missingBios.isNotEmpty()) {
                            lastError = "Missing BIOS files: ${missingBios.joinToString(", ")}. Please visit BIOS Manager in Settings."
                        }
                        
                        Log.e(TAG, lastError)
                        // Don't break, try next core
                    }
                } else {
                    lastError = errorMsg
                    Log.e(TAG, "Core load failed: $errorMsg")
                    
                    // Parse specific errors
                    when {
                        errorMsg.contains("EM_AARCH64") || errorMsg.contains("EM_X86_64") -> {
                            lastError = "Architecture mismatch detected"
                        }
                        errorMsg.contains("dlopen failed") -> {
                            lastError = "Native library error: $errorMsg"
                        }
                    }
                    // Try next core
                }
            } catch (e: Exception) {
                lastError = "Exception while loading: ${e.message}"
                Log.e(TAG, lastError, e)
            }
            
            // Small delay between attempts
            delay(100)
        }
        
        if (!loadSuccess) {
            Log.e(TAG, "=== All cores failed ===")
            Log.e(TAG, "Last error: $lastError")
            return@withContext LoadResult(false, lastError ?: "Unknown error")
        }
        
        return@withContext LoadResult(true, null, successfulCore)
    }
    
    private data class CoreInfo(
        val name: String,
        val path: String,
        val requiresLibCpp: Boolean,
        val archCheck: ArchitectureCheckResult
    )
    
    private fun buildCoresList(
        context: Context,
        platform: Platform,
        preferredCorePath: String,
        internalCoresDir: File,
        libCppLoaded: Boolean
    ): List<CoreInfo> {
        val coresList = mutableListOf<CoreInfo>()
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        
        fun getCoreFile(coreName: String): File {
            val searchNames = mutableListOf<String>()
            searchNames.add(coreName)
            if (!coreName.endsWith(".so")) searchNames.add("$coreName.so")
            
            if (!coreName.startsWith("lib")) {
                val withLib = "lib$coreName"
                searchNames.add(withLib)
                if (!withLib.endsWith(".so")) searchNames.add("$withLib.so")
            } else {
                val noLib = coreName.removePrefix("lib")
                searchNames.add(noLib)
                if (!noLib.endsWith(".so")) searchNames.add("$noLib.so")
            }

            for (name in searchNames) {
                val internalFile = File(internalCoresDir, name)
                if (internalFile.exists()) return internalFile
                val nativeFile = File(nativeDir, name)
                if (nativeFile.exists()) return nativeFile
            }
            
            val defaultName = if (coreName.startsWith("lib")) coreName else "lib$coreName"
            val defaultFileName = if (defaultName.endsWith(".so")) defaultName else "$defaultName.so"
            return File(internalCoresDir, defaultFileName)
        }
        
        // Add preferred core first
        if (preferredCorePath.isNotEmpty()) {
            val preferredFile = File(preferredCorePath)
            if (preferredFile.exists()) {
                coresList.add(
                    CoreInfo(
                        name = preferredFile.name,
                        path = preferredFile.absolutePath,
                        requiresLibCpp = preferredFile.name.contains("mupen") || 
                                       preferredFile.name.contains("parallel") ||
                                       preferredFile.name.contains("swanstation") ||
                                       preferredFile.name.contains("dolphin") ||
                                       preferredFile.name.contains("pcsx2") ||
                                       preferredFile.name.contains("melonds") ||
                                       preferredFile.name.contains("beetle"),
                        archCheck = LibraryDiagnostics.checkLibraryArchitecture(preferredFile)
                    )
                )
            }
        }
        
        // Add platform-specific cores
        MainActivity.AVAILABLE_CORES[platform]?.forEach { coreName ->
            val coreFile = getCoreFile(coreName)
            if (coreFile.exists()) {
                // Skip if already added as preferred
                if (coresList.none { it.path == coreFile.absolutePath }) {
                    coresList.add(
                        CoreInfo(
                            name = coreFile.name,
                            path = coreFile.absolutePath,
                            requiresLibCpp = coreName.contains("mupen") ||
                                           coreName.contains("parallel") ||
                                           coreName.contains("swanstation") ||
                                           coreName.contains("duckstation") ||
                                           coreName.contains("dolphin") ||
                                           coreName.contains("pcsx2") ||
                                           coreName.contains("melonds") ||
                                           coreName.contains("beetle"),
                            archCheck = LibraryDiagnostics.checkLibraryArchitecture(coreFile)
                        )
                    )
                }
            }
        }
        
        return coresList
    }

    private fun setupSystemDirectories(context: Context, platform: Platform, storageDir: File) {
        val systemDir = File(storageDir, "system")
        if (!systemDir.exists()) systemDir.mkdirs()

        when (platform) {
            Platform.GAMECUBE, Platform.WII -> {
                // Dolphin needs a specific data directory
                val dolphinDir = File(systemDir, "dolphin-emu")
                if (!dolphinDir.exists()) dolphinDir.mkdirs()
                
                // Create subdirectories Dolphin often expects
                File(dolphinDir, "Sys").mkdirs()
                File(dolphinDir, "Config").mkdirs()
                installDolphinShader(context, dolphinDir)
            }
            Platform.PS2 -> {
                // PCSX2 needs a specific config/bios folder structure
                val pcsx2Dir = File(systemDir, "pcsx2")
                if (!pcsx2Dir.exists()) pcsx2Dir.mkdirs()
                
                val biosDir = File(pcsx2Dir, "bios").also { it.mkdirs() }
                val target = File(biosDir, "scph39001.bin")
                val source = systemDir.walkTopDown().maxByOrNull { it.lastModified() }?.takeIf {
                    it.isFile &&
                        it.extension.equals("bin", true) &&
                        it.name.lowercase().replace("-", "").contains("scph39001") &&
                        it.absolutePath != target.absolutePath
                }
                if (source != null && !target.isFile) {
                    try {
                        source.inputStream().use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        val rootTarget = File(systemDir, "scph39001.bin")
                        if (rootTarget.absolutePath != target.absolutePath) {
                            target.inputStream().use { input ->
                                rootTarget.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                    } catch (e: java.io.IOException) {
                        Log.e(TAG, "Unable to install PS2 BIOS: ${source.absolutePath}", e)
                    }
                    Log.d(TAG, "Installed PS2 BIOS for PCSX2: ${source.name} -> ${target.absolutePath} (${target.length()} bytes)")
                } else if (target.exists()) {
                    Log.d(TAG, "PS2 BIOS already installed for PCSX2: ${target.absolutePath} (${target.length()} bytes)")
                    val rootTarget = File(systemDir, "scph39001.bin")
                    if (!rootTarget.exists() || rootTarget.length() != target.length())
                        target.copyTo(rootTarget, overwrite = true)
                } else {
                    Log.w(TAG, "No SCPH-39001 BIOS found below ${systemDir.absolutePath}")
                }
                if (!target.isFile || target.length() == 0L) {
                    Log.e(TAG, "PCSX2 BIOS path is missing or empty: ${target.absolutePath}")
                }
                File(pcsx2Dir, "inis").mkdirs()
            }
            Platform.DS -> {
                // MelonDS sometimes needs firmware in a specific spot or just 'system'
                val dsDir = File(systemDir, "melonds")
                if (!dsDir.exists()) dsDir.mkdirs()
            }
            else -> {}
        }
    }

    private fun installDolphinShader(context: Context, dolphinDir: File) {
        val shader = File(dolphinDir, "Sys/Shaders/default_pre_post_process.glsl")
        if (shader.exists()) return

        shader.parentFile?.mkdirs()
        context.assets.open("dolphin/Sys/Shaders/default_pre_post_process.glsl").use { input ->
            shader.outputStream().use { output -> input.copyTo(output) }
        }
        Log.d(TAG, "Installed Dolphin default post-processing shader")
    }

    private fun checkForMissingBios(platform: Platform, storageDir: File): List<String> {
        val systemDir = File(storageDir, "system")
        if (!systemDir.exists()) {
            return if (platform == Platform.PS2) {
                listOf("scph39001.bin (PS2 BIOS)")
            } else {
                emptyList()
            }
        }

        val detectedFiles = systemDir.listFiles()?.map { it.name.lowercase() } ?: emptyList()
        val missing = mutableListOf<String>()

        when (platform) {
            Platform.PS1 -> {
                val ps1Bios = listOf("scph5501.bin", "scph5500.bin", "scph5502.bin", "scph1001.bin")
                if (ps1Bios.none { detectedFiles.contains(it.lowercase()) }) {
                    missing.add("scph5501.bin (PS1 BIOS)")
                }
            }
            Platform.GBA -> {
                if (!detectedFiles.contains("gba_bios.bin")) {
                    missing.add("gba_bios.bin (GBA BIOS)")
                }
            }
            Platform.PS2 -> {
                val pcsx2BiosDir = File(File(systemDir, "pcsx2"), "bios")
                val ps2Bios = sequenceOf(systemDir, pcsx2BiosDir)
                    .flatMap { it.walkTopDown() }
                    .firstOrNull {
                        it.isFile &&
                            it.extension.equals("bin", true) &&
                            it.name.lowercase().replace("-", "").contains("scph39001")
                    }
                if (ps2Bios == null) {
                    missing.add("scph39001.bin (PS2 BIOS)")
                }
            }
            Platform.SATURN -> {
                if (!detectedFiles.contains("saturn_bios.bin")) {
                    missing.add("saturn_bios.bin (Saturn BIOS)")
                }
            }
            else -> {}
        }
        return missing
    }
}
