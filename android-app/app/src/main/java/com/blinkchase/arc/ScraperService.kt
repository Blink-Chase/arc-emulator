package com.blinkchase.arc

import android.content.Context
import android.util.Log
import com.blinkchase.arc.db.GameDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object ScraperService {

    private const val LIBRETRO_BASE_URL = "https://thumbnails.libretro.com"
    private const val SCREENSCRAPER_BASE_URL = "https://www.screenscraper.fr/api2/jeuInfos.php"
    private val artworkCatalogCache = ConcurrentHashMap<String, List<String>>()
    private val ROM_EXTENSIONS = setOf(
        "a26", "a78", "adf", "adz", "bin", "cdi", "chd", "cue", "d64", "d71", "d81", "dms", "fdi", "gb",
        "gba", "gbc", "gcm", "gc", "gen", "gdi", "hdf", "ipf", "iso", "lha", "m3u",
        "lnx", "md", "n64", "nds", "pbp", "pce", "rvz", "sfc", "smc", "smd", "ss", "v64",
        "wbfs", "wii", "ws", "z64", "zip"
    )

    private fun titleWithoutRomExtension(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return if (extension in ROM_EXTENSIONS) name.dropLast(extension.length + 1) else name
    }

    private fun getScreenScraperSystemId(game: GameFile): Int? = when (game.platform) {
        Platform.GENESIS -> 1
        Platform.ATARI -> 26
        Platform.ATARI_7800 -> 27
        Platform.LYNX -> 28
        Platform.TURBOGRAFX -> 31
        Platform.WONDERSWAN -> if (isWonderSwanColor(game)) 46 else 45
        Platform.PS1 -> 57
        Platform.GAMECUBE -> 13
        Platform.WII -> 16
        Platform.PS2 -> 23
        Platform.DS -> 15
        Platform.SATURN -> 18
        Platform.DREAMCAST -> 24
        Platform.AMIGA -> 64
        else -> null
    }

    fun getLibretroPlatform(platform: Platform): String? = when (platform) {
        Platform.SNES -> "Nintendo - Super Nintendo Entertainment System"
        Platform.TURBOGRAFX -> "NEC - PC Engine - TurboGrafx 16"
        Platform.WONDERSWAN -> "Bandai - WonderSwan"
        Platform.ATARI -> "Atari - 2600"
        Platform.ATARI_7800 -> "Atari - 7800"
        Platform.LYNX -> "Atari - Lynx"
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
        Platform.AMIGA -> "Commodore - Amiga"
        Platform.C64 -> "Commodore - 64"
        else -> null
    }

    private fun isWonderSwanColor(game: GameFile): Boolean {
        val pathParts = game.path.replace('\\', '/').split('/')
        if (pathParts.any {
                it.lowercase().replace(Regex("[^a-z0-9]"), "") == "wonderswancolor"
            }) return true

        val extension = game.path.substringBefore('?').substringAfterLast('.', "").lowercase()
        if (extension == "wsc") return true
        if (extension != "zip") return false

        return try {
            val zipFile = File(game.path)
            if (!zipFile.isFile) return false
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                var hasWonderSwanRom = false
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    when (entry.name.substringAfterLast('.', "").lowercase()) {
                        "wsc" -> return true
                        "ws", "bin" -> hasWonderSwanRom = true
                    }
                }
                hasWonderSwanRom && pathParts.any {
                    it.contains("wonderswan", ignoreCase = true) &&
                        it.contains("color", ignoreCase = true)
                }
            }
        } catch (e: Exception) {
            Log.w("ScraperService", "Unable to inspect WonderSwan archive ${game.path}: ${e.message}")
            false
        }
    }

    private fun getLibretroPlatform(game: GameFile): String? =
        if (game.platform == Platform.WONDERSWAN && isWonderSwanColor(game)) {
            "Bandai - WonderSwan Color"
        } else {
            getLibretroPlatform(game.platform)
        }

    fun generateLibretroUrl(game: GameFile, nameOverride: String? = null, type: String = "Named_Boxarts"): String? {
        val platformStr = getLibretroPlatform(game) ?: return null
        val nameToUse = (nameOverride ?: titleWithoutRomExtension(game.name))
            .replace(Regex("\\.nkit$", RegexOption.IGNORE_CASE), "")

        val encodedPlatform = encodePathSegment(platformStr)
        val encodedName = encodePathSegment("$nameToUse.png")
        return "$LIBRETRO_BASE_URL/$encodedPlatform/$type/$encodedName"
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%28", "(", ignoreCase = true)
            .replace("%29", ")", ignoreCase = true)
            .replace("%2C", ",", ignoreCase = true)

    private fun toProperTitleCase(s: String): String {
        val lowercaseWords = setOf("of", "the", "and", "in", "on", "a", "an", "to", "for", "with")
        val words = s.split(" ").filter { it.isNotBlank() }
        return words.mapIndexed { index, word ->
            val lower = word.lowercase()
            if (index > 0 && lower in lowercaseWords) {
                lower
            } else {
                lower.replaceFirstChar { it.uppercase() }
            }
        }.joinToString(" ")
    }

    fun fuzzyMatch(name: String): String {
        var clean = titleWithoutRomExtension(name)
        clean = clean.replace(Regex("\\.nkit$", RegexOption.IGNORE_CASE), "")
        // Safely strip version tags including underscores/hyphens (e.g. _v4, -v1.0)
        clean = clean.replace(Regex("(?i)[_\\s-]*\\b(v|ver|version|rev)\\s*\\d+(\\.\\d+)*\\b"), "")
        // Strip WHDLoad memory tags
        clean = clean.replace(Regex("(?i)\\b\\d+\\s*mb\\b"), "")
        // Strip parentheses and brackets
        clean = clean.replace(Regex("\\(.*?\\)"), "")
        clean = clean.replace(Regex("\\[.*?\\]"), "")
        // Strip trailing numbers attached directly or with space
        clean = clean.replace(Regex("(?i)\\d+$"), "").trim()
        // Replace underscores with spaces
        clean = clean.replace('_', ' ')
        
        return toProperTitleCase(clean.trim().replace(Regex("\\s+"), " "))
    }

    private fun generateGeneralVariants(name: String): Set<String> {
        val variants = linkedSetOf<String>()
        val properTitle = toProperTitleCase(name)
        variants.add(properTitle)
        variants.add(name)

        // 1. Handle "The " prefix <-> ", The" suffix universally
        val lower = properTitle.lowercase()
        if (lower.startsWith("the ")) {
            val withoutThe = properTitle.substring(4).trim()
            variants.add("$withoutThe, The")
            variants.add(withoutThe)
        } else if (lower.endsWith(", the")) {
            val withoutCommaThe = properTitle.dropLast(5).trim()
            variants.add("The $withoutCommaThe")
            variants.add(withoutCommaThe)
        } else {
            variants.add("$properTitle, The")
            variants.add("The $properTitle")
        }

        // 2. Insert spaces before capital letters (e.g. "PrinceOfPersia" -> "Prince Of Persia" -> "Prince of Persia")
        val spaced = name.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
        if (spaced != name) {
            val properSpaced = toProperTitleCase(spaced)
            variants.add(properSpaced)
            variants.add(spaced)
        }
        val unspaced = name.replace(" ", "")
        if (unspaced != name) {
            variants.add(unspaced)
        }

        // 3. Remove punctuation / symbols
        val noSymbols = name.replace(Regex("[^A-Za-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        variants.add(noSymbols)

        // 4. Progressive word fallbacks
        val words = noSymbols.split(" ").filter { it.isNotBlank() }
        if (words.size > 2) {
            variants.add(toProperTitleCase(words.take(3).joinToString(" ")))
        }
        if (words.size > 1) {
            variants.add(toProperTitleCase(words.take(2).joinToString(" ")))
        }
        if (words.isNotEmpty()) {
            variants.add(toProperTitleCase(words.first()))
        }

        return variants
    }

    private fun normalizeArtworkTitle(name: String): String =
        titleWithoutRomExtension(name)
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun editSimilarity(first: String, second: String): Double {
        if (first == second) return 1.0
        if (first.isEmpty() || second.isEmpty()) return 0.0

        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)
        for (i in first.indices) {
            current[0] = i + 1
            for (j in second.indices) {
                val substitutionCost = if (first[i] == second[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + substitutionCost
                )
            }
            val previousRow = previous
            previous = current
            current = previousRow
        }
        return 1.0 - previous[second.length].toDouble() / maxOf(first.length, second.length)
    }

    internal fun closestArtworkName(gameName: String, artworkNames: List<String>): String? {
        val query = normalizeArtworkTitle(gameName)
        if (query.isEmpty()) return null
        val queryTokens = query.split(' ').toSet()
        val queryWords = query.split(' ')

        val phraseMatches = artworkNames.mapNotNull { artworkName ->
            val candidate = normalizeArtworkTitle(artworkName)
            val candidateWords = candidate.split(' ')
            if (queryTokens.filter { it.all(Char::isDigit) }
                    .any { it !in candidateWords }) {
                return@mapNotNull null
            }
            val start = candidateWords.indices.firstOrNull { index ->
                index + queryWords.size <= candidateWords.size &&
                    candidateWords.subList(index, index + queryWords.size) == queryWords
            } ?: return@mapNotNull null

            val extraWords = candidateWords.size - queryWords.size
            artworkName to (0.85 - (extraWords * 0.03) - (start * 0.02))
        }.sortedByDescending { it.second }
        if (phraseMatches.isNotEmpty()) {
            if (phraseMatches.size == 1 ||
                phraseMatches[0].second - phraseMatches[1].second >= 0.05
            ) {
                return phraseMatches.first().first
            }
            return null
        }

        return artworkNames.mapNotNull { artworkName ->
            val candidate = normalizeArtworkTitle(artworkName)
            if (candidate.isEmpty()) return@mapNotNull null
            if (candidate == query) return artworkName

            val candidateTokens = candidate.split(' ').toSet()
            if (queryTokens.filter { it.all(Char::isDigit) }.any { it !in candidateTokens }) {
                return@mapNotNull null
            }

            val sharedTokens = queryTokens.intersect(candidateTokens).size
            if (sharedTokens < minOf(2, queryTokens.size)) return@mapNotNull null

            val dice = 2.0 * sharedTokens / (queryTokens.size + candidateTokens.size)
            val score = 0.65 * dice + 0.35 * editSimilarity(query, candidate)
            if (score >= 0.72) artworkName to score else null
        }.maxByOrNull { it.second }?.first
    }

    private fun fetchArtworkCatalog(game: GameFile, type: String): List<String>? {
        val platformName = getLibretroPlatform(game) ?: return null
        val cacheKey = "$platformName/$type"
        artworkCatalogCache[cacheKey]?.let { return it }

        val url = "$LIBRETRO_BASE_URL/${encodePathSegment(platformName)}/$type/"
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            if (connection.responseCode !in 200..299) return null

            val html = connection.inputStream.bufferedReader().use { it.readText() }
            val names = Regex("""href="([^"]+\.png)"""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { URLDecoder.decode(it.groupValues[1], Charsets.UTF_8.name()) }
                .map { it.substringAfterLast('/').removeSuffix(".png") }
                .filter { it.isNotBlank() }
                .toList()
            artworkCatalogCache[cacheKey] = names
            names
        } catch (e: Exception) {
            Log.w("ScraperService", "Unable to load artwork index for $platformName: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private val AMIGA_ALIASES = mapOf(
        "wdc" to listOf("Worms - The Director's Cut", "Worms Director's Cut", "Worms"),
        "wdc30" to listOf("Worms - The Director's Cut", "Worms Director's Cut", "Worms"),
        "worms" to listOf("Worms - The Director's Cut", "Worms"),
        "bombfusion" to listOf("Bomb Fusion"),
        "pop" to listOf("Prince of Persia"),
        "prince of persia" to listOf("Prince of Persia"),
        "last ninja" to listOf("Last Ninja, The", "Last Ninja"),
        "lastninja" to listOf("Last Ninja, The", "Last Ninja"),
        "sotb" to listOf("Shadow of the Beast")
    )

    internal fun amigaNameVariants(name: String): List<String> {
        val rawName = titleWithoutRomExtension(name).trim()

        // Aggressively clean trailing version/region tags like _v4, _v4_ntsc, memory tags, version tags, hyphens, and dots
        val cleaned = rawName
            .replace(Regex("(?i)[_\\s.-]+(v\\d+.*|ver\\d+.*|rev\\d+.*|ntsc.*|pal.*)$"), "")
            .replace(Regex("(?i)\\b\\d+\\s*mb\\b"), "")
            .replace(Regex("(?i)[_\\s-]*\\b(v|ver|version|rev)\\b.*$"), "")
            .replace(Regex("(?i)\\b\\d+$"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace('-', ' ')
            .replace('.', ' ')
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        val primaryCandidates = linkedSetOf<String>()

        // 1. Check known aliases first
        val normalizedCleaned = cleaned.lowercase()
        for ((alias, mappedNames) in AMIGA_ALIASES) {
            if (normalizedCleaned.contains(alias)) {
                primaryCandidates.addAll(mappedNames)
            }
        }

        // 2. Cleaned title and proper title case variants
        primaryCandidates.add(toProperTitleCase(cleaned))
        primaryCandidates.add(cleaned)
        primaryCandidates.addAll(generateGeneralVariants(cleaned))

        // 3. Build final candidates list prioritizing clean titles with regions FIRST
        val finalCandidates = linkedSetOf<String>()
        for (base in primaryCandidates) {
            finalCandidates.add(base)
            finalCandidates.add("$base (Europe)")
            finalCandidates.add("$base (USA)")
            finalCandidates.add("$base (World)")
        }

        // 4. Low-priority fallbacks (lowercase / unspaced) at the end
        for (base in primaryCandidates.toList()) {
            finalCandidates.add(base.lowercase())
            finalCandidates.add(base.replace(" ", "").lowercase())
        }

        return finalCandidates.filter { it.isNotEmpty() }.distinct()
    }

    private val SERIAL_REGEX = Regex("([A-Z]{3,4})[-_\\s.]?(\\d{3,5})", RegexOption.IGNORE_CASE)

    suspend fun scrapeGame(game: GameFile, gameDao: GameDao, apiKey: String? = null, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        // QUICK EXIT: If artwork already exists and we aren't forcing, skip to save resources
        if (!force && !game.coverUrl.isNullOrBlank()) {
            return@withContext true
        }

        // --- 0. LOCAL ARTWORK DISCOVERY ---
        val gameFile = File(game.path)
        val gameDir = if (gameFile.isDirectory) gameFile else gameFile.parentFile
        if (gameDir != null && gameDir.exists()) {
            val localArtNames = listOf(
                "cover.jpg", "cover.png", "boxart.jpg", "boxart.png",
                "folder.jpg", "front.jpg", "poster.jpg",
                "${gameFile.nameWithoutExtension}.jpg", "${gameFile.nameWithoutExtension}.png"
            )
            for (artName in localArtNames) {
                val artFile = File(gameDir, artName)
                if (artFile.exists() && artFile.isFile) {
                    val localUrl = artFile.absolutePath
                    Log.i("ScraperService", "MATCH FOUND (Local File): $localUrl")
                    val updatedGame = game.copy(coverUrl = localUrl)
                    gameDao.updateGame(updatedGame)
                    return@withContext true
                }
            }
            if (gameFile.isDirectory) {
                gameFile.listFiles()?.forEach { f ->
                    if (f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png")) {
                        val localUrl = f.absolutePath
                        Log.i("ScraperService", "MATCH FOUND (Local File in directory): $localUrl")
                        val updatedGame = game.copy(coverUrl = localUrl)
                        gameDao.updateGame(updatedGame)
                        return@withContext true
                    }
                }
            }
        }

        val baseName = titleWithoutRomExtension(game.name)
            .replace(Regex("\\.nkit$", RegexOption.IGNORE_CASE), "")
        
        val serialMatch = SERIAL_REGEX.find(baseName)
        val extractedSerial = serialMatch?.let { "${it.groupValues[1].uppercase()}-${it.groupValues[2]}" }

        val namesToTry = mutableListOf<String>()
        if (game.platform == Platform.AMIGA || game.platform == Platform.C64) {
            namesToTry.addAll(amigaNameVariants(baseName))
        }
        
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
        if (game.platform != Platform.AMIGA) {
            for (r in regions) {
                namesToTry.add("$titleOnly $r")
            }
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
        
        // 9. General Search Fallback: Strip subtitle/edition suffixes like "Director's Cut", "DC", "Special Edition", etc.
        val generalTitle = titleOnly
            .replace(Regex("(?i)\\b(Director's Cut|Directors Cut|DC|Special Edition|GOTY|Deluxe|Edition)\\b.*"), "")
            .trim()
        if (generalTitle.isNotEmpty() && generalTitle != titleOnly) {
            namesToTry.add(generalTitle)
            for (r in regions) namesToTry.add("$generalTitle $r")
        }

        // 10. Absolute fallback for Gran Turismo series
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

        val boxartNames = fetchArtworkCatalog(game, "Named_Boxarts")
        val closestBoxart = boxartNames?.let { closestArtworkName(baseName, it) }
        if (closestBoxart != null) {
            val closestUrl = generateLibretroUrl(game, closestBoxart)
            if (closestUrl != null) {
                Log.i("ScraperService", "MATCH FOUND (closest Libretro title): $closestUrl")
                gameDao.updateGame(game.copy(coverUrl = closestUrl))
                return@withContext true
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
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val responseCode = connection.responseCode
            responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchScreenScraperUrl(game: GameFile, apiKey: String?, serial: String? = null, log: Boolean = false): String? {
        val devId = apiKey ?: "guest"
        val devPassword = if (devId == "guest") "guest" else "" 
        
        return try {
            val systemId = getScreenScraperSystemId(game)
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
            
            val queryName = titleWithoutRomExtension(game.name)
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
