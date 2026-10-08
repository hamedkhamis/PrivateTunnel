package app.privatetunnel

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * Converts share links into sing-box outbounds.
 * Supported: vless (tcp/ws/grpc/httpupgrade, tls/reality), vmess, trojan, ss,
 * hysteria2/hy2, tuic, and raw sing-box outbound JSON (ShadowTLS, AnyTLS, Naive, WireGuard, ...).
 */
object UriParser {

    fun parse(raw: String): JSONObject? = try { inner(raw.trim()) } catch (e: Exception) { null }

    fun name(raw: String): String = try {
        val s = raw.trim()
        when {
            s.startsWith("{") -> {
                val o = JSONObject(s)
                o.optString("tag").ifEmpty { o.optString("type", "custom") + " " + o.optString("server") }
            }
            s.startsWith("vmess://") -> JSONObject(b64(s.removePrefix("vmess://"))).optString("ps", "vmess")
            else -> URLDecoder.decode(s.substringAfter("#", ""), "UTF-8").ifEmpty {
                s.substringBefore("://") + " " + (URI(s.substringBefore("#")).host ?: "")
            }
        }
    } catch (e: Exception) { "profile" }

    private fun b64(s: String): String {
        val t = s.trim().replace('-', '+').replace('_', '/')
        return String(Base64.decode(t, Base64.DEFAULT or Base64.NO_WRAP), Charsets.UTF_8)
    }

    private fun dec(s: String?) = if (s == null) null else URLDecoder.decode(s, "UTF-8")

    private fun query(u: URI): Map<String, String> =
        (u.rawQuery ?: "").split("&").filter { it.contains("=") }
            .associate { dec(it.substringBefore("="))!! to dec(it.substringAfter("="))!! }

    private fun inner(s: String): JSONObject? {
        if (s.startsWith("{")) return JSONObject(s).put("tag", "proxy")
        return when (s.substringBefore("://").lowercase()) {
            "vless" -> vlessTrojan(s, "vless")
            "trojan" -> vlessTrojan(s, "trojan")
            "vmess" -> vmess(s)
            "ss" -> shadowsocks(s)
            "hysteria2", "hy2" -> hysteria2(s)
            "tuic" -> tuic(s)
            else -> null
        }
    }

    private fun tls(o: JSONObject, q: Map<String, String>, host: String, force: Boolean) {
        val sec = q["security"] ?: if (force) "tls" else "none"
        if (sec != "tls" && sec != "reality") return
        val t = JSONObject().put("enabled", true).put("server_name", q["sni"] ?: q["host"] ?: host)
        if (q["allowInsecure"] == "1" || q["insecure"] == "1") t.put("insecure", true)
        q["alpn"]?.let { t.put("alpn", JSONArray(it.split(","))) }
        val fp = q["fp"] ?: if (sec == "reality") "chrome" else null
        if (!fp.isNullOrEmpty() && fp != "none") t.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        if (sec == "reality") {
            t.put("reality", JSONObject().put("enabled", true)
                .put("public_key", q["pbk"] ?: "").put("short_id", q["sid"] ?: ""))
        }
        o.put("tls", t)
    }

    /** returns false when the transport is not supported by sing-box */
    private fun transport(o: JSONObject, type: String?, host: String?, path: String?, service: String?): Boolean {
        when (type) {
            null, "", "tcp", "none" -> {}
            "ws" -> o.put("transport", JSONObject().put("type", "ws").put("path", path ?: "/")
                .apply { if (!host.isNullOrEmpty()) put("headers", JSONObject().put("Host", host)) })
            "grpc" -> o.put("transport", JSONObject().put("type", "grpc").put("service_name", service ?: path ?: ""))
            "httpupgrade" -> o.put("transport", JSONObject().put("type", "httpupgrade").put("path", path ?: "/")
                .apply { if (!host.isNullOrEmpty()) put("host", host) })
            else -> return false
        }
        return true
    }

    private fun vlessTrojan(s: String, kind: String): JSONObject? {
        val u = URI(s.substringBefore("#")); val q = query(u)
        val o = JSONObject().put("type", kind).put("tag", "proxy")
            .put("server", u.host).put("server_port", if (u.port > 0) u.port else 443)
        if (kind == "vless") {
            o.put("uuid", u.userInfo)
            q["flow"]?.takeIf { it.isNotEmpty() }?.let { o.put("flow", it) }
            o.put("packet_encoding", "xudp")
        } else o.put("password", dec(u.userInfo))
        tls(o, q, u.host, force = kind == "trojan")
        if (!transport(o, q["type"], q["host"], q["path"], q["serviceName"])) return null
        return o
    }

    private fun vmess(s: String): JSONObject? {
        val j = JSONObject(b64(s.removePrefix("vmess://")))
        val o = JSONObject().put("type", "vmess").put("tag", "proxy")
            .put("server", j.getString("add")).put("server_port", j.get("port").toString().toInt())
            .put("uuid", j.getString("id")).put("alter_id", j.optString("aid", "0").toIntOrNull() ?: 0)
            .put("security", j.optString("scy", "auto").ifEmpty { "auto" })
        val q = mutableMapOf<String, String>()
        if (j.optString("tls") == "tls") q["security"] = "tls"
        q["sni"] = j.optString("sni").ifEmpty { j.optString("host") }
        j.optString("alpn").takeIf { it.isNotEmpty() }?.let { q["alpn"] = it }
        j.optString("fp").takeIf { it.isNotEmpty() }?.let { q["fp"] = it }
        tls(o, q, j.getString("add"), false)
        if (!transport(o, j.optString("net"), j.optString("host"), j.optString("path"), j.optString("path"))) return null
        return o
    }

    private fun shadowsocks(s: String): JSONObject? {
        var body = s.removePrefix("ss://").substringBefore("#").substringBefore("?")
        if (!body.contains("@")) body = b64(body)
        val userinfo = body.substringBeforeLast("@").let { if (it.contains(":")) dec(it)!! else b64(it) }
        val hostport = body.substringAfterLast("@").trimEnd('/')
        return JSONObject().put("type", "shadowsocks").put("tag", "proxy")
            .put("server", hostport.substringBeforeLast(":").trim('[', ']'))
            .put("server_port", hostport.substringAfterLast(":").toInt())
            .put("method", userinfo.substringBefore(":")).put("password", userinfo.substringAfter(":"))
    }

    private fun hysteria2(s: String): JSONObject {
        val u = URI(s.replace("hy2://", "hysteria2://").substringBefore("#")); val q = query(u)
        val o = JSONObject().put("type", "hysteria2").put("tag", "proxy")
            .put("server", u.host).put("server_port", if (u.port > 0) u.port else 443)
            .put("password", dec(u.userInfo))
        val t = JSONObject().put("enabled", true).put("server_name", q["sni"] ?: u.host)
        if (q["insecure"] == "1") t.put("insecure", true)
        o.put("tls", t)
        q["obfs"]?.let { o.put("obfs", JSONObject().put("type", it).put("password", q["obfs-password"] ?: "")) }
        return o
    }

    private fun tuic(s: String): JSONObject {
        val u = URI(s.substringBefore("#")); val q = query(u)
        val o = JSONObject().put("type", "tuic").put("tag", "proxy")
            .put("server", u.host).put("server_port", u.port)
            .put("uuid", u.userInfo.substringBefore(":")).put("password", dec(u.userInfo.substringAfter(":", "")))
            .put("congestion_control", q["congestion_control"] ?: "bbr")
            .put("udp_relay_mode", q["udp_relay_mode"] ?: "native")
        val t = JSONObject().put("enabled", true).put("server_name", q["sni"] ?: u.host)
        q["alpn"]?.let { t.put("alpn", JSONArray(it.split(","))) }
        if (q["allow_insecure"] == "1" || q["insecure"] == "1") t.put("insecure", true)
        o.put("tls", t)
        return o
    }
}
