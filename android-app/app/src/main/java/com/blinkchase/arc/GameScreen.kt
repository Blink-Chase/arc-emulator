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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var isNavigatingToMapper by remember { mutableStateOf(false) }
    var wasPlayingBeforeNavigation by rememberSaveable { mutableStateOf(false) }

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
                visualStyle = VisualStyle.entries[prefs.getInt("control_visual_style", 0)],
                opacity = prefs.getFloat("control_opacity", 0.7f),
                buttonSize = prefs.getFloat("control_button_size", 1.0f),
                hapticFeedback = prefs.getBoolean("control_haptic", true),
                autoHideDelay = prefs.getInt("control_auto_hide", 0),
                portraitGameRatio = prefs.getFloat("control_portrait_game_ratio", 0.45f),
                portraitAlignment = VerticalAlignment.entries[prefs.getInt("control_portrait_alignment", 1)]
            )
        )
    }
    
    // Auto-hide controls timer
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Performance state
    var fps by remember { mutableIntStateOf(0) }
    var speed by remember { mutableIntStateOf(0) }

    // Unified Performance & Watchdog Logic
    LaunchedEffect(isGameLoaded) {
        if (isGameLoaded) {
            var lastSamples = mainActivity?.samplesWritten?.get() ?: 0L
            var lastTime = System.nanoTime()
            var zeroSpeedCount = 0

            while (true) {
                withFrameNanos { now ->
                    val elapsed = now - lastTime
                    if (elapsed >= 500_000_000) {
                        fps = mainActivity?.getNativeFps() ?: 0
                        val currentSamples = mainActivity?.samplesWritten?.get() ?: 0L
                        val diff = currentSamples - lastSamples
                        val rate = mainActivity?.targetSampleRate ?: 44100
                        speed = (diff.toFloat() / rate * 100).toInt()
                        lastSamples = currentSamples
                        lastTime = now

                        // Watchdog (Hard Flush)
                        if (speed == 0 && !isPaused && !showMenu && !isNavigatingToMapper) {
                            zeroSpeedCount++
                            if (zeroSpeedCount >= 2) {
                                Utils.Logger.e("GameScreen", "WATCHDOG: Engine stall detected. Performing Hard Flush...")
                                gameSurfaceView?.let { view ->
                                    mainActivity?.pauseGame()
                                    mainActivity?.setSurface(null)
                                    mainActivity?.setSurface(view.holder.surface, view.width, view.height)
                                    mainActivity?.resumeGame()
                                }
                                zeroSpeedCount = 0
                            }
                        } else { zeroSpeedCount = 0 }
                    }
                }
            }
        }
    }

    val hideOnController = remember { prefs.getBoolean(MainActivity.KEY_HIDE_TOUCH_ON_CONTROLLER, true) }

    LaunchedEffect(controlsVisible, controlConfig.autoHideDelay) {
        if (controlConfig.autoHideDelay > 0 && controlsVisible && !isPaused && !showMenu) {
            while (true) {
                delay(1000)
                val elapsed = System.currentTimeMillis() - lastInteractionTime
                if (elapsed > controlConfig.autoHideDelay * 1000) { controlsVisible = false }
            }
        }
    }
    
    // Reset interaction timer on touch
    val onUserInteraction = {
        lastInteractionTime = System.currentTimeMillis()
        if (!controlsVisible) { controlsVisible = true }
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
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { 
            lifecycleOwner.lifecycle.removeObserver(observer) 
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

    // IMMEDIATE log when GameScreen composes (Grouped)
    Utils.Logger.e("GameScreen", "=== GameScreen composing for: $gamePath ===")

    // Reset loading state ONLY when the game path is actually different from the last LOADED game
    var lastLoadedPath by rememberSaveable { mutableStateOf("") }
    var surfaceKey by remember { mutableIntStateOf(0) }
    
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
                val result = GameLoader.loadGame(context, mainActivity, platform, gamePath, corePath, storageDir)
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        android.util.Log.e("GameScreen", "Game loaded successfully!")
                        isGameLoaded = true
                        // Ensure profile is re-read when returning to game
                        mainActivity?.inputManager?.refreshCurrentProfile()
                        // Force a native nudge to ensure the engine re-binds the surface
                        mainActivity?.updateNativeActivity()
                    } else {
                        android.util.Log.e("GameScreen", "Failed to load game: ${result.errorMessage}")
                        loadError = result.errorMessage
                        delay(3000)
                        onBack()
                    }
                }
            }
        }
    }

    val isAnyMenuOpen = showMenu || showControlSettings || showCheats || showSaveManager || showTemplateManager || showSaveTemplateDialog

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (!isLeaving) {
            if (isLandscape) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.align(if (controlConfig.style != InputStyle.HIDDEN) Alignment.Center else Alignment.TopCenter)
                        .then(if (controlConfig.style != InputStyle.HIDDEN) {
                            Modifier.fillMaxHeight().then(when (screenScale) {
                                ScreenScale.RATIO_4_3 -> Modifier.aspectRatio(4f / 3f)
                                ScreenScale.RATIO_16_9 -> Modifier.aspectRatio(16f / 9f)
                                ScreenScale.STRETCH -> Modifier.fillMaxWidth()
                            })
                        } else { Modifier.fillMaxWidth().aspectRatio(4f / 3f) }),
                        contentAlignment = Alignment.Center
                    ) {
                        GameViewSurface(surfaceKey, mainActivity, isGameLoaded, wasPlayingBeforeNavigation, { gameSurfaceView = it }, { isNavigatingToMapper = it }, { startGameLoading() }, onTogglePause, { wasPlayingBeforeNavigation = it }, screenScale)
                    }

                    val shouldShowTouch = (controlConfig.style != InputStyle.HIDDEN) && controlConfig.showInLandscape && !(hideOnController && (inputManager?.hasActiveController() == true))
                    if (shouldShowTouch) {
                        AnimatedVisibility(visible = controlsVisible, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }, modifier = Modifier.fillMaxSize()) {
                            ControlsOverlay(Modifier.fillMaxSize().background(Color.Transparent), platform, controlConfig, inputManager?.getActiveModel() ?: ControllerModel.GENERIC_ABXY, buttonProps, selectedButtonId, { selectedButtonId = it }, isGameLoaded, isEditingControls, true, onUpdateProps, showFF, onFastForward, { showMenu = true; if (autoPause && !isPaused) { isPaused = true; onTogglePause(true); wasPausedByMenu = true } }, onUserInteraction)
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(controlConfig.portraitGameRatio),
                        contentAlignment = when (controlConfig.portraitAlignment) {
                            VerticalAlignment.TOP -> Alignment.TopCenter
                            VerticalAlignment.CENTER -> Alignment.Center
                            VerticalAlignment.BOTTOM -> Alignment.BottomCenter
                        }
                    ) {
                        Box(modifier = Modifier.aspectRatio(4f / 3f)) {
                            GameViewSurface(surfaceKey, mainActivity, isGameLoaded, wasPlayingBeforeNavigation, { gameSurfaceView = it }, { isNavigatingToMapper = it }, { startGameLoading() }, onTogglePause, { wasPlayingBeforeNavigation = it }, screenScale)
                        }
                    }
                    val shouldShowTouch = (controlConfig.style != InputStyle.HIDDEN) && controlConfig.showInPortrait && !(hideOnController && (inputManager?.hasActiveController() == true))
                    if (shouldShowTouch) {
                        AnimatedVisibility(visible = controlsVisible, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }, modifier = Modifier.fillMaxWidth().weight(1f - controlConfig.portraitGameRatio)) {
                            ControlsOverlay(Modifier.fillMaxSize().background(Color.Black), platform, controlConfig, inputManager?.getActiveModel() ?: ControllerModel.GENERIC_ABXY, buttonProps, selectedButtonId, { selectedButtonId = it }, isGameLoaded, isEditingControls, false, onUpdateProps, showFF, onFastForward, { showMenu = true; if (autoPause && !isPaused) { isPaused = true; onTogglePause(true); wasPausedByMenu = true } }, onUserInteraction)
                        }
                    } else { Box(modifier = Modifier.fillMaxWidth().weight(1f - controlConfig.portraitGameRatio).background(Color.Black)) }
                }
            }
        }

        // Loading overlay
        if (!isGameLoaded) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (loadError != null) {
                        Icon(Icons.Default.Warning, "Error", tint = Color.Red, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Error: $loadError", color = Color.Red)
                    } else {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(64.dp), strokeWidth = 4.dp)
                        Spacer(Modifier.height(24.dp))
                        Text("Loading game...", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        if (isGameLoaded) { FpsSpeedOverlay(fps, speed, Modifier.align(Alignment.TopStart).padding(12.dp)) }

        if (!controlsVisible && isGameLoaded && !isAnyMenuOpen) {
            IconButton(onClick = { showMenu = true; if (autoPause && !isPaused) { isPaused = true; onTogglePause(true); wasPausedByMenu = true } }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))) {
                Icon(Icons.Default.Menu, "Menu", tint = Color.White)
            }
        }
        
        if (isLandscape && !controlsVisible && isGameLoaded) {
            Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onUserInteraction() })
        }

        if (isEditingControls && selectedButtonId != null) {
            val currentProps = buttonProps[selectedButtonId] ?: ButtonProps()
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f), RoundedCornerShape(12.dp)).padding(16.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Selected Button ID: $selectedButtonId", style = MaterialTheme.typography.labelSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Scale, null, modifier = Modifier.size(16.dp))
                        Slider(value = currentProps.scale, onValueChange = { onUpdateProps(selectedButtonId!!, currentProps.copy(scale = it)) }, valueRange = 0.5f..2.5f, modifier = Modifier.width(120.dp))
                        Text("${(currentProps.scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Opacity, null, modifier = Modifier.size(16.dp))
                        Slider(value = currentProps.alpha, onValueChange = { onUpdateProps(selectedButtonId!!, currentProps.copy(alpha = it)) }, valueRange = 0.1f..1.0f, modifier = Modifier.width(120.dp))
                        Text("${(currentProps.alpha * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = { selectedButtonId = null }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) { Text("Deselect", fontSize = 12.sp) }
                }
            }
        }
        
        if (isAnyMenuOpen || !isGameLoaded) {
            Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent() } } })
        }
    }

    if (showMenu) {
        GameMenuDialog(
            isPaused = isPaused,
            isEditingControls = isEditingControls,
            screenScale = screenScale,
            onScreenScaleChange = { screenScale = it; prefs.edit().putInt(MainActivity.KEY_SCREEN_SCALE, it.ordinal).apply() },
            onDismiss = { showMenu = false; resumeIfAutoPaused() },
            onResume = { isPaused = false; onTogglePause(false); wasPausedByMenu = false; showMenu = false },
            onToggleEditControls = { isEditingControls = !isEditingControls },
            onControlSettings = { showControlSettings = true; showMenu = false },
            onControllerMapping = { showMenu = false; isNavigatingToMapper = true; wasPlayingBeforeNavigation = !isPaused || wasPausedByMenu; mainActivity?.pauseGame(); mainActivity?.setSurface(null); onControllerMapping() },
            onResetControls = onResetControls,
            onReset = { onReset(); showMenu = false; resumeIfAutoPaused() },
            onScreenshot = { gameSurfaceView?.let { view -> Utils.saveScreenshotToGallery(context, view) }; showMenu = false; resumeIfAutoPaused() },
            onCheats = { showCheats = true; showMenu = false },
            onSaveStates = { showSaveManager = true; showMenu = false },
            onSaveTemplate = { showSaveTemplateDialog = true; showMenu = false },
            onApplyTemplate = { showTemplateManager = true; showMenu = false },
            onQuit = { showMenu = false; scope.launch(Dispatchers.IO) { onQuit(); withContext(Dispatchers.Main) { isLeaving = true; onBack() } } }
        )
    }

    if (showControlSettings) {
        ControlSettingsDialog(
            config = controlConfig,
            onUpdate = { newConfig ->
                controlConfig = newConfig
                prefs.edit {
                    putInt(MainActivity.KEY_CONTROLLER_STYLE, newConfig.style.ordinal)
                    putInt("control_visual_style", newConfig.visualStyle.ordinal)
                    putFloat("control_opacity", newConfig.opacity)
                    putFloat("control_button_size", newConfig.buttonSize)
                    putBoolean("control_haptic", newConfig.hapticFeedback)
                    putInt("control_auto_hide", newConfig.autoHideDelay)
                    putFloat("control_portrait_game_ratio", newConfig.portraitGameRatio)
                    putInt("control_portrait_alignment", newConfig.portraitAlignment.ordinal)
                }
            },
            onResetControls = { onResetControls(); showControlSettings = false; resumeIfAutoPaused() },
            onDismiss = { showControlSettings = false; resumeIfAutoPaused() }
        )
    }

    if (showCheats) {
        CheatManagerDialog(
            cheats = cheatsList,
            onUpdate = { updatedList ->
                cheatsList = updatedList
                cheatsFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                cheatsFile.writeText(updatedList.joinToString("\n") { "${it.name}|${it.code}|${it.enabled}" })
                updatedList.forEachIndexed { index, cheat -> try { mainActivity?.setCheat(index, cheat.enabled, cheat.code) } catch (e: Exception) {} }
            },
            onDismiss = { showCheats = false; resumeIfAutoPaused() }
        )
    }

    if (showSaveManager) {
        SaveManagerDialog(
            filesDir = savesDir,
            surfaceView = gameSurfaceView,
            onSave = onSaveState,
            onLoad = onLoadState,
            onResume = { isPaused = false; onTogglePause(false); wasPausedByMenu = false },
            onAfterLoad = { gameSurfaceView?.let { view -> mainActivity?.setSurface(view.holder.surface, view.width, view.height) } },
            onDismiss = { showSaveManager = false; resumeIfAutoPaused() }
        )
    }

    if (showSaveTemplateDialog) {
        var templateName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveTemplateDialog = false; showMenu = true },
            title = { Text("Save as Template") },
            text = { OutlinedTextField(value = templateName, onValueChange = { templateName = it }, label = { Text("Template Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    if (templateName.isNotBlank()) {
                        val templatesDir = File(storageDir, "templates").also { it.mkdirs() }
                        val file = File(templatesDir, "$templateName.template")
                        val json = org.json.JSONObject()
                        buttonProps.forEach { (id, p) ->
                            val btnJson = org.json.JSONObject()
                            btnJson.put("x", p.x); btnJson.put("y", p.y); btnJson.put("scale", p.scale); btnJson.put("alpha", p.alpha)
                            json.put(id.toString(), btnJson)
                        }
                        try { file.writeText(json.toString()) } catch (e: Exception) {}
                        showSaveTemplateDialog = false; showMenu = true
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSaveTemplateDialog = false; showMenu = true }) { Text("Cancel") } }
        )
    }

    if (showTemplateManager) {
        val templatesDir = remember { File(storageDir, "templates").also { it.mkdirs() } }
        val templateFiles = remember { templatesDir.listFiles()?.filter { it.extension == "template" } ?: emptyList() }
        AlertDialog(
            onDismissRequest = { showTemplateManager = false; showMenu = true },
            title = { Text("Apply Template") },
            text = {
                if (templateFiles.isEmpty()) { Text("No templates found.", color = Color.Gray) }
                else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(templateFiles) { file ->
                            Card(modifier = Modifier.fillMaxWidth().clickable {
                                val text = file.readText()
                                try {
                                    val json = org.json.JSONObject(text)
                                    val keys = json.keys()
                                    while (keys.hasNext()) {
                                        val key = keys.next()
                                        val id = key.toInt()
                                        val btnJson = json.getJSONObject(key)
                                        onUpdateProps(id, ButtonProps(x = btnJson.optDouble("x", 0.0).toFloat(), y = btnJson.optDouble("y", 0.0).toFloat(), scale = btnJson.optDouble("scale", 1.0).toFloat(), alpha = btnJson.optDouble("alpha", 1.0).toFloat()))
                                    }
                                    showTemplateManager = false; showMenu = true
                                } catch (e: Exception) {}
                            }) {
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
            dismissButton = { TextButton(onClick = { showTemplateManager = false; showMenu = true }) { Text("Close") } }
        )
    }
}

@Composable
private fun GameViewSurface(
    surfaceKey: Int,
    mainActivity: MainActivity?,
    isGameLoaded: Boolean,
    wasPlayingBeforeNavigation: Boolean,
    onSurfaceViewAvailable: (SurfaceView?) -> Unit,
    onNavigatingToMapper: (Boolean) -> Unit,
    onStartGameLoading: () -> Unit,
    onTogglePause: (Boolean) -> Unit,
    onWasPlayingBeforeNavigation: (Boolean) -> Unit,
    screenScale: ScreenScale
) {
    key(surfaceKey) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    onSurfaceViewAvailable(this)
                    keepScreenOn = true
                    holder.setFormat(PixelFormat.RGBX_8888)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { onNavigatingToMapper(false) }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            if (width <= 0 || height <= 0) return
                            if (isGameLoaded) {
                                mainActivity?.setSurface(holder.surface, width, height)
                                mainActivity?.updateNativeActivity()
                                if (wasPlayingBeforeNavigation) {
                                    onTogglePause(false)
                                    mainActivity?.resumeGame()
                                    onWasPlayingBeforeNavigation(false)
                                } else { mainActivity?.nativeForceNextFrame() }
                            } else {
                                mainActivity?.setSurface(holder.surface, width, height)
                                onStartGameLoading()
                            }
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) { mainActivity?.setSurface(null) }
                    })
                }
            },
            modifier = Modifier.fillMaxSize().then(
                when (screenScale) {
                    ScreenScale.RATIO_4_3 -> Modifier.aspectRatio(4f / 3f)
                    ScreenScale.RATIO_16_9 -> Modifier.aspectRatio(16f / 9f)
                    ScreenScale.STRETCH -> Modifier
                }
            )
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
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isPaused) {
                    Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Resume Game")
                    }
                }
                OutlinedButton(onClick = onToggleEditControls, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (isEditingControls) Icons.Default.Check else Icons.Default.Edit, null)
                    Spacer(Modifier.width(8.dp)); Text(if (isEditingControls) "Finish Editing Controls" else "Edit Controls")
                }
                OutlinedButton(
                    onClick = {
                        val nextOrdinal = (screenScale.ordinal + 1) % ScreenScale.entries.size
                        onScreenScaleChange(ScreenScale.entries[nextOrdinal])
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AspectRatio, null); Spacer(Modifier.width(8.dp)); Text("Screen Scale: ${when(screenScale) {
                        ScreenScale.RATIO_4_3 -> "4:3"
                        ScreenScale.RATIO_16_9 -> "16:9"
                        ScreenScale.STRETCH -> "Stretch"
                    }}")
                }
                OutlinedButton(onClick = onControlSettings, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Settings, null); Spacer(Modifier.width(8.dp)); Text("Control Settings")
                }
                OutlinedButton(onClick = onControllerMapping, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Gamepad, null); Spacer(Modifier.width(8.dp)); Text("Controller Mapping")
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Reset Game")
                }
                OutlinedButton(onClick = onScreenshot, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Create, null); Spacer(Modifier.width(8.dp)); Text("Screenshot")
                }
                OutlinedButton(onClick = onCheats, modifier = Modifier.fillMaxWidth()) {
                    Text("⚡", fontSize = 18.sp); Spacer(Modifier.width(8.dp)); Text("Cheats")
                }
                OutlinedButton(onClick = onSaveStates, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp)); Text("Save States")
                }
                OutlinedButton(onClick = onSaveTemplate, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Save as Template")
                }
                OutlinedButton(onClick = onApplyTemplate, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Dashboard, null); Spacer(Modifier.width(8.dp)); Text("Apply Template")
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Button(onClick = onQuit, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, null); Spacer(Modifier.width(8.dp)); Text("Quit Game")
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
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onResetControls, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Reset Button Positions")
                }
                HorizontalDivider()
                Text("Button Opacity: ${(currentConfig.opacity * 100).roundToInt()}%")
                Slider(value = currentConfig.opacity, onValueChange = { currentConfig = currentConfig.copy(opacity = it) }, valueRange = 0.1f..1.0f, steps = 8)
                Text("Button Size: ${(currentConfig.buttonSize * 100).roundToInt()}%")
                Slider(value = currentConfig.buttonSize, onValueChange = { currentConfig = currentConfig.copy(buttonSize = it) }, valueRange = 0.5f..1.5f, steps = 9)
                Text("Auto-hide Delay: ${if (currentConfig.autoHideDelay == 0) "Never" else "${currentConfig.autoHideDelay}s"}")
                Slider(value = currentConfig.autoHideDelay.toFloat(), onValueChange = { currentConfig = currentConfig.copy(autoHideDelay = it.roundToInt()) }, valueRange = 0f..10f, steps = 9)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Haptic Feedback"); Switch(checked = currentConfig.hapticFeedback, onCheckedChange = { currentConfig = currentConfig.copy(hapticFeedback = it) })
                }
                HorizontalDivider()
                Text("Visual Style", style = MaterialTheme.typography.titleSmall)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VisualStyle.entries.forEach { style ->
                        FilterChip(selected = currentConfig.visualStyle == style, onClick = { currentConfig = currentConfig.copy(visualStyle = style) }, label = { Text(style.name.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = Modifier.weight(1f))
                    }
                }
                HorizontalDivider()
                Text("Portrait Layout", style = MaterialTheme.typography.titleSmall)
                Text("Game Screen Size: ${(currentConfig.portraitGameRatio * 100).roundToInt()}%")
                Slider(value = currentConfig.portraitGameRatio, onValueChange = { currentConfig = currentConfig.copy(portraitGameRatio = it) }, valueRange = 0.2f..0.6f, steps = 8)
                Text("Vertical Alignment")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    VerticalAlignment.entries.forEach { align ->
                        FilterChip(selected = currentConfig.portraitAlignment == align, onClick = { currentConfig = currentConfig.copy(portraitAlignment = align) }, label = { Text(align.name.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = Modifier.weight(1f))
                    }
                }
                HorizontalDivider()
                Text("Control Style", style = MaterialTheme.typography.titleSmall)
                InputStyle.entries.forEach { style ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { currentConfig = currentConfig.copy(style = style) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = currentConfig.style == style, onClick = { currentConfig = currentConfig.copy(style = style) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(style.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium)
                            Text(when (style) {
                                InputStyle.STANDARD -> "Full opacity, normal size"
                                InputStyle.COMPACT -> "Smaller buttons for small screens"
                                InputStyle.MINIMALIST -> "Reduced opacity, smaller buttons"
                                InputStyle.TRANSPARENT -> "Very transparent, minimal visual impact"
                                InputStyle.HIDDEN -> "No on-screen controls"
                            }, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onUpdate(currentConfig); onDismiss() }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FpsSpeedOverlay(
    fps: Int,
    speed: Int,
    modifier: Modifier = Modifier
) {
    Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Text(text = "FPS: $fps | Speed: $speed%", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
