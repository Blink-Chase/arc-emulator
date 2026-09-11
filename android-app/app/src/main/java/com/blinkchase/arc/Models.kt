package com.blinkchase.arc

import android.content.res.Configuration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class InputStyle {
    STANDARD,
    COMPACT,
    MINIMALIST,
    TRANSPARENT,
    HIDDEN
}

enum class ScreenScale {
    RATIO_4_3,
    RATIO_16_9,
    STRETCH
}

enum class ControlPosition {
    DEFAULT,
    LEFT,
    RIGHT,
    CENTER,
    CUSTOM
}

enum class OrientationMode {
    AUTO,           // Follow device orientation
    LANDSCAPE,      // Force landscape
    PORTRAIT,       // Force portrait
    SENSOR          // Use sensors for all orientations
}

enum class EmulatedDevice {
    JOYPAD,         // Digital only (SNES, Genesis, GB)
    ANALOG,         // Digital + Analog (PS1 DualShock, N64)
    MOUSE,          // Mouse emulation
    LIGHTGUN        // Lightgun emulation
}

enum class ControllerModel {
    GENERIC_ABXY,   // A, B, X, Y (SNES/Android standard)
    XBOX,           // A, B, X, Y (Xbox layout)
    PLAYSTATION,    // Cross, Circle, Square, Triangle
    N64             // N64 layout (A, B, C-Buttons, Z)
}

enum class VisualStyle {
    MODERN,
    CLASSIC
}

enum class VerticalAlignment {
    TOP,
    CENTER,
    BOTTOM
}

enum class Platform {
    SNES, GBA, GB, GBC, GENESIS, N64, PS1, GAMECUBE, WII, UNKNOWN;

    fun getColor(): Color = when (this) {
        SNES -> Color(0xFF9575CD) // Purple
        GBA -> Color(0xFF81C784)  // Green
        GB, GBC -> Color(0xFF4DB6AC) // Teal
        GENESIS -> Color(0xFF64B5F6) // Blue
        N64 -> Color(0xFFFF8A65) // Deep Orange
        PS1 -> Color(0xFFE57373) // Red
        GAMECUBE -> Color(0xFFBA68C8) // Lavender
        WII -> Color(0xFF90A4AE) // Blue Grey
        UNKNOWN -> Color(0xFFBDBDBD) // Grey
    }
}

@Entity(tableName = "games", indices = [Index(value = ["path"], unique = true)])
data class GameFile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val path: String,
    val platform: Platform,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastPlayed: Long = 0,
    val isFavorite: Boolean = false,
    val coverUrl: String? = null,
    val thumbnailUrl: String? = null,
    val description: String? = null
)

@Entity(tableName = "controller_profiles", primaryKeys = ["deviceName", "platform", "gamePath"])
data class ControllerProfile(
    val deviceName: String,
    val platform: String = "", // empty string for Global
    val gamePath: String = "", // empty string for Global/Platform
    val buttonMap: Map<Int, Int> = emptyMap(), // Physical KeyCode -> Libretro BTN_ID
    val axisMap: Map<Int, Int> = emptyMap(),    // Physical AxisID -> Libretro AXIS_ID/Direction
    val model: ControllerModel = ControllerModel.GENERIC_ABXY
)

data class GameCheat(var name: String, var code: String, var enabled: Boolean)

enum class SortMode { NAME, DATE_ADDED, LAST_PLAYED }

enum class LibraryViewStyle { DETAILED, POSTER }

enum class SearchGrouping { UNIFIED, BY_PLATFORM }

enum class HomeIdentity { ICON, TEXT }

enum class Screen { HOME, LIBRARY, IMPORT, SEARCH, SETTINGS, GAME, ABOUT, HELP, BIOS, CONTROLLER_MAPPING, CONTROLLER_TEST, SETUP_GUIDE }

// Data class to hold individual button properties for touch layouts
data class ButtonProps(
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1.0f,
    val alpha: Float = 1.0f
)

// Data class to hold control layout configuration
data class ControlLayoutConfig(
    val style: InputStyle = InputStyle.STANDARD,
    val position: ControlPosition = ControlPosition.DEFAULT,
    val visualStyle: VisualStyle = VisualStyle.MODERN,
    val opacity: Float = 1.0f,
    val buttonSize: Float = 1.0f,
    val hapticFeedback: Boolean = true,
    val showInLandscape: Boolean = true,
    val showInPortrait: Boolean = true,
    val autoHideDelay: Int = 0, // 0 = never auto-hide, otherwise seconds
    val portraitGameRatio: Float = 0.45f,
    val portraitAlignment: VerticalAlignment = VerticalAlignment.CENTER
)

// Game state preservation data
data class GameState(
    val gamePath: String = "",
    val gameName: String = "",
    val platformName: String = Platform.UNKNOWN.name,
    val corePath: String = "",
    val isPaused: Boolean = false,
    val currentSlot: Int = 0,
    val totalPlayTime: Long = 0L,
    val lastPlayed: Long = System.currentTimeMillis()
)

// Screen orientation helper
object OrientationHelper {
    fun isLandscape(orientation: Int): Boolean {
        return orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    
    fun getAspectRatio(orientation: Int): Float {
        return if (isLandscape(orientation)) 16f / 9f else 9f / 16f
    }
}

// Default button offsets for control layout (returns empty map for default positions)
object DefaultButtonOffsets {
    fun getDefaultOffsets(): Map<Int, Offset> {
        // Empty map means use default positions defined in ControlsOverlay
        return emptyMap()
    }
    
    fun getOffsetsWithDelta(deltaX: Float = 0f, deltaY: Float = 0f): Map<Int, Offset> {
        return emptyMap()
    }
}
