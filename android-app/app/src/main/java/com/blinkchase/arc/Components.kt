package com.blinkchase.arc

import android.graphics.BitmapFactory
import android.os.Environment
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@Composable
fun PlatformBadge(platform: Platform) {
    Surface(
        color = platform.getColor(),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Text(
            text = platform.name,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameListItem(
    game: GameFile,
    onToggleFavorite: (GameFile) -> Unit,
    onClick: (GameFile) -> Unit,
    onRefreshMetadata: (GameFile) -> Unit = {},
    showExtensions: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { onClick(game) },
            onLongClick = { showMenu = true }
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Box {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ... existing Row content ...
                Box(modifier = Modifier.size(48.dp)) {
                    val coversDir = remember { 
                        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                        File(File(documentsDir, "Arc"), "Covers")
                    }
                    val coverFile = remember(game.name) {
                        val baseName = game.name.substringBeforeLast(".")
                        val extensions = listOf(".png", ".jpg", ".jpeg")
                        extensions.map { File(coversDir, "$baseName$it") }.find { it.exists() }
                    }

                    if (game.coverUrl != null) {
                        AsyncImage(
                            model = game.coverUrl,
                            contentDescription = game.name,
                            modifier = Modifier.fillMaxSize().border(1.dp, Color.Gray,
                                RoundedCornerShape(4.dp)
                            ),
                            contentScale = ContentScale.Crop
                        )
                    } else if (coverFile != null) {
                        AsyncImage(
                            model = coverFile,
                            contentDescription = game.name,
                            modifier = Modifier.fillMaxSize().border(1.dp, Color.Gray, RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = game.platform.getColor().copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(game.platform.name.take(2), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (showExtensions) game.name else game.name.substringBeforeLast("."), 
                        style = MaterialTheme.typography.bodyMedium, 
                        maxLines = 1, 
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(game.platform.name, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                
                IconButton(onClick = { onToggleFavorite(game) }) {
                    Icon(
                        if (game.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (game.isFavorite) Color.Red else Color.Gray
                    )
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Refresh Metadata/Cover") },
                    onClick = {
                        showMenu = false
                        onRefreshMetadata(game)
                    },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                )
            }
        }
    }
}

@Composable
fun CrashReportDialog(onDismiss: () -> Unit) {
    val logFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "ArcEmulator/crash_log.txt")
    val logContent = remember { 
        if (logFile.exists()) logFile.readText() else "No log file found." 
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App Crashed Previously", color = Color.Red) },
        text = {
            Column {
                Text("The app crashed during the last session. Here are the logs:", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black)
                        .border(1.dp, Color.Gray)
                        .padding(8.dp)
                ) {
                    Text(
                        text = logContent,
                        color = Color.Green,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun SaveManagerDialog(
    filesDir: File,
    surfaceView: SurfaceView?,
    onSave: (String) -> Boolean,
    onLoad: suspend (String) -> Boolean,
    onResume: () -> Unit,
    onAfterLoad: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // filesDir is now already the game-specific directory (e.g., saves/Streets_of_Rage_3_USA)
    val gameSavesDir = remember(filesDir) { filesDir }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var lastMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var loadInProgress by remember { mutableStateOf(false) }

    fun showMessage(msg: String) {
        lastMessage = msg
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save States") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { 
                    Text("Location: ${gameSavesDir.absolutePath}", style = MaterialTheme.typography.bodySmall, color = Color.Gray) 
                }
                
                if (lastMessage != null) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (lastMessage!!.contains("Saved", ignoreCase = true)) 
                                    Color(0xFF4CAF50) else if (lastMessage!!.contains("Failed", ignoreCase = true)) 
                                    Color(0xFFF44336) else Color.DarkGray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                lastMessage!!,
                                modifier = Modifier.padding(12.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                
                items(5) { slot ->
                    val stateFile = File(gameSavesDir, "slot_$slot.state")
                    val imageFile = File(gameSavesDir, "slot_$slot.png")
                    val exists = remember(refreshTrigger) { stateFile.exists() }
                    
                    var bitmap by remember(refreshTrigger) { mutableStateOf<ImageBitmap?>(null) }
                    LaunchedEffect(refreshTrigger) {
                        if (imageFile.exists()) {
                            try {
                                val bmp = BitmapFactory.decodeFile(imageFile.absolutePath)
                                bitmap = bmp?.asImageBitmap()
                            } catch (e: Exception) { 
                                e.printStackTrace() 
                            }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color.DarkGray)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(100.dp, 75.dp)
                                    .background(Color.Black)
                                    .border(1.dp, Color.Gray),
                                contentAlignment = Alignment.Center
                            ) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap!!, 
                                        contentDescription = "Snapshot", 
                                        contentScale = ContentScale.Crop, 
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text("No Image", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            
                            Spacer(modifier = Modifier.width(16.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Slot ${slot + 1}", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Text(
                                    if (exists) "Saved" else "Empty", 
                                    style = MaterialTheme.typography.bodySmall, 
                                    color = if (exists) Color.Green else Color.Gray
                                )
                                
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    Button(
                                        onClick = {
                                            val success = onSave(stateFile.absolutePath)
                                            if (success) {
                                                surfaceView?.let { view ->
                                                    Utils.captureGameScreenshot(view, imageFile) {
                                                        refreshTrigger++
                                                    }
                                                } ?: run { refreshTrigger++ }
                                                showMessage("Saved to slot ${slot + 1}")
                                            } else {
                                                showMessage("Save failed - core may not support save states")
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        contentPadding = PaddingValues(4.dp)
                                    ) { 
                                        Text("Save", fontSize = TextUnit.Unspecified) 
                                    }
                                    
                                    Button(
                                        onClick = {
                                            if (loadInProgress) return@Button
                                            loadInProgress = true
                                            scope.launch {
                                                val success = onLoad(stateFile.absolutePath)
                                                loadInProgress = false
                                                if (success) {
                                                    showMessage("Loaded slot ${slot + 1}")
                                                    onResume()
                                                    onAfterLoad()
                                                } else {
                                                    showMessage("Load failed")
                                                }
                                                onDismiss()
                                            }
                                        },
                                        enabled = exists && !loadInProgress,
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        contentPadding = PaddingValues(4.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                    ) { 
                                        Text("Load", fontSize = TextUnit.Unspecified) 
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { 
            TextButton(onClick = onDismiss) { Text("Close") } 
        }
    )
}

@Composable
fun GameThumbnailItem(
    game: GameFile,
    onClick: (GameFile) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f) // Landscape ratio
            .clickable { onClick(game) },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val imageSource = game.thumbnailUrl ?: game.coverUrl

            if (imageSource != null) {
                AsyncImage(
                    model = imageSource,
                    contentDescription = game.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(game.platform.getColor()),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        game.name.take(1).uppercase(), 
                        style = MaterialTheme.typography.headlineLarge, 
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
            
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                PlatformBadge(game.platform)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameGridItem(
    game: GameFile,
    onToggleFavorite: (GameFile) -> Unit,
    onClick: (GameFile) -> Unit,
    onRefreshMetadata: (GameFile) -> Unit,
    showExtensions: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick(game) },
                onLongClick = { showMenu = true }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box {
            Column {
                Box(modifier = Modifier.aspectRatio(0.66f).fillMaxWidth()) {
                    // ... existing image loading ...
                    val coversDir = remember { 
                        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                        File(File(documentsDir, "Arc"), "Covers")
                    }
                    val coverFile = remember(game.name) {
                        val baseName = game.name.substringBeforeLast(".")
                        val extensions = listOf(".png", ".jpg", ".jpeg")
                        extensions.map { File(coversDir, "$baseName$it") }.find { it.exists() }
                    }

                    val imageSource = game.coverUrl ?: coverFile

                    if (imageSource != null) {
                        AsyncImage(
                            model = imageSource,
                            contentDescription = game.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize().background(game.platform.getColor().copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(game.platform.name, style = MaterialTheme.typography.labelLarge, color = game.platform.getColor())
                        }
                    }
                }
                
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        if (showExtensions) game.name else game.name.substringBeforeLast("."),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(game.platform.name, style = TextStyle(fontSize = 10.sp), color = Color.Gray, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onToggleFavorite(game) }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                if (game.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                tint = if (game.isFavorite) Color.Red else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Refresh Metadata/Cover") },
                    onClick = {
                        showMenu = false
                        onRefreshMetadata(game)
                    },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text(if (game.isFavorite) "Remove from Favorites" else "Add to Favorites") },
                    onClick = {
                        showMenu = false
                        onToggleFavorite(game)
                    },
                    leadingIcon = { Icon(if (game.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = null) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GamePosterItem(
    game: GameFile,
    onClick: (GameFile) -> Unit,
    onRefreshMetadata: (GameFile) -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.66f) // Standard 2:3 poster ratio
            .combinedClickable(
                onClick = { onClick(game) },
                onLongClick = { showMenu = true }
            ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // ... existing image loading ...
            val coversDir = remember { 
                val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                File(File(documentsDir, "Arc"), "Covers")
            }
            val coverFile = remember(game.name) {
                val baseName = game.name.substringBeforeLast(".")
                val extensions = listOf(".png", ".jpg", ".jpeg")
                extensions.map { File(coversDir, "$baseName$it") }.find { it.exists() }
            }

            val imageSource = game.coverUrl ?: coverFile

            if (imageSource != null) {
                AsyncImage(
                    model = imageSource,
                    contentDescription = game.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(game.platform.getColor()),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        game.name.take(1).uppercase(), 
                        style = MaterialTheme.typography.headlineLarge, 
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
            
            // Subtle overlay for platform badge
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                PlatformBadge(game.platform)
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Refresh Metadata/Cover") },
                    onClick = {
                        showMenu = false
                        onRefreshMetadata(game)
                    },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                )
            }
        }
    }
}

@Composable
fun CheatManagerDialog(
    cheats: MutableList<GameCheat>,
    onUpdate: (MutableList<GameCheat>) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var newCode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cheat Codes") },
        text = {
            Column {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().border(1.dp, Color.Gray).padding(4.dp)) {
                    itemsIndexed(cheats) { index, cheat ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Checkbox(
                                checked = cheat.enabled,
                                onCheckedChange = { isChecked ->
                                    val newList = cheats.toMutableList()
                                    newList[index] = cheat.copy(enabled = isChecked)
                                    onUpdate(newList)
                                }
                            )
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(cheat.name, style = MaterialTheme.typography.bodyMedium)
                                Text(cheat.code, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            IconButton(onClick = {
                                val newList = cheats.toMutableList()
                                newList.removeAt(index)
                                onUpdate(newList)
                            }) {
                                Icon(Icons.Default.Delete, "Delete", tint = Color.Red)
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Add New Cheat", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = newName, 
                    onValueChange = { newName = it }, 
                    label = { Text("Name") }, 
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newCode, 
                    onValueChange = { newCode = it }, 
                    label = { Text("Code (Game Genie/PAR)") }, 
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (newName.isNotBlank() && newCode.isNotBlank()) {
                            onUpdate(cheats.toMutableList().apply { add(GameCheat(newName, newCode, true)) })
                            newName = ""
                            newCode = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { 
                    Text("Add Cheat") 
                }
            }
        },
        confirmButton = { 
            TextButton(onClick = onDismiss) { Text("Close") } 
        }
    )
}

@Composable
fun GuidedTourTooltip(
    text: String,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    isLast: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .padding(16.dp)
            .widthIn(max = 280.dp)
            .zIndex(100f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Guided Tour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSkip) { Text("Skip") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onNext) {
                    Text(if (isLast) "Finish" else "Next")
                }
            }
        }
    }
}

@Composable
fun CrashRecoveryCard(
    onDismiss: () -> Unit,
    onViewWiki: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Text("Core Crash Detected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, null, tint = Color.Gray)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Arc detected a crash during your last game. This is usually caused by an incompatible Libretro core or missing BIOS files.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onViewWiki,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Troubleshooting Guide")
                }
            }
        }
    }
}

@Composable
fun LibraryEmptyState(
    onAddGames: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(100.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.List,
                    null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Your Library is Empty", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Wiped your collection or just getting started? Tap below to scan for ROMs.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onAddGames,
            modifier = Modifier.height(56.dp).fillMaxWidth(0.8f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Add Games to Library")
        }
    }
}
