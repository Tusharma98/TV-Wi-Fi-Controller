package app.tvremote.remote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Base64
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.widget.*
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val MP = -1
private const val WC = -2
private val DARK = 0xFF05050B.toInt()
private val GREEN = 0xFF4CAF50.toInt()
private val PURPLE = 0xFF6A4FB3.toInt()
private val SOFT = 0xFFDDDDE6.toInt()

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var main: View
    private lateinit var pill: LinearLayout
    private lateinit var pillBg: GradientDrawable
    private lateinit var pillTxt: TextView
    private lateinit var disc: TextView
        private val slotImg = arrayOfNulls<ImageView>(6)
    private var track: FrameLayout? = null
    private var trackPad: Pad? = null
    private var gamepad: FrameLayout? = null
    private val padCtl = LinkedHashMap<String, View>()   // movable / resizable gamepad controls by id
    private var padEdit: EditLayer? = null
    private var padSel: String? = null
    private var padBar: SeekBar? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun glossy(r: Int, oval: Boolean = false) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF30303A.toInt(), 0xFF15151B.toInt())).apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(r).toFloat()
            setStroke(dp(1), 0xFF3C3C4A.toInt())
        }

    // ---------- click animation ----------
    private fun pressAnim(v: View) {
        v.animate().cancel()
        v.animate().scaleX(.9f).scaleY(.9f).alpha(.75f).setDuration(70).start()
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
    private fun releaseAnim(v: View) {
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(170).setInterpolator(OvershootInterpolator(3f)).start()
    }

    /** fires on touch-down (and repeats while held) - for remote keys */
    @SuppressLint("ClickableViewAccessibility")
    private fun hold(v: View, repeat: Boolean = false, down: () -> Unit) {
        var rep: Runnable? = null
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressAnim(view); down()
                    if (repeat) {
                        val r = object : Runnable { override fun run() { down(); h.postDelayed(this, 150) } }
                        rep = r; h.postDelayed(r, 420)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { releaseAnim(view); rep?.let { h.removeCallbacks(it) } }
            }
            true
        }
    }

    private fun key(v: View, code: Int, repeat: Boolean = false) = hold(v, repeat) { Link.send("K $code") }

    /** fires on release (tap) with optional long-press */
    @SuppressLint("ClickableViewAccessibility")
    private fun tap(v: View, long: (() -> Unit)? = null, click: () -> Unit) {
        var lp = false
        var lr: Runnable? = null
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressAnim(view); lp = false
                    if (long != null) {
                        val r = Runnable { lp = true; view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); long() }
                        lr = r; h.postDelayed(r, 500)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    releaseAnim(view); lr?.let { h.removeCallbacks(it) }
                    if (!lp && e.x >= 0 && e.x <= view.width && e.y >= 0 && e.y <= view.height) click()
                }
                MotionEvent.ACTION_CANCEL -> { releaseAnim(view); lr?.let { h.removeCallbacks(it) } }
            }
            true
        }
    }

    // ---------- widgets ----------
    private fun ic(res: Int, tint: Int = Color.WHITE, pad: Int = 0) = ImageView(this).apply {
        setImageResource(res); setColorFilter(tint); scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun circleBtn(res: Int, size: Int, tint: Int = Color.WHITE) = ic(res, tint, size / 4).apply {
        background = glossy(0, true); layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun tileV(res: Int, label: String, w: Int, hh: Int, oval: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = glossy(18, oval)
        addView(ImageView(this@MainActivity).apply { setImageResource(res); setColorFilter(Color.WHITE) }, LinearLayout.LayoutParams(dp(26), dp(26)))
        addView(TextView(this@MainActivity).apply { text = label; setTextColor(SOFT); textSize = 11f; gravity = Gravity.CENTER })
        layoutParams = LinearLayout.LayoutParams(dp(w), dp(hh)).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
    }

    private fun label(t: String, sz: Float = 15f) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = sz; gravity = Gravity.CENTER; background = glossy(22)
    }

    private fun cell(v: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, WC, 1f); addView(v)
    }

    // ---------- main screen (weights + Fit => scales to any screen, no scrolling) ----------
    private val pages = ArrayList<View>()
    private var page = 0
    private lateinit var dockHost: FrameLayout
    private lateinit var dots: LinearLayout

    private fun fit(v: View, aspect: Float = 1f, ratio: Float = .9f, w: Float = 1f, vertical: Boolean = false) = Fit(this, aspect, ratio).apply {
        layoutParams = if (vertical) LinearLayout.LayoutParams(MP, 0, w) else LinearLayout.LayoutParams(0, MP, w)
        addView(v, FrameLayout.LayoutParams(WC, WC, Gravity.CENTER))
    }

    private fun rowW(v: View, w: Float, top: Int = 8) = v.apply { layoutParams = LinearLayout.LayoutParams(MP, 0, w).apply { topMargin = dp(top) } }

    private fun rbtn(res: Int, tint: Int = Color.WHITE, frac: Float = .27f) = RIcon(this, res, tint, frac).apply { background = glossy(0, true) }

    // ---------- dock swipe (finger-follow, looping) ----------
    private var dragTarget = -1
    private var dragging = false

    private fun resetPages() {
        for ((i, p) in pages.withIndex()) { p.animate().cancel(); p.translationX = 0f; p.visibility = if (i == page) View.VISIBLE else View.GONE }
    }

    private fun dragDock(dx: Float) {
        val wd = dockHost.width.toFloat().coerceAtLeast(1f)
        if (!dragging) { dragging = true; resetPages() }
        val dir = if (dx < 0) 1 else -1                       // finger moves right->left => next page
        val t = (page + dir + pages.size) % pages.size
        if (t != dragTarget) { if (dragTarget >= 0 && dragTarget != page) pages[dragTarget].visibility = View.GONE; dragTarget = t }
        if (t == page) return
        pages[t].visibility = View.VISIBLE
        pages[page].translationX = dx
        pages[t].translationX = dx + dir * wd
    }

    private fun endDock(dx: Float, vx: Float) {
        if (!dragging) return
        dragging = false
        val wd = dockHost.width.toFloat().coerceAtLeast(1f)
        val dir = if (dx < 0) 1 else -1
        val t = dragTarget; dragTarget = -1
        val old = pages[page]
        val commit = t >= 0 && t != page && (abs(dx) > wd / 4 || (abs(vx) > dp(600) && (vx < 0) == (dx < 0)))
        if (commit) {
            val nw = pages[t]
            old.animate().translationX(-dir * wd).setDuration(180).start()
            nw.animate().translationX(0f).setDuration(180).withEndAction { resetPages() }.start()
            page = t; updateDots()
        } else {
            old.animate().translationX(0f).setDuration(160).start()
            if (t >= 0 && t != page) pages[t].animate().translationX(dir * wd).setDuration(160).withEndAction { resetPages() }.start()
            else resetPages()
        }
    }

    private fun updateDots() { for (i in 0 until dots.childCount) dots.getChildAt(i).alpha = if (i == page) 1f else .3f }

    private lateinit var allPages: List<View>
    private lateinit var basePage: View
    private lateinit var accIcon: ImageView
    private lateinit var accText: TextView
    private var pagesFor: Boolean? = null

    /** Tray content follows the TV: Accessibility OFF -> one slide (Keyboard + Accessibility); ON -> three slides, swipe as before. */
    private fun applyPages() {
        val on = ImeLink.a11y
        accIcon.setColorFilter(if (on) GREEN else Color.WHITE); accText.setTextColor(if (on) GREEN else SOFT)
        if (pagesFor == on) return
        pagesFor = on
        pages.clear(); pages.addAll(if (on) allPages else listOf(basePage))
        for (p in allPages) { p.animate().cancel(); p.translationX = 0f; p.visibility = View.GONE }
        dragging = false; dragTarget = -1
        if (page >= pages.size) page = 0
        pages[page].visibility = View.VISIBLE
        dots.removeAllViews()
        for (i in pages.indices) dots.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { setMargins(dp(3), 0, dp(3), 0) }
        })
        dots.visibility = if (pages.size > 1) View.VISIBLE else View.INVISIBLE
        updateDots()
    }

    /** The Accessibility button: opens the TV's Accessibility settings right on the TV, so the receiver's service can be switched on. */
    private fun accessTile() {
        if (!Link.connected) { Link.send(""); return }
        Link.send("A11YSET")
        if (!ImeLink.a11y) toast("On the TV: turn ON 'TV Remote Keyboard' in Accessibility")
    }

    private fun wifiSettings() {
        if (!Link.connected) { Link.send(""); return }
        Link.send("W"); toast("Opening Wi-Fi settings on the TV")
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("remote", MODE_PRIVATE)
        Transport.init(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= 28) {
            val a = window.attributes
            a.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = a
        }
        root = FrameLayout(this).apply { setBackgroundColor(DARK) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(12)) }
        root.setOnApplyWindowInsetsListener { _, ins ->
            col.setPadding(dp(14) + ins.systemWindowInsetLeft, dp(10) + ins.systemWindowInsetTop, dp(14) + ins.systemWindowInsetRight, dp(12) + ins.systemWindowInsetBottom)
            ins
        }

        // top: power | connect pill | mute
        val power = rbtn(R.drawable.i_power, 0xFFFF3B47.toInt()); key(power, 26)
        val mute = rbtn(R.drawable.i_mute); key(mute, 164)
        pillBg = GradientDrawable().apply { setColor(0xFF14141C.toInt()); cornerRadius = dp(26).toFloat(); setStroke(dp(1), PURPLE) }
        pillTxt = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f }
        pill = LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = pillBg
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_wifi); setColorFilter(Color.WHITE) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
            addView(pillTxt)
        }
        tap(pill, long = { ipDialog() }) { connect() }
        disc = TextView(this).apply {
            setTextColor(GREEN); textSize = 12f; gravity = Gravity.CENTER; visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xFF10201A.toInt()); cornerRadius = dp(26).toFloat(); setStroke(dp(1), GREEN) }
        }
        tap(disc, long = { transportDialog() }) { disconnect() }
        val pillBox = Fit(this, 4.6f, .9f).apply {
            layoutParams = LinearLayout.LayoutParams(0, MP, 4.2f)
            addView(pill, FrameLayout.LayoutParams(WC, WC, Gravity.CENTER)); addView(disc, FrameLayout.LayoutParams(WC, WC, Gravity.CENTER))
        }
        col.addView(LinearLayout(this).apply { addView(fit(power, 1f, .95f, 1.1f)); addView(pillBox); addView(fit(mute, 1f, .95f, 1.1f)) }.also { it.layoutParams = LinearLayout.LayoutParams(MP, 0, .9f) })

        // d-pad panel
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1B1B23.toInt(), 0xFF0E0E13.toInt())).apply {
                cornerRadius = dp(38).toFloat(); setStroke(dp(1), 0xFF2E2E3A.toInt())
            }
        }
        fun prow(a: View?, m: View?, c: View?) = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MP, 0, 1f)
            for (x in listOf(a, m, c)) addView(x ?: Space(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(0, MP, 1f) })
        }
        fun chev(res: Int, code: Int): View { val v = RIcon(this, res, Color.WHITE, .2f); key(v, code, true); return fit(v, 1f, .85f) }
        val ok = TextView(this).apply { text = "OK"; setTextColor(Color.WHITE); textSize = 26f; gravity = Gravity.CENTER; background = glossy(0, true) }
        key(ok, 23)
        panel.addView(prow(null, chev(R.drawable.i_up, 19), null))
        panel.addView(prow(chev(R.drawable.i_left, 21), fit(ok, 1f, .95f), chev(R.drawable.i_right, 22)))
        panel.addView(prow(null, chev(R.drawable.i_down, 20), null))
        col.addView(rowW(panel, 3.2f))

        // back / home / settings
        val back = rbtn(R.drawable.i_back); key(back, 4)
        val home = rbtn(R.drawable.i_home); key(home, 3)
        val gear = rbtn(R.drawable.i_gear); tap(gear, long = { wifiSettings() }) { Link.send("K 176") }   // tap: Settings, hold: Wi-Fi settings on the TV
        col.addView(rowW(LinearLayout(this).apply { addView(fit(back, 1f, .8f)); addView(fit(home, 1f, .8f)); addView(fit(gear, 1f, .8f)) }, 1f))

        // volume | mic + clear all | play/pause + next
        val mic = rbtn(R.drawable.i_mic); tap(mic, long = { Link.send("VOICE") }) { voice() }   // tap: speak here -> text on TV, hold: TV's own assistant
        val clearB = tileV(R.drawable.i_clear, "Clear all", 0, 0, true); tap(clearB) { clearAll() }
        val snd = tileV(R.drawable.i_playpause, "Play/Pause", 0, 0); key(snd, 85)   // KEYCODE_MEDIA_PLAY_PAUSE
        val pic = tileV(R.drawable.i_next, "Next", 0, 0); key(pic, 87)              // KEYCODE_MEDIA_NEXT
        val c2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, MP, 1f); addView(fit(mic, 1f, .72f, 1f, true)); addView(fit(clearB, 1f, .92f, 1f, true)) }
        val c3 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, MP, 1f); addView(fit(snd, 1.25f, .9f, 1f, true)); addView(fit(pic, 1.25f, .9f, 1f, true)) }
        col.addView(rowW(LinearLayout(this).apply { addView(fit(VolView(this@MainActivity), .46f, .95f)); addView(c2); addView(c3) }, 2.1f))

        // dock: swipe right->left to cycle (looping): Favourite apps -> Modes -> Send file -> Favourite apps ...
        dockHost = SwipeHost(this, { dx -> dragDock(dx) }, { dx, vx -> endDock(dx, vx) }).apply { layoutParams = LinearLayout.LayoutParams(MP, 0, 1f) }
        fun pageOf(aspect: Float, vararg v: View) = LinearLayout(this).apply { layoutParams = FrameLayout.LayoutParams(MP, MP); for (x in v) addView(fit(x, aspect, .88f)) }
        val mouseB = tileV(R.drawable.i_mouse, "Mouse", 0, 0); tap(mouseB) { showTrack() }
        val kbdB = tileV(R.drawable.i_kbd, "Keyboard", 0, 0); tap(kbdB) { typeDialog() }
        val padB = tileV(R.drawable.i_pad, "Gamepad", 0, 0); tap(padB) { showPad() }
        val fileB = tileV(R.drawable.i_file, "Send file", 0, 0); tap(fileB) { pickFiles() }
        val accB = tileV(R.drawable.i_access, "Accessibility", 0, 0); tap(accB) { accessTile() }
        accIcon = accB.getChildAt(0) as ImageView; accText = accB.getChildAt(1) as TextView
        // Tray: always [Keyboard + Accessibility]. When the TV reports Accessibility is ON it also gets [Favourite apps] and [Mouse, Gamepad, Share].
        basePage = pageOf(1f, kbdB, accB)
        allPages = listOf(basePage, pageOf(1f, appSlot(0), appSlot(1), appSlot(2)), pageOf(1f, mouseB, padB, fileB))
        for (p in allPages) { p.visibility = View.GONE; dockHost.addView(p) }
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(MP, dp(12)) }
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(4)); background = glossy(40)
            addView(dockHost)
            addView(dots)
        }
        col.addView(rowW(dock, 1.35f))

        main = col
        root.addView(col, FrameLayout.LayoutParams(MP, MP))
        setContentView(root)
        applyPages(); updateSlots()
    }

    override fun onStart() {
        super.onStart()
        if (ImeLink.paired && !ImeLink.active) startLink()      // a paired TV connects right away, no need to tap Connect
        Link.onState = { runOnUiThread { refresh() } }
        Link.onToast = { m -> toast(m) }
        refresh()
    }

    override fun onStop() { Link.onState = null; Link.onToast = null; super.onStop() }

    private fun refresh() {
        applyPages()
        if (Link.connected) {
            pillTxt.text = "Connected"; pillTxt.setTextColor(GREEN)
            pillBg.setStroke(dp(1), GREEN)
            disc.text = "${Link.tvName}  -  Disconnect"
            h.postDelayed({ if (Link.connected) { pill.visibility = View.GONE; disc.visibility = View.VISIBLE } }, 700)
        } else {
            pill.visibility = View.VISIBLE; disc.visibility = View.GONE
            pillTxt.text = if (Link.connecting) "Searching for TV..." else "Connect to Receiver"
            pillTxt.setTextColor(Color.WHITE); pillBg.setStroke(dp(1), PURPLE)
        }
        if (Link.connected) disc.text = "${Link.tvName}  -  Disconnect"
    }

    private fun connect() {
        if (Link.connected || Link.connecting) return
        if (!ImeLink.paired) { ImePairUi(this, Link.host.ifEmpty { prefs.getString("last", "") ?: "" }) { startLink(); refresh() }.start(); return }   // first time: find the TV + PIN
        startLink(); refresh()
    }

    private fun disconnect() { startService(Intent(this, RemoteService::class.java).setAction(RemoteService.ACT_DISCONNECT)) }

    /** Asks the TV receiver to close every background app (the receiver and the remote link itself are kept). */
    private fun clearAll() {
        if (!Link.connected) { Link.send(""); return }
        Link.send("CLEAR")
        toast("Clearing TV background apps...")
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    private fun ipDialog() {
        val s = Sheet(this).title("TV address", "Shown at the top of the receiver app on the TV").cancelable(true)
        val et = s.input("e.g. 192.168.1.20", Link.host.ifEmpty { prefs.getString("last", "") ?: "" })
        s.secondary("Control mode") { s.dismiss(); transportDialog() }.primary("Pair") {
            val h = et.text.toString().trim()
            prefs.edit().putString("last", h).apply()
            s.dismiss()
            if (h.isNotEmpty()) ImePairUi(this, h) { startLink(); refresh() }.startAt(h)
        }.show()
    }

    // ---------- Control mode: status + shortcuts ----------
    private fun transportDialog() {
        val ui = ImePairUi(this, Link.host.ifEmpty { prefs.getString("last", "") ?: "" }) { startLink(); refresh() }
        val s = Sheet(this).title("Control mode", "How this phone controls the TV").cancelable(true)
        when {
            ImeLink.ready -> s.chip("TV keyboard  " + (if (ImeLink.inputActive) "Connected, field focused" else "Connected"), Sheet.GREEN)
            ImeLink.paired -> s.chip("TV keyboard  Paired, TV not reachable", Sheet.AMBER)
            else -> s.chip("TV keyboard  Not paired", Sheet.DIM)
        }
        s.chip("Accessibility  " + (if (ImeLink.a11y) "On" else "Off"), if (ImeLink.a11y) Sheet.GREEN else Sheet.DIM)
        s.row(R.drawable.ic_wifi, if (ImeLink.paired) "Pair another TV" else "Pair TV keyboard", "Find the TV on this Wi-Fi and enter its PIN") { s.dismiss(); ui.start() }
        if (ImeLink.tvs().size >= 2) s.row(R.drawable.i_kbd, "Switch TV", "Now: " + ImeLink.tvName.ifBlank { "TV" }) { s.dismiss(); ui.chooseActive() }
        if (ImeLink.ready) {
            s.row(R.drawable.i_access, "Accessibility shortcut", if (ImeLink.a11y) "On - opens the TV's Accessibility settings" else "Opens the TV's Accessibility settings, turn the receiver ON") { s.dismiss(); Link.send("A11YSET") }
            s.row(R.drawable.i_gear, "Wi-Fi settings on TV", "Same as holding the gear button") { s.dismiss(); Link.send("W") }
        }
        if (ImeLink.paired) s.row(R.drawable.i_close, "Forget TV keyboards", "Remove every paired TV from this phone", Sheet.RED) { s.dismiss(); ImeLink.forget(); toast("TV keyboards removed"); refresh() }
        s.secondary("Close").show()
    }

    private fun startLink() {
        val i = Intent(this, RemoteService::class.java).setAction(RemoteService.ACT_CONNECT)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    // ---------- app slots (not fixed: you pick any installed TV app) ----------
    private fun appSlot(i: Int): View {
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(10), dp(10), dp(10), dp(10)) }
        slotImg[i] = img
        return FrameLayout(this).apply {
            background = glossy(16)
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply { setMargins(dp(5), 0, dp(5), 0) }
            addView(img, FrameLayout.LayoutParams(MP, MP))
            tap(this, long = { pick(i) }) {
                val p = prefs.getString("slotp$i", null)
                if (p == null) pick(i) else Link.send("L $p")
            }
        }
    }

    private fun updateSlots() {
        for (i in 0..5) {
            val img = slotImg[i] ?: continue
            val b64 = prefs.getString("icon$i", null)
            if (b64 != null) {
                val raw = Base64.decode(b64, Base64.NO_WRAP)
                img.clearColorFilter(); img.setPadding(dp(6), dp(6), dp(6), dp(6))
                img.setImageBitmap(BitmapFactory.decodeByteArray(raw, 0, raw.size))
            } else {
                img.setPadding(dp(18), dp(18), dp(18), dp(18))
                img.setImageResource(R.drawable.i_plus); img.setColorFilter(0xFFAAAAB5.toInt())
            }
        }
    }

    private fun pick(i: Int) {
        if (!Link.connected) { Link.send(""); return }
        Link.onApps = { list ->
            Link.onApps = null
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Choose an app")
                    .setItems(list.map { it.first }.toTypedArray()) { _, w ->
                        val (name, pkg) = list[w]
                        prefs.edit().putString("slot$i", name).putString("slotp$i", pkg).remove("icon$i").apply()
                        updateSlots()
                        Link.onIcon = { p, b64 ->
                            if (p == pkg) { prefs.edit().putString("icon$i", b64).apply(); runOnUiThread { updateSlots() } }
                        }
                        Link.send("ICON $pkg")
                    }.show()
            }
        }
        Link.send("LIST")
    }

    // ---------- keyboard ----------
    private fun typeDialog() {
        Link.send("FIELD")   // ask the receiver to focus / open the text field on screen before typing
        val et = EditText(this).apply { hint = "Type here, then Send" }
        val d = AlertDialog.Builder(this).setTitle("Type on TV").setView(et)
            .setPositiveButton("Send") { _, _ -> sendText(et.text.toString(), false) }
            .setNeutralButton("Send + Enter") { _, _ -> sendText(et.text.toString(), true) }
            .setNegativeButton("Cancel", null).create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    private fun sendText(t: String, enter: Boolean) {
        if (t.isNotEmpty()) Link.send("T " + URLEncoder.encode(t, "UTF-8"))
        if (enter) Link.send("K 66")
    }

    // ---------- voice ----------
    /** Speech is recognised on the phone and the text is typed into the TV's search box (works over Wi-Fi alone). */
    private fun voice() {
        if (!Link.connected) { Link.send(""); return }
        try {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak - it goes to the TV")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1), 8)
        } catch (e: Exception) { toast("No speech recognizer on this phone (install / enable the Google app)") }
    }

    // ---------- send files ----------
    private fun pickFiles() {
        if (!Link.connected) { Link.send(""); return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 7)
    }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        if (rc == 8) {
            if (res != RESULT_OK || d == null) return
            val said = d.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
            if (said.isNullOrEmpty()) { toast("Didn't catch that"); return }
            toast("Heard: $said")
            Link.send("FIELD")                                                       // put the cursor in the TV's search box
            h.postDelayed({ Link.send("V " + URLEncoder.encode(said, "UTF-8")) }, 450)   // replace its text
            h.postDelayed({ Link.send("K 66") }, 1100)                                // submit
            return
        }
        if (rc != 7 || res != RESULT_OK || d == null) return
        val uris = ArrayList<android.net.Uri>()
        d.data?.let { uris.add(it) }
        d.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
        val host = Link.host
        val cr = contentResolver
        val list = uris.distinct()
        val ui = XferUi(this)
        ui.show(list.size)
        Thread {
            var okN = 0; var badN = 0
            for ((n, u) in list.withIndex()) {
                var name = "file"
                try {
                    var size = -1L
                    cr.query(u, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (ni >= 0) name = c.getString(ni)
                            val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE); if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                        }
                    }
                    if (size < 0) size = cr.openAssetFileDescriptor(u, "r")?.use { it.length } ?: -1L
                    if (size < 0) { ui.done(name, u, false); badN++; continue }
                    ui.begin(name, size, n + 1)
                    val ticket = Link.fileTicket()                                   // one-time pass from the TV, asked over the encrypted link
                    if (ticket == null) { ui.done(name, u, false); badN++; continue }
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(host, 8767), 3000)
                        s.soTimeout = 120000
                        val out = java.io.DataOutputStream(s.getOutputStream().buffered(65536))
                        out.writeUTF(ticket); out.writeUTF(name); out.writeLong(size)
                        var sent = 0L
                        val buf = ByteArray(65536)
                        cr.openInputStream(u)!!.use { inp ->
                            while (true) { val r = inp.read(buf); if (r < 0) break; out.write(buf, 0, r); sent += r; ui.progress(sent) }
                        }
                        out.flush()
                        val ok = s.getInputStream().read() == 1
                        ui.done(name, u, ok); if (ok) okN++ else badN++
                    }
                } catch (e: Exception) { ui.done(name, u, false); badN++ }
            }
            ui.finish(okN, badN)
        }.start()
    }

    // ---------- full-screen trackpad / air mouse ----------
    private fun showTrack() {
        if (!Link.connected) { Link.send(""); return }
        if (track == null) {
            val f = FrameLayout(this).apply { setBackgroundColor(DARK); isClickable = true; visibility = View.GONE }
            val p = Pad(this); trackPad = p
            f.addView(p, FrameLayout.LayoutParams(MP, MP))
            val done = label("Done", 15f); tap(done) { hideLayers() }
            f.addView(done, FrameLayout.LayoutParams(dp(96), dp(44), Gravity.TOP or Gravity.START).apply { setMargins(dp(16), dp(40), 0, 0) })
            val bar = LinearLayout(this)
            for ((t, act) in listOf<Pair<String, () -> Unit>>("Click" to { Link.send("C") }, "Back" to { Link.send("K 4") }, "Home" to { Link.send("K 3") })) {
                val b = label(t, 15f).apply { layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(6), 0, dp(6), 0) } }
                tap(b) { act() }; bar.addView(b)
            }
            f.addView(bar, FrameLayout.LayoutParams(MP, WC, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(24)) })
            root.addView(f, FrameLayout.LayoutParams(MP, MP)); track = f
        }
        trackPad?.sub = "tap = click  -  2-finger tap = back  -  2-finger slide = scroll"
        track?.visibility = View.VISIBLE
        Link.send("MOUSE 1")
    }

    private fun hideLayers() {
        track?.visibility = View.GONE; gamepad?.visibility = View.GONE; padEdit?.visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        Link.send("MOUSE 0")
    }

    override fun onBackPressed() {
        if (padEdit?.visibility == View.VISIBLE) padEdit?.visibility = View.GONE
        else if (track?.visibility == View.VISIBLE || gamepad?.visibility == View.VISIBLE) hideLayers() else super.onBackPressed()
    }

    // ---------- PSP-style gamepad (landscape) ----------
    private fun showPad() {
        if (!Link.connected) { Link.send(""); return }
        if (gamepad == null) { gamepad = buildPad(); root.addView(gamepad, FrameLayout.LayoutParams(MP, MP)) }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        gamepad?.visibility = View.VISIBLE
        gamepad?.post { applyPadLayout() }
        Link.send("MOUSE 1")
    }

    private fun buildPad(): FrameLayout {
        val f = FrameLayout(this).apply { setBackgroundColor(DARK); isClickable = true; visibility = View.GONE }
        fun place(v: View, w: Int, hh: Int, g: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0, id: String? = null) {
            f.addView(v, FrameLayout.LayoutParams(dp(w), dp(hh), g).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) })
            if (id != null) padCtl[id] = v
        }
        val lb = label("L", 16f); key(lb, 102); place(lb, 130, 44, Gravity.TOP or Gravity.START, 24, 14, id = "L")
        val rb = label("R", 16f); key(rb, 103); place(rb, 130, 44, Gravity.TOP or Gravity.END, 0, 14, 24, id = "R")
        val x = ic(R.drawable.i_close, Color.WHITE, 10).apply { background = glossy(0, true) }; tap(x) { hideLayers() }
        place(x, 44, 44, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 14)
        val ed = label("Edit", 13f); tap(ed) { padEditMode() }
        place(ed, 64, 36, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 130, 18)

        val cross = FrameLayout(this)
        fun arrow(res: Int, code: Int, g: Int) {
            val v = ic(res, Color.WHITE, 8).apply { background = glossy(14) }
            cross.addView(v, FrameLayout.LayoutParams(dp(50), dp(50), g)); key(v, code, true)
        }
        arrow(R.drawable.i_up, 19, Gravity.TOP or Gravity.CENTER_HORIZONTAL); arrow(R.drawable.i_down, 20, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        arrow(R.drawable.i_left, 21, Gravity.START or Gravity.CENTER_VERTICAL); arrow(R.drawable.i_right, 22, Gravity.END or Gravity.CENTER_VERTICAL)
        place(cross, 150, 150, Gravity.TOP or Gravity.START, 40, 64, id = "dpad")

        val faces = FrameLayout(this)
        fun face(kind: Int, code: Int, g: Int) {
            val v = Face(this, kind).apply { background = glossy(0, true) }
            faces.addView(v, FrameLayout.LayoutParams(dp(54), dp(54), g)); key(v, code)
        }
        face(0, 100, Gravity.TOP or Gravity.CENTER_HORIZONTAL); face(1, 97, Gravity.END or Gravity.CENTER_VERTICAL)
        face(2, 96, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL); face(3, 99, Gravity.START or Gravity.CENTER_VERTICAL)
        place(faces, 150, 150, Gravity.TOP or Gravity.END, 0, 64, 40, id = "faces")

        place(Stick(this), 110, 110, Gravity.BOTTOM or Gravity.START, 60, 0, 0, 12, id = "stick")
        val sel = label("SELECT", 13f); key(sel, 109); place(sel, 92, 38, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, -110, 0, 0, 22, id = "select")
        val st = label("START", 13f); key(st, 108); place(st, 92, 38, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 110, 0, 0, 22, id = "start")
        f.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) applyPadLayout()
        }
        return f
    }

    // ---------- gamepad layout editor: drag = move, slider = resize, saved on the phone ----------
    private fun applyPadLayout() {
        val f = gamepad ?: return
        if (f.width == 0 || f.height == 0) return
        for ((id, v) in padCtl) {
            val p = prefs.getString("pad_$id", null)?.split(",")
            if (p != null && p.size == 3) {
                val fx = p[0].toFloatOrNull(); val fy = p[1].toFloatOrNull(); val sc = p[2].toFloatOrNull()
                if (fx != null && fy != null && sc != null) {
                    v.translationX = fx * f.width; v.translationY = fy * f.height; v.scaleX = sc; v.scaleY = sc
                    continue
                }
            }
            v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f
        }
        padEdit?.invalidate()
    }

    private fun savePad() {
        val f = gamepad ?: return
        if (f.width == 0 || f.height == 0) return
        val e = prefs.edit()
        for ((id, v) in padCtl) e.putString("pad_$id", "${v.translationX / f.width},${v.translationY / f.height},${v.scaleX}")
        e.apply()
    }

    private fun hitPad(x: Float, y: Float): String? {
        val slack = dp(14).toFloat()
        for ((id, v) in padCtl.entries.reversed()) {
            val cx = v.left + v.width / 2f + v.translationX
            val cy = v.top + v.height / 2f + v.translationY
            val hw = v.width * v.scaleX / 2f + slack; val hh = v.height * v.scaleY / 2f + slack
            if (x >= cx - hw && x <= cx + hw && y >= cy - hh && y <= cy + hh) return id
        }
        return null
    }

    private fun keepOnScreen(v: View, f: View) {
        val cx = v.left + v.width / 2f + v.translationX
        val cy = v.top + v.height / 2f + v.translationY
        v.translationX += cx.coerceIn(0f, f.width.toFloat()) - cx
        v.translationY += cy.coerceIn(0f, f.height.toFloat()) - cy
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun padEditMode() {
        val f = gamepad ?: return
        var layer = padEdit
        if (layer == null) {
            val ly = EditLayer(this).also { it.isClickable = true }
            ly.controls = padCtl.values
            val title = TextView(this).apply { text = "Drag a button to move it. Tap it, then use the slider to resize."; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER }
            val sb = SeekBar(this).apply { max = 170; progress = 50 }   // scale 0.5x .. 2.2x
            padBar = sb
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                    if (!user) return
                    val v = padSel?.let { padCtl[it] } ?: return
                    val sc = 0.5f + p / 100f
                    v.scaleX = sc; v.scaleY = sc; keepOnScreen(v, f); ly.invalidate()
                }
                override fun onStartTrackingTouch(s: SeekBar?) { }
                override fun onStopTrackingTouch(s: SeekBar?) { savePad() }
            })
            val reset = label("Reset", 14f).apply { layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(4), dp(10), dp(4), 0) } }
            val done = label("Done", 14f).apply { layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(4), dp(10), dp(4), 0) } }
            tap(reset) {
                val e = prefs.edit(); for (id in padCtl.keys) e.remove("pad_$id"); e.apply()
                applyPadLayout(); sb.progress = 50; ly.target = null; padSel = null; ly.invalidate()
            }
            tap(done) { savePad(); ly.visibility = View.GONE }
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = glossy(18)
                addView(title); addView(sb, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(8) })
                addView(LinearLayout(this@MainActivity).apply { addView(reset); addView(done) })
            }
            ly.addView(panel, FrameLayout.LayoutParams(dp(300), WC, Gravity.CENTER))
            var lx = 0f; var lyy = 0f
            ly.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val id = hitPad(e.x, e.y)
                        padSel = id
                        val v = id?.let { padCtl[it] }
                        ly.target = v
                        if (v != null) { lx = e.x; lyy = e.y; sb.progress = ((v.scaleX - 0.5f) * 100f).toInt().coerceIn(0, 170) }
                        ly.invalidate()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val v = padSel?.let { padCtl[it] }
                        if (v != null) {
                            v.translationX += e.x - lx; v.translationY += e.y - lyy
                            lx = e.x; lyy = e.y
                            keepOnScreen(v, f); ly.invalidate()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> savePad()
                }
                true
            }
            f.addView(ly, FrameLayout.LayoutParams(MP, MP))
            padEdit = ly; layer = ly
        }
        padSel = null; layer.target = null
        layer.visibility = View.VISIBLE
        layer.invalidate()
    }

    // ---------- phone volume buttons while the app is open ----------
    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (Link.connected && (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            Link.send("K " + if (k == KeyEvent.KEYCODE_VOLUME_UP) 24 else 25); return true
        }
        return super.onKeyDown(k, e)
    }

    override fun onKeyUp(k: Int, e: KeyEvent): Boolean {
        if (Link.connected && (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(k, e)
    }
}

/** Vertical volume pill: drag up/down = vol up/down. */
class VolView(c: Context) : View(c) {
    private var level = 0.5f
    private var acc = 0f
    private var ly = 0f
    private val d = c.resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E1E26.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEDF5.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3C3C4A.toInt(); style = Paint.Style.STROKE; strokeWidth = d }
    private val icon = c.resources.getDrawable(R.drawable.i_volup, null)

    override fun onDraw(c: Canvas) {
        val r = width / 2f
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, r, r, bg)
        c.save(); val clip = Path(); clip.addRoundRect(rect, r, r, Path.Direction.CW); c.clipPath(clip)
        c.drawRect(0f, height * (1 - level), width.toFloat(), height.toFloat(), fill); c.restore()
        c.drawRoundRect(rect, r, r, edge)
        val s = (26 * d).toInt(); val cx = width / 2; val cy = height / 2
        icon.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        icon.setTint(if (level > 0.5f) 0xFF202028.toInt() else Color.WHITE); icon.draw(c)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); ly = e.y; acc = 0f }
            MotionEvent.ACTION_MOVE -> {
                val dy = ly - e.y; ly = e.y
                level = min(1f, max(0f, level + dy / height)); acc += dy
                val step = height / 12f
                while (abs(acc) >= step) {
                    Link.send(if (acc > 0) "K 24" else "K 25")
                    acc -= if (acc > 0) step else -step
                }
                invalidate()
            }
        }
        return true
    }
}

/** Touchpad: drag = move, tap = click, 2-finger tap = Back, 2-finger drag = scroll. */
class Pad(c: Context) : View(c) {
    var sub = ""
    private var lx = 0f; private var ly = 0f
    private var accX = 0f; private var accY = 0f; private var scr = 0f; private var scrX = 0f; private var total = 0f
    private var downAt = 0L; private var maxP = 1
    private val d = c.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF55555F.toInt(); textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        paint.textSize = 18 * d; c.drawText("Touchpad", width / 2f, height / 2f - 6 * d, paint)
        paint.textSize = 12 * d; c.drawText(sub, width / 2f, height / 2f + 18 * d, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val n = e.pointerCount
        var ax = 0f; var ay = 0f
        for (i in 0 until n) { ax += e.getX(i); ay += e.getY(i) }
        ax /= n; ay /= n
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lx = ax; ly = ay; total = 0f; accX = 0f; accY = 0f; scr = 0f; scrX = 0f; maxP = 1; downAt = e.eventTime
            }
            MotionEvent.ACTION_POINTER_DOWN -> { maxP = max(maxP, n); lx = ax; ly = ay }
            MotionEvent.ACTION_POINTER_UP -> {
                var sx = 0f; var sy = 0f; var k = 0
                for (i in 0 until n) { if (i == e.actionIndex) continue; sx += e.getX(i); sy += e.getY(i); k++ }
                if (k > 0) { lx = sx / k; ly = sy / k }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ax - lx; val dy = ay - ly
                lx = ax; ly = ay
                total += abs(dx) + abs(dy)
                if (n >= 2) {
                    scr += dy; scrX += dx
                    if (abs(scr) > 24 * d || abs(scrX) > 24 * d) {          // 2-finger slide: up/down AND left/right
                        val horiz = abs(scrX) > abs(scr)
                        Link.send("S " + (if (horiz) scrX.toInt() else 0) + " " + (if (horiz) 0 else scr.toInt()))
                        scr = 0f; scrX = 0f
                    }
                } else {
                    accX += dx * 2.4f; accY += dy * 2.4f
                    val ix = accX.toInt(); val iy = accY.toInt()
                    if (ix != 0 || iy != 0) { Link.send("M $ix $iy"); accX -= ix; accY -= iy }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (total < 12 * d && e.eventTime - downAt < 280) Link.send(if (maxP >= 2) "K 4" else "C")
            }
        }
        return true
    }
}

/** Analog nub: moves the TV cursor while held. */
class Stick(c: Context) : View(c) {
    private var nx = 0f; private var ny = 0f
    private var ax = 0f; private var ay = 0f
    private var on = false
    private val hh = Handler(Looper.getMainLooper())
    private val d = c.resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1B23.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3C3C4A.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEDF5.toInt() }
    private val tick = object : Runnable {
        override fun run() {
            if (!on) return
            ax += nx * 22f * sqrt(nx * nx + ny * ny); ay += ny * 22f * sqrt(nx * nx + ny * ny)
            val ix = ax.toInt(); val iy = ay.toInt()
            if (ix != 0 || iy != 0) { Link.send("M $ix $iy"); ax -= ix; ay -= iy }
            hh.postDelayed(this, 16)
        }
    }

    override fun onDraw(c: Canvas) {
        val r = width / 2f
        c.drawCircle(r, r, r - d, ring); c.drawCircle(r, r, r - d, edge)
        c.drawCircle(r + nx * r * 0.45f, r + ny * r * 0.45f, r * 0.42f, knob)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = width / 2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var dx = (e.x - r) / (r * 0.6f); var dy = (e.y - r) / (r * 0.6f)
                val m = sqrt(dx * dx + dy * dy)
                if (m > 1f) { dx /= m; dy /= m }
                nx = dx; ny = dy
                if (!on) { on = true; hh.post(tick) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { nx = 0f; ny = 0f; on = false; ax = 0f; ay = 0f }
        }
        invalidate()
        return true
    }
}

/** PSP face-button glyphs: triangle, circle, cross, square. */
class Face(c: Context, private val kind: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.5f * c.resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND
        color = intArrayOf(0xFF4CD964.toInt(), 0xFFFF5252.toInt(), 0xFF5AA9FF.toInt(), 0xFFFF80AB.toInt())[kind]
    }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = width * 0.24f
        when (kind) {
            0 -> { val t = Path(); t.moveTo(cx, cy - r); t.lineTo(cx + r, cy + r * .8f); t.lineTo(cx - r, cy + r * .8f); t.close(); c.drawPath(t, p) }
            1 -> c.drawCircle(cx, cy, r, p)
            2 -> { c.drawLine(cx - r, cy - r, cx + r, cy + r, p); c.drawLine(cx + r, cy - r, cx - r, cy + r, p) }
            else -> c.drawRect(cx - r, cy - r, cx + r, cy + r, p)
        }
    }
}

/** Square-ish child centred in its cell, sized from the cell => identical look on every screen size. */
class Fit(c: Context, private val aspect: Float, private val ratio: Float) : FrameLayout(c) {
    override fun onMeasure(wm: Int, hm: Int) {
        val w = MeasureSpec.getSize(wm); val h = MeasureSpec.getSize(hm)
        setMeasuredDimension(w, h)
        val s = (min(w / aspect, h.toFloat()) * ratio).toInt()
        val cw = MeasureSpec.makeMeasureSpec((s * aspect).toInt(), MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }
}

/** Icon whose padding is a fraction of its own size. */
class RIcon(c: Context, res: Int, tint: Int, private val frac: Float) : ImageView(c) {
    init { setImageResource(res); setColorFilter(tint); scaleType = ScaleType.FIT_CENTER }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val p = (min(w, h) * frac).toInt(); setPadding(p, p, p, p)
    }
}

/** Container that takes over horizontal drags (children keep their taps / long-presses) and reports finger-follow + fling. */
class SwipeHost(c: Context, private val onDrag: (Float) -> Unit, private val onEnd: (Float, Float) -> Unit) : FrameLayout(c) {
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var drag = false
    private var vt: VelocityTracker? = null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; drag = false; vt?.recycle(); vt = VelocityTracker.obtain(); vt!!.addMovement(e) }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX; val dy = e.y - downY
                if (!drag && abs(dx) > slop && abs(dx) > abs(dy)) { drag = true; parent?.requestDisallowInterceptTouchEvent(true) }
            }
        }
        return drag
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; drag = false; vt?.recycle(); vt = VelocityTracker.obtain(); vt!!.addMovement(e) }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX
                if (!drag && abs(dx) > slop && abs(dx) > abs(e.y - downY)) { drag = true; parent?.requestDisallowInterceptTouchEvent(true) }
                if (drag) onDrag(dx)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag) {
                    vt?.computeCurrentVelocity(1000)
                    onEnd(if (e.actionMasked == MotionEvent.ACTION_UP) e.x - downX else 0f, vt?.xVelocity ?: 0f)
                }
                drag = false; vt?.recycle(); vt = null
            }
        }
        return true
    }
}

/** Dimmed layer over the gamepad while editing: outlines every movable control, highlights the selected one. */
class EditLayer(c: Context) : FrameLayout(c) {
    var target: View? = null
    var controls: Collection<View> = emptyList()
    private val d = c.resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x66000000 }
    private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0x88FFFFFF.toInt(); pathEffect = DashPathEffect(floatArrayOf(10f * d, 8f * d), 0f) }
    private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = 0xFFFFD54F.toInt() }
    init { setWillNotDraw(false) }

    private fun box(c: Canvas, v: View, p: Paint) {
        val cx = v.left + v.width / 2f + v.translationX; val cy = v.top + v.height / 2f + v.translationY
        val hw = v.width * v.scaleX / 2f; val hh = v.height * v.scaleY / 2f
        c.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh, 14f * d, 14f * d, p)
    }

    override fun dispatchDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        for (v in controls) box(c, v, faint)
        target?.let { box(c, it, sel) }
        super.dispatchDraw(c)
    }
}
