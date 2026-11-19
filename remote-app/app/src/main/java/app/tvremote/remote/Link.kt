package app.tvremote.remote

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shared state between the UI and the foreground service (same process). Everything is derived from the TV keyboard link. */
object Link {
    val connected: Boolean get() = ImeLink.ready
    /** Looking for / waiting for a paired TV (the link keeps retrying by itself). */
    val connecting: Boolean get() = ImeLink.active && !ImeLink.ready && ImeLink.state != ImeClient.State.NEEDS_PAIR
    val tvName: String get() = ImeLink.tvName
    val host: String get() = ImeLink.host
    @Volatile var onIcon: ((String, String) -> Unit)? = null
    @Volatile var onState: (() -> Unit)? = null
    @Volatile var onApps: ((List<Pair<String, String>>) -> Unit)? = null
    @Volatile var onToast: ((String) -> Unit)? = null
    private var lastHint = 0L

    fun changed() { onState?.invoke(); RemoteService.inst?.refresh() }

    /** Replies of the TV's Accessibility add-on (arrive on the link's reader thread). */
    private val tickets = java.util.concurrent.LinkedBlockingQueue<String>()
    fun fromTv(l: String) {
        when {
            l.startsWith("ICON ") -> { val p = l.split(" ", limit = 3); if (p.size == 3) onIcon?.invoke(p[1], p[2]) }
            l.startsWith("MSG ") -> onToast?.invoke(l.removePrefix("MSG "))
            l.startsWith("FILE ") -> tickets.offer(l.removePrefix("FILE ").trim())
            l.startsWith("APPS") -> onApps?.invoke(l.removePrefix("APPS").trim().split(",").filter { it.contains("|") }.map {
                val p = it.split("|"); java.net.URLDecoder.decode(p[0], "UTF-8") to p[1]
            })
        }
    }

    /** One-time upload ticket for the TV's file receiver (asked over the encrypted link). Blocks up to 5 s: call off the UI thread. */
    fun fileTicket(): String? {
        tickets.clear()
        if (!ImeLink.ext("FILE")) return null
        return tickets.poll(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun send(line: String) {
        if (Transport.send(line)) return
        if (line.isNotEmpty() && line in Hints.SILENT) return          // housekeeping lines (FIELD, MOUSE...) never nag
        val now = System.currentTimeMillis()
        if (now - lastHint > 3000) { lastHint = now; onToast?.invoke("Not connected - tap the Wi-Fi icon") }
    }
}

object Net {
    /** Finds hosts with the given port open on every local /24 (same Wi-Fi AND phone-hotspot). */
    fun scan(port: Int): List<String> {
        val bases = LinkedHashMap<String, String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val ip = a.hostAddress ?: continue
                        bases[ip.substringBeforeLast('.')] = ip
                    }
                }
            }
        } catch (e: Exception) { }
        val found = CopyOnWriteArrayList<String>()
        val pool = Executors.newFixedThreadPool(48)
        for ((prefix, self) in bases) for (i in 1..254) {
            val ip = "$prefix.$i"
            if (ip == self) continue
            pool.execute {
                try { Socket().use { it.connect(InetSocketAddress(ip, port), 450); found.add(ip) } } catch (e: Exception) { }
            }
        }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS)
        return found
    }
}
