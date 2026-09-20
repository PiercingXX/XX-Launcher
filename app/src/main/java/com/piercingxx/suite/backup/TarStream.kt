// Canonical copy: xx-apps/app/src/main/java/com/piercingxx/suite/backup/.
// Suite apps vendor this file unchanged; the release guard checks the hash.
package com.piercingxx.suite.backup

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal POSIX ustar writer and reader. No external dependency, no
 * symlinks, no long-name extensions: entry names longer than 255 bytes
 * (100 name + 155 prefix) are refused, which is far beyond anything a
 * snapshot needs. Everything is stored as a regular file or directory.
 */
class TarWriter(private val out: OutputStream) {

    fun addDirectory(name: String) {
        val clean = name.trimEnd('/') + "/"
        writeHeader(clean, 0, '5')
    }

    fun addFile(name: String, size: Long, body: InputStream) {
        writeHeader(name, size, '0')
        var remaining = size
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = body.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("short read for $name")
            out.write(buf, 0, n)
            remaining -= n
        }
        pad(size)
    }

    fun addFile(name: String, bytes: ByteArray) = addFile(name, bytes.size.toLong(), bytes.inputStream())

    /** Two zero blocks close the archive. */
    fun finish() {
        out.write(ByteArray(BLOCK * 2))
        out.flush()
    }

    private fun pad(size: Long) {
        val rem = (size % BLOCK).toInt()
        if (rem != 0) out.write(ByteArray(BLOCK - rem))
    }

    private fun writeHeader(name: String, size: Long, type: Char) {
        val (prefix, base) = splitName(name)
        val h = ByteArray(BLOCK)
        putString(h, 0, 100, base)
        putOctal(h, 100, 8, 0b110_100_100L) // 0644
        putOctal(h, 108, 8, 0)
        putOctal(h, 116, 8, 0)
        putOctal(h, 124, 12, size)
        putOctal(h, 136, 12, System.currentTimeMillis() / 1000)
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        h[156] = type.code.toByte()
        putString(h, 257, 6, "ustar")
        h[263] = '0'.code.toByte()
        h[264] = '0'.code.toByte()
        putString(h, 345, 155, prefix)
        var sum = 0L
        for (b in h) sum += (b.toInt() and 0xFF)
        putOctal(h, 148, 7, sum)
        h[155] = ' '.code.toByte()
        out.write(h)
    }

    private fun splitName(name: String): Pair<String, String> {
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 100) return "" to name
        // Split at a slash so the prefix/name join is unambiguous.
        var cut = name.length - 1
        while (cut > 0) {
            if (name[cut] == '/' && name.substring(cut + 1).toByteArray().size <= 100 &&
                name.substring(0, cut).toByteArray().size <= 155
            ) {
                return name.substring(0, cut) to name.substring(cut + 1)
            }
            cut--
        }
        throw IllegalArgumentException("tar entry name too long: $name")
    }

    private fun putString(h: ByteArray, off: Int, len: Int, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size <= len) { "field overflow: $s" }
        System.arraycopy(b, 0, h, off, b.size)
    }

    private fun putOctal(h: ByteArray, off: Int, len: Int, value: Long) {
        val s = java.lang.Long.toOctalString(value).padStart(len - 1, '0')
        require(s.length <= len - 1) { "octal overflow: $value" }
        putString(h, off, len - 1, s)
        h[off + len - 1] = 0
    }

    companion object {
        const val BLOCK = 512
    }
}

class TarEntry(val name: String, val size: Long, val isDirectory: Boolean)

class TarReader(private val input: InputStream) {

    private var remaining = 0L

    /** Next header, or null at the end-of-archive marker. */
    fun next(): TarEntry? {
        skipRemaining()
        val h = ByteArray(TarWriter.BLOCK)
        if (!readFully(h)) return null
        if (h.all { it.toInt() == 0 }) return null
        val name = str(h, 0, 100)
        val prefix = str(h, 345, 155)
        val size = octal(h, 124, 12)
        val type = h[156].toInt().toChar()
        val full = if (prefix.isEmpty()) name else "$prefix/$name"
        remaining = size
        padding = ((TarWriter.BLOCK - (size % TarWriter.BLOCK)) % TarWriter.BLOCK).toInt()
        return TarEntry(full, size, type == '5' || full.endsWith("/"))
    }

    private var padding = 0

    /** Stream of the current entry's bytes; reading past the entry is refused. */
    fun body(): InputStream = object : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = input.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }

    fun readBytes(): ByteArray = body().readBytes()

    private fun skipRemaining() {
        var toSkip = remaining + padding
        val buf = ByteArray(8192)
        while (toSkip > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), toSkip).toInt())
            if (n < 0) break
            toSkip -= n
        }
        remaining = 0
        padding = 0
    }

    private fun readFully(b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) return off == 0
            off += n
        }
        return true
    }

    private fun str(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end].toInt() != 0) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    private fun octal(h: ByteArray, off: Int, len: Int): Long {
        val s = str(h, off, len).trim().trimEnd(' ')
        return if (s.isEmpty()) 0 else s.toLong(8)
    }
}
