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
    fun amigaDiscImagesAreNotClassifiedAsPs2() {
        val amigaIso = File(File(System.getProperty("java.io.tmpdir"), "Amiga Games"), "game.iso")
        val genericIso = File(File(System.getProperty("java.io.tmpdir"), "PlayStation"), "game.iso")

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
    }

    @Test
    fun scannerRecognizesAtariAndCommodore64Formats() {
        val atari = File(temporaryFolder.newFolder("Atari 2600"), "game.a26").apply { createNewFile() }
        val atariBinary = File(temporaryFolder.newFolder("Atari"), "game.bin")
        val c64 = File(temporaryFolder.newFolder("C64"), "game.tap").apply { createNewFile() }

        assertTrue(RomScanner.isScannableFile(atari))
        assertEquals(Platform.ATARI, detectConsolePlatform(atari))
        assertEquals(Platform.ATARI, detectConsolePlatform(atariBinary))
        assertTrue(RomScanner.isScannableFile(c64))
        assertEquals(Platform.COMMODORE_64, detectConsolePlatform(c64))
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
        assertEquals(
            "https://thumbnails.libretro.com/Commodore%20-%20Amiga/Named_Boxarts/Afterburner%20(Europe).png",
            ScraperService.generateLibretroUrl(game, "Afterburner (Europe)")
        )
    }
}