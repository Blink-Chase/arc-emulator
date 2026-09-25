package com.blinkchase.arc

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blinkchase.arc.db.InputDao
import kotlinx.coroutines.launch

enum class MappingLevel { GLOBAL, PLATFORM, GAME }

@Composable
fun ControllerMappingScreen(
    inputDao: InputDao,
    inputManager: InputManager,
    currentPlatform: Platform = Platform.UNKNOWN,
    currentGamePath: String = "",
    onBack: () -> Unit,
    onTestControls: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    
    var deviceName by remember { mutableStateOf<String?>(null) }
    var mapping = remember { mutableStateMapOf<Int, Int>() } // KeyCode -> BTN_ID
    var listeningForBtnId by remember { mutableStateOf<Int?>(null) }
    var selectedModel by remember { mutableStateOf(ControllerModel.GENERIC_ABXY) }
    
    var mappingLevel by remember { 
        mutableStateOf(if (currentGamePath.isNotEmpty()) MappingLevel.GAME else if (currentPlatform != Platform.UNKNOWN) MappingLevel.PLATFORM else MappingLevel.GLOBAL)
    }

    // Initial detection of already connected device
    LaunchedEffect(Unit) {
        val deviceIds = InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id)
            if (device != null && (device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD)) {
                deviceName = device.name
                break
            }
        }
    }
    
    val profiles by inputDao.getAllProfiles().collectAsState(initial = emptyList())
    var activeProfile by remember { mutableStateOf<ControllerProfile?>(null) }

    val buttons = listOf(
        MainActivity.BTN_UP,
        MainActivity.BTN_DOWN,
        MainActivity.BTN_LEFT,
        MainActivity.BTN_RIGHT,
        MainActivity.BTN_A,
        MainActivity.BTN_B,
        MainActivity.BTN_X,
        MainActivity.BTN_Y,
        MainActivity.BTN_L,
        MainActivity.BTN_R,
        MainActivity.BTN_L2,
        MainActivity.BTN_R2,
        MainActivity.BTN_SELECT,
        MainActivity.BTN_START,
        MainActivity.BTN_L3,
        MainActivity.BTN_R3
    )

    // Load existing profile if found for the specific level
    LaunchedEffect(profiles, deviceName, mappingLevel) {
        if (deviceName != null) {
            val levelPlatform = when(mappingLevel) {
                MappingLevel.GLOBAL -> ""
                else -> currentPlatform.name
            }
            val levelGame = when(mappingLevel) {
                MappingLevel.GAME -> currentGamePath
                else -> ""
            }
            
            val p = profiles.find { it.deviceName == deviceName && it.platform == levelPlatform && it.gamePath == levelGame }
            if (p != null) {
                activeProfile = p
                mapping.clear()
                mapping.putAll(p.buttonMap)
                selectedModel = p.model
            } else {
                activeProfile = null
                mapping.clear()
            }
        }
    }

    fun saveProfile() {
        val name = deviceName ?: "Unknown Controller"
        val levelPlatform = when(mappingLevel) {
            MappingLevel.GLOBAL -> ""
            else -> currentPlatform.name
        }
        val levelGame = when(mappingLevel) {
            MappingLevel.GAME -> currentGamePath
            else -> ""
        }
        
        scope.launch {
            val profile = ControllerProfile(
                deviceName = name,
                platform = levelPlatform,
                gamePath = levelGame,
                buttonMap = mapping.toMap(),
                model = selectedModel
            )
            inputDao.saveProfile(profile)
        }
    }

    val buttonState by inputManager.buttonState.collectAsState()
    
    // Bridge for D-Pad Axis detection (MotionEvents don't trigger onKeyEvent)
    LaunchedEffect(buttonState, listeningForBtnId) {
        val listeningId = listeningForBtnId
        if (listeningId != null) {
            // Find any active button (physical or virtual axis)
            buttonState.entries.find { it.value }?.key?.let { activeKeyCode ->
                // Remove any existing mapping for this key or this button
                val existingKey = mapping.entries.find { it.value == listeningId }?.key
                if (existingKey != null) mapping.remove(existingKey)
                
                mapping[activeKeyCode] = listeningId
                listeningForBtnId = null
                saveProfile()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                Log.d("ArcMapping", "Key Event: ${native.keyCode} Action: ${native.action}")
                if (native.action == KeyEvent.ACTION_DOWN) {
                    val device = native.device?.name ?: "Unknown Controller"
                    deviceName = device
                    
                    listeningForBtnId?.let { btnId ->
                        // Remove any existing mapping for this key or this button
                        val existingKey = mapping.entries.find { it.value == btnId }?.key
                        if (existingKey != null) mapping.remove(existingKey)
                        
                        mapping[native.keyCode] = btnId
                        listeningForBtnId = null
                        saveProfile()
                        return@onKeyEvent true
                    }
                }
                false
            }
    ) {
        ScreenHeader(
            title = "Controller Setup",
            navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavigationClick = onBack,
            actions = {
                IconButton(onClick = onTestControls) {
                    Icon(Icons.Default.BugReport, "Test Controls")
                }
            }
        )

        LazyColumn(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Device Info
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (deviceName != null) Icons.Default.Gamepad else Icons.Default.UsbOff, null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Current Device", style = MaterialTheme.typography.labelSmall)
                            Text(deviceName ?: "No Controller Detected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (deviceName == null) {
                                Text("Press any button on your controller to detect it.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }
                }
            }

            // 2. Mapping Level
            item {
                Text("Mapping Level", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MappingLevel.entries.forEach { level ->
                        if (level == MappingLevel.GAME && currentGamePath.isEmpty()) return@forEach
                        
                        val enabled = when(level) {
                            MappingLevel.GLOBAL -> true
                            MappingLevel.PLATFORM -> currentPlatform != Platform.UNKNOWN
                            MappingLevel.GAME -> currentGamePath.isNotEmpty()
                        }
                        FilterChip(
                            selected = mappingLevel == level,
                            onClick = { mappingLevel = level },
                            enabled = enabled,
                            label = { 
                                Text(
                                    text = when(level) {
                                        MappingLevel.GLOBAL -> "Global"
                                        MappingLevel.PLATFORM -> "Platform (${currentPlatform.name})"
                                        MappingLevel.GAME -> "Game specific"
                                    },
                                    fontSize = 10.sp
                                ) 
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 3. Emulated Model
            item {
                Text("Emulated Controller Type", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 3
                ) {
                    ControllerModel.entries.forEach { model ->
                        FilterChip(
                            selected = selectedModel == model,
                            onClick = { 
                                selectedModel = model
                                saveProfile()
                            },
                            label = { 
                                Text(
                                    text = model.name.replace("_", " "),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                ) 
                            },
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }

            // 4. Mapping List
            item {
                Text("Button Mappings", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }

            items(buttons) { id ->
                val label = ArcInputMap.getButtonLabel(id, selectedModel)
                val keyCode = mapping.entries.find { it.value == id }?.key
                val isListening = listeningForBtnId == id

                Surface(
                    onClick = { listeningForBtnId = id },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isListening) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val tint = MaterialTheme.colorScheme.primary
                            val isLetterButton = (id == MainActivity.BTN_A || id == MainActivity.BTN_B || 
                                            id == MainActivity.BTN_X || id == MainActivity.BTN_Y) && 
                                            selectedModel != ControllerModel.PLAYSTATION &&
                                            !(selectedModel == ControllerModel.N64 && (id == MainActivity.BTN_X || id == MainActivity.BTN_Y))

                            if (isLetterButton) {
                                val char = when(id) {
                                    MainActivity.BTN_A -> "A"
                                    MainActivity.BTN_B -> "B"
                                    MainActivity.BTN_X -> if (selectedModel == ControllerModel.WII) "1" else "X"
                                    MainActivity.BTN_Y -> if (selectedModel == ControllerModel.WII) "2" else "Y"
                                    else -> ""
                                }
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .border(1.5.dp, tint, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = char,
                                        color = tint,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.offset(y = (-3.7f).dp)
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = when (id) {
                                        MainActivity.BTN_UP -> Icons.Default.ArrowUpward
                                        MainActivity.BTN_DOWN -> Icons.Default.ArrowDownward
                                        MainActivity.BTN_LEFT -> Icons.AutoMirrored.Filled.ArrowBack
                                        MainActivity.BTN_RIGHT -> Icons.AutoMirrored.Filled.ArrowForward
                                        MainActivity.BTN_START -> {
                                            if (selectedModel == ControllerModel.PLAYSTATION) Icons.Default.Settings 
                                            else if (selectedModel == ControllerModel.XBOX) Icons.Default.Menu
                                            else Icons.Default.PlayArrow
                                        }
                                        MainActivity.BTN_SELECT -> {
                                            if (selectedModel == ControllerModel.PLAYSTATION) Icons.Default.Share 
                                            else if (selectedModel == ControllerModel.XBOX || selectedModel == ControllerModel.N64) Icons.Default.VerticalSplit
                                            else Icons.Default.HorizontalRule
                                        }
                                        MainActivity.BTN_L -> Icons.Default.KeyboardArrowLeft
                                        MainActivity.BTN_R -> Icons.Default.KeyboardArrowRight
                                        MainActivity.BTN_L2 -> Icons.Default.KeyboardDoubleArrowDown
                                        MainActivity.BTN_R2 -> if (selectedModel == ControllerModel.N64) Icons.AutoMirrored.Filled.ArrowForward else Icons.Default.KeyboardDoubleArrowDown
                                        MainActivity.BTN_L3 -> Icons.Default.ControlCamera
                                        MainActivity.BTN_R3 -> if (selectedModel == ControllerModel.N64) Icons.Default.ArrowDownward else Icons.Default.ControlCamera
                                        MainActivity.BTN_A, MainActivity.BTN_B, MainActivity.BTN_X, MainActivity.BTN_Y -> {
                                            when (selectedModel) {
                                                ControllerModel.PLAYSTATION -> {
                                                    when (id) {
                                                        MainActivity.BTN_A -> Icons.Default.Close
                                                        MainActivity.BTN_B -> Icons.Default.RadioButtonUnchecked
                                                        MainActivity.BTN_X -> Icons.Default.CheckBoxOutlineBlank
                                                        MainActivity.BTN_Y -> Icons.Default.ChangeHistory
                                                        else -> Icons.Default.RadioButtonUnchecked
                                                    }
                                                }
                                                ControllerModel.N64 -> {
                                                    if (id == MainActivity.BTN_X) Icons.Default.ArrowUpward
                                                    else if (id == MainActivity.BTN_Y) Icons.AutoMirrored.Filled.ArrowBack
                                                    else Icons.Default.RadioButtonUnchecked
                                                }
                                                else -> Icons.Default.RadioButtonUnchecked
                                            }
                                        }
                                        else -> Icons.Default.Circle
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = tint
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = label, 
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        
                        Box(contentAlignment = Alignment.CenterEnd) {
                            if (isListening) {
                                Text("Waiting for key...", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text(
                                    text = if (keyCode != null) {
                                        when(keyCode) {
                                            -10 -> "D-Pad Up"
                                            -11 -> "D-Pad Down"
                                            -12 -> "D-Pad Left"
                                            -13 -> "D-Pad Right"
                                            -20 -> "R-Stick Up"
                                            -21 -> "R-Stick Down"
                                            -22 -> "R-Stick Left"
                                            -23 -> "R-Stick Right"
                                            else -> "Key: $keyCode"
                                        }
                                    } else "Not Mapped",
                                    color = if (keyCode != null) MaterialTheme.colorScheme.onSurface else Color.Gray,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
            
            item { Spacer(Modifier.height(100.dp)) }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    maxItemsInEachRow: Int = Int.MAX_VALUE,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        maxItemsInEachRow = maxItemsInEachRow
    ) {
        content()
    }
}
