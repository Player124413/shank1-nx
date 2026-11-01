package com.shank1.port

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The shaders this copy of the game compiled itself.
 *
 * The game keeps 54 shaders in its files and compiles 21 more at run time, from HLSL text: those exist only after it
 * has needed them, so no dump of the files has them. The renderer writes each one it had to miss
 * (`ps_<hash>.bin` / `vs_<hash>.bin`, into `filesDir/shader_capture` - see the Android branch of
 * CaptureMissingShader), and a build that puts them in its shader dump gets a complete cache. That is the loop for a
 * phone and no PC: play for a while, export this, give the archive to the next CI run
 * (docs/android.md, "No PC?").
 */
object ShaderCaptures {

    fun directory(context: Context): File = File(context.filesDir, "shader_capture")

    fun files(context: Context): List<File> =
        directory(context).listFiles { file -> file.isFile && file.name.endsWith(".bin") }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun count(context: Context): Int = files(context).size

    /** The captures as one archive, named as the dump the build's shader step reads. Returns how many were written. */
    fun writeZip(context: Context, output: OutputStream): Int {
        val captures = files(context)
        ZipOutputStream(output).use { zip ->
            for (file in captures) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { input -> input.copyTo(zip, 1 shl 16) }
                zip.closeEntry()
            }
        }
        return captures.size
    }
}
