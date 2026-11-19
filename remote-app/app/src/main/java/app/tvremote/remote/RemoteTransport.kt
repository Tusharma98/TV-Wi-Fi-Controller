package app.tvremote.remote

import android.content.Context

/** A command the UI sends, parsed once from the line protocol the UI speaks ("K 19", "T hi%21"). */
sealed class Cmd {
    class Key(val code: Int) : Cmd()
    class Text(val text: String, val replace: Boolean) : Cmd()
    /** Mouse / app list / file / anything the TV keyboard (IME) cannot do. */
    class Other(val name: String) : Cmd()

    companion object {
        fun parse(line: String): Cmd {
            val i = line.indexOf(' ')
            val c = if (i < 0) line else line.substring(0, i)
            val a = if (i < 0) "" else line.substring(i + 1)
            return when (c) {
                "K" -> a.trim().toIntOrNull()?.let { Key(it) } ?: Other(c)
                "T" -> Text(dec(a), false)
                "V" -> Text(dec(a), true)
                else -> Other(c)
            }
        }
        private fun dec(s: String) = try { java.net.URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
    }
}

/** What an IME keyboard can deliver, given what the TV announced. */
object ImeCaps {
    val EDITOR_KEYS = setOf(19, 20, 21, 22, 23, 61, 62, 66, 67, 112, 4, 92, 93, 122, 123)
    val MEDIA_KEYS = setOf(85, 86, 87, 88, 89, 90, 126, 127)

    /** Keys the TV can press itself once its optional Accessibility add-on is on (no focused field needed). */
    val A11Y_KEYS = setOf(3, 4, 19, 20, 21, 22, 23, 26, 66, 82, 84, 176, 187)
    /** Extension commands that need the Accessibility add-on. */
    val A11Y_CMDS = setOf("M", "C", "S", "MOUSE", "FIELD", "CLEAR", "VOICE", "LIST", "ICON", "L", "A", "OPENF", "FILE")
    /** Extension commands that work without it (they open TV settings screens). */
    val FREE_CMDS = setOf("W", "A11YSET")

    fun supportsKey(code: Int, caps: Set<String>, inputActive: Boolean): Boolean = when {
        code == 24 || code == 25 || code == 164 -> "vol" in caps          // AudioManager on the TV: works without a focused field
        code in MEDIA_KEYS -> "media" in caps
        "a11y" in caps && code in A11Y_KEYS -> true                       // Accessibility add-on: D-pad / OK / Back / Home / Recents / Power / Menu / Search / Settings
        code == 3 -> "home" in caps                                       // only if the TV granted the optional overlay permission
        code in EDITOR_KEYS -> "keys" in caps && inputActive              // needs a focused text field: that is the IME's hard limit
        else -> false                                                     // Power, Recents, Menu, Search...: an IME cannot send them
    }
}

/** What to tell the person when a command cannot be delivered. Pure, unit-tested. */
object Hints {
    const val NO_FIELD = "Select a text field on the TV first"
    const val NO_KEY = "The TV keyboard cannot send that key (Power, Recents, Menu, Search are system keys)"
    const val NO_HOME = "Home needs 'Display over other apps' for TV Remote Keyboard on the TV"
    const val NOT_A11Y = "Tap the Accessibility button and turn it on for the TV Remote Keyboard to use this"

    /** null = deliver silently (nothing to say). */
    fun forKey(code: Int, caps: Set<String>): String = when {
        code == 3 -> NO_HOME
        code in ImeCaps.EDITOR_KEYS && "keys" in caps -> NO_FIELD
        else -> NO_KEY
    }

    /** UI housekeeping lines that must never raise a message. */
    val SILENT = setOf("", "FIELD", "MOUSE", "ICON", "PING", "A11YSET", "W")
}

/** The way to the TV: the TV keyboard link (and, when the TV's Accessibility add-on is on, extension lines over the same link). */
object Transport {
    private var lastHint = 0L

    fun init(c: Context) { ImeLink.init(c) }

    /** Gamepad buttons -> the TV key they stand for. */
    fun mapKey(code: Int): Int = when (code) {
        96 -> 23; 97 -> 4; 99 -> 82; 100 -> 84; 102 -> 89; 103 -> 90; 108 -> 3; 109 -> 187
        else -> code
    }

    private fun hint(m: String) {
        val n = System.currentTimeMillis()
        if (n - lastHint > 3000) { lastHint = n; Link.onToast?.invoke(m) }
    }

    // ---- mouse: touch events arrive ~100x per second; merge them so the link carries <= ~90 small messages per second ----
    private val mx = java.util.concurrent.atomic.AtomicInteger()
    private val my = java.util.concurrent.atomic.AtomicInteger()
    private val mPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mouseTimer by lazy { java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mouse-merge").apply { isDaemon = true } } }

    private fun mouse(dx: Int, dy: Int) {
        mx.addAndGet(dx); my.addAndGet(dy)
        if (mPending.compareAndSet(false, true)) mouseTimer.schedule({
            mPending.set(false)
            val x = mx.getAndSet(0); val y = my.getAndSet(0)
            if (x != 0 || y != 0) ImeLink.ext("M $x $y")
        }, 11, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** true = handled (delivered or explained). false = not connected (Link.send shows the connect hint). */
    fun send(line: String): Boolean {
        if (!ImeLink.ready) return false
        when (val cmd = Cmd.parse(line)) {
            is Cmd.Key -> {
                val code = mapKey(cmd.code)
                if (ImeLink.supportsKey(code)) ImeLink.key(code) else hint(Hints.forKey(code, ImeLink.caps))
            }
            is Cmd.Text -> if (ImeLink.inputActive || ImeLink.a11y) ImeLink.text(cmd.text, cmd.replace) else hint(Hints.NO_FIELD)
            is Cmd.Other -> when {
                cmd.name in ImeCaps.FREE_CMDS -> ImeLink.ext(line)
                cmd.name in ImeCaps.A11Y_CMDS -> when {
                    !ImeLink.a11y -> if (cmd.name !in Hints.SILENT) hint(Hints.NOT_A11Y)
                    cmd.name == "M" -> line.split(' ').let { d -> mouse(d.getOrNull(1)?.toIntOrNull() ?: 0, d.getOrNull(2)?.toIntOrNull() ?: 0) }
                    else -> ImeLink.ext(line)
                }
                else -> { }
            }
        }
        return true
    }
}
