package com.blinkchase.arc

import android.content.*
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.edit
import coil.compose.AsyncImage
import com.blinkchase.arc.db.GameDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@Composable
fun ScreenHeader(
    title: String,
    navigationIcon: ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    showIdentity: Boolean = false,
    identityIcon: Any = R.mipmap.ic_launcher,
    showInfo: Boolean = false,
    onInfoClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (navigationIcon != null && onNavigationClick != null) {
                IconButton(onClick = onNavigationClick) {
                    Icon(navigationIcon, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
                }
            } else if (showIdentity) {
                AsyncImage(
                    model = identityIcon,
                    contentDescription = "App Identity",
                    modifier = Modifier
                        .height(52.dp)
                        .widthIn(min = 48.dp, max = 220.dp)
                        .padding(start = 12.dp, top = 2.dp, bottom = 2.dp, end = 8.dp),
                    contentScale = ContentScale.Fit
                )
            }
 else {
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            actions()
            if (showInfo && onInfoClick != null) {
                IconButton(onClick = onInfoClick) {
                    Icon(Icons.Default.Info, contentDescription = "About", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
fun DestructiveActionDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = MaterialTheme.colorScheme.error) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = { onConfirm(); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ArcHomeScreen(
    games: List<GameFile>,
    recentGames: List<GameFile>,
    storageDir: File,
    gameDao: GameDao,
    showExtensions: Boolean,
    prefs: SharedPreferences,
    onGameClick: (GameFile) -> Unit,
    onToggleFavorite: (GameFile) -> Unit,
    onGoToLibrary: () -> Unit,
    onGoToAbout: () -> Unit
) {
    val context = LocalContext.current
    val homeIdentity = remember { prefs.getInt(MainActivity.KEY_HOME_IDENTITY, 0) }
    
    // Choose icon based on setting
    val headerIcon = when(homeIdentity) {
        0 -> R.mipmap.retro  // Retro Feel
        2 -> R.mipmap.ic_launcher  // Modern Icon (App Logo)
        else -> Icons.Default.SportsEsports   // Fallback
    }

    // Grid Cycling State (2 -> 3 -> 4)
    var recentGridSize by remember { mutableIntStateOf(prefs.getInt(MainActivity.KEY_RECENT_GRID_SIZE, 3)) }
    var discoveryGridSize by remember { mutableIntStateOf(prefs.getInt(MainActivity.KEY_DISCOVERY_GRID_SIZE, 3)) }

    // Crash Recovery Check
    val lastCrashedCore = remember { prefs.getString(MainActivity.KEY_LAST_CRASHED_CORE, null) }
    var showCrashRecovery by remember { mutableStateOf(lastCrashedCore != null) }
    
    // Smart Crash Recovery Logic
    val hasPendingCrash = remember { prefs.getBoolean(MainActivity.KEY_CRASH_PENDING, false) }
    var showSmartCrashDialog by remember { mutableStateOf(hasPendingCrash) }
    val crashSnippet = remember { prefs.getString(MainActivity.KEY_LAST_CRASH_SNIPPET, "") ?: "" }

    if (showSmartCrashDialog) {
        AlertDialog(
            onDismissRequest = { 
                showSmartCrashDialog = false
                prefs.edit { remove(MainActivity.KEY_CRASH_PENDING) }
            },
            title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Crash Detected", color = MaterialTheme.colorScheme.error)
            }},
            text = {
                Column {
                    Text("It looks like Arc closed unexpectedly. Here is a snippet of the error:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.1f))
                        .padding(8.dp)
                    ) {
                        Text(
                            text = crashSnippet + "\n...",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("The full logs have been saved. Would you like to export them to report this issue?", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val path = LogManager.exportLogs(context)
                    if (path != null) {
                        Toast.makeText(context, "Logs exported to Documents/Arc/logs/", Toast.LENGTH_LONG).show()
                    }
                    showSmartCrashDialog = false
                    prefs.edit { remove(MainActivity.KEY_CRASH_PENDING) }
                }) {
                    Text("Export & Close")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSmartCrashDialog = false
                    prefs.edit { remove(MainActivity.KEY_CRASH_PENDING) }
                }) {
                    Text("Dismiss")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenHeader(
                title = if (homeIdentity == 1) "Arc Emulator" else "",
                showIdentity = homeIdentity != 1,
                identityIcon = headerIcon,
                showInfo = true,
                onInfoClick = onGoToAbout,
                actions = {
                    IconButton(onClick = {
                        // Cycle grid sizes for both sections
                        val next = if (recentGridSize >= 4) 2 else recentGridSize + 1
                        recentGridSize = next
                        discoveryGridSize = next
                        prefs.edit { 
                            putInt(MainActivity.KEY_RECENT_GRID_SIZE, next)
                            putInt(MainActivity.KEY_DISCOVERY_GRID_SIZE, next)
                        }
                    }) {
                        Icon(
                            imageVector = when(recentGridSize) {
                                2 -> Icons.Default.ViewModule
                                3 -> Icons.Default.GridView
                                else -> Icons.Default.ViewComfy
                            },
                            contentDescription = "Cycle Grid",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )

            if (showCrashRecovery) {
                CrashRecoveryCard(
                    onDismiss = {
                        showCrashRecovery = false
                        prefs.edit { remove(MainActivity.KEY_LAST_CRASHED_CORE) }
                    },
                    onViewWiki = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/blinkchase/arc-emulator/wiki"))
                        context.startActivity(intent)
                    }
                )
            }

            if (recentGames.isNotEmpty()) {
                SectionHeader("RECENTLY PLAYED", onAction = onGoToLibrary, actionText = "See All")
                // Use a non-scrolling grid inside the vertical scroll
                val rows = (recentGames.size + recentGridSize - 1) / recentGridSize
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (i in 0 until rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (j in 0 until recentGridSize) {
                                val index = i * recentGridSize + j
                                if (index < recentGames.size) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        GamePosterItem(game = recentGames[index], onClick = onGameClick)
                                    }
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            if (games.isNotEmpty()) {
                SectionHeader("DISCOVER", onAction = onGoToLibrary, actionText = "${games.size} Games")
                val discoveryGames = remember(games) { games.shuffled().take(12) }
                val dRows = (discoveryGames.size + discoveryGridSize - 1) / discoveryGridSize
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (i in 0 until dRows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (j in 0 until discoveryGridSize) {
                                val index = i * discoveryGridSize + j
                                if (index < discoveryGames.size) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        GamePosterItem(game = discoveryGames[index], onClick = onGameClick)
                                    }
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun SectionHeader(title: String, actionText: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
            color = MaterialTheme.colorScheme.primary
        )
        if (actionText != null && onAction != null) {
            Text(
                text = actionText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.clickable { onAction() }
            )
        }
    }
}

@Composable
fun LibraryScreen(
    games: List<GameFile>, 
    showExtensions: Boolean,
    isScanning: Boolean = false,
    prefs: SharedPreferences,
    onToggleFavorite: (GameFile) -> Unit, 
    onGameSelected: (GameFile) -> Unit,
    onRefresh: () -> Unit,
    onRefreshMetadata: (GameFile) -> Unit,
    onNavigateToImport: () -> Unit
) {
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var filterPlatform by remember { mutableStateOf<Platform?>(null) }
    var isGridView by rememberSaveable { mutableStateOf(true) }
    val viewStyle = remember { prefs.getInt(MainActivity.KEY_LIBRARY_VIEW_STYLE, 0) }
    
    val filteredGames = remember(games, filterPlatform) {
        if (filterPlatform == null) games else games.filter { it.platform == filterPlatform }
    }
    
    val sortedGames = remember(filteredGames, sortMode) {
        when (sortMode) {
            SortMode.NAME -> filteredGames.sortedBy { it.name }
            SortMode.DATE_ADDED -> filteredGames.sortedByDescending { it.dateAdded }
            SortMode.LAST_PLAYED -> filteredGames.sortedByDescending { it.lastPlayed }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Library • ${games.size}",
            actions = {
                if (isScanning) {
                    Text("Scanning...", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(16.dp))
                }
                IconButton(onClick = { onRefresh() }) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = "Sync Library",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { isGridView = isGridView.not() }) {
                    Icon(
                        if (isGridView) Icons.AutoMirrored.Filled.List else Icons.Default.GridView,
                        contentDescription = "Toggle View",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = {
                    onRefresh()
                    onNavigateToImport()
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Import", tint = MaterialTheme.colorScheme.primary)
                }
            }
        )
        
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            if (games.isEmpty() && !isScanning) {
                LibraryEmptyState(onAddGames = onNavigateToImport)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SortMode.entries.forEach { mode ->
                        FilterChip(
                            selected = sortMode == mode,
                            onClick = { sortMode = mode },
                            label = { Text(mode.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filterPlatform == null,
                        onClick = { filterPlatform = null },
                        label = { Text("All", style = MaterialTheme.typography.labelSmall) }
                    )
                    Platform.entries.filter { it != Platform.UNKNOWN }.forEach { platform ->
                        FilterChip(
                            selected = filterPlatform == platform,
                            onClick = { filterPlatform = platform },
                            label = { Text(platform.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                
                Box(modifier = Modifier.weight(1f)) {
                    if (isGridView) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (viewStyle == 1) 100.dp else 120.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(sortedGames) { game ->
                                if (viewStyle == 1) {
                                    GamePosterItem(game, onGameSelected)
                                } else {
                                    GameGridItem(
                                        game = game,
                                        onToggleFavorite = onToggleFavorite,
                                        onClick = onGameSelected,
                                        onRefreshMetadata = onRefreshMetadata,
                                        showExtensions = showExtensions
                                    )
                                }
                            }
                        }
                    } else {
                        val listState = rememberLazyListState()
                        val scope = rememberCoroutineScope()
                        
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val favorites = sortedGames.filter { it.isFavorite }
                            val others = sortedGames.filter { !it.isFavorite }

                            if (favorites.isNotEmpty()) {
                                item {
                                    Text("Favorites", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.primary)
                                }
                                items(favorites) { game ->
                                    GameListItem(
                                        game = game,
                                        onToggleFavorite = onToggleFavorite,
                                        onClick = onGameSelected,
                                        onRefreshMetadata = onRefreshMetadata,
                                        showExtensions = showExtensions
                                    )
                                }
                            }

                            if (others.isNotEmpty()) {
                                item {
                                    Text(if (favorites.isNotEmpty()) "All Games" else "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.secondary)
                                }
                                items(others) { game ->
                                    GameListItem(
                                        game = game,
                                        onToggleFavorite = onToggleFavorite,
                                        onClick = onGameSelected,
                                        onRefreshMetadata = onRefreshMetadata,
                                        showExtensions = showExtensions
                                    )
                                }
                            }
                        }
                        
                        Column(
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ('A'..'Z').forEach { letter ->
                                Text(
                                    text = letter.toString(),
                                    modifier = Modifier.clickable {
                                        val favoritesCount = if (sortedGames.any { it.isFavorite }) 1 + sortedGames.count { it.isFavorite } else 0
                                        val allGamesHeader = if (favoritesCount > 0) 1 else 0
                                        val othersBefore = sortedGames.filter { !it.isFavorite }.takeWhile { !it.name.startsWith(letter, ignoreCase = true) }.size
                                        val targetIndex = if (sortedGames.filter { !it.isFavorite }.any { it.name.startsWith(letter, ignoreCase = true) }) {
                                            favoritesCount + allGamesHeader + othersBefore
                                        } else -1
                                        if (targetIndex >= 0) { scope.launch { listState.scrollToItem(targetIndex) } }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsCategory(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp))
    }
}

@Composable
fun SettingsItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: SharedPreferences,
    rootStorageDir: File,
    gameDao: GameDao,
    games: List<GameFile>,
    onGoToAbout: () -> Unit,
    onGoToHelp: () -> Unit,
    onGoToBios: () -> Unit,
    onGoToControllerMapping: () -> Unit,
    onRefresh: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var scanMode by remember { mutableIntStateOf(prefs.getInt(MainActivity.KEY_SCAN_MODE, 0)) }
    var customPaths by remember {
        mutableStateOf(try { prefs.getStringSet(MainActivity.KEY_CUSTOM_PATHS, emptySet()) ?: emptySet() } catch (_: Exception) { emptySet<String>() })
    }
    var audioLatency by remember { mutableIntStateOf(prefs.getInt(MainActivity.KEY_AUDIO_LATENCY, 1)) }
    var nuclearLogging by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_NUCLEAR_LOGGING, true)) }
    var showFF by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_SHOW_FF, true)) }
    var showExtensions by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_SHOW_EXTENSIONS, false)) }
    var autoPauseMenu by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_AUTO_PAUSE_MENU, true)) }
    var controllerStyle by remember { mutableStateOf(InputStyle.entries[prefs.getInt(MainActivity.KEY_CONTROLLER_STYLE, 0)]) }
    var hideTouchOnController by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_HIDE_TOUCH_ON_CONTROLLER, true)) }
    var controllerDeadzone by remember { mutableFloatStateOf(prefs.getFloat(MainActivity.KEY_CONTROLLER_DEADZONE, 0.15f)) }
    var showCoreSelectorFor by remember { mutableStateOf<Platform?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val installedCores = remember(refreshKey) { Utils.scanInstalledCores(context) }
    val internalCoresDir = remember { File(context.filesDir, "cores").also { it.mkdirs() } }
    
    val coreImporter = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val fileName = Utils.getFileName(context, it)
            if (fileName != null && fileName.endsWith(".so")) {
                try {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        File(internalCoresDir, fileName).outputStream().use { output -> input.copyTo(output) }
                    }
                    Toast.makeText(context, "Imported $fileName", Toast.LENGTH_SHORT).show()
                    refreshKey++
                } catch (e: Exception) { Toast.makeText(context, "Import Failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    val pathLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val path = it.path ?: ""
            val split = path.split(":")
            if (split.size > 1) {
                val real = Environment.getExternalStorageDirectory().absolutePath + "/" + split[1]
                val new = customPaths + real
                customPaths = new
                prefs.edit { putStringSet(MainActivity.KEY_CUSTOM_PATHS, new) }
                refreshKey++
                onRefresh()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(title = "Settings", showInfo = true, onInfoClick = onGoToAbout)
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("Profile", style = MaterialTheme.typography.titleMedium)
                        Text("Version 1.5.0 (Latest)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            SettingsCategory("APPEARANCE", Icons.Default.Palette)
            var identityMode by remember { mutableIntStateOf(prefs.getInt(MainActivity.KEY_HOME_IDENTITY, 0)) }
            SettingsItem(title = "Home Identity", subtitle = when(identityMode) { 0 -> "Retro Icon"; 1 -> "App Name"; else -> "Modern Icon" }) {
                Row {
                    IconButton(onClick = { 
                        identityMode = 0
                        prefs.edit { putInt(MainActivity.KEY_HOME_IDENTITY, 0) }
                    }) { 
                        Icon(Icons.Default.Gamepad, null, tint = if (identityMode == 0) MaterialTheme.colorScheme.primary else Color.Gray) 
                    }
                    IconButton(onClick = { 
                        identityMode = 1
                        prefs.edit { putInt(MainActivity.KEY_HOME_IDENTITY, 1) }
                    }) { 
                        Icon(Icons.Default.TextFields, null, tint = if (identityMode == 1) MaterialTheme.colorScheme.primary else Color.Gray) 
                    }
                    IconButton(onClick = { 
                        identityMode = 2
                        prefs.edit { putInt(MainActivity.KEY_HOME_IDENTITY, 2) }
                    }) { 
                        Icon(Icons.Default.Apps, null, tint = if (identityMode == 2) MaterialTheme.colorScheme.primary else Color.Gray) 
                    }
                }
            }
            var themeMode by remember { mutableIntStateOf(prefs.getInt(MainActivity.KEY_THEME_MODE, 0)) }
            SettingsItem(title = "Theme Mode", subtitle = when (themeMode) { 0 -> "Follow system"; 1 -> "Light"; else -> "Dark" }) {
                Row { listOf(0, 1, 2).forEach { mode ->
                    val icon = when(mode) { 0 -> Icons.Default.BrightnessAuto; 1 -> Icons.Default.LightMode; else -> Icons.Default.DarkMode }
                    IconButton(onClick = { themeMode = mode; prefs.edit { putInt(MainActivity.KEY_THEME_MODE, mode) } }) {
                        Icon(icon, null, tint = if (themeMode == mode) MaterialTheme.colorScheme.primary else Color.Gray)
                    }
                } }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.3f))
            SettingsCategory("EMULATION", Icons.Default.SportsEsports)
            SettingsItem(title = "Fast Forward Button", subtitle = "Toggle FF overlay visibility") {
                Switch(checked = showFF, onCheckedChange = { showFF = it; prefs.edit { putBoolean(MainActivity.KEY_SHOW_FF, it) } })
            }
            var showFps by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_SHOW_FPS, true)) }
            SettingsItem(title = "FPS & Speed Counter", subtitle = "Show performance overlay in-game") {
                Switch(checked = showFps, onCheckedChange = { showFps = it; prefs.edit { putBoolean(MainActivity.KEY_SHOW_FPS, it) } })
            }
            SettingsItem(title = "Show File Extensions", subtitle = "Display .sfc, .gba, etc. in library") {
                Switch(checked = showExtensions, onCheckedChange = { showExtensions = it; prefs.edit { putBoolean(MainActivity.KEY_SHOW_EXTENSIONS, it) } })
            }
            SettingsItem(title = "Auto-Pause", subtitle = "Pause game when menu is open") {
                Switch(checked = autoPauseMenu, onCheckedChange = { autoPauseMenu = it; prefs.edit { putBoolean(MainActivity.KEY_AUTO_PAUSE_MENU, it) } })
            }
            SettingsItem(title = "Audio Latency", subtitle = "Increase if sound crackles") {
                Slider(value = audioLatency.toFloat(), onValueChange = { audioLatency = it.roundToInt() }, onValueChangeFinished = { prefs.edit { putInt(MainActivity.KEY_AUDIO_LATENCY, audioLatency) }; (context as? MainActivity)?.resetAudio() }, valueRange = 0f..2f, steps = 1, modifier = Modifier.width(120.dp))
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.3f))
            SettingsCategory("INPUT DEVICE", Icons.Default.Gamepad)
            Button(onClick = onGoToControllerMapping, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                Icon(Icons.Default.SettingsInputComponent, null); Spacer(Modifier.width(8.dp)); Text("Setup Physical Controller")
            }
            SettingsItem(title = "Auto-Hide Touch Controls", subtitle = "Hide overlay when a controller is active") {
                Switch(checked = hideTouchOnController, onCheckedChange = { hideTouchOnController = it; prefs.edit { putBoolean(MainActivity.KEY_HIDE_TOUCH_ON_CONTROLLER, it) } })
            }
            SettingsItem(title = "Analog Deadzone", subtitle = "${(controllerDeadzone * 100).roundToInt()}%") {
                Slider(value = controllerDeadzone, onValueChange = { controllerDeadzone = it }, onValueChangeFinished = { prefs.edit { putFloat(MainActivity.KEY_CONTROLLER_DEADZONE, controllerDeadzone) } }, valueRange = 0.05f..0.5f, modifier = Modifier.width(120.dp))
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.3f))
            SettingsCategory("TOUCH CONTROLS", Icons.Default.TouchApp)
            var styleMenuExpanded by remember { mutableStateOf(false) }
            SettingsItem(title = "Style", subtitle = "Visual look of touch controls") {
                Box {
                    OutlinedButton(onClick = { styleMenuExpanded = true }) { Text(controllerStyle.name); Icon(Icons.Default.ArrowDropDown, null) }
                    DropdownMenu(expanded = styleMenuExpanded, onDismissRequest = { styleMenuExpanded = false }) {
                        InputStyle.entries.forEach { style ->
                            DropdownMenuItem(text = { Text(style.name) }, onClick = { controllerStyle = style; prefs.edit { putInt(MainActivity.KEY_CONTROLLER_STYLE, style.ordinal) }; styleMenuExpanded = false })
                        }
                    }
                }
            }
            var defaultOpacity by remember { mutableFloatStateOf(prefs.getFloat("control_opacity", 0.7f)) }
            SettingsItem(title = "Opacity", subtitle = "${(defaultOpacity * 100).roundToInt()}%") {
                Slider(value = defaultOpacity, onValueChange = { defaultOpacity = it }, onValueChangeFinished = { prefs.edit { putFloat("control_opacity", defaultOpacity) } }, valueRange = 0.1f..1.0f, modifier = Modifier.width(120.dp))
            }
            var defaultButtonSize by remember { mutableFloatStateOf(prefs.getFloat("control_button_size", 1.0f)) }
            SettingsItem(title = "Button Size", subtitle = "${(defaultButtonSize * 100).roundToInt()}%") {
                Slider(value = defaultButtonSize, onValueChange = { defaultButtonSize = it }, onValueChangeFinished = { prefs.edit { putFloat("control_button_size", defaultButtonSize) } }, valueRange = 0.5f..1.5f, modifier = Modifier.width(120.dp))
            }
            var hapticEnabled by remember { mutableStateOf(prefs.getBoolean("control_haptic", true)) }
            SettingsItem(title = "Haptic Feedback") {
                Switch(checked = hapticEnabled, onCheckedChange = { hapticEnabled = it; prefs.edit { putBoolean("control_haptic", it) } })
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.3f))
            SettingsCategory("LIBRARY & METADATA", Icons.Default.Storage)
            var libViewStyle by remember { mutableIntStateOf(prefs.getInt(MainActivity.KEY_LIBRARY_VIEW_STYLE, 0)) }
            SettingsItem(title = "Library View Style", subtitle = if (libViewStyle == 0) "Detailed Cards" else "Poster Grid") {
                Row {
                    IconButton(onClick = { libViewStyle = 0; prefs.edit { putInt(MainActivity.KEY_LIBRARY_VIEW_STYLE, 0) } }) { Icon(Icons.AutoMirrored.Filled.List, null, tint = if (libViewStyle == 0) MaterialTheme.colorScheme.primary else Color.Gray) }
                    IconButton(onClick = { libViewStyle = 1; prefs.edit { putInt(MainActivity.KEY_LIBRARY_VIEW_STYLE, 1) } }) { Icon(Icons.Default.GridView, null, tint = if (libViewStyle == 1) MaterialTheme.colorScheme.primary else Color.Gray) }
                }
            }
            SettingsItem(title = "Scan Mode", subtitle = if (scanMode == 0) "Auto (Documents)" else "Custom Folders") {
                Switch(checked = scanMode == 1, onCheckedChange = { 
                    scanMode = if (it) 1 else 0
                    prefs.edit { putInt(MainActivity.KEY_SCAN_MODE, scanMode) } 
                    onRefresh()
                })
            }
            if (scanMode == 1) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        customPaths.forEach { path ->
                            ListItem(
                                headlineContent = { Text(path.split("/").last(), style = MaterialTheme.typography.bodyMedium) },
                                supportingContent = { Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = {
                                    IconButton(onClick = { 
                                        val new = customPaths - path
                                        customPaths = new
                                        prefs.edit { putStringSet(MainActivity.KEY_CUSTOM_PATHS, new) }
                                        refreshKey++
                                        onRefresh()
                                    }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        
                        TextButton(
                            onClick = { pathLauncher.launch(null) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add Folder")
                        }
                    }
                }
            }
            var isScraping by remember { mutableStateOf(false) }
            var scrapeMessage by remember { mutableStateOf("Download missing game posters") }
            SettingsItem(title = "Scrape Library", subtitle = scrapeMessage) {
                if (isScraping) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                else { Button(onClick = {
                    scope.launch {
                        isScraping = true
                        val missing = games.filter { it.coverUrl.isNullOrBlank() }
                        var count = 0
                        missing.forEach { game ->
                            scrapeMessage = "Scraping: ${game.name.substringBeforeLast(".")}"
                            if (ScraperService.scrapeGame(game, gameDao, prefs.getString(MainActivity.KEY_SCREENSCRAPER_KEY, null))) { count++ }
                        }
                        scrapeMessage = "Finished! $count posters added."
                        isScraping = false
                        Toast.makeText(context, "Scraped $count covers!", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Start") } }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.3f))
            SettingsCategory("SYSTEM", Icons.Default.Dns)
            SettingsItem(title = "Nuclear Log Filtering", subtitle = "Collapse and hide system noise logs") {
                Switch(
                    checked = nuclearLogging, 
                    onCheckedChange = { 
                        nuclearLogging = it
                        prefs.edit { putBoolean(MainActivity.KEY_NUCLEAR_LOGGING, it) }
                        LogManager.setNuclearMode(it)
                    }
                )
            }
            SettingsItem(title = "Core Downloader", subtitle = "Download or update online emulator cores") { 
                Button(onClick = onGoToControllerMapping) { 
                    Text("Open")
                } 
            }
            SettingsItem(title = "Import Cores Manually", subtitle = "Load Libretro .so files") { Button(onClick = { coreImporter.launch("*/*") }) { Text("Import") } }
            SettingsItem(title = "BIOS Manager", subtitle = "Manage system firmware files") { Button(onClick = onGoToBios) { Text("Open") } }
            SettingsItem(title = "Diagnostics", subtitle = "Troubleshoot emulator issues") { Button(onClick = { showDiagnostics = true }) { Text("Run") } }
            SettingsItem(title = "Logs", subtitle = "Export session logs for debugging") { 
                Button(onClick = {
                    val path = LogManager.exportLogs(context)
                    if (path != null) {
                        Toast.makeText(context, "Logs exported to Documents/Arc/logs/", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Export Failed", Toast.LENGTH_SHORT).show()
                    }
                }) { 
                    Text("Export") 
                } 
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onGoToAbout, modifier = Modifier.weight(1f)) { Text("About") }
                OutlinedButton(onClick = onGoToHelp, modifier = Modifier.weight(1f)) { Text("Help") }
            }
            
            Button(
                onClick = { 
                    prefs.edit { putBoolean(MainActivity.KEY_SETUP_COMPLETE, true) }
                    Toast.makeText(context, "Setup will appear on next launch!", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
            ) {
                Icon(Icons.Default.SettingsBackupRestore, null)
                Spacer(Modifier.width(8.dp))
                Text("Relaunch Setup Guide")
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("DANGER ZONE", style = MaterialTheme.typography.labelSmall, color = Color.Red, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { showWipeConfirm = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f), contentColor = Color.Red), modifier = Modifier.fillMaxWidth()) { Text("Wipe Library") }
            var showBackupConfirm by remember { mutableStateOf(false) }
            Button(onClick = { showBackupConfirm = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Backup & Reset Setup") }
            TextButton(onClick = { showResetConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Reset All Settings", color = Color.Gray) }
            Spacer(modifier = Modifier.height(48.dp))
        }
    }

    if (showWipeConfirm) { DestructiveActionDialog(title = "Wipe Library?", message = "This will remove all games from your database. Physical ROMs will not be deleted.", onConfirm = { scope.launch(Dispatchers.IO) { gameDao.deleteAll() }; Toast.makeText(context, "Library Cleared", Toast.LENGTH_SHORT).show() }, onDismiss = { showWipeConfirm = false }) }
    if (showResetConfirm) { DestructiveActionDialog(title = "Reset All Settings?", message = "This will clear all preferences and controller layouts.", onConfirm = { prefs.edit { clear() }; Toast.makeText(context, "Settings Reset", Toast.LENGTH_SHORT).show(); refreshKey++ }, onDismiss = { showResetConfirm = false }) }
    if (showDiagnostics) { DiagnosticsDialog(context = context) { showDiagnostics = false } }
}

@Composable
fun DiagnosticsDialog(context: Context, onDismiss: () -> Unit) {
    val report = remember { LibraryDiagnostics.generateReport(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("System Diagnostics") },
        text = { Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { Text(text = report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } },
        confirmButton = { TextButton(onClick = { val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostics", report)); Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show(); onDismiss() }) { Text("Copy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun AboutScreen(storageDir: File, gameDao: GameDao, prefs: SharedPreferences, onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader(title = "About", navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = onBack)
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(modifier = Modifier.size(120.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 4.dp) {
                Box(contentAlignment = Alignment.Center) { AsyncImage(model = R.mipmap.ic_launcher, contentDescription = "App Logo", modifier = Modifier.size(80.dp)) }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "Arc Emulator", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(text = "Version 1.5.0", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(32.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(16.dp)) { Text(text = "A high-performance multi-platform emulator frontend built with Jetpack Compose and Libretro cores.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "COMMUNITY & SOURCE", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.align(Alignment.Start).padding(start = 8.dp, bottom = 8.dp) )
            AboutLinkItem(title = "GitHub Repository", subtitle = "View source code & report issues", icon = Icons.Default.Code, onClick = { uriHandler.openUri("https://github.com/blinkchase/arc-emulator") })
            AboutLinkItem(title = "Official Website", subtitle = "Learn more about the project", icon = Icons.Default.Public, onClick = { uriHandler.openUri("https://blinkchase.com/arc") })
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // "Help Us Out" Section
            Text(text = "HELP US OUT", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.align(Alignment.Start).padding(start = 8.dp, bottom = 8.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Support Arc Emulator", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "As an open-source project, your support helps us stay active. You can help by:\n" +
                        "• Sharing Arc with the emulation community\n" +
                        "• Contributing code or translations on GitHub\n" +
                        "• Reporting bugs via our issue tracker",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "CREDITS", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.align(Alignment.Start).padding(start = 8.dp, bottom = 8.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("• Powered by Libretro API", style = MaterialTheme.typography.bodySmall)
                    Text("• Icons by Material Design", style = MaterialTheme.typography.bodySmall)
                    Text("• Artwork by ScreenScraper", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun ImportScreen(
    isScanning: Boolean,
    onScanGames: () -> Unit,
    onScanCores: () -> Unit,
    onScanLayouts: () -> Unit,
    onImportFiles: () -> Unit,
    coresCount: Int,
    layoutsCount: Int,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader(title = "Import & Scan", navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = onBack)
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(modifier = Modifier.size(80.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.height(24.dp))
            Text("Add Your Collection", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Scan your device for games, cores, and layouts.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray, textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            Button(onClick = onScanGames, modifier = Modifier.fillMaxWidth().height(56.dp), enabled = !isScanning, shape = RoundedCornerShape(16.dp)) {
                if (isScanning) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                else { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Scan for Games") }
            }
            Spacer(Modifier.height(24.dp)); HorizontalDivider(); Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ImportCard(title = "Cores", count = coresCount, icon = Icons.Default.Dns, onClick = onScanCores, modifier = Modifier.weight(1f))
                ImportCard(title = "Layouts", count = layoutsCount, icon = Icons.Default.Dashboard, onClick = onScanLayouts, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onImportFiles, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Import Files Manually")
            }
        }
    }
}

@Composable
private fun ImportCard(title: String, count: Int, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text("$count Installed", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

@Composable
fun SearchScreen(
    games: List<GameFile>,
    showExtensions: Boolean,
    prefs: SharedPreferences,
    onToggleFavorite: (GameFile) -> Unit,
    onGameSelected: (GameFile) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filterPlatform by remember { mutableStateOf<Platform?>(null) }
    
    val results = remember(query, filterPlatform, games) {
        var list = games
        if (query.isNotBlank()) {
            list = list.filter { it.name.contains(query, ignoreCase = true) || it.platform.name.contains(query, ignoreCase = true) }
        }
        if (filterPlatform != null) {
            list = list.filter { it.platform == filterPlatform }
        }
        if (query.isBlank() && filterPlatform == null) emptyList() else list
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search games or platforms...") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, null) } }, shape = RoundedCornerShape(16.dp), singleLine = true)
        }
        
        // Platform Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterPlatform == null,
                onClick = { filterPlatform = null },
                label = { Text("All", style = MaterialTheme.typography.labelSmall) }
            )
            Platform.entries.filter { it != Platform.UNKNOWN }.forEach { platform ->
                FilterChip(
                    selected = filterPlatform == platform,
                    onClick = { filterPlatform = platform },
                    label = { Text(platform.name, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = platform.getColor().copy(alpha = 0.3f),
                        selectedLabelColor = platform.getColor()
                    )
                )
            }
        }

        if (query.isBlank() && filterPlatform == null) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Search, null, modifier = Modifier.size(64.dp), tint = Color.Gray.copy(alpha = 0.3f))
                Spacer(Modifier.height(16.dp)); Text("Search your library", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { game ->
                    GameListItem(
                        game = game,
                        onToggleFavorite = onToggleFavorite,
                        onClick = onGameSelected,
                        showExtensions = showExtensions
                    )
                }
            }
        }
    }
}

@Composable
fun BiosScreen(
    storageDir: File,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val biosCheckResults = remember(refreshKey) { BiosManager.scanBiosFiles(storageDir) }
    val systemDir = remember { File(storageDir, "system").also { it.mkdirs() } }

    val biosPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val fileName = Utils.getFileName(context, it)
            if (fileName != null) {
                try {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        val importedFile = File(systemDir, fileName)
                        importedFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                        if (fileName.lowercase().replace("-", "").contains("scph39001")) {
                            val pcsx2BiosDir = File(systemDir, "pcsx2/bios").also { it.mkdirs() }
                            val canonical = File(pcsx2BiosDir, "scph39001.bin")
                            val rootCanonical = File(systemDir, "scph39001.bin")
                            systemDir.walkTopDown()
                                .filter { candidate ->
                                    candidate.isFile &&
                                        candidate.name.lowercase().replace("-", "").contains("scph39001") &&
                                        candidate.absolutePath != importedFile.absolutePath &&
                                        candidate.absolutePath != canonical.absolutePath &&
                                        candidate.absolutePath != rootCanonical.absolutePath
                                }
                                .forEach { it.delete() }
                            importedFile.inputStream().use { biosInput ->
                                canonical.outputStream().use { biosOutput -> biosInput.copyTo(biosOutput) }
                            }
                            canonical.copyTo(rootCanonical, overwrite = true)
                            if (importedFile.absolutePath != canonical.absolutePath) {
                                importedFile.delete()
                            }
                            Toast.makeText(context, "Replaced PS2 BIOS at ${canonical.absolutePath}", Toast.LENGTH_LONG).show()
                        }
                    }
                    Toast.makeText(context, "Imported $fileName", Toast.LENGTH_SHORT).show()
                    refreshKey++
                } catch (e: Exception) {
                    Toast.makeText(context, "Import Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader(
            title = "BIOS Manager",
            navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavigationClick = onBack,
            actions = {
                IconButton(onClick = { biosPicker.launch("*/*") }) {
                    Icon(Icons.Default.FileOpen, contentDescription = "Import BIOS", tint = MaterialTheme.colorScheme.primary)
                }
            }
        )

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "Required firmware files for specific systems. Place them in /Documents/Arc/system/",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Group by platform
                val grouped = biosCheckResults.groupBy { it.requirement.platform }
                
                grouped.forEach { (platform, results) ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(platform.getColor())
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = platform.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = platform.getColor()
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                results.forEach { result ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = when (result.status) {
                                                BiosManager.BiosStatus.VALID -> Icons.Default.CheckCircle
                                                BiosManager.BiosStatus.CORRUPTED -> Icons.Default.Error
                                                BiosManager.BiosStatus.MISSING -> Icons.Default.Description
                                            },
                                            contentDescription = null,
                                            tint = when (result.status) {
                                                BiosManager.BiosStatus.VALID -> Color(0xFF4CAF50)
                                                BiosManager.BiosStatus.CORRUPTED -> MaterialTheme.colorScheme.error
                                                BiosManager.BiosStatus.MISSING -> Color.Gray.copy(alpha = 0.5f)
                                            },
                                            modifier = Modifier.size(20.dp)
                                        )
                                        
                                        Spacer(modifier = Modifier.width(12.dp))
                                        
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = result.requirement.fileName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = result.requirement.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.Gray
                                            )
                                        }
                                        
                                        if (result.status == BiosManager.BiosStatus.CORRUPTED) {
                                            Text(
                                                text = "Invalid MD5",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutLinkItem(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            Spacer(modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
        }
    }
}

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader(title = "Help", navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = onBack)
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Getting Started", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("1. Copy ROMs to /Documents/Emulator Games/\n2. Copy cores to /Documents/Arc/cores/\n3. Tap 'Scan Games'", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(16.dp)); HorizontalDivider(); Spacer(modifier = Modifier.height(16.dp))
            Text("Playing Games", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("• Tap a game to start\n• Tap menu for options\n• Swipe down for FF", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(16.dp)); HorizontalDivider(); Spacer(modifier = Modifier.height(16.dp))
            Text("Troubleshooting", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("• Game not loading? Check core compatibility\n• No sound? Increase audio latency\n• Use Diagnostics in Settings", style = MaterialTheme.typography.bodySmall)
            
            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            
            Text("Support the Project", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Love Arc? Help us grow by reporting bugs, suggesting features on GitHub, or simply sharing the app with others. Every bit of support counts!",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SetupGuideScreen(
    onFinish: () -> Unit,
    onImportBios: () -> Unit,
    onScanLibrary: () -> Unit,
    isScanning: Boolean,
    gameList: List<GameFile>,
    prefs: SharedPreferences,
    gameDao: GameDao
) {
    var step by remember { mutableIntStateOf(0) }
    var isAdvanced by remember { mutableStateOf(false) }
    var scanStarted by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val internalCoresDir = remember { File(context.filesDir, "cores").also { it.mkdirs() } }
    var customPaths by remember { mutableStateOf(try { prefs.getStringSet(MainActivity.KEY_CUSTOM_PATHS, emptySet()) ?: emptySet() } catch (_: Exception) { emptySet<String>() }) }

    LaunchedEffect(isScanning) { if (isScanning) scanStarted = true }
    LaunchedEffect(step) { if (step == 4 && customPaths.isNotEmpty()) { prefs.edit { putInt(MainActivity.KEY_SCAN_MODE, 1) } } }

    val coreImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            var count = 0
            uris.forEach { uri ->
                val fileName = Utils.getFileName(context, uri)
                if (fileName != null && fileName.endsWith(".so")) {
                    try { context.contentResolver.openInputStream(uri)?.use { input -> File(internalCoresDir, fileName).outputStream().use { output -> input.copyTo(output) } }; count++ } catch (_: Exception) {}
                }
            }
            Toast.makeText(context, "Imported $count cores", Toast.LENGTH_SHORT).show()
        }
    }

    val pathLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val path = it.path ?: ""
            val split = path.split(":")
            if (split.size > 1) {
                val real = Environment.getExternalStorageDirectory().absolutePath + "/" + split[1]
                val new = customPaths + real
                customPaths = new
                prefs.edit { putStringSet(MainActivity.KEY_CUSTOM_PATHS, new); putInt(MainActivity.KEY_SCAN_MODE, 1) }
                onScanLibrary()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (step == 0) {
            Spacer(modifier = Modifier.height(48.dp))
            Icon(Icons.Default.SportsEsports, null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(24.dp))
            Text("Welcome to Arc Emulator", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("How would you like to set up?", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            Spacer(modifier = Modifier.height(48.dp))
            Card(onClick = { isAdvanced = false; step = 1 }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) { Text("Simple Setup", style = MaterialTheme.typography.titleLarge); Text("Guided steps to get you playing fast.", style = MaterialTheme.typography.bodySmall) }
            }
            Card(onClick = { isAdvanced = true; step = 1 }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) { Text("Advanced Setup", style = MaterialTheme.typography.titleLarge); Text("Full control over cores and ROM paths.", style = MaterialTheme.typography.bodySmall) }
            }
        } else {
            Text(text = if (isAdvanced) "Advanced Setup" else "Simple Setup", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            when (step) {
                1 -> {
                    Text("Step 1: Core Selection", style = MaterialTheme.typography.titleLarge)
                    Text("Import Libretro cores (.so files). Arc manages these files internally once imported. You can find official nightly cores at buildbot.libretro.com/nightly/android/", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { coreImporter.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Import Core Files") }
                }
                2 -> {
                    Text("Step 2: BIOS Files", style = MaterialTheme.typography.titleLarge)
                    Text("Some consoles require BIOS files. Place them in /Documents/Arc/system/", textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp)); Button(onClick = onImportBios, modifier = Modifier.fillMaxWidth()) { Text("Open BIOS Manager") }
                }
                3 -> {
                    Text("Step 3: Add Your Games", style = MaterialTheme.typography.titleLarge)
                    Text("Select where your ROMs are stored.", textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp)); Button(onClick = { pathLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Select ROM Folder") }
                }
                4 -> {
                    val total = gameList.size
                    val scraped = gameList.count { it.coverUrl != null }
                    Text("Prepare Your Library", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(32.dp))
                    if (isScanning || (total > 0 && scraped < total)) {
                        CircularProgressIndicator(); Spacer(modifier = Modifier.height(16.dp)); Text(if (isScanning) "Scanning..." else "Scraping Art...")
                    } else if (total > 0) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color.Green, modifier = Modifier.size(48.dp)); Text("All Set!", modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        prefs.edit { 
                            putString(MainActivity.KEY_SETUP_MODE, if (isAdvanced) "advanced" else "simple")
                            putBoolean(MainActivity.KEY_TOUR_COMPLETE, isAdvanced)
                        }
                        onFinish()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Finish & Ready to Play!") }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (step < 4) OutlinedButton(onClick = { if (step == 1) step = 0 else step-- }) { Text("Back") } else Spacer(Modifier.width(1.dp))
                if (step < 4) Button(onClick = { step++ }) { Text("Next") }
            }
        }
    }
}
