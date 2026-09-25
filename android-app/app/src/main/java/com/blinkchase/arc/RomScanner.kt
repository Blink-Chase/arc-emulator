package com.blinkchase.arc

import android.util.Log
import java.io.File

object RomScanner {
    fun scan(directory: File): List<GameFile> {
        val games = mutableListOf<GameFile>()
        Log.d("ArcScanner", "Scanning directory: ${directory.absolutePath}")

        if (directory.exists() && directory.isDirectory) {
            // Group files by parent directory to handle multi-disc/multi-track sets as units
            directory.walkTopDown()
                .onEnter { 
                    val name = it.name
                    if (name.startsWith(".")) return@onEnter false
                    val blockedFolders = listOf("node_modules", "Android", "replit", "debug", "tmp", "temp", "cache")
                    if (blockedFolders.any { b -> name.equals(b, true) }) return@onEnter false
                    
                    if (name.equals("Roms", true) && it.parentFile?.name?.equals("Arc", true) == true) return@onEnter false
                    true
                }
                .filter { file ->
                    val name = file.name
                    val blockedKeywords = listOf("replit", "license", "readme", "install", "debug", ".nomedia", "changelog", "credits", "config", "cache")
                    file.isFile && !name.startsWith(".") && blockedKeywords.none { b -> name.contains(b, true) }
                }
                .groupBy { it.parentFile?.absolutePath ?: "root" }
                .forEach { (_, folderFiles) ->
                    val folderGames = mutableListOf<File>()
                    
                    // 1. Priority #1: Proper Master Files
                    val masterFiles = folderFiles.filter { f ->
                        val ext = f.extension.lowercase()
                        ext == "cue" || ext == "gdi" || ext == "m3u" || ext == "chd" || ext == "rvz" || ext == "wbfs" || ext == "nds" || ext == "gba" || ext == "sfc" || ext == "smc" || ext == "n64" || ext == "z64"
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
                        } else if (folderFiles.isNotEmpty()) {
                            // Last resort: Just take the first file alphabetic
                            folderGames.add(folderFiles.sortedBy { it.name }.first())
                        }
                    }

                    folderGames.forEach { file ->
                        val platform = detectConsolePlatform(file)

                        if (platform != Platform.UNKNOWN) {
                            Log.d("ArcScanner", "Found Game: ${file.name} ($platform)")
                            games.add(GameFile(name = file.name, path = file.absolutePath, platform = platform))
                        }
                    }
                }
        } else {
            Log.e("ArcScanner", "Directory does not exist or cannot be read")
        }
        return games
    }
}
