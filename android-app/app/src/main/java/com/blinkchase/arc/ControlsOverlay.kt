package com.blinkchase.arc

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.*
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
    val context = LocalContext.current
    
    // Global opacity from config
    val globalOpacity = when (config.style) {
        InputStyle.STANDARD -> config.opacity
        InputStyle.COMPACT -> config.opacity * 0.9f
        InputStyle.MINIMALIST -> config.opacity * 0.5f
        InputStyle.TRANSPARENT -> config.opacity * 0.25f
        InputStyle.HIDDEN -> 0f
    }
    
    // Global size multiplier
    val globalSizeMultiplier = when (config.style) {
        InputStyle.COMPACT, InputStyle.MINIMALIST -> config.buttonSize * 0.85f
        else -> config.buttonSize
    }

    Box(modifier = modifier) {
        if (isLandscape) {
            LandscapeControlsLayout(
                platform = platform,
                model = model,
                globalOpacity = globalOpacity,
                globalSizeMultiplier = globalSizeMultiplier,
                buttonProps = buttonProps,
                selectedButtonId = selectedButtonId,
                onSelectButton = onSelectButton,
                enabled = enabled,
                isEditing = isEditing,
                showFF = showFF,
                onUpdateProps = onUpdateProps,
                onFastForward = onFastForward,
                onMenuClick = onMenuClick,
                onInteraction = onInteraction
            )
        } else {
            PortraitControlsLayout(
                platform = platform,
                model = model,
                globalOpacity = globalOpacity,
                globalSizeMultiplier = globalSizeMultiplier,
                buttonProps = buttonProps,
                selectedButtonId = selectedButtonId,
                onSelectButton = onSelectButton,
                enabled = enabled,
                isEditing = isEditing,
                showFF = showFF,
                onUpdateProps = onUpdateProps,
                onFastForward = onFastForward,
                onMenuClick = onMenuClick,
                onInteraction = onInteraction
            )
        }

        // Selection Controls Overlay (Pro Customizer)
        if (isEditing && selectedButtonId != null) {
            val currentProps = buttonProps[selectedButtonId] ?: ButtonProps()
            
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Selected Button ID: $selectedButtonId", style = MaterialTheme.typography.labelSmall)
                    
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Scale, null, modifier = Modifier.size(16.dp))
                        Slider(
                            value = currentProps.scale,
                            onValueChange = { onUpdateProps(selectedButtonId!!, currentProps.copy(scale = it)) },
                            valueRange = 0.5f..2.5f,
                            modifier = Modifier.width(120.dp)
                        )
                        Text("${(currentProps.scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Opacity, null, modifier = Modifier.size(16.dp))
                        Slider(
                            value = currentProps.alpha,
                            onValueChange = { onUpdateProps(selectedButtonId!!, currentProps.copy(alpha = it)) },
                            valueRange = 0.1f..1.0f,
                            modifier = Modifier.width(120.dp)
                        )
                        Text("${(currentProps.alpha * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                    
                    Button(
                        onClick = { onSelectButton(null) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Text("Deselect", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LandscapeControlsLayout(
    platform: Platform,
    model: ControllerModel,
    globalOpacity: Float,
    globalSizeMultiplier: Float,
    buttonProps: Map<Int, ButtonProps>,
    selectedButtonId: Int?,
    onSelectButton: (Int?) -> Unit,
    enabled: Boolean,
    isEditing: Boolean,
    showFF: Boolean,
    onUpdateProps: (Int, ButtonProps) -> Unit,
    onFastForward: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onInteraction: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (showFF) {
            FastForwardButton(
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                onFastForward = onFastForward,
                enabled = enabled,
                opacity = globalOpacity,
                onInteraction = onInteraction
            )
        }

        MenuButton(
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
            onClick = onMenuClick,
            opacity = globalOpacity,
            onInteraction = onInteraction
        )

        // Platform-specific layouts
        when (platform) {
            Platform.PS1 -> LandscapePS1Layout(model, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
            Platform.N64 -> LandscapeN64Layout(model, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
            Platform.GENESIS -> LandscapeGenesisLayout(model, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
            else -> LandscapeSNESLayout(model, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
        }
    }
}

@Composable
private fun PortraitControlsLayout(
    platform: Platform,
    model: ControllerModel,
    globalOpacity: Float,
    globalSizeMultiplier: Float,
    buttonProps: Map<Int, ButtonProps>,
    selectedButtonId: Int?,
    onSelectButton: (Int?) -> Unit,
    enabled: Boolean,
    isEditing: Boolean,
    showFF: Boolean,
    onUpdateProps: (Int, ButtonProps) -> Unit,
    onFastForward: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onInteraction: () -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GameButton(
                icon = Icons.Default.KeyboardArrowLeft,
                buttonId = MainActivity.BTN_L,
                modifier = Modifier.size((70 * globalSizeMultiplier).dp, (35 * globalSizeMultiplier).dp),
                props = buttonProps[MainActivity.BTN_L],
                isSelected = selectedButtonId == MainActivity.BTN_L,
                onSelect = { onSelectButton(MainActivity.BTN_L) },
                enabled = enabled,
                isEditing = isEditing,
                onUpdateProps = onUpdateProps,
                alpha = globalOpacity,
                onInteraction = onInteraction
            )

            MenuButton(onClick = onMenuClick, opacity = globalOpacity, onInteraction = onInteraction)

            GameButton(
                icon = Icons.Default.KeyboardArrowRight,
                buttonId = MainActivity.BTN_R,
                modifier = Modifier.size((70 * globalSizeMultiplier).dp, (35 * globalSizeMultiplier).dp),
                props = buttonProps[MainActivity.BTN_R],
                isSelected = selectedButtonId == MainActivity.BTN_R,
                onSelect = { onSelectButton(MainActivity.BTN_R) },
                enabled = enabled,
                isEditing = isEditing,
                onUpdateProps = onUpdateProps,
                alpha = globalOpacity,
                onInteraction = onInteraction
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp)) {
                if (platform == Platform.N64) {
                    VirtualJoystick(
                        modifier = Modifier.alpha(globalOpacity),
                        size = (160 * globalSizeMultiplier).dp,
                        onMoved = { x, y ->
                            onInteraction()
                            (context as? MainActivity)?.setAnalogInput(x, y)
                        }
                    )
                } else {
                    DPadLayout(globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
                }
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                GameButton(
                    icon = Icons.Default.HorizontalRule,
                    buttonId = MainActivity.BTN_SELECT,
                    modifier = Modifier.size((70 * globalSizeMultiplier).dp, (35 * globalSizeMultiplier).dp),
                    props = buttonProps[MainActivity.BTN_SELECT],
                    isSelected = selectedButtonId == MainActivity.BTN_SELECT,
                    onSelect = { onSelectButton(MainActivity.BTN_SELECT) },
                    enabled = enabled,
                    isEditing = isEditing,
                    onUpdateProps = onUpdateProps,
                    alpha = globalOpacity,
                    onInteraction = onInteraction
                )
                GameButton(
                    icon = Icons.Default.PlayArrow,
                    buttonId = MainActivity.BTN_START,
                    modifier = Modifier.size((70 * globalSizeMultiplier).dp, (35 * globalSizeMultiplier).dp),
                    props = buttonProps[MainActivity.BTN_START],
                    isSelected = selectedButtonId == MainActivity.BTN_START,
                    onSelect = { onSelectButton(MainActivity.BTN_START) },
                    enabled = enabled,
                    isEditing = isEditing,
                    onUpdateProps = onUpdateProps,
                    alpha = globalOpacity,
                    onInteraction = onInteraction
                )
            }

            Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, bottom = 72.dp)) {
                SNESActionButtons(model, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
            }
        }
    }
}

// =========================== Layout Implementation Helpers ===========================

@Composable
private fun LandscapePS1Layout(
    model: ControllerModel,
    globalOpacity: Float, globalSizeMultiplier: Float,
    buttonProps: Map<Int, ButtonProps>, selectedButtonId: Int?, onSelectButton: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdateProps: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 80.dp)) {
            GameButton(
                icon = Icons.Default.KeyboardDoubleArrowDown,
                text = "L2",
                buttonId = MainActivity.BTN_L2,
                modifier = Modifier.size((70 * globalSizeMultiplier).dp, (35 * globalSizeMultiplier).dp),
                props = buttonProps[MainActivity.BTN_L2],
                isSelected = selectedButtonId == MainActivity.BTN_L2,
                onSelect = { onSelectButton(MainActivity.BTN_L2) },
                enabled = enabled,
                isEditing = isEditing,
                onUpdateProps = onUpdateProps,
                alpha = globalOpacity,
                onInteraction = onInteraction
            )
            Spacer(Modifier.height(8.dp))
            GameButton(
                icon = Icons.Default.KeyboardArrowLeft,
                text = "L1",
                buttonId = MainActivity.BTN_L,
                modifier = Modifier.size((70 * globalSizeMultiplier).dp, (35 * globalSizeMultiplier).dp),
                props = buttonProps[MainActivity.BTN_L],
                isSelected = selectedButtonId == MainActivity.BTN_L,
                onSelect = { onSelectButton(MainActivity.BTN_L) },
                enabled = enabled,
                isEditing = isEditing,
                onUpdateProps = onUpdateProps,
                alpha = globalOpacity,
                onInteraction = onInteraction
            )
        }
        Column(modifier = Modifier.align(Alignment.TopEnd).padding(end = 24.dp, top = 80.dp)) {
            GameButton(
                icon = Icons.Default.KeyboardDoubleArrowDown,
                text = "R2",
                buttonId = MainActivity.BTN_R2,
                modifier = Modifier.size((70 * globalSizeMultiplier).dp, (35 * globalSizeMultiplier).dp),
                props = buttonProps[MainActivity.BTN_R2],
                isSelected = selectedButtonId == MainActivity.BTN_R2,
                onSelect = { onSelectButton(MainActivity.BTN_R2) },
                enabled = enabled,
                isEditing = isEditing,
                onUpdateProps = onUpdateProps,
                alpha = globalOpacity,
                onInteraction = onInteraction
            )
            Spacer(Modifier.height(8.dp))
            GameButton(
                icon = Icons.Default.KeyboardArrowRight,
                text = "R1",
                buttonId = MainActivity.BTN_R,
                modifier = Modifier.size((70 * globalSizeMultiplier).dp, (35 * globalSizeMultiplier).dp),
                props = buttonProps[MainActivity.BTN_R],
                isSelected = selectedButtonId == MainActivity.BTN_R,
                onSelect = { onSelectButton(MainActivity.BTN_R) },
                enabled = enabled,
                isEditing = isEditing,
                onUpdateProps = onUpdateProps,
                alpha = globalOpacity,
                onInteraction = onInteraction
            )
        }
        Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) {
            DPadLayout(globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)) {
            PS1ActionButtons(model, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
        }
    }
}

@Composable
private fun LandscapeN64Layout(
    model: ControllerModel,
    globalOpacity: Float, globalSizeMultiplier: Float,
    buttonProps: Map<Int, ButtonProps>, selectedButtonId: Int?, onSelectButton: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdateProps: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 48.dp)) {
            VirtualJoystick(modifier = Modifier.alpha(globalOpacity), size = (160 * globalSizeMultiplier).dp, onMoved = { x, y -> onInteraction(); (context as? MainActivity)?.setAnalogInput(x, y) })
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 48.dp)) {
            N64ActionButtons(globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
        }
    }
}

@Composable
private fun LandscapeGenesisLayout(
    model: ControllerModel,
    globalOpacity: Float, globalSizeMultiplier: Float,
    buttonProps: Map<Int, ButtonProps>, selectedButtonId: Int?, onSelectButton: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdateProps: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) {
            DPadLayout(globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)) {
            GenesisActionButtons(globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
        }
    }
}

@Composable
private fun LandscapeSNESLayout(
    model: ControllerModel,
    globalOpacity: Float, globalSizeMultiplier: Float,
    buttonProps: Map<Int, ButtonProps>, selectedButtonId: Int?, onSelectButton: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdateProps: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) {
            DPadLayout(globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)) {
            SNESActionButtons(model, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
        }
    }
}

@Composable
private fun DPadLayout(
    globalOpacity: Float, globalSizeMultiplier: Float,
    buttonProps: Map<Int, ButtonProps>, selectedButtonId: Int?, onSelectButton: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdateProps: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    val btnSize = (55 * globalSizeMultiplier).dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GameButton(
            icon = Icons.Default.ArrowUpward,
            buttonId = MainActivity.BTN_UP,
            modifier = Modifier.size(btnSize),
            props = buttonProps[MainActivity.BTN_UP],
            isSelected = selectedButtonId == MainActivity.BTN_UP,
            onSelect = { onSelectButton(MainActivity.BTN_UP) },
            enabled = enabled,
            isEditing = isEditing,
            onUpdateProps = onUpdateProps,
            alpha = globalOpacity,
            onInteraction = onInteraction
        )
        Row {
            GameButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                buttonId = MainActivity.BTN_LEFT,
                modifier = Modifier.size(btnSize),
                props = buttonProps[MainActivity.BTN_LEFT],
                isSelected = selectedButtonId == MainActivity.BTN_LEFT,
                onSelect = { onSelectButton(MainActivity.BTN_LEFT) },
                enabled = enabled,
                isEditing = isEditing,
                onUpdateProps = onUpdateProps,
                alpha = globalOpacity,
                onInteraction = onInteraction
            )
            Spacer(Modifier.size(btnSize))
            GameButton(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                buttonId = MainActivity.BTN_RIGHT,
                modifier = Modifier.size(btnSize),
                props = buttonProps[MainActivity.BTN_RIGHT],
                isSelected = selectedButtonId == MainActivity.BTN_RIGHT,
                onSelect = { onSelectButton(MainActivity.BTN_RIGHT) },
                enabled = enabled,
                isEditing = isEditing,
                onUpdateProps = onUpdateProps,
                alpha = globalOpacity,
                onInteraction = onInteraction
            )
        }
        GameButton(
            icon = Icons.Default.ArrowDownward,
            buttonId = MainActivity.BTN_DOWN,
            modifier = Modifier.size(btnSize),
            props = buttonProps[MainActivity.BTN_DOWN],
            isSelected = selectedButtonId == MainActivity.BTN_DOWN,
            onSelect = { onSelectButton(MainActivity.BTN_DOWN) },
            enabled = enabled,
            isEditing = isEditing,
            onUpdateProps = onUpdateProps,
            alpha = globalOpacity,
            onInteraction = onInteraction
        )
    }
}

@Composable
private fun SNESActionButtons(
    model: ControllerModel,
    globalOpacity: Float, globalSizeMultiplier: Float,
    buttonProps: Map<Int, ButtonProps>, selectedButtonId: Int?, onSelectButton: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdateProps: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    val btnSize = (55 * globalSizeMultiplier).dp
    
    val (labelA, labelB, labelX, labelY) = when(model) {
        ControllerModel.PLAYSTATION -> listOf("○", "✕", "△", "□")
        ControllerModel.XBOX -> listOf("B", "A", "Y", "X")
        ControllerModel.N64 -> listOf("A", "B", "C-Up", "C-Left")
        else -> listOf("A", "B", "X", "Y")
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GameButton(
            text = labelX,
            buttonId = MainActivity.BTN_X,
            modifier = Modifier.size(btnSize),
            icon = when (model) {
                ControllerModel.N64 -> Icons.Default.ArrowDropUp
                ControllerModel.PLAYSTATION -> Icons.Default.ChangeHistory
                else -> null
            },
            props = buttonProps[MainActivity.BTN_X],
            isSelected = selectedButtonId == MainActivity.BTN_X,
            onSelect = { onSelectButton(MainActivity.BTN_X) },
            enabled = enabled,
            isEditing = isEditing,
            onUpdateProps = onUpdateProps,
            alpha = globalOpacity,
            onInteraction = onInteraction
        )
        Row {
            GameButton(
                text = labelY,
                buttonId = MainActivity.BTN_Y,
                modifier = Modifier.size(btnSize),
                icon = when (model) {
                    ControllerModel.N64 -> Icons.AutoMirrored.Filled.ArrowLeft
                    ControllerModel.PLAYSTATION -> Icons.Default.CheckBoxOutlineBlank
                    else -> null
                },
                props = buttonProps[MainActivity.BTN_Y],
                isSelected = selectedButtonId == MainActivity.BTN_Y,
                onSelect = { onSelectButton(MainActivity.BTN_Y) },
                enabled = enabled,
                isEditing = isEditing,
                onUpdateProps = onUpdateProps,
                alpha = globalOpacity,
                onInteraction = onInteraction
            )
            Spacer(Modifier.size(btnSize))
            GameButton(
                text = labelA,
                buttonId = MainActivity.BTN_A,
                modifier = Modifier.size(btnSize),
                icon = if (model == ControllerModel.PLAYSTATION) Icons.Default.RadioButtonUnchecked else null,
                props = buttonProps[MainActivity.BTN_A],
                isSelected = selectedButtonId == MainActivity.BTN_A,
                onSelect = { onSelectButton(MainActivity.BTN_A) },
                enabled = enabled,
                isEditing = isEditing,
                onUpdateProps = onUpdateProps,
                alpha = globalOpacity,
                buttonColor = Color(0xFFE91E63),
                onInteraction = onInteraction
            )
        }
        GameButton(
            text = labelB,
            buttonId = MainActivity.BTN_B,
            modifier = Modifier.size(btnSize),
            icon = if (model == ControllerModel.PLAYSTATION) Icons.Default.Close else null,
            props = buttonProps[MainActivity.BTN_B],
            isSelected = selectedButtonId == MainActivity.BTN_B,
            onSelect = { onSelectButton(MainActivity.BTN_B) },
            enabled = enabled,
            isEditing = isEditing,
            onUpdateProps = onUpdateProps,
            alpha = globalOpacity,
            buttonColor = Color(0xFF4CAF50),
            onInteraction = onInteraction
        )
    }
}

@Composable
private fun PS1ActionButtons(
    model: ControllerModel,
    globalOpacity: Float, globalSizeMultiplier: Float,
    buttonProps: Map<Int, ButtonProps>, selectedButtonId: Int?, onSelectButton: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdateProps: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    // Reuse SNES layout logic but specifically for PS1 buttons
    SNESActionButtons(model, globalOpacity, globalSizeMultiplier, buttonProps, selectedButtonId, onSelectButton, enabled, isEditing, onUpdateProps, onInteraction)
}

@Composable
private fun N64ActionButtons(
    globalOpacity: Float, globalSizeMultiplier: Float,
    buttonProps: Map<Int, ButtonProps>, selectedButtonId: Int?, onSelectButton: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdateProps: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    val btnSize = (68 * globalSizeMultiplier).dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GameButton(
                text = "Z",
                buttonId = MainActivity.BTN_Z,
                modifier = Modifier.size(btnSize),
                icon = Icons.Default.KeyboardDoubleArrowDown,
                props = buttonProps[MainActivity.BTN_Z],
                isSelected = selectedButtonId == MainActivity.BTN_Z,
                onSelect = { onSelectButton(MainActivity.BTN_Z) },
                enabled = enabled,
                isEditing = isEditing,
                onUpdateProps = onUpdateProps,
                alpha = globalOpacity,
                buttonColor = Color(0xFFB39DDB),
                onInteraction = onInteraction
            )
            GameButton(
                text = "B",
                buttonId = MainActivity.BTN_B,
                modifier = Modifier.size(btnSize),
                props = buttonProps[MainActivity.BTN_B],
                isSelected = selectedButtonId == MainActivity.BTN_B,
                onSelect = { onSelectButton(MainActivity.BTN_B) },
                enabled = enabled,
                isEditing = isEditing,
                onUpdateProps = onUpdateProps,
                alpha = globalOpacity,
                onInteraction = onInteraction
            )
        }
        GameButton(
            text = "A",
            buttonId = MainActivity.BTN_A,
            modifier = Modifier.size(btnSize),
            props = buttonProps[MainActivity.BTN_A],
            isSelected = selectedButtonId == MainActivity.BTN_A,
            onSelect = { onSelectButton(MainActivity.BTN_A) },
            enabled = enabled,
            isEditing = isEditing,
            onUpdateProps = onUpdateProps,
            alpha = globalOpacity,
            buttonColor = Color(0xFF4CAF50),
            onInteraction = onInteraction
        )
    }
}

@Composable
private fun GenesisActionButtons(
    globalOpacity: Float, globalSizeMultiplier: Float,
    buttonProps: Map<Int, ButtonProps>, selectedButtonId: Int?, onSelectButton: (Int?) -> Unit,
    enabled: Boolean, isEditing: Boolean, onUpdateProps: (Int, ButtonProps) -> Unit, onInteraction: () -> Unit
) {
    val btnSize = (60 * globalSizeMultiplier).dp
    Column(horizontalAlignment = Alignment.End) {
        GameButton(
            text = "C",
            buttonId = MainActivity.BTN_A,
            modifier = Modifier.size(btnSize),
            props = buttonProps[MainActivity.BTN_A],
            isSelected = selectedButtonId == MainActivity.BTN_A,
            onSelect = { onSelectButton(MainActivity.BTN_A) },
            enabled = enabled,
            isEditing = isEditing,
            onUpdateProps = onUpdateProps,
            alpha = globalOpacity,
            onInteraction = onInteraction
        )
        Spacer(Modifier.height(4.dp))
        Row {
            GameButton(
                text = "B",
                buttonId = MainActivity.BTN_B,
                modifier = Modifier.size(btnSize),
                props = buttonProps[MainActivity.BTN_B],
                isSelected = selectedButtonId == MainActivity.BTN_B,
                onSelect = { onSelectButton(MainActivity.BTN_B) },
                enabled = enabled,
                isEditing = isEditing,
                onUpdateProps = onUpdateProps,
                alpha = globalOpacity,
                onInteraction = onInteraction
            )
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row {
            GameButton(
                text = "A",
                buttonId = MainActivity.BTN_Y,
                modifier = Modifier.size(btnSize),
                props = buttonProps[MainActivity.BTN_Y],
                isSelected = selectedButtonId == MainActivity.BTN_Y,
                onSelect = { onSelectButton(MainActivity.BTN_Y) },
                enabled = enabled,
                isEditing = isEditing,
                onUpdateProps = onUpdateProps,
                alpha = globalOpacity,
                onInteraction = onInteraction
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
fun GameButton(
    text: String = "",
    buttonId: Int,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    props: ButtonProps? = null,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    enabled: Boolean = true,
    isEditing: Boolean = false,
    onUpdateProps: (Int, ButtonProps) -> Unit = { _, _ -> },
    alpha: Float = 1.0f,
    buttonColor: Color = MaterialTheme.colorScheme.primary,
    onInteraction: () -> Unit = {}
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }

    val currentProps = props ?: ButtonProps()
    val offsetX = currentProps.x
    val offsetY = currentProps.y
    val currentScale = currentProps.scale
    val currentAlpha = currentProps.alpha * alpha

    val dragModifier = if (isEditing) {
        Modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                onUpdateProps(buttonId, currentProps.copy(x = offsetX + dragAmount.x, y = offsetY + dragAmount.y))
            }
        }
    } else Modifier

    val borderModifier = if (isEditing && isSelected) {
        Modifier.border(3.dp, Color.Cyan, CircleShape)
    } else if (isEditing) {
        Modifier.border(1.dp, Color.Red.copy(alpha = 0.5f), CircleShape)
    } else Modifier

    LaunchedEffect(interactionSource, enabled) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> if (!isEditing && enabled) {
                    onInteraction()
                    (context as? MainActivity)?.sendInput(buttonId, 1)
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> if (!isEditing && enabled) {
                    (context as? MainActivity)?.sendInput(buttonId, 0)
                }
            }
        }
    }

    Button(
        onClick = { if (isEditing) onSelect() },
        interactionSource = interactionSource,
        enabled = enabled,
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .scale(currentScale)
            .then(dragModifier)
            .then(borderModifier)
            .alpha(currentAlpha),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
        contentPadding = PaddingValues(0.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.White
            )
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
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
