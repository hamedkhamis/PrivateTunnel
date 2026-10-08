package app.privatetunnel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

/** Probes what the current network lets through. Runs outside the VPN (the app is excluded from it). */
object NetTest {
    data class Row(val group: String, val name: String, val ok: Boolean?, val detail: String)
    private class Bad(msg: String) : Exception(msg)
    private class T(val group: String, val name: String, val fn: () -> String)

    val rows = MutableStateFlow<List<Row>>(emptyList())
    val running = MutableStateFlow(false)
    val summary = MutableStateFlow<List<String>>(emptyList())
    val suggestion = MutableStateFlow<Preset?>(null)
    val clean = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val scanning = MutableStateFlow(false)

    private const val CF = "104.17.0.1"
    private val PORTS = listOf(80, 443, 2053, 2083, 2087, 2096, 8443, 8080, 8880, 2052, 2082, 2086, 2095)
    private val SNIS = listOf("www.cloudflare.com", "www.google.com", "www.microsoft.com", "www.apple.com",
        "cdnjs.cloudflare.com", "www.speedtest.net", "www.wikipedia.org", "dash.cloudflare.com")
    private val SITES = listOf("www.google.com", "gemini.google.com", "www.youtube.com", "www.instagram.com",
        "telegram.org", "github.com", "raw.githubusercontent.com", "api.cloudflareclient.com",
        "speed.cloudflare.com", "chatgpt.com", "x.com")

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun tls(ip: String, port: Int, sni: String, timeout: Int): Int {
        val t0 = System.currentTimeMillis()
        val c = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        val s = c.socketFactory.createSocket() as SSLSocket
        try {
            s.soTimeout = timeout
            s.connect(InetSocketAddress(ip, port), timeout)
            val p = s.sslParameters; p.serverNames = listOf(SNIHostName(sni)); s.sslParameters = p
            s.startHandshake()
        } finally { try { s.close() } catch (_: Exception) {} }
        return (System.currentTimeMillis() - t0).toInt()
    }

    private fun httpGet(url: String, accept: String? = null): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = false
        accept?.let { c.setRequestProperty("Accept", it) }
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        return try { c.responseCode } finally { c.disconnect() }
    }

    private fun udpDns(server: String): String {
        val q = java.io.ByteArrayOutputStream()
        q.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        "example.com".split(".").forEach { q.write(it.length); q.write(it.toByteArray()) }
        q.write(0); q.write(byteArrayOf(0, 1, 0, 1))
        DatagramSocket().use { s ->
            s.soTimeout = 3000
            val b = q.toByteArray()
            s.send(DatagramPacket(b, b.size, InetAddress.getByName(server), 53))
            val r = ByteArray(512); val p = DatagramPacket(r, r.size); s.receive(p)
            if (p.length < 12) throw Bad("پاسخ نامعتبر")
        }
        return "پاسخ آمد"
    }

    private fun tests(): List<T> {
        val l = mutableListOf<T>()
        l += T("DNS", "DNS سیستم (google.com)") {
            val ips = InetAddress.getAllByName("www.google.com").map { it.hostAddress ?: "" }
            val bad = ips.firstOrNull { it.startsWith("10.") || it.startsWith("127.") || it.startsWith("192.168.") || it == "0.0.0.0" }
            if (bad != null) throw Bad("دستکاری‌شده ($bad)")
            ips.first()
        }
        l += T("DNS", "DoH روی 1.1.1.1") {
            val c = httpGet("https://1.1.1.1/dns-query?name=www.google.com&type=A", "application/dns-json")
            if (c != 200) throw Bad("کد $c"); "OK"
        }
        l += T("DNS", "DoH روی 8.8.8.8") {
            val c = httpGet("https://8.8.8.8/resolve?name=www.google.com&type=A")
            if (c != 200) throw Bad("کد $c"); "OK"
        }
        l += T("UDP", "UDP پورت 53 به 1.1.1.1") { udpDns("1.1.1.1") }
        l += T("UDP", "UDP پورت 53 به 8.8.8.8") { udpDns("8.8.8.8") }
        PORTS.forEach { p ->
            l += T("پورت TCP روی Cloudflare", "پورت $p") {
                Socket().use { it.connect(InetSocketAddress(CF, p), 3000) }; "باز"
            }
        }
        SNIS.forEach { sni ->
            l += T("TLS و SNI (به IP کلودفلر)", sni) { "handshake " + tls(CF, 443, sni, 4000) + "ms" }
        }
        SITES.forEach { h ->
            l += T("سایت‌ها (مستقیم)", h) {
                val c = httpGet("https://$h/"); "HTTP $c"
            }
        }
        return l
    }

    suspend fun run(ctx: Context) = withContext(Dispatchers.IO) {
        if (running.value) return@withContext
        running.value = true; summary.value = emptyList(); suggestion.value = null
        val ts = tests()
        rows.value = ts.map { Row(it.group, it.name, null, "...") }
        val sem = Semaphore(10)
        coroutineScope {
            ts.map { t ->
                async {
                    sem.withPermit {
                        val t0 = System.currentTimeMillis()
                        val r = try {
                            val d = t.fn(); Row(t.group, t.name, true, "$d  ·  ${System.currentTimeMillis() - t0}ms")
                        } catch (e: Bad) { Row(t.group, t.name, false, e.message ?: "")
                        } catch (e: Exception) {
                            Row(t.group, t.name, false, (e.javaClass.simpleName.replace("Exception", "")) + (e.message?.let { ": " + it.take(40) } ?: ""))
                        }
                        synchronized(this@NetTest) {
                            rows.value = rows.value.map { if (it.group == r.group && it.name == r.name) r else it }
                        }
                    }
                }
            }.awaitAll()
        }
        build()
        Store.setTestDone(ctx, true)
        running.value = false
    }

    private fun ok(g: String, n: String) = rows.value.firstOrNull { it.group == g && it.name == n }?.ok == true

    private fun build() {
        val r = rows.value
        val out = mutableListOf<String>()
        val dnsBad = r.firstOrNull { it.name.startsWith("DNS سیستم") }?.ok == false
        out += if (dnsBad) "DNS شبکه دستکاری شده است. اپ DNS را از داخل تونل حل می‌کند، پس مشکلی نیست."
               else "DNS شبکه سالم به نظر می‌رسد."
        val udp = r.any { it.group == "UDP" && it.ok == true }
        out += if (udp) "UDP عبور می‌کند، پس WARP/Gool/Hysteria2/TUIC شانس دارند."
               else "UDP بسته به نظر می‌رسد. پروتکل‌های TCP (Reality، WS+TLS، Trojan) را اولویت بده."
        val tcp443 = ok("پورت TCP روی Cloudflare", "پورت 443")
        out += if (tcp443) "TCP 443 باز است." else "TCP 443 به Cloudflare بسته است. وضعیت شبکه خیلی سخت است."
        val openPorts = r.filter { it.group.startsWith("پورت") && it.ok == true }.map { it.name.removePrefix("پورت ") }
        if (openPorts.isNotEmpty()) out += "پورت‌های باز: " + openPorts.joinToString(", ")
        val sni = r.filter { it.group.startsWith("TLS") && it.ok == true }.map { it.name }
        out += if (sni.isNotEmpty()) "SNIهای عبوری: " + sni.joinToString(", ")
               else "هیچ SNI ای عبور نکرد، احتمالاً TLS فیلتر شده."
        val sites = r.filter { it.group.startsWith("سایت") }
        out += "سایت‌های مستقیم باز: ${sites.count { it.ok == true }} از ${sites.size}"
        out += if (ok("سایت‌ها (مستقیم)", "api.cloudflareclient.com")) "ثبت‌نام مستقیم WARP ممکن است."
               else "ثبت‌نام مستقیم WARP از این شبکه ممکن نیست؛ مشکلی نیست، هویت داخل APK است."
        summary.value = out
        suggestion.value = when {
            udp -> Preset("Gool + Psiphon", "gool", true, false)
            tcp443 -> Preset("پروکسی", "none", false, true)
            else -> null
        }
    }

    private fun randomCfIp(): String {
        fun r(n: Int) = Random.nextInt(n)
        fun h() = 1 + r(254)
        return when (r(7)) {
            0 -> "104.${16 + r(16)}.${r(256)}.${h()}"
            1 -> "172.${64 + r(8)}.${r(256)}.${h()}"
            2 -> "188.114.${96 + r(4)}.${h()}"
            3 -> "162.159.${r(256)}.${h()}"
            4 -> "141.101.${64 + r(64)}.${h()}"
            5 -> "108.162.${192 + r(64)}.${h()}"
            else -> "173.245.${48 + r(16)}.${h()}"
        }
    }

    /** finds Cloudflare IPs that complete a TLS handshake fast from this network */
    suspend fun scanClean() = withContext(Dispatchers.IO) {
        if (scanning.value) return@withContext
        scanning.value = true; clean.value = emptyList()
        val found = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        val sem = Semaphore(60)
        coroutineScope {
            (1..320).map {
                async {
                    sem.withPermit {
                        val ip = randomCfIp()
                        try { found += ip to tls(ip, 443, "www.cloudflare.com", 2500) } catch (_: Exception) {}
                    }
                }
            }.awaitAll()
        }
        clean.value = found.sortedBy { it.second }.take(15)
        scanning.value = false
    }
}
