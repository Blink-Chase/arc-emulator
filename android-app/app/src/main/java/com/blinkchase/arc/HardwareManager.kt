package com.blinkchase.arc

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

object HardwareManager {
    private const val TAG = "HardwareManager"

    enum class DeviceTier {
        LOW,
        MID,
        HIGH
    }

    data class HardwareProfile(
        val totalRamGb: Float,
        val cpuCores: Int,
        val tier: DeviceTier,
        val supportsVulkan: Boolean = false
    )

    fun getDeviceProfile(context: Context): HardwareProfile {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        
        val totalRamGb = memInfo.totalMem.toFloat() / (1024 * 1024 * 1024)
        val cpuCores = Runtime.getRuntime().availableProcessors()
        
        // Simple logic for tiering
        val tier = when {
            totalRamGb >= 7.0f && cpuCores >= 8 -> DeviceTier.HIGH
            totalRamGb >= 3.5f && cpuCores >= 6 -> DeviceTier.MID
            else -> DeviceTier.LOW
        }

        // Basic Vulkan check (Android 7.0+)
        val supportsVulkan = context.packageManager.hasSystemFeature("android.hardware.vulkan.version")

        Log.d(TAG, "Device Profile: RAM=${"%.2f".format(totalRamGb)}GB, Cores=$cpuCores, Tier=$tier, Vulkan=$supportsVulkan")
        
        return HardwareProfile(totalRamGb, cpuCores, tier, supportsVulkan)
    }

    fun isCoreAppropriateForDevice(coreId: String, platform: Platform, tier: DeviceTier): Boolean {
        return when (platform) {
            Platform.PS2, Platform.GAMECUBE, Platform.WII -> tier == DeviceTier.HIGH
            Platform.N64 -> {
                if (coreId.contains("gles3")) tier >= DeviceTier.MID
                else true
            }
            Platform.PS1 -> {
                if (coreId.contains("swanstation")) tier >= DeviceTier.MID
                else true
            }
            else -> true
        }
    }
}
