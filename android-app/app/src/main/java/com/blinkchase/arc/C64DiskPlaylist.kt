package com.blinkchase.arc

import java.io.File
import java.io.IOException
import java.util.Locale

internal object C64DiskPlaylist {
    private val diskExtensions = setOf(
        "d2m", "d4m", "d64", "d6z", "d71", "d7z", "d80", "d81", "d82", "d8z", "g41", "g4z", "g64", "g6z", "nib", "nbz", "x64", "x6z"
    )
    private val diskLabel = Regex(
        """(?i)[\s._-]*[\(\[]?\s*(?:disk|disc|part)\s*[-_. ]*\d+(?:\s*(?:of|/)\s*\d+)?(?:\s*side\s*[a-z0-9]+)?\s*[\)\]]?"""
    )
    private val sideLabel = Regex("""(?i)[\s._-]*[\(\[]?\s*side\s*[-_. ]*[a-z0-9]+\s*[\)\]]?""")
    private val trailingNumber = Regex("""[\s._-]+\d{1,2}$""")
    private val explicitDiskNumber = Regex("""(?i)(?:disk|disc|part)\s*[-_. ]*(\d+)""")
    private val explicitSide = Regex("""(?i)side\s*[-_. ]*([a-z0-9]+)""")
    private val trailingDiskNumber = Regex("""[\s._-]+(\d{1,2})$""")

    fun create(contextCacheDir: File, selectedDisk: File): File? {
        if (!selectedDisk.isFile || selectedDisk.extension.lowercase(Locale.ROOT) !in diskExtensions) {
            return null
        }

        val parent = selectedDisk.parentFile ?: return null
        val selectedKey = gameKey(selectedDisk)
        val disks = parent.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in diskExtensions && gameKey(it) == selectedKey }
            ?.distinctBy { it.absoluteFile }
            ?.sortedWith(compareBy<File>({ diskNumber(it) }, { sideNumber(it) }, { it.name.lowercase(Locale.ROOT) }))
            .orEmpty()

        if (disks.size < 2 || disks.none(::hasDiskLabel) || disks.none { it.absoluteFile == selectedDisk.absoluteFile }) {
            return null
        }

        val playlistDirectory = File(contextCacheDir, "c64-playlists")
        if (!playlistDirectory.isDirectory && !playlistDirectory.mkdirs()) {
            throw IOException("Unable to create C64 playlist directory: ${playlistDirectory.absolutePath}")
        }

        val playlistHash = 31 * parent.absolutePath.hashCode() + selectedKey.hashCode()
        val playlist = File(playlistDirectory, "c64-${Integer.toUnsignedString(playlistHash, 16)}.m3u")
        playlist.writeText(disks.joinToString(separator = "\n", postfix = "\n") { it.absolutePath })
        return playlist
    }

    private fun gameKey(file: File): String =
        file.nameWithoutExtension
            .replace(diskLabel, " ")
            .replace(sideLabel, " ")
            .replace(trailingNumber, " ")
            .replace(Regex("[\\[\\](){}]"), " ")
            .replace(Regex("[._-]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)

    private fun hasDiskLabel(file: File): Boolean =
        diskLabel.containsMatchIn(file.nameWithoutExtension) ||
            sideLabel.containsMatchIn(file.nameWithoutExtension) ||
            trailingNumber.containsMatchIn(file.nameWithoutExtension)

    private fun diskNumber(file: File): Int {
        val stem = file.nameWithoutExtension
        return explicitDiskNumber.find(stem)?.groupValues?.get(1)?.toIntOrNull()
            ?: trailingDiskNumber.find(stem)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1
    }

    private fun sideNumber(file: File): Int {
        val side = explicitSide.find(file.nameWithoutExtension)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
        return when (side) {
            "A" -> 0
            "B" -> 1
            else -> 0
        }
    }
}
