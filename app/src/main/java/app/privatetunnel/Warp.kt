package app.privatetunnel

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** WARP / Gool through sing-box's WireGuard endpoint, with our own Cloudflare endpoint finder. */
object Warp {
    class Id(val priv: String, val pub: String, val addrs: List<String>, val reserved: List<Int>)
    /** rz = true when the endpoint worked only with reserved bytes 0,0,0 */
    data class Hit(val ip: String, val port: Int, val ms: Int, val rz: Boolean = false) {
        fun hostPort() = if (ip.contains(":")) "[$ip]:$port" else "$ip:$port"
    }

    fun withReserved(id: Id, zero: Boolean) = if (zero) Id(id.priv, id.pub, id.addrs, listOf(0, 0, 0)) else id

    fun seed(ctx: Context) {
        if (ctx.assets.list("warp-seed")?.isNotEmpty() == true) copy(ctx, "warp-seed", File(ctx.filesDir, "warp"))
    }

    private fun copy(ctx: Context, src: String, dst: File) {
        val kids = ctx.assets.list(src)
        if (kids.isNullOrEmpty()) {
            if (!dst.exists()) { dst.parentFile?.mkdirs(); ctx.assets.open(src).use { i -> dst.outputStream().use { i.copyTo(it) } } }
        } else { dst.mkdirs(); kids.forEach { copy(ctx, "$src/$it", File(dst, it)) } }
    }

    /** reads the identity file made by warp-plus (wgcf-identity.json) */
    fun load(ctx: Context, which: String): Id? = try {
        val f = File(ctx.filesDir, "warp/$which/wgcf-identity.json")
        if (!f.exists()) null else {
            val j = JSONObject(f.readText())
            val cfg = j.getJSONObject("config")
            val pub = cfg.getJSONArray("peers").getJSONObject(0).getString("public_key")
            val a = cfg.getJSONObject("interface").getJSONObject("addresses")
            val addrs = listOfNotNull(
                a.optString("v4").takeIf { it.isNotEmpty() }?.let { "$it/32" },
                a.optString("v6").takeIf { it.isNotEmpty() }?.let { "$it/128" })
            val reserved = try {
                Base64.decode(cfg.optString("client_id"), Base64.DEFAULT).map { it.toInt() and 0xff }.take(3)
            } catch (e: Exception) { listOf(0, 0, 0) }.let { if (it.size == 3) it else listOf(0, 0, 0) }
            Id(j.getString("private_key"), pub, addrs, reserved)
        }
    } catch (e: Exception) { null }

    fun endpoint(tag: String, id: Id, ip: String, port: Int, detour: String?, mtu: Int): JSONObject {
        val peer = JSONObject().put("address", ip).put("port", port).put("public_key", id.pub)
            .put("allowed_ips", JSONArray().put("0.0.0.0/0").put("::/0"))
            .put("reserved", JSONArray(id.reserved))
            .put("persistent_keepalive_interval", 25)
        val e = JSONObject().put("type", "wireguard").put("tag", tag).put("system", false).put("mtu", mtu)
            .put("address", JSONArray(id.addrs)).put("private_key", id.priv)
            .put("peers", JSONArray().put(peer))
        if (detour != null) e.put("detour", detour)
        return e
    }

    private val PORTS = listOf(500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939, 942, 943, 945, 946,
        955, 968, 987, 988, 1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387, 1701, 1843, 2371, 2408, 2506, 3138, 3476,
        3581, 3854, 4177, 4198, 4233, 4500, 5279, 5956, 7103, 7152, 7156, 7281, 7559, 8319, 8742, 8854, 8886)
    private val V4 = listOf("162.159.192", "162.159.195", "188.114.96", "188.114.97", "188.114.98", "188.114.99")
    private val COMMON = listOf(2408, 500, 4500, 1701)

    fun candidates(n: Int, skip: Set<String>): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        var guard = 0
        while (out.size < n && guard++ < n * 20) {
            val port = if (Random.nextInt(3) == 0) COMMON.random() else PORTS.random()
            // most networks here let IPv6 through more often, so it gets 2/3 of the tries
            val ip = if (Random.nextInt(3) < 2)
                String.format("2606:4700:d%d:0:%x:%x:%x:%x", Random.nextInt(2), Random.nextInt(65536),
                    Random.nextInt(65536), Random.nextInt(65536), Random.nextInt(65536))
            else V4.random() + "." + (1 + Random.nextInt(254))
            if ("$ip|$port" in skip) continue
            out += ip to port
        }
        return out
    }

    private class Cand(val ip: String, val port: Int, val rz: Boolean)

    private fun probeConfig(id: Id, batch: List<Cand>, base: Int): String {
        val ins = JSONArray(); val eps = JSONArray(); val rules = JSONArray()
        batch.forEachIndexed { i, c ->
            ins.put(JSONObject().put("type", "mixed").put("tag", "in$i").put("listen", "127.0.0.1").put("listen_port", base + i))
            eps.put(endpoint("w$i", withReserved(id, c.rz), c.ip, c.port, null, 1280))
            rules.put(JSONObject().put("inbound", JSONArray().put("in$i")).put("outbound", "w$i"))
        }
        return JSONObject().put("log", JSONObject().put("level", "debug"))
            .put("inbounds", ins).put("endpoints", eps)
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject().put("rules", rules).put("final", "direct")).toString()
    }

    /**
     * Finds Cloudflare WARP endpoints that really work from this network.
     * 1) endpoints saved from last time, 2) endpoints found by the warp-plus scanner (it is good at finding them
     * on this kind of network, only its WireGuard engine is broken on Android), 3) a few random ones.
     * Every candidate is verified by a real request through sing-box's WireGuard; the answer must say warp=on.
     */
    class Finder(
        private val ctx: Context,
        private val procs: MutableList<Process>,
        private val cancelled: () -> Boolean,
        private val status: (String) -> Unit
    ) {
        private fun log(m: String) = TunnelService.log("[warp] $m")

        fun find(): List<Hit> {
            val id = load(ctx, "primary") ?: error("هویت WARP داخل APK نیست؛ workflow را دوباره اجرا کن")
            val tried = HashSet<String>()
            val hits = ArrayList<Hit>()

            fun both(l: List<Pair<String, Int>>) = l.flatMap { listOf(Cand(it.first, it.second, false), Cand(it.first, it.second, true)) }

            // 1) saved + custom
            val pre = ArrayList<Pair<String, Int>>()
            val s = Store.endpoint(ctx).trim()
            val cip = s.substringBeforeLast(":", "").trim('[', ']'); val cp = s.substringAfterLast(":", "").toIntOrNull()
            if (cip.isNotEmpty() && cp != null) pre += cip to cp
            pre += Store.warpEndpoints(ctx).take(6)
            if (pre.isNotEmpty()) {
                status("بررسی endpointهای ذخیره‌شده...")
                pre.forEach { tried += "${it.first}|${it.second}" }
                hits += probe(id, both(pre))
            }

            // 2) warp-plus scanner
            if (hits.isEmpty() && !cancelled()) {
                val found = harvest().filter { "${it.first}|${it.second}" !in tried }
                if (found.isNotEmpty()) {
                    status("تست endpointهای پیدا شده...")
                    found.forEach { tried += "${it.first}|${it.second}" }
                    hits += probe(id, both(found))
                }
            }

            // 3) random
            var b = 0
            while (hits.isEmpty() && b < 2 && !cancelled()) {
                status("اسکن تصادفی endpoint: دسته ${b + 1} از 2")
                val c = candidates(16, tried); c.forEach { tried += "${it.first}|${it.second}" }
                hits += probe(id, c.map { Cand(it.first, it.second, false) })
                b++
            }
            val sorted = hits.sortedBy { it.ms }
            if (sorted.isNotEmpty()) Store.setWarpEndpoints(ctx, sorted.take(6).map { it.ip to it.port })
            return sorted
        }

        /** runs the warp-plus scanner only to read the endpoints it chooses, then kills it */
        private fun harvest(): List<Pair<String, Int>> {
            val bin = File(ctx.applicationInfo.nativeLibraryDir, "libwarp.so")
            if (!bin.exists()) return emptyList()
            val cache = File(ctx.filesDir, "warp").apply { mkdirs() }
            val p = ProcessBuilder(bin.path, "--bind", "127.0.0.1:18999", "--cache-dir", cache.path, "--scan", "--rtt", "3s")
                .directory(ctx.filesDir).redirectErrorStream(true).start()
            procs += p
            val found = java.util.concurrent.atomic.AtomicReference<List<Pair<String, Int>>?>(null)
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (line.contains("using warp endpoints")) {
                            val m = Regex("endpoints=\"\\[(.*)\\]\"").find(line)
                            val list = m?.groupValues?.get(1)?.split(" ")?.mapNotNull { t ->
                                if (t.startsWith("[")) {
                                    val ip = t.substring(1, t.indexOf("]")); val port = t.substringAfter("]:").toIntOrNull()
                                    if (port != null) ip to port else null
                                } else {
                                    val port = t.substringAfterLast(":").toIntOrNull()
                                    if (port != null) t.substringBeforeLast(":") to port else null
                                }
                            }?.distinct() ?: emptyList()
                            found.set(list)
                        }
                    }
                } catch (_: Exception) {}
            }.start()
            val t0 = System.currentTimeMillis()
            try {
                while (System.currentTimeMillis() - t0 < 150_000 && found.get() == null && p.isAlive && !cancelled()) {
                    status("اسکن endpoint با اسکنر Cloudflare... ${(System.currentTimeMillis() - t0) / 1000} ثانیه (حدود ۱ دقیقه)")
                    Thread.sleep(700)
                }
            } finally { try { p.destroyForcibly(); p.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) {}; procs.remove(p) }
            if (cancelled()) error("لغو شد")
            val r = found.get() ?: emptyList()
            log("اسکنر warp-plus: " + r.joinToString { "${it.first}:${it.second}" }.ifEmpty { "چیزی پیدا نکرد" })
            return r
        }

        private fun probe(id: Id, batch: List<Cand>): List<Hit> {
            if (batch.isEmpty()) return emptyList()
            val base = 22000
            val f = File(ctx.filesDir, "wp.json").apply { writeText(probeConfig(id, batch, base)) }
            val bin = File(ctx.applicationInfo.nativeLibraryDir, "libsingbox.so")
            val p = ProcessBuilder(bin.path, "run", "-c", f.path, "-D", ctx.filesDir.path).redirectErrorStream(true).start()
            procs += p
            val out = StringBuilder()
            Thread {
                try { p.inputStream.bufferedReader().forEachLine { out.appendLine(it); if (out.length > 20000) out.delete(0, 10000) } }
                catch (_: Exception) {}
            }.start()
            try {
                var up = false
                for (i in 0 until 40) {
                    if (cancelled()) error("لغو شد")
                    if (!p.isAlive) error("sing-box برای WARP بالا نیامد: " + out.toString().trim().takeLast(250))
                    try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", base), 300) }; up = true; break }
                    catch (_: Exception) { Thread.sleep(300) }
                }
                if (!up) error("پورت تست بالا نیامد: " + out.toString().trim().takeLast(250))
                val res = ConcurrentLinkedQueue<Hit>()
                val errs = java.util.concurrent.ConcurrentHashMap<String, Int>()
                val ts = batch.mapIndexed { i, c ->
                    Thread {
                        try {
                            val px = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", base + i))
                            val conn = URL("http://1.1.1.1/cdn-cgi/trace").openConnection(px) as HttpURLConnection
                            conn.connectTimeout = 9000; conn.readTimeout = 9000
                            val t0 = System.nanoTime()
                            val body = conn.inputStream.bufferedReader().readText()
                            if (body.contains("warp=on") || body.contains("warp=plus"))
                                res += Hit(c.ip, c.port, ((System.nanoTime() - t0) / 1_000_000).toInt(), c.rz)
                            else errs.merge("بدون warp=on", 1, Int::plus)
                        } catch (e: Exception) { errs.merge(e.javaClass.simpleName, 1, Int::plus) }
                    }.also { it.start() }
                }
                ts.forEach { it.join(12_000) }
                log("تست ${batch.size} endpoint: ${res.size} سالم" +
                    if (res.isEmpty()) "  علت: " + errs.entries.joinToString { "${it.key}=${it.value}" } else "")
                if (res.isEmpty()) {
                    val keep = out.lines().filter { l ->
                        l.contains("andshake", true) || l.contains("ERROR") || l.contains("WARN") || l.contains("denied", true)
                    }.takeLast(8)
                    if (keep.isNotEmpty()) log("خروجی sing-box:\n" + keep.joinToString("\n") { it.take(160) })
                }
                return res.toList()
            } finally {
                try { p.destroyForcibly(); p.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) {}
                procs.remove(p)
            }
        }
    }
}
