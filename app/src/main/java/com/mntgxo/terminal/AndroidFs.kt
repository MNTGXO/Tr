package com.mntgxo.terminal

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/** [Fs] backed by android.system.Os. */
object AndroidFs : Fs {
    override fun symlink(target: String, linkPath: String) {
        try {
            Os.symlink(target, linkPath)
        } catch (e: ErrnoException) {
            throw IOException("symlink $linkPath -> $target: ${e.message}", e)
        }
    }

    override fun hardlink(existingPath: String, newPath: String) {
        try {
            Os.link(existingPath, newPath)
        } catch (e: ErrnoException) {
            throw IOException("link $newPath: ${e.message}", e)
        }
    }

    override fun chmod(path: String, mode: Int) {
        try {
            Os.chmod(path, mode)
        } catch (e: ErrnoException) {
            // Best effort: some filesystems refuse chmod.
        }
    }

    override fun isSymlink(path: String): Boolean {
        return try {
            OsConstants.S_ISLNK(Os.lstat(path).st_mode)
        } catch (e: ErrnoException) {
            false
        }
    }
}

/** Deletes a directory tree without ever following symlinks. */
fun deleteTree(fs: Fs, file: File) {
    if (fs.isSymlink(file.absolutePath) || !file.isDirectory) {
        file.delete()
        return
    }
    fs.chmod(file.absolutePath, 0x1C0)
    file.listFiles()?.forEach { deleteTree(fs, it) }
    file.delete()
}
