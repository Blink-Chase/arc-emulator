package com.blinkchase.arc

import android.content.Context
import android.util.Log
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

    private fun getScreenScraperSystemId(platform: Platform): Int? = when (platform) {
        Platform.GENESIS -> 1
        Platform.PS1 -> 57
        Platform.GAMECUBE -> 13
        Platform.WII -> 16
        Platform.PS2 -> 23
        Platform.DS -> 15
        Platform.SATURN -> 18
        Platform.DREAMCAST -> 24
        else -> null
    }

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
        Platform.PS2 -> "Sony - PlayStation 2"
        Platform.DS -> "Nintendo - Nintendo DS"
        Platform.SATURN -> "Sega - Saturn"
        Platform.DREAMCAST -> "Sega - Dreamcast"
        else -> null
    }

    fun generateLibretroUrl(game: GameFile, nameOverride: String? = null, type: String = "Named_Boxarts"): String? {
        val platformStr = getLibretroPlatform(game.platform) ?: return null
        val nameToUse = (nameOverride ?: game.name.substringBeforeLast("."))
            .replace(Regex("\\.nkit$", RegexOption.IGNORE_CASE), "")
            .replace("&", "_")
            .replace("*", "_")
            .replace("?", "_")
            .replace("\"", "_")
            .replace("<", "_")
            .replace(">", "_")
            .replace("|", "_")
            .replace(":", "_")
            .replace("/", "_")
            .replace("\\", "_")
        
        // Manual Encoding: Libretro server is picky about parentheses and commas.
        // We only encode spaces and other truly illegal URL characters.
        fun manualEncode(s: String): String {
            // ONLY encode the most basic illegal characters. 
            // Do NOT encode % here because it leads to double-encoding if the string 
            // already contains %20 (which it shouldn't here, but let's be safe).
            return s.replace(" ", "%20")
                .replace("#", "%23")
                .replace("[", "%5B")
                .replace("]", "%5D")
        }

        val encodedPlatform = manualEncode(platformStr)
        val encodedName = manualEncode("$nameToUse.png")
        return "$LIBRETRO_BASE_URL/$encodedPlatform/$type/$encodedName"
    }

    fun fuzzyMatch(name: String): String {
        var clean = name.substringBeforeLast(".")
        clean = clean.replace(Regex("\\.nkit$", RegexOption.IGNORE_CASE), "")
        // Strips region tags (USA), (Europe), etc. but keeps Disc info if possible
        // Actually for a clean base match, we strip everything in brackets
        clean = clean.replace(Regex("\\(.*?\\)"), "")
        clean = clean.replace(Regex("\\[.*?\\]"), "")
        
        return clean.trim().replace(Regex("\\s+"), " ")
    }

    private val SERIAL_REGEX = Regex("([A-Z]{3,4})[-_\\s.]?(\\d{3,5})", RegexOption.IGNORE_CASE)

    suspend fun scrapeGame(game: GameFile, gameDao: GameDao, apiKey: String? = null, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val baseName = game.name.substringBeforeLast(".")
            .replace(Regex("\\.nkit$", RegexOption.IGNORE_CASE), "")
        
        val serialMatch = SERIAL_REGEX.find(baseName)
        val extractedSerial = serialMatch?.let { "${it.groupValues[1].uppercase()}-${it.groupValues[2]}" }

        val namesToTry = mutableListOf<String>()
        
        // --- 0. HARDCODED SERIAL MAPPING (FOR PROBLEMATIC GAMES) ---
        if (extractedSerial == "SCUS-97328" || extractedSerial == "SCUS97328") {
            namesToTry.add("Gran Turismo 4 (USA) (v1.01)")
            namesToTry.add("Gran Turismo 4 (USA)")
        } else if (extractedSerial == "SCES-51719" || extractedSerial == "SCES51719") {
            namesToTry.add("Gran Turismo 4 (Europe, Australia) (En,Fr,De,Es,It)")
        }

        val regions = listOf(
            "(USA)", 
            "(World)", 
            "(Europe)", 
            "(Japan)", 
            "(Japan) (En)", 
            "(En)",
            "(Europe, Australia) (En,Fr,De,Es,It)", 
            "(USA) (v1.01)",
            "(USA) (v1.00)",
            "(USA) (En,Es,Pt)",
            "(USA) (En,Fr,Es)"
        )
        
        // 1. First priority: The actual filename (minus ext) - Good for specific mode art
        namesToTry.add(baseName)
        
        // 2. Second priority: Clean version/mode metadata but keep important tags
        // For GT2: "Gran Turismo 2 (USA) (Arcade Mode) (v1.0)" -> "Gran Turismo 2 (USA) (Arcade Mode)"
        val noVersion = baseName
            .replace(Regex("\\(v\\d+\\.\\d+\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ").trim()
        namesToTry.add(noVersion)

        // 3. Third priority: Specific Mode Tags (Disc/Mode/Version) if not in baseName
        val modeMatch = Regex("\\(.*?mode.*?\\)", RegexOption.IGNORE_CASE).find(baseName)
        if (modeMatch != null) {
            val titleOnly = fuzzyMatch(game.name)
            namesToTry.add("$titleOnly ${modeMatch.value}")
            for (r in regions) namesToTry.add("$titleOnly $r ${modeMatch.value}")
        }

        // 4. Fourth priority: Pure title match (stripped of all brackets)
        val titleOnly = fuzzyMatch(game.name)
        namesToTry.add(titleOnly)
        
        // 4. Region variations on title
        for (r in regions) {
            namesToTry.add("$titleOnly $r")
        }
        
        // 5. Special Case: Multi-disc games (Disc X)
        if (baseName.contains(Regex("Disc\\s*\\d", RegexOption.IGNORE_CASE))) {
            val discMatch = Regex("\\(Disc\\s*\\d\\)", RegexOption.IGNORE_CASE).find(baseName)
            val discTag = discMatch?.value ?: ""
            if (discTag.isNotEmpty()) {
                val titleNoDisc = baseName.replace(discTag, "").replace(Regex("\\s+"), " ").trim()
                val titleNoDiscNoRegion = fuzzyMatch(titleNoDisc)
                namesToTry.add("$titleNoDiscNoRegion $discTag")
                for (r in regions) {
                    namesToTry.add("$titleNoDiscNoRegion $r $discTag")
                }
            }
        }
        
        // 6. Special Case: Handling titles with subtitles
        val separators = listOf(" - ", ": ", " – ", " : ")
        for (sep in separators) {
            if (titleOnly.contains(sep)) {
                val shortTitle = titleOnly.substringBefore(sep).trim()
                namesToTry.add(shortTitle)
                for (r in regions) namesToTry.add("$shortTitle $r")
            }
        }
        
        // 7. Special Case: Sega Rally and Virtua Cop
        if (titleOnly.contains("Sega Rally", ignoreCase = true)) {
            namesToTry.add("Sega Rally Championship")
            namesToTry.add("Sega Rally 2")
            namesToTry.add("Sega Rally Championship 2")
            namesToTry.add("Sega Rally")
        }
        if (titleOnly.contains("Virtua Cop", ignoreCase = true)) {
            namesToTry.add("Virtua Cop")
            namesToTry.add("Virtua Cop 2")
        }

        // 8. Clean symbols variations
        namesToTry.add(titleOnly.replace(":", " -"))
        namesToTry.add(titleOnly.replace(":", ""))
        namesToTry.add(titleOnly.replace("&", "and"))
        namesToTry.add(titleOnly.replace("'", ""))
        
        // 9. Absolute fallback for Gran Turismo series
        if (titleOnly.contains("Gran Turismo", ignoreCase = true)) {
            val match = Regex("Gran Turismo \\d", RegexOption.IGNORE_CASE).find(titleOnly)
            match?.value?.let {
                namesToTry.add(it)
                namesToTry.add("$it (USA)")
                namesToTry.add("$it (USA) (v1.01)") // Specific GT4 version found in database
                namesToTry.add("$it (USA) (v1.00)")
                namesToTry.add("$it (Europe)")
                for (r in regions) namesToTry.add("$it $r")
            }
        }

        val libretroTypes = listOf("Named_Boxarts", "Named_Snaps", "Named_Titles")
        
        val isMissing = game.coverUrl.isNullOrBlank()
        if (force || isMissing) {
            Log.d("ScraperService", "${if(force) "FORCE " else ""}Scraping: ${game.name}")
        }

        // Try Libretro first
        for (type in libretroTypes) {
            val distinctNames = namesToTry.map { it.trim() }.distinct().filter { it.isNotEmpty() }
            for (nameVar in distinctNames) {
                val libretroUrl = generateLibretroUrl(game, nameVar, type)
                if (libretroUrl != null) {
                    if (force || isMissing) Log.d("ScraperService", "Trying Libretro: $libretroUrl")
                    if (verifyUrl(libretroUrl)) {
                        Log.i("ScraperService", "MATCH FOUND (Libretro): $libretroUrl")
                        val updatedGame = game.copy(coverUrl = libretroUrl)
                        gameDao.updateGame(updatedGame)
                        return@withContext true
                    }
                }
            }
        }

        // 2. Try ScreenScraper Fallback (with Serial support)
        if (force || isMissing) Log.d("ScraperService", "No Libretro match, trying ScreenScraper...")
        val screenScraperUrl = fetchScreenScraperUrl(game, apiKey, extractedSerial, force || isMissing)
        if (screenScraperUrl != null) {
            Log.i("ScraperService", "MATCH FOUND (ScreenScraper): $screenScraperUrl")
            val updatedGame = game.copy(coverUrl = screenScraperUrl)
            gameDao.updateGame(updatedGame)
            return@withContext true
        }

        if (force || isMissing) Log.w("ScraperService", "FAILED to find any cover art for: ${game.name}")
        false
    }

    private fun verifyUrl(urlStr: String): Boolean {
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val responseCode = connection.responseCode
            responseCode in 200..299
        } catch (e: Exception) {
            false
        }
    }

    private fun fetchScreenScraperUrl(game: GameFile, apiKey: String?, serial: String? = null, log: Boolean = false): String? {
        val devId = apiKey ?: "guest"
        val devPassword = if (devId == "guest") "guest" else "" 
        
        return try {
            val systemId = getScreenScraperSystemId(game.platform)
            // Try both parameter names as some ScreenScraper endpoints differ
            val systemParameters = listOf(
                systemId?.let { "&systemid=$it" } ?: "",
                systemId?.let { "&systemeid=$it" } ?: ""
            )
            val queryParams = mutableListOf<String>()
            if (serial != null) {
                // Priority #1: The exact serial
                queryParams.add("romserial=${URLEncoder.encode(serial, "UTF-8")}")
                if (serial.contains("-")) {
                    // Priority #2: Serial without hyphen
                    queryParams.add("romserial=${URLEncoder.encode(serial.replace("-", ""), "UTF-8")}")
                }
            }
            
            val queryName = game.name.substringBeforeLast(".")
                .replace(Regex("\\.nkit$", RegexOption.IGNORE_CASE), "")
            
            val names = listOf(
                queryName, // Try full filename for mode specificity
                queryName.replace(Regex("\\(v\\d+\\.\\d+\\)", RegexOption.IGNORE_CASE), "").trim(),
                fuzzyMatch(queryName),
                queryName.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
            ).distinct()
            
            for (name in names) {
                queryParams.add("romname=${URLEncoder.encode(name, "UTF-8")}")
            }

            for (param in queryParams) {
                for (sysParam in systemParameters) {
                    val urlStr = "$SCREENSCRAPER_BASE_URL?devid=$devId&devpassword=$devPassword&softname=ArcEmulator&output=json&$param$sysParam"
                    if (log) Log.d("ScraperService", "Trying ScreenScraper API: $urlStr")
                    val url = URL(urlStr)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000

                    if (connection.responseCode == 200) {
                        val text = connection.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(text)
                        val response = json.optJSONObject("response") ?: continue
                        
                        // ScreenScraper can return either a single 'jeu' or an array of 'jeux'
                        val gameObjects = mutableListOf<JSONObject>()
                        
                        val singleJeu = response.optJSONObject("jeu")
                        if (singleJeu != null) gameObjects.add(singleJeu)
                        
                        val multipleJeux = response.optJSONArray("jeux")
                        if (multipleJeux != null) {
                            for (i in 0 until multipleJeux.length()) {
                                gameObjects.add(multipleJeux.getJSONObject(i))
                            }
                        }

                        for (jeu in gameObjects) {
                            val medias = jeu.optJSONArray("medias") ?: continue
                            // Priority media types: ScreenScraper uses very specific naming for PS2/CD systems
                            val types = listOf(
                                "box-2d", "box-2d-front", "box-3d", "box-3d-front",
                                "titlescreen", "wheel", "screen-game"
                            )
                            for (type in types) {
                                for (i in 0 until medias.length()) {
                                    val media = medias.getJSONObject(i)
                                    if (media.optString("type").equals(type, ignoreCase = true)) {
                                        val artUrl = media.optString("url")
                                        if (artUrl.isNotBlank()) {
                                            if (log) Log.i("ScraperService", "Found Media URL via ScreenScraper: $artUrl")
                                            return artUrl
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        if (log) Log.w("ScraperService", "ScreenScraper API returned code ${connection.responseCode} for $param$sysParam")
                        if (connection.responseCode == 403 || connection.responseCode == 429) {
                            return null
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            if (log) Log.w("ScraperService", "ScreenScraper lookup failed for ${game.name}: ${e.message}")
            null
        }
    }
}
