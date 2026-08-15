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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
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
    onClick: (GameFile) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick(game) },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlatformBadge(game.platform)
                    Text(
                        text = game.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                if (game.lastPlayed > 0) {
                    Text(
                        text = "Last played: ${DateUtils.getRelativeTimeSpanString(game.lastPlayed)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
            IconButton(onClick = { onToggleFavorite(game) }) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Star,
                    contentDescription = "Favorite",
                    tint = if (game.isFavorite) Color(0xFFDAA520) else Color.Gray
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

@Composable
fun ControlsOverlay(
    modifier: Modifier = Modifier,
    platform: Platform,
    style: InputStyle = InputStyle.STANDARD,
    buttonOffsets: Map<Int, Offset> = emptyMap(),
    enabled: Boolean = true,
    isEditing: Boolean = false,
    onDrag: (Int, Offset) -> Unit = { _, _ -> },
    showFF: Boolean = true,
    onMenuClick: () -> Unit = {},
    onFastForward: (Boolean) -> Unit = {}
) {
    val buttonScale = if (style == InputStyle.COMPACT) 0.8f else 1.0f
    val buttonAlpha = if (style == InputStyle.MINIMALIST) 0.3f else 1.0f
    
    @Composable
    fun StyledGameButton(
        text: String, 
        id: Int, 
        baseSize: androidx.compose.ui.unit.DpSize, 
        offset: Offset? = null
    ) {
        GameButton(
            text = text, 
            buttonId = id, 
            modifier = Modifier.size(baseSize.width * buttonScale, baseSize.height * buttonScale), 
            offset = offset, 
            enabled = enabled,
            isEditing = isEditing, 
            onDrag = onDrag,
            alpha = buttonAlpha
        )
    }

    Box(modifier = modifier) {
        // Fast Forward Button
        if (showFF) {
            val interactionSource = remember { MutableInteractionSource() }
            LaunchedEffect(interactionSource) {
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> onFastForward(true)
                        is PressInteraction.Release, is PressInteraction.Cancel -> onFastForward(false)
                    }
                }
            }
            Button(
                onClick = {}, 
                interactionSource = interactionSource, 
                enabled = enabled, 
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) { 
                Text("FF") 
            }
        }

        // Menu Button
        val menuButtonModifier = if (platform == Platform.GENESIS) {
            Modifier.align(Alignment.TopStart)
        } else {
            Modifier.align(Alignment.TopCenter)
        }
        Button(
            onClick = onMenuClick, 
            enabled = true, 
            modifier = menuButtonModifier.padding(16.dp)
        ) { 
            Text("MENU") 
        }

        when (platform) {
            Platform.PS1 -> {
                // PS1 layout implementation
                Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp), 
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        StyledGameButton("L1", MainActivity.BTN_L, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_L])
                        Spacer(modifier = Modifier.height(4.dp))
                        StyledGameButton("L2", MainActivity.BTN_L2, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_L2])
                    }
                    Column {
                        StyledGameButton("R1", MainActivity.BTN_R, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_R])
                        Spacer(modifier = Modifier.height(4.dp))
                        StyledGameButton("R2", MainActivity.BTN_R2, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_R2])
                    }
                }

                // D-Pad
                Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StyledGameButton("U", MainActivity.BTN_UP, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_UP])
                        Row {
                            StyledGameButton("L", MainActivity.BTN_LEFT, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_LEFT])
                            Spacer(modifier = Modifier.size(60.dp))
                            StyledGameButton("R", MainActivity.BTN_RIGHT, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_RIGHT])
                        }
                        StyledGameButton("D", MainActivity.BTN_DOWN, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_DOWN])
                    }
                }

                // Select/Start
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    StyledGameButton("SEL", MainActivity.BTN_SELECT, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_SELECT])
                    StyledGameButton("STR", MainActivity.BTN_START, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_START])
                }

                // Action buttons
                Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StyledGameButton("△", MainActivity.BTN_X, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_X])
                        Row {
                            StyledGameButton("□", MainActivity.BTN_Y, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_Y])
                            Spacer(modifier = Modifier.size(60.dp))
                            StyledGameButton("○", MainActivity.BTN_A, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_A])
                        }
                        StyledGameButton("✕", MainActivity.BTN_B, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_B])
                    }
                }
            }
            Platform.N64 -> {
                // N64 layout
                Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp), 
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    StyledGameButton("L", MainActivity.BTN_L, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_L])
                    StyledGameButton("Z", MainActivity.BTN_Z, androidx.compose.ui.unit.DpSize(60.dp, 40.dp), buttonOffsets[MainActivity.BTN_Z])
                    StyledGameButton("R", MainActivity.BTN_R, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_R])
                }

                Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp, bottom = 32.dp)) {
                    AnalogStick(modifier = Modifier.size(100.dp), enabled = enabled, isEditing = isEditing)
                }

                Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp, bottom = 32.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StyledGameButton("S", MainActivity.BTN_START, androidx.compose.ui.unit.DpSize(50.dp, 50.dp), buttonOffsets[MainActivity.BTN_START])
                        Spacer(modifier = Modifier.height(16.dp))
                        Row {
                            StyledGameButton("B", MainActivity.BTN_B, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_B])
                            Spacer(modifier = Modifier.width(16.dp))
                            StyledGameButton("A", MainActivity.BTN_A, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_A])
                        }
                    }
                }
            }
            Platform.GENESIS -> {
                // Genesis layout
                Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StyledGameButton("U", MainActivity.BTN_UP, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_UP])
                        Row {
                            StyledGameButton("L", MainActivity.BTN_LEFT, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_LEFT])
                            Spacer(modifier = Modifier.size(60.dp))
                            StyledGameButton("R", MainActivity.BTN_RIGHT, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_RIGHT])
                        }
                        StyledGameButton("D", MainActivity.BTN_DOWN, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_DOWN])
                    }
                }

                Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) {
                    StyledGameButton("STR", MainActivity.BTN_START, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_START])
                }

                Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp, bottom = 32.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        StyledGameButton("C", MainActivity.BTN_A, androidx.compose.ui.unit.DpSize(65.dp, 65.dp), buttonOffsets[MainActivity.BTN_A])
                        Spacer(modifier = Modifier.height(4.dp))
                        GameButton("B", MainActivity.BTN_B, Modifier.size(65.dp * buttonScale).offset(x = (-25).dp), 
                                  buttonOffsets[MainActivity.BTN_B], enabled, isEditing, onDrag, alpha = buttonAlpha)
                        Spacer(modifier = Modifier.height(4.dp))
                        GameButton("A", MainActivity.BTN_Y, Modifier.size(65.dp * buttonScale).offset(x = (-50).dp), 
                                  buttonOffsets[MainActivity.BTN_Y], enabled, isEditing, onDrag, alpha = buttonAlpha)
                    }
                }
            }
            Platform.GBA, Platform.GB, Platform.GBC -> {
                // GBA/GB/GBC layout
                if (platform == Platform.GBA) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp), 
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        StyledGameButton("L", MainActivity.BTN_L, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_L])
                        StyledGameButton("R", MainActivity.BTN_R, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_R])
                    }
                }

                Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StyledGameButton("U", MainActivity.BTN_UP, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_UP])
                        Row {
                            StyledGameButton("L", MainActivity.BTN_LEFT, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_LEFT])
                            Spacer(modifier = Modifier.size(60.dp))
                            StyledGameButton("R", MainActivity.BTN_RIGHT, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_RIGHT])
                        }
                        StyledGameButton("D", MainActivity.BTN_DOWN, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_DOWN])
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    StyledGameButton("SEL", MainActivity.BTN_SELECT, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_SELECT])
                    StyledGameButton("STR", MainActivity.BTN_START, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_START])
                }

                Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GameButton("B", MainActivity.BTN_B, Modifier.size(60.dp * buttonScale).offset(y = 15.dp), 
                                  buttonOffsets[MainActivity.BTN_B], enabled, isEditing, onDrag, alpha = buttonAlpha)
                        Spacer(modifier = Modifier.width(16.dp))
                        GameButton("A", MainActivity.BTN_A, Modifier.size(60.dp * buttonScale).offset(y = (-15).dp), 
                                  buttonOffsets[MainActivity.BTN_A], enabled, isEditing, onDrag, alpha = buttonAlpha)
                    }
                }
            }
            else -> {
                // SNES/Default layout
                Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp), 
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    StyledGameButton("L", MainActivity.BTN_L, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_L])
                    StyledGameButton("R", MainActivity.BTN_R, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_R])
                }

                Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StyledGameButton("U", MainActivity.BTN_UP, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_UP])
                        Row {
                            StyledGameButton("L", MainActivity.BTN_LEFT, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_LEFT])
                            Spacer(modifier = Modifier.size(60.dp))
                            StyledGameButton("R", MainActivity.BTN_RIGHT, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_RIGHT])
                        }
                        StyledGameButton("D", MainActivity.BTN_DOWN, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_DOWN])
                    }
                }

                Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StyledGameButton("X", MainActivity.BTN_X, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_X])
                        Row {
                            StyledGameButton("Y", MainActivity.BTN_Y, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_Y])
                            Spacer(modifier = Modifier.size(60.dp))
                            StyledGameButton("A", MainActivity.BTN_A, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_A])
                        }
                        StyledGameButton("B", MainActivity.BTN_B, androidx.compose.ui.unit.DpSize(60.dp, 60.dp), buttonOffsets[MainActivity.BTN_B])
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    StyledGameButton("SEL", MainActivity.BTN_SELECT, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_SELECT])
                    StyledGameButton("STR", MainActivity.BTN_START, androidx.compose.ui.unit.DpSize(80.dp, 40.dp), buttonOffsets[MainActivity.BTN_START])
                }
            }
        }
    }
}

@Composable
fun GameButton(
    text: String, 
    buttonId: Int, 
    modifier: Modifier = Modifier.size(60.dp).padding(4.dp),
    offset: Offset? = null,
    enabled: Boolean = true,
    isEditing: Boolean = false,
    onDrag: (Int, Offset) -> Unit = { _, _ -> },
    alpha: Float = 1.0f
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    
    val offsetX = offset?.x ?: 0f
    val offsetY = offset?.y ?: 0f
    
    val dragModifier = if (isEditing) {
        Modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                val newOffset = Offset(offsetX + dragAmount.x, offsetY + dragAmount.y)
                onDrag(buttonId, newOffset)
            }
        }
    } else Modifier

    val borderModifier = if (isEditing) Modifier.border(2.dp, Color.Red, CircleShape) else Modifier
    
    LaunchedEffect(interactionSource, enabled) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> if (!isEditing && enabled) {
                    (context as? MainActivity)?.sendInput(buttonId, 1)
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> if (!isEditing && enabled) { 
                    (context as? MainActivity)?.sendInput(buttonId, 0) 
                }
            }
        }
    }

    Button(
        onClick = {},
        interactionSource = interactionSource,
        enabled = enabled,
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .then(dragModifier)
            .then(borderModifier)
            .alpha(alpha),
        shape = CircleShape
    ) {
        Text(text)
    }
}

@Composable
fun AnalogStick(
    modifier: Modifier = Modifier, 
    enabled: Boolean = true, 
    isEditing: Boolean = false
) {
    var knobPosition by remember { mutableStateOf(Offset.Zero) }
    val maxRadius = 100f
    val context = LocalContext.current

    Box(
        modifier = modifier
            .background(Color.Gray.copy(alpha = 0.5f), CircleShape)
            .pointerInput(enabled, isEditing) {
                if (!isEditing && enabled) {
                    detectDragGestures(
                        onDragEnd = { 
                            knobPosition = Offset.Zero 
                            (context as? MainActivity)?.sendInput(MainActivity.BTN_UP, 0)
                            (context as? MainActivity)?.sendInput(MainActivity.BTN_DOWN, 0)
                            (context as? MainActivity)?.sendInput(MainActivity.BTN_LEFT, 0)
                            (context as? MainActivity)?.sendInput(MainActivity.BTN_RIGHT, 0)
                            (context as? MainActivity)?.setAnalogInput(0, 0)
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        val newPos = knobPosition + dragAmount
                        val distance = newPos.getDistance()
                        knobPosition = if (distance > maxRadius) newPos * (maxRadius / distance) else newPos
                        (context as? MainActivity)?.sendInput(MainActivity.BTN_RIGHT, if (knobPosition.x > 30) 1 else 0)
                        (context as? MainActivity)?.sendInput(MainActivity.BTN_LEFT, if (knobPosition.x < -30) 1 else 0)
                        (context as? MainActivity)?.sendInput(MainActivity.BTN_DOWN, if (knobPosition.y > 30) 1 else 0)
                        (context as? MainActivity)?.sendInput(MainActivity.BTN_UP, if (knobPosition.y < -30) 1 else 0)
                        
                        val analogX = (knobPosition.x / maxRadius * 32767).toInt()
                        val analogY = (knobPosition.y / maxRadius * 32767).toInt()
                        (context as? MainActivity)?.setAnalogInput(analogX, analogY)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(knobPosition.x.roundToInt(), knobPosition.y.roundToInt()) }
                .size(50.dp)
                .background(Color.DarkGray, CircleShape)
        )
    }
}