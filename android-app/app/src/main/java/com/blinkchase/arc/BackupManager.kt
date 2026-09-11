package com.blinkchase.arc

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BackupManager {

    fun createBackup(context: Context, storageDir: File): File? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val backupFile = File(storageDir, "ARC_Backup_$timeStamp.zip")
        
        try {
            ZipOutputStream(FileOutputStream(backupFile)).use { zos ->
                // 1. Backup internal cores
                val internalCoresDir = File(context.filesDir, "cores")
                if (internalCoresDir.exists()) {
                    zipFolder(internalCoresDir, "cores/", zos)
                }
                
                // 2. Backup Saves
                val savesDir = File(storageDir, "saves")
                if (savesDir.exists()) {
                    zipFolder(savesDir, "saves/", zos)
                }
                
                // 3. Backup Layouts
                val layoutsDir = File(storageDir, "layouts")
                if (layoutsDir.exists()) {
                    zipFolder(layoutsDir, "layouts/", zos)
                }
                
                // 4. Backup BIOS (system)
                val systemDir = File(storageDir, "system")
                if (systemDir.exists()) {
                    zipFolder(systemDir, "system/", zos)
                }
            }
            return backupFile
        } catch (e: Exception) {
            Log.e("ArcBackup", "Backup failed: ${e.message}")
            return null
        }
    }

    private fun zipFolder(folder: File, parentPath: String, zos: ZipOutputStream) {
        folder.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                zipFolder(file, "$parentPath${file.name}/", zos)
            } else {
                val entry = ZipEntry("$parentPath${file.name}")
                zos.putNextEntry(entry)
                FileInputStream(file).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
