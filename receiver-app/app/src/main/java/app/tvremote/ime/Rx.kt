package app.tvremote.ime

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.webkit.MimeTypeMap
import android.widget.Toast
import app.tvremote.imeproto.ImeProto
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The optional Accessibility add-on of the receiver. It is reached ONLY through the authenticated, encrypted keyboard link
 * (extension lines "X ..."), never through an open port. Everything here is extra: without the Accessibility service the
 * keyboard works exactly as before.
 *
 *  phone -> TV lines:  M dx dy | C | S dx dy | MOUSE 0|1 | CLEAR | FIELD | VOICE | LIST | ICON <pkg> | L <pkg> | A <label> | OPENF <name>
 *                      W (Wi-Fi settings) | A11YSET (Accessibility settings) | FILE (ask for a one-time upload ticket)
 *  TV -> phone lines:  APPS ... | ICON <pkg> <b64> | MSG <text> | FILE <ticket>
 */
object Rx {
    const val FILE_PORT = 8767
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "rx-exec").apply { priority = Thread.MAX_PRIORITY } }   // keys / taps / text, strictly in order
    private val slow = Executors.newSingleThreadExecutor { r -> Thread(r, "rx-slow") }                                             // app list, icons, clear all: never blocks input
    private val dpadPending = AtomicInteger(0)
    private var cursor: Cursor? = null
    private var wl: PowerManager.WakeLock? = null
    private var wf: WifiManager.WifiLock? = null
    @Volatile private var fileSrvUp = false
    private val tickets = ConcurrentHashMap<String, Long>()
    private val k get() = app.resources.displayMetrics.widthPixels / 1920f
    private var lastHint = 0L

    @Synchronized fun init(c: Context) { if (!::app.isInitialized) { app = c.applicationContext; cursor = Cursor(app) } }

    // ---------- while a phone is connected: keep CPU + Wi-Fi awake and in low-latency mode (the TV radio napping was a source of lag) ----------
    @Synchronized fun session(active: Boolean) {
        if (!::app.isInitialized) return
        if (active) {
            try { if (wl == null) wl = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvrx:link").apply { setReferenceCounted(false) }; wl?.acquire() } catch (e: Exception) { }
            try {
                if (wf == null) {
                    val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    wf = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(mode, "tvrx:wifi").apply { setReferenceCounted(false) }
                }
                wf?.acquire()
            } catch (e: Exception) { }
        } else {
            try { wl?.release() } catch (e: Exception) { }
            try { wf?.release() } catch (e: Exception) { }
            cursor?.hide()
        }
    }

    // ---------- launching (an Accessibility service may start activities from the background; a keyboard may not on every Android version) ----------
    private fun launch(i: Intent, why: String? = null) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val a = A11y.inst
        val c: Context = a ?: ImeHost.service ?: app
        try { c.startActivity(i) } catch (e: Exception) { }
        // without Accessibility / overlay permission Android may silently block it: leave a notification the TV user can click
        if (why != null && a == null && !(Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(app))) notifyOpen(why, i)
    }

    private fun notifyOpen(title: String, i: Intent) {
        try {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("open", "Open from phone", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(app, title.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, "open") else Notification.Builder(app)
            nm.notify(title.hashCode(), b.setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle(title).setContentText("Select to open").setAutoCancel(true).setContentIntent(pi).setFullScreenIntent(pi, true).build())
        } catch (e: Exception) { }
    }

    // ---------- keys that need Accessibility (called by ImeHost.key) ----------
    val A11Y_KEYS = setOf(3, 4, 19, 20, 21, 22, 23, 26, 66, 82, 84, 176, 187)

    /** true = handled here. Never blocks the caller. */
    fun a11yKey(code: Int, inputActive: Boolean): Boolean {
        val a = A11y.inst ?: return false
        when (code) {
            19, 20, 21, 22 -> {                       // each move reads the screen: never queue more than 2 behind the one running
                if (dpadPending.get() >= 3) return true
                dpadPending.incrementAndGet()
                exec.execute { try { A11y.inst?.dpad(code - 19) } catch (e: Exception) { } finally { dpadPending.decrementAndGet() } }
            }
            23 -> exec.execute { A11y.inst?.ok() }
            66 -> if (inputActive) return false else exec.execute { A11y.inst?.enter() }
            4 -> a.global(AccessibilityService.GLOBAL_ACTION_BACK)
            3 -> a.global(AccessibilityService.GLOBAL_ACTION_HOME)
            187 -> a.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            26 -> exec.execute { A11y.inst?.power() }
            82 -> exec.execute { A11y.inst?.menu() }
            84 -> exec.execute { A11y.inst?.search() }
            176 -> exec.execute { launch(Intent(Settings.ACTION_SETTINGS)) }
            else -> return false
        }
        return true
    }

    /** Text with no focused keyboard field: fill / open a field through Accessibility instead. */
    fun a11yText(t: String, replace: Boolean): Boolean {
        if (A11y.inst == null) return false
        exec.execute { A11y.inst?.typeText(t, replace) }
        return true
    }

    // ---------- extension lines ----------
    private fun hint(reply: (String) -> Unit) {
        val n = System.currentTimeMillis()
        if (n - lastHint > 8000) { lastHint = n; reply("MSG Turn on Accessibility for TV Remote Keyboard on the TV (one time)") }
    }

    fun handle(line: String, reply: (String) -> Unit) {
        val i = line.indexOf(' ')
        val cmd = if (i < 0) line else line.substring(0, i)
        val a = if (i < 0) "" else line.substring(i + 1)
        when (cmd) {                                    // these two work without Accessibility
            "A11YSET" -> { exec.execute { launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Open Accessibility settings") }; reply("MSG Opening Accessibility settings on the TV"); return }
            "W" -> { exec.execute { launch(Intent(Settings.ACTION_WIFI_SETTINGS), "Open Wi-Fi settings") }; return }
        }
        if (A11y.inst == null) { hint(reply); return }
        when (cmd) {
            "M" -> { val d = a.split(' '); if (d.size == 2) cursor?.move((d[0].toFloatOrNull() ?: 0f) * k, (d[1].toFloatOrNull() ?: 0f) * k) }
            "MOUSE" -> if (a == "1") cursor?.show() else cursor?.hide()
            "C" -> { val c = cursor ?: return; val x = c.x.toInt(); val y = c.y.toInt(); exec.execute { A11y.inst?.tap(x, y) } }
            "S" -> {
                val d = a.split(' '); val c = cursor ?: return
                val x = c.x.toInt(); val y = c.y.toInt()
                val dm = app.resources.displayMetrics
                val x2 = (x + (d.getOrNull(0)?.toIntOrNull() ?: 0) * 3).coerceIn(10, dm.widthPixels - 10)
                val y2 = (y + (d.getOrNull(1)?.toIntOrNull() ?: 0) * 3).coerceIn(10, dm.heightPixels - 10)
                exec.execute { A11y.inst?.swipe(x, y, x2, y2) }
            }
            "FIELD" -> exec.execute { A11y.inst?.focusField() }
            "CLEAR" -> slow.execute { clearAll(reply) }
            "VOICE" -> slow.execute { reply(if (assistant()) "MSG Voice assistant opened on the TV" else "MSG No voice assistant found on this TV") }
            "LIST" -> slow.execute { reply("APPS " + launchables().joinToString(",") { URLEncoder.encode(it.first, "UTF-8") + "|" + it.second }) }
            "ICON" -> slow.execute {
                try {
                    val dr = app.packageManager.getApplicationIcon(a)
                    val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                    dr.setBounds(0, 0, 96, 96); dr.draw(Canvas(bmp))
                    val o = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, o)
                    reply("ICON " + a + " " + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP))
                } catch (e: Exception) { }
            }
            "L" -> launchPkg(a)
            "A" -> launchLabel(a)
            "OPENF" -> exec.execute { Xfer.openByName(app, a) }
            "FILE" -> { startFileServer(); val t = ImeProto.randomHex(16); tickets[t] = SystemClock.elapsedRealtime() + 60_000; reply("FILE $t") }
        }
    }

    // ---------- file receive: one-time ticket (handed out over the encrypted link) + name + size + bytes ----------
    @Synchronized private fun startFileServer() {
        if (fileSrvUp) return
        fileSrvUp = true
        Thread({
            while (true) {
                try {
                    val ss = ServerSocket(); ss.reuseAddress = true; ss.bind(InetSocketAddress(FILE_PORT))
                    while (true) { val s = ss.accept(); Thread { receiveFile(s) }.start() }
                } catch (e: Exception) { try { Thread.sleep(2000) } catch (x: Exception) { } }
            }
        }, "rx-files").apply { isDaemon = true; start() }
    }

    private fun receiveFile(s: Socket) {
        var started = false
        try {
            s.soTimeout = 60000
            val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
            val ticket = inp.readUTF()
            val until = tickets.remove(ticket)                         // single use
            if (until == null || SystemClock.elapsedRealtime() > until) return
            val name = inp.readUTF().replace('/', '_').replace('\\', '_').replace(':', '_').trimStart('.').take(120).ifBlank { "file" }
            val total = inp.readLong()
            if (total < 0) return
            var left = total
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val out: OutputStream
            var uri: Uri? = null
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return
                out = app.contentResolver.openOutputStream(uri) ?: return
            } else {
                var d: File? = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                try { d?.mkdirs() } catch (e: Exception) { }
                if (d == null || !d.canWrite()) { d = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS); d?.mkdirs() }
                var f = File(d, name)
                var n = 1
                while (f.exists()) { f = File(d, name.substringBeforeLast('.') + " (" + n + ")" + (if (ext.isEmpty()) "" else ".$ext")); n++ }
                out = FileOutputStream(f)
            }
            Xfer.begin(name, total); started = true
            main.post { try { Toast.makeText(app, "Receiving $name", Toast.LENGTH_SHORT).show() } catch (e: Exception) { } }
            launch(Intent(app, TransferActivity::class.java))           // full-screen live transfer screen
            var lastT = SystemClock.elapsedRealtime()
            var lastB = 0L
            out.use { o ->
                val buf = ByteArray(65536)
                while (left > 0) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    o.write(buf, 0, n); left -= n
                    Xfer.done = total - left
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastT >= 300) {
                        val inst = (Xfer.done - lastB) * 1000.0 / (now - lastT)
                        Xfer.speed = if (Xfer.speed == 0.0) inst else Xfer.speed * 0.6 + inst * 0.4
                        lastT = now; lastB = Xfer.done
                    }
                }
            }
            if (left > 0) { uri?.let { app.contentResolver.delete(it, null, null) }; Xfer.fail(); return }   // never keep half a file
            s.getOutputStream().write(1)
            Xfer.finish(Xfer.Rec(name, uri?.toString(), mime))
        } catch (e: Exception) {
            if (started) Xfer.fail()
        } finally { try { s.close() } catch (e: Exception) { } }
    }

    // ---------- clear all background apps ----------
    private fun freeMb(am: ActivityManager): Long { val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); return mi.availMem / 1048576L }

    private fun protectedPkgs(): Set<String> {
        val keep = HashSet<String>()
        keep.add(app.packageName); keep.add("android"); keep.add("com.android.systemui")
        try { for (r in app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) keep.add(r.activityInfo.packageName) } catch (e: Exception) { }
        try { for (m in (app.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).enabledInputMethodList) keep.add(m.packageName) } catch (e: Exception) { }
        try { for (c in (Settings.Secure.getString(app.contentResolver, "enabled_accessibility_services") ?: "").split(':')) if (c.contains('/')) keep.add(c.substringBefore('/')) } catch (e: Exception) { }
        return keep
    }

    /** Ends every other app running in the background; this app, the launcher, keyboards and accessibility services stay. */
    private fun clearAll(reply: (String) -> Unit) {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = freeMb(am)
        A11y.inst?.global(AccessibilityService.GLOBAL_ACTION_HOME)       // leave the foreground app first so it can be cleared too
        try { Thread.sleep(600) } catch (e: Exception) { }
        val keep = protectedPkgs()
        val pm = app.packageManager
        var n = 0
        for (ai in pm.getInstalledApplications(0)) {
            val p = ai.packageName
            if (p in keep) continue
            val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val launchable = pm.getLaunchIntentForPackage(p) != null || pm.getLeanbackLaunchIntentForPackage(p) != null
            if (sys && !launchable) continue
            try { am.killBackgroundProcesses(p); n++ } catch (e: Exception) { }
        }
        try { Thread.sleep(900) } catch (e: Exception) { }
        reply("MSG Cleared $n apps - ${freeMb(am)} MB free (was $before MB)")
    }

    /** Opens the TV's own voice assistant (needs an assistant + mic / voice remote on the TV). */
    private fun assistant(): Boolean {
        for (i in listOf(Intent(Intent.ACTION_VOICE_COMMAND), Intent("android.intent.action.VOICE_ASSIST"), Intent(Intent.ACTION_ASSIST), Intent("android.speech.action.VOICE_SEARCH_HANDS_FREE"))) {
            if (app.packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) == null) continue
            launch(i); return true
        }
        return false
    }

    private fun launchables(): List<Pair<String, String>> {
        val pm = app.packageManager
        val out = ArrayList<Pair<String, String>>()
        for (ai in pm.getInstalledApplications(0)) {
            if (ai.packageName == app.packageName) continue
            if (pm.getLaunchIntentForPackage(ai.packageName) != null || pm.getLeanbackLaunchIntentForPackage(ai.packageName) != null) out.add(ai.loadLabel(pm).toString() to ai.packageName)
        }
        return out.sortedBy { it.first.lowercase() }
    }

    private fun launchLabel(q: String) {
        val l = q.lowercase().trim()
        val pkg = launchables().firstOrNull { it.first.lowercase().contains(l) }?.second ?: return
        launchPkg(pkg)
    }

    private fun launchPkg(p: String) {
        val pm = app.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(p) ?: pm.getLaunchIntentForPackage(p) ?: return
        exec.execute { launch(i) }
    }
}
