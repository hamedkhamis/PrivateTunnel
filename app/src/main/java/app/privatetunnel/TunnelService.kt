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
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit

/**
 * Same design as AetherST: the Aether core (a separate process) exposes a local SOCKS5 port,
 * hev-socks5-tunnel bridges the VpnService TUN to that port.
 */
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
            "auto" -> "خودکار"; "masque" -> "MASQUE (HTTP/2)"; "masque3" -> "MASQUE (HTTP/3)"
            "wg" -> "WireGuard"; "gool" -> "Gool"; "psiphon" -> "Psiphon + WARP"
            "psiphon_only" -> "فقط Psiphon"; "v2ray" -> "کانفیگ V2Ray"; else -> k
        }
    }

    private data class Step(val key: String)

    private val procs = mutableListOf<Process>()
    private val tail = ConcurrentLinkedDeque<String>()
    private var tun: ParcelFileDescriptor? = null
    private var dupFd: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val hev by lazy { TProxyService() }
    private var hevStarted = false
    @Volatile private var cancelled = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { cancelled = true; stopAll(); stopSelf(); return START_NOT_STICKY }
        startForeground(1, notification())
        if (worker?.isAlive != true) {
            busy.value = true
            worker = Thread {
                try { startAll() } catch (e: Throwable) {   // Throwable: native-library errors must not kill the app
                    val m = e.message ?: e.javaClass.simpleName
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

    private fun copyAsset(src: String, dst: File) {
        val kids = assets.list(src)
        if (kids.isNullOrEmpty()) {
            if (!dst.exists()) { dst.parentFile?.mkdirs(); assets.open(src).use { i -> dst.outputStream().use { i.copyTo(it) } } }
        } else { dst.mkdirs(); kids.forEach { copyAsset("$src/$it", File(dst, it)) } }
    }

    private fun portFree(p: Int) = try {
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress("127.0.0.1", p)) }; true
    } catch (e: Exception) { false }

    private fun steps(mode: String): List<Step> {
        if (mode != "auto") return listOf(Step(mode))
        val l = mutableListOf(Step("masque"), Step("masque3"), Step("wg"), Step("gool"), Step("psiphon"))
        if (Store.selectedProfile(this) != null) l += Step("v2ray")
        val last = Store.lastGood(this)
        l.sortBy { if (it.key == last) 0 else 1 }
        return l
    }

    private fun startAll() {
        cancelled = false
        status.value = "آماده‌سازی..."; method.value = ""
        killProcs()
        if (Sys.vpnActive(this)) error("یک VPN دیگر روشن است (مثلاً AetherST). اول آن را قطع کن، بعد وصل شو")
        val port = Store.port(this)
        val listen = if (Store.hotspot(this)) "0.0.0.0" else "127.0.0.1"
        val dir = filesDir
        if (!portFree(port) || !portFree(port + 1)) error("پورت $port اشغال است (برنامه دیگری استفاده می‌کند)")
        // identities pre-registered by the build (outside Iran); Aether keeps them and registers only if missing
        if (assets.list("aether-seed")?.isNotEmpty() == true) copyAsset("aether-seed", dir)

        val list = steps(Store.mode(this))
        var used = ""
        for ((i, st) in list.withIndex()) {
            if (cancelled) error("لغو شد")
            val label = modeLabel(st.key)
            status.value = if (list.size > 1) "تلاش ${i + 1} از ${list.size}: $label" else "اتصال با $label..."
            log("--- تلاش: $label")
            if (tryStep(st, port, listen, dir)) { used = st.key; break }
            killProcs()
        }
        if (used.isEmpty()) error("هیچ روشی وصل نشد. لاگ را ببین")
        if (Store.mode(this) == "auto") Store.setLastGood(this, used)
        if (cancelled) error("لغو شد")

        status.value = "ساخت تونل..."
        val mtu = 1320
        val b = Builder().setSession("PrivateTunnel").setMtu(mtu)
            .addAddress("198.18.0.1", 24).addAddress("fd00::1", 120)
            .addRoute("0.0.0.0", 0).addRoute("::", 0).addDnsServer("198.18.0.2")
        b.addDisallowedApplication(packageName)
        tun = b.establish() ?: error("VPN ساخته نشد (مجوز داده نشده؟)")
        val dup = ParcelFileDescriptor.dup(tun!!.fileDescriptor)   // hev owns this copy
        dupFd = dup
        val conf = File(dir, "hev.yml").apply { writeText(ConfigBuilder.hev(port, mtu)) }
        log("شروع hev با fd=${dup.fd}")
        hev.start(conf.path, dup.fd)
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

    /** command line and environment for the Aether core, same flags AetherST passes */
    private fun aetherCommand(key: String, listen: String, port: Int, dir: File): Triple<List<String>, Map<String, String>, Int> {
        val lib = applicationInfo.nativeLibraryDir
        val bin = File(lib, "libaether.so").path
        val psi = File(lib, "libpsiphon.so").path
        val noise = Store.noise(this); val scan = Store.scanMode(this)
        val h2 = Store.h2(this)
        val args = mutableListOf(bin)
        val env = mutableMapOf<String, String>()
        val psiphon = key == "psiphon" || key == "psiphon_only"
        // with --psiphon the proxy the app uses is the psiphon one, so the core's own SOCKS moves to port+1
        val bind = if (key == "psiphon") "127.0.0.1:${port + 1}" else "$listen:$port"
        args += listOf("--bind", bind, "--dual", "--quick-reconnect", "--validate-secs", "10", "--reconnect-secs", "2")
        env["AETHER_SOCKS"] = bind
        env["AETHER_SCAN"] = scan
        env["AETHER_NOIZE"] = noise
        env["AETHER_IP"] = "Dual"
        env["AETHER_QUICK_RECONNECT"] = "1"
        env["AETHER_REPROVISION"] = "1"
        env["AETHER_LOG_LEVEL"] = "info"
        env["AETHER_MASQUE_VALIDATE_SECS"] = "10"; env["AETHER_WG_VALIDATE_SECS"] = "10"
        env["AETHER_MASQUE_RECONNECT_SECS"] = "2"; env["AETHER_WG_RECONNECT_SECS"] = "2"
        val peer = Store.endpoint(this).trim()
        if (peer.isNotEmpty()) { args += listOf("--peer", peer); env["AETHER_PEER"] = peer }

        var wait = 120
        when (key) {
            "masque", "masque3", "psiphon" -> {
                env["AETHER_PROTOCOL"] = "masque"
                val useH2 = key == "masque" || key == "psiphon"
                if (useH2 && h2) { args += "--h2"; env["AETHER_MASQUE_HTTP2"] = "1" }
                if (key == "masque3") env["AETHER_MASQUE_HTTP2"] = "0"
            }
            "wg" -> {
                env["AETHER_PROTOCOL"] = "wg"
                args += listOf("--keepalive", "5"); env["AETHER_WG_KEEPALIVE"] = "5"
            }
            "gool" -> {
                env["AETHER_PROTOCOL"] = "gool"; wait = 150
                args += listOf("--keepalive", "5"); env["AETHER_WG_KEEPALIVE"] = "5"
                if (peer.isEmpty()) { args += "--wiw-scan"; env["AETHER_WIW_PEERS"] = "auto" }
            }
            "psiphon_only" -> { /* plain psiphon, no WARP tunnel */ }
        }
        if (psiphon) {
            wait = 220
            val region = Store.country(this).trim()
            args += if (key == "psiphon") listOf("--psiphon", "--psiphon-bind", "$listen:$port") else listOf("--psiphon-only")
            args += listOf("--psiphon-bin", psi, "--psiphon-dir", File(dir, "psiphon").apply { mkdirs() }.path)
            if (region.length == 2) args += listOf("--psiphon-region", region.uppercase())
            env["AETHER_PSIPHON_BIN"] = psi
            env["AETHER_PSIPHON_READY_SECS"] = "180"
        }
        return Triple(args, env, wait)
    }

    private fun tryStep(st: Step, port: Int, listen: String, dir: File): Boolean {
        try {
            when (st.key) {
                "v2ray" -> {
                    val p = Store.selectedProfile(this) ?: error("کانفیگی انتخاب نشده (تب کانفیگ‌ها)")
                    val out = UriParser.parse(p.uri) ?: error("این لینک پشتیبانی نمی‌شود")
                    val cfg = File(dir, "sb.json").apply { writeText(ConfigBuilder.singBox(out, listen, port)) }
                    startCore("sing-box", listOf(File(applicationInfo.nativeLibraryDir, "libsingbox.so").path,
                        "run", "-c", cfg.path, "-D", dir.path), emptyMap(), dir)
                    waitPort(port, 30)
                }
                else -> {
                    val (args, env, wait) = aetherCommand(st.key, listen, port, dir)
                    startCore("aether", args, env, dir)
                    status.value = status.value.substringBefore("\n") + "\nراه‌اندازی هسته Aether (اسکن و تأیید مسیر)..."
                    waitPort(port, wait)
                }
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

    private fun noteLine(line: String) {
        val l = line.lowercase()
        val hint = when {
            "tunnel validated" in l || "data-plane verification passed" in l -> "مسیر تأیید شد"
            "enrolling" in l || "regist" in l || "provision" in l -> "ثبت دستگاه در Cloudflare..."
            "scanning" in l || "sweep" in l -> "اسکن gateway سالم..."
            "validat" in l -> "تست مسیر داده..."
            "psiphon" in l -> "راه‌اندازی Psiphon..."
            else -> null
        }
        if (hint != null) status.value = status.value.substringBefore("\n") + "\n" + hint
    }

    private fun startCore(name: String, cmd: List<String>, env: Map<String, String>, dir: File) {
        val bin = File(cmd[0])
        if (!bin.exists()) error("هسته $name داخل APK نیست")
        log("اجرا: $name ${cmd.drop(1).joinToString(" ")}")
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
        pb.environment()["HOME"] = dir.path
        pb.environment()["TMPDIR"] = cacheDir.path
        pb.environment().putAll(env)
        tail.clear()
        val p = pb.start(); procs += p
        Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { l ->
                    log("[$name] $l"); tail.addLast(l.take(160)); while (tail.size > 6) tail.pollFirst()
                    if (name == "aether") noteLine(l)
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun waitPort(port: Int, seconds: Int) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            if (cancelled) error("لغو شد")
            if (procs.any { !it.isAlive }) error("هسته متوقف شد: " + tail.toList().takeLast(3).joinToString(" | "))
            try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }; return }
            catch (_: Exception) { Thread.sleep(400) }
        }
        error("پورت $port در $seconds ثانیه بالا نیامد: " + tail.toList().takeLast(2).joinToString(" | "))
    }

    private fun killProcs() {
        procs.forEach { try { it.destroyForcibly(); it.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) {} }
        procs.clear()
    }

    private fun stopAll() {
        running.value = false
        try { if (hevStarted) { hev.stop(); Thread.sleep(300) } } catch (_: Throwable) {}
        hevStarted = false
        try { dupFd?.close() } catch (_: Exception) {}
        dupFd = null
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
