package app.tvremote.remote

import android.content.Context
import android.content.SharedPreferences
import app.tvremote.imeproto.ImeProto

/** Android glue for the TV keyboard link: persistence, network scan, and the few calls the rest of the app uses. */
object ImeLink {
    private var app: Context? = null
    private lateinit var store: PrefsImeStore
    private var client: ImeClient? = null

    @Synchronized fun init(c: Context) {
        if (client != null) return
        app = c.applicationContext
        store = PrefsImeStore(app!!.getSharedPreferences("ime", Context.MODE_PRIVATE))
        client = ImeClient(store, object : ImeFinder {
            override fun scan(): List<Found> = Net.scan(ImeProto.PORT).mapNotNull { ImeClient.hello(it, ImeProto.PORT, store.clientId(), 1500) }
        }, android.os.Build.MODEL ?: "Phone", changed = { Link.changed() }, hint = { m -> Link.onToast?.invoke(m) }, onExt = { l -> Link.fromTv(l) })
    }

    val ready: Boolean get() = client?.state == ImeClient.State.READY
    val active: Boolean get() = client?.active == true
    val paired: Boolean get() = client != null && store.tvs().isNotEmpty()
    val inputActive: Boolean get() = client?.input == true
    val state: ImeClient.State get() = client?.state ?: ImeClient.State.IDLE
    val tvName: String get() = client?.tvName ?: ""
    /** The TV's optional Accessibility add-on is on (mouse, gamepad, app list, file send work). */
    val a11y: Boolean get() = ready && "a11y" in caps
    val caps: Set<String> get() = client?.caps ?: emptySet()
    /** Address of the TV this phone currently controls ("" if none paired). */
    val host: String get() = if (client == null) "" else (store.tvs().let { l -> l.firstOrNull { it.tvId == store.last() } ?: l.firstOrNull() }?.host ?: "")
    fun tvs(): List<ImeTv> = if (client == null) emptyList() else store.tvs()
    fun lastTv(): String? = if (client == null) null else store.last()

    fun supportsKey(code: Int): Boolean { val c = client ?: return false; return ImeCaps.supportsKey(code, c.caps, c.input) }
    fun key(code: Int): Boolean = client?.send("K $code") == true
    /** One line for the TV's Accessibility add-on ("M 3 4", "LIST", "W"...). false = not delivered. */
    fun ext(line: String): Boolean = client?.send("X " + ImeProto.enc(line)) == true
    fun text(s: String, replace: Boolean): Boolean = client?.send((if (replace) "R " else "T ") + ImeProto.enc(s)) == true

    fun start() { if (paired) client?.start() }
    fun stop() { client?.stop() }
    /** Switch the active TV when several are paired. */
    fun use(tvId: String) { store.setLast(tvId); client?.stop(); client?.start() }
    fun forget() { client?.stop(); store.tvs().forEach { store.remove(it.tvId) }; Link.changed() }

    // pairing (UI drives these two calls)
    fun scan(onDone: (List<Found>) -> Unit) { Thread { onDone(try { Net.scan(ImeProto.PORT).mapNotNull { ImeClient.hello(it, ImeProto.PORT, store.clientId(), 1500) } } catch (e: Exception) { emptyList() }) }.start() }
    fun beginPairing(host: String, cb: (String?) -> Unit) { client?.beginPairing(host, cb) ?: cb("Not ready") }
    fun finishPairing(pin: String, cb: (ImeClient.PairResult) -> Unit) { client?.finishPairing(pin, cb) ?: cb(ImeClient.PairResult.Failed("Not ready")) }
    fun cancelPairing() { client?.cancelPairing() }
}

class PrefsImeStore(private val p: SharedPreferences) : ImeStore {
    @Synchronized override fun clientId(): String {
        p.getString("cid", null)?.let { return it }
        val id = ImeProto.randomHex(16); p.edit().putString("cid", id).apply(); return id
    }
    override fun tvs(): List<ImeTv> = p.all.keys.filter { it.startsWith("tv_") }.mapNotNull { k ->
        val v = p.getString(k, null)?.split('|') ?: return@mapNotNull null
        if (v.size != 3) return@mapNotNull null
        ImeTv(k.substring(3), v[0], v[1], ImeProto.unhex(v[2]) ?: return@mapNotNull null)
    }
    override fun save(t: ImeTv) { p.edit().putString("tv_" + t.tvId, t.name.replace("|", " ") + "|" + t.host + "|" + ImeProto.hex(t.token)).apply() }
    override fun remove(tvId: String) { p.edit().remove("tv_$tvId").apply() }
    override fun last(): String? = p.getString("last", null)
    override fun setLast(tvId: String) { p.edit().putString("last", tvId).apply() }
}
