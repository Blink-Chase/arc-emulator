package com.blinkchase.arc

import android.content.Context
import android.os.Environment
import android.util.Log
import android.content.SharedPreferences
import com.blinkchase.arc.db.GameDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object NovaMigrator {
    private const val TAG = "NovaMigrator"

    fun getNovaFolder(): File? {
        val docs = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Nova")
        val downloads = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nova")
        return when {
            docs.exists() -> docs
            downloads.exists() -> downloads
            else -> null
        }
    }

    fun isNovaFolderPresent(): Boolean = getNovaFolder() != null

    suspend fun migrate(
        context: Context, 
        arcStorageDir: File, 
        gameDao: GameDao, 
        prefs: SharedPreferences
    ): MigrationResult = withContext(Dispatchers.IO) {
        val novaDir = getNovaFolder() ?: return@withContext MigrationResult(false, "Nova folder not found")

        // 1. Physical File Migration (BIOS, Saves, etc)
        val subFolders = listOf("saves", "system", "cores", "layouts")
        var movedCount = 0
        val errors = mutableListOf<String>()

        subFolders.forEach { folderName ->
            val source = File(novaDir, folderName)
            val destination = File(arcStorageDir, folderName)
            
            if (source.exists() && source.isDirectory) {
                if (!destination.exists()) destination.mkdirs()
                
                source.listFiles()?.forEach { file ->
                    try {
                        val targetFile = File(destination, file.name)
                        file.copyTo(targetFile, overwrite = true)
                        movedCount++
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to move ${file.name}: ${e.message}")
                        errors.add(file.name)
                    }
                }
            }
        }

        // 2. Data Migration (JSON Import)
        val jsonFile = File(novaDir, "migration_data.json")
        if (jsonFile.exists()) {
            try {
                val json = JSONObject(jsonFile.readText())
                
                // Migrate Settings
                val settings = json.optJSONObject("settings")
                settings?.let { s ->
                    val editor = prefs.edit()
                    s.keys().forEach { key ->
                        val value = s.get(key)
                        when (value) {
                            is Int -> editor.putInt(key, value)
                            is Boolean -> editor.putBoolean(key, value)
                            is Float -> editor.putFloat(key, value.toFloat())
                            is String -> editor.putString(key, value)
                            is JSONArray -> {
                                val set = mutableSetOf<String>()
                                for (i in 0 until value.length()) {
                                    set.add(value.getString(i))
                                }
                                editor.putStringSet(key, set)
                            }
                        }
                    }
                    editor.apply()
                }

                // Migrate Game Stats (Time Played, Favorites)
                val gamesArray = json.optJSONArray("games")
                if (gamesArray != null) {
                    val gamesToInsert = mutableListOf<GameFile>()
                    for (i in 0 until gamesArray.length()) {
                        val g = gamesArray.getJSONObject(i)
                        val path = g.getString("path")
                        val lastPlayed = g.optLong("lastPlayed", 0)
                        val isFavorite = g.optBoolean("isFavorite", false)
                        val name = g.optString("name", File(path).nameWithoutExtension)
                        val platformName = g.optString("platform", guessPlatform(path).name)
                        
                        val platform = try { Platform.valueOf(platformName) } catch (_: Exception) { guessPlatform(path) }

                        // ONLY migrate if it actually looks like a game or has a known platform
                        if (platform == Platform.UNKNOWN && !path.endsWith(".zip", true)) continue
                        if (name.endsWith(".md", true) || name.endsWith(".txt", true) || name.contains("LICENSE", true)) continue

                        val existingGame = gameDao.getGameByPath(path)
                        if (existingGame != null) {
                            gameDao.updateGame(existingGame.copy(
                                lastPlayed = if (lastPlayed > 0) lastPlayed else existingGame.lastPlayed,
                                isFavorite = isFavorite || existingGame.isFavorite
                            ))
                        } else {
                            gamesToInsert.add(GameFile(
                                name = name,
                                path = path,
                                platform = platform,
                                lastPlayed = lastPlayed,
                                isFavorite = isFavorite
                            ))
                        }
                    }
                    if (gamesToInsert.isNotEmpty()) {
                        gameDao.insertGames(gamesToInsert)
                    }
                }
                Log.d(TAG, "JSON metadata migration complete")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse migration JSON: ${e.message}")
            }
        }

        // 3. Cleanup: rename old folder to backup
        val backupDir = File(novaDir.parentFile, "Nova_Backup_${System.currentTimeMillis()}")
        novaDir.renameTo(backupDir)

        MigrationResult(
            success = true,
            message = "Successfully moved $movedCount files and imported metadata.",
            movedCount = movedCount
        )
    }

    private fun guessPlatform(path: String): Platform {
        val p = path.lowercase()
        return when {
            p.contains("snes") || p.endsWith(".sfc") || p.endsWith(".smc") -> Platform.SNES
            p.contains("gba") || p.endsWith(".gba") -> Platform.GBA
            p.contains("gbc") || p.endsWith(".gbc") -> Platform.GBC
            p.contains("gb") || p.endsWith(".gb") -> Platform.GB
            p.contains("genesis") || p.contains("megadrive") || p.endsWith(".md") || p.endsWith(".gen") -> Platform.GENESIS
            p.contains("n64") || p.endsWith(".z64") || p.endsWith(".n64") -> Platform.N64
            p.contains("ps1") || p.contains("psx") || p.endsWith(".cue") || p.endsWith(".bin") -> Platform.PS1
            p.contains("gc") || p.contains("gamecube") || p.endsWith(".iso") -> Platform.GAMECUBE
            p.contains("wii") || p.endsWith(".wbfs") -> Platform.WII
            else -> Platform.UNKNOWN
        }
    }

    data class MigrationResult(val success: Boolean, val message: String, val movedCount: Int = 0)
}
