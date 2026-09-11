package com.blinkchase.arc

import android.content.Context
import com.blinkchase.arc.db.GameDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ScraperService {

    private const val LIBRETRO_BASE_URL = "https://thumbnails.libretro.com"
    private const val SCREENSCRAPER_BASE_URL = "https://www.screenscraper.fr/api2/jeuInfos.php"

    fun getLibretroPlatform(platform: Platform): String? = when (platform) {
        Platform.SNES -> "Nintendo - Super Nintendo Entertainment System"
        Platform.GBA -> "Nintendo - Game Boy Advance"
        Platform.GB -> "Nintendo - Game Boy"
        Platform.GBC -> "Nintendo - Game Boy Color"
        Platform.GENESIS -> "Sega - Mega Drive - Genesis"
        Platform.N64 -> "Nintendo - Nintendo 64"
        Platform.PS1 -> "Sony - PlayStation"
        Platform.GAMECUBE -> "Nintendo - GameCube"
        Platform.WII -> "Nintendo - Wii"
        else -> null
    }

    fun generateLibretroUrl(game: GameFile, nameOverride: String? = null, type: String = "Named_Boxarts"): String? {
        val platformStr = getLibretroPlatform(game.platform) ?: return null
        val nameToUse = nameOverride ?: game.name.substringBeforeLast(".")
        
        // Libretro normalization: replace specific characters
        // No-Intro names are usually clean, but let's be safe.
        // Actually, Libretro thumbnails use the exact No-Intro filename.
        
        val encodedPlatform = URLEncoder.encode(platformStr, "UTF-8").replace("+", "%20")
        val encodedName = URLEncoder.encode("$nameToUse.png", "UTF-8").replace("+", "%20")
        return "$LIBRETRO_BASE_URL/$encodedPlatform/$type/$encodedName"
    }

    fun fuzzyMatch(name: String): String {
        var clean = name.substringBeforeLast(".")
        
        // Handling Roman Numerals for common games
        clean = clean.replace(" 2 ", " II ")
        clean = clean.replace(" 3 ", " III ")
        
        // Strips region tags (USA), (Europe), etc. and other metadata in brackets [!]
        clean = clean.replace(Regex("\\(.*?\\)"), "")
        clean = clean.replace(Regex("\\[.*?\\]"), "")
        
        // Symbols: Libretro database is VERY picky.
        // Some games use ' - ', others use ':', others just space.
        clean = clean.replace(":", " -")
        
        return clean.trim().replace(Regex("\\s+"), " ")
    }

    suspend fun scrapeGame(game: GameFile, gameDao: GameDao, apiKey: String? = null): Boolean = withContext(Dispatchers.IO) {
        val baseName = game.name.substringBeforeLast(".")
        val fuzzy = fuzzyMatch(game.name)
        
        // 1. Try Libretro - Multiple Variations with Suffixes and Types
        val namesToTry = mutableListOf<String>()
        val regions = listOf("(USA)", "(World)", "(Europe)", "(Japan)", "(Japan) (En)", "(En)")
        
        // Basic cleanings
        val noSymbols = fuzzy.replace(Regex("[^a-zA-Z0-9\\s]"), "")
        val hyphenated = fuzzy.replace(" ", "-")
        
        namesToTry.add(fuzzy)
        for (r in regions) namesToTry.add("$fuzzy $r")
        
        namesToTry.add(baseName)
        for (r in regions) namesToTry.add("$baseName $r")
        
        namesToTry.add(noSymbols)
        namesToTry.add(hyphenated)

        val libretroTypes = listOf("Named_Boxarts", "Named_Snaps", "Named_Titles")
        
        for (type in libretroTypes) {
            for (nameVar in namesToTry.distinct()) {
                val libretroUrl = generateLibretroUrl(game, nameVar, type)
                if (libretroUrl != null && verifyUrl(libretroUrl)) {
                    val updatedGame = game.copy(coverUrl = libretroUrl)
                    gameDao.updateGame(updatedGame)
                    return@withContext true
                }
            }
        }

        // 2. Try ScreenScraper Fallback
        val screenScraperUrl = fetchScreenScraperUrl(game, apiKey)
        if (screenScraperUrl != null) {
            val updatedGame = game.copy(coverUrl = screenScraperUrl)
            gameDao.updateGame(updatedGame)
            return@withContext true
        }

        false
    }

    private fun verifyUrl(urlStr: String): Boolean {
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            val responseCode = connection.responseCode
            responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            false
        }
    }

    private fun fetchScreenScraperUrl(game: GameFile, apiKey: String?): String? {
        // TODO: Use a real developer key here instead of guest for better rate limits
        val devId = apiKey ?: "guest"
        val devPassword = if (devId == "guest") "guest" else "" 
        
        return try {
            val query = URLEncoder.encode(game.name.substringBeforeLast("."), "UTF-8")
            val urlStr = "$SCREENSCRAPER_BASE_URL?devid=$devId&devpassword=$devPassword&softname=ArcEmulator&output=json&romname=$query"
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val response = json.optJSONObject("response")
            val jeu = response?.optJSONObject("jeu")
            val medias = jeu?.optJSONArray("medias")
            
            if (medias != null) {
                // Look for box-2D first, then box-3D, then titlescreen
                val types = listOf("box-2d", "box-3d", "titlescreen")
                for (type in types) {
                    for (i in 0 until medias.length()) {
                        val media = medias.getJSONObject(i)
                        if (media.optString("type").equals(type, ignoreCase = true)) {
                            return media.optString("url")
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
