package com.blinkchase.arc

import android.view.KeyEvent
import java.io.File
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun capsimgExtractionSelectsTheRequestedAndroidAbi() {
        val archive = File(temporaryFolder.root, "capsimg.zip")
        val arm64Library = byteArrayOf(1, 2, 3)
        java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("Android/x86/capsimg.so"))
            zip.write(byteArrayOf(9))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("Android/arm64-v8a/capsimg.so"))
            zip.write(arm64Library)
            zip.closeEntry()
        }

        val output = File(temporaryFolder.root, "installed-capsimg.so")
        assertTrue(CoreManager.extractCapsImgLibrary(archive, "arm64-v8a", output))
        assertArrayEquals(arm64Library, output.readBytes())
        assertFalse(CoreManager.extractCapsImgLibrary(archive, "riscv64", File(temporaryFolder.root, "unsupported.so")))
    }

    @Test
    fun amigaDiscImagesAreNotClassifiedAsPs2() {
        val amigaIso = File(File(System.getProperty("java.io.tmpdir"), "Amiga Games"), "game.iso")
        val genericIso = File(File(System.getProperty("java.io.tmpdir"), "PlayStation 2"), "game.iso")

        assertEquals(Platform.AMIGA, detectConsolePlatform(amigaIso))
        assertEquals(Platform.PS2, detectConsolePlatform(genericIso))
    }

    @Test
    fun amigaSidecarFilesAreNotRecognizedAsGames() {
        val infoFile = File(File(System.getProperty("java.io.tmpdir"), "Amiga"), "game.info")

        assertEquals(Platform.UNKNOWN, detectConsolePlatform(infoFile))
    }

    @Test
    fun duplicateTitlesAreCollapsedWithinAPlatform() {
        val first = GameFile(name = "Game (USA)", path = "/roms/a-game.adf", platform = Platform.AMIGA)
        val second = GameFile(name = "game", path = "/roms/b-game.adz", platform = Platform.AMIGA)
        val sameTitleOtherPlatform = GameFile(name = "game", path = "/roms/game.iso", platform = Platform.PS2)

        assertEquals(
            listOf(first, sameTitleOtherPlatform),
            RomScanner.deduplicate(listOf(second, sameTitleOtherPlatform, first))
        )
    }

    @Test
    fun duplicateNormalizationKeepsExplicitGameVersionsDistinct() {
        val versionNine = RomScanner.duplicateKey("Mortal Kombat v0.9", Platform.AMIGA)
        val versionEight = RomScanner.duplicateKey("Mortal Kombat v0.8", Platform.AMIGA)

        assertNotEquals(versionNine, versionEight)
    }

    @Test
    fun scannerRejectsInfoAndStandaloneDiskFiles() {
        val infoFile = temporaryFolder.newFile("game.info")
        val diskFile = temporaryFolder.newFile("Disk 1.adf")

        assertFalse(RomScanner.isScannableFile(infoFile))
        assertFalse(RomScanner.isScannableFile(diskFile))
        assertFalse(RomScanner.isScannableName("game.info"))
        assertTrue(RomScanner.isScannableName("game.a26"))
    }

    @Test
    fun storageProviderMetadataCanDrivePlatformDetectionWithoutLocalFile() {
        assertEquals(
            Platform.ATARI_7800,
            detectConsolePlatform("game.bin", "Atari 7800/game.bin", 16)
        )
        assertEquals(
            Platform.C64,
            detectConsolePlatform("game.tap", "C64/game.tap", 16)
        )
    }

    @Test
    fun scannerSelectsOnePrimaryImagePerSafDirectory() {
        assertEquals(
            setOf("game.cue"),
            RomScanner.selectPrimaryFileNames(listOf("game.cue", "game.bin", "game Track 2.bin"))
        )
        assertEquals(
            setOf("game.a26"),
            RomScanner.selectPrimaryFileNames(listOf("game.a26", "game Disk 2.a26"))
        )
    }

    @Test
    fun scannerRecognizesAtariAndCommodore64Formats() {
        val atari = File(temporaryFolder.newFolder("Atari 2600"), "game.a26").apply { createNewFile() }
        val atariBinary = File(temporaryFolder.newFolder("Atari"), "game.bin")
        val atari7800 = File(temporaryFolder.newFolder("Atari 7800"), "game.a78").apply { createNewFile() }
        val atariLynx = File(temporaryFolder.newFolder("Atari Lynx"), "game.lnx").apply { createNewFile() }
        val c64 = File(temporaryFolder.newFolder("C64"), "game.tap").apply { createNewFile() }

        assertTrue(RomScanner.isScannableFile(atari))
        assertEquals(Platform.ATARI, detectConsolePlatform(atari))
        assertEquals(Platform.ATARI, detectConsolePlatform(atariBinary))
        assertTrue(RomScanner.isScannableFile(atari7800))
        assertEquals(Platform.ATARI_7800, detectConsolePlatform(atari7800))
        assertTrue(RomScanner.isScannableFile(atariLynx))
        assertEquals(Platform.LYNX, detectConsolePlatform(atariLynx))
        assertTrue(RomScanner.isScannableFile(c64))
        assertEquals(Platform.C64, detectConsolePlatform(c64))
    }

    @Test
    fun atariBinsAndArchivesUseTheirSystemFolder() {
        val lynxBin = File(temporaryFolder.newFolder("Atari Lynx"), "game.bin")
        lynxBin.writeBytes(byteArrayOf(1))
        val atari7800Zip = File(temporaryFolder.newFolder("Atari 7800"), "game.zip")
        java.util.zip.ZipOutputStream(atari7800Zip.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("game.bin"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        assertEquals(Platform.LYNX, detectConsolePlatform(lynxBin))
        assertEquals(Platform.ATARI_7800, detectConsolePlatform(atari7800Zip))
    }

    @Test
    fun romFilesAreRecognizedForTurboGrafxAndWonderSwan() {
        val wonderSwanDirectory = temporaryFolder.newFolder("WonderSwan")
        val files = listOf(
            File(temporaryFolder.newFolder("TurboGrafx-16"), "game.pce") to Platform.TURBOGRAFX,
            File(temporaryFolder.newFolder("SuperGrafx"), "game.sgx") to Platform.TURBOGRAFX,
            File(wonderSwanDirectory, "game.ws") to Platform.WONDERSWAN,
            File(temporaryFolder.newFolder("WonderSwan Color"), "game.wsc") to Platform.WONDERSWAN,
            File(wonderSwanDirectory, "binary-game.bin") to Platform.WONDERSWAN
        )

        files.forEach { (file, platform) ->
            file.writeBytes(byteArrayOf(1))
            assertTrue(RomScanner.isScannableFile(file))
            assertEquals(platform, detectConsolePlatform(file))
        }

        assertEquals(
            Platform.PS1,
            detectConsolePlatform(File(temporaryFolder.newFolder("PlayStation"), "binary-game.bin"))
        )
    }

    @Test
    fun zippedGamesUseRomContentsInsteadOfPokemonFilenameGuessing() {
        fun createRomZip(file: File, entryName: String) {
            java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }

        val gameBoyZip = File(temporaryFolder.root, "Pokemon Blue Version.zip")
        val n64Zip = File(temporaryFolder.root, "Pokemon Stadium 2.zip")
        createRomZip(gameBoyZip, "Pokemon Blue Version.gb")
        createRomZip(n64Zip, "Pokemon Stadium 2.z64")

        assertEquals(Platform.GB, detectConsolePlatform(gameBoyZip))
        assertEquals(Platform.N64, detectConsolePlatform(n64Zip))
    }

    @Test
    fun closestArtworkMatchingIgnoresGenericReleaseMetadata() {
        val artwork = listOf(
            "Pokemon - Red Version (USA, Europe) (SGB Enhanced)",
            "Pokemon - Blue Version (USA, Europe) (SGB Enhanced)",
            "Pokemon Stadium (USA)",
            "Pokemon Stadium 2 (USA)"
        )

        assertEquals(
            "Pokemon - Blue Version (USA, Europe) (SGB Enhanced)",
            ScraperService.closestArtworkName("Pokemon Blue Version.zip", artwork)
        )
        assertEquals(
            "Pokemon Stadium 2 (USA)",
            ScraperService.closestArtworkName("Pokemon Stadium 2.zip", artwork)
        )
        assertNull(
            ScraperService.closestArtworkName("Unrelated Adventure.zip", artwork)
        )
    }

    @Test
    fun closestArtworkMatchAllowsAUniqueFullTitleWithCatalogSuffix() {
        assertEquals(
            "beatmania for WonderSwan (Japan)",
            ScraperService.closestArtworkName(
                "beatmania.ws",
                listOf("beatmania for WonderSwan (Japan)")
            )
        )
        assertNull(
            ScraperService.closestArtworkName(
                "beatmania",
                listOf("Beatmania Fever (Japan)", "beatmania for WonderSwan (Japan)")
            )
        )
    }

    @Test
    fun genericControllerButtonsMapAcrossAllLibretroPadIds() {
        assertEquals(
            ArcInputMap.BTN_B,
            ArcInputMap.fromKeyCode(KeyEvent.KEYCODE_BUTTON_1)
        )
        assertEquals(
            ArcInputMap.BTN_R3,
            ArcInputMap.fromKeyCode(KeyEvent.KEYCODE_BUTTON_16)
        )
        assertEquals(
            MainActivity.BTN_L2,
            ArcInputMap.toLibretroButtonId(ArcInputMap.BTN_L2)
        )
    }

    @Test
    fun c64DiskSetCreatesOrderedPlaylistFromAnyDisk() {
        val gameDirectory = temporaryFolder.newFolder("Last Ninja")
        val cacheDirectory = temporaryFolder.newFolder("cache")
        val disks = listOf(
            File(gameDirectory, "LNinja (Disk 1 of 4).d64"),
            File(gameDirectory, "LNinja (Disk 2 of 4).d64"),
            File(gameDirectory, "LNinja (Disk 3 of 4).d64"),
            File(gameDirectory, "LNinja (Disk 4 of 4).d64")
        )
        disks.forEach { it.writeBytes(byteArrayOf(1)) }

        val playlist = C64DiskPlaylist.create(cacheDirectory, disks[2])

        assertNotNull(playlist)
        assertEquals(disks.map { it.absolutePath }, playlist!!.readLines())
    }

    @Test
    fun c64SingleDiskAndUnrelatedGamesDoNotCreatePlaylist() {
        val gameDirectory = temporaryFolder.newFolder("C64")
        val cacheDirectory = temporaryFolder.newFolder("cache")
        val selectedDisk = File(gameDirectory, "Bubble Bobble.d64").apply { writeBytes(byteArrayOf(1)) }
        File(gameDirectory, "Another Game (Disk 2 of 2).d64").writeBytes(byteArrayOf(1))

        assertNull(C64DiskPlaylist.create(cacheDirectory, selectedDisk))
    }

    @Test
    fun scannerAllowsAmigaZipUnderArcRomsDirectory() {
        val arcDirectory = temporaryFolder.newFolder("Arc")
        val amigaDirectory = File(File(arcDirectory, "Roms"), "Amiga").apply { mkdirs() }
        val zipFile = File(amigaDirectory, "Example WHDLoad Game.zip")
        zipFile.writeBytes(byteArrayOf(1, 2, 3))

        assertFalse(RomScanner.shouldSkipDirectory(File(arcDirectory, "Roms")))
        assertTrue(RomScanner.isScannableFile(zipFile))
        assertEquals(Platform.AMIGA, detectConsolePlatform(zipFile))
    }

    @Test
    fun scannerRecognizesExtractedWHDLoadFolderButNotSlaveAsRom() {
        val amigaDirectory = temporaryFolder.newFolder("Amiga")
        val gameDirectory = File(amigaDirectory, "AfterBurner88").apply { mkdirs() }
        File(gameDirectory, "AfterBurner88.slave").writeBytes(byteArrayOf(1))
        File(gameDirectory, "disk.1").writeBytes(byteArrayOf(1))
        File(gameDirectory, "AfterBurner88.info").writeText("")

        assertTrue(RomScanner.isWHDLoadInstallDirectory(gameDirectory))
        assertEquals(listOf(gameDirectory), RomScanner.findWHDLoadInstallDirectories(amigaDirectory))
        assertFalse(RomScanner.isScannableFile(File(gameDirectory, "AfterBurner88.slave")))
    }

    @Test
    fun amigaArtworkUsesLibretroAmigaDatabase() {
        val game = GameFile(
            name = "Mortal Kombat v0.9",
            path = "/roms/amiga/Mortal Kombat v0.9.adf",
            platform = Platform.AMIGA
        )

        assertEquals("Commodore - Amiga", ScraperService.getLibretroPlatform(Platform.AMIGA))
        assertTrue(
            ScraperService.generateLibretroUrl(game).orEmpty()
                .contains("Commodore%20-%20Amiga/Named_Boxarts/Mortal%20Kombat%20v0.9.png")
        )
    }

    @Test
    fun turboGrafxAndWonderSwanUseTheirLibretroArtworkCatalogs() {
        val turboGrafx = GameFile(
            name = "Example Game",
            path = "/roms/TurboGrafx/Example Game.pce",
            platform = Platform.TURBOGRAFX
        )
        val wonderSwan = GameFile(
            name = "Example Game",
            path = "/roms/WonderSwan/Example Game.bin",
            platform = Platform.WONDERSWAN
        )
        val wonderSwanColor = GameFile(
            name = "Example Game",
            path = "/roms/WonderSwan Color/Example Game.bin",
            platform = Platform.WONDERSWAN
        )

        assertEquals(
            "https://thumbnails.libretro.com/NEC%20-%20PC%20Engine%20-%20TurboGrafx%2016/Named_Boxarts/Example%20Game.png",
            ScraperService.generateLibretroUrl(turboGrafx)
        )
        assertEquals(
            "https://thumbnails.libretro.com/Bandai%20-%20WonderSwan/Named_Boxarts/Example%20Game.png",
            ScraperService.generateLibretroUrl(wonderSwan)
        )
        assertEquals(
            "https://thumbnails.libretro.com/Bandai%20-%20WonderSwan%20Color/Named_Boxarts/Example%20Game.png",
            ScraperService.generateLibretroUrl(wonderSwanColor)
        )
    }

    @Test
    fun atariPlatformsUseTheirLibretroArtworkCatalogs() {
        val games = listOf(
            GameFile(name = "Game", path = "/roms/Atari 2600/Game.a26", platform = Platform.ATARI) to "Atari%20-%202600",
            GameFile(name = "Game", path = "/roms/Atari 7800/Game.a78", platform = Platform.ATARI_7800) to "Atari%20-%207800",
            GameFile(name = "Game", path = "/roms/Atari Lynx/Game.lnx", platform = Platform.LYNX) to "Atari%20-%20Lynx"
        )

        games.forEach { (game, catalog) ->
            assertEquals(
                "https://thumbnails.libretro.com/$catalog/Named_Boxarts/Game.png",
                ScraperService.generateLibretroUrl(game)
            )
        }
    }

    @Test
    fun amigaArtworkCandidatesNormalizeTosecFilenames() {
        val game = GameFile(
            name = "AfterBurner_v1.0_88_0095",
            path = "/roms/amiga/AfterBurner_v1.0_88_0095.adf",
            platform = Platform.AMIGA
        )

        val candidates = ScraperService.amigaNameVariants(game.name)
        assertTrue(candidates.contains("Afterburner"))
        assertTrue(candidates.contains("After Burner"))
        assertTrue(candidates.contains("Afterburner (Europe)"))
        assertTrue(candidates.contains("AfterBurner (Europe)"))
        assertFalse(candidates.any { it.contains("88") })
        assertEquals(
            "https://thumbnails.libretro.com/Commodore%20-%20Amiga/Named_Boxarts/AfterBurner%20(Europe).png",
            ScraperService.generateLibretroUrl(game, "AfterBurner (Europe)")
        )
        assertTrue(ScraperService.amigaNameVariants("AfterBurner_v1.0_88_0095.lha").contains("AfterBurner (Europe)"))
    }

    @Test
    fun artworkUrlEncodesPercentAndStripsLhaExtension() {
        val game = GameFile(
            name = "AfterBurner_v1.0_88_0095.lha",
            path = "/roms/amiga/AfterBurner_v1.0_88_0095.lha",
            platform = Platform.AMIGA
        )

        assertEquals(
            "https://thumbnails.libretro.com/Commodore%20-%20Amiga/Named_Boxarts/AfterBurner%20(Europe).png",
            ScraperService.generateLibretroUrl(game, "AfterBurner (Europe)")
        )
        assertTrue(
            ScraperService.generateLibretroUrl(game, "88%20")!!.endsWith("/88%2520.png")
        )
    }

    @Test
    fun amigaMultiDiskSetCreatesPlaylistFromAnyDisk() {
        val gameDirectory = temporaryFolder.newFolder("Worms Directors Cut")
        val cacheDirectory = temporaryFolder.newFolder("amiga-cache")
        val disks = listOf(
            File(gameDirectory, "Worms Directors Cut (Disk 1 of 2).adf"),
            File(gameDirectory, "Worms Directors Cut (Disk 2 of 2).adf")
        )
        disks.forEach { it.writeBytes(byteArrayOf(1)) }

        val playlist = AmigaDiskPlaylist.create(cacheDirectory, disks[1])

        assertNotNull(playlist)
        assertEquals(disks.map { it.absolutePath }, playlist!!.readLines())
    }

    @Test
    fun amigaGamesWithDifferentReleaseVersionsDoNotBecomeDisks() {
        val gameDirectory = temporaryFolder.newFolder("Amiga")
        val cacheDirectory = temporaryFolder.newFolder("amiga-cache")
        val selected = File(gameDirectory, "Worms Directors Cut v1.adf").apply { writeBytes(byteArrayOf(1)) }
        File(gameDirectory, "Worms Directors Cut v2.adf").writeBytes(byteArrayOf(1))

        assertNull(AmigaDiskPlaylist.create(cacheDirectory, selected))
    }
}