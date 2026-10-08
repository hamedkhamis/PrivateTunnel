package app.privatetunnel

import org.json.JSONArray
import org.json.JSONObject

object ConfigBuilder {

    /** sing-box config: one SOCKS/HTTP mixed inbound, one proxy outbound, optional detour through WARP. */
    fun singBox(proxy: JSONObject, listen: String, port: Int, warpPort: Int?): String {
        val p = JSONObject(proxy.toString()).put("tag", "proxy")
        val outs = JSONArray()
        if (warpPort != null) {
            p.put("detour", "warp")
            outs.put(p)
            outs.put(JSONObject().put("type", "socks").put("tag", "warp")
                .put("server", "127.0.0.1").put("server_port", warpPort))
        } else outs.put(p)
        outs.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val cfg = JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", JSONObject()
                .put("servers", JSONArray()
                    .put(JSONObject().put("tag", "d").put("address", "https://1.1.1.1/dns-query"))
                    .put(JSONObject().put("tag", "l").put("address", "local")))
                .put("strategy", "ipv4_only"))
            .put("inbounds", JSONArray().put(JSONObject().put("type", "mixed").put("tag", "in")
                .put("listen", listen).put("listen_port", port)))
            .put("outbounds", outs)
            .put("route", JSONObject().put("final", "proxy"))
        return cfg.toString(2)
    }

    /** arguments for bepass-org/warp-plus (base = warp|gool|none, psiphon runs inside it) */
    fun warpArgs(base: String, psiphon: Boolean, country: String, scan: Boolean, rtt: Boolean,
                 endpoint: String, bind: String, cacheDir: String): List<String> {
        val a = mutableListOf("--bind", bind, "--cache-dir", cacheDir)
        if (base == "gool") a += "--gool"
        if (psiphon) { a += "--cfon"; a += listOf("--country", country) }
        if (endpoint.isNotBlank()) a += listOf("--endpoint", endpoint.trim())
        if (scan) { a += "--scan"; if (rtt) a += listOf("--rtt", "3s") }
        return a
    }

    private fun dnsBlock() = JSONObject()
        .put("servers", JSONArray()
            .put(JSONObject().put("tag", "l").put("address", "local"))
            .put(JSONObject().put("tag", "d").put("address", "https://1.1.1.1/dns-query")))
        .put("strategy", "ipv4_only")

    /** one sing-box with many outbounds o0..oN; delays are measured through the clash API */
    fun delayConfig(outs: List<JSONObject>): String {
        val arr = JSONArray()
        outs.forEachIndexed { i, o -> arr.put(JSONObject(o.toString()).put("tag", "o$i")) }
        arr.put(JSONObject().put("type", "direct").put("tag", "direct"))
        return JSONObject()
            .put("log", JSONObject().put("level", "fatal"))
            .put("dns", dnsBlock())
            .put("inbounds", JSONArray().put(JSONObject().put("type", "mixed").put("tag", "idle")
                .put("listen", "127.0.0.1").put("listen_port", 19091)))
            .put("outbounds", arr)
            .put("route", JSONObject().put("final", "direct"))
            .put("experimental", JSONObject().put("clash_api",
                JSONObject().put("external_controller", "127.0.0.1:19090")))
            .toString()
    }

    /** one mixed inbound per outbound so each config can be speed-tested through its own port */
    fun speedConfig(outs: List<JSONObject>, basePort: Int): String {
        val ins = JSONArray(); val arr = JSONArray(); val rules = JSONArray()
        outs.forEachIndexed { i, o ->
            arr.put(JSONObject(o.toString()).put("tag", "o$i"))
            ins.put(JSONObject().put("type", "mixed").put("tag", "in$i")
                .put("listen", "127.0.0.1").put("listen_port", basePort + i))
            rules.put(JSONObject().put("inbound", JSONArray().put("in$i")).put("outbound", "o$i"))
        }
        arr.put(JSONObject().put("type", "direct").put("tag", "direct"))
        return JSONObject()
            .put("log", JSONObject().put("level", "fatal"))
            .put("dns", dnsBlock())
            .put("inbounds", ins).put("outbounds", arr)
            .put("route", JSONObject().put("rules", rules).put("final", "direct"))
            .toString()
    }

    /** hev-socks5-tunnel yaml: TUN -> local SOCKS5, DNS is mapped (resolved remotely, no leak) */
    fun hev(socksPort: Int) = """
tunnel:
  mtu: 1500
  ipv4: 198.18.0.1
  ipv6: 'fd00::1'
socks5:
  port: $socksPort
  address: 127.0.0.1
  udp: 'udp'
mapdns:
  address: 198.18.0.2
  port: 53
  network: 100.64.0.0
  netmask: 255.192.0.0
  cache-size: 10000
""".trimIndent()
}
