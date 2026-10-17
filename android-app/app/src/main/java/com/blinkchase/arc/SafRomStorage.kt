package com.blinkchase.arc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest

object SafRomStorage {
    private const val TAG = "SafStorage"
    private val diskNumber = Regex("(?i)(disc|disk|track|part|data|audio|side)\\s*([2-9]|0[2-9]|\\d{2,})")

    fun persistTreeReadPermission(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    fun releaseTreeReadPermission(context: Context, treeUri: Uri) {
        context.contentResolver.releasePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    fun scanTree(context: Context, treeUri: Uri): List<GameFile> {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = ArrayDeque<Pair<String, String>>()
        val visitedDirectories = mutableSetOf<String>()
        val filesByParent = linkedMapOf<String, MutableList<SafDocument>>()
        queue.add(rootId to "")

        while (queue.isNotEmpty()) {
            val (parentId, relativeParent) = queue.removeFirst()
            if (!visitedDirectories.add(parentId)) continue

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idColumn) ?: continue
                    val name = cursor.getString(nameColumn)?.takeIf(String::isNotBlank) ?: continue
                    val mimeType = cursor.getString(mimeColumn)
                    val relativePath = if (relativeParent.isEmpty()) name else "$relativeParent/$name"

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!RomScanner.shouldSkipDirectory(File(name))) {
                            queue.add(documentId to relativePath)
                        }
                        continue
                    }

                    if (!RomScanner.isScannableName(name)) continue
                    val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else -1L
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    filesByParent.getOrPut(parentId) { mutableListOf() }
                        .add(SafDocument(name, relativePath, documentUri, size))
                }
            } ?: throw IOException("The selected storage provider could not list folder contents.")
        }

        return filesByParent.values.flatMap { siblings ->
            val primaryNames = RomScanner.selectPrimaryFileNames(siblings.map { it.name })
            siblings.filter { it.name in primaryNames }
        }
            .filterNot { diskNumber.containsMatchIn(it.name) }
            .mapNotNull { document ->
                val archiveFile = if (document.name.substringAfterLast('.', "").equals("zip", true)) {
                    try {
                        stageUri(context, document.uri)
                    } catch (e: IOException) {
                        Log.w(TAG, "Unable to inspect ZIP from SAF: ${document.name}", e)
                        null
                    }
                } else {
                    null
                }
                val platform = detectConsolePlatform(
                    document.name,
                    document.relativePath,
                    document.size,
                    archiveFile
                )
                if (platform == Platform.UNKNOWN) {
                    null
                } else {
                    GameFile(
                        name = document.name.substringBeforeLast('.'),
                        path = document.uri.toString(),
                        platform = platform
                    )
                }
            }
    }

    @Throws(IOException::class)
    fun stageUri(context: Context, uri: Uri): File {
        val sourceMetadata = queryMetadata(context, uri)
        val canReuseCacheEntry = sourceMetadata.size != null && sourceMetadata.lastModified != null
        val cacheKeyInput = "${uri}|${sourceMetadata.size}|${sourceMetadata.lastModified}"
        val cacheKey = sha256(cacheKeyInput)
        val extension = Utils.getFileName(context, uri)
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
            .orEmpty()
        val stagingDirectory = File(context.cacheDir, "saf-roms")
        if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
            throw IOException("Unable to create the ROM staging directory.")
        }

        val suffix = if (extension.isEmpty()) "" else ".$extension"
        val target = File(stagingDirectory, "$cacheKey$suffix")
        if (canReuseCacheEntry && target.isFile && target.length() == sourceMetadata.size) {
            return target
        }
        if (sourceMetadata.size != null && sourceMetadata.size > 0L &&
            StatFs(stagingDirectory.absolutePath).availableBytes < sourceMetadata.size
        ) {
            throw IOException("Not enough free storage to stage this game from the selected location.")
        }

        val temporary = File(stagingDirectory, "$cacheKey.part")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("The selected storage provider could not open this game.")
            input.use { source ->
                temporary.outputStream().buffered().use { destination -> source.copyTo(destination) }
            }
            if (sourceMetadata.size != null && sourceMetadata.size >= 0L && temporary.length() != sourceMetadata.size) {
                throw IOException("The game changed or was truncated while being copied from external storage.")
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Unable to replace the previous staged copy.")
            }
            if (!temporary.renameTo(target)) {
                throw IOException("Unable to finalize the staged game file.")
            }
            return target
        } catch (e: Exception) {
            temporary.delete()
            if (e is IOException) throw e
            throw IOException("Unable to copy the game from the selected storage location.", e)
        }
    }

    private fun queryMetadata(context: Context, uri: Uri): SourceMetadata {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return SourceMetadata(null, null)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                SourceMetadata(
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                    if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else null
                )
            } ?: SourceMetadata(null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read source metadata; staging a fresh copy", e)
            SourceMetadata(null, null)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class SafDocument(
        val name: String,
        val relativePath: String,
        val uri: Uri,
        val size: Long
    )

    private data class SourceMetadata(val size: Long?, val lastModified: Long?)
}
