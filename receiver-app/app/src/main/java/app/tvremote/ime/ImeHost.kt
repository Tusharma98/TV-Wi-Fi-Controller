package app.tvremote.ime

import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import app.tvremote.imeproto.ImeProto

/** Wires the pure server to Android: persistence, the keyboard service (editor keys / text) and system calls (volume, media). */
object ImeHost : Injector, ServerEvents, PairStorage {
    @Volatile var service: ImeService? = null
    @Volatile var onChanged: (() -> Unit)? = null
    @Volatile private var srv: ImeServer? = null
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences

    private val EDITOR_KEYS = setOf(19, 20, 21, 22, 23, 61, 62, 66, 67, 112, 4, 92, 93, 122, 123)
    private val MEDIA_KEYS = setOf(85, 86, 87, 88, 89, 90, 126, 127)

    @Synchronized fun ensure(c: Context): ImeServer {
        srv?.let { return it }
        app = c.applicationContext
        Rx.init(app)
        prefs = app.getSharedPreferences("ime", Context.MODE_PRIVATE)
        var id = prefs.getString("tv_id", null)
        if (id == null) { id = ImeProto.randomHex(8); prefs.edit().putString("tv_id", id).apply() }
        val s = ImeServer(ImeProto.PORT, id, Build.MODEL ?: "TV", this, this, this)
        try { s.start() } catch (e: Exception) { }          // port busy: the next ensure() retries
        srv = s
        return s
    }
    fun server(): ImeServer? = srv

    // ---------- events ----------
    override fun changed() { Rx.session(srv?.activeClient != null); onChanged?.invoke() }

    /** Accessibility service connected / disconnected: refresh the setup screen. */
    fun onA11yChanged() { onChanged?.invoke() }

    // ---------- storage (app-private preferences) ----------
    override fun load(clientId: String): Paired? {
        val v = prefs.getString("p_$clientId", null) ?: return null
        val i = v.indexOf('|'); if (i < 0) return null
        val t = ImeProto.unhex(v.substring(i + 1)) ?: return null
        return Paired(clientId, v.substring(0, i), t)
    }
    override fun save(p: Paired) { prefs.edit().putString("p_" + p.clientId, p.name.replace("|", " ") + "|" + ImeProto.hex(p.token)).apply() }
    override fun all(): List<Paired> = prefs.all.keys.filter { it.startsWith("p_") }.mapNotNull { load(it.substring(2)) }
    override fun clear() { val e = prefs.edit(); for (k in prefs.all.keys.filter { it.startsWith("p_") }) e.remove(k); e.apply() }

    // ---------- injection ----------
    override fun inputActive(): Boolean = service?.hasInput() == true

    private fun canHome() = Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(app)

    override fun caps(): String {
        val l = arrayListOf("keys", "text", "vol", "media")
        if (canHome()) l.add("home")
        if (A11y.inst != null) l.add("a11y")                       // optional Accessibility add-on is on: the phone shows mouse / gamepad / apps / files
        return l.joinToString(",")
    }

    override fun key(code: Int, action: Char): InjectResult {
        when (code) {
            24, 25, 164 -> { if (action != 'U') volume(code); return InjectResult.OK }       // AudioManager: needs no focused field
            in MEDIA_KEYS -> { if (action != 'U') media(code); return InjectResult.OK }
        }
        if (A11y.inst != null && code in Rx.A11Y_KEYS) {                                     // Accessibility add-on: focus moves, Back/Home/Recents, Power, Menu...
            if (action == 'U') return InjectResult.OK
            if (Rx.a11yKey(code, inputActive())) return InjectResult.OK
        }
        if (code == 3) return if (canHome() && action != 'U') { home(); InjectResult.OK } else InjectResult.UNSUPPORTED   // Home/Recents/Power are system keys an IME cannot send
        if (code !in EDITOR_KEYS) return InjectResult.UNSUPPORTED
        val s = service?.takeIf { it.hasInput() } ?: return InjectResult.NO_INPUT
        return if (s.sendKey(code, action)) InjectResult.OK else InjectResult.NO_INPUT
    }

    override fun ext(line: String, reply: (String) -> Unit) = Rx.handle(line, reply)

    override fun text(s: String, replace: Boolean): InjectResult {
        val sv = service?.takeIf { it.hasInput() } ?: return if (Rx.a11yText(s, replace)) InjectResult.OK else InjectResult.NO_INPUT
        return if (sv.putText(s, replace)) InjectResult.OK else InjectResult.NO_INPUT
    }

    private fun volume(code: Int) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val d = when (code) { 24 -> AudioManager.ADJUST_RAISE; 25 -> AudioManager.ADJUST_LOWER; else -> AudioManager.ADJUST_TOGGLE_MUTE }
        try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI) } catch (e: Exception) { }
    }

    private fun media(code: Int) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try { am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) } catch (e: Exception) { }
    }

    private fun home() {
        try { app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }
}
