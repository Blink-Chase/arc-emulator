package com.blinkchase.arc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val deadzone = maxRadius * 0.08f

    var thumbOffsetX by remember { mutableStateOf(0f) }
    var thumbOffsetY by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .size(size)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val centre = Offset(sizePx / 2f, sizePx / 2f)

                    fun updatePosition(pos: Offset) {
                        var dx = pos.x - centre.x
                        var dy = pos.y - centre.y
                        val dist = sqrt(dx * dx + dy * dy)

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
                            val normalized = ((effectiveDist - deadzone) / (maxRadius - deadzone)).coerceIn(0f, 1f)
                            val angle = atan2(dy, dx)
                            val nx = (cos(angle) * normalized * 32767).toInt().coerceIn(-32768, 32767)
                            val ny = -(sin(angle) * normalized * 32767).toInt().coerceIn(-32768, 32767)
                            onMoved(nx, ny)
                        }
                    }

                    updatePosition(down.position)
                    down.consume()

                    do {
                        val event = awaitPointerEvent()
                        val ptr = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (ptr.positionChanged()) {
                            updatePosition(ptr.position)
                            ptr.consume()
                        }
                    } while (event.changes.any { it.pressed && it.id == down.id })

                    thumbOffsetX = 0f
                    thumbOffsetY = 0f
                    onMoved(0, 0)
                }
            }
    ) {
        // Base / Gate
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.toPx() / 2, size.toPx() / 2)
            val radius = size.toPx() / 2
            
            // Draw octagonal gate hint
            val path = Path()
            for (i in 0 until 8) {
                val angle = (i * 45 - 22.5) * PI / 180
                val px = center.x + cos(angle).toFloat() * radius
                val py = center.y + sin(angle).toFloat() * radius
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            
            drawPath(path, Color.DarkGray.copy(alpha = 0.4f))
            drawPath(path, Color.White.copy(alpha = 0.2f), style = Stroke(width = 2.dp.toPx()))
        }

        // Thumb stick with swirls
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffsetX.roundToInt(), thumbOffsetY.roundToInt()) }
                .size(thumbSize)
                .align(Alignment.Center)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.toPx() / 6
                
                // Stick body
                drawCircle(Color(0xFFBDBDBD), radius)
                
                // Swirls / Concentric rings
                for (i in 1..3) {
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.15f),
                        radius = radius * (i / 4f),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
                
                // Outer highlight
                drawCircle(
                    color = Color.White.copy(alpha = 0.3f),
                    radius = radius,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}
