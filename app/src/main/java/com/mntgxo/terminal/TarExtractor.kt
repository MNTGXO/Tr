package com.mntgxo.terminal

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Filesystem operations that plain java.io cannot do; implemented with android.system.Os on Android. */
interface Fs {
    fun symlink(target: String, linkPath: String)
    fun hardlink(existingPath: String, newPath: String)
    fun chmod(path: String, mode: Int)
    fun isSymlink(path: String): Boolean
}

/**
 * Minimal, dependency-free tar reader (ustar, GNU long names and PAX headers) that safely
 * unpacks a root filesystem tarball into [root]. Entries that would escape [root] are rejected.
 */
class TarExtractor(private val root: File, private val fs: Fs) {

    private val verifiedDirs = HashSet<String>()
    private val rootPath: String = root.absolutePath

    fun extract(input: InputStream, onEntry: (Int) -> Unit = {}): Int {
        root.mkdirs()
        verifiedDirs.add(rootPath)
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize: Long = -1
        var count = 0

        while (true) {
            if (!readFully(input, header)) break
            if (isZeroBlock(header)) break

            val type = header[156].toInt().toChar()
            var size = parseNumber(header, 124, 12)
            if (paxSize >= 0 && (type == '0' || type == '\u0000' || type == '7')) size = paxSize

            when (type) {
                'x' -> {
                    val data = readBytes(input, size)
                    skipPadding(input, size)
                    val pax = parsePax(data)
                    paxPath = pax["path"] ?: paxPath
                    paxLink = pax["linkpath"] ?: paxLink
                    pax["size"]?.toLongOrNull()?.let { paxSize = it }
                    continue
                }
                'g' -> {
                    skipFully(input, size + padding(size))
                    continue
                }
                'L' -> {
                    longName = cString(readBytes(input, size))
                    skipPadding(input, size)
                    continue
                }
                'K' -> {
                    longLink = cString(readBytes(input, size))
                    skipPadding(input, size)
                    continue
                }
            }

            var name = paxPath ?: longName ?: buildName(header)
            val linkName = paxLink ?: longLink ?: cString(header, 157, 100)
            val mode = parseNumber(header, 100, 8).toInt() and 0x1FF
            longName = null; longLink = null; paxPath = null; paxLink = null; paxSize = -1

            name = name.trimEnd('/')
            val rel = sanitize(name)
            if (rel == null) {
                skipFully(input, size + padding(size))
                continue
            }
            val target = File(root, rel)

            when (type) {
                '0', '\u0000', '7' -> {
                    prepareParent(target)
                    removeExisting(target)
                    FileOutputStream(target).use { out -> copyExactly(input, out, size) }
                    skipFully(input, padding(size))
                    fs.chmod(target.absolutePath, (mode or 0x180) and 0x1FF)
                }
                '5' -> {
                    prepareParent(target)
                    if (!target.isDirectory) {
                        removeExisting(target)
                        if (!target.mkdirs() && !target.isDirectory) throw IOException("mkdir failed: $rel")
                    }
                    fs.chmod(target.absolutePath, mode or 0x1C0)
                    verifiedDirs.add(target.absolutePath)
                    skipFully(input, size + padding(size))
                }
                '2' -> {
                    prepareParent(target)
                    removeExisting(target)
                    fs.symlink(linkName, target.absolutePath)
                    skipFully(input, size + padding(size))
                }
                '1' -> {
                    prepareParent(target)
                    removeExisting(target)
                    val srcRel = sanitize(linkName.trimEnd('/'))
                        ?: throw IOException("bad hardlink target: $linkName")
                    val src = File(root, srcRel)
                    try {
                        fs.hardlink(src.absolutePath, target.absolutePath)
                    } catch (e: Exception) {
                        src.inputStream().use { ins ->
                            FileOutputStream(target).use { out -> ins.copyTo(out) }
                        }
                        fs.chmod(target.absolutePath, (mode or 0x180) and 0x1FF)
                    }
                    skipFully(input, size + padding(size))
                }
                else -> {
                    // Device nodes, FIFOs and unknown types cannot be created by an app; skip them.
                    skipFully(input, size + padding(size))
                }
            }
            count++
            if (count % 50 == 0) onEntry(count)
        }
        onEntry(count)
        return count
    }

    private fun sanitize(name: String): String? {
        val parts = ArrayList<String>()
        for (p in name.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> return null
                else -> parts.add(p)
            }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("/")
    }

    /** Creates parent directories and refuses to write through symlinked ancestors. */
    private fun prepareParent(target: File) {
        val parent = target.parentFile ?: return
        if (verifiedDirs.contains(parent.absolutePath)) return
        val chain = ArrayList<File>()
        var cur: File? = parent
        while (cur != null && cur.absolutePath != rootPath) {
            chain.add(cur)
            cur = cur.parentFile
        }
        if (cur == null) throw IOException("path escapes root: $target")
        for (dir in chain.asReversed()) {
            val p = dir.absolutePath
            if (verifiedDirs.contains(p)) continue
            if (fs.isSymlink(p)) throw IOException("refusing to write through symlink: $p")
            if (!dir.exists()) {
                if (!dir.mkdir()) throw IOException("mkdir failed: $p")
                fs.chmod(p, 0x1ED)
            } else if (!dir.isDirectory) {
                throw IOException("not a directory: $p")
            }
            verifiedDirs.add(p)
        }
    }

    private fun removeExisting(target: File) {
        if (fs.isSymlink(target.absolutePath) || target.isFile) {
            target.delete()
        } else if (target.isDirectory) {
            throw IOException("cannot replace directory with file: ${target.name}")
        }
    }

    private fun buildName(h: ByteArray): String {
        val name = cString(h, 0, 100)
        val isUstar = h[257] == 'u'.code.toByte() && h[258] == 's'.code.toByte() &&
            h[259] == 't'.code.toByte() && h[260] == 'a'.code.toByte() && h[261] == 'r'.code.toByte()
        if (isUstar) {
            val prefix = cString(h, 345, 155)
            if (prefix.isNotEmpty()) return "$prefix/$name"
        }
        return name
    }

    private fun parsePax(data: ByteArray): Map<String, String> {
        val result = HashMap<String, String>()
        var pos = 0
        while (pos < data.size) {
            var sp = pos
            while (sp < data.size && data[sp] != ' '.code.toByte()) sp++
            if (sp >= data.size) break
            val len = String(data, pos, sp - pos, Charsets.US_ASCII).toIntOrNull() ?: break
            if (len <= 0 || pos + len > data.size) break
            val recStart = sp + 1
            val recEnd = pos + len - 1 // drop trailing newline
            if (recEnd > recStart) {
                val rec = String(data, recStart, recEnd - recStart, Charsets.UTF_8)
                val eq = rec.indexOf('=')
                if (eq > 0) result[rec.substring(0, eq)] = rec.substring(eq + 1)
            }
            pos += len
        }
        return result
    }

    private fun parseNumber(b: ByteArray, off: Int, len: Int): Long {
        if ((b[off].toInt() and 0x80) != 0) {
            var v = (b[off].toInt() and 0x7F).toLong()
            for (i in 1 until len) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        var seen = false
        for (i in off until off + len) {
            val c = b[i].toInt()
            if (c in '0'.code..'7'.code) {
                v = (v shl 3) + (c - '0'.code)
                seen = true
            } else if (seen || c == 0) {
                break
            }
        }
        return v
    }

    private fun cString(b: ByteArray, off: Int = 0, len: Int = b.size): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun isZeroBlock(b: ByteArray): Boolean {
        for (x in b) if (x != 0.toByte()) return false
        return true
    }

    private fun padding(size: Long): Long = (BLOCK - (size % BLOCK)) % BLOCK

    private fun skipPadding(input: InputStream, size: Long) = skipFully(input, padding(size))

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("truncated tar header")
            }
            off += n
        }
        return true
    }

    private fun readBytes(input: InputStream, size: Long): ByteArray {
        if (size < 0 || size > MAX_META) throw IOException("bad metadata size: $size")
        val out = ByteArrayOutputStream(size.toInt())
        copyExactly(input, out, size)
        return out.toByteArray()
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("truncated tar entry")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("truncated tar")
            left -= n
        }
    }

    companion object {
        private const val BLOCK = 512
        private const val MAX_META = 1L shl 20
    }
}
