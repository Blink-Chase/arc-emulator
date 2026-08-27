package com.blinkchase.arc

import android.util.Log
import java.io.File

object RomScanner {
    fun scan(directory: File): List<GameFile> {
        val games = mutableListOf<GameFile>()
        Log.d("ArcScanner", "Scanning directory: ${directory.absolutePath}")

        if (directory.exists() && directory.isDirectory) {
            // Debug check to see if we have permission to read
            val files = directory.listFiles()
            if (files == null) {
                Log.e("ArcScanner", "Permission Denied: Cannot list files in ${directory.name}")
            }

            directory.walkTopDown()
                .onEnter { 
                    val name = it.name
                    if (name.startsWith(".")) return@onEnter false
                    if (name.equals("node_modules", true)) return@onEnter false
                    if (name.equals("Android", true)) return@onEnter false
                    
                    // Ignore our own internal Roms folder to avoid duplicates during generic scan
                    if (name.equals("Roms", true) && it.parentFile?.name?.equals("Arc", true) == true) return@onEnter false
                    
                    true
                }
                .forEach { file ->
                if (file.isFile) {
                    val platform = when {
                        file.name.startsWith(".") -> null
                        file.name.endsWith(".sfc", true) || file.name.endsWith(".smc", true) -> Platform.SNES
                        file.name.endsWith(".gba", true) -> Platform.GBA
                        file.name.endsWith(".gb", true) -> Platform.GB
                        file.name.endsWith(".gbc", true) -> Platform.GBC
                        file.name.endsWith(".md", true) || file.name.endsWith(".gen", true) || file.name.endsWith(".smd", true) -> Platform.GENESIS
                        file.name.endsWith(".n64", true) || file.name.endsWith(".z64", true) || file.name.endsWith(".v64", true) -> Platform.N64
                        file.name.endsWith(".chd", true) || file.name.endsWith(".cue", true) || file.name.endsWith(".m3u", true) || file.name.endsWith(".pbp", true) -> Platform.PS1
                        file.name.endsWith(".gcm", true) || file.name.endsWith(".rvz", true) || file.name.endsWith(".gc", true) -> Platform.GAMECUBE
                        file.name.endsWith(".wbfs", true) || file.name.endsWith(".wii", true) -> Platform.WII
                        file.name.endsWith(".iso", true) -> {
                            if (file.absolutePath.contains("GameCube", true) || file.absolutePath.contains("GC", true)) Platform.GAMECUBE
                            else if (file.absolutePath.contains("Wii", true)) Platform.WII
                            else Platform.PS1
                        }
                        // Only count ZIPs if they are in a folder likely to have games
                        file.name.endsWith(".zip", true) && (file.parent?.contains("Rom", true) == true || file.parent?.contains("Game", true) == true) -> Platform.UNKNOWN
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