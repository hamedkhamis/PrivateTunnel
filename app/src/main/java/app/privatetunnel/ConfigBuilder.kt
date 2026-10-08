package app.privatetunnel

import org.json.JSONArray
import org.json.JSONObject

object ConfigBuilder {

    private fun dnsBlock() = JSONObject()
        .put("servers", JSONArray()
            .put(JSONObject().put("tag", "g").put("address", "https://8.8.8.8/dns-query"))
            .put(JSONObject().put("tag", "l").put("address", "local")))
        .put("strategy", "ipv4_only")

    /** sing-box config for one V2Ray outbound behind a local mixed (SOCKS+HTTP) inbound */
    fun singBox(proxy: JSONObject, listen: String, port: Int): String {
        val outs = JSONArray()
            .put(JSONObject(proxy.toString()).put("tag", "proxy"))
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", dnsBlock())
            .put("inbounds", JSONArray().put(JSONObject().put("type", "mixed").put("tag", "in")
                .put("listen", listen).put("listen_port", port)))
            .put("outbounds", outs)
            .put("route", JSONObject().put("final", "proxy"))
            .toString(2)
    }

    /** one mixed inbound per outbound so each config can be tested through its own local port */
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

    /** hev-socks5-tunnel yaml (same layout AetherST uses): TUN -> local SOCKS5, DNS is mapped (resolved remotely) */
    fun hev(socksPort: Int, mtu: Int) = """
tunnel:
  mtu: $mtu
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
misc:
  log-level: warn
  connect-timeout: 5000
  read-write-timeout: 60000
""".trimIndent()
}
