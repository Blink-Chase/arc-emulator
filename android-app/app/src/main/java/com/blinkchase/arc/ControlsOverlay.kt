package com.blinkchase.arc

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun ControlsOverlay(
    modifier: Modifier = Modifier,
    platform: Platform,
    config: ControlLayoutConfig,
    buttonOffsets: Map<Int, Offset> = emptyMap(),
    enabled: Boolean = true,
    isEditing: Boolean = false,
    isLandscape: Boolean = false,
    onDrag: (Int, Offset) -> Unit = { _, _ -> },
    showFF: Boolean = true,
    onFastForward: (Boolean) -> Unit = {},
    onMenuClick: () -> Unit = {},
    onInteraction: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    
    // Calculate dynamic opacity based on config and style
    val finalOpacity = when (config.style) {
        InputStyle.STANDARD -> config.opacity
        InputStyle.COMPACT -> config.opacity * 0.9f
        InputStyle.MINIMALIST -> config.opacity * 0.5f
        InputStyle.TRANSPARENT -> config.opacity * 0.25f
        InputStyle.HIDDEN -> 0f
    }
    
    // Calculate button size multiplier
    val sizeMultiplier = when (config.style) {
        InputStyle.COMPACT, InputStyle.MINIMALIST -> config.buttonSize * 0.85f
        else -> config.buttonSize
    }

    Box(modifier = modifier) {
        if (isLandscape) {
            // Landscape layout - controls overlay on sides
            LandscapeControlsLayout(
                platform = platform,
                config = config,
                finalOpacity = finalOpacity,
                sizeMultiplier = sizeMultiplier,
                buttonOffsets = buttonOffsets,
                enabled = enabled,
                isEditing = isEditing,
                showFF = showFF,
                onDrag = onDrag,
                onFastForward = onFastForward,
                onMenuClick = onMenuClick,
                onInteraction = onInteraction
            )
        } else {
            // Portrait layout - controls at bottom
            PortraitControlsLayout(
                platform = platform,
                config = config,
                finalOpacity = finalOpacity,
                sizeMultiplier = sizeMultiplier,
                buttonOffsets = buttonOffsets,
                enabled = enabled,
                isEditing = isEditing,
                showFF = showFF,
                onDrag = onDrag,
                onFastForward = onFastForward,
                onMenuClick = onMenuClick,
                onInteraction = onInteraction
            )
        }
    }
}

// ============================================================================================
// =================================== LANDSCAPE LAYOUTS ======================================
// ============================================================================================

@Composable
private fun LandscapeControlsLayout(
    platform: Platform,
    config: ControlLayoutConfig,
    finalOpacity: Float,
    sizeMultiplier: Float,
    buttonOffsets: Map<Int, Offset>,
    enabled: Boolean,
    isEditing: Boolean,
    showFF: Boolean,
    onDrag: (Int, Offset) -> Unit,
    onFastForward: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onInteraction: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Fast Forward button - top right
        if (showFF) {
            FastForwardButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                onFastForward = onFastForward,
                enabled = enabled,
                opacity = finalOpacity,
                onInteraction = onInteraction
            )
        }

        // Menu button - top center
        MenuButton(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            onClick = onMenuClick,
            opacity = finalOpacity,
            onInteraction = onInteraction
        )

        // Platform-specific control layouts
        when (platform) {
            Platform.PS1 -> LandscapePS1Layout(
                finalOpacity, sizeMultiplier, buttonOffsets, enabled, isEditing, onDrag, onInteraction
            )
            Platform.N64 -> LandscapeN64Layout(
                finalOpacity, sizeMultiplier, buttonOffsets, enabled, isEditing, onDrag, onInteraction
            )
            Platform.GENESIS -> LandscapeGenesisLayout(
                finalOpacity, sizeMultiplier, buttonOffsets, enabled, isEditing, onDrag, onInteraction
            )
            Platform.GBA, Platform.GB, Platform.GBC -> LandscapeGBLayout(
                finalOpacity, sizeMultiplier, buttonOffsets, enabled, isEditing, onDrag, onInteraction,
                showLRArt = platform == Platform.GBA
            )
            else -> LandscapeSNESLayout(
                finalOpacity, sizeMultiplier, buttonOffsets, enabled, isEditing, onDrag, onInteraction
            )
        }
    }
}

// ============================================================================================
// =================================== PORTRAIT LAYOUTS =======================================
// ============================================================================================

@Composable
private fun PortraitControlsLayout(
    platform: Platform,
    config: ControlLayoutConfig,
    finalOpacity: Float,
    sizeMultiplier: Float,
    buttonOffsets: Map<Int, Offset>,
    enabled: Boolean,
    isEditing: Boolean,
    showFF: Boolean,
    onDrag: (Int, Offset) -> Unit,
    onFastForward: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onInteraction: () -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        // Top row - L button, Menu (top-right), R button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // L button (if applicable)
            if (platform == Platform.SNES || platform == Platform.PS1 || platform == Platform.GBA || platform == Platform.N64) {
                GameButton(
                    text = when(platform) {
                        Platform.PS1 -> "L1"
                        Platform.N64 -> "L"
                        else -> "L"
                    },
                    buttonId = MainActivity.BTN_L,
                    modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                    offset = buttonOffsets[MainActivity.BTN_L],
                    enabled = enabled,
                    isEditing = isEditing,
                    onDrag = onDrag,
                    alpha = finalOpacity,
                    onInteraction = onInteraction
                )
            } else {
                Spacer(modifier = Modifier.width(70.dp))
            }

            // Menu button - TOP RIGHT CORNER (same height as FPS stats)
            MenuButton(
                modifier = Modifier,
                onClick = onMenuClick,
                opacity = finalOpacity,
                onInteraction = onInteraction
            )

            // R button (if applicable)
            if (platform == Platform.SNES || platform == Platform.PS1 || platform == Platform.GBA || platform == Platform.N64) {
                GameButton(
                    text = when(platform) {
                        Platform.PS1 -> "R1"
                        Platform.N64 -> "R"
                        else -> "R"
                    },
                    buttonId = MainActivity.BTN_R,
                    modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                    offset = buttonOffsets[MainActivity.BTN_R],
                    enabled = enabled,
                    isEditing = isEditing,
                    onDrag = onDrag,
                    alpha = finalOpacity,
                    onInteraction = onInteraction
                )
            } else {
                Spacer(modifier = Modifier.width(70.dp))
            }
        }

        // Main controls area - D-Pad and Action buttons spread from top to Select/Start
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
        // D-Pad - Left side, aligned to top
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp)
                    .padding(top = 16.dp)
            ) {
                if (platform == Platform.N64) {
                    VirtualJoystick(
                        modifier = Modifier.alpha(finalOpacity),
                        size = (160 * sizeMultiplier).dp,
                        onMoved = { x, y ->
                            onInteraction()
                            (context as? MainActivity)?.setAnalogInput(x, y)
                        }
                    )
                } else {
                    DPadLayout(
                        finalOpacity, sizeMultiplier, buttonOffsets, enabled, isEditing, onDrag, onInteraction
                    )
                }
            }

            // Center buttons (Select/Start) - moved higher
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                GameButton(
                    text = "SEL",
                    buttonId = MainActivity.BTN_SELECT,
                    modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                    offset = buttonOffsets[MainActivity.BTN_SELECT],
                    enabled = enabled,
                    isEditing = isEditing,
                    onDrag = onDrag,
                    alpha = finalOpacity,
                    onInteraction = onInteraction
                )
                GameButton(
                    text = "START",
                    buttonId = MainActivity.BTN_START,
                    modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                    offset = buttonOffsets[MainActivity.BTN_START],
                    enabled = enabled,
                    isEditing = isEditing,
                    onDrag = onDrag,
                    alpha = finalOpacity,
                    onInteraction = onInteraction
                )
            }

            // Action buttons - Far right side, vertically centred-upper
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .padding(bottom = 72.dp)
            ) {
                when (platform) {
                    Platform.GENESIS -> GenesisActionButtons(
                        finalOpacity, sizeMultiplier, buttonOffsets, enabled, isEditing, onDrag, onInteraction
                    )
                    Platform.N64 -> N64ActionButtons(
                        finalOpacity, sizeMultiplier, buttonOffsets, enabled, isEditing, onDrag, onInteraction
                    )
                    else -> SNESActionButtons(
                        finalOpacity, sizeMultiplier, buttonOffsets, enabled, isEditing, onDrag, onInteraction
                    )
                }
            }
        }
    }
}

@Composable
private fun LandscapePS1Layout(
    finalOpacity: Float,
    sizeMultiplier: Float,
    buttonOffsets: Map<Int, Offset>,
    enabled: Boolean,
    isEditing: Boolean,
    onDrag: (Int, Offset) -> Unit,
    onInteraction: () -> Unit
) {
    // L1/L2 on top left
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = 80.dp)
        ) {
            GameButton(
                text = "L2",
                buttonId = MainActivity.BTN_L2,
                modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_L2],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
            Spacer(modifier = Modifier.height(8.dp))
            GameButton(
                text = "L1",
                buttonId = MainActivity.BTN_L,
                modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_L],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
        }

        // R1/R2 on top right
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 24.dp, top = 80.dp)
        ) {
            GameButton(
                text = "R2",
                buttonId = MainActivity.BTN_R2,
                modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_R2],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
            Spacer(modifier = Modifier.height(8.dp))
            GameButton(
                text = "R1",
                buttonId = MainActivity.BTN_R,
                modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_R],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
        }

        // D-Pad - Left side, lower
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 32.dp)
        ) {
            DPadLayout(finalOpacity, sizeMultiplier, buttonOffsets, enabled, isEditing, onDrag, onInteraction)
        }

        // Select/Start - Bottom center (moved lower for better reachability)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            GameButton(
                text = "SELECT",
                buttonId = MainActivity.BTN_SELECT,
                modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_SELECT],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
            GameButton(
                text = "START",
                buttonId = MainActivity.BTN_START,
                modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_START],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
        }

        // PS1 Action buttons - Right side (diamond layout)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 32.dp)
        ) {
            DiamondButtonLayout(
                sizeMultiplier = sizeMultiplier,
                topButton = {
                    GameButton(
                        text = "△",
                        buttonId = MainActivity.BTN_X,
                        modifier = Modifier.size((55 * sizeMultiplier).dp),
                        offset = buttonOffsets[MainActivity.BTN_X],
                        enabled = enabled,
                        isEditing = isEditing,
                        onDrag = onDrag,
                        alpha = finalOpacity,
                        buttonColor = MaterialTheme.colorScheme.tertiary,
                        onInteraction = onInteraction
                    )
                },
                bottomButton = {
                    GameButton(
                        text = "✕",
                        buttonId = MainActivity.BTN_B,
                        modifier = Modifier.size((55 * sizeMultiplier).dp),
                        offset = buttonOffsets[MainActivity.BTN_B],
                        enabled = enabled,
                        isEditing = isEditing,
                        onDrag = onDrag,
                        alpha = finalOpacity,
                        buttonColor = Color(0xFF4CAF50),
                        onInteraction = onInteraction
                    )
                },
                leftButton = {
                    GameButton(
                        text = "□",
                        buttonId = MainActivity.BTN_Y,
                        modifier = Modifier.size((55 * sizeMultiplier).dp),
                        offset = buttonOffsets[MainActivity.BTN_Y],
                        enabled = enabled,
                        isEditing = isEditing,
                        onDrag = onDrag,
                        alpha = finalOpacity,
                        buttonColor = Color(0xFF2196F3),
                        onInteraction = onInteraction
                    )
                },
                rightButton = {
                    GameButton(
                        text = "○",
                        buttonId = MainActivity.BTN_A,
                        modifier = Modifier.size((55 * sizeMultiplier).dp),
                        offset = buttonOffsets[MainActivity.BTN_A],
                        enabled = enabled,
                        isEditing = isEditing,
                        onDrag = onDrag,
                        alpha = finalOpacity,
                        buttonColor = Color(0xFFE91E63),
                        onInteraction = onInteraction
                    )
                }
            )
        }
    }
}

@Composable
private fun LandscapeN64Layout(
    finalOpacity: Float,
    sizeMultiplier: Float,
    buttonOffsets: Map<Int, Offset>,
    enabled: Boolean,
    isEditing: Boolean,
    onDrag: (Int, Offset) -> Unit,
    onInteraction: () -> Unit
) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        // L/Z/R on top
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            GameButton(
                text = "L",
                buttonId = MainActivity.BTN_L,
                modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_L],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
            GameButton(
                text = "Z",
                buttonId = MainActivity.BTN_Z,
                modifier = Modifier.size((50 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_Z],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
            GameButton(
                text = "R",
                buttonId = MainActivity.BTN_R,
                modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_R],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
        }

        // Analog stick - Left side
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp, bottom = 32.dp)
        ) {
            VirtualJoystick(
                modifier = Modifier.alpha(finalOpacity),
                size = (160 * sizeMultiplier).dp,
                onMoved = { x, y ->
                    onInteraction()
                    (context as? MainActivity)?.setAnalogInput(x, y)
                }
            )
        }

        // Start - Center bottom
        GameButton(
            text = "S",
            buttonId = MainActivity.BTN_START,
            modifier = Modifier
                .size((50 * sizeMultiplier).dp)
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
            offset = buttonOffsets[MainActivity.BTN_START],
            enabled = enabled,
            isEditing = isEditing,
            onDrag = onDrag,
            alpha = finalOpacity,
            onInteraction = onInteraction
        )

        // A/B buttons - Right side
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 48.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GameButton(
                text = "A",
                buttonId = MainActivity.BTN_A,
                modifier = Modifier.size((70 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_A],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                buttonColor = Color(0xFF4CAF50),
                onInteraction = onInteraction
            )
            Spacer(modifier = Modifier.height(16.dp))
            GameButton(
                text = "B",
                buttonId = MainActivity.BTN_B,
                modifier = Modifier.size((55 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_B],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
        }
    }
}

@Composable
private fun LandscapeGenesisLayout(
    finalOpacity: Float,
    sizeMultiplier: Float,
    buttonOffsets: Map<Int, Offset>,
    enabled: Boolean,
    isEditing: Boolean,
    onDrag: (Int, Offset) -> Unit,
    onInteraction: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // D-Pad - Left side
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 32.dp)
        ) {
            DPadLayout(finalOpacity, sizeMultiplier, buttonOffsets, enabled, isEditing, onDrag, onInteraction)
        }

        // Start - Bottom center
        GameButton(
            text = "START",
            buttonId = MainActivity.BTN_START,
            modifier = Modifier
                .size((90 * sizeMultiplier).dp, (40 * sizeMultiplier).dp)
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            offset = buttonOffsets[MainActivity.BTN_START],
            enabled = enabled,
            isEditing = isEditing,
            onDrag = onDrag,
            alpha = finalOpacity,
            onInteraction = onInteraction
        )

        // A/B/C buttons - Right side (staggered)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 32.dp)
        ) {
            Column(horizontalAlignment = Alignment.End) {
                GameButton(
                    text = "C",
                    buttonId = MainActivity.BTN_A,
                    modifier = Modifier.size((60 * sizeMultiplier).dp),
                    offset = buttonOffsets[MainActivity.BTN_A],
                    enabled = enabled,
                    isEditing = isEditing,
                    onDrag = onDrag,
                    alpha = finalOpacity,
                    onInteraction = onInteraction
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    GameButton(
                        text = "B",
                        buttonId = MainActivity.BTN_B,
                        modifier = Modifier.size((60 * sizeMultiplier).dp),
                        offset = buttonOffsets[MainActivity.BTN_B],
                        enabled = enabled,
                        isEditing = isEditing,
                        onDrag = onDrag,
                        alpha = finalOpacity,
                        onInteraction = onInteraction
                    )
                    Spacer(modifier = Modifier.width((-15).dp))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    GameButton(
                        text = "A",
                        buttonId = MainActivity.BTN_Y,
                        modifier = Modifier.size((60 * sizeMultiplier).dp),
                        offset = buttonOffsets[MainActivity.BTN_Y],
                        enabled = enabled,
                        isEditing = isEditing,
                        onDrag = onDrag,
                        alpha = finalOpacity,
                        onInteraction = onInteraction
                    )
                    Spacer(modifier = Modifier.width((-30).dp))
                }
            }
        }
    }
}

@Composable
private fun LandscapeGBLayout(
    finalOpacity: Float,
    sizeMultiplier: Float,
    buttonOffsets: Map<Int, Offset>,
    enabled: Boolean,
    isEditing: Boolean,
    onDrag: (Int, Offset) -> Unit,
    onInteraction: () -> Unit,
    showLRArt: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // L/R buttons for GBA
        if (showLRArt) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                GameButton(
                    text = "L",
                    buttonId = MainActivity.BTN_L,
                    modifier = Modifier
                        .padding(start = 24.dp)
                        .size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                    offset = buttonOffsets[MainActivity.BTN_L],
                    enabled = enabled,
                    isEditing = isEditing,
                    onDrag = onDrag,
                    alpha = finalOpacity,
                    onInteraction = onInteraction
                )
                GameButton(
                    text = "R",
                    buttonId = MainActivity.BTN_R,
                    modifier = Modifier
                        .padding(end = 24.dp)
                        .size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                    offset = buttonOffsets[MainActivity.BTN_R],
                    enabled = enabled,
                    isEditing = isEditing,
                    onDrag = onDrag,
                    alpha = finalOpacity,
                    onInteraction = onInteraction
                )
            }
        }

        // D-Pad - Left side
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 32.dp)
        ) {
            DPadLayout(finalOpacity, sizeMultiplier, buttonOffsets, enabled, isEditing, onDrag, onInteraction)
        }

        // Select/Start - Bottom center
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GameButton(
                text = "SELECT",
                buttonId = MainActivity.BTN_SELECT,
                modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_SELECT],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
            GameButton(
                text = "START",
                buttonId = MainActivity.BTN_START,
                modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_START],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
        }

        // A/B buttons - Right side
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GameButton(
                text = "B",
                buttonId = MainActivity.BTN_B,
                modifier = Modifier
                    .size((60 * sizeMultiplier).dp)
                    .offset(y = 20.dp),
                offset = buttonOffsets[MainActivity.BTN_B],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
            Spacer(modifier = Modifier.width(16.dp))
            GameButton(
                text = "A",
                buttonId = MainActivity.BTN_A,
                modifier = Modifier
                    .size((60 * sizeMultiplier).dp)
                    .offset(y = (-20).dp),
                offset = buttonOffsets[MainActivity.BTN_A],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                buttonColor = MaterialTheme.colorScheme.tertiary,
                onInteraction = onInteraction
            )
        }
    }
}

@Composable
private fun LandscapeSNESLayout(
    finalOpacity: Float,
    sizeMultiplier: Float,
    buttonOffsets: Map<Int, Offset>,
    enabled: Boolean,
    isEditing: Boolean,
    onDrag: (Int, Offset) -> Unit,
    onInteraction: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // L/R on top
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GameButton(
                text = "L",
                buttonId = MainActivity.BTN_L,
                modifier = Modifier
                    .padding(start = 24.dp)
                    .size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_L],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
            GameButton(
                text = "R",
                buttonId = MainActivity.BTN_R,
                modifier = Modifier
                    .padding(end = 24.dp)
                    .size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_R],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
        }

        // D-Pad - Left side
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 32.dp)
        ) {
            DPadLayout(finalOpacity, sizeMultiplier, buttonOffsets, enabled, isEditing, onDrag, onInteraction)
        }

        // Select/Start - Bottom center
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            GameButton(
                text = "SELECT",
                buttonId = MainActivity.BTN_SELECT,
                modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_SELECT],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
            GameButton(
                text = "START",
                buttonId = MainActivity.BTN_START,
                modifier = Modifier.size((70 * sizeMultiplier).dp, (35 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_START],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
        }

        // SNES Action buttons - Right side
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 32.dp)
        ) {
            SNESActionButtons(finalOpacity, sizeMultiplier, buttonOffsets, enabled, isEditing, onDrag, onInteraction)
        }
    }
}

@Composable
private fun DiamondButtonLayout(
    modifier: Modifier = Modifier,
    sizeMultiplier: Float = 1f,
    topButton: @Composable (() -> Unit),
    bottomButton: @Composable (() -> Unit),
    leftButton: @Composable (() -> Unit),
    rightButton: @Composable (() -> Unit)
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Top button
        topButton()
        
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left button
            leftButton()
            // Center spacer for diamond effect
            Spacer(modifier = Modifier.width((55 * sizeMultiplier).dp))
            // Right button
            rightButton()
        }
        
        // Bottom button
        bottomButton()
    }
}

@Composable
private fun DPadLayout(
    finalOpacity: Float,
    sizeMultiplier: Float,
    buttonOffsets: Map<Int, Offset>,
    enabled: Boolean,
    isEditing: Boolean,
    onDrag: (Int, Offset) -> Unit,
    onInteraction: () -> Unit
) {
    val buttonSize = (55 * sizeMultiplier).dp
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Top button
        GameButton(
            text = "▲",
            buttonId = MainActivity.BTN_UP,
            modifier = Modifier.size(buttonSize),
            offset = buttonOffsets[MainActivity.BTN_UP],
            enabled = enabled,
            isEditing = isEditing,
            onDrag = onDrag,
            alpha = finalOpacity,
            onInteraction = onInteraction
        )
        
        // Middle row with left and right buttons, spaced for diamond
        Row {
            // Left button
            GameButton(
                text = "◀",
                buttonId = MainActivity.BTN_LEFT,
                modifier = Modifier.size(buttonSize),
                offset = buttonOffsets[MainActivity.BTN_LEFT],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
            // Spacer for diamond effect
            Spacer(modifier = Modifier.size(buttonSize))
            // Right button
            GameButton(
                text = "▶",
                buttonId = MainActivity.BTN_RIGHT,
                modifier = Modifier.size(buttonSize),
                offset = buttonOffsets[MainActivity.BTN_RIGHT],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
        }
        
        // Bottom button
        GameButton(
            text = "▼",
            buttonId = MainActivity.BTN_DOWN,
            modifier = Modifier.size(buttonSize),
            offset = buttonOffsets[MainActivity.BTN_DOWN],
            enabled = enabled,
            isEditing = isEditing,
            onDrag = onDrag,
            alpha = finalOpacity,
            onInteraction = onInteraction
        )
    }
}

@Composable
private fun SNESActionButtons(
    finalOpacity: Float,
    sizeMultiplier: Float,
    buttonOffsets: Map<Int, Offset>,
    enabled: Boolean,
    isEditing: Boolean,
    onDrag: (Int, Offset) -> Unit,
    onInteraction: () -> Unit
) {
    DiamondButtonLayout(
        sizeMultiplier = sizeMultiplier,
        topButton = {
            GameButton(
                text = "X",
                buttonId = MainActivity.BTN_X,
                modifier = Modifier.size((55 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_X],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
        },
        bottomButton = {
            GameButton(
                text = "B",
                buttonId = MainActivity.BTN_B,
                modifier = Modifier.size((55 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_B],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
        },
        leftButton = {
            GameButton(
                text = "Y",
                buttonId = MainActivity.BTN_Y,
                modifier = Modifier.size((55 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_Y],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
        },
        rightButton = {
            GameButton(
                text = "A",
                buttonId = MainActivity.BTN_A,
                modifier = Modifier.size((55 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_A],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                buttonColor = MaterialTheme.colorScheme.tertiary,
                onInteraction = onInteraction
            )
        }
    )
}

@Composable
private fun N64ActionButtons(
    finalOpacity: Float,
    sizeMultiplier: Float,
    buttonOffsets: Map<Int, Offset>,
    enabled: Boolean,
    isEditing: Boolean,
    onDrag: (Int, Offset) -> Unit,
    onInteraction: () -> Unit
) {
    // Triangle layout:
    //   B ○   Z ○   ← top row
    //      A ○      ← centred below
    val btnSize = (68 * sizeMultiplier).dp
    val gap = (14 * sizeMultiplier).dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        // Top row: Z on left, B on right
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            GameButton(
                text = "Z",
                buttonId = MainActivity.BTN_Z,
                modifier = Modifier.size(btnSize),
                offset = buttonOffsets[MainActivity.BTN_Z],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                buttonColor = Color(0xFFB39DDB),
                onInteraction = onInteraction
            )
            GameButton(
                text = "B",
                buttonId = MainActivity.BTN_B,
                modifier = Modifier.size(btnSize),
                offset = buttonOffsets[MainActivity.BTN_B],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
        }
        // Bottom: A centred beneath B and Z
        GameButton(
            text = "A",
            buttonId = MainActivity.BTN_A,
            modifier = Modifier.size(btnSize),
            offset = buttonOffsets[MainActivity.BTN_A],
            enabled = enabled,
            isEditing = isEditing,
            onDrag = onDrag,
            alpha = finalOpacity,
            buttonColor = MaterialTheme.colorScheme.tertiary,
            onInteraction = onInteraction
        )
    }
}

@Composable
private fun GenesisActionButtons(
    finalOpacity: Float,
    sizeMultiplier: Float,
    buttonOffsets: Map<Int, Offset>,
    enabled: Boolean,
    isEditing: Boolean,
    onDrag: (Int, Offset) -> Unit,
    onInteraction: () -> Unit
) {
    Column(horizontalAlignment = Alignment.End) {
        GameButton(
            text = "C",
            buttonId = MainActivity.BTN_A,
            modifier = Modifier.size((60 * sizeMultiplier).dp),
            offset = buttonOffsets[MainActivity.BTN_A],
            enabled = enabled,
            isEditing = isEditing,
            onDrag = onDrag,
            alpha = finalOpacity,
            onInteraction = onInteraction
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row {
            GameButton(
                text = "B",
                buttonId = MainActivity.BTN_B,
                modifier = Modifier.size((60 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_B],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row {
            GameButton(
                text = "A",
                buttonId = MainActivity.BTN_Y,
                modifier = Modifier.size((60 * sizeMultiplier).dp),
                offset = buttonOffsets[MainActivity.BTN_Y],
                enabled = enabled,
                isEditing = isEditing,
                onDrag = onDrag,
                alpha = finalOpacity,
                onInteraction = onInteraction
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
fun GameButton(
    text: String,
    buttonId: Int,
    modifier: Modifier = Modifier,
    offset: Offset? = null,
    enabled: Boolean = true,
    isEditing: Boolean = false,
    onDrag: (Int, Offset) -> Unit = { _, _ -> },
    alpha: Float = 1.0f,
    buttonColor: Color = MaterialTheme.colorScheme.primary,
    onInteraction: () -> Unit = {}
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

    val borderModifier = if (isEditing) {
        Modifier.border(2.dp, Color.Red, CircleShape)
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
        onClick = {},
        interactionSource = interactionSource,
        enabled = enabled,
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .then(dragModifier)
            .then(borderModifier)
            .alpha(alpha),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun MenuButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    opacity: Float = 1.0f,
    onInteraction: () -> Unit = {}
) {
    FilledTonalButton(
        onClick = {
            onInteraction()
            onClick()
        },
        modifier = modifier.alpha(opacity),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text("MENU")
    }
}

@Composable
fun FastForwardButton(
    modifier: Modifier = Modifier,
    onFastForward: (Boolean) -> Unit,
    enabled: Boolean,
    opacity: Float = 1.0f,
    onInteraction: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    onInteraction()
                    onFastForward(true)
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> onFastForward(false)
            }
        }
    }

    Button(
        onClick = {},
        interactionSource = interactionSource,
        enabled = enabled,
        modifier = modifier.alpha(opacity),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text("FF")
    }
}
