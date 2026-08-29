package com.blinkchase.arc

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ControllerTestScreen(
    inputManager: InputManager,
    onBack: () -> Unit
) {
    val eventLog = remember { mutableStateListOf<String>() }

    val axisState by inputManager.axisState.collectAsState()
    val lsX = axisState.lsX
    val lsY = axisState.lsY
    val rsX = axisState.rsX
    val rsY = axisState.rsY
    
    val buttonState by inputManager.buttonState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Log last pressed button to event log
        LaunchedEffect(buttonState) {
            buttonState.filter { it.value }.keys.firstOrNull()?.let { keyCode ->
                val text = "Button: $keyCode (PRESSED)"
                if (!eventLog.contains(text)) {
                    eventLog.add(0, text)
                    if (eventLog.size > 20) eventLog.removeAt(eventLog.size - 1)
                }
            }
        }
        ScreenHeader(
            title = "Input Diagnostics",
            navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavigationClick = onBack
        )

        LazyColumn(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Sticks
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StickVisualizer("Left Stick", lsX, lsY)
                        StickVisualizer("Right Stick", rsX, rsY)
                    }
                }
            }

            // 2. Buttons Grid (Visualization)
            item {
                Text("Active Buttons", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val profile = inputManager.getActiveProfile()
                    val model = profile?.model ?: ControllerModel.GENERIC_ABXY
                    
                    buttonState.filter { it.value }.forEach { (keyCode, _) ->
                        // Determine the label
                        val label = when {
                            keyCode == -10 -> "D-Pad Up"
                            keyCode == -11 -> "D-Pad Down"
                            keyCode == -12 -> "D-Pad Left"
                            keyCode == -13 -> "D-Pad Right"
                            keyCode == -20 -> "R-Stick Up"
                            keyCode == -21 -> "R-Stick Down"
                            keyCode == -22 -> "R-Stick Left"
                            keyCode == -23 -> "R-Stick Right"
                            else -> {
                                val btnId = profile?.buttonMap?.get(keyCode)
                                if (btnId != null) {
                                    ArcInputMap.getButtonLabel(btnId, model)
                                } else {
                                    "ID: $keyCode"
                                }
                            }
                        }
                        
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // 3. Event Log
            item {
                Text("Input Log", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    LazyColumn(contentPadding = PaddingValues(8.dp)) {
                        items(eventLog) { log ->
                            Text(log, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StickVisualizer(label: String, x: Float, y: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(Color.DarkGray.copy(alpha = 0.1f), CircleShape)
                .border(1.dp, Color.Gray.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Deadzone marker
            Box(modifier = Modifier.size(20.dp).border(0.5.dp, Color.Red.copy(alpha = 0.2f), CircleShape))
            
            // Stick marker
            Box(
                modifier = Modifier
                    .offset(x = (x * 40).dp, y = (y * 40).dp)
                    .size(16.dp)
                    .background(Color.Cyan, CircleShape)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement
    ) {
        content()
    }
}
