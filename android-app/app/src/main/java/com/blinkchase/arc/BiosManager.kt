package com.blinkchase.arc

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object BiosManager {
    private const val TAG = "BiosManager"

    enum class BiosStatus {
        MISSING,
        VALID,
        CORRUPTED // Invalid MD5
    }

    data class BiosRequirement(
        val platform: Platform,
        val fileName: String,
        val expectedMd5: String,
        val description: String,
        val isOptional: Boolean = false
    )

    val BIOS_REGISTRY = listOf(
        // PlayStation 1
        BiosRequirement(Platform.PS1, "scph5501.bin", "c53d970438f62dfabefcf3e871d757ae", "PS1 North American BIOS (Recommended)"),
        BiosRequirement(Platform.PS1, "scph5500.bin", "8dd7d3d67054166de202cf216e4efcc9", "PS1 Japanese BIOS"),
        BiosRequirement(Platform.PS1, "scph5502.bin", "32736f17079d6b2b60244011849be3d3", "PS1 European BIOS"),
        
        // Game Boy Advance
        BiosRequirement(Platform.GBA, "gba_bios.bin", "a86dbbb51da65dd6dae50d8c3472c44e", "GBA System Boot ROM"),

        // PlayStation 2
        BiosRequirement(Platform.PS2, "scph39001.bin", "28ad553488587d4f3c0517f468600d8f", "PS2 BIOS (Main)"),
        
        // Sega Saturn
        BiosRequirement(Platform.SATURN, "saturn_bios.bin", "af5828cedff51384f99b3c3526ae2391", "Sega Saturn System BIOS")
    )

    data class BiosCheckResult(
        val requirement: BiosRequirement,
        val status: BiosStatus,
        val actualPath: String? = null
    )

    fun calculateFileMd5(file: File): String {
        if (!file.exists() || !file.isFile) return ""
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val fis = FileInputStream(file)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            fis.close()
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    fun scanBiosFiles(storageDir: File): List<BiosCheckResult> {
        val systemDir = File(storageDir, "system").also { it.mkdirs() }
        val results = mutableListOf<BiosCheckResult>()

        // Gather all files recursively in system directory
        val allFiles = systemDir.walkTopDown().filter { it.isFile }.toList()

        BIOS_REGISTRY.forEach { req ->
            // Match file by filename OR by expected MD5 hash (handles custom names or subfolders)
            val matchedFile = allFiles.firstOrNull { file ->
                file.name.equals(req.fileName, true) || calculateFileMd5(file).lowercase() == req.expectedMd5.lowercase()
            }

            if (matchedFile != null) {
                val actualMd5 = calculateFileMd5(matchedFile)
                if (actualMd5.lowercase() == req.expectedMd5.lowercase()) {
                    results.add(BiosCheckResult(req, BiosStatus.VALID, matchedFile.absolutePath))
                } else {
                    results.add(BiosCheckResult(req, BiosStatus.CORRUPTED, matchedFile.absolutePath))
                }
            } else {
                results.add(BiosCheckResult(req, BiosStatus.MISSING))
            }
        }
        return results
    }
}
