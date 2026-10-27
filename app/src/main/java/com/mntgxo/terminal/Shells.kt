package com.mntgxo.terminal

import android.content.Context
import android.os.Environment
import com.termux.terminal.TerminalSession
import java.io.File

/** Builds shell sessions: the native Android shell and the proot-based Alpine Linux environment. */
object Shells {

    const val ANDROID_SHELL = "/system/bin/sh"
    private const val TRANSCRIPT_ROWS = 5000

    val DEV_TOOLS = listOf(
        "bash", "bash-completion", "coreutils", "util-linux", "procps", "curl", "wget", "git",
        "openssh", "vim", "nano", "less", "tmux", "htop", "jq", "rsync", "file", "tar", "zip", "unzip",
        "python3", "py3-pip", "nodejs", "npm", "build-base", "make", "cmake", "ncurses", "ncurses-terminfo-base"
    )

    fun homeDir(ctx: Context) = File(ctx.filesDir, "home")
    fun binDir(ctx: Context) = File(ctx.filesDir, "usr/bin")
    fun rootfsDir(ctx: Context) = File(ctx.filesDir, "linux/alpine")
    fun prootBinary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libproot.so")
    fun isLinuxInstalled(ctx: Context) = File(rootfsDir(ctx), ".mn-installed").exists()

    private fun linuxScript(ctx: Context) = File(binDir(ctx), "linux")

    /** Creates directories and helper scripts. Safe to call repeatedly. */
    fun prepare(ctx: Context) {
        homeDir(ctx).mkdirs()
        binDir(ctx).mkdirs()
        File(ctx.filesDir, "tmp").mkdirs()

        val script = linuxScript(ctx)
        script.writeText(LINUX_SCRIPT)
        script.setExecutable(true, false)

        val rc = File(homeDir(ctx), ".mkshrc")
        if (!rc.exists()) rc.writeText(MKSHRC)

        val storage = File(homeDir(ctx), "storage")
        if (!storage.exists()) {
            try {
                AndroidFs.symlink(Environment.getExternalStorageDirectory().absolutePath, storage.absolutePath)
            } catch (e: Exception) {
                // Not critical.
            }
        }
    }

    fun environment(ctx: Context, compat: Boolean): Array<String> {
        val skip = setOf(
            "LD_PRELOAD", "LD_LIBRARY_PATH", "TERM", "HOME", "TMPDIR", "PATH", "PWD", "SHELL",
            "ENV", "LANG", "COLORTERM"
        )
        val env = LinkedHashMap<String, String>()
        for ((k, v) in System.getenv()) if (k !in skip) env[k] = v

        val home = homeDir(ctx).absolutePath
        val lib = ctx.applicationInfo.nativeLibraryDir
        env["HOME"] = home
        env["TMPDIR"] = File(ctx.filesDir, "tmp").absolutePath
        env["PATH"] = "${binDir(ctx).absolutePath}:/system/bin:/system/xbin"
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["LANG"] = "en_US.UTF-8"
        env["SHELL"] = ANDROID_SHELL
        env["ENV"] = File(home, ".mkshrc").absolutePath

        env["MNTGXO_PROOT"] = File(lib, "libproot.so").absolutePath
        env["PROOT_LOADER"] = File(lib, "libproot_loader.so").absolutePath
        val loader32 = File(lib, "libproot_loader32.so")
        if (loader32.exists()) env["PROOT_LOADER_32"] = loader32.absolutePath
        env["PROOT_TMP_DIR"] = File(ctx.filesDir, "tmp").absolutePath
        env["LINUX_ROOTFS"] = rootfsDir(ctx).absolutePath
        env["LINUX_SDCARD"] = Environment.getExternalStorageDirectory().absolutePath
        if (compat) env["PROOT_NO_SECCOMP"] = "1"

        return env.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    fun newAndroidSession(ctx: Context): TerminalSession {
        prepare(ctx)
        return TerminalSession(
            ANDROID_SHELL,
            homeDir(ctx).absolutePath,
            arrayOf("sh"),
            environment(ctx, false),
            TRANSCRIPT_ROWS,
            SessionHub
        )
    }

    /** [command] is run inside Linux; when empty an interactive login shell is started. */
    fun newLinuxSession(ctx: Context, command: List<String> = emptyList(), compat: Boolean = false): TerminalSession {
        prepare(ctx)
        val args = ArrayList<String>()
        args.add("sh")
        args.add(linuxScript(ctx).absolutePath)
        args.addAll(command)
        return TerminalSession(
            ANDROID_SHELL,
            homeDir(ctx).absolutePath,
            args.toTypedArray(),
            environment(ctx, compat),
            TRANSCRIPT_ROWS,
            SessionHub
        )
    }

    private val MKSHRC = """
        PS1='${'$'}PWD ${'$'} '
        alias ll='ls -la'
        alias la='ls -A'
        alias l='ls -CF'
        alias ls='ls --color=auto'
        alias grep='grep --color=auto'
        echo "MN Terminal - type 'linux' to enter Alpine Linux (menu: Install Linux)."
    """.trimIndent() + "\n"

    private val LINUX_SCRIPT = """
        #!/system/bin/sh
        # Enter the Alpine Linux environment (proot). Usage: linux [command [args...]]
        if [ ! -x "${'$'}MNTGXO_PROOT" ]; then
            echo "linux: proot binary not found (${'$'}MNTGXO_PROOT)"; exit 1
        fi
        if [ ! -e "${'$'}LINUX_ROOTFS/bin/sh" ]; then
            echo "linux: Alpine is not installed. Open the menu and choose 'Install Linux'."; exit 1
        fi
        mkdir -p "${'$'}PROOT_TMP_DIR"

        BINDS=""
        FAKE="${'$'}LINUX_ROOTFS/.fake-proc"
        mkdir -p "${'$'}FAKE"
        if ! cat /proc/loadavg >/dev/null 2>&1; then
            echo "0.12 0.07 0.02 2/165 765" > "${'$'}FAKE/loadavg"
            BINDS="${'$'}BINDS -b ${'$'}FAKE/loadavg:/proc/loadavg"
        fi
        if ! cat /proc/stat >/dev/null 2>&1; then
            printf 'cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 8 0 0 0\nintr 0\nctxt 0\nbtime 0\nprocesses 0\nprocs_running 1\nprocs_blocked 0\n' > "${'$'}FAKE/stat"
            BINDS="${'$'}BINDS -b ${'$'}FAKE/stat:/proc/stat"
        fi
        if ! cat /proc/uptime >/dev/null 2>&1; then
            echo "124.08 932.80" > "${'$'}FAKE/uptime"
            BINDS="${'$'}BINDS -b ${'$'}FAKE/uptime:/proc/uptime"
        fi
        if ! cat /proc/version >/dev/null 2>&1; then
            echo "Linux version 6.2.1-proot (builder@mntgxo) (gcc 12.2.1) #1 SMP PREEMPT" > "${'$'}FAKE/version"
            BINDS="${'$'}BINDS -b ${'$'}FAKE/version:/proc/version"
        fi
        if [ -d "${'$'}LINUX_SDCARD" ]; then
            BINDS="${'$'}BINDS -b ${'$'}LINUX_SDCARD:/sdcard"
        fi

        if [ -x "${'$'}LINUX_ROOTFS/bin/bash" ]; then GSH=/bin/bash; else GSH=/bin/sh; fi
        if [ "${'$'}#" -eq 0 ]; then set -- "${'$'}GSH" -l; fi

        exec "${'$'}MNTGXO_PROOT" --kill-on-exit --link2symlink -0 -r "${'$'}LINUX_ROOTFS" \
            -b /dev -b /proc -b /sys ${'$'}BINDS -w /root \
            /usr/bin/env -i HOME=/root USER=root LOGNAME=root SHELL="${'$'}GSH" \
            TERM="${'$'}{TERM:-xterm-256color}" COLORTERM=truecolor LANG=C.UTF-8 TMPDIR=/tmp \
            PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            "${'$'}@"
    """.trimIndent() + "\n"
}
