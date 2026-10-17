package com.blinkchase.arc

import android.util.Log
import java.io.File

object RomScanner {
    private val supportedRomExtensions = setOf(
        "a26", "a78", "adf", "adz", "bin", "cdi", "chd", "crt", "cue", "d2m", "d4m", "d64", "d6z", "d71", "d7z",
        "d80", "d81", "d82", "d8z", "dms", "fdi", "g41", "g4z", "g64", "g6z", "gb", "gba", "gbc", "gcm", "gc", "gen", "gdi", "hdf",
        "ipf", "iso", "lha", "lnx", "m3u", "md", "n64", "nds", "nib", "p00", "pbp", "pce",
        "prg", "rvz", "sfc", "sgx", "smc", "smd", "ss", "t64", "tap", "v64", "wbfs", "wii",
        "ws", "wsc", "z64", "zip"
    )

    private val masterFileExtensions = setOf(
        "a26", "a78", "adf", "adz", "chd", "crt", "cue", "d64", "d71", "d81", "dms", "fdi",
        "d2m", "d4m", "d6z", "d7z", "d80", "d82", "d8z", "g41", "g4z", "g64", "g6z", "gb", "gba", "gbc", "gdi", "hdf", "ipf", "lha", "m3u", "n64", "nds",
        "lnx", "nib", "p00", "pce", "pbp", "prg", "rvz", "sfc", "sgx", "smc", "t64", "tap", "v64",
        "wbfs", "ws", "wsc", "z64", "zip"
    )

    private fun normalizeGameTitle(rawName: String): String {
        val extension = rawName.substringAfterLast('.', "").lowercase()
        var title = if (extension in supportedRomExtensions) {
            rawName.dropLast(extension.length + 1)
        } else {
            rawName
        }
        title = title.replace(Regex("(?i)\\s*\\((USA|Europe|Japan|World|En|Fr|De|Es|It|Rev\\s*\\d+|Rev\\d+|Beta|Prototype|Sample|Demo).*?\\)", RegexOption.IGNORE_CASE), "")
        title = title.replace(Regex("(?i)\\s*\\[.*?]", RegexOption.IGNORE_CASE), "")
        title = title.replace(Regex("(?i)\\s*\\((Disc|Disk|Track|Part|Side)\\s*([\\d]+|[a-z]).*?\\)|\\s*\\[(Disc|Disk|Track|Part|Side)\\s*([\\d]+|[a-z]).*?\\]|\\s*(disc|disk|track|part|side)\\s*([\\d]+|[a-z])", RegexOption.IGNORE_CASE), "")
        return title.trim().lowercase()
    }

    fun isScannableName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        val secondaryExtensions = setOf("1", "2", "3", "4", "5", "a", "b", "c", "d", "side1", "side2", "disk1", "disk2")
        if (ext in secondaryExtensions || ext !in supportedRomExtensions) return false
        if (name.startsWith(".")) return false

        val stem = name.substringBeforeLast('.', name).trim()
        val diskLabel = Regex("(?i)^(disk|disc|track|part|data|audio|side)(?:[\\s._-]*\\d+)?$")
        val blockedKeywords = listOf("replit", "license", "readme", "install", "debug", ".nomedia", "changelog", "credits", "config", "cache")
        return !stem.matches(diskLabel) && blockedKeywords.none { stem.contains(it, true) }
    }

    fun isScannableFile(file: File): Boolean = file.isFile && isScannableName(file.name)

    internal fun selectPrimaryFileNames(names: List<String>): Set<String> {
        val scannable = names.filter(::isScannableName)
        val masterFiles = scannable.filter { name ->
            val extension = name.substringAfterLast('.', "").lowercase()
            extension in masterFileExtensions &&
                !name.contains(Regex("(Disc|Disk|Track|Part|Data|Audio|Side)\\s*([2-9]|0[2-9]|\\d{2,})", RegexOption.IGNORE_CASE))
        }
        if (masterFiles.isNotEmpty()) return masterFiles.toSet()

        val isos = scannable.filter { it.substringAfterLast('.', "").equals("iso", true) }
        val bins = scannable.filter { it.substringAfterLast('.', "").equals("bin", true) }
        val others = scannable.filterNot { it.substringAfterLast('.', "").lowercase() in setOf("iso", "bin") }
        val primary = (isos + bins + others).filterNot {
            it.contains(Regex("(Track|Part|Data|Audio|Disc|Disk|Side)\\s*([2-9]|0[2-9]|\\d{2,})", RegexOption.IGNORE_CASE))
        }
        return primary.sorted().firstOrNull()?.let(::setOf) ?: emptySet()
    }

    internal fun shouldSkipDirectory(directory: File): Boolean {
        val name = directory.name
        val blockedFolders = listOf("node_modules", "Android", "replit", "debug", "tmp", "temp", "cache")
        return name.startsWith(".") || blockedFolders.any { it.equals(name, true) }
    }

    fun isWHDLoadInstallDirectory(directory: File): Boolean =
        directory.isDirectory && directory.listFiles()?.any {
            it.isFile && it.extension.equals("slave", ignoreCase = true)
        } == true

    internal fun findWHDLoadInstallDirectories(directory: File): List<File> =
        directory.walkTopDown()
            .onEnter { !shouldSkipDirectory(it) }
            .filter(::isWHDLoadInstallDirectory)
            .toList()

    fun duplicateKey(name: String, platform: Platform): Pair<String, Platform> =
        Pair(normalizeGameTitle(name), platform)

    fun deduplicate(games: List<GameFile>): List<GameFile> =
        games.sortedBy { it.path }.distinctBy { duplicateKey(it.name, it.platform) }

    fun scan(directory: File): List<GameFile> {
        val games = mutableListOf<GameFile>()
        Log.d("ArcScanner", "Scanning directory: ${directory.absolutePath}")

        if (directory.exists() && directory.isDirectory) {
            // Group files by parent directory to handle multi-disc/multi-track sets as units
            directory.walkTopDown()
                .onEnter { 
                    !shouldSkipDirectory(it)
                }
                .filter { file ->
                    isScannableFile(file)
                }
                .groupBy { it.parentFile?.absolutePath ?: "root" }
                .forEach { (_, folderFiles) ->
                    val selectedNames = selectPrimaryFileNames(folderFiles.map { it.name })
                    val folderGames = folderFiles.filter { it.name in selectedNames }

                    folderGames.forEach { file ->
                        val platform = detectConsolePlatform(file)

                        if (platform != Platform.UNKNOWN) {
                            var gameName = file.name.substringBeforeLast('.')
                            if (platform == Platform.AMIGA) {
                                gameName = gameName.replace(Regex("(?i)\\s*\\((Disc|Disk|Track|Part)\\s*\\d+.*?\\)|\\s*\\[(Disc|Disk|Track|Part)\\s*\\d+.*?]|\\s*disk\\s*\\d+", RegexOption.IGNORE_CASE), "").trim()
                            }
                            if (gameName.isNotEmpty() && !gameName.equals("disk", true)) {
                                Log.d("ArcScanner", "Found Game: $gameName ($platform)")
                                games.add(GameFile(name = gameName, path = file.absolutePath, platform = platform))
                            }
                        }
                    }
                }

            findWHDLoadInstallDirectories(directory).forEach { installDirectory ->
                games.add(
                    GameFile(
                        name = installDirectory.name,
                        path = installDirectory.absolutePath,
                        platform = Platform.AMIGA
                    )
                )
            }
        } else {
            Log.e("ArcScanner", "Directory does not exist or cannot be read")
        }
        // Deduplicate games by normalized title and platform to prevent multi-extension / region duplicates (.n64 and .z64, Pokemon Blue variants, etc.)
        return deduplicate(games)
    }
}
