package app.tvremote.remote

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.URLEncoder

/** Full-screen "sending to TV" screen: live progress + speed, and every sent file stays openable from here. */
class XferUi(private val a: Activity) {
    private val dlg = Dialog(a, android.R.style.Theme_Material_NoActionBar_Fullscreen)
    private lateinit var head: TextView
    private lateinit var nameTv: TextView
    private lateinit var bar: ProgressBar
    private lateinit var stats: TextView
    private lateinit var list: LinearLayout
    private lateinit var close: TextView
    private var total = 0L; private var lastT = 0L; private var lastB = 0L; private var speed = 0.0
    private var idx = 0; private var count = 0

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
    private fun fmt(b: Double): String = when {
        b >= 1e9 -> String.format("%.2f GB", b / 1e9); b >= 1e6 -> String.format("%.1f MB", b / 1e6)
        b >= 1e3 -> String.format("%.0f KB", b / 1e3); else -> String.format("%.0f B", b)
    }
    private fun btn(t: String, color: Int = 0xFF2A2A36.toInt()) = TextView(a).apply {
        text = t; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10)); setBackgroundColor(color)
    }

    fun show(n: Int) {
        count = n
        a.runOnUiThread {
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF05050B.toInt()); setPadding(dp(22), dp(48), dp(22), dp(22)) }
            head = TextView(a).apply { text = "Sending to TV"; setTextColor(0xFFFFFFFF.toInt()); textSize = 24f }
            nameTv = TextView(a).apply { setTextColor(0xFFDDDDE6.toInt()); textSize = 16f; setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE; setPadding(0, dp(14), 0, dp(8)) }
            bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
            stats = TextView(a).apply { setTextColor(0xFF4CAF50.toInt()); textSize = 15f; setPadding(0, dp(10), 0, dp(14)) }
            list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            close = btn("Close").apply { visibility = android.view.View.GONE; setOnClickListener { dlg.dismiss() } }
            col.addView(head); col.addView(nameTv); col.addView(bar); col.addView(stats)
            col.addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
            col.addView(close, LinearLayout.LayoutParams(-1, -2))
            dlg.setContentView(col); dlg.setCancelable(false); dlg.show()
        }
    }

    fun begin(name: String, size: Long, i: Int) {
        total = size; idx = i; lastT = System.currentTimeMillis(); lastB = 0L; speed = 0.0
        a.runOnUiThread { nameTv.text = name; head.text = "Sending to TV  ($i of $count)"; bar.progress = 0; stats.text = "Starting..." }
    }

    /** called from the sending thread for every chunk */
    fun progress(done: Long) {
        val now = System.currentTimeMillis()
        if (now - lastT < 250) return
        val inst = (done - lastB) * 1000.0 / (now - lastT)
        speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
        lastT = now; lastB = done
        val pc = if (total > 0) (done * 1000 / total).toInt() else 0
        val eta = if (speed > 1) ((total - done) / speed).toInt() else 0
        a.runOnUiThread { bar.progress = pc; stats.text = "${pc / 10}%   ${fmt(done.toDouble())} / ${fmt(total.toDouble())}\n${fmt(speed)}/s   ${eta}s left" }
    }

    fun done(name: String, uri: Uri, ok: Boolean) {
        a.runOnUiThread {
            if (ok) { bar.progress = 1000; stats.text = "Sent  ${fmt(total.toDouble())}" }
            val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            row.addView(TextView(a).apply { text = (if (ok) "OK  " else "FAILED  ") + name; setTextColor(if (ok) 0xFFFFFFFF.toInt() else 0xFFFF6B6B.toInt()); textSize = 14f; setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, -2, 1f))
            if (ok) {
                row.addView(btn("Open here").apply { setOnClickListener { openHere(uri) } }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
                row.addView(btn("Open on TV", 0xFF3D5AFE.toInt()).apply { setOnClickListener { Link.send("OPENF " + URLEncoder.encode(name, "UTF-8")) } })
            }
            list.addView(row, 0)
        }
    }

    fun finish(ok: Int, bad: Int) {
        a.runOnUiThread {
            head.text = if (bad == 0) "Sent to TV" else "Finished: $ok sent, $bad failed"
            nameTv.text = "Saved in the TV's Download folder"
            close.visibility = android.view.View.VISIBLE; dlg.setCancelable(true)
        }
    }

    private fun openHere(uri: Uri) {
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.contentResolver.getType(uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { Toast.makeText(a, "No app on this phone can open it", Toast.LENGTH_SHORT).show() }
    }
}
