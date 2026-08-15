package com.blinkchase.arc

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.*

@Composable
fun VirtualJoystick(
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    onMoved: (x: Int, y: Int) -> Unit
) {
    val thumbSize = size / 3
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }
    val maxRadius = sizePx / 2f
    val deadzone = maxRadius * 0.08f  // 8% centre deadzone

    var thumbOffsetX by remember { mutableStateOf(0f) }
    var thumbOffsetY by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.3f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Capture initial finger-down immediately (no drag delay)
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val centre = androidx.compose.ui.geometry.Offset(sizePx / 2f, sizePx / 2f)

                    fun updatePosition(pos: androidx.compose.ui.geometry.Offset) {
                        var dx = pos.x - centre.x
                        var dy = pos.y - centre.y
                        val dist = sqrt(dx * dx + dy * dy)

                        // Clamp thumb to circle boundary
                        if (dist > maxRadius) {
                            val ratio = maxRadius / dist
                            dx *= ratio
                            dy *= ratio
                        }

                        thumbOffsetX = dx
                        thumbOffsetY = dy

                        val effectiveDist = sqrt(dx * dx + dy * dy)
                        if (effectiveDist < deadzone) {
                            onMoved(0, 0)
                        } else {
                            // Smoothly remap [deadzone..maxRadius] -> [0..1]
                            val normalized = ((effectiveDist - deadzone) / (maxRadius - deadzone)).coerceIn(0f, 1f)
                            val angle = atan2(dy, dx)
                            val nx = (cos(angle) * normalized * 32767).toInt().coerceIn(-32768, 32767)
                            // Invert Y: screen Y increases downward, N64 Y increases upward
                            val ny = -(sin(angle) * normalized * 32767).toInt().coerceIn(-32768, 32767)
                            onMoved(nx, ny)
                        }
                    }

                    // Report the initial press position right away
                    updatePosition(down.position)
                    down.consume()

                    // Track finger movement until release
                    do {
                        val event = awaitPointerEvent()
                        val ptr = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (ptr.positionChanged()) {
                            updatePosition(ptr.position)
                            ptr.consume()
                        }
                    } while (event.changes.any { it.pressed && it.id == down.id })

                    // Reset on finger lift
                    thumbOffsetX = 0f
                    thumbOffsetY = 0f
                    onMoved(0, 0)
                }
            }
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffsetX.roundToInt(), thumbOffsetY.roundToInt()) }
                .size(thumbSize)
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.7f))
        )
    }
}
