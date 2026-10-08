package app.privatetunnel

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class Cfg(
    val uri: String, val name: String, val proto: String, val host: String, val port: Int,
    val delay: Int = -1, val cc: String = "", val speed: Double = -1.0
)

/**
 * Downloads public V2Ray lists, removes duplicates, and tests every config for real:
 * one sing-box process loads a whole batch and its clash API measures a real request
 * through each outbound. Alive configs are then geolocated and can be speed-tested.
 */
object Collector {
    val SOURCES = listOf(
        "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/All_Configs_Sub.txt",
        "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
        "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/V2Ray-Config-By-EbraSha.txt",
        "https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list_raw.txt",
        "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/v2ray.txt",
        "https://raw.githubusercontent.com/MatinGhanbari/v2ray-CONFIGs/main/subscriptions/v2ray/super-sub.txt",
        "https://raw.githubusercontent.com/hamedcode/port-based-v2ray-configs/main/sub/vless.txt"
    )
    private const val TEST_URL = "https://www.gstatic.com/generate_204"
    private val SCHEMES = listOf("vless://", "vmess://", "trojan://", "ss://", "hy2://", "hysteria2://", "tuic://")

    val running = MutableStateFlow(false)
    val speedRunning = MutableStateFlow(false)
    val phase = MutableStateFlow("")
    val progress = MutableStateFlow(0 to 0)
    val alive = MutableStateFlow(0)
    val results = MutableStateFlow<List<Cfg>>(emptyList())

    @Volatile private var cancelled = false
    @Volatile private var proc: Process? = null

    fun cancel() { cancelled = true; try { proc?.destroyForcibly() } catch (_: Exception) {} }

    private fun proxyFor(ctx: Context): Proxy =
        if (TunnelService.running.value) Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", Store.port(ctx)))
        else Proxy.NO_PROXY

    private fun fetchText(url: String, px: Proxy): String = try {
        val c = URL(url).openConnection(px) as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 25000; c.setRequestProperty("User-Agent", "v2rayN/6.0")
        val t = c.inputStream.bufferedReader().readText().trim()
        if (t.contains("://")) t else String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: Exception) { "" }

    private fun seedLines(ctx: Context): List<String> = try {
        ctx.assets.open("configs-seed.txt").bufferedReader().readLines()
    } catch (e: Exception) { emptyList() }

    private fun isPrivate(h: String) = h.isEmpty() || h.startsWith("127.") || h.startsWith("10.") ||
        h.startsWith("192.168.") || h == "0.0.0.0" || h == "localhost"

    private fun flagCc(name: String): String {
        val cps = name.codePoints().toArray()
        for (i in 0 until cps.size - 1) {
            if (cps[i] in 0x1F1E6..0x1F1FF && cps[i + 1] in 0x1F1E6..0x1F1FF)
                return "" + ('A' + (cps[i] - 0x1F1E6)) + ('A' + (cps[i + 1] - 0x1F1E6))
        }
        return ""
    }

    private fun sbBin(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libsingbox.so")

    private fun check(ctx: Context, list: List<Pair<Cfg, JSONObject>>): Boolean = try {
        val f = File(ctx.filesDir, "chk.json").apply { writeText(ConfigBuilder.delayConfig(list.map { it.second })) }
        val p = ProcessBuilder(sbBin(ctx).path, "check", "-c", f.path, "-D", ctx.filesDir.path)
            .redirectErrorStream(true).start()
        Thread { try { p.inputStream.readBytes() } catch (_: Exception) {} }.start()
        p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (e: Exception) { false }

    /** keeps only the configs sing-box accepts (bisects away the broken ones) */
    private fun validBatch(ctx: Context, list: List<Pair<Cfg, JSONObject>>): List<Pair<Cfg, JSONObject>> {
        if (list.isEmpty() || cancelled) return emptyList()
        if (check(ctx, list)) return list
        if (list.size == 1) return emptyList()
        val mid = list.size / 2
        return validBatch(ctx, list.subList(0, mid)) + validBatch(ctx, list.subList(mid, list.size))
    }

    private fun apiDelay(i: Int): Int = try {
        val u = "http://127.0.0.1:19090/proxies/o$i/delay?url=" + URLEncoder.encode(TEST_URL, "UTF-8") + "&timeout=6000"
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 10000
        if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()).optInt("delay", -1) else -1
    } catch (e: Exception) { -1 }

    private fun apiReady(): Boolean {
        repeat(30) {
            try {
                val c = URL("http://127.0.0.1:19090/version").openConnection() as HttpURLConnection
                c.connectTimeout = 500; c.readTimeout = 1000
                if (c.responseCode == 200) return true
            } catch (_: Exception) {}
            Thread.sleep(300)
        }
        return false
    }

    private fun geo(hosts: List<String>, px: Proxy): List<String> = try {
        val c = URL("http://ip-api.com/batch?fields=countryCode").openConnection(px) as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONArray().apply { hosts.forEach { put(JSONObject().put("query", it)) } }.toString()
        c.outputStream.use { it.write(body.toByteArray()) }
        val arr = JSONArray(c.inputStream.bufferedReader().readText())
        (0 until arr.length()).map { arr.getJSONObject(it).optString("countryCode", "") }
    } catch (e: Exception) { emptyList() }

    suspend fun run(ctx: Context, limit: Int) = withContext(Dispatchers.IO) {
        if (running.value) return@withContext
        running.value = true; cancelled = false
        results.value = emptyList(); alive.value = 0; progress.value = 0 to 0
        try {
            val px = proxyFor(ctx)
            phase.value = "دریافت لیست‌ها از منابع آنلاین..."
            val srcs = Collector.SOURCES + Store.extraSources(ctx)
            val texts = coroutineScope { srcs.map { s -> async { fetchText(s, px) } }.awaitAll() }
            var lines = texts.flatMap { it.lines() }
            if (texts.all { it.isEmpty() }) {
                phase.value = "منابع آنلاین در دسترس نبود، از لیست داخل APK استفاده می‌شود"
                lines = seedLines(ctx)
            }
            phase.value = "پردازش و حذف تکراری‌ها..."
            val seen = HashSet<String>()
            val parsed = ArrayList<Pair<Cfg, JSONObject>>()
            for (raw in lines.map { it.trim() }.filter { l -> SCHEMES.any { l.startsWith(it) } }.distinct().shuffled()) {
                if (parsed.size >= limit) break
                val o = UriParser.parse(raw) ?: continue
                val host = o.optString("server"); val port = o.optInt("server_port")
                if (port <= 0 || isPrivate(host)) continue
                val key = o.optString("type") + "|" + host + "|" + port + "|" +
                    o.optString("uuid", o.optString("password"))
                if (!seen.add(key)) continue
                parsed += Cfg(raw, UriParser.name(raw), o.optString("type"), host, port) to o
            }
            if (parsed.isEmpty()) { phase.value = "هیچ کانفیگ قابل‌استفاده‌ای پیدا نشد"; return@withContext }
            val total = parsed.size
            val done = AtomicInteger(0)
            progress.value = 0 to total

            for (batch in parsed.chunked(250)) {
                if (cancelled) break
                phase.value = "بررسی ساختار کانفیگ‌ها..."
                val valid = validBatch(ctx, batch)
                done.addAndGet(batch.size - valid.size); progress.value = done.get() to total
                if (valid.isEmpty() || cancelled) continue

                phase.value = "تست اتصال واقعی..."
                val f = File(ctx.filesDir, "ct.json").apply { writeText(ConfigBuilder.delayConfig(valid.map { it.second })) }
                val p = ProcessBuilder(sbBin(ctx).path, "run", "-c", f.path, "-D", ctx.filesDir.path)
                    .redirectErrorStream(true).start()
                proc = p
                Thread { try { p.inputStream.readBytes() } catch (_: Exception) {} }.start()
                try {
                    if (!apiReady()) { done.addAndGet(valid.size); progress.value = done.get() to total; continue }
                    val sem = Semaphore(40)
                    coroutineScope {
                        valid.mapIndexed { i, (cfg, _) ->
                            async {
                                sem.withPermit {
                                    if (!cancelled) {
                                        val d = apiDelay(i)
                                        if (d > 0) synchronized(this@Collector) {
                                            results.value = (results.value + cfg.copy(delay = d)).sortedBy { it.delay }
                                            alive.value = results.value.size
                                        }
                                    }
                                    progress.value = done.incrementAndGet() to total
                                }
                            }
                        }.awaitAll()
                    }
                } finally { try { p.destroyForcibly() } catch (_: Exception) {}; proc = null }
            }

            if (results.value.isNotEmpty() && !cancelled) {
                phase.value = "تشخیص کشور..."
                val list = results.value.map { it.copy(cc = flagCc(it.name)) }.toMutableList()
                val need = list.withIndex().filter { it.value.cc.isEmpty() }.take(200)
                need.chunked(100).forEachIndexed { n, chunk ->
                    if (n > 0) delay(4200)
                    val ccs = geo(chunk.map { it.value.host }, px)
                    chunk.forEachIndexed { k, iv -> if (k < ccs.size) list[iv.index] = list[iv.index].copy(cc = ccs[k]) }
                }
                results.value = list
            }
            phase.value = if (cancelled) "متوقف شد" else "تمام شد: ${results.value.size} کانفیگ سالم از $total"
        } catch (e: Exception) {
            phase.value = "خطا: ${e.message}"
        } finally { running.value = false }
    }

    /** real download speed through each of the best configs */
    suspend fun speedTest(ctx: Context, n: Int = 12) = withContext(Dispatchers.IO) {
        if (speedRunning.value || running.value) return@withContext
        speedRunning.value = true; cancelled = false
        try {
            val top = results.value.sortedBy { it.delay }.take(n)
            val outs = top.mapNotNull { c -> UriParser.parse(c.uri)?.let { c to it } }
            if (outs.isEmpty()) return@withContext
            phase.value = "تست سرعت دانلود..."
            val base = 19100
            val f = File(ctx.filesDir, "cs.json").apply { writeText(ConfigBuilder.speedConfig(outs.map { it.second }, base)) }
            val p = ProcessBuilder(sbBin(ctx).path, "run", "-c", f.path, "-D", ctx.filesDir.path)
                .redirectErrorStream(true).start()
            proc = p
            Thread { try { p.inputStream.readBytes() } catch (_: Exception) {} }.start()
            try {
                var up = false
                repeat(30) {
                    if (!up) try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", base), 300) }; up = true }
                    catch (_: Exception) { Thread.sleep(300) }
                }
                if (!up) { phase.value = "هسته تست سرعت بالا نیامد"; return@withContext }
                val sem = Semaphore(3)
                coroutineScope {
                    outs.mapIndexed { i, (cfg, _) ->
                        async {
                            sem.withPermit {
                                val mbps = download(base + i)
                                synchronized(this@Collector) {
                                    results.value = results.value.map { if (it.uri == cfg.uri) it.copy(speed = mbps) else it }
                                }
                            }
                        }
                    }.awaitAll()
                }
                phase.value = "تست سرعت تمام شد"
            } finally { try { p.destroyForcibly() } catch (_: Exception) {}; proc = null }
        } finally { speedRunning.value = false }
    }

    private fun download(port: Int): Double = try {
        val px = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val c = URL("https://speed.cloudflare.com/__down?bytes=3000000").openConnection(px) as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        val t0 = System.nanoTime(); var total = 0L
        val buf = ByteArray(32768)
        c.inputStream.use { s ->
            while (true) {
                val r = s.read(buf); if (r <= 0) break
                total += r
                if ((System.nanoTime() - t0) > 8_000_000_000L) break
            }
        }
        val sec = (System.nanoTime() - t0) / 1e9
        if (sec <= 0 || total < 50_000) 0.0 else total * 8 / 1e6 / sec
    } catch (e: Exception) { 0.0 }
}
