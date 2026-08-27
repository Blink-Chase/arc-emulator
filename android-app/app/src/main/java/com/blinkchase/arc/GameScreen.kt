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
    buttonOffsets: Map<Int, Offset>,
    onUpdateOffset: (Int, Offset) -> Unit,
    onResetControls: () -> Unit,
    onBack: () -> Unit,
    onTogglePause: (Boolean) -> Unit,
    onSaveState: (String) -> Boolean,
    onLoadState: (String) -> Boolean,
    onReset: () -> Unit,
    onFastForward: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val mainActivity = context as? MainActivity
    val prefs = remember { context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE) }
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    
    // Check orientation
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    
    // State preservation across rotation
    var isPaused by rememberSaveable { mutableStateOf(false) }
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var isEditingControls by rememberSaveable { mutableStateOf(false) }
    var wasPausedByMenu by rememberSaveable { mutableStateOf(false) }
    var showCheats by rememberSaveable { mutableStateOf(false) }
    var showSaveManager by rememberSaveable { mutableStateOf(false) }
    var showControlSettings by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var gameSurfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isLeaving by remember { mutableStateOf(false) }
    
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
        if (!controlsVisible && controlConfig.autoHideDelay > 0) {
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
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    // Game loading states
    var isGameLoaded by remember { mutableStateOf(false) }
    var loadAttempted by remember { mutableStateOf(false) }
    
    // Loading coroutine scope
    val loadingScope = rememberCoroutineScope()
    
    // IMMEDIATE log when GameScreen composes
    android.util.Log.e("GameScreen", "=== GameScreen composing for: $gamePath ===")
    
    // Reset loading state when game changes
    LaunchedEffect(gamePath) {
        android.util.Log.e("GameScreen", "gamePath changed: $gamePath")
        isGameLoaded = false
        loadAttempted = false
        loadError = null
    }

    // Function to start loading - called from Surface callback
    fun startGameLoading() {
        if (!isGameLoaded && !loadAttempted && gamePath.isNotEmpty()) {
            android.util.Log.e("GameScreen", "Starting game load for: $gamePath")
            loadAttempted = true
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
                modifier = if (isLandscape && controlConfig.style != InputStyle.HIDDEN) {
                    Modifier
                        .fillMaxHeight()
                        .then(
                            when (screenScale) {
                                ScreenScale.RATIO_4_3 -> Modifier.aspectRatio(4f / 3f)
                                ScreenScale.RATIO_16_9 -> Modifier.aspectRatio(16f / 9f)
                                ScreenScale.STRETCH -> Modifier.fillMaxWidth()
                            }
                        )
                        .align(Alignment.Center)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f/3f)
                        .align(Alignment.TopCenter)
                },
                contentAlignment = Alignment.Center
            ) {
                // Wrap in key() to force AndroidView recreation when game changes
                key(gamePath) {
                    AndroidView(
                        factory = { ctx ->
                            android.util.Log.d("GameScreen", "Creating new SurfaceView for: $gamePath")
                            SurfaceView(ctx).apply {
                                gameSurfaceView = this
                                keepScreenOn = true
                                // Use RGBX_8888 to ensure the surface is opaque (fixes broken screenshots)
                                holder.setFormat(PixelFormat.RGBX_8888)
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        android.util.Log.e("GameScreen", "Surface created, starting game load!")
                                        mainActivity?.setSurface(holder.surface)
                                        // Start loading immediately from the callback
                                        startGameLoading()
                                    }
                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                        android.util.Log.d("GameScreen", "Surface changed: ${width}x${height}")
                                    }
                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        android.util.Log.d("GameScreen", "Surface destroyed")
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
                            )
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
                            .pointerInput(Unit) {
                                // This will capture taps to show controls
                            }
                    )
                }
            }
        }

        // Controls - Overlay in landscape, below screen in portrait
        if (controlConfig.style != InputStyle.HIDDEN && 
            ((isLandscape && controlConfig.showInLandscape) || (!isLandscape && controlConfig.showInPortrait))) {
            
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
                    buttonOffsets = buttonOffsets,
                    enabled = isGameLoaded,
                    isEditing = isEditingControls,
                    isLandscape = isLandscape,
                    onDrag = onUpdateOffset,
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
            onResetControls = {
                onResetControls()
                showMenu = false
            },
            onControlSettings = {
                showControlSettings = true
                showMenu = false
            },
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
            onQuit = {
                isLeaving = true
                showMenu = false
                
                // Save layout immediately
                val layoutFile = File(layoutsDir, "${gameName}.layout")
                val content = buttonOffsets.map { "${it.key},${it.value.x},${it.value.y}" }.joinToString("\n")
                try { layoutFile.writeText(content) } catch (e: Exception) {}

                // Trigger UI transition back to Library IMMEDIATELY
                onBack()

                // Run native cleanup in the background so it doesn't block the UI
                scope.launch(Dispatchers.IO) {
                    mainActivity?.quitGame()
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
    onReset: () -> Unit,
    onScreenshot: () -> Unit,
    onCheats: () -> Unit,
    onSaveStates: () -> Unit,
    onQuit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Game Menu") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    onClick = onResetControls,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset Controls")
                }

                OutlinedButton(
                    onClick = onControlSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Control Settings")
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
