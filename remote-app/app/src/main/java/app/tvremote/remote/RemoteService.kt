package app.tvremote.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * Keeps the TV keyboard link alive while the app is in the background (foreground service) and lets the
 * phone's volume buttons control the TV. The link itself (connect, authenticate, heartbeat, reconnect) lives in ImeClient.
 */
class RemoteService : Service() {
    companion object {
        const val ACT_CONNECT = "app.tvremote.remote.CONNECT"
        const val ACT_DISCONNECT = "app.tvremote.remote.DISCONNECT"
        @Volatile var inst: RemoteService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var media: MediaSession? = null
    private var wf: WifiManager.WifiLock? = null
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        Transport.init(this)
    }

    override fun onDestroy() { if (inst === this) inst = null; stopMedia(); lowLatency(false); super.onDestroy() }

    // Survives "clear from recents": a foreground service is not killed with the task.
    override fun onTaskRemoved(rootIntent: Intent?) { }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ACT_DISCONNECT) { stopAll(); return START_NOT_STICKY }
        startFg(statusText())
        if (!ImeLink.paired) { stopAll(); return START_NOT_STICKY }     // nothing to keep alive until a TV is paired
        ImeLink.start()
        refresh()
        return START_STICKY
    }

    private fun statusText(): String = when {
        ImeLink.ready -> "Connected to ${ImeLink.tvName.ifBlank { "TV" }}"
        ImeLink.state == ImeClient.State.NEEDS_PAIR -> "Pair this phone again on the TV"
        ImeLink.active -> "Looking for the TV..."
        else -> "Connecting..."
    }

    /** Called from Link.changed() on every state change of the keyboard link. */
    fun refresh() {
        if (!ImeLink.active) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, notification(statusText()))
        if (ImeLink.ready) { startMedia(); lowLatency(true) } else { stopMedia(); lowLatency(false) }
    }

    /** While connected: Wi-Fi in low-latency mode (no radio napping between taps) + CPU awake. A big part of the "laggy" feeling when the screen is off or the phone is idle. */
    @Synchronized private fun lowLatency(on: Boolean) {
        try {
            if (on) {
                if (wf == null) {
                    val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    wf = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(mode, "tvremote:link").apply { setReferenceCounted(false) }
                }
                if (wf?.isHeld != true) wf?.acquire()
                if (wl == null) wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvremote:link").apply { setReferenceCounted(false) }
                if (wl?.isHeld != true) wl?.acquire()
            } else {
                if (wf?.isHeld == true) wf?.release()
                if (wl?.isHeld == true) wl?.release()
            }
        } catch (e: Exception) { }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("remote", "Remote connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val dis = PendingIntent.getService(this, 1, Intent(this, RemoteService::class.java).setAction(ACT_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "remote") else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_wifi).setContentTitle("TV Remote").setContentText(text)
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", dis)
        return b.build()
    }

    private fun startFg(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(1, n)
    }

    private fun stopAll() {
        ImeLink.stop()
        stopMedia(); lowLatency(false)
        Link.changed()
        stopForeground(true)
        stopSelf()
    }

    // ---------- phone volume buttons -> TV ----------
    private fun startMedia() {
        main.post {
            if (media != null) return@post
            val ms = MediaSession(this, "TvRemote")
            ms.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            ms.setPlaybackToRemote(object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    when (direction) {
                        1 -> Link.send("K 24")
                        -1 -> Link.send("K 25")
                        0 -> { }
                        else -> Link.send("K 164")
                    }
                }
            })
            ms.isActive = true
            media = ms
        }
    }

    private fun stopMedia() {
        main.post {
            media?.let { it.isActive = false; it.release() }
            media = null
        }
    }
}
