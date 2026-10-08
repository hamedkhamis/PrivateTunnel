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

    /** arguments for bepass-org/warp-plus */
    fun warpArgs(mode: String, bind: String, country: String, scan: Boolean, cacheDir: String): List<String> {
        val a = mutableListOf("--bind", bind, "--cache-dir", cacheDir)
        when (mode) {
            "gool" -> a += "--gool"
            "psiphon" -> { a += "--cfon"; a += listOf("--country", country) }
            "masque" -> a += "--masque"
        }
        if (scan) a += "--scan"
        return a
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
