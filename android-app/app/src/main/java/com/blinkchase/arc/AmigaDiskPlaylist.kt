package com.blinkchase.arc

import java.io.File
import java.io.IOException
import java.util.Locale

internal object AmigaDiskPlaylist {
    private val diskExtensions = setOf("adf", "adz", "dms", "fdi", "ipf")
    private val diskMarker = Regex(
        """(?i)[\s._-]*[\(\[]?\s*(?:disk|disc)\s*[-_. ]*(\d+)(?:\s*(?:of|/)\s*\d+)?\s*[\)\]]?"""
    )

    fun create(contextCacheDir: File, selectedDisk: File): File? {
        if (!selectedDisk.isFile ||
            selectedDisk.extension.lowercase(Locale.ROOT) !in diskExtensions
        ) {
            return null
        }

        val selectedName = selectedDisk.nameWithoutExtension
        val selectedMarker = diskMarker.find(selectedName) ?: return null
        val gameKey = normalize(selectedName.replaceRange(selectedMarker.range, " "))
        val parent = selectedDisk.parentFile ?: return null
        val disks = parent.listFiles()
            ?.mapNotNull { file ->
                if (!file.isFile || file.extension.lowercase(Locale.ROOT) !in diskExtensions) {
                    return@mapNotNull null
                }
                val name = file.nameWithoutExtension
                val marker = diskMarker.find(name) ?: return@mapNotNull null
                if (normalize(name.replaceRange(marker.range, " ")) != gameKey) {
                    return@mapNotNull null
                }
                file to (marker.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE)
            }
            ?.distinctBy { it.first.absoluteFile }
            ?.sortedWith(compareBy({ it.second }, { it.first.name.lowercase(Locale.ROOT) }))
            ?.map { it.first }
            .orEmpty()

        if (disks.size < 2 || disks.none { it.absoluteFile == selectedDisk.absoluteFile }) {
            return null
        }

        val playlistDirectory = File(contextCacheDir, "amiga-playlists")
        if (!playlistDirectory.isDirectory && !playlistDirectory.mkdirs()) {
            throw IOException("Unable to create Amiga playlist directory: ${playlistDirectory.absolutePath}")
        }
        val playlistHash = 31 * parent.absolutePath.hashCode() + gameKey.hashCode()
        val playlist = File(
            playlistDirectory,
            "amiga-${Integer.toUnsignedString(playlistHash, 16)}.m3u"
        )
        playlist.writeText(disks.joinToString(separator = "\n", postfix = "\n") { it.absolutePath })
        return playlist
    }

    private fun normalize(name: String): String =
        name.replace(Regex("[\\[\\](){}]"), " ")
            .replace(Regex("[._-]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
}
