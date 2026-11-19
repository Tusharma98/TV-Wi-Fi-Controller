package app.tvremote.ime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs

/**
 * Does everything a TV remote needs over Wi-Fi alone: focus moves, click, scroll, taps, text, Back/Home.
 * v2: works with apps that have no "active window" (system media / file manager), draws its own focus ring when an
 * app refuses real focus, falls back to a real tap, and can open / fill text boxes (search bars).
 */
class A11y : AccessibilityService() {
    companion object { @Volatile var inst: A11y? = null }

    private val h = Handler(Looper.getMainLooper())
    private var virtualFocus = false
    private val dm by lazy { resources.displayMetrics }

    override fun onServiceConnected() {
        inst = this
        ImeHost.ensure(this).capsChanged()           // starts the server if only Accessibility came up, and tells a connected phone "a11y is on"
        ImeHost.onA11yChanged()
    }
    override fun onUnbind(i: Intent?): Boolean { inst = null; hideRing(); ImeHost.server()?.capsChanged(); ImeHost.onA11yChanged(); return super.onUnbind(i) }
    override fun onDestroy() { if (inst === this) inst = null; ImeHost.server()?.capsChanged(); super.onDestroy() }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        // a new screen / dialog opened (not caused by our own move): forget where our cursor was
        if (e?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && SystemClock.uptimeMillis() - lastAt > 600) { lastRect = null; lastNode = null }
    }
    override fun onInterrupt() { }

    fun global(a: Int) { performGlobalAction(a) }

    fun power() {
        if (Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) return
        performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    }

    // ---------- touch / mouse ----------
    fun tap(x: Int, y: Int) = gesture(x, y, x, y, 60)
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) = gesture(x1, y1, x2, y2, 250)

    private fun gesture(x1: Int, y1: Int, x2: Int, y2: Int, dur: Long) {
        val p = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            if (x1 != x2 || y1 != y2) lineTo(x2.toFloat(), y2.toFloat())
        }
        try { dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build(), null, null) } catch (e: Exception) { }
    }

    private fun tapCenter(n: AccessibilityNodeInfo) {
        val r = Rect(); n.getBoundsInScreen(r)
        if (!r.isEmpty) tap(r.centerX(), r.centerY())
    }

    // ---------- windows ----------
    /** Active window first; then other app windows (some TV system apps never report an "active" window). */
    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val ids = HashSet<Int>()
        rootInActiveWindow?.let { if (!isIme(it)) { out.add(it); ids.add(it.windowId) } }
        try {
            for (w in windows.sortedByDescending { it.layer }) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION && w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
                val r = w.root ?: continue
                if (ids.add(r.windowId)) out.add(r)
            }
        } catch (e: Exception) { }
        return out
    }

    private fun isIme(n: AccessibilityNodeInfo): Boolean = try { n.window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } catch (e: Exception) { false }

    // ---------- focus navigation (D-pad) ----------
    private fun scanFocused(n: AccessibilityNodeInfo?, d: Int): AccessibilityNodeInfo? {
        if (n == null || d > 45) return null
        if (n.isFocused && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) scanFocused(n.getChild(i), d + 1)?.let { return it }
        return null
    }

    private fun focused(): AccessibilityNodeInfo? {
        for (r in roots()) {
            val a = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val b = r.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            val f = if (virtualFocus) (b ?: a) else (a ?: b)
            if (f != null) return f
            scanFocused(r, 0)?.let { return it }
        }
        return null
    }

    private fun usable(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser || !n.isEnabled) return false
        val box = n.className?.toString()?.endsWith("EditText") == true
        if (!(n.isFocusable || n.isClickable || n.isLongClickable || n.isEditable || n.isCheckable || box)) return false
        val b = Rect(); n.getBoundsInScreen(b)
        if (b.isEmpty) return false
        // ignore full-screen containers
        if (n.childCount > 0 && b.width().toLong() * b.height() > dm.widthPixels.toLong() * dm.heightPixels * 85 / 100) return false
        return true
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (n == null || depth > 45 || out.size > 700) return
        if (usable(n)) out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    // Our own record of where the focus is. Many TV apps never report the focus we set (or report an old one),
    // and asking them was the reason D-pad moves jumped to the top-left item "randomly".
    private var lastRect: Rect? = null
    private var lastNode: AccessibilityNodeInfo? = null
    @Volatile private var lastAt = 0L

    private fun rectOf(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }

    private fun focusNode(n: AccessibilityNodeInfo) {
        n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        try { n.refresh() } catch (e: Exception) { }
        val r = rectOf(n)
        lastRect = r; lastNode = n; lastAt = SystemClock.uptimeMillis()
        if (n.isFocusable && n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) { virtualFocus = false; hideRing(); return }
        // the app refuses real focus (touch mode / custom focus): use accessibility focus and draw our own ring
        n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        virtualFocus = true
        showRing(r)
    }

    /** The node we are on: our own cursor when the app does not report focus, otherwise what the app reports. */
    private fun current(): AccessibilityNodeInfo? {
        val ln = lastNode
        if (ln != null && virtualFocus && SystemClock.uptimeMillis() - lastAt < 15000) {
            val alive = try { ln.refresh() } catch (e: Exception) { false }
            if (alive) return ln
        }
        return focused()
    }

    private fun gap(a1: Int, a2: Int, b1: Int, b2: Int) = if (b1 < a2 && b2 > a1) 0 else if (b1 >= a2) b1 - a2 else a1 - b2

    /** Candidate lists in priority order: the on-screen keyboard if one is showing, then the first app window with something to focus. */
    private fun scopes(): List<List<AccessibilityNodeInfo>> {
        val out = ArrayList<List<AccessibilityNodeInfo>>()
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val l = ArrayList<AccessibilityNodeInfo>(); collect(w.root, l)
                if (l.isNotEmpty()) { out.add(l); break }
            }
        } catch (e: Exception) { }
        for (r in roots()) {
            val l = ArrayList<AccessibilityNodeInfo>(); collect(r, l)
            if (l.isNotEmpty()) { out.add(l); break }
        }
        return out
    }

    /**
     * Same idea as Android's FocusFinder: the target must lie strictly beyond the current item in the pressed
     * direction (so DOWN never picks a neighbour in the same row or a child inside the item), items in the same
     * "beam" (overlapping columns/rows) win, then the closest one (distance along the direction counts most).
     */
    private fun best(list: List<AccessibilityNodeInfo>, o: Rect, dir: Int): AccessibilityNodeInfo? {
        var pick: AccessibilityNodeInfo? = null
        var bs = Long.MAX_VALUE
        val b = Rect()
        val vertical = dir < 2
        for (n in list) {
            n.getBoundsInScreen(b)
            if (b.isEmpty || b.contains(o) || o.contains(b)) continue      // wrapper of / child inside the current item
            val beyond = when (dir) {
                0 -> (o.bottom > b.bottom || o.top >= b.bottom) && o.top > b.top
                1 -> (o.top < b.top || o.bottom <= b.top) && o.bottom < b.bottom
                2 -> (o.right > b.right || o.left >= b.right) && o.left > b.left
                else -> (o.left < b.left || o.right <= b.left) && o.right < b.right
            }
            if (!beyond) continue
            val inBeam = if (vertical) b.right > o.left && b.left < o.right else b.bottom > o.top && b.top < o.bottom
            val major = when (dir) { 0 -> o.top - b.bottom; 1 -> b.top - o.bottom; 2 -> o.left - b.right; else -> b.left - o.right }.coerceAtLeast(0).toLong()
            val minor = (if (vertical) abs(b.centerX() - o.centerX()) else abs(b.centerY() - o.centerY())).toLong()
            val s = (if (inBeam) 0L else 1L shl 50) + 13L * major * major + minor * minor
            if (s < bs) { bs = s; pick = n }
        }
        return pick
    }

    /** dir: 0 up, 1 down, 2 left, 3 right */
    fun dpad(dir: Int) {
        val sc = scopes()
        if (sc.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val last = lastRect
        val fresh = last != null && now - lastAt < 15000
        val rep = focused()?.let { rectOf(it) }?.takeIf { !it.isEmpty }
        val origin: Rect? = when {
            last != null && now - lastAt < 400 -> last      // our previous move may not be reported by the app yet
            fresh && virtualFocus -> last                    // app does not report our focus: trust our own cursor
            rep != null -> rep
            fresh -> last
            else -> null
        }
        if (origin == null) {                                 // nothing focused yet: start at the top-left item
            val b = Rect()
            sc[0].minByOrNull { n -> n.getBoundsInScreen(b); b.top.toLong() * 100000 + b.left }?.let { focusNode(it) }
            return
        }
        for (list in sc) {
            val pick = best(list, origin, dir)
            if (pick != null) { focusNode(pick); return }
        }
        for (r in roots()) if (scrollDir(dir, r)) return
    }

    private fun scrollDir(dir: Int, n: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 45) return false
        if (n.isScrollable) {
            val act = if (dir == 1 || dir == 3) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (n.performAction(act)) return true
        }
        for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; if (scrollDir(dir, c, depth + 1)) return true }
        return false
    }

    // ---------- OK / menu ----------
    fun ok() {
        val n = current()
        if (n == null) {   // nothing reported: tap where our cursor is
            val r = lastRect
            if (r != null && SystemClock.uptimeMillis() - lastAt < 30000) { hideRing(); tap(r.centerX(), r.centerY()) }
            return
        }
        hideRing()
        val ed = editableOf(n)
        if (ed != null) { openField(ed); return }
        var x: AccessibilityNodeInfo? = n
        var d = 0
        while (x != null && d < 6) {
            if (x.isClickable && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            x = x.parent; d++
        }
        tapCenter(n)   // nothing clickable reported: real tap in the middle of the item
    }

    /** TV "Menu" key ~ context menu = long press on the focused item. */
    fun menu() {
        var n = current()
        var d = 0
        while (n != null && d < 6) {
            if (n.isLongClickable && n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return
            n = n.parent; d++
        }
    }

    // ---------- text boxes / search ----------
    private fun findEditable(n: AccessibilityNodeInfo?, d: Int = 0): AccessibilityNodeInfo? {
        if (n == null || d > 45) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i), d + 1)?.let { return it }
        return null
    }

    private fun findSearchy(n: AccessibilityNodeInfo?, d: Int = 0, skipEditable: Boolean = false): AccessibilityNodeInfo? {
        if (n == null || d > 45) return null
        val t = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase()
        if (n.isVisibleToUser && (n.isClickable || n.isFocusable) && t.contains("search") && !(skipEditable && n.isEditable)) return n
        for (i in 0 until n.childCount) findSearchy(n.getChild(i), d + 1, skipEditable)?.let { return it }
        return null
    }

    /** The text box for this node: itself, a box inside it (search-bar wrapper) or the box it sits in. */
    private fun editableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        findEditable(n)?.let { return it }
        var p = n.parent; var d = 0
        while (p != null && d < 3) { if (p.isEditable) return p; p = p.parent; d++ }
        return null
    }

    /** Focus + click a text box; if the app ignores that, tap it for real so the keyboard / caret appears. */
    private fun openField(n: AccessibilityNodeInfo) {
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (n.isEditable) h.postDelayed({
            try { n.refresh(); if (!n.isFocused) tapCenter(n) } catch (e: Exception) { }
        }, 220)
    }

    /** Phone "FIELD": put the cursor in the text box / search bar of the current screen. */
    fun focusField() {
        val cur = focused()
        if (cur != null && cur.isEditable && cur.isFocused) return
        for (r in roots()) {
            val n = findEditable(r) ?: findSearchy(r) ?: continue
            openField(editableOf(n) ?: n)
            return
        }
    }

    /** Focus the search/type bar on the current page (search key). */
    fun search() = focusField()

    fun typeText(t: String, replace: Boolean = false) {
        var n: AccessibilityNodeInfo? = null
        for (r in roots()) { n = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }; if (n != null) break }
        if (n == null) n = focused()?.let { editableOf(it) }
        if (n == null) {
            var e: AccessibilityNodeInfo? = null
            for (r in roots()) { e = findEditable(r); if (e != null) break }
            if (e == null) return
            openField(e)
            try { Thread.sleep(400) } catch (x: Exception) { }
            n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: e
        }
        val node: AccessibilityNodeInfo = n ?: return
        node.refresh()
        val hint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
        val old = if (replace || hint) "" else (node.text?.toString() ?: "")
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, old + t)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) {
            try {   // app ignores SET_TEXT: paste instead
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("tv", t))
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (e: Exception) { }
        }
    }

    fun enter() {
        val n = current()
        if (n != null && n.isEditable) {
            if (Build.VERSION.SDK_INT >= 30 && n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return
            // older TVs: press the on-screen search / submit button instead
            for (r in roots()) {
                val b = findSearchy(r, 0, true)
                if (b != null && b.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            }
        }
        ok()
    }

    // ---------- own focus ring (for apps that refuse real focus) ----------
    private var ring: View? = null
    private var ringLp: WindowManager.LayoutParams? = null
    private val hideRingRun = Runnable { hideRing() }

    private fun showRing(r: Rect) {
        h.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val lp = ringLp ?: WindowManager.LayoutParams(
                    r.width(), r.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.START }
                lp.x = r.left; lp.y = r.top; lp.width = r.width().coerceAtLeast(8); lp.height = r.height().coerceAtLeast(8)
                val v = ring
                if (v == null) {
                    val nv = object : View(this@A11y) {
                        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = 0xFF4CD964.toInt() }
                        override fun onDraw(c: Canvas) { c.drawRoundRect(4f, 4f, width - 4f, height - 4f, 14f, 14f, p) }
                    }
                    wm.addView(nv, lp); ring = nv; ringLp = lp
                } else wm.updateViewLayout(v, lp)
                h.removeCallbacks(hideRingRun); h.postDelayed(hideRingRun, 6000)
            } catch (e: Exception) { }
        }
    }

    private fun hideRing() {
        h.post {
            val v = ring ?: return@post
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) { }
            ring = null; ringLp = null
        }
    }
}
