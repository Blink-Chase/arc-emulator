package com.blinkchase.arc

import android.graphics.BitmapFactory
import android.os.Environment
import android.text.format.DateUtils
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.roundToInt

@Composable
fun PlatformBadge(platform: Platform) {
    Surface(
        color = platform.getColor(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
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

@Composable
fun GameListItem(
    game: GameFile,
    onToggleFavorite: (GameFile) -> Unit,
    onClick: (GameFile) -> Unit,
    showExtensions: Boolean = false
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick(game) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

                if (coverFile != null) {
                    AsyncImage(
                        model = coverFile,
                        contentDescription = game.name,
                        modifier = Modifier.fillMaxSize().border(1.dp, Color.Gray, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = game.platform.getColor().copy(alpha = 0.2f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
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
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
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
    onLoad: (String) -> Boolean,
    onResume: () -> Unit,
    onAfterLoad: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // filesDir is now already the game-specific directory (e.g., saves/Streets_of_Rage_3_USA)
    val gameSavesDir = remember(filesDir) { filesDir }
    var refreshTrigger by remember { mutableStateOf(0) }
    var lastMessage by remember { mutableStateOf<String?>(null) }

    fun showMessage(msg: String) {
        lastMessage = msg
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
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
                    
                    var bitmap by remember(refreshTrigger) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
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
                                        Text("Save", fontSize = androidx.compose.ui.unit.TextUnit.Unspecified) 
                                    }
                                    
                                    Button(
                                        onClick = { 
                                            val success = onLoad(stateFile.absolutePath)
                                            if (success) {
                                                showMessage("Loaded slot ${slot + 1}")
                                                onResume()
                                                onAfterLoad()
                                            } else {
                                                showMessage("Load failed")
                                            }
                                            onDismiss()
                                        },
                                        enabled = exists,
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        contentPadding = PaddingValues(4.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                    ) { 
                                        Text("Load", fontSize = androidx.compose.ui.unit.TextUnit.Unspecified) 
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
