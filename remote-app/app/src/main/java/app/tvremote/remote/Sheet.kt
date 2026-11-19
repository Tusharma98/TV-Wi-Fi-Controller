package app.tvremote.remote

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Modern dark bottom sheet in the same style as the remote (glossy dark panels, purple / green accents). Used by Control mode and pairing. */
class Sheet(private val a: Activity) {
    companion object {
        val PANEL = 0xFF14141C.toInt(); val EDGE = 0xFF2E2E3A.toInt(); val ROW = 0xFF1C1C25.toInt()
        val PURPLE = 0xFF6A4FB3.toInt(); val GREEN = 0xFF4CAF50.toInt(); val AMBER = 0xFFFFB300.toInt(); val RED = 0xFFFF3B47.toInt()
        val SOFT = 0xFFDDDDE6.toInt(); val DIM = 0xFF8A8A98.toInt()
    }
    private val d = Dialog(a)
    private val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private var chips: LinearLayout? = null
    private var btns: LinearLayout? = null
    private var sub: TextView? = null
    var onCancel: (() -> Unit)? = null

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
    private fun round(fill: Int, r: Int, stroke: Int = EDGE, w: Int = 1) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(r).toFloat(); setStroke(dp(w), stroke) }

    init {
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = FrameLayout(a).apply {
            background = GradientDrawable().apply { setColor(PANEL); cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f); setStroke(dp(1), EDGE) }
            setPadding(dp(18), dp(10), dp(18), dp(16))
        }
        val handle = View(a).apply { background = round(0xFF3C3C4A.toInt(), 3, 0xFF3C3C4A.toInt(), 0) }
        col.addView(handle, LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        card.addView(ScrollView(a).apply { isFillViewport = false; addView(col) }, FrameLayout.LayoutParams(-1, -2))
        d.setContentView(card)
        d.setOnCancelListener { onCancel?.invoke() }
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM); setDimAmount(.62f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    fun title(t: String, s: String? = null): Sheet {
        col.addView(TextView(a).apply { text = t; setTextColor(Color.WHITE); textSize = 21f; typeface = Typeface.DEFAULT_BOLD })
        if (s != null) sub = TextView(a).apply { text = s; setTextColor(DIM); textSize = 13.5f; setPadding(0, dp(4), 0, 0) }.also { col.addView(it) }
        return this
    }

    /** Small status pill, e.g. "TV keyboard  Connected". */
    fun chip(text: String, color: Int): Sheet {
        if (chips == null) chips = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }.also { col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }) }
        chips!!.addView(TextView(a).apply {
            this.text = "●  $text"; setTextColor(color); textSize = 12.5f; setPadding(dp(12), dp(6), dp(12), dp(6))
            background = round((color and 0x00FFFFFF) or 0x22000000, 16, color, 1)
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        return this
    }

    /** Message line (e.g. an error) under the title. */
    fun note(text: String, color: Int = DIM): Sheet {
        col.addView(TextView(a).apply { this.text = text; setTextColor(color); textSize = 13.5f; setPadding(0, dp(10), 0, 0) })
        return this
    }

    fun row(icon: Int, title: String, subtitle: String? = null, tint: Int = Color.WHITE, onClick: () -> Unit): Sheet {
        val ic = ImageView(a).apply { setImageResource(icon); setColorFilter(tint); scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(10), dp(10), dp(10), dp(10)); background = round(0xFF262631.toInt(), 22, 0xFF262631.toInt(), 0) }
        val tx = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(a).apply { text = title; setTextColor(if (tint == RED) RED else Color.WHITE); textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD })
            if (subtitle != null) addView(TextView(a).apply { text = subtitle; setTextColor(DIM); textSize = 12.5f; setPadding(0, dp(2), 0, 0) })
        }
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(14), dp(10))
            background = round(ROW, 18); isClickable = true
            addView(ic, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(tx, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
            addView(TextView(a).apply { text = "›"; setTextColor(DIM); textSize = 22f })
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(.98f).scaleY(.98f).alpha(.8f).setDuration(60).start()
                    android.view.MotionEvent.ACTION_UP -> { v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start(); if (e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat()) { v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onClick() } }
                    android.view.MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
                }
                true
            }
        }
        col.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return this
    }

    fun input(hint: String, text: String = "", numeric: Boolean = false, big: Boolean = false, maxLen: Int = 0): EditText {
        val et = EditText(a).apply {
            this.hint = hint; setText(text); setTextColor(Color.WHITE); setHintTextColor(0xFF5A5A68.toInt())
            textSize = if (big) 30f else 16f; if (big) { gravity = Gravity.CENTER; letterSpacing = 0.35f; typeface = Typeface.MONOSPACE }
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            if (maxLen > 0) filters = arrayOf(InputFilter.LengthFilter(maxLen))
            setSingleLine(); setPadding(dp(16), dp(14), dp(16), dp(14))
            background = round(0xFF0E0E13.toInt(), 16, PURPLE, 1)
        }
        col.addView(et, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        et.requestFocus()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return et
    }

    private fun buttonBar(): LinearLayout = btns ?: LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }.also { btns = it; col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }) }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit) = TextView(a).apply {
        this.text = text; gravity = Gravity.CENTER; textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) Color.WHITE else SOFT)
        background = if (primary) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF7C5FCB.toInt(), PURPLE)).apply { cornerRadius = dp(18).toFloat() } else round(ROW, 18)
        isClickable = true; setOnClickListener { onClick() }
    }

    fun primary(text: String, onClick: () -> Unit): Sheet { buttonBar().addView(button(text, true, onClick), LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) }); return this }
    fun secondary(text: String = "Close", onClick: () -> Unit = { dismiss() }): Sheet { buttonBar().addView(button(text, false, onClick), LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(6) }); return this }

    fun cancelable(c: Boolean): Sheet { d.setCancelable(c); d.setCanceledOnTouchOutside(c); return this }
    fun show(): Sheet { if (!a.isFinishing) d.show(); return this }
    fun dismiss() { try { d.dismiss() } catch (e: Exception) { } }
}
