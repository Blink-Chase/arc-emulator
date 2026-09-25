package com.blinkchase.arc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import android.content.ComponentName
import android.content.pm.PackageManager
import android.Manifest
import android.util.Log
import com.blinkchase.arc.ui.theme.ArcEmuTheme
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.blinkchase.arc.db.GameDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {

    companion object {
        init {
            // Load libc++_shared first (bundled in jniLibs)
            try { System.loadLibrary("c++_shared") } catch (e: Exception) {}
            // Then load our native library
            System.loadLibrary("arc_native")
        }
        private const val PERMISSION_REQUEST_CODE = 1001
        const val PREFS_NAME = "arc_prefs"
        const val KEY_FAVORITES = "favorite_games"
        const val KEY_RECENTS = "recent_games"
        const val KEY_SCAN_MODE = "scan_mode"
        const val KEY_CUSTOM_PATHS = "custom_paths"
        const val KEY_AUDIO_LATENCY = "audio_latency"
        const val KEY_SHOW_FF = "show_ff"
        const val KEY_AUTO_PAUSE_MENU = "auto_pause_menu"
        const val KEY_CONTROLLER_STYLE = "controller_style"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_SCREEN_SCALE = "screen_scale"
        const val KEY_SHOW_EXTENSIONS = "show_extensions"
        const val KEY_HIDE_TOUCH_ON_CONTROLLER = "hide_touch_on_controller"
        const val KEY_CONTROLLER_DEADZONE = "controller_deadzone"
        const val KEY_RECENT_GRID_SIZE = "recent_grid_size"
        const val KEY_DISCOVERY_GRID_SIZE = "discovery_grid_size"
        const val KEY_SETUP_COMPLETE = "setup_complete_v15"
        const val KEY_HOME_IDENTITY = "home_identity"
        const val KEY_SETUP_MODE = "setup_mode"
        const val KEY_TOUR_COMPLETE = "tour_complete"
        const val KEY_LIBRARY_VIEW_STYLE = "library_view_style"
        const val KEY_SEARCH_GROUPING = "search_grouping"
        const val KEY_SCREENSCRAPER_KEY = "screenscraper_key"
        const val KEY_SHOW_FPS = "show_fps"
        const val KEY_NUCLEAR_LOGGING = "nuclear_logging"
        const val KEY_PS2_VULKAN = "ps2_vulkan_renderer"
        const val KEY_GC_CONTROLLER_STYLE = "gc_controller_style"
        const val KEY_WII_CONTROLLER_STYLE = "wii_controller_style"
        const val BTN_B = 0; const val BTN_Y = 1; const val BTN_SELECT = 2; const val BTN_START = 3
        const val BTN_UP = 4; const val BTN_DOWN = 5; const val BTN_LEFT = 6; const val BTN_RIGHT = 7
        const val BTN_A = 8; const val BTN_X = 9; const val BTN_L = 10; const val BTN_R = 11
        const val BTN_L2 = 12; const val BTN_R2 = 13; const val BTN_L3 = 14; const val BTN_R3 = 15
        const val BTN_Z = 12 // Z usually maps to L2 in modern retro mapping
        const val BTN_SCREEN_SWAP = 16 // DS specialized button
        const val KEY_LAST_CRASHED_CORE = "last_crashed_core"
        const val KEY_LAST_CRASHED_LAYOUT = "last_crashed_layout"
        const val KEY_CRASH_PENDING = "crash_pending"
        const val KEY_LAST_CRASH_SNIPPET = "last_crash_snippet"

        val AVAILABLE_CORES = mapOf(
            Platform.SNES to listOf("snes9x_libretro_android", "snes9x2010_libretro_android", "snes9x"),
            Platform.GBA to listOf("mgba_libretro_android", "vba_next_libretro_android", "mgba"),
            Platform.GB to listOf("mgba_libretro_android", "gambatte_libretro_android", "mgba", "gambatte"),
            Platform.GBC to listOf("mgba_libretro_android", "gambatte_libretro_android", "mgba", "gambatte"),
            Platform.GENESIS to listOf("genesis_plus_gx_libretro_android", "picodrive_libretro_android", "genesis_plus_gx"),
            Platform.N64 to listOf("parallel_n64_libretro_android", "mupen64plus_next_gles3", "mupen64plus_next_gles2", "mupen64plus_next_libretro", "mupen64plus_next_libretro_android", "mupen64plus_next"),
            Platform.PS1 to listOf("pcsx_rearmed_libretro_android", "swanstation_libretro_android", "pcsx_rearmed"),
            Platform.GAMECUBE to listOf("dolphin_libretro_android", "dolphin"),
            Platform.WII to listOf("dolphin_libretro_android", "dolphin"),
            Platform.DS to listOf("melonds_libretro_android", "desmume_libretro_android"),
            Platform.PS2 to listOf("pcsx2_libretro_android"),
            Platform.SATURN to listOf("yabause_libretro_android", "beetle_saturn_libretro_android")
        )
    }

    private var audioTrack: android.media.AudioTrack? = null
    private val audioLock = Any()
    var targetSampleRate = 44100
    val samplesWritten = AtomicLong(0)

    // Advanced audio tracking for underrun detection
    private var audioErrorCount = 0
    private var lastAudioLogTime = 0L
    private var audioSamplesTotal = 0L
    private var audioUnderruns = 0
    private var audioInitCount = 0
    private var audioCutouts = 0
    private var lastPositionFrames = 0L
    private var lastPositionTime = 0L
    private var wasPlaying = false

    private val database by lazy { GameDatabase.getDatabase(this) }
    internal val gameDao by lazy { database.gameDao() }
    internal val inputDao by lazy { database.inputDao() }
    val inputManager by lazy { InputManager(this) }
    var lastDeviceName: String? = null
    private var currentRetroType: Int = 1 // Default to Joypad
    var isEngineReady: Boolean = false
    var isSurfaceActive: Boolean = false // Track if we have a valid EGL surface

    // PERSISTENT NATIVE STATE - survives transitions
    var activeGamePath by mutableStateOf("")
    var activeGameName by mutableStateOf("")
    var activeGamePlatform by mutableStateOf(Platform.UNKNOWN)
    var activeGameCorePath by mutableStateOf("")
    var buttonProps by mutableStateOf(mapOf<Int, ButtonProps>())

    // Audio Threading
    @Volatile private var isAudioRunning = false
    private var audioThread: Thread? = null

    external fun loadCore(corePath: String): String?
    external fun nativeLoadGame(romPath: String): Boolean
    external fun nativePauseGame()
    external fun nativeResumeGame()
    external fun nativeForceNextFrame()
    external fun resetGame(): Int
    external fun nativeQuitGame()
    external fun nativeOnSurfaceCreated(surface: Surface)
    external fun nativeOnSurfaceChanged(surface: Surface, width: Int, height: Int)
    external fun nativeOnSurfaceDestroyed()
    external fun sendInput(buttonId: Int, value: Int)
    external fun setAnalogInput(x: Int, y: Int)
    external fun setRightAnalogInput(x: Int, y: Int)
    external fun updateNativeActivity()
    external fun setSystemDirectories(systemDir: String, saveDir: String)
    external fun saveState(path: String): Boolean
    external fun loadState(path: String): Boolean
    external fun setFastForward(enabled: Boolean)
    external fun setCheat(index: Int, enabled: Boolean, code: String)
    external fun setControllerType(port: Int, type: Int)
    external fun getAudioSamples(buffer: ShortArray, maxSamples: Int): Int
    external fun getNativeFps(): Int
    external fun getGameSampleRate(): Double
    external fun getGameFrameRate(): Double
    external fun getAudioBufferOccupancy(): Int
    external fun nativeSetVulkanRequested(enabled: Boolean)
    external fun nativeIsVulkanRequested(): Boolean
    external fun nativeIsVulkanActive(): Boolean
    external fun nativeCoreSupportsVulkan(): Boolean
    external fun setTouchInput(x: Int, y: Int, pressed: Boolean)
    external fun nativeSetDsScreenLayout(layout: String)

    /**
     * Milliseconds since the emulation loop last made progress, or -1 when there
     * is no running session to judge. Used by the in-game watchdog to notice a
     * game that hung inside the core (the state that used to make Reset/Quit
     * crash the process). Keep the threshold in sync with EmuThreadResponsive().
     */
    external fun nativeEmuHeartbeatAgeMs(): Int

    private val surfaceLock = Any()

    fun setSurface(surface: Surface?, width: Int = 0, height: Int = 0) {
        synchronized(surfaceLock) {
            isSurfaceActive = surface != null
            if (surface != null && width > 0 && height > 0) {
                Utils.Logger.i("ArcNative", "Binding surface: $surface Size: ${width}x${height}")
                nativeOnSurfaceCreated(surface)
                nativeOnSurfaceChanged(surface, width, height)
                if (isEngineReady) {
                    updateNativeActivity()
                }
            } else if (surface == null) {
                Utils.Logger.i("ArcNative", "Unbinding surface (Force Release)")
                nativeOnSurfaceDestroyed()
                if (isEngineReady) {
                    updateNativeActivity()
                }
            } else {
                Utils.Logger.w("ArcNative", "Ignored binding for $surface - invalid size: ${width}x${height}")
            }
        }
    }

    fun loadGame(romPath: String): Boolean {
        // SAFETY: If a game was already loaded, shut it down cleanly first
        if (isEngineReady) {
            nativeQuitGame()
            isEngineReady = false
        }

        resetAudio()
        if (activeGamePlatform == Platform.DS) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val dsLayout = prefs.getString("melonds_screen_layout", "Top/Bottom") ?: "Top/Bottom"
            nativeSetDsScreenLayout(dsLayout)
        }
        val success = nativeLoadGame(romPath)
        if (success) {
            isEngineReady = true

            // PROACTIVE NUDGE: Kick the renderer once immediately
            if (isSurfaceActive) {
                nativeForceNextFrame()
                updateNativeActivity()
            }
            
            resumeGame()
        }
        return success
    }

    // Start a dedicated thread for audio to prevent UI stuttering affecting sound
    private fun startAudioThread() {
        if (isAudioRunning) return
        isAudioRunning = true

        audioThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buffer = ShortArray(4096)
            var lastLogTime = System.currentTimeMillis()
            var totalSamplesRead = 0L
            while (isAudioRunning) {
                val samplesRead = getAudioSamples(buffer, buffer.size)
                if (samplesRead > 0) {
                    // OPTIMIZATION: Access audioTrack directly without locking to prevent UI stutter from affecting audio
                    val track = audioTrack
                    if (track != null && track.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                        try {
                            var offset = 0
                            while (offset < samplesRead && isAudioRunning) {
                                val written = track.write(buffer, offset, samplesRead - offset)
                                if (written <= 0) {
                                    audioErrorCount++
                                    break
                                }
                                offset += written
                                samplesWritten.addAndGet(written.toLong())
                                totalSamplesRead += written
                            }
                        } catch (e: Exception) {
                            audioErrorCount++
                        }
                    }
                } else {
                    // If buffer is empty, sleep briefly to save CPU
                    try { Thread.sleep(1) } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }

                // DEBUG: Log audio stats every 2 seconds
                val now = System.currentTimeMillis()
                if (now - lastLogTime > 2000) {
                    Utils.Logger.d("ArcAudio", "Audio Thread Alive. Samples processed in last 2s: $totalSamplesRead. Errors: $audioErrorCount")
                    totalSamplesRead = 0
                    lastLogTime = now
                }
            }
        }
        audioThread?.priority = Thread.MAX_PRIORITY
        audioThread?.start()
    }

    fun resetAudio() {
        isAudioRunning = false
        val thread = audioThread
        audioThread = null
        try { thread?.join(500) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(audioLock) {
            try {
                audioTrack?.release()
                Log.d("Arc", "AUDIO: Track released")
            } catch (e: Exception) {}
        }
        audioTrack = null
        wasPlaying = false
        lastPositionFrames = 0

    }

    private fun loadStateAndResetAudio(path: String): Boolean {
        val success = loadState(path)
        if (success) {
            synchronized(audioLock) {
                try {
                    audioTrack?.flush()
                } catch (e: Exception) {
                    audioErrorCount++
                }
            }
        }
        return success
    }

    fun pauseGame() {
        Log.d("Arc", "Requesting Game Pause...")
        isAudioRunning = false
        val thread = audioThread
        audioThread = null
        try { thread?.join(500) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        synchronized(audioLock) {
            try { audioTrack?.pause() } catch (e: Exception) {}
        }
        wasPlaying = false
        inputManager.clearAll()
        
        nativePauseGame()
    }

    fun resumeGame() {
        inputManager.clearAll() // Sanitize inputs on resume
        
        // Force a native activity nudge to ensure surface is bound
        if (isEngineReady) {
            updateNativeActivity()
        }
        
        nativeResumeGame()

        // Initialize audio track if needed
        synchronized(audioLock) {
            if (audioTrack == null) {
                try {
                    audioInitCount++
                    // Get the correct sample rate from the core (e.g. 48000Hz vs 44100Hz)
                    val coreRate = getGameSampleRate().toInt()
                    if (coreRate > 0) targetSampleRate = coreRate

                    val latencyMode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_AUDIO_LATENCY, 1)
                    val minSize = android.media.AudioTrack.getMinBufferSize(targetSampleRate, android.media.AudioFormat.CHANNEL_OUT_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    if (minSize > 0) {
                        val bufferMultiplier = when(latencyMode) { 0 -> 3; 1 -> 6; 2 -> 10; else -> 6 }
                        val bufferSize = minSize * bufferMultiplier
                        audioTrack = android.media.AudioTrack.Builder()
                            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_GAME).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(targetSampleRate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
                            .setBufferSizeInBytes(bufferSize).setTransferMode(android.media.AudioTrack.MODE_STREAM).build()
                        Log.i("ArcAudio", "AudioTrack initialized: Rate=$targetSampleRate, BufferSize=$bufferSize (Min=$minSize, Mult=$bufferMultiplier)")
                    }
                } catch (e: Exception) {
                    audioErrorCount++
                }
            }
            try { audioTrack?.play() } catch (e: Exception) {
                resetAudio()
            }
        }

        // Start continuous audio pulling
        startAudioThread()
    }

    fun quitGame() {
        isEngineReady = false
        synchronized(audioLock) {
            try { audioTrack?.pause() } catch (e: Exception) {}
        }
        wasPlaying = false
        nativeQuitGame();
        resetAudio()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nativeSetVulkanRequested(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_PS2_VULKAN, true))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = "package:$packageName".toUri()
                startActivity(intent)
            }
        } else {
            val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)
            }
        }

        // Initialize system paths immediately
        val storageDir = try {
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val publicDir = File(documentsDir, "Arc")
            if (publicDir.exists() || publicDir.mkdirs()) publicDir else getExternalFilesDir(null) ?: filesDir
        } catch (e: Exception) { filesDir }

        val savesDir = File(storageDir, "saves").also { it.mkdirs() }
        val systemDir = File(storageDir, "system").also { it.mkdirs() }
        Log.d("Arc", "Initializing Native Paths: System=${systemDir.absolutePath} Save=${savesDir.absolutePath}")
        setSystemDirectories(systemDir.absolutePath, savesDir.absolutePath)

        // Initialize Smart Log Management
        LogManager.setNuclearMode(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_NUCLEAR_LOGGING, true))
        nativeSetVulkanRequested(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_PS2_VULKAN, true))
        LogManager.startObserving(this)
        
        // Setup Global Crash Recovery
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogManager.saveCrashLog(this, throwable)
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            
            // Get last few lines for the UI snippet
            val logs = LogManager.getBufferedLogs().takeLast(10).joinToString("\n") { it.toString() }
            
            prefs.edit {
                putBoolean(KEY_CRASH_PENDING, true)
                putString(KEY_LAST_CRASH_SNIPPET, logs)
            }
            
            defaultHandler?.uncaughtException(thread, throwable)
        }

        updateNativeActivity()
        setContent {
            var themeMode by remember { mutableStateOf(0) }
            var showExtensions by remember { mutableStateOf(false) }
            val prefs = remember { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
            LaunchedEffect(Unit) {
                themeMode = prefs.getInt(KEY_THEME_MODE, 0)
                showExtensions = prefs.getBoolean(KEY_SHOW_EXTENSIONS, false)
            }
            DisposableEffect(prefs) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    when (key) {
                        KEY_THEME_MODE -> themeMode = prefs.getInt(KEY_THEME_MODE, 0)
                        KEY_SHOW_EXTENSIONS -> showExtensions = prefs.getBoolean(KEY_SHOW_EXTENSIONS, false)
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            val isDarkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> null
            }
            ArcEmuTheme(darkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) { 
                    ArcApp(storageDir, savesDir, showExtensions) 
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isEngineReady) {
            // CRITICAL: Stop engine rendering BEFORE the surface is gone
            pauseGame()
            setSurface(null)
        }
    }

    override fun onResume() {
        super.onResume()
        // Wait for GameScreen to re-bind
    }

    override fun onStop() { super.onStop(); resetAudio() }
    override fun onDestroy() { super.onDestroy(); resetAudio() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        checkControllerProfile(event.device?.name)
        if (inputManager.onKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (inputManager.onKeyUp(keyCode, event)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        checkControllerProfile(event.device?.name)
        if (inputManager.onGenericMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun checkControllerProfile(name: String?) {
        if (name != lastDeviceName) {
            lastDeviceName = name
            if (name != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    inputManager.resolveProfile(name)
                    if (inputManager.hasActiveController()) {
                        Log.d("ArcInput", "Loaded hierarchical profile for: $name")
                    }
                }
            } else {
                inputManager.setActiveProfile(null)
            }
        }
    }

    @Composable
    fun ArcApp(storageDir: File, savesDir: File, isExtensionsShown: Boolean) {
        val context = LocalContext.current
        val navController = rememberNavController()
        val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
        val scope = rememberCoroutineScope()

        // PERSISTENT GAME STATE - Link UI state to Activity properties
        var uiActivePath by rememberSaveable { mutableStateOf(activeGamePath) }
        var uiActiveName by rememberSaveable { mutableStateOf(activeGameName) }
        var uiActivePlatform by rememberSaveable { mutableStateOf(activeGamePlatform) }
        var uiActiveCore by rememberSaveable { mutableStateOf(activeGameCorePath) }
        
        // Handle button props carefully - large maps can be heavy for rememberSaveable
        var uiButtonProps by remember { mutableStateOf(buttonProps) }

        LaunchedEffect(uiActivePath, uiActiveName, uiActivePlatform, uiActiveCore) {
            activeGamePath = uiActivePath
            activeGameName = uiActiveName
            activeGamePlatform = uiActivePlatform
            activeGameCorePath = uiActiveCore
        }

        val gameList by gameDao.getAllGames().collectAsState(initial = emptyList())
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        // Guided Tour State (Managed globally to follow navigation)
        val setupMode = remember { prefs.getString(KEY_SETUP_MODE, "advanced") }
        val tourComplete = remember { prefs.getBoolean(KEY_TOUR_COMPLETE, false) }
        var tourStep by remember { mutableIntStateOf(if (setupMode == "simple" && !tourComplete) 1 else 0) }

        // Trigger tour when arriving at Home screen if needed (handles transition from Setup)
        LaunchedEffect(currentRoute) {
            if (currentRoute == Screen.HOME.name) {
                val mode = prefs.getString(KEY_SETUP_MODE, "advanced")
                val complete = prefs.getBoolean(KEY_TOUR_COMPLETE, false)
                if (mode == "simple" && !complete && tourStep == 0) {
                    tourStep = 1
                }
            }
        }

        val romImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    val importedGames = mutableListOf<GameFile>()
                    uris.forEach { uri ->
                        // Grant permanent access to this specific file
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: Exception) {
                            Log.e("Arc", "Failed to get persistent permission for $uri")
                        }

                        val fileName = Utils.getFileName(context, uri) ?: "Unknown Game"
                        val path = uri.toString()
                        
                        // Aggressive duplicate check for manual import
                        if (fileName.contains(Regex("(Track|Part|Data|Audio|Disc|Disk|Side)\\s*([2-9]|0[2-9]|\\d{2,})", RegexOption.IGNORE_CASE))) return@forEach

                        // Platform detection logic based on filename
                        val platform = when {
                            fileName.endsWith(".sfc", true) || fileName.endsWith(".smc", true) -> Platform.SNES
                            fileName.endsWith(".gba", true) -> Platform.GBA
                            fileName.endsWith(".gb", true) -> Platform.GB
                            fileName.endsWith(".gbc", true) -> Platform.GBC
                            fileName.endsWith(".md", true) || fileName.endsWith(".gen", true) || fileName.endsWith(".smd", true) -> Platform.GENESIS
                            fileName.endsWith(".n64", true) || fileName.endsWith(".z64", true) || fileName.endsWith(".v64", true) -> Platform.N64
                            fileName.endsWith(".nds", true) -> Platform.DS
                            fileName.endsWith(".chd", true) || fileName.endsWith(".cue", true) || fileName.endsWith(".m3u", true) || fileName.endsWith(".pbp", true) -> {
                                when {
                                    fileName.contains("PS2", true) || fileName.contains("PlayStation 2", true) -> Platform.PS2
                                    fileName.contains("Saturn", true) -> Platform.SATURN
                                    else -> Platform.PS1
                                }
                            }
                            fileName.endsWith(".gcm", true) || fileName.endsWith(".rvz", true) || fileName.endsWith(".gc", true) -> Platform.GAMECUBE
                            fileName.endsWith(".wbfs", true) || fileName.endsWith(".wii", true) -> Platform.WII
                            fileName.endsWith(".iso", true) -> {
                                val fileObj = File(path)
                                val fileSize = if (fileObj.exists()) fileObj.length() else 0L
                                when {
                                    fileName.contains("GC", true) || fileName.contains("GameCube", true) -> Platform.GAMECUBE
                                    fileName.contains("Wii", true) || fileSize > 3_000_000_000L -> Platform.WII
                                    fileSize in 1_300_000_000L..1_600_000_000L -> Platform.GAMECUBE
                                    fileName.contains("Saturn", true) -> Platform.SATURN
                                    else -> Platform.PS2
                                }
                            }
                            fileName.endsWith(".ss", true) || (fileName.endsWith(".bin", true) && fileName.contains("Saturn", true)) -> Platform.SATURN
                            else -> Platform.UNKNOWN
                        }
                        
                        if (platform != Platform.UNKNOWN) {
                            importedGames.add(GameFile(name = fileName, path = path, platform = platform))
                        }
                    }
                    if (importedGames.isNotEmpty()) {
                        gameDao.insertGames(importedGames)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Added ${importedGames.size} games to Library", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // Re-use the dirs we created in onCreate, but remember them for Compose
        val layoutsDir = remember { File(storageDir, "layouts").also { it.mkdirs() } }
        val internalCoresDir = remember { File(context.filesDir, "cores").also { it.mkdirs() } }
        val coresDir = remember { File(storageDir, "cores").also { it.mkdirs() } }

        fun scanCores(): List<String> = internalCoresDir.listFiles()?.filter { it.name.endsWith(".so") }?.map { it.name.removePrefix("lib").removeSuffix(".so") } ?: emptyList()
        fun scanLayouts(): List<String> = layoutsDir.listFiles()?.filter { it.extension == "layout" }?.map { it.nameWithoutExtension } ?: emptyList()

        var isScanning by remember { mutableStateOf(false) }
        var scanSignal by remember { mutableIntStateOf(0) }

        fun performScan() {
            isScanning = true
            scope.launch(Dispatchers.IO) {
                Log.d("Arc", "SCAN: Starting ROM scan...")
                
                // 1. Passive Cleanup: Prune missing, duplicate, or blocked files from database
                Log.d("Arc", "SCAN: Validating existing library entries...")
                val trackRegex = Regex("(Track|Part|Data|Audio|Disc|Disk|Side)\\s*([2-9]|0[2-9]|\\d{2,})", RegexOption.IGNORE_CASE)
                val blockedKeywords = listOf("replit", "license", "readme", "install", "debug", ".nomedia", "changelog", "credits", "config", "cache")
                
                gameList.forEach { game ->
                    if (!game.path.startsWith("content://")) {
                        val file = File(game.path)
                        val name = file.name
                        
                        // Check if the file actually exists and isn't a junk file
                        val exists = file.exists()
                        val isJunk = blockedKeywords.any { name.contains(it, true) } || name.contains(trackRegex)
                        val isBinWithoutCue = file.extension.lowercase() == "bin" && File(file.parentFile, "${file.nameWithoutExtension}.cue").exists()
                        
                        if (!exists || isJunk || isBinWithoutCue) {
                            Log.d("Arc", "SCAN: Pruning invalid/duplicate/blocked game: ${game.name} (exists=$exists, junk=$isJunk)")
                            gameDao.deleteGame(game)
                        }
                    }
                }

                val mode = prefs.getInt(KEY_SCAN_MODE, 0)
                val pathsToScan = if (mode == 1) {
                    try {
                        val allPrefs = prefs.all
                        val value = allPrefs[KEY_CUSTOM_PATHS]
                        if (value is Set<*>) {
                            @Suppress("UNCHECKED_CAST")
                            (value as Set<String>).map { File(it) }
                        } else {
                            Log.w("Arc", "Custom paths not a set, skipping. Type: ${value?.javaClass?.name}")
                            emptyList()
                        }
                    } catch (e: Exception) {
                        Log.e("Arc", "Failed to read custom paths", e)
                        emptyList()
                    }
                } else {
                    listOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
                }
                Log.d("Arc", "SCAN: Scanning ${pathsToScan.size} paths")
                val allGames = mutableListOf<GameFile>()
                pathsToScan.forEach { dir ->
                    Log.d("Arc", "SCAN: Checking directory: ${dir.absolutePath}")
                    if (dir.exists() && dir.isDirectory) {
                        val found = RomScanner.scan(dir)
                        Log.d("Arc", "SCAN: Found ${found.size} games in ${dir.name}")
                        allGames.addAll(found)
                    } else {
                        Log.w("Arc", "SCAN: Directory does not exist: ${dir.absolutePath}")
                    }
                }
                Log.d("Arc", "SCAN: Total games found: ${allGames.size}")

                // A scan is authoritative for file name and platform. IGNORE inserts
                // would leave stale PS1/PS2 assignments in Room after detection changes.
                allGames.forEach { scannedGame ->
                    val existingGame = gameDao.getGameByPath(scannedGame.path)
                    if (existingGame == null) {
                        gameDao.insertGames(listOf(scannedGame))
                    } else if (existingGame.name != scannedGame.name || existingGame.platform != scannedGame.platform) {
                        gameDao.updateGame(existingGame.copy(
                            name = scannedGame.name,
                            platform = scannedGame.platform
                        ))
                    }
                }
                
                val totalGamesInLibrary = gameDao.getGameCount()

                withContext(Dispatchers.Main) {
                    isScanning = false
                    scanSignal++
                    Toast.makeText(context, "Library Scan Complete: $totalGamesInLibrary games found", Toast.LENGTH_SHORT).show()
                }
            }
        }

        LaunchedEffect(Unit) {
            // CRITICAL FIX: Remove libc++_shared.so from internal dir if it exists.
            // It causes architecture mismatches because the app already loads the correct version.
            val badLib = File(internalCoresDir, "libc++_shared.so")
            if (badLib.exists()) {
                try { badLib.delete() } catch(e: Exception) {}
            }

            var copiedCount = 0
            coresDir.listFiles()?.filter { it.name.endsWith(".so") }?.forEach { soFile ->
                // Skip copying libc++_shared.so to prevent future conflicts
                if (soFile.name == "libc++_shared.so") return@forEach

                val targetFile = File(internalCoresDir, soFile.name)
                if (!targetFile.exists() || targetFile.length() != soFile.length()) {
                    try {
                        soFile.copyTo(targetFile, overwrite = true)
                        copiedCount++
                    } catch (e: Exception) {}
                }
            }

            val installedCores = scanCores()
            val installedLayouts = scanLayouts()
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Found ${installedCores.size} cores ($copiedCount imported), ${installedLayouts.size} layouts", android.widget.Toast.LENGTH_LONG).show()
            }
            performScan()
        }


        fun saveLayout(gameName: String) {
            val layoutFile = File(layoutsDir, "$gameName.layout")
            val json = org.json.JSONObject()
            buttonProps.forEach { (id, p) ->
                val btnJson = org.json.JSONObject()
                btnJson.put("x", p.x)
                btnJson.put("y", p.y)
                btnJson.put("scale", p.scale)
                btnJson.put("alpha", p.alpha)
                json.put(id.toString(), btnJson)
            }
            try { layoutFile.writeText(json.toString()) } catch (e: Exception) {}
        }

        fun loadLayout(gameName: String): Map<Int, ButtonProps> {
            val layoutFile = File(layoutsDir, "$gameName.layout")
            if (!layoutFile.exists()) return emptyMap()
            val text = try { layoutFile.readText() } catch (e: Exception) { return emptyMap() }
            
            val result = mutableMapOf<Int, ButtonProps>()
            try {
                if (text.startsWith("{")) {
                    val json = org.json.JSONObject(text)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val id = key.toInt()
                        val btnJson = json.getJSONObject(key)
                        result[id] = ButtonProps(
                            x = btnJson.optDouble("x", 0.0).toFloat(),
                            y = btnJson.optDouble("y", 0.0).toFloat(),
                            scale = btnJson.optDouble("scale", 1.0).toFloat(),
                            alpha = btnJson.optDouble("alpha", 1.0).toFloat()
                        )
                    }
                } else {
                    // Legacy CSV format
                    text.lines().forEach { line ->
                        val parts = line.split(",")
                        if (parts.size == 3) {
                            val id = parts[0].toIntOrNull() ?: return@forEach
                            val x = parts[1].toFloatOrNull() ?: return@forEach
                            val y = parts[2].toFloatOrNull() ?: return@forEach
                            result[id] = ButtonProps(x = x, y = y)
                        }
                    }
                }
            } catch (e: Exception) {}
            return result
        }

        fun launchGame(game: GameFile) {
            if (isEngineReady && activeGamePath == game.path) {
                // Game already running, just return to it (should be handled by navigation usually)
                Log.d("MainActivity", "Game already running: ${game.name}")
                navController.navigate(Screen.GAME.name)
                return
            }

            val retroType = when (game.platform) {
                Platform.WII -> {
                    val wiiStyle = prefs.getInt(KEY_WII_CONTROLLER_STYLE, 0)
                    when (wiiStyle) {
                        1 -> 769 // Wiimote + Classic Controller
                        2 -> 257 // Sideways Wiimote
                        else -> 513 // Wiimote + Nunchuk
                    }
                }
                Platform.GAMECUBE -> 1 // GameCube Controller
                else -> {
                    val deviceKey = "device_pref_${game.platform.name}"
                    val deviceTypeString = prefs.getString(deviceKey, if (game.platform == Platform.N64 || game.platform == Platform.PS1) EmulatedDevice.ANALOG.name else EmulatedDevice.JOYPAD.name)
                    when (deviceTypeString) {
                        EmulatedDevice.ANALOG.name -> 5
                        EmulatedDevice.MOUSE.name -> 2
                        EmulatedDevice.LIGHTGUN.name -> 4
                        else -> 1
                    }
                }
            }
            currentRetroType = retroType // Store for safe application in loadGame
            setControllerType(0, retroType) 

            scope.launch(Dispatchers.IO) {
                gameDao.updateGame(game.copy(lastPlayed = System.currentTimeMillis()))
            }
            activeGamePath = game.path
            activeGameName = game.name
            activeGamePlatform = game.platform
            inputManager.setContext(game.platform, game.path)
            checkControllerProfile(lastDeviceName) // Re-resolve profile for new game context

            val platformCores = AVAILABLE_CORES[game.platform] ?: emptyList()
            val savedCore = prefs.getString("core_pref_${game.platform.name}", null)

            var finalCorePath: String? = null

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

                val searchDirs = listOf(internalCoresDir, File(context.applicationInfo.nativeLibraryDir))
                for (name in searchNames) {
                    for (dir in searchDirs) {
                        val file = File(dir, name)
                        if (file.exists()) return file
                    }
                }
                
                // Fallback
                val defaultLibName = if (coreName.startsWith("lib")) {
                    if (coreName.endsWith(".so")) coreName else "$coreName.so"
                } else {
                    if (coreName.endsWith(".so")) "lib$coreName" else "lib$coreName.so"
                }
                return File(internalCoresDir, defaultLibName)
            }

            // 1. Check saved core (if valid arch)
            if (savedCore != null) {
                val file = getCoreFile(savedCore)
                if (file.exists() && LibraryDiagnostics.checkLibraryArchitecture(file).status == "MATCH") {
                    finalCorePath = file.absolutePath
                }
            }

            // 2. Check known cores for this platform
            if (finalCorePath == null) {
                for (name in platformCores) {
                    val file = getCoreFile(name)
                    if (file.exists() && LibraryDiagnostics.checkLibraryArchitecture(file).status == "MATCH") {
                        finalCorePath = file.absolutePath
                        break
                    }
                }
            }

            // 3. Fuzzy search for any valid core for this platform
            if (finalCorePath == null) {
                val keywords = when (game.platform) {
                    Platform.SNES -> listOf("snes")
                    Platform.GBA -> listOf("gba", "vba")
                    Platform.GB, Platform.GBC -> listOf("gambatte", "mgba", "sameboy")
                    Platform.GENESIS -> listOf("genesis", "picodrive", "md")
            Platform.N64 -> listOf("n64", "mupen", "parallel", "gles3")
                    Platform.PS1 -> listOf("pcsx", "swan", "duck")
                    Platform.GAMECUBE, Platform.WII -> listOf("dolphin")
                    Platform.PS2 -> listOf("pcsx2", "play!")
                    Platform.SATURN -> listOf("yabause", "beetle", "saturn")
                    else -> emptyList()
                }
                
                // Search both internal and native directories
                val searchDirs = listOf(internalCoresDir, File(context.applicationInfo.nativeLibraryDir))
                for (dir in searchDirs) {
                    val bestMatch = dir.listFiles()?.find { file ->
                        file.name.endsWith(".so") &&
                                keywords.any { k -> file.name.contains(k, ignoreCase = true) } &&
                                LibraryDiagnostics.checkLibraryArchitecture(file).status == "MATCH"
                    }
                    if (bestMatch != null) {
                        finalCorePath = bestMatch.absolutePath
                        break
                    }
                }
            }

            val firstCore = platformCores.firstOrNull() ?: "snes9x_libretro_android"
            activeGameCorePath = finalCorePath ?: getCoreFile(savedCore ?: firstCore).absolutePath
            buttonProps = loadLayout(game.name)
        }

        Scaffold(
            bottomBar = {
                val hideBottomBarRoutes = listOf(Screen.GAME.name, Screen.ABOUT.name, Screen.HELP.name, Screen.SETUP_GUIDE.name)
                if (currentRoute !in hideBottomBarRoutes) {
                    NavigationBar {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                            label = { Text("Home") },
                            selected = currentRoute == Screen.HOME.name,
                            onClick = { 
                                if (currentRoute != Screen.HOME.name) {
                                    navController.navigate(Screen.HOME.name) {
                                        popUpTo(Screen.HOME.name) { inclusive = true }
                                    }
                                }
                            }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Library") },
                            label = { Text("Library") },
                            selected = currentRoute == Screen.LIBRARY.name,
                            onClick = { 
                                if (currentRoute != Screen.LIBRARY.name) {
                                    navController.navigate(Screen.LIBRARY.name)
                                }
                            }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                            label = { Text("Search") },
                            selected = currentRoute == Screen.SEARCH.name,
                            onClick = { 
                                if (currentRoute != Screen.SEARCH.name) {
                                    navController.navigate(Screen.SEARCH.name)
                                }
                            }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                            label = { Text("Settings") },
                            selected = currentRoute == Screen.SETTINGS.name,
                            onClick = { 
                                if (currentRoute != Screen.SETTINGS.name) {
                                    navController.navigate(Screen.SETTINGS.name)
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            // Background scraper triggers on scan completion and avoids games with art
            LaunchedEffect(scanSignal) {
                if (gameList.isNotEmpty()) {
                    val toScrape = gameList.filter { it.coverUrl.isNullOrBlank() }
                    if (toScrape.isNotEmpty()) {
                        Log.d("Arc", "SCRAPER: Starting background pass for ${toScrape.size} games")
                        toScrape.forEach { game ->
                            // Triple check to prevent redundant scraping during high-frequency UI updates
                            if (game.coverUrl.isNullOrBlank()) {
                                ScraperService.scrapeGame(
                                    game,
                                    gameDao,
                                    prefs.getString(KEY_SCREENSCRAPER_KEY, null)
                                )
                                delay(2000) // Increase delay to respect rate limits and reduce CPU usage
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.padding(innerPadding)) {
                    NavHost(
                        navController = navController, 
                        startDestination = if (prefs.getBoolean(KEY_SETUP_COMPLETE, true)) Screen.SETUP_GUIDE.name else Screen.HOME.name
                    ) {
                        composable(Screen.HOME.name) {
                            ArcHomeScreen(
                                games = gameList,
                                recentGames = gameList.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(6),
                                storageDir = storageDir,
                                gameDao = gameDao,
                                showExtensions = isExtensionsShown,
                                prefs = prefs,
                                onGameClick = { game ->
                                    launchGame(game)
                                    uiActivePath = activeGamePath
                                    uiActiveName = activeGameName
                                    uiActivePlatform = activeGamePlatform
                                    uiActiveCore = activeGameCorePath
                                    uiButtonProps = buttonProps
                                    navController.navigate(Screen.GAME.name)
                                },
                                onToggleFavorite = { game ->
                                    scope.launch(Dispatchers.IO) {
                                        gameDao.updateGame(game.copy(isFavorite = !game.isFavorite))
                                    }
                                },
                                onGoToLibrary = { navController.navigate(Screen.LIBRARY.name) },
                                onGoToAbout = { navController.navigate(Screen.ABOUT.name) }
                            )

                            // Handle initial scrape if requested from setup
                            // Use Unit as key so it only runs once per app launch/navigation to Home
                            LaunchedEffect(Unit) {
                                if (prefs.getBoolean("pending_initial_scrape", false)) {
                                    prefs.edit { putBoolean("pending_initial_scrape", false) }
                                    scope.launch {
                                        Toast.makeText(context, "Initial Scrape Started in background...", Toast.LENGTH_SHORT).show()
                                        
                                        // Keep scraping as long as there are unscraped games
                                        // This handles games being added by the scanner in real-time
                                        var hasUnscraped = true
                                        while(hasUnscraped) {
                                            val toScrape = gameList.filter { it.coverUrl == null }
                                            if (toScrape.isEmpty()) {
                                                if (!isScanning) {
                                                    hasUnscraped = false // Truly done
                                                } else {
                                                    delay(2000) // Wait for scanner to find more
                                                }
                                            } else {
                                                toScrape.forEach { game ->
                                                    ScraperService.scrapeGame(game, gameDao)
                                                }
                                            }
                                        }
                                        Toast.makeText(context, "Initial Scrape Complete!", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                        composable(Screen.LIBRARY.name) {
                            LibraryScreen(
                                games = gameList,
                                showExtensions = isExtensionsShown,
                                isScanning = isScanning,
                                prefs = prefs,
                                onToggleFavorite = { game ->
                                    scope.launch(Dispatchers.IO) {
                                        gameDao.updateGame(game.copy(isFavorite = !game.isFavorite))
                                    }
                                },
                                onGameSelected = { game ->
                                    launchGame(game)
                                    uiActivePath = activeGamePath
                                    uiActiveName = activeGameName
                                    uiActivePlatform = activeGamePlatform
                                    uiActiveCore = activeGameCorePath
                                    uiButtonProps = buttonProps
                                    navController.navigate(Screen.GAME.name)
                                },
                                onRefreshMetadata = { game ->
                                    scope.launch(Dispatchers.IO) {
                                        ScraperService.scrapeGame(
                                            game,
                                            gameDao,
                                            prefs.getString(KEY_SCREENSCRAPER_KEY, null),
                                            force = true
                                        )
                                    }
                                },
                                onRefresh = { performScan() },
                                onNavigateToImport = {
                                    navController.navigate(Screen.IMPORT.name)
                                }
                            )
                        }
                        composable(Screen.IMPORT.name) {
                            ImportScreen(
                                isScanning = isScanning,
                                onScanGames = { performScan() },
                                onScanCores = {
                                    val cores = scanCores()
                                    Toast.makeText(context, "Found ${cores.size} cores", Toast.LENGTH_SHORT).show()
                                },
                                onScanLayouts = {
                                    val layouts = scanLayouts()
                                    Toast.makeText(context, "Found ${layouts.size} layouts", Toast.LENGTH_SHORT).show()
                                },
                                onImportFiles = { romImporter.launch(arrayOf("*/*")) },
                                coresCount = scanCores().size,
                                layoutsCount = scanLayouts().size,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Screen.SEARCH.name) {
                            SearchScreen(
                                games = gameList,
                                showExtensions = isExtensionsShown,
                                prefs = prefs,
                                onToggleFavorite = { game ->
                                    scope.launch(Dispatchers.IO) {
                                        gameDao.updateGame(game.copy(isFavorite = !game.isFavorite))
                                    }
                                },
                                onGameSelected = { game ->
                                    launchGame(game)
                                    uiActivePath = activeGamePath
                                    uiActiveName = activeGameName
                                    uiActivePlatform = activeGamePlatform
                                    uiActiveCore = activeGameCorePath
                                    uiButtonProps = buttonProps
                                    navController.navigate(Screen.GAME.name)
                                }
                            )
                        }
                        composable(Screen.SETTINGS.name) {
                            SettingsScreen(
                                prefs = prefs,
                                rootStorageDir = storageDir,
                                gameDao = gameDao,
                                games = gameList,
                                onGoToAbout = { navController.navigate(Screen.ABOUT.name) },
                                onGoToHelp = { navController.navigate(Screen.HELP.name) },
                                onGoToBios = { navController.navigate(Screen.BIOS.name) },
                                onGoToControllerMapping = { navController.navigate(Screen.CONTROLLER_MAPPING.name) },
                                onGoToCoreManagement = { navController.navigate(Screen.CORE_MANAGEMENT.name) },
                                onRefresh = { performScan() }
                            )
                        }
                        composable(Screen.CORE_MANAGEMENT.name) {
                            CoreManagementScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Screen.BIOS.name) {
                            BiosScreen(
                                storageDir = storageDir,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Screen.ABOUT.name) {
                            AboutScreen(
                                storageDir = storageDir,
                                gameDao = gameDao,
                                prefs = prefs,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Screen.HELP.name) {
                            HelpScreen(onBack = { navController.popBackStack() })
                        }
                        composable(Screen.CONTROLLER_MAPPING.name) {
                            ControllerMappingScreen(
                                inputDao = inputDao,
                                inputManager = inputManager,
                                currentPlatform = activeGamePlatform,
                                currentGamePath = activeGamePath,
                                onBack = { navController.popBackStack() },
                                onTestControls = { navController.navigate(Screen.CONTROLLER_TEST.name) }
                            )
                        }
                        composable(Screen.CONTROLLER_TEST.name) {
                            ControllerTestScreen(
                                inputManager = inputManager,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Screen.SETUP_GUIDE.name) {
                            SetupGuideScreen(
                                onFinish = {
                                    prefs.edit { putBoolean(KEY_SETUP_COMPLETE, false) }
                                    // Manually trigger tour if simple setup was chosen
                                    val mode = prefs.getString(KEY_SETUP_MODE, "advanced")
                                    if (mode == "simple") {
                                        tourStep = 1
                                    }
                                    navController.navigate(Screen.HOME.name) {
                                        popUpTo(Screen.SETUP_GUIDE.name) { inclusive = true }
                                    }
                                },
                                onImportBios = { navController.navigate(Screen.BIOS.name) },
                                onScanLibrary = { performScan() },
                                isScanning = isScanning,
                                gameList = gameList,
                                prefs = prefs,
                                gameDao = gameDao
                            )
                        }
                        composable(
                            route = Screen.GAME.name,
                            enterTransition = { fadeIn(animationSpec = tween(150)) },
                            exitTransition = { fadeOut(animationSpec = tween(150)) },
                            popEnterTransition = { fadeIn(animationSpec = tween(150)) },
                            popExitTransition = { fadeOut(animationSpec = tween(150)) }
                        ) {
                            val gameSafeName = remember(activeGameName) {
                                activeGameName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                            }
                            val gameSaveDir = remember(gameSafeName) {
                                File(savesDir, gameSafeName).also { it.mkdirs() }
                            }
                            GameScreen(
                                gameName = activeGameName,
                                platform = activeGamePlatform,
                                gamePath = activeGamePath,
                                corePath = activeGameCorePath,
                                storageDir = storageDir,
                                savesDir = gameSaveDir,
                                layoutsDir = layoutsDir,
                                buttonProps = buttonProps,
                                onUpdateProps = { id, props -> buttonProps = buttonProps + (id to props) },
                                onResetControls = { buttonProps = emptyMap() },
                                onBack = {
                                    saveLayout(activeGameName)
                                    navController.popBackStack()
                                },
                                onTogglePause = { if (it) pauseGame() else resumeGame() },
                                onSaveState = { filePath ->
                                    Log.d("MainActivity", "Saving state to: $filePath")
                                    saveState(filePath)
                                },
                                onLoadState = { filePath ->
                                    Log.d("MainActivity", "Loading state from: $filePath")
                                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        loadStateAndResetAudio(filePath)
                                    }
                                },
                                onReset = {
                                    // 1 = reset queued, 0 = the emulation thread is
                                    // wedged inside the core (a reset would crash
                                    // the process), -1 = nothing running.
                                    if (resetGame() == 0) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "The emulator core stopped responding, so the reset was cancelled. Use Quit Game to leave safely.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                onFastForward = { setFastForward(it) },
                                onControllerMapping = { navController.navigate(Screen.CONTROLLER_MAPPING.name) },
                                onQuit = {
                                    quitGame()
                                    uiActivePath = ""
                                    uiActiveName = ""
                                    uiActivePlatform = Platform.UNKNOWN
                                    uiActiveCore = ""
                                    uiButtonProps = emptyMap()
                                    inputManager.setContext(Platform.UNKNOWN, "")
                                },
                                inputManager = inputManager
                            )
                        }
                    }
                }

                // Global Tour Overlay
                if (tourStep > 0) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(enabled = false) {}) {
                        when (tourStep) {
                            1 -> GuidedTourTooltip(
                                text = "Welcome to Arc! Your most recently played games will appear here for quick access.",
                                onNext = { 
                                    tourStep = 2
                                    navController.navigate(Screen.LIBRARY.name)
                                },
                                onSkip = { 
                                    tourStep = 0
                                    prefs.edit { putBoolean(KEY_TOUR_COMPLETE, true) }
                                },
                                modifier = Modifier.align(Alignment.Center)
                            )
                            2 -> GuidedTourTooltip(
                                text = "The Library tab holds your entire collection, beautifully organized by platform.",
                                onNext = { 
                                    tourStep = 3
                                    navController.navigate(Screen.SEARCH.name)
                                },
                                onSkip = { 
                                    tourStep = 0
                                    prefs.edit { putBoolean(KEY_TOUR_COMPLETE, true) }
                                },
                                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 200.dp, start = 16.dp) // HIGHER
                            )
                            3 -> GuidedTourTooltip(
                                text = "Looking for something specific? The Search tab helps you find games instantly.",
                                onNext = { 
                                    tourStep = 4
                                    navController.navigate(Screen.SETTINGS.name)
                                },
                                onSkip = { 
                                    tourStep = 0
                                    prefs.edit { putBoolean(KEY_TOUR_COMPLETE, true) }
                                },
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
                            )
                            4 -> GuidedTourTooltip(
                                text = "Finally, use Settings to customize your experience and manage cores.",
                                isLast = true,
                                onNext = { 
                                    tourStep = 0
                                    prefs.edit { putBoolean(KEY_TOUR_COMPLETE, true) }
                                    navController.navigate(Screen.HOME.name)
                                },
                                onSkip = { 
                                    tourStep = 0
                                    prefs.edit { putBoolean(KEY_TOUR_COMPLETE, true) }
                                },
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }
}
