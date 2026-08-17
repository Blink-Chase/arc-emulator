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
            mainActivity.resetAudio()
            delay(100) // Give audio time to clean up
        } catch (e: Exception) {
            Log.w(TAG, "Error during cleanup: ${e.message}")
        }
        
        // Step 4: Set optimal sample rate for platform
        mainActivity.targetSampleRate = when (platform) {
            Platform.SNES -> 32000
            Platform.PS1 -> 44100
            Platform.N64 -> 44100
            else -> 48000
        }
        Log.d(TAG, "Sample rate set to: ${mainActivity.targetSampleRate}")
        
        delay(200) // Allow system to stabilize
        
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
                                       preferredFile.name.contains("swanstation"),
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
                                           coreName.contains("duckstation"),
                            archCheck = LibraryDiagnostics.checkLibraryArchitecture(coreFile)
                        )
                    )
                }
            }
        }
        
        return coresList
    }
}