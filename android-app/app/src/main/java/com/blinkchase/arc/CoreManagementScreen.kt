package com.blinkchase.arc

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

@Composable
fun CoreManagementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE) }
    val hapticEnabled = remember { prefs.getBoolean("control_haptic", true) }
    
    var coreItems by remember { mutableStateOf<List<CoreManager.CoreItem>>(emptyList()) }
    var installedCores by remember { mutableStateOf(CoreManager.getInstalledCores(context)) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var downloadingCoreId by remember { mutableStateOf<String?>(null) }
    var showErrorDialog by remember { mutableStateOf<String?>(null) }
    var isProfileExpanded by remember { mutableStateOf(false) }
    
    val downloadState by CoreManager.downloadState.collectAsState()
    val hardwareProfile = remember { HardwareManager.getDeviceProfile(context) }

    LaunchedEffect(Unit) {
        coreItems = CoreManager.fetchManifest()
        isLoading = false
    }

    fun refreshInstalledList() {
        installedCores = CoreManager.getInstalledCores(context)
    }

    // Handle global download state transitions for the UI
    LaunchedEffect(downloadState) {
        when (downloadState) {
            is CoreManager.DownloadState.Success -> {
                if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                downloadingCoreId = null
                refreshInstalledList()
            }
            is CoreManager.DownloadState.Error -> {
                showErrorDialog = (downloadState as CoreManager.DownloadState.Error).message
                downloadingCoreId = null
            }
            is CoreManager.DownloadState.Idle -> {
                downloadingCoreId = null
            }
            else -> {}
        }
    }

    val filteredCores = remember(selectedTab, coreItems, hardwareProfile, installedCores) {
        if (selectedTab == 0) {
            // Show exactly 1 recommended core per console as requested
            coreItems.filter { it.isRecommended }
        } else {
            // Show every single core available
            coreItems
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // PS3 XMB Style Animated Background
        XMBBackground()

        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Core Downloader",
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = onBack
            )

            // Hardware Profile Banner (Refined Activity State)
            Card(
                onClick = { isProfileExpanded = !isProfileExpanded },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val tierDisplayName = when(hardwareProfile.tier) {
                                HardwareManager.DeviceTier.LOW -> "Entry-Level"
                                HardwareManager.DeviceTier.MID -> "Mid-Range"
                                HardwareManager.DeviceTier.HIGH -> "High-End"
                            }
                            Text(
                                text = "Device Performance: $tierDisplayName",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${"%.1f".format(hardwareProfile.totalRamGb)}GB RAM • ${hardwareProfile.cpuCores} Cores",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                            )
                        }
                        Icon(
                            imageVector = if (isProfileExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = Color.Gray
                        )
                    }
                    
                    AnimatedVisibility(visible = isProfileExpanded) {
                        val tierDescription = when(hardwareProfile.tier) {
                            HardwareManager.DeviceTier.LOW -> "This device is optimized for 2D systems. 3D cores like N64 and PS1 may require reducing internal resolution or disabling shaders to maintain full speed. Complex systems such as PS2, Wii, and GameCube will likely experience severe lag and are not recommended for this hardware."
                            HardwareManager.DeviceTier.MID -> "Expect smooth performance for most 3D systems. High-end consoles like PS2 or Wii should run well at native resolutions, but you may experience frame dips if you crank up settings like 'Resolution Scale' or 'Anti-Aliasing' too high."
                            HardwareManager.DeviceTier.HIGH -> "Full speed support for extreme emulation (PS2, Wii, GameCube). This device can comfortably handle high-resolution upscaling, heavy shaders, and advanced graphics features without losing performance."
                        }
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = tierDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hardwareProfile.tier == HardwareManager.DeviceTier.LOW) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {}
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Recommended") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("All Cores") })
            }

            if (isLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(filteredCores, key = { it.coreId }) { item ->
                        CoreCard(
                            item = item,
                            isInstalled = installedCores.contains("${item.coreId}.so"),
                            isDownloading = downloadingCoreId == item.coreId,
                            downloadState = if (downloadingCoreId == item.coreId) downloadState else CoreManager.DownloadState.Idle,
                            isAnyDownloading = downloadingCoreId != null,
                            onInstall = {
                                if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                downloadingCoreId = item.coreId
                                scope.launch {
                                    CoreManager.downloadAndInstallCore(context, item)
                                }
                            },
                            onUninstall = {
                                if (CoreManager.deleteCore(context, item.coreId)) {
                                    refreshInstalledList()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showErrorDialog != null) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = null },
            title = { Text("Installation Failed") },
            text = { Text(showErrorDialog ?: "Unknown error occurred during installation.") },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = null }) {
                    Text("Dismiss")
                }
            }
        )
    }
}

@Composable
fun XMBBackground() {
    val infiniteTransition = rememberInfiniteTransition()
    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Canvas(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val width = size.width
        val height = size.height
        
        val color1 = Color(0xFF1A237E).copy(alpha = 0.12f) // Deep Blue
        val color2 = Color(0xFF0D47A1).copy(alpha = 0.08f)  // Navy
        
        for (i in 0..2) {
            val path = Path().apply {
                moveTo(0f, height * (0.4f + i * 0.12f))
                for (x in 0..width.toInt() step 15) {
                    val variation = sin((x / 180f + waveOffset * 2 * Math.PI + i * 0.7f).toFloat()) * 40f
                    lineTo(x.toFloat(), height * (0.5f + i * 0.08f) + variation)
                }
                lineTo(width, height)
                lineTo(0f, height)
                close()
            }
            drawPath(path, Brush.verticalGradient(listOf(color1, color2, Color.Transparent)))
        }
    }
}

@Composable
fun CoreCard(
    item: CoreManager.CoreItem,
    isInstalled: Boolean,
    isDownloading: Boolean,
    downloadState: CoreManager.DownloadState,
    isAnyDownloading: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit
) {
    // Dim the card if another core is downloading
    val cardAlpha by animateFloatAsState(targetValue = if (!isDownloading && isAnyDownloading) 0.4f else 1.0f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(cardAlpha),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDownloading) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) 
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        elevation = if (isDownloading) CardDefaults.cardElevation(defaultElevation = 6.dp) else CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (isDownloading) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (item.isRecommended) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.Default.Star, null, tint = Color(0xFFFFB74D), modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        text = "System: ${item.platform} • v${item.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
                
                // Morphing Button Logic
                Box(contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = when {
                            isDownloading -> "loading"
                            isInstalled -> "installed"
                            else -> "idle"
                        },
                        transitionSpec = {
                            fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                        }
                    ) { state ->
                        when (state) {
                            "loading" -> {
                                Box(
                                    modifier = Modifier.size(44.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        strokeWidth = 3.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            "installed" -> {
                                IconButton(onClick = onUninstall) {
                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            "idle" -> {
                                Button(
                                    onClick = onInstall,
                                    enabled = !isAnyDownloading,
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Install", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            // Progress Section with Waves
            if (isDownloading) {
                Spacer(modifier = Modifier.height(16.dp))
                val progress = if (downloadState is CoreManager.DownloadState.Downloading) downloadState.progress else 0f
                val statusText = when (downloadState) {
                    is CoreManager.DownloadState.Preparing -> "Connecting to buildbot..."
                    is CoreManager.DownloadState.Downloading -> "Downloading... ${(progress * 100).toInt()}%"
                    is CoreManager.DownloadState.Extracting -> "Extracting assets..."
                    is CoreManager.DownloadState.Verifying -> "Verifying architecture..."
                    else -> "Initializing..."
                }
                
                Text(statusText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(6.dp))
                WaveProgressBar(progress = progress)
            }
        }
    }
}

@Composable
fun WaveProgressBar(progress: Float) {
    val infiniteTransition = rememberInfiniteTransition()
    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        val color = MaterialTheme.colorScheme.primary
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val progressWidth = width * progress
            
            clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, width, height,
                CornerRadius(height/2)
            )) }) {
                // Background of progress
                drawRect(
                    color = color.copy(alpha = 0.2f),
                    size = Size(progressWidth, height)
                )
                
                // Draw Wave Path
                val path = Path().apply {
                    moveTo(0f, height)
                    for (x in 0..progressWidth.toInt() step 5) {
                        val y = height / 2 + sin((x / 30f + waveOffset * 2 * Math.PI).toFloat()) * 3f
                        lineTo(x.toFloat(), y)
                    }
                    lineTo(progressWidth, height)
                    close()
                }
                
                drawPath(
                    path = path,
                    color = color.copy(alpha = 0.6f)
                )
            }
        }
    }
}
