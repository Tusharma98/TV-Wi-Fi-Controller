package app.tvremote.ime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.tvremote.imeproto.ImeProto
import java.net.Inet4Address
import java.net.NetworkInterface

/** Setup + pairing screen, drivable with the TV remote. Steps: enable the keyboard, select it, pair a phone. */
class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var addr: TextView
    private lateinit var pin: TextView
    private lateinit var s1: TextView
    private lateinit var s2: TextView
    private lateinit var s3: TextView
    private lateinit var s4: TextView
    private lateinit var next: TextView
    private lateinit var info: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun box(fill: Int, stroke: Int, w: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(14).toFloat(); setStroke(dp(w), stroke) }
    private fun txt(s: String, size: Float, c: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply { text = s; textSize = size; setTextColor(c); if (bold) typeface = Typeface.DEFAULT_BOLD }

    private fun card(t: TextView, onClick: () -> Unit): TextView = t.apply {
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), box(0xFF26335E.toInt(), 0xFF4CD964.toInt(), 3))
            addState(intArrayOf(), box(0xFF182038.toInt(), 0xFF2C3659.toInt(), 1))
        }
        isFocusable = true; isClickable = true
        setOnClickListener { onClick(); refresh() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }

    private fun ours(): Boolean = try {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.any { it.packageName == packageName }
    } catch (e: Exception) { false }

    private fun selected(): Boolean = try {
        (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith("$packageName/")
    } catch (e: Exception) { false }

    private fun go(i: Intent) = try { startActivity(i); true } catch (e: Exception) { false }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ImeHost.ensure(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(28), dp(24), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0B1020.toInt(), 0xFF16224A.toInt()))
        }
        col.addView(txt("TV Remote Keyboard", 30f, Color.WHITE, true))
        status = txt("", 15f, Color.BLACK, true).apply { background = box(0xFF4CD964.toInt(), 0xFF4CD964.toInt(), 1); setPadding(dp(18), dp(6), dp(18), dp(6)); layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) } }
        col.addView(status)
        addr = txt("", 20f, 0xFF9AA5C4.toInt()).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(14), 0, dp(6)) }
        col.addView(addr)
        pin = txt("", 40f, Color.WHITE, true).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
        col.addView(pin)                                   // visible without scrolling, also on a phone
        next = card(txt("", 20f, Color.BLACK, true)) { doNext() }
        next.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), box(0xFF8DF2A3.toInt(), Color.WHITE, 3))
            addState(intArrayOf(), box(0xFF4CD964.toInt(), 0xFF4CD964.toInt(), 1))
        }
        next.gravity = Gravity.CENTER
        col.addView(next)
        info = txt("", 14f, 0xFF9AA5C4.toInt()).apply { setPadding(0, dp(12), 0, 0) }
        col.addView(info)
        s1 = card(txt("", 18f)) { if (!go(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))) Toast.makeText(this, "Open TV Settings > Keyboard", Toast.LENGTH_LONG).show() }
        s2 = card(txt("", 18f)) { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        s3 = card(txt("", 18f)) { ImeHost.server()?.openPairing() }
        s4 = card(txt("", 18f)) { if (!go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) Toast.makeText(this, "Open Settings > Accessibility", Toast.LENGTH_LONG).show() }
        col.addView(s1); col.addView(s2); col.addView(s3); col.addView(s4)
        col.addView(card(txt("Forget all phones", 16f)) { ImeHost.server()?.forgetAll(); Toast.makeText(this, "All phones removed", Toast.LENGTH_SHORT).show() })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(col) })     // phones: the list scrolls
        next.requestFocus()
    }

    private val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1000) } }
    override fun onResume() { super.onResume(); ImeHost.ensure(this); ImeHost.onChanged = { h.post { refresh() } }; h.post(tick) }
    override fun onPause() { h.removeCallbacks(tick); ImeHost.onChanged = null; super.onPause() }

    /** Wi-Fi / hotspot / ethernet address first; mobile-data and VPN interfaces last. */
    private fun ip(): String = try {
        val all = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { n ->
            n.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { n.name to (it.hostAddress ?: "") }
        }
        fun rank(n: String) = when { n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") -> 0; n.startsWith("eth") -> 1; n.startsWith("rmnet") || n.startsWith("tun") || n.startsWith("ccmni") -> 9; else -> 5 }
        all.sortedBy { rank(it.first) }.joinToString("\n") { it.second }.ifBlank { "no network - join the same Wi-Fi / hotspot as the controller" }
    } catch (e: Exception) { "no network" }

    /** One button that always does the next missing thing. */
    private fun doNext() {
        val srv = ImeHost.server()
        when {
            !ours() -> if (!go(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))) Toast.makeText(this, "Open Settings > System > Languages & input > On-screen keyboard", Toast.LENGTH_LONG).show()
            !selected() -> (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            else -> srv?.openPairing()
        }
    }

    private fun refresh() {
        val srv = ImeHost.server()
        val en = ours(); val sel = selected()
        val up = (srv?.boundPort ?: -1) > 0
        val who = srv?.activeClient?.let { id -> ImeHost.all().firstOrNull { it.clientId == id }?.name?.ifBlank { null } ?: "phone" }
        status.text = when { !up -> "SERVER NOT RUNNING"; who != null -> "CONNECTED: $who"; sel -> "READY"; else -> "SETUP NEEDED" }
        addr.text = ip() + "\nport ${ImeProto.PORT}"
        next.text = when { !en -> "STEP 1: ENABLE THE KEYBOARD"; !sel -> "STEP 2: SELECT THE KEYBOARD"; else -> "PAIR A NEW PHONE" }
        info.text = when {
            !up -> "Port ${ImeProto.PORT} is busy. Close other copies of this app, then reopen it."
            !en || !sel -> "Controller can type only while this keyboard is the selected one. Come back here after each step."
            else -> "Ready. Open any text field on this device, then pair / use the controller on the same Wi-Fi or hotspot." +
                (ImeHost.all().takeIf { it.isNotEmpty() }?.joinToString(prefix = "\nPaired: ") { it.name.ifBlank { "phone" } } ?: "")
        }
        s4.text = (if (A11y.inst != null) "✓  " else "+  ") + "Accessibility (optional)" + (if (A11y.inst != null) "  -  on: mouse, gamepad, apps, file receive" else "  -  tap, turn on 'TV Remote Keyboard' to unlock mouse, gamepad, apps, files")
        s1.text = (if (en) "✓  " else "1  ") + "Keyboard enabled" + (if (en) "" else "  -  tap to open Keyboard settings")
        s2.text = (if (sel) "✓  " else "2  ") + "Keyboard selected" + (if (sel) "" else "  -  tap and choose TV Remote Keyboard")
        val left = srv?.pairingSecondsLeft() ?: 0
        val n = srv?.pairedCount() ?: 0
        s3.text = "3  Pair a new phone  -  tap ($n paired)"
        pin.text = if (left > 0) "PIN  " + (srv?.pairingPin() ?: "") + "   (" + left + " s)" else ""
    }
}
