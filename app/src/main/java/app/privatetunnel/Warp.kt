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
    data class Hit(val ip: String, val port: Int, val ms: Int) {
        fun hostPort() = if (ip.contains(":")) "[$ip]:$port" else "$ip:$port"
    }

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

    private fun probeConfig(id: Id, batch: List<Pair<String, Int>>, base: Int): String {
        val ins = JSONArray(); val eps = JSONArray(); val rules = JSONArray()
        batch.forEachIndexed { i, (ip, port) ->
            ins.put(JSONObject().put("type", "mixed").put("tag", "in$i").put("listen", "127.0.0.1").put("listen_port", base + i))
            eps.put(endpoint("w$i", id, ip, port, null, 1280).apply { remove("peers"); put("peers", JSONArray().put(
                JSONObject().put("address", ip).put("port", port).put("public_key", id.pub)
                    .put("allowed_ips", JSONArray().put("0.0.0.0/0").put("::/0")).put("reserved", JSONArray(id.reserved)))) })
            rules.put(JSONObject().put("inbound", JSONArray().put("in$i")).put("outbound", "w$i"))
        }
        return JSONObject().put("log", JSONObject().put("level", "warn"))
            .put("inbounds", ins).put("endpoints", eps)
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject().put("rules", rules).put("final", "direct")).toString()
    }

    /**
     * Finds Cloudflare WARP endpoints that really work from this network: one sing-box loads a batch of
     * WireGuard endpoints and a real request is sent through each; the answer must say warp=on.
     */
    class Finder(
        private val ctx: Context,
        private val procs: MutableList<Process>,
        private val cancelled: () -> Boolean,
        private val status: (String) -> Unit
    ) {
        fun find(want: Int = 2, maxBatches: Int = 10): List<Hit> {
            val id = load(ctx, "primary") ?: error("هویت WARP داخل APK نیست؛ workflow را دوباره اجرا کن")
            val hits = ArrayList<Hit>()
            val tried = HashSet<String>()
            val saved = Store.warpEndpoints(ctx)
            val custom = Store.endpoint(ctx).trim().let { s ->
                val ip = s.substringBeforeLast(":", "").trim('[', ']'); val p = s.substringAfterLast(":", "").toIntOrNull()
                if (ip.isNotEmpty() && p != null) ip to p else null
            }
            for (b in 0 until maxBatches) {
                if (cancelled()) error("لغو شد")
                val batch = ArrayList<Pair<String, Int>>()
                if (b == 0) { custom?.let { batch += it }; batch += saved.take(8) }
                batch += candidates(16 - batch.size, tried)
                batch.forEach { tried += "${it.first}|${it.second}" }
                status("اسکن endpoint سالم Cloudflare: دسته ${b + 1} از $maxBatches" +
                    if (hits.isNotEmpty()) "  (پیدا شده: ${hits.size})" else "")
                hits += probe(id, batch)
                if (hits.size >= want || (hits.isNotEmpty() && b >= 1)) break
            }
            val sorted = hits.sortedBy { it.ms }
            if (sorted.isNotEmpty()) Store.setWarpEndpoints(ctx, sorted.take(6).map { it.ip to it.port })
            return sorted
        }

        private fun probe(id: Id, batch: List<Pair<String, Int>>): List<Hit> {
            val base = 22000
            val f = File(ctx.filesDir, "wp.json").apply { writeText(probeConfig(id, batch, base)) }
            val bin = File(ctx.applicationInfo.nativeLibraryDir, "libsingbox.so")
            val p = ProcessBuilder(bin.path, "run", "-c", f.path, "-D", ctx.filesDir.path).redirectErrorStream(true).start()
            procs += p
            val out = StringBuilder()
            Thread { try { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } catch (_: Exception) {} }.start()
            try {
                var up = false
                for (i in 0 until 40) {
                    if (cancelled()) error("لغو شد")
                    if (!p.isAlive) error("sing-box برای WARP بالا نیامد: " + out.toString().trim().takeLast(220))
                    try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", base), 300) }; up = true; break }
                    catch (_: Exception) { Thread.sleep(300) }
                }
                if (!up) error("پورت تست بالا نیامد: " + out.toString().trim().takeLast(220))
                val res = ConcurrentLinkedQueue<Hit>()
                val ts = batch.mapIndexed { i, (ip, port) ->
                    Thread {
                        try {
                            val px = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", base + i))
                            val c = URL("http://1.1.1.1/cdn-cgi/trace").openConnection(px) as HttpURLConnection
                            c.connectTimeout = 8000; c.readTimeout = 8000
                            val t0 = System.nanoTime()
                            val body = c.inputStream.bufferedReader().readText()
                            if (body.contains("warp=on") || body.contains("warp=plus"))
                                res += Hit(ip, port, ((System.nanoTime() - t0) / 1_000_000).toInt())
                        } catch (_: Exception) {}
                    }.also { it.start() }
                }
                ts.forEach { it.join(10_000) }
                TunnelService.log("[warp] دسته: ${res.size} سالم از ${batch.size}")
                return res.toList()
            } finally {
                try { p.destroyForcibly(); p.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) {}
                procs.remove(p)
            }
        }
    }
}
