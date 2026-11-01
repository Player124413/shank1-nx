package com.shank1.port

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The game's data on the device: `filesDir/game_root` (the loose files while they are being imported) and
 * `filesDir/game_root.nxpack` + `.nxidx`, the asset pack the renderer mounts instead of opening thousands of files
 * (the same format as port/tools/build_asset_pack.py and the SDK's PackDevice: version 2, FNV-1a 64 checksums).
 *
 * The game data is the player's own copy: the launcher copies it out of a folder the player picked with the system's
 * folder picker (the storage access framework), never from the APK, and nothing here uploads or shares it.
 */
object GameData {

    class Summary(val bytes: Long, val files: Int, val packed: Boolean)

    /** Thrown when the picked folder is not an extracted copy of the game. */
    class NotAGameFolder(message: String) : IOException(message)

    fun looseRoot(context: Context): File = File(context.filesDir, "game_root")
    fun packFile(context: Context): File = File(context.filesDir, "game_root.nxpack")
    fun indexFile(context: Context): File = File(context.filesDir, "game_root.nxidx")

    /** True when the engine will find the game: the loose folder with default.xex, or a complete pack. */
    fun isInstalled(context: Context): Boolean {
        val loose = looseRoot(context)
        if (File(loose, "default.xex").isFile) return true
        return packFile(context).isFile && indexFile(context).isFile
    }

    fun summary(context: Context): Summary? {
        val loose = looseRoot(context)
        if (File(loose, "default.xex").isFile) {
            var bytes = 0L
            var files = 0
            loose.walkTopDown().forEach { if (it.isFile) { bytes += it.length(); files++ } }
            return Summary(bytes, files, packed = false)
        }
        val pack = packFile(context)
        if (pack.isFile && indexFile(context).isFile) {
            return Summary(pack.length(), countFilesOfIndex(context) , packed = true)
        }
        return null
    }

    private fun countFilesOfIndex(context: Context): Int = try {
        val header = indexFile(context).inputStream().use { input ->
            val bytes = ByteArray(56)
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n <= 0) break
                read += n
            }
            bytes
        }
        java.nio.ByteBuffer.wrap(header, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
    } catch (e: Exception) {
        0
    }

    fun deleteAll(context: Context) {
        looseRoot(context).deleteRecursively()
        packFile(context).delete()
        indexFile(context).delete()
    }

    /** One file of the picked folder: where it is and where it goes. */
    class Source(val relativePath: String, val uri: Uri, val size: Long)

    /**
     * Walks a folders the player picked and returns the game's files, with paths relative to the game's root. Accepts
     * the folder that holds `default.xex` itself, or one that holds it a level down (a package folder picked by
     * mistake).
     */
    fun scan(context: Context, treeUri: Uri): Pair<String, List<Source>> {
        val resolver = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val files = ArrayList<Source>()
        var gamePrefix: String? = null

        fun walk(documentId: String, prefix: String, depth: Int) {
            if (depth > 12) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val childName = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    val childPrefix = if (prefix.isEmpty()) childName else "$prefix/$childName"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(id, childPrefix, depth + 1)
                    } else {
                        if (childName.equals("default.xex", ignoreCase = true) && gamePrefix == null) {
                            gamePrefix = prefix
                        }
                        files.add(
                            Source(childPrefix, DocumentsContract.buildDocumentUriUsingTree(treeUri, id), size),
                        )
                    }
                }
            }
        }

        walk(rootDocId, "", 0)
        val prefix = gamePrefix
            ?: throw NotAGameFolder("default.xex is not in that folder: pick the extracted copy of the game")
        val kept = files.filter { it.relativePath.startsWith(prefix) }
            .map { Source(if (prefix.isEmpty()) it.relativePath else it.relativePath.removePrefix("$prefix/"), it.uri, it.size) }
        if (kept.none { it.relativePath.equals("default.xex", ignoreCase = true) }) {
            throw NotAGameFolder("the folder has no default.xex")
        }
        return prefix to kept
    }

    /** Copies the files in, reporting bytes copied; [cancelled] is checked between files. */
    fun copyIn(
        context: Context,
        files: List<Source>,
        progress: (bytes: Long, file: String) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val root = looseRoot(context)
        var copied = 0L
        for (source in files.sortedBy { it.relativePath }) {
            if (cancelled()) throw IOException("cancelled")
            val target = File(root, source.relativePath)
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(source.uri).use { input: InputStream? ->
                if (input == null) { copied += source.size; return@use }
                FileOutputStream(target).use { output -> input.copyTo(output, 1 shl 16) }
            }
            copied += if (target.length() > 0) target.length() else source.size
            progress(copied, source.relativePath)
        }
    }

    /** Packs the loose copy and deletes it, so the device holds the game once. */
    fun packInPlace(context: Context, progress: (percent: Int) -> Unit, cancelled: () -> Boolean) {
        val root = looseRoot(context)
        val pack = packFile(context)
        val index = indexFile(context)
        PackWriter.build(root, pack, index, progress, cancelled)
        if (pack.isFile && index.isFile && pack.length() > 0) {
            root.deleteRecursively()
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format("%.0f MB", bytes / (1L shl 20).toDouble())
        else -> String.format("%.0f KB", bytes / 1024.0)
    }
}
