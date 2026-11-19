package app.tvremote.ime

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Full-screen transfer UI on the TV: live progress, speed, ETA, and every received file can be opened right here. */
class TransferActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var title: TextView
    private lateinit var nameTv: TextView
    private lateinit var stats: TextView
    private lateinit var bar: ProgressBar
    private lateinit var list: LinearLayout
    private var shown = -1

    private fun fmt(b: Double): String = when {
        b >= 1e9 -> String.format("%.2f GB", b / 1e9); b >= 1e6 -> String.format("%.1f MB", b / 1e6)
        b >= 1e3 -> String.format("%.0f KB", b / 1e3); else -> String.format("%.0f B", b)
    }

    private val tick = object : Runnable {
        override fun run() { render(); h.postDelayed(this, 250) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        title = TextView(this).apply { textSize = 34f; setTextColor(Color.WHITE) }
        nameTv = TextView(this).apply { textSize = 22f; setTextColor(0xFFDDDDE6.toInt()); setPadding(0, 18, 0, 12); setSingleLine() }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        stats = TextView(this).apply { textSize = 24f; setTextColor(0xFF4CD964.toInt()); setPadding(0, 14, 0, 22) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dl = Button(this).apply { text = "Open Download folder"; setOnClickListener { Xfer.downloads(this@TransferActivity) } }
        val close = Button(this).apply { text = "Close"; setOnClickListener { finish() } }
        val bottom = LinearLayout(this).apply { addView(dl); addView(close) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.START
            setBackgroundColor(0xFF05050B.toInt()); setPadding(90, 60, 90, 40)
            addView(title); addView(nameTv); addView(bar, LinearLayout.LayoutParams(-1, -2)); addView(stats)
            addView(ScrollView(this@TransferActivity).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bottom)
        })
        tick.run()
    }

    private fun render() {
        val st = Xfer.state
        title.text = when (st) { 1 -> "Receiving from phone"; 2 -> "File received"; 3 -> "Transfer interrupted"; else -> "File transfer" }
        nameTv.text = Xfer.name
        val total = Xfer.total; val done = Xfer.done
        val pc = if (total > 0) (done * 1000 / total).toInt() else 0
        bar.progress = pc
        val eta = if (st == 1 && Xfer.speed > 1) ((total - done) / Xfer.speed).toInt() else 0
        stats.text = if (st == 0) "" else "${pc / 10}%     ${fmt(done.toDouble())} / ${fmt(total.toDouble())}" +
            (if (st == 1) "\n${fmt(Xfer.speed)}/s     ${eta}s left" else "")
        if (Xfer.files.size != shown) {
            shown = Xfer.files.size
            list.removeAllViews()
            for ((i, r) in Xfer.files.withIndex()) {
                val b = Button(this).apply { text = "OPEN   " + r.name; isAllCaps = false; textSize = 20f; setOnClickListener { Xfer.open(this@TransferActivity, r) } }
                list.addView(b)
                if (i == 0) b.requestFocus()
            }
        }
    }

    override fun onDestroy() { h.removeCallbacks(tick); super.onDestroy() }
}
