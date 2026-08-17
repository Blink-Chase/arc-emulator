package com.blinkchase.arc

import android.content.Context
import android.os.Build
import android.os.Environment
import com.blinkchase.arc.db.GameDao
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.roundToInt
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ArcHomeScreen(
    games: List<GameFile>,
    recentGames: List<GameFile>,
    storageDir: File,
    gameDao: GameDao,
    prefs: android.content.SharedPreferences,
    onGameClick: (GameFile) -> Unit,
    onGoToLibrary: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val showMigration = remember { NovaMigrator.isNovaFolderPresent() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(32.dp))
            Text("Arc Emulator", style = MaterialTheme.typography.headlineLarge)
        }

        if (showMigration) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Legacy Nova Data Detected!", style = MaterialTheme.typography.titleMedium)
                        Text("Move your saves, time played, and favorites now.", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    val result = NovaMigrator.migrate(context, storageDir, gameDao, prefs)
                                    android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_LONG).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) {
                            Text("Migrate from Nova")
                        }
                    }
                }
            }
        }

        item {
            Text("Library: ${games.size} games", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Button(onClick = onGoToLibrary, modifier = Modifier.fillMaxWidth()) {
                Text("Go to Library")
            }
        }

        if (recentGames.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Recent Games", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            }
            items(items = recentGames) { game ->
                GameListItem(game = game, onToggleFavorite = {}, onClick = onGameClick)
            }
        }
    }
}

@Composable
fun ImportScreen(
    onScanGames: () -> Unit,
    onScanCores: () -> Unit,
    onScanLayouts: () -> Unit,
    coresCount: Int,
    layoutsCount: Int,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Scan Library", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
        // Games section
        Text("Games", style = MaterialTheme.typography.titleMedium)
        Text("Scan configured locations for games.")
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onScanGames, modifier = Modifier.fillMaxWidth()) {
            Text("Scan Games")
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Cores section
        Text(
            text = "Cores ($coresCount installed)",
            style = MaterialTheme.typography.titleMedium,
            color = if (coresCount > 0) Color.Green else MaterialTheme.colorScheme.onSurface
        )
        Text("Place .so files in Arc/Cores/ folder")
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onScanCores, modifier = Modifier.fillMaxWidth()) {
            Text(if (coresCount > 0) "Rescan Cores" else "Scan Cores")
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Layouts section
        Text(
            text = "Layouts ($layoutsCount found)",
            style = MaterialTheme.typography.titleMedium,
            color = if (layoutsCount > 0) Color.Green else MaterialTheme.colorScheme.onSurface
        )
        Text("Layout files are saved to Arc/Layouts/")
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onScanLayouts, modifier = Modifier.fillMaxWidth()) {
            Text(if (layoutsCount > 0) "Rescan Layouts" else "Scan Layouts")
        }
    }
}

@Composable
fun SearchScreen(games: List<GameFile>, onGameSelected: (GameFile) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filteredGames = remember(query, games) {
        if (query.isBlank()) emptyList() 
        else games.filter { it.name.contains(query, ignoreCase = true) }
            .sortedBy { it.name }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Search", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { newValue -> query = newValue },
            label = { Text("Game Name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn {
            items(items = filteredGames) { game ->
                GameListItem(game = game, onToggleFavorite = {}, onClick = onGameSelected)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    games: List<GameFile>, 
    onToggleFavorite: (GameFile) -> Unit, 
    onGameSelected: (GameFile) -> Unit
) {
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var filterPlatform by remember { mutableStateOf<Platform?>(null) }
    
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Library", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Sorting Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SortMode.entries.forEach { mode ->
                FilterChip(
                    selected = sortMode == mode,
                    onClick = { sortMode = mode },
                    label = { Text(mode.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }) }
                )
            }
        }
        
        // Platform Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterPlatform == null,
                onClick = { filterPlatform = null },
                label = { Text("All") }
            )
            Platform.entries.filter { it != Platform.UNKNOWN }.forEach { platform ->
                FilterChip(
                    selected = filterPlatform == platform,
                    onClick = { filterPlatform = platform },
                    label = { Text(platform.name) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
            val favorites = sortedGames.filter { it.isFavorite }
            val others = sortedGames.filter { !it.isFavorite }

            if (favorites.isNotEmpty()) {
                item {
                    Text(
                        text = "Favorites",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(items = favorites) { game ->
                    GameListItem(game, onToggleFavorite, onGameSelected)
                }
            }

            if (others.isNotEmpty()) {
                item {
                    Text(
                        text = if (favorites.isNotEmpty()) "All Games" else "",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                items(items = others) { game ->
                    GameListItem(game, onToggleFavorite, onGameSelected)
                }
            }
        }
        
        // Fast scroll handle
        val letters = ('A'..'Z').map { it.toString() }
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            letters.forEach { letter ->
                Text(
                    text = letter,
                    modifier = Modifier.clickable {
                        val favoritesCount = if (sortedGames.any { it.isFavorite }) 1 + sortedGames.count { it.isFavorite } else 0
                        val allGamesHeader = if (favoritesCount > 0) 1 else 0
                        val othersBefore = sortedGames.filter { !it.isFavorite }.takeWhile { !it.name.startsWith(letter, ignoreCase = true) }.size
                        
                        val targetIndex = if (sortedGames.filter { !it.isFavorite }.any { it.name.startsWith(letter, ignoreCase = true) }) {
                            favoritesCount + allGamesHeader + othersBefore
                        } else -1
                        
                        if (targetIndex >= 0) {
                            scope.launch { listState.scrollToItem(targetIndex) }
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: android.content.SharedPreferences,
    rootStorageDir: File,
    gameDao: GameDao,
    onReportBug: () -> Unit,
    onGoToAbout: () -> Unit,
    onGoToHelp: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var scanMode by remember { mutableIntStateOf(prefs.getInt(MainActivity.KEY_SCAN_MODE, 0)) }
    var customPaths by remember {
    mutableStateOf(
        try {
            prefs.getStringSet(MainActivity.KEY_CUSTOM_PATHS, emptySet()) ?: emptySet()
        } catch (_: Exception) {
            emptySet<String>()
        }
    )
}
    var audioLatency by remember { mutableIntStateOf(prefs.getInt(MainActivity.KEY_AUDIO_LATENCY, 1)) }
    var showFF by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_SHOW_FF, true)) }
    var autoPauseMenu by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_AUTO_PAUSE_MENU, true)) }
    var controllerStyle by remember { mutableStateOf(InputStyle.entries[prefs.getInt(MainActivity.KEY_CONTROLLER_STYLE, 0)]) }
    var showCoreSelectorFor by remember { mutableStateOf<Platform?>(null) }
    var showDiagnostics by remember { mutableStateOf(value = false) }
    
    val context = LocalContext.current
    val installedCores = remember(refreshKey) { Utils.scanInstalledCores(context) }

    val internalCoresDir = remember { File(context.filesDir, "cores").also { it.mkdirs() } }
    
    val coreImporter = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val fileName = Utils.getFileName(context, it)
            if (fileName != null && fileName.endsWith(".so")) {
                try {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        File(internalCoresDir, fileName).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    android.widget.Toast.makeText(context, "Imported $fileName", android.widget.Toast.LENGTH_SHORT).show()
                    refreshKey++
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "Import Failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            } else {
                android.widget.Toast.makeText(context, "Invalid file selected", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Profile: Guest")
        Spacer(modifier = Modifier.height(8.dp))
        Text("Version: 1.3.0")
        Text("System Arch: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"}",
             style = MaterialTheme.typography.bodySmall, 
             color = Color.Gray)
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("General", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        // Theme mode selection
        var themeMode by remember { mutableIntStateOf(prefs.getInt(MainActivity.KEY_THEME_MODE, 0)) }
        Text("Theme Mode", style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("System" to 0, "Light" to 1, "Dark" to 2).forEach { (label, value) ->
                FilterChip(
                    selected = themeMode == value,
                    onClick = {
                        themeMode = value
                        prefs.edit { putInt(MainActivity.KEY_THEME_MODE, value) }
                    },
                    label = { Text(label) }
                )
            }
        }
        Text(
            text = when (themeMode) {
                0 -> "Follow system setting"
                1 -> "Always use light theme"
                2 -> "Always use dark theme"
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Show Fast Forward Button", modifier = Modifier.weight(1f))
            Switch(checked = showFF, onCheckedChange = { 
                showFF = it
                prefs.edit { putBoolean(MainActivity.KEY_SHOW_FF, it) }
            })
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Pause Game when Menu Opens", modifier = Modifier.weight(1f))
            Switch(checked = autoPauseMenu, onCheckedChange = { 
                autoPauseMenu = it
                prefs.edit { putBoolean(MainActivity.KEY_AUTO_PAUSE_MENU, it) }
            })
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Controller Style", style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(), 
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InputStyle.entries.forEach { style ->
                FilterChip(
                    selected = controllerStyle == style,
                    onClick = {
                        controllerStyle = style
                        prefs.edit { putInt(MainActivity.KEY_CONTROLLER_STYLE, style.ordinal) }
                    },
                    label = { 
                        Text(
                            style.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall
                        ) 
                    }
                )
            }
        }
        Text(
            text = when (controllerStyle) {
                InputStyle.COMPACT -> "Smaller buttons for small screens"
                InputStyle.MINIMALIST -> "Reduced opacity and size"
                InputStyle.TRANSPARENT -> "Very transparent controls"
                InputStyle.HIDDEN -> "No on-screen controls"
                else -> "Default look with full opacity"
            },
            style = MaterialTheme.typography.bodySmall, 
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))
        
        // Default control opacity setting
        var defaultOpacity by remember { mutableFloatStateOf(prefs.getFloat("control_opacity", 0.7f)) }
        Text("Default Control Opacity: ${(defaultOpacity * 100).roundToInt()}%")
        Slider(
            value = defaultOpacity,
            onValueChange = { defaultOpacity = it },
            onValueChangeFinished = {
                prefs.edit { putFloat("control_opacity", defaultOpacity) }
            },
            valueRange = 0.1f..1.0f,
            steps = 8
        )

        Spacer(modifier = Modifier.height(8.dp))
        
        // Default button size
        var defaultButtonSize by remember { mutableFloatStateOf(prefs.getFloat("control_button_size", 1.0f)) }
        Text("Default Button Size: ${(defaultButtonSize * 100).roundToInt()}%")
        Slider(
            value = defaultButtonSize,
            onValueChange = { defaultButtonSize = it },
            onValueChangeFinished = {
                prefs.edit { putFloat("control_button_size", defaultButtonSize) }
            },
            valueRange = 0.5f..1.5f,
            steps = 9
        )

        Spacer(modifier = Modifier.height(8.dp))
        
        // Haptic feedback toggle
        var hapticEnabled by remember { mutableStateOf(prefs.getBoolean("control_haptic", true)) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Haptic Feedback on Button Press")
            Switch(
                checked = hapticEnabled,
                onCheckedChange = { 
                    hapticEnabled = it
                    prefs.edit { putBoolean("control_haptic", hapticEnabled) }
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        
        // Auto-hide delay
        var autoHideDelay by remember { mutableIntStateOf(prefs.getInt("control_auto_hide", 0)) }
        Text("Auto-hide Controls: ${if (autoHideDelay == 0) "Never" else "${autoHideDelay}s"}")
        Slider(
            value = autoHideDelay.toFloat(),
            onValueChange = { autoHideDelay = it.roundToInt() },
            onValueChangeFinished = {
                prefs.edit { putInt("control_auto_hide", autoHideDelay) }
            },
            valueRange = 0f..10f,
            steps = 9
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("Audio Latency (Buffer Size)", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = when(audioLatency) { 
                0 -> "Low (Fast)" 
                1 -> "Medium (Balanced)" 
                else -> "High (Safe)" 
            }, 
            style = MaterialTheme.typography.bodySmall, 
            color = Color.Gray
        )
        Slider(
            value = audioLatency.toFloat(),
            onValueChange = { audioLatency = it.roundToInt() },
            onValueChangeFinished = { 
                prefs.edit { putInt(MainActivity.KEY_AUDIO_LATENCY, audioLatency) }
                (context as? MainActivity)?.resetAudio()
            },
            valueRange = 0f..2f,
            steps = 1
        )
        Text("If sound crackles, increase this.", 
             style = MaterialTheme.typography.bodySmall, 
             color = Color.Gray)

        Spacer(modifier = Modifier.height(24.dp))
        Text("Library Settings", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scan Source:", modifier = Modifier.weight(1f))
            Switch(
                checked = scanMode == 1,
                onCheckedChange = { isCustom ->
                    val newMode = if (isCustom) 1 else 0
                    scanMode = newMode
                    prefs.edit { putInt(MainActivity.KEY_SCAN_MODE, newMode) }
                }
            )
        }
        Text(
            if (scanMode == 0) "Scanning Downloads Folder" else "Scanning Custom Folders",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        if (scanMode == 1) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Custom Paths:", style = MaterialTheme.typography.bodyMedium)
            
            LazyColumn(modifier = Modifier.height(150.dp).fillMaxWidth().border(1.dp, Color.Gray).padding(4.dp)) {
                items(items = customPaths.toList()) { path ->
                    Row(
                        modifier = Modifier.fillMaxWidth(), 
                        horizontalArrangement = Arrangement.SpaceBetween, 
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(path, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(end = 8.dp))
                        IconButton(onClick = {
                            val newPaths = customPaths - path
                            customPaths = newPaths
                            prefs.edit { putStringSet(MainActivity.KEY_CUSTOM_PATHS, newPaths) }
                        }) {
                            Icon(Icons.Default.Delete, "Remove", tint = Color.Red)
                        }
                    }
                }
            }

            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let {
                    val path = it.path ?: ""
                    val split = path.split(":")
                    if (split.size > 1) {
                        val realPath = Environment.getExternalStorageDirectory().absolutePath + "/" + split[1]
                        val newPaths = customPaths + realPath
                        customPaths = newPaths
                        prefs.edit { putStringSet(MainActivity.KEY_CUSTOM_PATHS, newPaths) }
                    }
                }
            }

            Button(onClick = { launcher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text("Add Folder")
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Core Selection", style = MaterialTheme.typography.titleMedium)
        Text("Tap to cycle through available cores", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = { coreImporter.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
            Text("Import Core / Lib (.so)")
        }
        Spacer(modifier = Modifier.height(8.dp))

        MainActivity.AVAILABLE_CORES.forEach { (platform, cores) ->
            val key = "core_pref_${platform.name}"
            val currentCore = prefs.getString(key, cores.first()) ?: cores.first()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCoreSelectorFor = platform }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(platform.name, style = MaterialTheme.typography.bodyLarge)
                Text(currentCore.replace("_libretro_android", ""), color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("BIOS Manager", style = MaterialTheme.typography.titleMedium)
        Text("Required for some cores (e.g. PS1)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        
        val systemDir = File(rootStorageDir, "system")
        if (!systemDir.exists()) systemDir.mkdirs()
        var biosList by remember { mutableStateOf(systemDir.listFiles()?.map { it.name } ?: emptyList<String>()) }

        val biosLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                val fileName = Utils.getFileName(context, it)
                if (fileName != null) {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        File(systemDir, fileName).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    biosList = systemDir.listFiles()?.map { f -> f.name } ?: emptyList()
                }
            }
        }

        Button(onClick = { biosLauncher.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
            Text("Import BIOS File")
        }
        
        LazyColumn(modifier = Modifier.height(100.dp).fillMaxWidth().border(1.dp, Color.Gray).padding(4.dp)) {
            items(items = biosList) { name ->
                Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(2.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Support", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(onClick = { showDiagnostics = true }, modifier = Modifier.fillMaxWidth()) {
            Text("System Diagnostics")
        }
        
        Button(onClick = onReportBug, modifier = Modifier.fillMaxWidth()) {
            Text("Report Bug / View Logs")
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // About and Help buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onGoToAbout,
                modifier = Modifier.weight(1f)
            ) {
                Text("About")
            }
            Button(
                onClick = onGoToHelp,
                modifier = Modifier.weight(1f)
            ) {
                Text("Help")
            }
        }

        Button(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    gameDao.deleteAll()
                }
                android.widget.Toast.makeText(context, "Library Cleared", android.widget.Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Wipe Library (Delete all games)")
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                prefs.edit { clear()}
                scanMode = 0
                customPaths = emptySet()
                audioLatency = 1
                showFF = true
                controllerStyle = InputStyle.STANDARD
                autoPauseMenu = true
                themeMode = 0
                refreshKey++
                android.widget.Toast.makeText(context, "Settings Reset", android.widget.Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset All Settings")
        }
    }

    // Core selector dialog
    if (showCoreSelectorFor != null) {
        val platform = showCoreSelectorFor!!
        val key = "core_pref_${platform.name}"
        val currentCore = prefs.getString(key, MainActivity.AVAILABLE_CORES[platform]?.first())
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val coresDir = File(rootStorageDir, "cores")
        val knownCores = MainActivity.AVAILABLE_CORES[platform] ?: emptyList()

        AlertDialog(
            onDismissRequest = { showCoreSelectorFor = null },
            title = { Text("Select Core for ${platform.name}") },
            text = {
                LazyColumn {
                    items(items = knownCores) { core ->
                        val isInstalled = installedCores.contains(core)
                        val isSelected = currentCore == core
                        
                        val libName = if (core.startsWith("lib")) core else "lib$core"
                        val fileName = if (libName.endsWith(".so")) libName else "$libName.so"
                        val customFile = File(coresDir, fileName)
                        val nativeFile = File(nativeDir, fileName)
                        val finalFile = if (customFile.exists()) customFile else nativeFile
                        val arch = Utils.getLibArchitecture(finalFile)
                        val isCompatible = ((arch == "x86_64") && Build.SUPPORTED_ABIS.contains("x86_64")) || 
                                          ((arch == "ARM64") && Build.SUPPORTED_ABIS.contains("arm64-v8a"))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    prefs.edit { putString(key, core)}
                                    showCoreSelectorFor = null
                                    refreshKey++
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(core.replace("_libretro_android", ""), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (isInstalled) "Installed ($arch)" else "Not Found", 
                                    style = MaterialTheme.typography.bodySmall, 
                                    color = if (isInstalled && isCompatible) Color.Green else Color.Red
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { 
                TextButton(onClick = { showCoreSelectorFor = null }) { 
                    Text("Cancel") 
                } 
            },
            dismissButton = {
                TextButton(onClick = { coreImporter.launch("*/*") }) {
                    Text("Import Core / Lib")
                }
            }
        )
    }
    
    // Diagnostics dialog
    if (showDiagnostics) {
        DiagnosticsDialog(
            context = context
        ) { showDiagnostics = false }
    }
}

@Composable
fun DiagnosticsDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val report = remember { LibraryDiagnostics.generateReport(context) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("System Diagnostics") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Diagnostics", report)
                clipboard.setPrimaryClip(clip)
                android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                onDismiss()
            }) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun AboutScreen(
    storageDir: File, 
    gameDao: GameDao,
    prefs: android.content.SharedPreferences,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var migrationStatus by remember { mutableStateOf<String?>(null) }
    val showMigration = remember { NovaMigrator.isNovaFolderPresent() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text("Arc Emulator", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Version 1.3.0", style = MaterialTheme.typography.titleMedium)
        
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        if (showMigration) {
            Text("Legacy Data Detected", color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        val result = NovaMigrator.migrate(context, storageDir, gameDao, prefs)
                        migrationStatus = result.message
                        android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text("Migrate from Nova Emulator")
            }
            migrationStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
        }
        
        Text("About", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Arc Emulator is a multi-platform Android emulator frontend built with Jetpack Compose and libretro.",
            style = MaterialTheme.typography.bodyMedium
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("Features", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "• ROM Library with favorites\n" +
            "• Multiple emulator cores\n" +
            "• Customizable touch controls\n" +
            "• Save states\n" +
            "• Fast forward\n" +
            "• Dark/Light themes",
            style = MaterialTheme.typography.bodySmall
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("Supported Platforms", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "SNES, Genesis, GBA, GB, GBC, PS1",
            style = MaterialTheme.typography.bodySmall
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("License", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "GNU General Public License v3.0",
            style = MaterialTheme.typography.bodySmall
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("Developer", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Blink-Chase",
            style = MaterialTheme.typography.bodySmall
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onBack) {
            Text("Back")
        }
    }
}

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text("Help", style = MaterialTheme.typography.headlineLarge)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Getting Started
        Text("Getting Started", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "1. Copy your ROM files to /Documents/Emulator Games/\n" +
            "2. Copy libretro cores to /Documents/Arc/cores/\n" +
            "3. Open Arc Emulator and tap 'Scan Games'",
            style = MaterialTheme.typography.bodySmall
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // Playing Games
        Text("Playing Games", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "• Tap a game in the Library to start\n" +
            "• Use on-screen controls to play\n" +
            "• Tap the menu button for more options\n" +
            "• Swipe down for fast forward",
            style = MaterialTheme.typography.bodySmall
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // Controls
        Text("Touch Controls", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "• Drag buttons to reposition them\n" +
            "• Long-press to reset positions\n" +
            "• Change styles in Settings > Controller",
            style = MaterialTheme.typography.bodySmall
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // Save States
        Text("Save States", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "• Access from in-game menu\n" +
            "• 5 save slots per game\n" +
            "• Auto-save when closing game",
            style = MaterialTheme.typography.bodySmall
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        
        // Troubleshooting
        Text("Troubleshooting", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "• Game not loading? Check ROM format\n" +
            "• No sound? Increase audio latency in Settings\n" +
            "• Controls not working? Try different control style\n" +
            "• Use System Diagnostics in Settings for help",
            style = MaterialTheme.typography.bodySmall
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onBack) {
            Text("Back")
        }
    }
}
