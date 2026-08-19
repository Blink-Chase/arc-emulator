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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.roundToInt
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import coil.compose.AsyncImage

@Composable
fun ArcHomeScreen(
    games: List<GameFile>,
    recentGames: List<GameFile>,
    storageDir: File,
    gameDao: GameDao,
    prefs: android.content.SharedPreferences,
    onGameClick: (GameFile) -> Unit,
    onToggleFavorite: (GameFile) -> Unit,
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
                GameListItem(game = game, onToggleFavorite = onToggleFavorite, onClick = onGameClick)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onScanGames: () -> Unit,
    onScanCores: () -> Unit,
    onScanLayouts: () -> Unit,
    onImportFiles: () -> Unit,
    coresCount: Int,
    layoutsCount: Int,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import & Scan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
            Text("Games", style = MaterialTheme.typography.titleMedium)
            Text("Scan configured locations or import specific files.")
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScanGames, modifier = Modifier.weight(1f)) {
                    Text("Scan Library")
                }
                Button(onClick = onImportFiles, modifier = Modifier.weight(1f)) {
                    Text("Import Files")
                }
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
}

@Composable
fun SearchScreen(
    games: List<GameFile>, 
    onToggleFavorite: (GameFile) -> Unit,
    onGameSelected: (GameFile) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filterPlatform by remember { mutableStateOf<Platform?>(null) }

    val filteredGames = remember(query, filterPlatform, games) {
        games.filter { 
            (query.isBlank() || it.name.contains(query, ignoreCase = true)) &&
            (filterPlatform == null || it.platform == filterPlatform)
        }.sortedBy { it.name }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Search & Filter", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Game Name") },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            shape = RoundedCornerShape(12.dp)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Quick Filters
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterPlatform == null,
                onClick = { filterPlatform = null },
                label = { Text("All Platforms", style = MaterialTheme.typography.labelSmall) }
            )
            Platform.entries.filter { it != Platform.UNKNOWN }.forEach { platform ->
                FilterChip(
                    selected = filterPlatform == platform,
                    onClick = { filterPlatform = platform },
                    label = { Text(platform.name, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        if (query.isBlank() && filterPlatform == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.Gray.copy(alpha = 0.3f))
                    Text("Enter a name or select a platform", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = filteredGames) { game ->
                    GameListItem(game = game, onToggleFavorite = onToggleFavorite, onClick = onGameSelected)
                }
                
                if (filteredGames.isEmpty()) {
                    item {
                        Text("No games found matching your search.", modifier = Modifier.fillMaxWidth().padding(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Color.Gray)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    games: List<GameFile>, 
    onToggleFavorite: (GameFile) -> Unit, 
    onGameSelected: (GameFile) -> Unit,
    onNavigateToImport: () -> Unit
) {
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var filterPlatform by remember { mutableStateOf<Platform?>(null) }
    var isGridView by rememberSaveable { mutableStateOf(true) }
    
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Library", style = MaterialTheme.typography.headlineMedium)
            Row {
                IconButton(onClick = { isGridView = isGridView.not() }) {
                    Icon(
                        if (isGridView) Icons.AutoMirrored.Filled.List else Icons.Default.GridView,
                        contentDescription = "Toggle View",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onNavigateToImport) {
                    Icon(Icons.Default.Add, contentDescription = "Import", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Sorting Chips
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
        
        // Platform Filter Chips
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
                    columns = GridCells.Adaptive(120.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items = sortedGames) { game ->
                        GameGridItem(game, onToggleFavorite, onGameSelected)
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
                
                // Letter scroll for list view
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
                                
                                if (targetIndex >= 0) {
                                    scope.launch { listState.scrollToItem(targetIndex) }
                                }
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

@Composable
fun GameGridItem(
    game: GameFile,
    onToggleFavorite: (GameFile) -> Unit,
    onClick: (GameFile) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(game) },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column {
            Box(modifier = Modifier.aspectRatio(0.75f).fillMaxWidth()) {
                val coversDir = remember { 
                    val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    File(File(documentsDir, "Arc"), "Covers")
                }
                val coverFile = remember(game.name) {
                    val baseName = game.name.substringBeforeLast(".")
                    val extensions = listOf(".png", ".jpg", ".jpeg")
                    extensions.map { File(coversDir, "$baseName$it") }.find { it.exists() }
                }

                if (coverFile != null) {
                    AsyncImage(
                        model = coverFile,
                        contentDescription = game.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = game.platform.getColor().copy(alpha = 0.3f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                game.platform.name,
                                style = MaterialTheme.typography.headlineSmall,
                                color = game.platform.getColor()
                            )
                        }
                    }
                }
                
                IconButton(
                    onClick = { onToggleFavorite(game) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)
                ) {
                    Icon(
                        if (game.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (game.isFavorite) Color.Red else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = game.name.substringBeforeLast("."),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp)
            )
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
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
        )
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
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
        Box(modifier = Modifier.padding(start = 16.dp)) {
            content()
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
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(16.dp))

        // Profile Section (Static for now)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Guest Profile", style = MaterialTheme.typography.titleMedium)
                    Text("Version 1.4.0", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 1. General Settings
        SettingsCategory("GENERAL", Icons.Default.Settings)
        
        var themeMode by remember { mutableIntStateOf(prefs.getInt(MainActivity.KEY_THEME_MODE, 0)) }
        SettingsItem(
            title = "Theme Mode",
            subtitle = when (themeMode) { 0 -> "Follow system"; 1 -> "Light"; else -> "Dark" }
        ) {
            Row {
                listOf(0, 1, 2).forEach { mode ->
                    val icon = when(mode) { 0 -> Icons.Default.BrightnessAuto; 1 -> Icons.Default.LightMode; else -> Icons.Default.DarkMode }
                    IconButton(
                        onClick = {
                            themeMode = mode
                            prefs.edit { putInt(MainActivity.KEY_THEME_MODE, mode) }
                        }
                    ) {
                        Icon(icon, null, tint = if (themeMode == mode) MaterialTheme.colorScheme.primary else Color.Gray)
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.3f))

        // 2. Emulation Settings
        SettingsCategory("EMULATION", Icons.Default.SportsEsports)
        
        SettingsItem(title = "Fast Forward Button", subtitle = "Toggle FF overlay visibility") {
            Switch(checked = showFF, onCheckedChange = { 
                showFF = it
                prefs.edit { putBoolean(MainActivity.KEY_SHOW_FF, it) }
            })
        }
        
        SettingsItem(title = "Auto-Pause", subtitle = "Pause game when menu is open") {
            Switch(checked = autoPauseMenu, onCheckedChange = { 
                autoPauseMenu = it
                prefs.edit { putBoolean(MainActivity.KEY_AUTO_PAUSE_MENU, it) }
            })
        }

        SettingsItem(title = "Audio Latency", subtitle = "Increase if sound crackles") {
            Slider(
                value = audioLatency.toFloat(),
                onValueChange = { audioLatency = it.roundToInt() },
                onValueChangeFinished = { 
                    prefs.edit { putInt(MainActivity.KEY_AUDIO_LATENCY, audioLatency) }
                    (context as? MainActivity)?.resetAudio()
                },
                valueRange = 0f..2f,
                steps = 1,
                modifier = Modifier.width(120.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.3f))

        // 3. Controller Settings
        SettingsCategory("TOUCH CONTROLS", Icons.Default.TouchApp)
        
        SettingsItem(title = "Style", subtitle = controllerStyle.name) {
            IconButton(onClick = {
                val next = (controllerStyle.ordinal + 1) % InputStyle.entries.size
                controllerStyle = InputStyle.entries[next]
                prefs.edit { putInt(MainActivity.KEY_CONTROLLER_STYLE, next) }
            }) {
                Icon(Icons.Default.Refresh, null)
            }
        }

        var defaultOpacity by remember { mutableFloatStateOf(prefs.getFloat("control_opacity", 0.7f)) }
        SettingsItem(title = "Opacity", subtitle = "${(defaultOpacity * 100).roundToInt()}%") {
            Slider(
                value = defaultOpacity,
                onValueChange = { defaultOpacity = it },
                onValueChangeFinished = { prefs.edit { putFloat("control_opacity", defaultOpacity) } },
                valueRange = 0.1f..1.0f,
                modifier = Modifier.width(120.dp)
            )
        }

        var defaultButtonSize by remember { mutableFloatStateOf(prefs.getFloat("control_button_size", 1.0f)) }
        SettingsItem(title = "Button Size", subtitle = "${(defaultButtonSize * 100).roundToInt()}%") {
            Slider(
                value = defaultButtonSize,
                onValueChange = { defaultButtonSize = it },
                onValueChangeFinished = { prefs.edit { putFloat("control_button_size", defaultButtonSize) } },
                valueRange = 0.5f..1.5f,
                modifier = Modifier.width(120.dp)
            )
        }

        var hapticEnabled by remember { mutableStateOf(prefs.getBoolean("control_haptic", true)) }
        SettingsItem(title = "Haptic Feedback") {
            Switch(checked = hapticEnabled, onCheckedChange = { 
                hapticEnabled = it
                prefs.edit { putBoolean("control_haptic", it) }
            })
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.3f))

        // 4. Library Settings
        SettingsCategory("LIBRARY & STORAGE", Icons.Default.Storage)
        
        SettingsItem(title = "Scan Location", subtitle = if (scanMode == 0) "Downloads" else "Custom") {
            Switch(checked = scanMode == 1, onCheckedChange = { 
                scanMode = if (it) 1 else 0
                prefs.edit { putInt(MainActivity.KEY_SCAN_MODE, scanMode) }
            })
        }

        if (scanMode == 1) {
            customPaths.forEach { path ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(path.split("/").last(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val new = customPaths - path
                        customPaths = new
                        prefs.edit { putStringSet(MainActivity.KEY_CUSTOM_PATHS, new) }
                    }) { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp), tint = Color.Red) }
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
                    }
                }
            }
            Button(onClick = { pathLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("Add Folder")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("Core & BIOS", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        
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

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { coreImporter.launch("*/*") }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                Text("Import Core")
            }
            Button(onClick = { biosLauncher.launch("*/*") }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                Text("Import BIOS")
            }
        }

        if (biosList.isNotEmpty()) {
            Text("BIOS Files: ${biosList.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        MainActivity.AVAILABLE_CORES.forEach { (platform, cores) ->
            val key = "core_pref_${platform.name}"
            val current = prefs.getString(key, cores.first()) ?: cores.first()
            ListItem(
                headlineContent = { Text(platform.name) },
                supportingContent = { Text(current.replace("_libretro_android", "")) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                modifier = Modifier.clickable { showCoreSelectorFor = platform }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.3f))

        // 5. Support & About
        SettingsCategory("SUPPORT", Icons.Default.Info)
        
        SettingsItem(title = "Diagnostics", subtitle = "View system & library info") {
            Button(onClick = { showDiagnostics = true }) { Text("Run") }
        }

        SettingsItem(title = "Logs", subtitle = "Report a bug or view logs") {
            Button(onClick = onReportBug) { Text("View") }
        }
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onGoToAbout, modifier = Modifier.weight(1f)) { Text("About") }
            OutlinedButton(onClick = onGoToHelp, modifier = Modifier.weight(1f)) { Text("Help") }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        // 6. Danger Zone
        Text("DANGER ZONE", style = MaterialTheme.typography.labelSmall, color = Color.Red)
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                scope.launch(Dispatchers.IO) { gameDao.deleteAll() }
                android.widget.Toast.makeText(context, "Library Cleared", android.widget.Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f), contentColor = Color.Red),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Wipe Library")
        }

        TextButton(
            onClick = {
                prefs.edit { clear() }
                android.widget.Toast.makeText(context, "Settings Reset", android.widget.Toast.LENGTH_SHORT).show()
                refreshKey++
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset All Settings", color = Color.Gray)
        }
        
        Spacer(modifier = Modifier.height(48.dp))
    }

    if (showCoreSelectorFor != null) {
        val platform = showCoreSelectorFor!!
        val key = "core_pref_${platform.name}"
        val currentCore = prefs.getString(key, MainActivity.AVAILABLE_CORES[platform]?.first())
        val knownCores = MainActivity.AVAILABLE_CORES[platform] ?: emptyList()

        AlertDialog(
            onDismissRequest = { showCoreSelectorFor = null },
            title = { Text("Select Core: ${platform.name}") },
            text = {
                LazyColumn {
                    items(items = knownCores) { core ->
                        val isSelected = currentCore == core
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    prefs.edit { putString(key, core) }
                                    showCoreSelectorFor = null
                                    refreshKey++
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(core.replace("_libretro_android", ""))
                                val isInstalled = installedCores.contains(core)
                                Text(
                                    text = if (isInstalled) "Installed" else "Not Found",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isInstalled) Color.Green else Color.Red
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCoreSelectorFor = null }) { Text("Cancel") } }
        )
    }
    
    if (showDiagnostics) {
        DiagnosticsDialog(context = context) { showDiagnostics = false }
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
        Text("Version 1.4.0", style = MaterialTheme.typography.titleMedium)
        
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
