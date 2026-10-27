package com.mntgxo.terminal

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide owner of all terminal sessions. Sessions outlive the activity (a foreground
 * service keeps the process alive), and the activity attaches itself as [listener] when visible.
 */
object SessionHub : TerminalSessionClient {

    interface Listener {
        fun onSessionsChanged()
        fun onScreenChanged(session: TerminalSession)
        fun onTitleChanged(session: TerminalSession)
        fun onSessionFinished(session: TerminalSession)
        fun onCopy(text: String)
        fun onPaste(session: TerminalSession)
        fun onBell()
    }

    val sessions = CopyOnWriteArrayList<TerminalSession>()
    @Volatile var listener: Listener? = null
    @Volatile var current: TerminalSession? = null
    @Volatile var appContext: Context? = null
    private var counter = 0

    fun nextName(prefix: String): String {
        counter++
        return "$prefix $counter"
    }

    fun killAll() {
        for (s in sessions) s.finishIfRunning()
        sessions.clear()
        current = null
        listener?.onSessionsChanged()
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        listener?.onScreenChanged(changedSession)
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        listener?.onTitleChanged(changedSession)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val l = listener
        if (l != null) {
            l.onSessionFinished(finishedSession)
        } else {
            sessions.remove(finishedSession)
            val ctx = appContext
            if (ctx != null && sessions.isEmpty()) TerminalService.stop(ctx)
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        listener?.onCopy(text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        listener?.onPaste(session)
    }

    override fun onBell(session: TerminalSession) {
        listener?.onBell()
    }

    override fun onColorsChanged(session: TerminalSession) {
        listener?.onScreenChanged(session)
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
    }

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: "MNTerm", message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: "MNTerm", message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: "MNTerm", message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: "MNTerm", message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: "MNTerm", message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: "MNTerm", message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: "MNTerm", "error", e)
    }
}
