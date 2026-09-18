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
                    if (name.equals("node_modules", true)) return@onEnter false
                    if (name.equals("Android", true)) return@onEnter false
                    if (name.equals("Roms", true) && it.parentFile?.name?.equals("Arc", true) == true) return@onEnter false
                    true
                }
                .filter { it.isFile }
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
                        // 2. Priority #2: .iso files (if no .cue exists)
                        val isos = folderFiles.filter { f -> f.extension.lowercase() == "iso" }
                        if (isos.isNotEmpty()) {
                            // Filter out "Track 2", "Part 2" etc if someone named their iso like that
                            val primaryIsos = isos.filter { !it.name.contains(Regex("(Track|Part|Data|Audio)\\s*([2-9]|0[2-9]|\\d{2,})", RegexOption.IGNORE_CASE)) }
                            folderGames.addAll(primaryIsos)
                        } else {
                            // 3. Priority #3: .bin files (if no .cue or .iso exists)
                            val bins = folderFiles.filter { f -> f.extension.lowercase() == "bin" }
                            if (bins.isNotEmpty()) {
                                // Take only "Track 1" or files without track tags
                                val primaryBins = bins.filter { !it.name.contains(Regex("(Track|Part|Data|Audio)\\s*([2-9]|0[2-9]|\\d{2,})", RegexOption.IGNORE_CASE)) }
                                // If multiple bins, just take the first one as a last resort
                                if (primaryBins.isNotEmpty()) folderGames.add(primaryBins.first())
                            }
                        }
                    }

                    folderGames.forEach { file ->
                        val platform = when {
                            file.name.endsWith(".sfc", true) || file.name.endsWith(".smc", true) -> Platform.SNES
                            file.name.endsWith(".gba", true) -> Platform.GBA
                            file.name.endsWith(".gb", true) -> Platform.GB
                            file.name.endsWith(".gbc", true) -> Platform.GBC
                            file.name.endsWith(".md", true) || file.name.endsWith(".gen", true) || file.name.endsWith(".smd", true) -> Platform.GENESIS
                            file.name.endsWith(".n64", true) || file.name.endsWith(".z64", true) || file.name.endsWith(".v64", true) -> Platform.N64
                            file.name.endsWith(".nds", true) -> Platform.DS
                            file.name.endsWith(".cdi", true) || file.name.endsWith(".gdi", true) -> Platform.DREAMCAST
                            file.name.endsWith(".chd", true) -> {
                                when {
                                    file.absolutePath.contains("Genesis", true) || file.absolutePath.contains("Mega Drive", true) -> Platform.GENESIS
                                    file.absolutePath.contains("PS2 Games", true) || file.length() > 700_000_000L -> Platform.PS2
                                    file.absolutePath.contains("Saturn", true) -> Platform.SATURN
                                    else -> Platform.PS1
                                }
                            }
                            file.name.endsWith(".cue", true) || file.name.endsWith(".m3u", true) -> {
                                when {
                                    file.absolutePath.contains("Genesis", true) || file.absolutePath.contains("Mega Drive", true) -> Platform.GENESIS
                                    file.absolutePath.contains("PS2 Games", true) -> Platform.PS2
                                    file.absolutePath.contains("Saturn", true) || file.name.contains("Virtua", true) || file.name.contains("Rally", true) -> Platform.SATURN
                                    else -> Platform.PS1
                                }
                            }
                            file.name.endsWith(".iso", true) -> {
                                when {
                                    file.absolutePath.contains("Genesis", true) || file.absolutePath.contains("Mega Drive", true) -> Platform.GENESIS
                                    file.absolutePath.contains("GameCube Games", true) || file.name.contains(".nkit.", true) -> Platform.GAMECUBE
                                    file.absolutePath.contains("Wii Games", true) -> Platform.WII
                                    file.absolutePath.contains("Dreamcast", true) -> Platform.DREAMCAST
                                    file.absolutePath.contains("PS2 Games", true) || file.length() > 800_000_000L -> Platform.PS2
                                    file.absolutePath.contains("Saturn", true) || file.name.contains("Virtua", true) || file.name.contains("Rally", true) -> Platform.SATURN
                                    else -> Platform.PS1
                                }
                            }
                            else -> null
                        }

                        if (platform != null) {
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
