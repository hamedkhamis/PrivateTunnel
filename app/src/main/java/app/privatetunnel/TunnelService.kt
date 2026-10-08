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
import java.net.Socket

class TunnelService : VpnService() {

    companion object {
        val running = MutableStateFlow(false)
        val busy = MutableStateFlow(false)
        val logs = MutableStateFlow<List<String>>(emptyList())
        fun log(s: String) { logs.value = (logs.value + s).takeLast(500) }

        /** addresses other devices on the hotspot can use */
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopAll(); stopSelf(); return START_NOT_STICKY }
        startForeground(1, notification())
        if (worker == null) {
            busy.value = true
            worker = Thread {
                try { startAll() } catch (e: Exception) {
                    log("خطا: ${e.message}"); stopAll(); stopSelf()
                } finally { busy.value = false }
            }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pt", "Tunnel", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "pt")
            .setContentTitle("Private Tunnel")
            .setContentText("اتصال فعال است")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true).build()
    }

    private fun startAll() {
        val mode = Store.mode(this)
        val port = Store.port(this)
        val listen = if (Store.hotspot(this)) "0.0.0.0" else "127.0.0.1"
        val dir = filesDir
        val cache = File(dir, "warp").apply { mkdirs() }
        val country = Store.country(this).ifBlank { "US" }
        val scan = Store.scan(this)
        log("شروع: mode=$mode port=$port listen=$listen")

        if (mode == "proxy") {
            val p = Store.selectedProfile(this) ?: error("هیچ پروفایلی انتخاب نشده")
            val out = UriParser.parse(p.uri) ?: error("این لینک پشتیبانی نمی‌شود")
            var chain: Int? = null
            if (Store.chain(this)) {
                chain = port + 1
                startCore("warp", "libwarp.so",
                    ConfigBuilder.warpArgs("warp", "127.0.0.1:$chain", country, scan, cache.path), dir)
                waitPort(chain)
            }
            val cfg = File(dir, "sb.json").apply { writeText(ConfigBuilder.singBox(out, listen, port, chain)) }
            startCore("sing-box", "libsingbox.so", listOf("run", "-c", cfg.path, "-D", dir.path), dir)
        } else {
            startCore("warp", "libwarp.so",
                ConfigBuilder.warpArgs(mode, "$listen:$port", country, scan, cache.path), dir)
        }
        waitPort(port)

        val b = Builder().setSession("PrivateTunnel").setMtu(1500)
            .addAddress("198.18.0.1", 32).addAddress("fd00::1", 126)
            .addRoute("0.0.0.0", 0).addRoute("::", 0)
            .addDnsServer("198.18.0.2")
        try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}
        tun = b.establish() ?: error("VPN ساخته نشد (مجوز داده نشده؟)")

        val conf = File(dir, "hev.yml").apply { writeText(ConfigBuilder.hev(port)) }
        hev.start(conf.path, tun!!.fd)
        hevStarted = true
        running.value = true
        log("متصل شد")
    }

    private fun startCore(name: String, lib: String, args: List<String>, dir: File) {
        val bin = File(applicationInfo.nativeLibraryDir, lib)
        if (!bin.exists()) error("هسته $name داخل APK نیست")
        log("اجرا: $name ${args.joinToString(" ")}")
        val pb = ProcessBuilder(listOf(bin.path) + args).directory(dir).redirectErrorStream(true)
        pb.environment()["HOME"] = dir.path
        val p = pb.start()
        procs += p
        Thread {
            try { p.inputStream.bufferedReader().forEachLine { log("[$name] $it") } } catch (_: Exception) {}
        }.start()
    }

    private fun waitPort(port: Int) {
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end) {
            if (procs.any { !it.isAlive }) error("هسته متوقف شد، لاگ را ببین")
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                return
            } catch (_: Exception) { Thread.sleep(400) }
        }
        error("پورت $port بالا نیامد (تایم‌اوت)")
    }

    private fun stopAll() {
        running.value = false
        try { if (hevStarted) hev.stop() } catch (_: Throwable) {}
        hevStarted = false
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        procs.forEach { try { it.destroy() } catch (_: Exception) {} }
        procs.clear()
        worker = null
        log("قطع شد")
    }

    override fun onRevoke() { stopAll(); stopSelf() }
    override fun onDestroy() { stopAll(); super.onDestroy() }
}
