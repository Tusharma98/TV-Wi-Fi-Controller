package app.tvremote.ime

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/** Live state of the current file transfer (read by TransferActivity) + files received this session. */
object Xfer {
    class Rec(val name: String, val uri: String?, val mime: String)
    @Volatile var name = ""
    @Volatile var total = 0L
    @Volatile var done = 0L
    @Volatile var speed = 0.0
    @Volatile var state = 0          // 0 idle, 1 receiving, 2 done, 3 interrupted
    @Volatile var startedAt = 0L
    val files = CopyOnWriteArrayList<Rec>()

    fun begin(n: String, t: Long) { name = n; total = t; done = 0; speed = 0.0; state = 1; startedAt = SystemClock.elapsedRealtime() }
    fun finish(r: Rec) { files.add(0, r); done = total; state = 2 }
    fun fail() { if (state == 1) state = 3 }

    fun open(c: Context, r: Rec) {
        val i = if (r.uri != null) Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(r.uri), r.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        else Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ctx: Context = A11y.inst ?: c
        try { ctx.startActivity(i) } catch (e: Exception) { downloads(c) }
    }

    fun downloads(c: Context) {
        try { c.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }

    fun openByName(c: Context, n: String) { files.firstOrNull { it.name == n }?.let { open(c, it) } }
}
