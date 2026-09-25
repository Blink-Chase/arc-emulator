package com.blinkchase.arc

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import kotlin.math.roundToInt

@Composable
fun ControlsOverlay(
    modifier: Modifier = Modifier,
    platform: Platform,
    config: ControlLayoutConfig,
    model: ControllerModel = ControllerModel.GENERIC_ABXY,
    buttonProps: Map<Int, ButtonProps> = emptyMap(),
    selectedButtonId: Int? = null,
    onSelectButton: (Int?) -> Unit = {},
    enabled: Boolean = true,
    isEditing: Boolean = false,
    isLandscape: Boolean = false,
    onUpdateProps: (Int, ButtonProps) -> Unit = { _, _ -> },
    showFF: Boolean = true,
    onFastForward: (Boolean) -> Unit = {},
    onMenuClick: () -> Unit = {},
    onInteraction: () -> Unit = {}
) {
    val globalOpacity = when (config.style) {
        InputStyle.STANDARD -> config.opacity
        InputStyle.COMPACT -> config.opacity * 0.9f
        InputStyle.MINIMALIST -> config.opacity * 0.5f
        InputStyle.TRANSPARENT -> config.opacity * 0.25f
        InputStyle.HIDDEN -> 0f
    }
    
    // Improved Scale Factor: Optimized to keep buttons large (~100% size at 42% ratio)
    val portraitRatioScale = if (!isLandscape) {
        (1.22f - (config.portraitGameRatio * 0.52f)).coerceIn(0.85f, 1.25f)
    } else 1.0f

    val globalSizeMultiplier = (when (config.style) {
        InputStyle.COMPACT, InputStyle.MINIMALIST -> config.buttonSize * 0.85f
        else -> config.buttonSize
    }) * portraitRatioScale

    BoxWithConstraints(modifier = modifier) {
        val availableHeight = maxHeight
        
        // vertical scale for internal spacing - more generous floor at 0.6f
        val vScale = (availableHeight.value / 380f).coerceIn(0.6f, 1.0f)

        if (isLandscape) {
            LandscapeControlsLayout(
                platform = platform, model = model, config = config,
                globalOpacity = globalOpacity, globalSizeMultiplier = globalSizeMultiplier,
                buttonProps = buttonProps, selectedButtonId = selectedButtonId, onSelectButton = onSelectButton,
                enabled = enabled, isEditing = isEditing, showFF = showFF,
                onUpdateProps = onUpdateProps, onFastForward = onFastForward,
                onMenuClick = onMenuClick, onInteraction = onInteraction
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                when (platform) {
                    Platform.N64 -> PortraitN64Layout(config, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onMenuClick, onInteraction, vScale)
                    Platform.PS1 -> PortraitPS1Layout(model, config, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onMenuClick, onInteraction, vScale)
                    Platform.GENESIS -> PortraitGenesisLayout(config, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onMenuClick, onInteraction, vScale)
                    Platform.DS -> PortraitDSLayout(config, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onMenuClick, onInteraction, vScale)
                    Platform.GAMECUBE -> PortraitGameCubeLayout(config, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onMenuClick, onInteraction, vScale)
                    Platform.WII -> PortraitWiiLayout(config, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onMenuClick, onInteraction, vScale)
                    else -> PortraitSNESLayout(model, config, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onMenuClick, onInteraction)
                }
            }
        }

        if (isEditing && selectedButtonId != null) {
            val currentProps = buttonProps[selectedButtonId] ?: ButtonProps()
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f), RoundedCornerShape(12.dp)).padding(16.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Selected Button ID: $selectedButtonId", style = MaterialTheme.typography.labelSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Scale, null, modifier = Modifier.size(16.dp))
                        Slider(value = currentProps.scale, onValueChange = { onUpdateProps(selectedButtonId, currentProps.copy(scale = it)) }, valueRange = 0.5f..2.5f, modifier = Modifier.width(120.dp))
                        Text("${(currentProps.scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Opacity, null, modifier = Modifier.size(16.dp))
                        Slider(value = currentProps.alpha, onValueChange = { onUpdateProps(selectedButtonId, currentProps.copy(alpha = it)) }, valueRange = 0.1f..1.0f, modifier = Modifier.width(120.dp))
                        Text("${(currentProps.alpha * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = { onSelectButton(null) }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) { Text("Deselect", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun LandscapeControlsLayout(
    platform: Platform, model: ControllerModel, config: ControlLayoutConfig,
    globalOpacity: Float, globalSizeMultiplier: Float,
    buttonProps: Map<Int, ButtonProps>, selectedButtonId: Int?, onSelectButton: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, showFF: Boolean,
    onUpdateProps: (Int, ButtonProps) -> Unit, onFastForward: (Boolean) -> Unit,
    onMenuClick: () -> Unit, onInteraction: () -> Unit,
    onSwapScreens: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize()) {
        MenuButton(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onClick = onMenuClick, opacity = globalOpacity, onInteraction = onInteraction)
        when (platform) {
            Platform.N64 -> LandscapeN64Layout(config, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
            Platform.PS1 -> LandscapePS1Layout(model, config, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
            Platform.GENESIS -> LandscapeGenesisLayout(config, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
            Platform.DS -> LandscapeDSLayout(config, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction, onSwapScreens)
            Platform.GAMECUBE -> LandscapeGameCubeLayout(config, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
            Platform.WII -> LandscapeWiiLayout(config, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
            else -> LandscapeSNESLayout(model, config, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
        }
    }
}

@Composable
private fun LandscapeN64Layout(
    config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.align(Alignment.TopStart).padding(start = 64.dp, top = 24.dp)) {
            GameButton(icon = Icons.Default.KeyboardArrowLeft, text = "L", buttonId = MainActivity.BTN_L, modifier = Modifier.size((70 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_L], isSelected = selectedId == MainActivity.BTN_L, onSelect = { onSelect(MainActivity.BTN_L) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(end = 64.dp, top = 24.dp)) {
            GameButton(icon = Icons.Default.KeyboardArrowRight, text = "R", buttonId = MainActivity.BTN_R, modifier = Modifier.size((70 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_R], isSelected = selectedId == MainActivity.BTN_R, onSelect = { onSelect(MainActivity.BTN_R) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }
        Box(modifier = Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 70.dp)) {
            DPadLayout(config, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction, isN64 = true)
        }
        Box(modifier = Modifier.align(Alignment.TopStart).padding(start = 72.dp, top = 230.dp)) {
            VirtualJoystick(modifier = Modifier.alpha(opacity), size = (100 * size).dp, onMoved = { x, y -> onInteraction(); (context as? MainActivity)?.setAnalogInput(x, y) })
        }
        Box(modifier = Modifier.align(Alignment.TopStart).padding(start = 165.dp, top = 24.dp)) {
            N64StartButton(opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction)
        }
        Box(modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 30.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val btnSize = (60 * size).dp
                Box(modifier = Modifier.size((130 * size).dp)) {
                    Box(modifier = Modifier.align(Alignment.TopCenter)) {
                        GameButton(text = "Z", buttonId = MainActivity.BTN_Z, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_Z], isSelected = selectedId == MainActivity.BTN_Z, onSelect = { onSelect(MainActivity.BTN_Z) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFFB39DDB), onInteraction = onInteraction)
                    }
                    Box(modifier = Modifier.align(Alignment.BottomStart)) {
                        GameButton(text = "B", buttonId = MainActivity.BTN_B, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_B], isSelected = selectedId == MainActivity.BTN_B, onSelect = { onSelect(MainActivity.BTN_B) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF4CAF50), onInteraction = onInteraction)
                    }
                    Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                        GameButton(text = "A", buttonId = MainActivity.BTN_A, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_A], isSelected = selectedId == MainActivity.BTN_A, onSelect = { onSelect(MainActivity.BTN_A) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF2196F3), onInteraction = onInteraction)
                    }
                }
                CButtonCluster(opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction, baseBtnSize = 35)
            }
        }
    }
}

@Composable
private fun PortraitN64Layout(
    config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit,
    onMenu: () -> Unit, onInteraction: () -> Unit,
    vScale: Float = 1.0f 
) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        // HEADER: L, Start, R
        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = (8 * vScale).dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GameButton(icon = Icons.Default.KeyboardArrowLeft, text = "L", buttonId = MainActivity.BTN_L, modifier = Modifier.size((70 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_L], isSelected = selectedId == MainActivity.BTN_L, onSelect = { onSelect(MainActivity.BTN_L) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            N64StartButton(opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction)
            GameButton(icon = Icons.Default.KeyboardArrowRight, text = "R", buttonId = MainActivity.BTN_R, modifier = Modifier.size((70 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_R], isSelected = selectedId == MainActivity.BTN_R, onSelect = { onSelect(MainActivity.BTN_R) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }

        // MIDDLE AREA: Joystick Left, Actions Right (Shifted UP)
        // Joystick
        Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp).offset(y = (-45 * vScale).dp)) {
            VirtualJoystick(modifier = Modifier.alpha(opacity), size = (130 * size).dp, onMoved = { x, y -> onInteraction(); (context as? MainActivity)?.setAnalogInput(x, y) })
        }

        // Action Triangle (Z, B, A)
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp).offset(y = (-45 * vScale).dp)) {
            val btnSize = (64 * size).dp
            Box(modifier = Modifier.size((140 * size).dp)) {
                Box(modifier = Modifier.align(Alignment.TopCenter)) {
                    GameButton(text = "Z", buttonId = MainActivity.BTN_Z, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_Z], isSelected = selectedId == MainActivity.BTN_Z, onSelect = { onSelect(MainActivity.BTN_Z) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFFB39DDB), onInteraction = onInteraction)
                }
                Box(modifier = Modifier.align(Alignment.BottomStart)) {
                    GameButton(text = "B", buttonId = MainActivity.BTN_B, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_B], isSelected = selectedId == MainActivity.BTN_B, onSelect = { onSelect(MainActivity.BTN_B) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF4CAF50), onInteraction = onInteraction)
                }
                Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                    GameButton(text = "A", buttonId = MainActivity.BTN_A, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_A], isSelected = selectedId == MainActivity.BTN_A, onSelect = { onSelect(MainActivity.BTN_A) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF2196F3), onInteraction = onInteraction)
                }
            }
        }

        // BOTTOM AREA: DPad and C-Buttons (Lowered more toward Menu)
        Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = (55 * vScale).dp)) {
            DPadLayout(config, opacity, size * 0.85f, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction, isN64 = true)
        }
        Box(modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = (55 * vScale).dp)) {
            CButtonCluster(opacity, size * 0.85f, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction, baseBtnSize = 45)
        }

        // MENU BUTTON: Absolute Bottom Middle
        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (16 * vScale).dp)) {
            MenuButton(onClick = onMenu, opacity = opacity, onInteraction = onInteraction)
        }
    }
}

@Composable
private fun LandscapeDSLayout(
    config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit,
    onSwapScreens: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 24.dp)) {
            GameButton(text = "L", buttonId = MainActivity.BTN_L, modifier = Modifier.size((80 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_L], isSelected = selectedId == MainActivity.BTN_L, onSelect = { onSelect(MainActivity.BTN_L) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(end = 32.dp, top = 24.dp)) {
            GameButton(text = "R", buttonId = MainActivity.BTN_R, modifier = Modifier.size((80 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_R], isSelected = selectedId == MainActivity.BTN_R, onSelect = { onSelect(MainActivity.BTN_R) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }
        Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) { DPadLayout(config, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction) }
        
        Row(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            GameButton(text = "SELECT", buttonId = MainActivity.BTN_SELECT, modifier = Modifier.size((70 * size).dp, (30 * size).dp), props = buttonProps[MainActivity.BTN_SELECT], isSelected = selectedId == MainActivity.BTN_SELECT, onSelect = { onSelect(MainActivity.BTN_SELECT) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            GameButton(text = "SWAP", buttonId = MainActivity.BTN_SCREEN_SWAP, modifier = Modifier.size((70 * size).dp, (30 * size).dp), props = buttonProps[MainActivity.BTN_SCREEN_SWAP], isSelected = selectedId == MainActivity.BTN_SCREEN_SWAP, onSelect = { onSelect(MainActivity.BTN_SCREEN_SWAP) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF673AB7), onInteraction = onInteraction)
            GameButton(text = "START", buttonId = MainActivity.BTN_START, modifier = Modifier.size((70 * size).dp, (30 * size).dp), props = buttonProps[MainActivity.BTN_START], isSelected = selectedId == MainActivity.BTN_START, onSelect = { onSelect(MainActivity.BTN_START) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }

        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)) { SNESActionButtons(ControllerModel.GENERIC_ABXY, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction) }
    }
}

@Composable
private fun PortraitDSLayout(
    config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit,
    onMenu: () -> Unit, onInteraction: () -> Unit,
    vScale: Float = 1.0f
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = (8 * vScale).dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            GameButton(text = "L", buttonId = MainActivity.BTN_L, modifier = Modifier.size((75 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_L], isSelected = selectedId == MainActivity.BTN_L, onSelect = { onSelect(MainActivity.BTN_L) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            MenuButton(onClick = onMenu, opacity = opacity, onInteraction = onInteraction)
            GameButton(text = "R", buttonId = MainActivity.BTN_R, modifier = Modifier.size((75 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_R], isSelected = selectedId == MainActivity.BTN_R, onSelect = { onSelect(MainActivity.BTN_R) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) { DPadLayout(config, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction) }
            
            Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GameButton(text = "SWAP SCREEN", buttonId = MainActivity.BTN_SCREEN_SWAP, modifier = Modifier.size((120 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_SCREEN_SWAP], isSelected = selectedId == MainActivity.BTN_SCREEN_SWAP, onSelect = { onSelect(MainActivity.BTN_SCREEN_SWAP) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF673AB7), onInteraction = onInteraction)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    GameButton(text = "SEL", buttonId = MainActivity.BTN_SELECT, modifier = Modifier.size((65 * size).dp, (30 * size).dp), props = buttonProps[MainActivity.BTN_SELECT], isSelected = selectedId == MainActivity.BTN_SELECT, onSelect = { onSelect(MainActivity.BTN_SELECT) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
                    GameButton(text = "START", buttonId = MainActivity.BTN_START, modifier = Modifier.size((65 * size).dp, (30 * size).dp), props = buttonProps[MainActivity.BTN_START], isSelected = selectedId == MainActivity.BTN_START, onSelect = { onSelect(MainActivity.BTN_START) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
                }
            }

            Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { SNESActionButtons(ControllerModel.GENERIC_ABXY, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction) }
        }
    }
}

@Composable
private fun LandscapePS1Layout(
    model: ControllerModel, config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    // Reduced base size for PS1 Landscape Triggers (86x40dp)
    val trigW = (86 * size).dp
    val trigH = (40 * size).dp
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Triggers moved UP (Reduced top padding) and more INWARD
        Column(modifier = Modifier.align(Alignment.TopStart).padding(start = 64.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GameButton(icon = Icons.Default.KeyboardDoubleArrowDown, text = "L2", buttonId = MainActivity.BTN_L2, modifier = Modifier.size(trigW, trigH), props = buttonProps[MainActivity.BTN_L2], isSelected = selectedId == MainActivity.BTN_L2, onSelect = { onSelect(MainActivity.BTN_L2) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            GameButton(icon = Icons.Default.KeyboardArrowLeft, text = "L1", buttonId = MainActivity.BTN_L, modifier = Modifier.size(trigW, trigH), props = buttonProps[MainActivity.BTN_L], isSelected = selectedId == MainActivity.BTN_L, onSelect = { onSelect(MainActivity.BTN_L) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }
        Column(modifier = Modifier.align(Alignment.TopEnd).padding(end = 64.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GameButton(icon = Icons.Default.KeyboardDoubleArrowDown, text = "R2", buttonId = MainActivity.BTN_R2, modifier = Modifier.size(trigW, trigH), props = buttonProps[MainActivity.BTN_R2], isSelected = selectedId == MainActivity.BTN_R2, onSelect = { onSelect(MainActivity.BTN_R2) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            GameButton(icon = Icons.Default.KeyboardArrowRight, text = "R1", buttonId = MainActivity.BTN_R, modifier = Modifier.size(trigW, trigH), props = buttonProps[MainActivity.BTN_R], isSelected = selectedId == MainActivity.BTN_R, onSelect = { onSelect(MainActivity.BTN_R) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }
        // DPad and Action Buttons moved DOWN
        Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp, top = 48.dp)) { DPadLayout(config, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction) }
        Row(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            GameButton(icon = Icons.Default.HorizontalRule, text = "SEL", buttonId = MainActivity.BTN_SELECT, modifier = Modifier.size((60 * size).dp, (30 * size).dp), props = buttonProps[MainActivity.BTN_SELECT], isSelected = selectedId == MainActivity.BTN_SELECT, onSelect = { onSelect(MainActivity.BTN_SELECT) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            GameButton(icon = Icons.Default.PlayArrow, text = "START", buttonId = MainActivity.BTN_START, modifier = Modifier.size((60 * size).dp, (30 * size).dp), props = buttonProps[MainActivity.BTN_START], isSelected = selectedId == MainActivity.BTN_START, onSelect = { onSelect(MainActivity.BTN_START) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp, top = 48.dp)) { PS1ActionButtons(model, config, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction) }
    }
}

@Composable
private fun PortraitPS1Layout(
    model: ControllerModel, config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit,
    onMenu: () -> Unit, onInteraction: () -> Unit,
    vScale: Float = 1.0f
) {
    // Reduced base size for PS1 Portrait Triggers (74x34dp)
    val triggerSize = (74 * size).dp
    val triggerHeight = (34 * size).dp
    val isModern = config.visualStyle == VisualStyle.MODERN

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = (8 * vScale).dp), contentAlignment = Alignment.TopCenter) {
            MenuButton(onClick = onMenu, opacity = opacity, onInteraction = onInteraction)
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // TRIGGERS moved UP (Reduced top padding)
            Column(modifier = Modifier.align(Alignment.TopStart).padding(start = 22.dp, top = (12 * vScale).dp), verticalArrangement = Arrangement.spacedBy((4 * vScale).dp)) {
                GameButton(icon = Icons.Default.KeyboardDoubleArrowDown, text = "L2", buttonId = MainActivity.BTN_L2, modifier = Modifier.size(triggerSize, triggerHeight), props = buttonProps[MainActivity.BTN_L2], isSelected = selectedId == MainActivity.BTN_L2, onSelect = { onSelect(MainActivity.BTN_L2) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
                GameButton(icon = Icons.Default.KeyboardArrowLeft, text = "L1", buttonId = MainActivity.BTN_L, modifier = Modifier.size(triggerSize, triggerHeight).offset(x = if (isModern) 24.dp else 0.dp, y = if (isModern) 12.dp else 0.dp), props = buttonProps[MainActivity.BTN_L], isSelected = selectedId == MainActivity.BTN_L, onSelect = { onSelect(MainActivity.BTN_L) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            }

            Column(modifier = Modifier.align(Alignment.TopEnd).padding(end = 22.dp, top = (12 * vScale).dp), verticalArrangement = Arrangement.spacedBy((4 * vScale).dp)) {
                GameButton(icon = Icons.Default.KeyboardDoubleArrowDown, text = "R2", buttonId = MainActivity.BTN_R2, modifier = Modifier.size(triggerSize, triggerHeight), props = buttonProps[MainActivity.BTN_R2], isSelected = selectedId == MainActivity.BTN_R2, onSelect = { onSelect(MainActivity.BTN_R2) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
                GameButton(icon = Icons.Default.KeyboardArrowRight, text = "R1", buttonId = MainActivity.BTN_R, modifier = Modifier.size(triggerSize, triggerHeight).offset(x = if (isModern) (-24).dp else 0.dp, y = if (isModern) 12.dp else 0.dp), props = buttonProps[MainActivity.BTN_R], isSelected = selectedId == MainActivity.BTN_R, onSelect = { onSelect(MainActivity.BTN_R) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            }

            Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {
                DPadLayout(config, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction)
            }
            CenterButtons(config, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction)
            Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
                PS1ActionButtons(model, config, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction)
            }
        }
    }
}

@Composable
private fun PortraitSNESLayout(
    model: ControllerModel, config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit,
    onMenu: () -> Unit, onInteraction: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ShoulderButtonsPortrait(config, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onMenu, onInteraction)
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Align DPad and Actions in a Row to keep them lined up
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DPadLayout(config, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction)
                SNESActionButtons(model, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction)
            }
            
            // Move Select/Start to the bottom center area
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) {
                CenterButtons(config, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction)
            }
        }
    }
}

@Composable
private fun LandscapeSNESLayout(
    model: ControllerModel, config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) { DPadLayout(config, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction) }
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)) { SNESActionButtons(model, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction) }
    }
}

@Composable
private fun LandscapeGenesisLayout(
    config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) { DPadLayout(config, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction) }
        
        // Added missing START button for Genesis Landscape
        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
            GameButton(
                text = "START", 
                buttonId = MainActivity.BTN_START, 
                modifier = Modifier.size((70 * size).dp, (30 * size).dp), 
                props = buttonProps[MainActivity.BTN_START], 
                isSelected = selectedId == MainActivity.BTN_START, 
                onSelect = { onSelect(MainActivity.BTN_START) }, 
                enabled = enabled, 
                isEditing = isEditing, 
                onUpdateProps = onUpdate, 
                alpha = opacity, 
                onInteraction = onInteraction
            )
        }

        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)) { GenesisActionButtons(opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction) }
    }
}

@Composable
private fun PortraitGenesisLayout(
    config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit,
    onMenu: () -> Unit, onInteraction: () -> Unit,
    vScale: Float = 1.0f
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().padding(top = (8 * vScale).dp), contentAlignment = Alignment.TopCenter) {
            MenuButton(onClick = onMenu, opacity = opacity, onInteraction = onInteraction)
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = (24 * vScale).dp)) {
                GameButton(text = "START", buttonId = MainActivity.BTN_START, modifier = Modifier.size((70 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_START], isSelected = selectedId == MainActivity.BTN_START, onSelect = { onSelect(MainActivity.BTN_START) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            }
            Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) { DPadLayout(config, opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction) }
            Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { GenesisActionButtons(opacity, size, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction) }
        }
    }
}

@Composable
private fun LandscapeGameCubeLayout(
    config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.align(Alignment.TopStart).padding(start = 64.dp, top = 20.dp)) {
            GameButton(text = "L", buttonId = MainActivity.BTN_L, modifier = Modifier.size((75 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_L], isSelected = selectedId == MainActivity.BTN_L, onSelect = { onSelect(MainActivity.BTN_L) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(end = 64.dp, top = 20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GameButton(text = "Z", buttonId = MainActivity.BTN_Z, modifier = Modifier.size((50 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_Z], isSelected = selectedId == MainActivity.BTN_Z, onSelect = { onSelect(MainActivity.BTN_Z) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF7E57C2), onInteraction = onInteraction)
                GameButton(text = "R", buttonId = MainActivity.BTN_R, modifier = Modifier.size((75 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_R], isSelected = selectedId == MainActivity.BTN_R, onSelect = { onSelect(MainActivity.BTN_R) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            }
        }
        Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                VirtualJoystick(modifier = Modifier.alpha(opacity), size = (110 * size).dp, onMoved = { x, y -> onInteraction(); (context as? MainActivity)?.setAnalogInput(x, y) })
                DPadLayout(config, opacity, size * 0.75f, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction)
            }
        }
        Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)) {
            GameButton(text = "START", buttonId = MainActivity.BTN_START, modifier = Modifier.size((65 * size).dp, (30 * size).dp), props = buttonProps[MainActivity.BTN_START], isSelected = selectedId == MainActivity.BTN_START, onSelect = { onSelect(MainActivity.BTN_START) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)) {
            Box(modifier = Modifier.size((130 * size).dp)) {
                Box(modifier = Modifier.align(Alignment.Center)) {
                    GameButton(text = "A", buttonId = MainActivity.BTN_A, modifier = Modifier.size((64 * size).dp), props = buttonProps[MainActivity.BTN_A], isSelected = selectedId == MainActivity.BTN_A, onSelect = { onSelect(MainActivity.BTN_A) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF4CAF50), onInteraction = onInteraction)
                }
                Box(modifier = Modifier.align(Alignment.CenterStart)) {
                    GameButton(text = "B", buttonId = MainActivity.BTN_B, modifier = Modifier.size((42 * size).dp), props = buttonProps[MainActivity.BTN_B], isSelected = selectedId == MainActivity.BTN_B, onSelect = { onSelect(MainActivity.BTN_B) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFFF44336), onInteraction = onInteraction)
                }
                Box(modifier = Modifier.align(Alignment.TopCenter)) {
                    GameButton(text = "Y", buttonId = MainActivity.BTN_Y, modifier = Modifier.size((42 * size).dp), props = buttonProps[MainActivity.BTN_Y], isSelected = selectedId == MainActivity.BTN_Y, onSelect = { onSelect(MainActivity.BTN_Y) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFFE0E0E0), iconTint = Color.Black, onInteraction = onInteraction)
                }
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    GameButton(text = "X", buttonId = MainActivity.BTN_X, modifier = Modifier.size((42 * size).dp), props = buttonProps[MainActivity.BTN_X], isSelected = selectedId == MainActivity.BTN_X, onSelect = { onSelect(MainActivity.BTN_X) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFFE0E0E0), iconTint = Color.Black, onInteraction = onInteraction)
                }
            }
        }
    }
}

@Composable
private fun PortraitGameCubeLayout(
    config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit,
    onMenu: () -> Unit, onInteraction: () -> Unit,
    vScale: Float = 1.0f
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = (8 * vScale).dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            GameButton(text = "L", buttonId = MainActivity.BTN_L, modifier = Modifier.size((75 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_L], isSelected = selectedId == MainActivity.BTN_L, onSelect = { onSelect(MainActivity.BTN_L) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            MenuButton(onClick = onMenu, opacity = opacity, onInteraction = onInteraction)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameButton(text = "Z", buttonId = MainActivity.BTN_Z, modifier = Modifier.size((45 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_Z], isSelected = selectedId == MainActivity.BTN_Z, onSelect = { onSelect(MainActivity.BTN_Z) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF7E57C2), onInteraction = onInteraction)
                GameButton(text = "R", buttonId = MainActivity.BTN_R, modifier = Modifier.size((75 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_R], isSelected = selectedId == MainActivity.BTN_R, onSelect = { onSelect(MainActivity.BTN_R) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    VirtualJoystick(modifier = Modifier.alpha(opacity), size = (110 * size).dp, onMoved = { x, y -> onInteraction(); (context as? MainActivity)?.setAnalogInput(x, y) })
                    DPadLayout(config, opacity, size * 0.75f, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction)
                }
            }
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
                GameButton(text = "START", buttonId = MainActivity.BTN_START, modifier = Modifier.size((65 * size).dp, (30 * size).dp), props = buttonProps[MainActivity.BTN_START], isSelected = selectedId == MainActivity.BTN_START, onSelect = { onSelect(MainActivity.BTN_START) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            }
            Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
                Box(modifier = Modifier.size((130 * size).dp)) {
                    Box(modifier = Modifier.align(Alignment.Center)) {
                        GameButton(text = "A", buttonId = MainActivity.BTN_A, modifier = Modifier.size((60 * size).dp), props = buttonProps[MainActivity.BTN_A], isSelected = selectedId == MainActivity.BTN_A, onSelect = { onSelect(MainActivity.BTN_A) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF4CAF50), onInteraction = onInteraction)
                    }
                    Box(modifier = Modifier.align(Alignment.CenterStart)) {
                        GameButton(text = "B", buttonId = MainActivity.BTN_B, modifier = Modifier.size((40 * size).dp), props = buttonProps[MainActivity.BTN_B], isSelected = selectedId == MainActivity.BTN_B, onSelect = { onSelect(MainActivity.BTN_B) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFFF44336), onInteraction = onInteraction)
                    }
                    Box(modifier = Modifier.align(Alignment.TopCenter)) {
                        GameButton(text = "Y", buttonId = MainActivity.BTN_Y, modifier = Modifier.size((40 * size).dp), props = buttonProps[MainActivity.BTN_Y], isSelected = selectedId == MainActivity.BTN_Y, onSelect = { onSelect(MainActivity.BTN_Y) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFFE0E0E0), iconTint = Color.Black, onInteraction = onInteraction)
                    }
                    Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                        GameButton(text = "X", buttonId = MainActivity.BTN_X, modifier = Modifier.size((40 * size).dp), props = buttonProps[MainActivity.BTN_X], isSelected = selectedId == MainActivity.BTN_X, onSelect = { onSelect(MainActivity.BTN_X) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFFE0E0E0), iconTint = Color.Black, onInteraction = onInteraction)
                    }
                }
            }
        }
    }
}

@Composable
private fun LandscapeWiiLayout(
    config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.align(Alignment.TopStart).padding(start = 64.dp, top = 20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameButton(text = "C", buttonId = MainActivity.BTN_L, modifier = Modifier.size((50 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_L], isSelected = selectedId == MainActivity.BTN_L, onSelect = { onSelect(MainActivity.BTN_L) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF78909C), onInteraction = onInteraction)
                GameButton(text = "Z", buttonId = MainActivity.BTN_R, modifier = Modifier.size((50 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_R], isSelected = selectedId == MainActivity.BTN_R, onSelect = { onSelect(MainActivity.BTN_R) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF546E7A), onInteraction = onInteraction)
            }
        }
        Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GameButton(text = "-", buttonId = MainActivity.BTN_L2, modifier = Modifier.size((38 * size).dp), props = buttonProps[MainActivity.BTN_L2], isSelected = selectedId == MainActivity.BTN_L2, onSelect = { onSelect(MainActivity.BTN_L2) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
                GameButton(text = "HOME", buttonId = MainActivity.BTN_START, modifier = Modifier.size((55 * size).dp, (38 * size).dp), props = buttonProps[MainActivity.BTN_START], isSelected = selectedId == MainActivity.BTN_START, onSelect = { onSelect(MainActivity.BTN_START) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF03A9F4), onInteraction = onInteraction)
                GameButton(text = "+", buttonId = MainActivity.BTN_R2, modifier = Modifier.size((38 * size).dp), props = buttonProps[MainActivity.BTN_R2], isSelected = selectedId == MainActivity.BTN_R2, onSelect = { onSelect(MainActivity.BTN_R2) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            }
        }
        Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VirtualJoystick(modifier = Modifier.alpha(opacity), size = (110 * size).dp, onMoved = { x, y -> onInteraction(); (context as? MainActivity)?.setAnalogInput(x, y) })
                DPadLayout(config, opacity, size * 0.75f, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction)
            }
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GameButton(text = "A", buttonId = MainActivity.BTN_A, modifier = Modifier.size((58 * size).dp), props = buttonProps[MainActivity.BTN_A], isSelected = selectedId == MainActivity.BTN_A, onSelect = { onSelect(MainActivity.BTN_A) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF0288D1), onInteraction = onInteraction)
                    GameButton(text = "B", buttonId = MainActivity.BTN_B, modifier = Modifier.size((58 * size).dp), props = buttonProps[MainActivity.BTN_B], isSelected = selectedId == MainActivity.BTN_B, onSelect = { onSelect(MainActivity.BTN_B) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF0288D1), onInteraction = onInteraction)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GameButton(text = "1", buttonId = MainActivity.BTN_X, modifier = Modifier.size((48 * size).dp), props = buttonProps[MainActivity.BTN_X], isSelected = selectedId == MainActivity.BTN_X, onSelect = { onSelect(MainActivity.BTN_X) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
                    GameButton(text = "2", buttonId = MainActivity.BTN_Y, modifier = Modifier.size((48 * size).dp), props = buttonProps[MainActivity.BTN_Y], isSelected = selectedId == MainActivity.BTN_Y, onSelect = { onSelect(MainActivity.BTN_Y) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
                }
            }
        }
    }
}

@Composable
private fun PortraitWiiLayout(
    config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit,
    onMenu: () -> Unit, onInteraction: () -> Unit,
    vScale: Float = 1.0f
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = (8 * vScale).dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GameButton(text = "C", buttonId = MainActivity.BTN_L, modifier = Modifier.size((45 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_L], isSelected = selectedId == MainActivity.BTN_L, onSelect = { onSelect(MainActivity.BTN_L) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF78909C), onInteraction = onInteraction)
                GameButton(text = "Z", buttonId = MainActivity.BTN_R, modifier = Modifier.size((45 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_R], isSelected = selectedId == MainActivity.BTN_R, onSelect = { onSelect(MainActivity.BTN_R) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF546E7A), onInteraction = onInteraction)
            }
            MenuButton(onClick = onMenu, opacity = opacity, onInteraction = onInteraction)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GameButton(text = "-", buttonId = MainActivity.BTN_L2, modifier = Modifier.size((35 * size).dp), props = buttonProps[MainActivity.BTN_L2], isSelected = selectedId == MainActivity.BTN_L2, onSelect = { onSelect(MainActivity.BTN_L2) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
                GameButton(text = "+", buttonId = MainActivity.BTN_R2, modifier = Modifier.size((35 * size).dp), props = buttonProps[MainActivity.BTN_R2], isSelected = selectedId == MainActivity.BTN_R2, onSelect = { onSelect(MainActivity.BTN_R2) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    VirtualJoystick(modifier = Modifier.alpha(opacity), size = (100 * size).dp, onMoved = { x, y -> onInteraction(); (context as? MainActivity)?.setAnalogInput(x, y) })
                    DPadLayout(config, opacity, size * 0.75f, buttonProps, selectedId, onSelect, enabled, isEditing, onUpdate, onInteraction)
                }
            }
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
                GameButton(text = "HOME", buttonId = MainActivity.BTN_START, modifier = Modifier.size((65 * size).dp, (30 * size).dp), props = buttonProps[MainActivity.BTN_START], isSelected = selectedId == MainActivity.BTN_START, onSelect = { onSelect(MainActivity.BTN_START) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF03A9F4), onInteraction = onInteraction)
            }
            Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GameButton(text = "A", buttonId = MainActivity.BTN_A, modifier = Modifier.size((52 * size).dp), props = buttonProps[MainActivity.BTN_A], isSelected = selectedId == MainActivity.BTN_A, onSelect = { onSelect(MainActivity.BTN_A) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF0288D1), onInteraction = onInteraction)
                        GameButton(text = "B", buttonId = MainActivity.BTN_B, modifier = Modifier.size((52 * size).dp), props = buttonProps[MainActivity.BTN_B], isSelected = selectedId == MainActivity.BTN_B, onSelect = { onSelect(MainActivity.BTN_B) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = Color(0xFF0288D1), onInteraction = onInteraction)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GameButton(text = "1", buttonId = MainActivity.BTN_X, modifier = Modifier.size((44 * size).dp), props = buttonProps[MainActivity.BTN_X], isSelected = selectedId == MainActivity.BTN_X, onSelect = { onSelect(MainActivity.BTN_X) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
                        GameButton(text = "2", buttonId = MainActivity.BTN_Y, modifier = Modifier.size((44 * size).dp), props = buttonProps[MainActivity.BTN_Y], isSelected = selectedId == MainActivity.BTN_Y, onSelect = { onSelect(MainActivity.BTN_Y) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
                    }
                }
            }
        }
    }
}

@Composable
private fun DPadLayout(
    config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit,
    onInteraction: () -> Unit, isN64: Boolean = false
) {
    // Significantly increased base size for DPad (68dp from 64dp) for better reach
    val btnSize = ((if (isN64) 55 else 56) * size).dp
    val isClassic = config.visualStyle == VisualStyle.CLASSIC
    val buttonColor = if (isN64 && isClassic) Color(0xFFE0E0E0) else MaterialTheme.colorScheme.primary
    val iconTint = if (isN64 && isClassic) Color.Black else Color.White
    val borderColor = if (isClassic) Color.LightGray.copy(alpha = 0.5f) else Color.Transparent
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GameButton(icon = Icons.Default.ArrowUpward, buttonId = MainActivity.BTN_UP, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_UP], isSelected = selectedId == MainActivity.BTN_UP, onSelect = { onSelect(MainActivity.BTN_UP) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = buttonColor, iconTint = iconTint, borderColor = borderColor, onInteraction = onInteraction)
        Row {
            GameButton(icon = Icons.AutoMirrored.Filled.ArrowBack, buttonId = MainActivity.BTN_LEFT, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_LEFT], isSelected = selectedId == MainActivity.BTN_LEFT, onSelect = { onSelect(MainActivity.BTN_LEFT) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = buttonColor, iconTint = iconTint, borderColor = borderColor, onInteraction = onInteraction)
            Spacer(Modifier.size(btnSize))
            GameButton(icon = Icons.AutoMirrored.Filled.ArrowForward, buttonId = MainActivity.BTN_RIGHT, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_RIGHT], isSelected = selectedId == MainActivity.BTN_RIGHT, onSelect = { onSelect(MainActivity.BTN_RIGHT) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = buttonColor, iconTint = iconTint, borderColor = borderColor, onInteraction = onInteraction)
        }
        GameButton(icon = Icons.Default.ArrowDownward, buttonId = MainActivity.BTN_DOWN, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_DOWN], isSelected = selectedId == MainActivity.BTN_DOWN, onSelect = { onSelect(MainActivity.BTN_DOWN) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = buttonColor, iconTint = iconTint, borderColor = borderColor, onInteraction = onInteraction)
    }
}

@Composable
private fun SNESActionButtons(
    model: ControllerModel, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    val btnSize = (54 * size).dp
    val (labelA, labelB, labelX, labelY) = when(model) {
        ControllerModel.PLAYSTATION -> listOf("○", "✕", "△", "□")
        ControllerModel.XBOX -> listOf("B", "A", "Y", "X")
        else -> listOf("A", "B", "X", "Y")
    }
    
    // SNES Colors: Green, Blue, Red, Yellow
    val colorA = Color(0xFFF44336) // Red
    val colorB = Color(0xFFFFEB3B) // Yellow
    val colorX = Color(0xFF2196F3) // Blue
    val colorY = Color(0xFF4CAF50) // Green
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GameButton(text = labelX, buttonId = MainActivity.BTN_X, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_X], isSelected = selectedId == MainActivity.BTN_X, onSelect = { onSelect(MainActivity.BTN_X) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = colorX, onInteraction = onInteraction)
        Row {
            GameButton(text = labelY, buttonId = MainActivity.BTN_Y, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_Y], isSelected = selectedId == MainActivity.BTN_Y, onSelect = { onSelect(MainActivity.BTN_Y) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = colorY, onInteraction = onInteraction)
            Spacer(Modifier.size(btnSize / 2))
            GameButton(text = labelA, buttonId = MainActivity.BTN_A, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_A], isSelected = selectedId == MainActivity.BTN_A, onSelect = { onSelect(MainActivity.BTN_A) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = colorA, onInteraction = onInteraction)
        }
        GameButton(text = labelB, buttonId = MainActivity.BTN_B, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_B], isSelected = selectedId == MainActivity.BTN_B, onSelect = { onSelect(MainActivity.BTN_B) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = colorB, onInteraction = onInteraction)
    }
}

@Composable
private fun PS1ActionButtons(
    model: ControllerModel, config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    // Increased base size for PS1 Action Buttons (56dp)
    val btnSize = (56 * size).dp
    val isClassic = config.visualStyle == VisualStyle.CLASSIC
    val buttonColor = if (isClassic) Color.Black else MaterialTheme.colorScheme.primary
    val borderColor = if (isClassic) Color(0xFFBDBDBD) else Color.Transparent
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GameButton(text = if (isClassic) "" else "△", buttonId = MainActivity.BTN_X, modifier = Modifier.size(btnSize), icon = Icons.Default.ChangeHistory, iconOffset = DpOffset(0.dp, (-2).dp), props = buttonProps[MainActivity.BTN_X], isSelected = selectedId == MainActivity.BTN_X, onSelect = { onSelect(MainActivity.BTN_X) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = buttonColor, iconTint = if (isClassic) Color.Green else Color.White, borderColor = borderColor, onInteraction = onInteraction)
        Row {
            GameButton(text = if (isClassic) "" else "□", buttonId = MainActivity.BTN_Y, modifier = Modifier.size(btnSize), icon = Icons.Default.CheckBoxOutlineBlank, props = buttonProps[MainActivity.BTN_Y], isSelected = selectedId == MainActivity.BTN_Y, onSelect = { onSelect(MainActivity.BTN_Y) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = buttonColor, iconTint = if (isClassic) Color(0xFFF06292) else Color.White, borderColor = borderColor, onInteraction = onInteraction)
            Spacer(Modifier.size(btnSize))
            GameButton(text = if (isClassic) "" else "○", buttonId = MainActivity.BTN_A, modifier = Modifier.size(btnSize), icon = Icons.Default.RadioButtonUnchecked, iconOffset = DpOffset(1.dp, 0.dp), props = buttonProps[MainActivity.BTN_A], isSelected = selectedId == MainActivity.BTN_A, onSelect = { onSelect(MainActivity.BTN_A) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = buttonColor, iconTint = if (isClassic) Color.Red else Color.White, borderColor = borderColor, onInteraction = onInteraction)
        }
        GameButton(text = if (isClassic) "" else "✕", buttonId = MainActivity.BTN_B, modifier = Modifier.size(btnSize), icon = Icons.Default.Close, props = buttonProps[MainActivity.BTN_B], isSelected = selectedId == MainActivity.BTN_B, onSelect = { onSelect(MainActivity.BTN_B) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = buttonColor, iconTint = if (isClassic) Color(0xFF64B5F6) else Color.White, borderColor = borderColor, onInteraction = onInteraction)
    }
}

@Composable
private fun GenesisActionButtons(
    opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    val btnSize = (60 * size).dp
    // Triangle cluster (B top, A left, C right)
    Box(modifier = Modifier.size((140 * size).dp)) {
        // Top: B (Center)
        Box(modifier = Modifier.align(Alignment.TopCenter)) {
            GameButton(text = "B", buttonId = MainActivity.BTN_B, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_B], isSelected = selectedId == MainActivity.BTN_B, onSelect = { onSelect(MainActivity.BTN_B) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }
        // Bottom Left: A
        Box(modifier = Modifier.align(Alignment.BottomStart)) {
            GameButton(text = "A", buttonId = MainActivity.BTN_Y, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_Y], isSelected = selectedId == MainActivity.BTN_Y, onSelect = { onSelect(MainActivity.BTN_Y) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }
        // Bottom Right: C
        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
            GameButton(text = "C", buttonId = MainActivity.BTN_A, modifier = Modifier.size(btnSize), props = buttonProps[MainActivity.BTN_A], isSelected = selectedId == MainActivity.BTN_A, onSelect = { onSelect(MainActivity.BTN_A) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }
    }
}

@Composable
private fun CButtonCluster(
    opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit,
    baseBtnSize: Int = 50
) {
    val btnSize = (baseBtnSize * size).dp
    val cColor = Color(0xFFFFD54F)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GameButton(text = "C", buttonId = MainActivity.BTN_X, modifier = Modifier.size(btnSize), icon = Icons.Default.ArrowDropUp, props = buttonProps[MainActivity.BTN_X], isSelected = selectedId == MainActivity.BTN_X, onSelect = { onSelect(MainActivity.BTN_X) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = cColor, onInteraction = onInteraction)
        Row {
            GameButton(text = "C", buttonId = MainActivity.BTN_Y, modifier = Modifier.size(btnSize), icon = Icons.AutoMirrored.Filled.ArrowLeft, props = buttonProps[MainActivity.BTN_Y], isSelected = selectedId == MainActivity.BTN_Y, onSelect = { onSelect(MainActivity.BTN_Y) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = cColor, onInteraction = onInteraction)
            Spacer(Modifier.size(btnSize))
            GameButton(text = "C", buttonId = MainActivity.BTN_R2, modifier = Modifier.size(btnSize), icon = Icons.AutoMirrored.Filled.ArrowRight, props = buttonProps[MainActivity.BTN_R2], isSelected = selectedId == MainActivity.BTN_R2, onSelect = { onSelect(MainActivity.BTN_R2) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = cColor, onInteraction = onInteraction)
        }
        GameButton(text = "C", buttonId = MainActivity.BTN_R3, modifier = Modifier.size(btnSize), icon = Icons.Default.ArrowDropDown, props = buttonProps[MainActivity.BTN_R3], isSelected = selectedId == MainActivity.BTN_R3, onSelect = { onSelect(MainActivity.BTN_R3) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, buttonColor = cColor, onInteraction = onInteraction)
    }
}

@Composable
private fun N64StartButton(
    opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    GameButton(
        text = "START", 
        buttonId = MainActivity.BTN_START, 
        modifier = Modifier.size((42 * size).dp), // Shrunken from 50dp
        props = buttonProps[MainActivity.BTN_START], 
        isSelected = selectedId == MainActivity.BTN_START, 
        onSelect = { onSelect(MainActivity.BTN_START) }, 
        enabled = enabled, 
        isEditing = isEditing, 
        onUpdateProps = onUpdate, 
        alpha = opacity, 
        buttonColor = Color.Red, 
        onInteraction = onInteraction,
        textStyle = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            fontFamily = FontFamily.SansSerif,
            fontSize = (9 * size).sp // Slightly smaller text for smaller button
        )
    )
}

@Composable
private fun ShoulderButtonsPortrait(
    config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit,
    onMenu: () -> Unit, onInteraction: () -> Unit, isN64: Boolean = false
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        GameButton(icon = Icons.Default.KeyboardArrowLeft, text = if (isN64) "L" else "L1", buttonId = MainActivity.BTN_L, modifier = Modifier.size((70 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_L], isSelected = selectedId == MainActivity.BTN_L, onSelect = { onSelect(MainActivity.BTN_L) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        MenuButton(onClick = onMenu, opacity = opacity, onInteraction = onInteraction)
        GameButton(icon = Icons.Default.KeyboardArrowRight, text = if (isN64) "R" else "R1", buttonId = MainActivity.BTN_R, modifier = Modifier.size((70 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_R], isSelected = selectedId == MainActivity.BTN_R, onSelect = { onSelect(MainActivity.BTN_R) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
    }
}

@Composable
private fun CenterButtons(
    config: ControlLayoutConfig, opacity: Float, size: Float,
    buttonProps: Map<Int, ButtonProps>, selectedId: Int?, onSelect: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdate: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(bottom = 28.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            GameButton(icon = Icons.Default.HorizontalRule, text = "SEL", buttonId = MainActivity.BTN_SELECT, modifier = Modifier.size((70 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_SELECT], isSelected = selectedId == MainActivity.BTN_SELECT, onSelect = { onSelect(MainActivity.BTN_SELECT) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
            GameButton(icon = Icons.Default.PlayArrow, text = "START", buttonId = MainActivity.BTN_START, modifier = Modifier.size((70 * size).dp, (35 * size).dp), props = buttonProps[MainActivity.BTN_START], isSelected = selectedId == MainActivity.BTN_START, onSelect = { onSelect(MainActivity.BTN_START) }, enabled = enabled, isEditing = isEditing, onUpdateProps = onUpdate, alpha = opacity, onInteraction = onInteraction)
        }
    }
}

@Composable
fun GameButton(
    text: String = "",
    buttonId: Int,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconOffset: DpOffset = DpOffset.Zero,
    props: ButtonProps? = null,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    enabled: Boolean = true,
    isEditing: Boolean = false,
    onUpdateProps: (Int, ButtonProps) -> Unit = { _, _ -> },
    alpha: Float = 1.0f,
    buttonColor: Color = MaterialTheme.colorScheme.primary,
    iconTint: Color = Color.White,
    borderColor: Color = Color.Transparent,
    onInteraction: () -> Unit = {},
    textStyle: TextStyle? = null
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val currentProps = props ?: ButtonProps()
    val currentPropsState = rememberUpdatedState(currentProps)
    val onUpdateState = rememberUpdatedState(onUpdateProps)
    val offsetX = currentProps.x
    val offsetY = currentProps.y
    val currentScale = currentProps.scale
    val currentAlpha = currentProps.alpha * alpha
    val dragModifier = if (isEditing) { Modifier.pointerInput(buttonId) { detectDragGestures { change, dragAmount -> change.consume(); val p = currentPropsState.value; onUpdateState.value(buttonId, p.copy(x = p.x + dragAmount.x, y = p.y + dragAmount.y)) } } } else Modifier
    val selectionBorder = if (isEditing && isSelected) { Modifier.border(3.dp, Color.Cyan, CircleShape) } else if (isEditing) { Modifier.border(1.dp, Color.Red.copy(alpha = 0.5f), CircleShape) } else Modifier
    val customBorder = if (borderColor != Color.Transparent) { Modifier.border(2.dp, borderColor, CircleShape) } else Modifier
    LaunchedEffect(interactionSource, enabled) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> if (!isEditing && enabled) { 
                    onInteraction()
                    if (buttonId == MainActivity.BTN_SCREEN_SWAP) {
                        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                        val currentLayout = prefs.getString("melonds_screen_layout", "Top/Bottom") ?: "Top/Bottom"
                        val newLayout = if (currentLayout == "Top/Bottom") "Bottom/Top" else "Top/Bottom"
                        prefs.edit().putString("melonds_screen_layout", newLayout).putString("desmume_screens_layout", newLayout).apply()
                        context.findMainActivity()?.nativeSetDsScreenLayout(newLayout)
                        Log.i("ControlsOverlay", "DS Screen Layout swapped to: $newLayout")
                    } else {
                        context.findMainActivity()?.sendInput(buttonId, 1) 
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> if (!isEditing && enabled) { 
                    if (buttonId != MainActivity.BTN_SCREEN_SWAP) {
                        context.findMainActivity()?.sendInput(buttonId, 0) 
                    }
                }
            }
        }
    }
    Button(
        onClick = { if (isEditing) onSelect() },
        interactionSource = interactionSource,
        enabled = enabled,
        modifier = modifier.offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }.scale(currentScale).then(dragModifier).then(selectionBorder).then(customBorder).alpha(currentAlpha),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
        contentPadding = PaddingValues(0.dp)
    ) {
        if (icon != null) { Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp).offset(x = iconOffset.x, y = iconOffset.y), tint = iconTint) }
        else { 
            Text(
                text = text, 
                style = textStyle ?: MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), 
                color = iconTint
            ) 
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun N64PortraitLargeScreenPreview() {
    val ratio = 0.60f
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Box(modifier = Modifier.fillMaxWidth().weight(ratio).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.fillMaxHeight().aspectRatio(4f/3f).background(Color.Gray), contentAlignment = Alignment.Center) {
                    Text("GAME (4:3)", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f - ratio)) {
                PortraitN64Layout(
                    config = ControlLayoutConfig(visualStyle = VisualStyle.CLASSIC, portraitGameRatio = ratio),
                    opacity = 1f,
                    size = 1f,
                    buttonProps = emptyMap(),
                    selectedId = null,
                    onSelect = {},
                    enabled = true,
                    isEditing = false,
                    onUpdate = { _, _ -> },
                    onMenu = {},
                    onInteraction = {},
                    vScale = 0.6f 
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun N64PortraitClassicPreview() {
    val ratio = 0.42f
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Box(modifier = Modifier.fillMaxWidth().weight(ratio).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.fillMaxHeight().aspectRatio(4f/3f).background(Color.Gray), contentAlignment = Alignment.Center) {
                    Text("GAME (4:3)", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f - ratio)) {
                PortraitN64Layout(
                    config = ControlLayoutConfig(visualStyle = VisualStyle.CLASSIC, portraitGameRatio = ratio),
                    opacity = 1f,
                    size = 1f,
                    buttonProps = emptyMap(),
                    selectedId = null,
                    onSelect = {},
                    enabled = true,
                    isEditing = false,
                    onUpdate = { _, _ -> },
                    onMenu = {},
                    onInteraction = {},
                    vScale = 0.9f
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PS1PortraitLargeScreenPreview() {
    val ratio = 0.60f
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Box(modifier = Modifier.fillMaxWidth().weight(ratio).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.fillMaxHeight().aspectRatio(4f/3f).background(Color.Gray), contentAlignment = Alignment.Center) {
                    Text("GAME (4:3)", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f - ratio)) {
                PortraitPS1Layout(
                    model = ControllerModel.PLAYSTATION,
                    config = ControlLayoutConfig(visualStyle = VisualStyle.CLASSIC, portraitGameRatio = ratio),
                    opacity = 1f,
                    size = 1f,
                    buttonProps = emptyMap(),
                    selectedId = null,
                    onSelect = {},
                    enabled = true,
                    isEditing = false,
                    onUpdate = { _, _ -> },
                    onMenu = {},
                    onInteraction = {},
                    vScale = 0.6f
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PS1PortraitClassicPreview() {
    val ratio = 0.42f
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Box(modifier = Modifier.fillMaxWidth().weight(ratio).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.fillMaxHeight().aspectRatio(4f/3f).background(Color.Gray), contentAlignment = Alignment.Center) {
                    Text("GAME (4:3)", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f - ratio)) {
                PortraitPS1Layout(
                    model = ControllerModel.PLAYSTATION,
                    config = ControlLayoutConfig(visualStyle = VisualStyle.CLASSIC, portraitGameRatio = ratio),
                    opacity = 1f,
                    size = 1f,
                    buttonProps = emptyMap(),
                    selectedId = null,
                    onSelect = {},
                    enabled = true,
                    isEditing = false,
                    onUpdate = { _, _ -> },
                    onMenu = {},
                    onInteraction = {},
                    vScale = 0.9f
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PS1PortraitModernPreview() {
    val ratio = 0.42f
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Box(modifier = Modifier.fillMaxWidth().weight(ratio).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.fillMaxHeight().aspectRatio(4f/3f).background(Color.Gray), contentAlignment = Alignment.Center) {
                    Text("GAME (4:3)", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f - ratio)) {
                PortraitPS1Layout(
                    model = ControllerModel.PLAYSTATION,
                    config = ControlLayoutConfig(visualStyle = VisualStyle.MODERN, portraitGameRatio = ratio),
                    opacity = 1f,
                    size = 1f,
                    buttonProps = emptyMap(),
                    selectedId = null,
                    onSelect = {},
                    enabled = true,
                    isEditing = false,
                    onUpdate = { _, _ -> },
                    onMenu = {},
                    onInteraction = {},
                    vScale = 0.9f
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun GenesisPortraitLargeScreenPreview() {
    val ratio = 0.60f
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Box(modifier = Modifier.fillMaxWidth().weight(ratio).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.fillMaxHeight().aspectRatio(4f/3f).background(Color.Gray), contentAlignment = Alignment.Center) {
                    Text("GAME (4:3)", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f - ratio)) {
                PortraitGenesisLayout(
                    config = ControlLayoutConfig(visualStyle = VisualStyle.MODERN, portraitGameRatio = ratio),
                    opacity = 1f,
                    size = 1f,
                    buttonProps = emptyMap(),
                    selectedId = null,
                    onSelect = {},
                    enabled = true,
                    isEditing = false,
                    onUpdate = { _, _ -> },
                    onMenu = {},
                    onInteraction = {},
                    vScale = 0.6f
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun GenesisPortraitPreview() {
    val ratio = 0.42f
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Box(modifier = Modifier.fillMaxWidth().weight(ratio).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.fillMaxHeight().aspectRatio(4f/3f).background(Color.Gray), contentAlignment = Alignment.Center) {
                    Text("GAME (4:3)", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f - ratio)) {
                PortraitGenesisLayout(
                    config = ControlLayoutConfig(visualStyle = VisualStyle.MODERN, portraitGameRatio = ratio),
                    opacity = 1f,
                    size = 1f,
                    buttonProps = emptyMap(),
                    selectedId = null,
                    onSelect = {},
                    enabled = true,
                    isEditing = false,
                    onUpdate = { _, _ -> },
                    onMenu = {},
                    onInteraction = {},
                    vScale = 0.9f
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 360)
@Composable
fun PS1LandscapePreview() {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Simulated 4:3 Game Screen
            Box(modifier = Modifier.fillMaxHeight().aspectRatio(4f/3f).background(Color.DarkGray).align(Alignment.Center)) {
                Text("GAME SCREEN (4:3)", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
            }
            LandscapePS1Layout(
                model = ControllerModel.PLAYSTATION,
                config = ControlLayoutConfig(visualStyle = VisualStyle.CLASSIC),
                opacity = 1f,
                size = 1f,
                buttonProps = emptyMap(),
                selectedId = null,
                onSelect = {},
                enabled = true,
                isEditing = false,
                onUpdate = { _, _ -> },
                onInteraction = {}
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 360)
@Composable
fun GenesisLandscapePreview() {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Simulated 4:3 Game Screen
            Box(modifier = Modifier.fillMaxHeight().aspectRatio(4f/3f).background(Color.DarkGray).align(Alignment.Center)) {
                Text("GAME SCREEN (4:3)", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
            }
            LandscapeGenesisLayout(
                config = ControlLayoutConfig(visualStyle = VisualStyle.MODERN),
                opacity = 1f,
                size = 1f,
                buttonProps = emptyMap(),
                selectedId = null,
                onSelect = {},
                enabled = true,
                isEditing = false,
                onUpdate = { _, _ -> },
                onInteraction = {}
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 360)
@Composable
fun N64LandscapePreview() {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Simulated 4:3 Game Screen
            Box(modifier = Modifier.fillMaxHeight().aspectRatio(4f/3f).background(Color.DarkGray).align(Alignment.Center)) {
                Text("GAME SCREEN (4:3)", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
            }
            LandscapeN64Layout(
                config = ControlLayoutConfig(visualStyle = VisualStyle.CLASSIC),
                opacity = 1f,
                size = 1f,
                buttonProps = emptyMap(),
                selectedId = null,
                onSelect = {},
                enabled = true,
                isEditing = false,
                onUpdate = { _, _ -> },
                onInteraction = {}
            )
        }
    }
}

@Composable
fun MenuButton(modifier: Modifier = Modifier, onClick: () -> Unit, opacity: Float = 1.0f, onInteraction: () -> Unit = {}) {
    FilledTonalButton(onClick = { onInteraction(); onClick() }, modifier = modifier.alpha(opacity), shape = RoundedCornerShape(8.dp)) { Text("MENU") }
}

@Composable
fun FastForwardButton(modifier: Modifier = Modifier, onFastForward: (Boolean) -> Unit, enabled: Boolean, opacity: Float = 1.0f, onInteraction: () -> Unit = {}) {
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> { onInteraction(); onFastForward(true) }
                is PressInteraction.Release, is PressInteraction.Cancel -> onFastForward(false)
            }
        }
    }
    Button(onClick = {}, interactionSource = interactionSource, enabled = enabled, modifier = modifier.alpha(opacity), shape = RoundedCornerShape(8.dp)) { Text("FF") }
}

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}

