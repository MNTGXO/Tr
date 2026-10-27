package com.mntgxo.terminal

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Downloads, verifies (SHA-256) and unpacks an Alpine Linux root filesystem for use with proot. */
object LinuxInstaller {

    private const val BASE = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases"

    fun alpineArch(): String? {
        for (abi in Build.SUPPORTED_ABIS) {
            when (abi) {
                "arm64-v8a" -> return "aarch64"
                "armeabi-v7a" -> return "armv7"
                "x86_64" -> return "x86_64"
            }
        }
        return null
    }

    private val ROOTFS_NAME = Regex("^alpine-minirootfs-[0-9][0-9A-Za-z._]*-[a-z0-9_]+\\.tar\\.gz$")

    /**
     * Finds the mini root filesystem tarball in Alpine's latest-releases.yaml.
     * Returns the file name and, when the index lists it, the SHA-256 (otherwise null).
     */
    fun parseLatestReleases(yaml: String): Pair<String, String?>? {
        var file: String? = null
        var sha: String? = null

        fun result(): Pair<String, String?>? {
            val f = file
            return if (f != null && ROOTFS_NAME.matches(f)) Pair(f, sha) else null
        }

        for (raw in yaml.lines()) {
            if (raw.startsWith("-")) {
                result()?.let { return it }
                file = null
                sha = null
            }
            val t = raw.trim().removePrefix("-").trim()
            fun value(key: String): String? =
                if (t.startsWith("$key:")) t.substring(key.length + 1).trim().trim('"', '\'') else null
            value("file")?.let { file = it }
            value("sha256")?.let { sha = it }
        }
        return result()
    }

    fun install(ctx: Context, progress: (String) -> Unit) {
        val arch = alpineArch() ?: throw IOException("Unsupported CPU: ${Build.SUPPORTED_ABIS.joinToString()}")
        if (!Shells.prootBinary(ctx).exists()) {
            throw IOException("proot is not bundled for this CPU architecture")
        }

        progress("Looking up the latest Alpine release…")
        val dir = "$BASE/$arch"
        val yaml = httpText("$dir/latest-releases.yaml")
        val (fileName, listedSha) = parseLatestReleases(yaml)
            ?: throw IOException("Could not find the Alpine mini root filesystem for $arch")
        val expectedSha = listedSha
            ?: httpText("$dir/$fileName.sha256").trim().split(Regex("\\s+")).firstOrNull()
            ?: throw IOException("No checksum available for $fileName")
        if (!Regex("^[0-9a-fA-F]{64}$").matches(expectedSha)) {
            throw IOException("Unexpected checksum format for $fileName")
        }

        val tarball = File(ctx.cacheDir, "alpine-rootfs.tar.gz")
        tarball.delete()
        download("$dir/$fileName", tarball, expectedSha, progress)

        progress("Preparing the root filesystem…")
        val rootfs = Shells.rootfsDir(ctx)
        if (rootfs.exists()) deleteTree(AndroidFs, rootfs)
        rootfs.mkdirs()

        try {
            tarball.inputStream().buffered(64 * 1024).use { raw ->
                GZIPInputStream(raw, 64 * 1024).use { gz ->
                    TarExtractor(rootfs, AndroidFs).extract(gz) { n -> progress("Extracting… $n files") }
                }
            }
        } catch (e: Exception) {
            deleteTree(AndroidFs, rootfs)
            throw IOException("Extraction failed: ${e.message}", e)
        } finally {
            tarball.delete()
        }

        progress("Configuring…")
        configure(rootfs)
        File(rootfs, ".mn-installed").writeText("$fileName\n")
    }

    private fun configure(rootfs: File) {
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/resolv.conf").apply {
            delete()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        for (d in listOf("root", "tmp", "proc", "sys", "dev", "sdcard", "run", "var/tmp")) {
            File(rootfs, d).mkdirs()
        }
        AndroidFs.chmod(File(rootfs, "tmp").absolutePath, 0x3FF) // 1777
        AndroidFs.chmod(File(rootfs, "var/tmp").absolutePath, 0x3FF)
        AndroidFs.chmod(File(rootfs, "root").absolutePath, 0x1C0) // 0700
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "MNTerminal/2.0")
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            throw IOException("HTTP $code for $url")
        }
        return conn
    }

    private fun httpText(url: String): String {
        val conn = open(url)
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, dest: File, expectedSha: String, progress: (String) -> Unit) {
        val conn = open(url)
        try {
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            progress(if (pct >= 0) "Downloading… $pct%" else "Downloading… ${done / 1024} KB")
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                dest.delete()
                throw IOException("Checksum mismatch - download discarded")
            }
        } finally {
            conn.disconnect()
        }
    }
}
