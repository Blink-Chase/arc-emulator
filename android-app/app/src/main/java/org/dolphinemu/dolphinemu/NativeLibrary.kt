package org.dolphinemu.dolphinemu

/**
 * Stub class for Dolphin Libretro core JNI compatibility.
 * The core expects this class to exist during JNI_OnLoad.
 */
object NativeLibrary {
    // These methods might be called by the core, but we don't need to implement 
    // them here if the core handles its own libretro lifecycle.
    // They are primarily for the standalone Dolphin app.
    
    @JvmStatic
    fun displayAlert(message: String) {
        // No-op or log
    }
}
