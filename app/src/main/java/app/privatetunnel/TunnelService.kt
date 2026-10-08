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
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

class TunnelService : VpnService() {

    companion object {
        val running = MutableStateFlow(false)
        val busy = MutableStateFlow(false)
        val status = MutableStateFlow("")
        val since = MutableStateFlow(0L)
        val traffic = MutableStateFlow(0L to 0L) // up, down bytes
        val logs = MutableStateFlow<List<String>>(emptyList())
        fun log(s: String) { logs.value = (logs.value + s).takeLast(600) }

        fun hotspotAddresses(port: Int): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && it.isSiteLocalAddress && it.address.size == 4 }
                .map { "${it.hostAddress}:$port" }
        } catch (e: Exception) { emptyList() }
    }

    private val procs = mutableListOf<Process>()
    private var tun: ParcelFileDescriptor? = null
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
                try { startAll() } catch (e: Exception) {
                    val m = e.message ?: "نامشخص"
                    log("خطا: $m"); stopAll(); status.value = "خطا: $m"; stopSelf()
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

    private fun copyAsset(src: String, dst: File) {
        val kids = assets.list(src)
        if (kids.isNullOrEmpty()) {
            if (!dst.exists()) { dst.parentFile?.mkdirs(); assets.open(src).use { i -> dst.outputStream().use { i.copyTo(it) } } }
        } else { dst.mkdirs(); kids.forEach { copyAsset("$src/$it", File(dst, it)) } }
    }

    private fun portFree(p: Int) = try {
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress("127.0.0.1", p)) }; true
    } catch (e: Exception) { false }

    private fun startAll() {
        cancelled = false
        status.value = "آماده‌سازی..."
        killProcs()

        val base = Store.base(this); val psi = Store.psiphon(this); val useProxy = Store.useProxy(this)
        val needWarp = base != "none" || psi
        if (!needWarp && !useProxy) error("هیچ لایه‌ای انتخاب نشده")
        val port = Store.port(this)
        val listen = if (Store.hotspot(this)) "0.0.0.0" else "127.0.0.1"
        val dir = filesDir
        val cache = File(dir, "warp").apply { mkdirs() }
        val warpPort = if (useProxy) port + 1 else port
        if (!portFree(port) || !portFree(port + 1)) error("پورت $port اشغال است (برنامه دیگری استفاده می‌کند)")
        log("شروع: base=$base psiphon=$psi proxy=$useProxy port=$port listen=$listen")

        if (needWarp) {
            if (assets.list("warp-seed")?.isNotEmpty() == true) copyAsset("warp-seed", cache)
            val help = helpText()
            fun need(flag: String) { if (help.isNotEmpty() && !help.contains(flag)) error("این نسخه warp-plus از $flag پشتیبانی نمی‌کند") }
            if (base == "gool") need("--gool")
            if (psi) need("--cfon")
            if (Store.endpoint(this).isNotBlank()) need("--endpoint")
            if (Store.scan(this)) need("--scan")
            val args = ConfigBuilder.warpArgs(base, psi, Store.country(this).ifBlank { "US" }, Store.scan(this),
                help.contains("--rtt"), Store.endpoint(this), (if (useProxy) "127.0.0.1" else listen) + ":$warpPort", cache.path)
            status.value = "اتصال به Cloudflare / اسکن endpoint (تا ۲ دقیقه)..."
            startCore("warp", "libwarp.so", args, dir)
            waitPort(warpPort, 150)
        }
        if (useProxy) {
            val p = Store.selectedProfile(this) ?: error("پروفایلی انتخاب نشده")
            val out = UriParser.parse(p.uri) ?: error("این لینک پشتیبانی نمی‌شود")
            val cfg = File(dir, "sb.json").apply {
                writeText(ConfigBuilder.singBox(out, listen, port, if (needWarp) warpPort else null))
            }
            status.value = "راه‌اندازی sing-box..."
            startCore("sing-box", "libsingbox.so", listOf("run", "-c", cfg.path, "-D", dir.path), dir)
            waitPort(port, 30)
        }
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
        since.value = System.currentTimeMillis()
        running.value = true
        status.value = "متصل"
        log("متصل شد")
        Thread {
            while (running.value) {
                try { hev.stats()?.let { traffic.value = it[1] to it[3] } } catch (_: Throwable) {}
                Thread.sleep(1000)
            }
        }.start()
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
            if (procs.any { !it.isAlive }) error("هسته متوقف شد، تب لاگ را ببین")
            try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }; return }
            catch (_: Exception) { Thread.sleep(400) }
        }
        error("پورت $port بالا نیامد (تایم‌اوت)")
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
        traffic.value = 0L to 0L
        since.value = 0L
        if (!status.value.startsWith("خطا")) status.value = ""
        log("قطع شد")
    }

    override fun onRevoke() { cancelled = true; stopAll(); stopSelf() }
    override fun onDestroy() { cancelled = true; stopAll(); super.onDestroy() }
}
