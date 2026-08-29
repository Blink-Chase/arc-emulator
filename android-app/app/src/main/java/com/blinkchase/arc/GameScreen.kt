package com.blinkchase.arc

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
fun GameScreen(
    gameName: String,
    platform: Platform,
    gamePath: String,
    corePath: String,
    storageDir: File,
    savesDir: File,
    layoutsDir: File,
    buttonProps: Map<Int, ButtonProps>,
    onUpdateProps: (Int, ButtonProps) -> Unit,
    onResetControls: () -> Unit,
    onBack: () -> Unit,
    onTogglePause: (Boolean) -> Unit,
    onSaveState: (String) -> Boolean,
    onLoadState: (String) -> Boolean,
    onReset: () -> Unit,
    onFastForward: (Boolean) -> Unit,
    onControllerMapping: () -> Unit = {},
    onQuit: () -> Unit = {},
    inputManager: InputManager? = null
) {
    val context = LocalContext.current
    val mainActivity = context as? MainActivity
    val prefs = remember { context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE) }
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    
    // orientation check
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    
    // IMMORTAL UI STATE - Surivives rotations and backgrounding
    var isPaused by rememberSaveable { mutableStateOf(false) }
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var isGameLoaded by rememberSaveable { mutableStateOf(false) }
    var loadAttempted by rememberSaveable { mutableStateOf(false) }
    var wasPausedByMenu by rememberSaveable { mutableStateOf(false) }

    var isEditingControls by rememberSaveable { mutableStateOf(false) }
    var showCheats by rememberSaveable { mutableStateOf(false) }
    var showSaveManager by rememberSaveable { mutableStateOf(false) }
    var showControlSettings by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var gameSurfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isLeaving by remember { mutableStateOf(false) }
    var selectedButtonId by remember { mutableStateOf<Int?>(null) }
    var showTemplateManager by rememberSaveable { mutableStateOf(false) }
    var showSaveTemplateDialog by rememberSaveable { mutableStateOf(false) }
    
    var screenScale by remember {
        mutableStateOf(ScreenScale.entries[prefs.getInt(MainActivity.KEY_SCREEN_SCALE, 0)])
    }
    
    // Control layout configuration
    var controlConfig by remember {
        mutableStateOf(
            ControlLayoutConfig(
                style = InputStyle.entries[prefs.getInt(MainActivity.KEY_CONTROLLER_STYLE, 0)],
                opacity = prefs.getFloat("control_opacity", 0.7f),
                buttonSize = prefs.getFloat("control_button_size", 1.0f),
                hapticFeedback = prefs.getBoolean("control_haptic", true),
                autoHideDelay = prefs.getInt("control_auto_hide", 0)
            )
        )
    }
    
    // Auto-hide controls timer
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    
    val hideOnController = remember { prefs.getBoolean(MainActivity.KEY_HIDE_TOUCH_ON_CONTROLLER, true) }

    LaunchedEffect(controlsVisible, controlConfig.autoHideDelay) {
        if (controlConfig.autoHideDelay > 0 && controlsVisible && !isPaused && !showMenu) {
            while (true) {
                delay(1000)
                val elapsed = System.currentTimeMillis() - lastInteractionTime
                if (elapsed > controlConfig.autoHideDelay * 1000) {
                    controlsVisible = false
                }
            }
        }
    }
    
    // Reset interaction timer on touch
    val onUserInteraction = {
        lastInteractionTime = System.currentTimeMillis()
        if (!controlsVisible) {
            controlsVisible = true
        }
    }

    val showFF = remember { prefs.getBoolean(MainActivity.KEY_SHOW_FF, true) }
    val autoPause = remember { prefs.getBoolean(MainActivity.KEY_AUTO_PAUSE_MENU, true) }

    // Cheats
    val cheatsFile = File(storageDir, "cheats/${gameName.replace(" ", "_")}.cht")
    var cheatsList by remember { mutableStateOf(mutableListOf<GameCheat>()) }

    // Auto-pause on lifecycle
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                if (autoPause && !isPaused) {
                    isPaused = true
                    onTogglePause(true)
                    wasPausedByMenu = true
                    showMenu = true
                }
            } else if (event == Lifecycle.Event.ON_RESUME) {
                // Ensure profile is re-read when returning to game
                mainActivity?.inputManager?.refreshCurrentProfile()
                
                // If a surface already exists and is valid, resume immediately
                gameSurfaceView?.holder?.surface?.let { surf ->
                    if (surf.isValid) {
                        // FORCE RESET native surface to clear error states
                        mainActivity?.setSurface(null)
                        mainActivity?.setSurface(surf)
                        if (!isPaused && !showMenu) {
                            mainActivity?.resumeGame()
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { 
            lifecycleOwner.lifecycle.removeObserver(observer) 
            // ASYNC CLEANUP: We use the context's activity scope to ensure this completes
            // even after the GameScreen is destroyed.
            if (isGameLoaded) {
                android.util.Log.d("GameScreen", "Screen disposed - parking engine async.")
                mainActivity?.let { activity ->
                    activity.lifecycleScope.launch(Dispatchers.Default) {
                        activity.pauseGame()
                        activity.setSurface(null)
                    }
                }
            }
        }
    }

    fun resumeIfAutoPaused() {
        if (wasPausedByMenu) {
            isPaused = false
            onTogglePause(false)
            wasPausedByMenu = false
        }
    }

    // Load cheats
    LaunchedEffect(Unit) {
        if (cheatsFile.exists()) {
            val loaded = cheatsFile.readLines().mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size == 3) GameCheat(parts[0], parts[1], parts[2].toBoolean()) else null
            }.toMutableList()
            cheatsList = loaded
        }
    }

    // Loading coroutine scope
    val loadingScope = rememberCoroutineScope()
    
    // IMMEDIATE log when GameScreen composes
    android.util.Log.e("GameScreen", "=== GameScreen composing for: $gamePath ===")
    
    // Reset loading state ONLY when the game path is actually different from the last LOADED game
    var lastLoadedPath by rememberSaveable { mutableStateOf("") }
    
    LaunchedEffect(gamePath) {
        // ONLY reset if we actually have a NEW path. 
        // Ignore empty paths during navigation transitions.
        if (gamePath.isNotEmpty() && gamePath != lastLoadedPath) {
            android.util.Log.e("GameScreen", "gamePath changed: $gamePath")
            isGameLoaded = false
            loadAttempted = false
            loadError = null
        }
    }

    // Function to start loading - called from Surface callback
    fun startGameLoading() {
        if (!isGameLoaded && !loadAttempted && gamePath.isNotEmpty()) {
            android.util.Log.e("GameScreen", "Starting game load for: $gamePath")
            loadAttempted = true
            lastLoadedPath = gamePath
            loadingScope.launch(Dispatchers.IO) {
                val result = GameLoader.loadGame(
                    context = context,
                    mainActivity = mainActivity,
                    platform = platform,
                    gamePath = gamePath,
                    preferredCorePath = corePath,
                    storageDir = storageDir
                )

                withContext(Dispatchers.Main) {
                    if (result.success) {
                        android.util.Log.e("GameScreen", "Game loaded successfully!")
                        isGameLoaded = true
                        // Ensure profile is re-read when returning to game
                        mainActivity?.inputManager?.refreshCurrentProfile()
                        // Force a native nudge to ensure the engine re-binds the surface
                        mainActivity?.updateNativeActivity()
                    } else {
                        android.util.Log.e("GameScreen", "Game load failed: ${result.errorMessage}")
                        loadError = result.errorMessage
                        delay(3000)
                        onBack()
                    }
                }
            }
        }
    }

    // Main layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (!isLeaving) {
            // Game view - takes full screen in landscape with overlay controls
            Box(
                modifier = Modifier
                    .align(if (isLandscape && controlConfig.style != InputStyle.HIDDEN) Alignment.Center else Alignment.TopCenter)
                    .then(
                        if (isLandscape && controlConfig.style != InputStyle.HIDDEN) {
                            Modifier
                                .fillMaxHeight()
                                .then(
                                    when (screenScale) {
                                        ScreenScale.RATIO_4_3 -> Modifier.aspectRatio(4f / 3f)
                                        ScreenScale.RATIO_16_9 -> Modifier.aspectRatio(16f / 9f)
                                        ScreenScale.STRETCH -> Modifier.fillMaxWidth()
                                    }
                                )
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(4f / 3f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Keep the SurfaceView completely stable. No more recreation keys.
                AndroidView(
                    factory = { ctx ->
                        android.util.Log.d("GameScreen", "Creating stable SurfaceView for: $gamePath")
                        SurfaceView(ctx).apply {
                            gameSurfaceView = this
                            keepScreenOn = true
                            holder.setFormat(PixelFormat.RGBX_8888)
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    android.util.Log.e("GameScreen", "Surface valid, re-syncing native engine...")
                                    
                                    // FORCE NULL THEN BIND to clear native error states
                                    mainActivity?.setSurface(null)
                                    mainActivity?.setSurface(holder.surface)
                                    
                                    if (isGameLoaded) {
                                        // RETURN HANDSHAKE:
                                        // Wait for transition to finish, then force a native frame draw
                                        scope.launch {
                                            delay(150) 
                                            mainActivity?.updateNativeActivity()
                                            if (showMenu || isPaused) {
                                                mainActivity?.nativeResumeGame()
                                                delay(50) // Give the N64 thread time to cycle
                                                mainActivity?.nativePauseGame()
                                            }
                                        }
                                        if (!isPaused && !showMenu) {
                                            mainActivity?.resumeGame()
                                        }
                                    } else {
                                        startGameLoading()
                                    }
                                }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    android.util.Log.d("GameScreen", "Surface res: ${width}x${height}")
                                    if (isGameLoaded && !isPaused && !showMenu) {
                                        mainActivity?.setSurface(holder.surface)
                                    }
                                }
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    android.util.Log.d("GameScreen", "Surface destroyed, locking native engine...")
                                    // ONLY null surface if it hasn't been handled by onPause or onDispose
                                    // mainActivity?.pauseGame() // Removed to prevent double-call issues
                                    mainActivity?.setSurface(null)
                                }
                            })
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            when (screenScale) {
                                ScreenScale.RATIO_4_3 -> Modifier.aspectRatio(4f / 3f)
                                ScreenScale.RATIO_16_9 -> Modifier.aspectRatio(16f / 9f)
                                ScreenScale.STRETCH -> Modifier
                            }
                        ),
                    update = {
                        // Handle dynamic scaling without recreating the whole view
                    }
                )
            }

            // Loading overlay
            if (!isGameLoaded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (loadError != null) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Error",
                                tint = Color.Red,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Error: $loadError", color = Color.Red)
                        } else {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(64.dp),
                                strokeWidth = 4.dp
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text("Loading game...", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            // FPS counter
            if (isGameLoaded) {
                var fps by remember { mutableIntStateOf(0) }
                var speed by remember { mutableIntStateOf(0) }

                LaunchedEffect(Unit) {
                    var lastSamples = mainActivity?.samplesWritten?.get() ?: 0L
                    var frameCount = 0
                    var lastTime = System.nanoTime()

                    while (true) {
                        withFrameNanos { now ->
                            frameCount++
                            val elapsed = now - lastTime

                            if (elapsed >= 1_000_000_000) {
                                fps = frameCount
                                frameCount = 0

                                val currentSamples = mainActivity?.samplesWritten?.get() ?: 0L
                                val diff = currentSamples - lastSamples
                                val rate = mainActivity?.targetSampleRate ?: 44100
                                speed = ((diff / 2f) / rate * 100).toInt()
                                lastSamples = currentSamples
                                lastTime = now
                            }
                        }
                    }
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "FPS: $fps | Speed: $speed%",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // Quick menu button (visible when controls hidden in both orientations)
            if (!controlsVisible && isGameLoaded) {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    IconButton(
                        onClick = {
                            showMenu = true
                            if (autoPause && !isPaused) {
                                isPaused = true
                                onTogglePause(true)
                                wasPausedByMenu = true
                            }
                        },
                        modifier = Modifier
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = Color.White
                        )
                    }
                }
            }
            
            // Tap to show controls in landscape
            if (isLandscape && !controlsVisible && isGameLoaded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            onUserInteraction()
                        }
                )
            }
        }

        // Controls - Overlay in landscape, below screen in portrait
        val shouldShowTouch = !isLeaving && (controlConfig.style != InputStyle.HIDDEN) &&
            ((isLandscape && controlConfig.showInLandscape) || (!isLandscape && controlConfig.showInPortrait)) &&
            !(hideOnController && (inputManager?.hasActiveController() == true))

        if (shouldShowTouch) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = if (isLandscape) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .fillMaxHeight(0.45f)
                }
            ) {
                ControlsOverlay(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isLandscape) {
                                Modifier.background(Color.Transparent)
                            } else {
                                Modifier.background(Color.DarkGray.copy(alpha = 0.95f))
                            }
                        ),
                    platform = platform,
                    config = controlConfig,
                    model = inputManager?.getActiveModel() ?: ControllerModel.GENERIC_ABXY,
                    buttonProps = buttonProps,
                    selectedButtonId = selectedButtonId,
                    onSelectButton = { selectedButtonId = it },
                    enabled = isGameLoaded,
                    isEditing = isEditingControls,
                    isLandscape = isLandscape,
                    onUpdateProps = onUpdateProps,
                    showFF = showFF,
                    onFastForward = onFastForward,
                    onMenuClick = {
                        showMenu = true
                        if (autoPause && !isPaused) {
                            isPaused = true
                            onTogglePause(true)
                            wasPausedByMenu = true
                        }
                    },
                    onInteraction = onUserInteraction
                )
            }
        }

        // TOUCH EATER OVERLAY: Swallows all touch events when menu is open or game is loading.
        // This stops the OS from spamming "ViewPostIme" logs and starving the native thread.
        if (showMenu || !isGameLoaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Consume all events
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent()
                            }
                        }
                    }
            )
        }
    }

    // Game Menu Dialog
    if (showMenu) {
        GameMenuDialog(
            isPaused = isPaused,
            isEditingControls = isEditingControls,
            screenScale = screenScale,
            onScreenScaleChange = { newScale ->
                screenScale = newScale
                prefs.edit().putInt(MainActivity.KEY_SCREEN_SCALE, newScale.ordinal).apply()
            },
            onDismiss = { showMenu = false; resumeIfAutoPaused() },
            onResume = {
                isPaused = false
                onTogglePause(false)
                wasPausedByMenu = false
                showMenu = false
            },
            onToggleEditControls = {
                isEditingControls = !isEditingControls
                showMenu = false
                resumeIfAutoPaused()
            },
            onControlSettings = {
                showControlSettings = true
                showMenu = false
            },
            onControllerMapping = {
                // IMPORTANT: Stop the engine rendering thread before navigating
                // to the Mapper. This makes the transition instant.
                mainActivity?.pauseGame()
                onControllerMapping()
            },
            onResetControls = onResetControls,
            onReset = {
                onReset()
                showMenu = false
                resumeIfAutoPaused()
            },
            onScreenshot = {
                // Capture the bitmap from the SurfaceView (requires PixelCopy API on Android O+)
                gameSurfaceView?.let { view ->
                    Utils.saveScreenshotToGallery(context, view)
                }
                showMenu = false
                resumeIfAutoPaused()
            },
            onCheats = {
                showCheats = true
                showMenu = false
            },
            onSaveStates = {
                showSaveManager = true
                showMenu = false
            },
            onSaveTemplate = {
                showSaveTemplateDialog = true
                showMenu = false
            },
            onApplyTemplate = {
                showTemplateManager = true
                showMenu = false
            },
            onQuit = {
                showMenu = false
                // STOP EMULATION FIRST in background while view still exists
                scope.launch(Dispatchers.IO) {
                    onQuit() // Call the callback instead of mainActivity directly
                    withContext(Dispatchers.Main) {
                        isLeaving = true // Now safe to remove view
                        onBack()
                    }
                }
            }
        )
    }

    // Control Settings Dialog
    if (showControlSettings) {
        ControlSettingsDialog(
            config = controlConfig,
            onUpdate = { newConfig ->
                controlConfig = newConfig
                // Save preferences
                prefs.edit {
                    putFloat("control_opacity", newConfig.opacity)
                    putFloat("control_button_size", newConfig.buttonSize)
                    putBoolean("control_haptic", newConfig.hapticFeedback)
                    putInt("control_auto_hide", newConfig.autoHideDelay)
                }
            },
            onResetControls = {
                onResetControls()
                showControlSettings = false
            },
            onDismiss = { showControlSettings = false; resumeIfAutoPaused() }
        )
    }

    // Cheats Dialog
    if (showCheats) {
        CheatManagerDialog(
            cheats = cheatsList,
            onUpdate = { updatedList ->
                cheatsList = updatedList
                cheatsFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                cheatsFile.writeText(updatedList.joinToString("\n") {
                    "${it.name}|${it.code}|${it.enabled}"
                })

                updatedList.forEachIndexed { index, cheat ->
                    try {
                        mainActivity?.setCheat(index, cheat.enabled, cheat.code)
                    } catch (e: Exception) {}
                }
            },
            onDismiss = { showCheats = false; resumeIfAutoPaused() }
        )
    }

    // Save Manager Dialog
    if (showSaveManager) {
        SaveManagerDialog(
            filesDir = savesDir,
            surfaceView = gameSurfaceView,
            onSave = onSaveState,
            onLoad = onLoadState,
            onResume = {
                isPaused = false
                onTogglePause(false)
                wasPausedByMenu = false
            },
            onAfterLoad = {
                gameSurfaceView?.let { surface ->
                    mainActivity?.setSurface(surface.holder?.surface)
                }
            },
            onDismiss = { showSaveManager = false; resumeIfAutoPaused() }
        )
    }

    if (showSaveTemplateDialog) {
        var templateName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveTemplateDialog = false; showMenu = true }, // Back to menu
            title = { Text("Save as Template") },
            text = {
                OutlinedTextField(
                    value = templateName,
                    onValueChange = { templateName = it },
                    label = { Text("Template Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (templateName.isNotBlank()) {
                            val templatesDir = File(storageDir, "templates").also { it.mkdirs() }
                            val file = File(templatesDir, "$templateName.template")
                            
                            val json = org.json.JSONObject()
                            buttonProps.forEach { (id, p) ->
                                val btnJson = org.json.JSONObject()
                                btnJson.put("x", p.x)
                                btnJson.put("y", p.y)
                                btnJson.put("scale", p.scale)
                                btnJson.put("alpha", p.alpha)
                                json.put(id.toString(), btnJson)
                            }
                            try { file.writeText(json.toString()) } catch (e: Exception) {}
                            
                            showSaveTemplateDialog = false
                            showMenu = true // Back to menu
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveTemplateDialog = false; showMenu = true }) { Text("Cancel") } // Back to menu
            }
        )
    }

    if (showTemplateManager) {
        val templatesDir = remember { File(storageDir, "templates").also { it.mkdirs() } }
        val templateFiles = remember { templatesDir.listFiles()?.filter { it.extension == "template" } ?: emptyList() }
        
        AlertDialog(
            onDismissRequest = { showTemplateManager = false; showMenu = true }, // Back to menu
            title = { Text("Apply Template") },
            text = {
                if (templateFiles.isEmpty()) {
                    Text("No templates found. Save a layout as a template first.", color = Color.Gray)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(templateFiles) { file ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val text = file.readText()
                                        val result = mutableMapOf<Int, ButtonProps>()
                                        try {
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
                                            // Apply to current game
                                            result.forEach { (id, props) -> onUpdateProps(id, props) }
                                            showTemplateManager = false
                                            showMenu = true // Back to menu
                                        } catch (e: Exception) {}
                                    }
                            ) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Dashboard, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Text(file.nameWithoutExtension)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTemplateManager = false; showMenu = true }) { Text("Close") } // Back to menu
            }
        )
    }
}

@Composable
fun GameMenuDialog(
    isPaused: Boolean,
    isEditingControls: Boolean,
    screenScale: ScreenScale,
    onScreenScaleChange: (ScreenScale) -> Unit,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
    onToggleEditControls: () -> Unit,
    onResetControls: () -> Unit,
    onControlSettings: () -> Unit,
    onControllerMapping: () -> Unit = {},
    onReset: () -> Unit,
    onScreenshot: () -> Unit,
    onCheats: () -> Unit,
    onSaveStates: () -> Unit,
    onSaveTemplate: () -> Unit,
    onApplyTemplate: () -> Unit,
    onQuit: () -> Unit
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Game Menu") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isPaused) {
                    Button(
                        onClick = onResume,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Resume Game")
                    }
                }

                OutlinedButton(
                    onClick = onToggleEditControls,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (isEditingControls) Icons.Default.Check else Icons.Default.Edit,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isEditingControls) "Finish Editing Controls" else "Edit Controls")
                }

                OutlinedButton(
                    onClick = {
                        val nextOrdinal = (screenScale.ordinal + 1) % ScreenScale.entries.size
                        onScreenScaleChange(ScreenScale.entries[nextOrdinal])
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AspectRatio, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Screen Scale: ${when(screenScale) {
                        ScreenScale.RATIO_4_3 -> "4:3"
                        ScreenScale.RATIO_16_9 -> "16:9"
                        ScreenScale.STRETCH -> "Stretch"
                    }}")
                }

                OutlinedButton(
                    onClick = onControlSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Control Settings")
                }

                OutlinedButton(
                    onClick = onControllerMapping,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Gamepad, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Controller Mapping")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset Game")
                }

                OutlinedButton(
                    onClick = onScreenshot,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Create, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Screenshot")
                }

                OutlinedButton(
                    onClick = onCheats,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("⚡", fontSize = MaterialTheme.typography.titleMedium.fontSize)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cheats")
                }

                OutlinedButton(
                    onClick = onSaveStates,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save States")
                }

                OutlinedButton(
                    onClick = onSaveTemplate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save as Template")
                }

                OutlinedButton(
                    onClick = onApplyTemplate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Dashboard, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Apply Template")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Button(
                    onClick = onQuit,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Quit Game")
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun ControlSettingsDialog(
    config: ControlLayoutConfig,
    onUpdate: (ControlLayoutConfig) -> Unit,
    onResetControls: () -> Unit,
    onDismiss: () -> Unit
) {
    var currentConfig by remember { mutableStateOf(config) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Control Settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Reset Layout Button
                OutlinedButton(
                    onClick = onResetControls,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reset Button Positions")
                }

                HorizontalDivider()

                // Opacity slider
                Text("Button Opacity: ${(currentConfig.opacity * 100).roundToInt()}%")
                Slider(
                    value = currentConfig.opacity,
                    onValueChange = { currentConfig = currentConfig.copy(opacity = it) },
                    valueRange = 0.1f..1.0f,
                    steps = 8
                )

                // Button size slider
                Text("Button Size: ${(currentConfig.buttonSize * 100).roundToInt()}%")
                Slider(
                    value = currentConfig.buttonSize,
                    onValueChange = { currentConfig = currentConfig.copy(buttonSize = it) },
                    valueRange = 0.5f..1.5f,
                    steps = 9
                )

                // Auto-hide slider
                Text("Auto-hide Delay: ${if (currentConfig.autoHideDelay == 0) "Never" else "${currentConfig.autoHideDelay}s"}")
                Slider(
                    value = currentConfig.autoHideDelay.toFloat(),
                    onValueChange = { currentConfig = currentConfig.copy(autoHideDelay = it.roundToInt()) },
                    valueRange = 0f..10f,
                    steps = 9
                )

                // Haptic feedback toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Haptic Feedback")
                    Switch(
                        checked = currentConfig.hapticFeedback,
                        onCheckedChange = { currentConfig = currentConfig.copy(hapticFeedback = it) }
                    )
                }

                HorizontalDivider()

                // Style selection
                Text("Control Style", style = MaterialTheme.typography.titleSmall)
                InputStyle.entries.forEach { style ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { currentConfig = currentConfig.copy(style = style) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentConfig.style == style,
                            onClick = { currentConfig = currentConfig.copy(style = style) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                style.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                when (style) {
                                    InputStyle.STANDARD -> "Full opacity, normal size"
                                    InputStyle.COMPACT -> "Smaller buttons for small screens"
                                    InputStyle.MINIMALIST -> "Reduced opacity, smaller buttons"
                                    InputStyle.TRANSPARENT -> "Very transparent, minimal visual impact"
                                    InputStyle.HIDDEN -> "No on-screen controls"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onUpdate(currentConfig)
                    onDismiss()
                }
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
