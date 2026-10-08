package app.privatetunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit

class TunnelService : VpnService() {

    companion object {
        val running = MutableStateFlow(false)
        val busy = MutableStateFlow(false)
        val status = MutableStateFlow("")
        val method = MutableStateFlow("")
        val since = MutableStateFlow(0L)
        val traffic = MutableStateFlow(0L to 0L)
        val logs = MutableStateFlow<List<String>>(emptyList())
        fun log(s: String) { logs.value = (logs.value + s).takeLast(700) }

        fun hotspotAddresses(port: Int): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && it.isSiteLocalAddress && it.address.size == 4 }
                .map { "${it.hostAddress}:$port" }
        } catch (e: Exception) { emptyList() }

        fun modeLabel(k: String) = when (k) {
            "auto" -> "خودکار"; "psiphon" -> "Psiphon"; "warp" -> "WARP"; "gool" -> "Gool"
            "v2ray" -> "کانفیگ V2Ray"; else -> k
        }
    }

    private data class Step(val key: String)

    private val procs = mutableListOf<Process>()
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val hev by lazy { TProxyService() }
    private var hevStarted = false
    @Volatile private var cancelled = false
    private var foundEps: List<Warp.Hit>? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { cancelled = true; stopAll(); stopSelf(); return START_NOT_STICKY }
        startForeground(1, notification())
        if (worker?.isAlive != true) {
            busy.value = true
            worker = Thread {
                try { startAll() } catch (e: Exception) {
                    val m = e.message ?: "نامشخص"
                    log("خطا: $m"); stopAll(); status.value = if (cancelled) "" else "خطا: $m"; stopSelf()
                } finally { busy.value = false }
            }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pt", "Tunnel", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "pt").setContentTitle("Private Tunnel")
            .setContentText("اتصال فعال است").setSmallIcon(R.drawable.ic_launcher).setOngoing(true).build()
    }

    private fun helpText(): String = try {
        val p = ProcessBuilder(File(applicationInfo.nativeLibraryDir, "libwarp.so").path, "--help")
            .redirectErrorStream(true).start()
        val t = p.inputStream.bufferedReader().readText(); p.waitFor(5, TimeUnit.SECONDS); t
    } catch (e: Exception) { "" }

    private fun portFree(p: Int) = try {
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress("127.0.0.1", p)) }; true
    } catch (e: Exception) { false }

    private fun steps(mode: String): List<Step> {
        if (mode != "auto") return listOf(Step(mode))
        val l = mutableListOf(Step("warp"), Step("gool"), Step("psiphon"))
        if (Store.selectedProfile(this) != null) l += Step("v2ray")
        val last = Store.lastGood(this)
        l.sortBy { if (it.key == last) 0 else 1 }
        return l
    }

    private fun startAll() {
        cancelled = false
        status.value = "آماده‌سازی..."; method.value = ""
        killProcs()
        val port = Store.port(this)
        val listen = if (Store.hotspot(this)) "0.0.0.0" else "127.0.0.1"
        val dir = filesDir
        val cache = File(dir, "warp").apply { mkdirs() }
        if (!portFree(port) || !portFree(port + 1)) error("پورت $port اشغال است (برنامه دیگری استفاده می‌کند)")
        Warp.seed(this)
        foundEps = null
        val help = helpText()

        val list = steps(Store.mode(this))
        var used = ""
        for ((i, st) in list.withIndex()) {
            if (cancelled) error("لغو شد")
            val label = modeLabel(st.key)
            status.value = if (list.size > 1) "تلاش ${i + 1} از ${list.size}: $label" else "اتصال با $label..."
            log("--- تلاش: $label")
            if (tryStep(st, port, listen, dir, cache, help)) { used = st.key; break }
            killProcs()
        }
        if (used.isEmpty()) error("هیچ روشی وصل نشد. تب «تست» را ببین و بعد دوباره امتحان کن")
        if (Store.mode(this) == "auto") Store.setLastGood(this, used)
        if (cancelled) error("لغو شد")

        status.value = "ساخت تونل..."
        val b = Builder().setSession("PrivateTunnel").setMtu(1500)
            .addAddress("198.18.0.1", 32).addAddress("fd00::1", 126)
            .addRoute("0.0.0.0", 0).addRoute("::", 0).addDnsServer("198.18.0.2")
        b.addDisallowedApplication(packageName)
        tun = b.establish() ?: error("VPN ساخته نشد (مجوز داده نشده؟)")
        val conf = File(dir, "hev.yml").apply { writeText(ConfigBuilder.hev(port)) }
        hev.start(conf.path, tun!!.fd)
        hevStarted = true
        method.value = modeLabel(used)
        since.value = System.currentTimeMillis()
        running.value = true
        status.value = "متصل"
        log("متصل شد با ${modeLabel(used)}")
        Thread {
            while (running.value) {
                try { hev.stats()?.let { traffic.value = it[1] to it[3] } } catch (_: Throwable) {}
                Thread.sleep(1000)
            }
        }.start()
    }

    /** warp-plus arguments (only used for Psiphon now) */
    private fun warpPlusArgs(psiphon: Boolean, endpoint: String, bind: String, cache: File, help: String): List<String> {
        fun need(flag: String) { if (help.isNotEmpty() && !help.contains(flag)) error("این نسخه warp-plus از $flag پشتیبانی نمی‌کند") }
        if (psiphon) need("--cfon")
        if (endpoint.isNotBlank()) need("--endpoint")
        return ConfigBuilder.warpArgs("warp", psiphon, Store.country(this).ifBlank { "US" }, false, false, endpoint, bind, cache.path)
    }

    private fun endpoints(): List<Warp.Hit> {
        foundEps?.let { return it }
        val base = status.value.substringBefore("\n")
        val f = Warp.Finder(this, procs, { cancelled }, { status.value = base + "\n" + it })
        val r = f.find()
        if (r.isEmpty()) error("هیچ endpoint سالمی از Cloudflare پیدا نشد. UDP یا IP های WARP بسته است")
        log("endpointهای سالم: " + r.joinToString { "${it.hostPort()} (${it.ms}ms)" })
        foundEps = r
        return r
    }

    private fun warpChain(key: String, hits: List<Warp.Hit>): Pair<List<org.json.JSONObject>, String> {
        val id1 = Warp.load(this, "primary") ?: error("هویت WARP داخل APK نیست")
        val list = mutableListOf(Warp.endpoint("w1", id1, hits[0].ip, hits[0].port, null, 1280))
        var last = "w1"
        if (key == "gool") {
            val id2 = Warp.load(this, "secondary") ?: id1
            val o = hits.getOrElse(1) { hits[0] }
            list += Warp.endpoint("w2", id2, o.ip, o.port, "w1", 1200)
            last = "w2"
        }
        return list to last
    }

    /** starts the cores for one method and checks that real traffic passes. false = try next */
    private fun tryStep(st: Step, port: Int, listen: String, dir: File, cache: File, help: String): Boolean {
        try {
            when (st.key) {
                "warp", "gool" -> {
                    val (eps, last) = warpChain(st.key, endpoints())
                    val cfg = File(dir, "sb.json").apply { writeText(ConfigBuilder.singBox(null, listen, port, eps, last)) }
                    status.value = status.value.substringBefore("\n") + "\nراه‌اندازی تونل..."
                    startCore("sing-box", "libsingbox.so", listOf("run", "-c", cfg.path, "-D", dir.path), dir)
                    waitPort(port, 30)
                }
                "psiphon" -> {
                    val ep = Store.endpoint(this).ifBlank { endpoints()[0].hostPort() }
                    startCore("warp", "libwarp.so", warpPlusArgs(true, ep, "$listen:$port", cache, help), dir)
                    status.value = status.value.substringBefore("\n") + "\nراه‌اندازی Psiphon..."
                    waitPort(port, 90)
                }
                "v2ray" -> {
                    val p = Store.selectedProfile(this) ?: error("کانفیگی انتخاب نشده (تب کانفیگ‌ها)")
                    val out = UriParser.parse(p.uri) ?: error("این لینک پشتیبانی نمی‌شود")
                    val via = Store.via(this)
                    var eps = emptyList<org.json.JSONObject>(); var last: String? = null
                    if (via != "none") { val c = warpChain(via, endpoints()); eps = c.first; last = c.second }
                    val cfg = File(dir, "sb.json").apply { writeText(ConfigBuilder.singBox(out, listen, port, eps, last)) }
                    startCore("sing-box", "libsingbox.so", listOf("run", "-c", cfg.path, "-D", dir.path), dir)
                    waitPort(port, 30)
                }
                else -> error("روش ناشناخته")
            }
            status.value = status.value.substringBefore("\n") + "\nبررسی اینترنت..."
            if (!verify(port)) error("پورت بالا آمد ولی اینترنت از این مسیر کار نکرد")
            return true
        } catch (e: Exception) {
            if (cancelled) throw e
            log("ناموفق: ${e.message}")
            return false
        }
    }

    private fun verify(port: Int): Boolean {
        val px = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        repeat(3) {
            if (cancelled) return false
            try {
                val c = URL("https://www.gstatic.com/generate_204").openConnection(px) as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val code = c.responseCode; c.disconnect()
                if (code in 200..399) { log("تست اینترنت OK (کد $code)"); return true }
            } catch (e: Exception) { log("تست اینترنت: ${e.javaClass.simpleName}") }
        }
        return false
    }

    private fun startCore(name: String, lib: String, args: List<String>, dir: File) {
        val bin = File(applicationInfo.nativeLibraryDir, lib)
        if (!bin.exists()) error("هسته $name داخل APK نیست")
        log("اجرا: $name ${args.joinToString(" ")}")
        val pb = ProcessBuilder(listOf(bin.path) + args).directory(dir).redirectErrorStream(true)
        pb.environment()["HOME"] = dir.path
        val p = pb.start(); procs += p
        Thread { try { p.inputStream.bufferedReader().forEachLine { log("[$name] $it") } } catch (_: Exception) {} }.start()
    }

    private fun waitPort(port: Int, seconds: Int) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            if (cancelled) error("لغو شد")
            if (procs.any { !it.isAlive }) error("هسته متوقف شد")
            try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }; return }
            catch (_: Exception) { Thread.sleep(400) }
        }
        error("پورت $port در $seconds ثانیه بالا نیامد")
    }

    private fun killProcs() {
        procs.forEach { try { it.destroyForcibly(); it.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) {} }
        procs.clear()
    }

    private fun stopAll() {
        running.value = false
        try { if (hevStarted) hev.stop() } catch (_: Throwable) {}
        hevStarted = false
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        killProcs()
        traffic.value = 0L to 0L; since.value = 0L; method.value = ""
        if (!status.value.startsWith("خطا")) status.value = ""
        log("قطع شد")
    }

    override fun onRevoke() { cancelled = true; stopAll(); stopSelf() }
    override fun onDestroy() { cancelled = true; stopAll(); super.onDestroy() }
}
