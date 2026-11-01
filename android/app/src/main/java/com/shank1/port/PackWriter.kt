package com.shank1.port

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The asset pack: `<folder>.nxpack` + `<folder>.nxidx`, the format of the SDK's PackDevice (version 2), byte for byte
 * the same as port/tools/build_asset_pack.py writes on a PC.
 *
 * PackHeader (40 bytes): "SSNXPAK1", version 2, flags 0, pack id, pack size, data checksum. Every file follows,
 * 16-byte aligned, in the order of its UTF-8 '/'-separated relative path (strcmp). Then the index: IndexHeader
 * (56 bytes: "SSNXIDX1", version 2, count, pack id, pack size, paths size, index checksum, data checksum),
 * DiskEntry[count] (24 bytes: offset, size, path offset, path length) and the NUL-terminated paths.
 * Checksums are 64-bit FNV-1a, the pack id a hash of every path and size.
 *
 * Packing on the device means the phone reads the game once, after the import, instead of the engine opening tens of
 * thousands of small files every launch.
 */
object PackWriter {

    private const val FNV_BASIS = -3750763034362895579L // 14695981039346656037 as a signed long
    private const val FNV_PRIME = 1099511628211L
    private const val CHUNK = 1 shl 20

    private fun fnv(hash: Long, bytes: ByteArray, length: Int, seed: Long = hash): Long {
        var h = seed
        for (i in 0 until length) {
            h = h xor (bytes[i].toLong() and 0xFF)
            h *= FNV_PRIME
        }
        return h
    }

    private fun fnv(hash: Long, value: Int, bytes: Int): Long {
        var h = hash
        repeat(bytes) { index ->
            h = h xor ((value ushr (8 * index)).toLong() and 0xFF)
            h *= FNV_PRIME
        }
        return h
    }

    private fun fnv(hash: Long, value: Long): Long {
        var h = hash
        repeat(8) { index ->
            h = h xor ((value ushr (8 * index)) and 0xFF)
            h *= FNV_PRIME
        }
        return h
    }

    class Entry(val relativePath: String, val file: File) {
        val pathBytes: ByteArray = relativePath.toByteArray(Charsets.UTF_8)
        val size: Long = file.length()
        var offset: Long = 0
    }

    fun build(folder: File, packFile: File, indexFile: File, progress: (percent: Int) -> Unit, cancelled: () -> Boolean) {
        val entries = collect(folder)
        if (entries.isEmpty()) throw IOException("nothing to pack in ${folder.name}")

        // Pass 1: where each file goes, and the pack id over every path and size.
        var packSize = 40L
        var pathsSize = 0L
        var total = 0L
        var packId = FNV_BASIS
        for (entry in entries) {
            packSize = (packSize + 15) and 15L.inv()
            entry.offset = packSize
            packSize += entry.size
            pathsSize += entry.pathBytes.size + 1
            total += entry.size
            for (byte in entry.pathBytes) {
                packId = packId xor (byte.toLong() and 0xFF)
                packId *= FNV_PRIME
            }
            packId = fnv(packId, entry.size)
        }
        if (total == 0L) throw IOException("the files are all empty")

        // Pass 2: the file itself.
        var dataChecksum = FNV_BASIS
        val buffer = ByteArray(CHUNK)
        var done = 0L
        val temporary = File(packFile.parentFile, packFile.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(ByteArray(40))
            var position = 40L
            for (entry in entries) {
                if (cancelled()) { temporary.delete(); throw IOException("cancelled") }
                if (entry.offset > position) {
                    output.write(ByteArray((entry.offset - position).toInt()))
                    position = entry.offset
                }
                entry.file.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        dataChecksum = fnv(dataChecksum, buffer, read, dataChecksum)
                        position += read
                        done += read
                    }
                }
                progress((done * 100 / total).toInt())
            }
            // The header, now that the checksum over the data is known.
            val header = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            header.put("SSNXPAK1".toByteArray(Charsets.US_ASCII))
            header.putInt(2)
            header.putInt(0)
            header.putLong(packId)
            header.putLong(packSize)
            header.putLong(dataChecksum)
            RandomAccessFile(temporary, "rw").use { file ->
                file.seek(0)
                file.write(header.array())
            }
        }

        // The index.
        val entriesBuffer = ByteBuffer.allocate(entries.size * 24).order(ByteOrder.LITTLE_ENDIAN)
        val pathsBuffer = java.io.ByteArrayOutputStream()
        for (entry in entries) {
            entriesBuffer.putLong(entry.offset)
            entriesBuffer.putLong(entry.size)
            entriesBuffer.putInt(pathsBuffer.size())
            entriesBuffer.putInt(entry.pathBytes.size)
            pathsBuffer.write(entry.pathBytes)
            pathsBuffer.write(0)
        }
        val entriesBytes = entriesBuffer.array()
        val pathsBytes = pathsBuffer.toByteArray()
        var checksum = FNV_BASIS
        checksum = fnv(checksum, entriesBytes, entriesBytes.size)
        checksum = fnv(checksum, pathsBytes, pathsBytes.size)

        val indexTemporary = File(indexFile.parentFile, indexFile.name + ".tmp")
        FileOutputStream(indexTemporary).use { output ->
            val header = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
            header.put("SSNXIDX1".toByteArray(Charsets.US_ASCII))
            header.putInt(2)
            header.putInt(entries.size)
            header.putLong(packId)
            header.putLong(packSize)
            header.putLong(pathsSize)
            header.putLong(checksum)
            header.putLong(dataChecksum)
            output.write(header.array())
            output.write(entriesBytes)
            output.write(pathsBytes)
        }

        if (packFile.exists()) packFile.delete()
        if (indexFile.exists()) indexFile.delete()
        if (!temporary.renameTo(packFile)) throw IOException("could not write ${packFile.name}")
        if (!indexTemporary.renameTo(indexFile)) throw IOException("could not write ${indexFile.name}")
    }

    /** Every file under [folder] with a path the pack accepts, sorted by the bytes of its path. */
    private fun collect(folder: File): List<Entry> {
        val entries = ArrayList<Entry>()
        val root = folder.absoluteFile.toPath()
        folder.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val relative = root.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
            if (relative.isEmpty() || relative.startsWith("/") || relative.endsWith("/")) return@forEach
            if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." || it.contains(':') }) {
                throw IOException("path not allowed in the pack: $relative")
            }
            entries.add(Entry(relative, file))
        }
        // strcmp order: compare the UTF-8 bytes, as the pack does.
        entries.sortWith { a, b -> compareBytes(a.pathBytes, b.pathBytes) }
        return entries
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val count = minOf(a.size, b.size)
        for (i in 0 until count) {
            val x = a[i].toInt() and 0xFF
            val y = b[i].toInt() and 0xFF
            if (x != y) return x - y
        }
        return a.size - b.size
    }
}
