package com.blinkchase.arc

import android.util.Log
import java.io.File

object RomScanner {
    private val supportedRomExtensions = setOf(
        "a26", "adf", "adz", "bin", "cdi", "chd", "crt", "cue", "d64", "d71", "d81",
        "dms", "fdi", "g64", "gb", "gba", "gbc", "gcm", "gc", "gen", "gdi", "hdf",
        "ipf", "iso", "lha", "m3u", "md", "n64", "nds", "nib", "p00", "pbp", "pce",
        "prg", "rvz", "sfc", "smc", "smd", "ss", "t64", "tap", "v64", "wbfs", "wii",
        "ws", "z64", "zip"
    )

    private val masterFileExtensions = setOf(
        "a26", "adf", "adz", "chd", "crt", "cue", "d64", "d71", "d81", "dms", "fdi",
        "g64", "gb", "gba", "gbc", "gdi", "hdf", "ipf", "lha", "m3u", "n64", "nds",
        "nib", "p00", "pce", "pbp", "prg", "rvz", "sfc", "smc", "t64", "tap", "v64",
        "wbfs", "ws", "z64", "zip"
    )

    private fun normalizeGameTitle(rawName: String): String {
        val extension = rawName.substringAfterLast('.', "").lowercase()
        var title = if (extension in supportedRomExtensions) {
            rawName.dropLast(extension.length + 1)
        } else {
            rawName
        }
        title = title.replace(Regex("(?i)\\s*\\((USA|Europe|Japan|World|En|Fr|De|Es|It|Rev\\s*\\d+|Rev\\d+|Beta|Prototype|Sample|Demo|Disk\\s*\\d+.*?|Disc\\s*\\d+.*?).*?\\)", RegexOption.IGNORE_CASE), "")
        title = title.replace(Regex("(?i)\\s*\\[.*?]", RegexOption.IGNORE_CASE), "")
        title = title.replace(Regex("(?i)\\s*\\((Disc|Disk|Track|Part|Side)\\s*\\d+.*?\\)|\\s*\\[(Disc|Disk|Track|Part|Side)\\s*\\d+.*?\\]|\\s*disk\\s*\\d+|\\s*disc\\s*\\d+", RegexOption.IGNORE_CASE), "")
        return title.trim().lowercase()
    }

    fun isScannableFile(file: File): Boolean {
        if (!file.isFile || file.extension.lowercase() !in supportedRomExtensions) return false
        if (file.name.startsWith(".")) return false

        val stem = file.nameWithoutExtension.trim()
        val diskLabel = Regex("(?i)^(disk|disc|track|part|data|audio|side)(?:[\\s._-]*\\d+)?$")
        val blockedKeywords = listOf("replit", "license", "readme", "install", "debug", ".nomedia", "changelog", "credits", "config", "cache")
        return !stem.matches(diskLabel) && blockedKeywords.none { stem.contains(it, true) }
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
                    val folderGames = mutableListOf<File>()
                    
                    // 1. Priority #1: Proper Master Files
                    val masterFiles = folderFiles.filter { f ->
                        val ext = f.extension.lowercase()
                        val isMasterExt = ext in masterFileExtensions
                        if (!isMasterExt) return@filter false

                        // Filter out secondary disks (Disk 2, Disk 3, etc.) for multi-disk systems
                        val isSecondaryDisk = f.name.contains(Regex("(Disc|Disk|Track|Part|Data|Audio|Side)\\s*([2-9]|0[2-9]|\\d{2,})", RegexOption.IGNORE_CASE))
                        !isSecondaryDisk
                    }

                    if (masterFiles.isNotEmpty()) {
                        // If we have master files, only add those. Skip all .bin/.iso "noise" in the same folder.
                        folderGames.addAll(masterFiles)
                    } else {
                        // 2. NO MASTER FILES: Pick ONE primary entry from the folder to prevent duplication
                        val isos = folderFiles.filter { f -> f.extension.lowercase() == "iso" }
                        val bins = folderFiles.filter { f -> f.extension.lowercase() == "bin" }
                        val others = folderFiles.filter { f -> !listOf("iso", "bin").contains(f.extension.lowercase()) }
                        
                        // Try to find a primary file (Track 1 or no track tag)
                        val primaryCandidates = (isos + bins + others).filter { 
                            !it.name.contains(Regex("(Track|Part|Data|Audio|Disc|Disk|Side)\\s*([2-9]|0[2-9]|\\d{2,})", RegexOption.IGNORE_CASE)) 
                        }
                        
                        if (primaryCandidates.isNotEmpty()) {
                            folderGames.add(primaryCandidates.sortedBy { it.name }.first())
                        }
                    }

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
