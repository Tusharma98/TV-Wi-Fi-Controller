package app.tvremote.remote

import android.app.Activity
import android.widget.Toast

/** Pairing flow on the modern sheet: find the TV (scan or typed IP) -> type the PIN shown on the TV. */
class ImePairUi(private val a: Activity, private val defaultHost: String, private val done: () -> Unit) {
    private fun toast(m: String) = a.runOnUiThread { Toast.makeText(a, m, Toast.LENGTH_LONG).show() }

    fun start() {
        toast("Looking for TVs with the keyboard app...")
        ImeLink.scan { list -> a.runOnUiThread { choose(list) } }
    }

    /** Pair with a TV at a typed address (skips the scan). */
    fun startAt(host: String) = begin(host)

    private fun choose(list: List<Found>) {
        if (list.isEmpty()) { manual("No TV found automatically. On the TV open 'TV Remote Keyboard' and keep it open, on the same Wi-Fi / hotspot."); return }
        if (list.size == 1) { begin(list[0].host); return }
        val s = Sheet(a).title("Which TV?", "Found on this network").cancelable(true)
        for (f in list) s.row(R.drawable.ic_wifi, f.name.ifBlank { "TV" }, f.host + if (f.paired) "  -  already paired" else "") { s.dismiss(); begin(f.host) }
        s.row(R.drawable.i_kbd, "Type an IP address", "If your TV is not listed") { s.dismiss(); manual(null) }
        s.secondary("Cancel").show()
    }

    private fun manual(why: String?) {
        val s = Sheet(a).title("TV address", "Shown at the top of the receiver app on the TV").cancelable(true)
        if (why != null) s.note(why, Sheet.AMBER)
        val et = s.input("e.g. 192.168.1.20", defaultHost)
        s.secondary("Cancel").primary("Next") { val h = et.text.toString().trim(); if (h.isNotEmpty()) { s.dismiss(); begin(h) } }.show()
    }

    private fun begin(host: String) {
        toast("Contacting TV...")
        ImeLink.beginPairing(host) { err -> if (err != null) toast(err) else a.runOnUiThread { pin(null) } }
    }

    private fun pin(error: String?) {
        val s = Sheet(a).title("Enter the PIN", "On the TV press 'Pair a new phone' - a 6-digit PIN appears").cancelable(false)
        if (error != null) s.note(error, Sheet.RED)
        val et = s.input("000000", numeric = true, big = true, maxLen = 6)
        s.secondary("Cancel") { s.dismiss(); ImeLink.cancelPairing() }.primary("Pair") {
            s.dismiss()
            ImeLink.finishPairing(et.text.toString()) { r ->
                when (r) {
                    is ImeClient.PairResult.Paired -> a.runOnUiThread { toast("Paired. Works after TV reboots without re-pairing"); done() }
                    is ImeClient.PairResult.WrongPin -> a.runOnUiThread { if (r.left > 0) pin("Wrong PIN - ${r.left} tries left") else toast("Too many wrong PINs. Press 'Pair a new phone' on the TV again.") }
                    is ImeClient.PairResult.Failed -> toast(r.why)
                }
            }
        }.show()
    }

    /** With several paired TVs: pick which one this phone controls. */
    fun chooseActive() {
        val tvs = ImeLink.tvs()
        if (tvs.size < 2) return
        val s = Sheet(a).title("Control which TV?", "Tap to switch").cancelable(true)
        for (t in tvs) s.row(R.drawable.ic_wifi, t.name.ifBlank { "TV" }, t.host + if (t.tvId == ImeLink.lastTv()) "  -  in use" else "", if (t.tvId == ImeLink.lastTv()) Sheet.GREEN else android.graphics.Color.WHITE) { s.dismiss(); ImeLink.use(t.tvId); done() }
        s.secondary("Cancel").show()
    }
}
