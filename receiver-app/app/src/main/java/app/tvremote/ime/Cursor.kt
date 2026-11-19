package app.tvremote.ime

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** On-screen mouse pointer. Uses the accessibility overlay window (no extra permission needed). */
class Cursor(private val ctx: Context) {
    private val h = Handler(Looper.getMainLooper())
    private val dm = ctx.resources.displayMetrics
    @Volatile var x = dm.widthPixels / 2f
    @Volatile var y = dm.heightPixels / 2f
    private var view: View? = null
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private val sz = (44 * dm.density).toInt()

    private inner class Arrow(c: Context) : View(c) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
        private val p = Path().apply {
            moveTo(2f, 2f); lineTo(2f, 34f); lineTo(10f, 26f); lineTo(16f, 40f)
            lineTo(22f, 37f); lineTo(16f, 24f); lineTo(27f, 24f); close()
        }
        override fun onDraw(c: Canvas) {
            c.scale(dm.density, dm.density)
            c.drawPath(p, fill); c.drawPath(p, edge)
        }
    }

    fun show() {
        h.post {
            if (view != null) return@post
            val svc = A11y.inst
            val c: Context
            val type: Int
            if (svc != null) { c = svc; type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
            else if (Settings.canDrawOverlays(ctx)) {
                c = ctx
                type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            } else return@post
            val w = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val lp = WindowManager.LayoutParams(
                sz, sz, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; this.x = this@Cursor.x.toInt(); this.y = this@Cursor.y.toInt() }
            try {
                val v = Arrow(c)
                w.addView(v, lp)
                view = v; wm = w; params = lp
            } catch (e: Exception) { }
        }
    }

    fun hide() {
        h.post {
            view?.let { try { wm?.removeView(it) } catch (e: Exception) { } }
            view = null
        }
    }

    fun move(dx: Float, dy: Float) {
        x = (x + dx).coerceIn(0f, dm.widthPixels - 1f)
        y = (y + dy).coerceIn(0f, dm.heightPixels - 1f)
        if (view == null) show()
        h.post {
            val v = view; val lp = params
            if (v != null && lp != null) {
                lp.x = x.toInt(); lp.y = y.toInt()
                try { wm?.updateViewLayout(v, lp) } catch (e: Exception) { }
            }
        }
    }
}
