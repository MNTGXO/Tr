package com.mntgxo.terminal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class MainActivity : Activity(), SessionHub.Listener, TerminalViewClient {

    private lateinit var terminalView: TerminalView
    private lateinit var tabContainer: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var extraKeys: LinearLayout

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private var fontDp = 14
    private var ctrlLatched = false
    private var altLatched = false
    private var volumeDownHeld = false
    private var ctrlButton: TextView? = null
    private var altButton: TextView? = null
    private var repeater: Runnable? = null
    private var installing = false
    private var installDialog: AlertDialog? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        setContentView(R.layout.activity_main)

        SessionHub.appContext = applicationContext
        terminalView = findViewById(R.id.terminalView)
        tabContainer = findViewById(R.id.tabContainer)
        tabScroll = findViewById(R.id.tabScroll)
        extraKeys = findViewById(R.id.extraKeys)

        fontDp = prefs.getInt(PREF_FONT_DP, 14)
        terminalView.setTerminalViewClient(this)
        applyFontSize()
        buildExtraKeys()

        findViewById<View>(R.id.btnNew).setOnClickListener { newDefaultSession() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        Shells.prepare(this)
        SessionHub.listener = this

        if (SessionHub.sessions.isEmpty()) {
            addSession(Shells.newAndroidSession(this), "android")
        } else {
            val target = SessionHub.current?.takeIf { SessionHub.sessions.contains(it) } ?: SessionHub.sessions.last()
            selectSession(target)
        }
        terminalView.post { showKeyboard() }
    }

    override fun onResume() {
        super.onResume()
        SessionHub.listener = this
        SessionHub.current?.let { if (SessionHub.sessions.contains(it)) terminalView.onScreenUpdated() }
        terminalView.requestFocus()
    }

    override fun onDestroy() {
        if (SessionHub.listener === this) SessionHub.listener = null
        installDialog?.dismiss()
        stopRepeat()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    // ------------------------------------------------------------------ sessions

    private fun addSession(session: TerminalSession, prefix: String) {
        session.mSessionName = SessionHub.nextName(prefix)
        SessionHub.sessions.add(session)
        TerminalService.start(this)
        selectSession(session)
    }

    private fun selectSession(session: TerminalSession) {
        SessionHub.current = session
        terminalView.attachSession(session)
        terminalView.onScreenUpdated()
        refreshTabs()
        terminalView.requestFocus()
    }

    private fun newDefaultSession() {
        if (Shells.isLinuxInstalled(this)) {
            addSession(Shells.newLinuxSession(this), "linux")
        } else {
            addSession(Shells.newAndroidSession(this), "android")
        }
    }

    private fun closeSession(session: TerminalSession) {
        session.finishIfRunning()
        val index = SessionHub.sessions.indexOf(session)
        SessionHub.sessions.remove(session)
        if (SessionHub.sessions.isEmpty()) {
            SessionHub.current = null
            TerminalService.stop(this)
            finish()
            return
        }
        TerminalService.start(this)
        if (SessionHub.current === session || SessionHub.current == null) {
            val next = SessionHub.sessions[minOf(maxOf(index - 1, 0), SessionHub.sessions.size - 1)]
            selectSession(next)
        } else {
            refreshTabs()
        }
    }

    private fun refreshTabs() {
        tabContainer.removeAllViews()
        val density = resources.displayMetrics.density
        for ((i, s) in SessionHub.sessions.withIndex()) {
            val title = s.title?.takeIf { it.isNotBlank() } ?: s.mSessionName ?: "session"
            val label = "${i + 1}:${title.take(18)}"
            val selected = s === SessionHub.current
            val tab = TextView(this).apply {
                text = if (s.isRunning) label else "$label (done)"
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#8B949E"))
                setBackgroundColor(if (selected) Color.parseColor("#238636") else Color.parseColor("#21262D"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                setOnClickListener { selectSession(s) }
                setOnLongClickListener { confirmClose(s); true }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            lp.setMargins(0, (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            tabContainer.addView(tab, lp)
        }
        tabScroll.post { tabScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun confirmClose(session: TerminalSession) {
        AlertDialog.Builder(this)
            .setTitle("Close session?")
            .setMessage(session.mSessionName ?: "session")
            .setPositiveButton("Close") { _, _ -> closeSession(session) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ SessionHub.Listener

    override fun onSessionsChanged() {
        if (SessionHub.sessions.isEmpty()) {
            TerminalService.stop(this)
            finish()
        } else {
            refreshTabs()
        }
    }

    override fun onScreenChanged(session: TerminalSession) {
        if (session === SessionHub.current) terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(session: TerminalSession) {
        refreshTabs()
    }

    override fun onSessionFinished(session: TerminalSession) {
        refreshTabs()
    }

    override fun onCopy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPaste(session: TerminalSession) {
        pasteInto(session)
    }

    @Suppress("DEPRECATION")
    override fun onBell() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        v?.vibrate(40)
    }

    private fun pasteInto(session: TerminalSession) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!text.isNullOrEmpty()) session.emulator?.paste(text)
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        val menu = m.menu
        val linux = Shells.isLinuxInstalled(this)
        menu.add(0, M_NEW_ANDROID, 0, "New Android shell")
        if (linux) {
            menu.add(0, M_NEW_LINUX, 1, "New Linux (Alpine) session")
            menu.add(0, M_NEW_LINUX_COMPAT, 2, "New Linux session (compat mode)")
            menu.add(0, M_DEV_TOOLS, 3, "Install dev tools (bash, git, python, gcc...)")
            menu.add(0, M_REMOVE_LINUX, 4, "Remove Linux")
        } else {
            menu.add(0, M_INSTALL_LINUX, 1, "Install Linux (Alpine)")
        }
        menu.add(0, M_CLOSE, 5, "Close this session")
        menu.add(0, M_PASTE, 6, "Paste")
        menu.add(0, M_FONT_UP, 7, "Font size +")
        menu.add(0, M_FONT_DOWN, 8, "Font size -")
        menu.add(0, M_KEYBOARD, 9, "Toggle keyboard")
        menu.add(0, M_RESET, 10, "Reset terminal")
        menu.add(0, M_STORAGE, 11, "Grant storage access")
        menu.add(0, M_HELP, 12, "Help")
        menu.add(0, M_EXIT, 13, "Exit and kill all sessions")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                M_NEW_ANDROID -> addSession(Shells.newAndroidSession(this), "android")
                M_NEW_LINUX -> addSession(Shells.newLinuxSession(this), "linux")
                M_NEW_LINUX_COMPAT -> addSession(Shells.newLinuxSession(this, emptyList(), true), "linux")
                M_INSTALL_LINUX -> startInstall(false)
                M_DEV_TOOLS -> installDevTools()
                M_REMOVE_LINUX -> confirmRemoveLinux()
                M_CLOSE -> SessionHub.current?.let { confirmClose(it) }
                M_PASTE -> SessionHub.current?.let { pasteInto(it) }
                M_FONT_UP -> changeFont(1)
                M_FONT_DOWN -> changeFont(-1)
                M_KEYBOARD -> toggleKeyboard()
                M_RESET -> SessionHub.current?.reset()
                M_STORAGE -> requestStorage()
                M_HELP -> showHelp()
                M_EXIT -> SessionHub.killAll()
            }
            true
        }
        m.show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("MN Terminal")
            .setMessage(
                "- Android shell: native /system/bin/sh with a real PTY (vim, nano, top work).\n" +
                "- Linux: a full Alpine Linux in proot. Install it from the menu, then use apk add <pkg>. " +
                "From the Android shell, type 'linux' to enter it, or 'linux <command>' to run one command.\n" +
                "- Volume Down acts as Ctrl (Volume Down + C = Ctrl+C). CTRL/ALT buttons latch for one key.\n" +
                "- Pinch to zoom, long-press text to select and copy, long-press a tab to close it.\n" +
                "- If Linux crashes on startup, use 'New Linux session (compat mode)'.\n" +
                "- Shared storage: ~/storage (Android) or /sdcard (Linux) after granting storage access."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_STORAGE
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE) {
            val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            Toast.makeText(this, if (ok) "Storage access granted" else "Storage access denied", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ Linux install / remove

    private fun startInstall(thenDevTools: Boolean) {
        if (installing) return
        installing = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("Installing Alpine Linux")
            .setMessage("Starting…")
            .setCancelable(false)
            .create()
        installDialog = dialog
        dialog.show()
        val appCtx = applicationContext
        Thread({
            var error: String? = null
            try {
                LinuxInstaller.install(appCtx) { msg -> ui.post { if (!isFinishing) dialog.setMessage(msg) } }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            ui.post {
                installing = false
                dialog.dismiss()
                installDialog = null
                if (isFinishing || isDestroyed) return@post
                if (error != null) {
                    AlertDialog.Builder(this)
                        .setTitle("Installation failed")
                        .setMessage(error)
                        .setPositiveButton("OK", null)
                        .show()
                } else if (thenDevTools) {
                    installDevTools()
                } else {
                    addSession(Shells.newLinuxSession(this), "linux")
                }
            }
        }, "linux-installer").start()
    }

    private fun installDevTools() {
        if (!Shells.isLinuxInstalled(this)) {
            startInstall(true)
            return
        }
        val pkgs = Shells.DEV_TOOLS.joinToString(" ")
        val script = "apk update && apk add --no-cache $pkgs; echo; " +
            "if [ -x /bin/bash ]; then exec /bin/bash -l; else exec /bin/sh -l; fi"
        addSession(Shells.newLinuxSession(this, listOf("/bin/sh", "-c", script)), "linux")
    }

    private fun confirmRemoveLinux() {
        AlertDialog.Builder(this)
            .setTitle("Remove Linux?")
            .setMessage("This deletes the Alpine root filesystem and everything you installed or saved inside it.")
            .setPositiveButton("Remove") { _, _ -> removeLinux() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeLinux() {
        for (s in SessionHub.sessions.toList()) {
            if (s.mSessionName?.startsWith("linux") == true) closeSession(s)
        }
        if (isFinishing) return
        val root = Shells.rootfsDir(this)
        Thread({
            deleteTree(AndroidFs, root)
            ui.post { Toast.makeText(this, "Linux removed", Toast.LENGTH_SHORT).show() }
        }, "linux-remover").start()
    }

    // ------------------------------------------------------------------ font / keyboard

    private fun applyFontSize() {
        val px = (fontDp * resources.displayMetrics.density + 0.5f).toInt()
        terminalView.setTextSize(px)
    }

    private fun changeFont(delta: Int) {
        fontDp = (fontDp + delta).coerceIn(MIN_FONT_DP, MAX_FONT_DP)
        prefs.edit().putInt(PREF_FONT_DP, fontDp).apply()
        applyFontSize()
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        terminalView.requestFocus()
        imm.showSoftInput(terminalView, 0)
    }

    @Suppress("DEPRECATION")
    private fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    // ------------------------------------------------------------------ extra keys

    private class ExtraKey(val label: String, val repeat: Boolean = false, val action: () -> Unit)

    private fun buildExtraKeys() {
        extraKeys.removeAllViews()
        val rows = listOf(
            listOf(
                ExtraKey("ESC") { sendKey(KeyEvent.KEYCODE_ESCAPE) },
                ExtraKey("TAB") { sendKey(KeyEvent.KEYCODE_TAB) },
                ExtraKey("CTRL") { toggleCtrl() },
                ExtraKey("ALT") { toggleAlt() },
                ExtraKey("←", true) { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) },
                ExtraKey("↓", true) { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) },
                ExtraKey("↑", true) { sendKey(KeyEvent.KEYCODE_DPAD_UP) },
                ExtraKey("→", true) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            ),
            listOf(
                ExtraKey("HOME") { sendKey(KeyEvent.KEYCODE_MOVE_HOME) },
                ExtraKey("END") { sendKey(KeyEvent.KEYCODE_MOVE_END) },
                ExtraKey("PGUP", true) { sendKey(KeyEvent.KEYCODE_PAGE_UP) },
                ExtraKey("PGDN", true) { sendKey(KeyEvent.KEYCODE_PAGE_DOWN) },
                ExtraKey("-") { sendChar('-') },
                ExtraKey("/") { sendChar('/') },
                ExtraKey("|") { sendChar('|') },
                ExtraKey("~") { sendChar('~') }
            )
        )
        for (row in rows) {
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in row) {
                val v = TextView(this).apply {
                    text = key.label
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setBackgroundColor(KEY_BG)
                    isClickable = true
                }
                if (key.label == "CTRL") ctrlButton = v
                if (key.label == "ALT") altButton = v
                v.setOnTouchListener { view, ev -> handleKeyTouch(view, ev, key) }
                val lp = LinearLayout.LayoutParams(0, (40 * resources.displayMetrics.density).toInt(), 1f)
                lp.setMargins(1, 1, 1, 1)
                rowView.addView(v, lp)
            }
            extraKeys.addView(rowView)
        }
    }

    private fun handleKeyTouch(view: View, ev: MotionEvent, key: ExtraKey): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.setBackgroundColor(KEY_BG_PRESSED)
                key.action()
                if (key.repeat) {
                    stopRepeat()
                    val r = object : Runnable {
                        override fun run() {
                            key.action()
                            ui.postDelayed(this, 70)
                        }
                    }
                    repeater = r
                    ui.postDelayed(r, 400)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopRepeat()
                updateModifierButtons()
                if (view !== ctrlButton && view !== altButton) view.setBackgroundColor(KEY_BG)
            }
        }
        return true
    }

    private fun stopRepeat() {
        repeater?.let { ui.removeCallbacks(it) }
        repeater = null
    }

    private fun toggleCtrl() {
        ctrlLatched = !ctrlLatched
        updateModifierButtons()
    }

    private fun toggleAlt() {
        altLatched = !altLatched
        updateModifierButtons()
    }

    private fun updateModifierButtons() {
        ctrlButton?.setBackgroundColor(if (ctrlLatched) KEY_BG_ACTIVE else KEY_BG)
        altButton?.setBackgroundColor(if (altLatched) KEY_BG_ACTIVE else KEY_BG)
    }

    private fun sendKey(keyCode: Int) {
        if (SessionHub.current == null) return
        var mod = 0
        if (consumeCtrl()) mod = mod or KeyHandler.KEYMOD_CTRL
        if (consumeAlt()) mod = mod or KeyHandler.KEYMOD_ALT
        terminalView.handleKeyCode(keyCode, mod)
    }

    private fun sendChar(c: Char) {
        if (SessionHub.current == null) return
        terminalView.inputCodePoint(c.code, false, false)
    }

    private fun consumeCtrl(): Boolean {
        val v = ctrlLatched || volumeDownHeld
        if (ctrlLatched) {
            ctrlLatched = false
            updateModifierButtons()
        }
        return v
    }

    private fun consumeAlt(): Boolean {
        val v = altLatched
        if (altLatched) {
            altLatched = false
            updateModifierButtons()
        }
        return v
    }

    // ------------------------------------------------------------------ TerminalViewClient

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            changeFont(if (scale > 1f) 1 else -1)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        showKeyboard()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeDownHeld = true
            return true
        }
        if (session != null && !session.isRunning && keyCode == KeyEvent.KEYCODE_ENTER) {
            closeSession(session)
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeDownHeld = false
            return true
        }
        return false
    }

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = consumeCtrl()

    override fun readAltKey(): Boolean = consumeAlt()

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        if (session != null && !session.isRunning && (codePoint == 13 || codePoint == 10)) {
            closeSession(session)
            return true
        }
        return false
    }

    override fun onEmulatorSet() {
    }

    override fun logError(tag: String?, message: String?) = SessionHub.logError(tag, message)
    override fun logWarn(tag: String?, message: String?) = SessionHub.logWarn(tag, message)
    override fun logInfo(tag: String?, message: String?) = SessionHub.logInfo(tag, message)
    override fun logDebug(tag: String?, message: String?) = SessionHub.logDebug(tag, message)
    override fun logVerbose(tag: String?, message: String?) = SessionHub.logVerbose(tag, message)
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) =
        SessionHub.logStackTraceWithMessage(tag, message, e)
    override fun logStackTrace(tag: String?, e: Exception?) = SessionHub.logStackTrace(tag, e)

    companion object {
        private const val PREF_FONT_DP = "font_dp"
        private const val MIN_FONT_DP = 8
        private const val MAX_FONT_DP = 40
        private const val REQUEST_STORAGE = 42

        private val KEY_BG = Color.parseColor("#21262D")
        private val KEY_BG_PRESSED = Color.parseColor("#484F58")
        private val KEY_BG_ACTIVE = Color.parseColor("#238636")

        private const val M_NEW_ANDROID = 1
        private const val M_NEW_LINUX = 2
        private const val M_NEW_LINUX_COMPAT = 3
        private const val M_INSTALL_LINUX = 4
        private const val M_DEV_TOOLS = 5
        private const val M_REMOVE_LINUX = 6
        private const val M_CLOSE = 7
        private const val M_PASTE = 8
        private const val M_FONT_UP = 9
        private const val M_FONT_DOWN = 10
        private const val M_KEYBOARD = 11
        private const val M_RESET = 12
        private const val M_STORAGE = 13
        private const val M_HELP = 14
        private const val M_EXIT = 15
    }
}
