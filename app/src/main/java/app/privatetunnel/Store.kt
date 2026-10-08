package app.privatetunnel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Profile(val name: String, val uri: String)

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("pt", Context.MODE_PRIVATE)

    // mode: auto | psiphon | warp | gool | v2ray
    fun mode(c: Context) = sp(c).getString("mode", "auto")!!
    fun setMode(c: Context, v: String) = sp(c).edit().putString("mode", v).apply()
    // v2ray mode can optionally go through WARP/Gool first: none | warp | gool
    fun via(c: Context) = sp(c).getString("via", "none")!!
    fun setVia(c: Context, v: String) = sp(c).edit().putString("via", v).apply()
    fun lastGood(c: Context) = sp(c).getString("lastGood", "")!!
    fun setLastGood(c: Context, v: String) = sp(c).edit().putString("lastGood", v).apply()

    fun port(c: Context) = sp(c).getInt("port", 10808)
    fun setPort(c: Context, v: Int) = sp(c).edit().putInt("port", v).apply()
    fun hotspot(c: Context) = sp(c).getBoolean("hotspot", true)
    fun setHotspot(c: Context, v: Boolean) = sp(c).edit().putBoolean("hotspot", v).apply()
    fun scan(c: Context) = sp(c).getBoolean("scan", true)
    fun setScan(c: Context, v: Boolean) = sp(c).edit().putBoolean("scan", v).apply()
    fun country(c: Context) = sp(c).getString("country", "US")!!
    fun setCountry(c: Context, v: String) = sp(c).edit().putString("country", v).apply()
    fun endpoint(c: Context) = sp(c).getString("endpoint", "")!!
    fun setEndpoint(c: Context, v: String) = sp(c).edit().putString("endpoint", v).apply()

    fun selected(c: Context) = sp(c).getInt("sel", 0)
    fun setSelected(c: Context, v: Int) = sp(c).edit().putInt("sel", v).apply()

    fun profiles(c: Context): List<Profile> {
        val arr = JSONArray(sp(c).getString("profiles", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it); Profile(o.getString("name"), o.getString("uri"))
        }
    }

    fun saveProfiles(c: Context, list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("uri", it.uri)) }
        sp(c).edit().putString("profiles", arr.toString()).apply()
    }

    fun selectedProfile(c: Context): Profile? = profiles(c).getOrNull(selected(c))

    fun testDone(c: Context) = sp(c).getBoolean("testDone", false)
    fun setTestDone(c: Context, v: Boolean) = sp(c).edit().putBoolean("testDone", v).apply()

    fun extraSources(c: Context): List<String> =
        sp(c).getString("extra", "")!!.lines().map { it.trim() }.filter { it.startsWith("http") }
    fun addExtraSource(c: Context, url: String) {
        val l = (extraSources(c) + url.trim()).distinct()
        sp(c).edit().putString("extra", l.joinToString("\n")).apply()
    }

    /** adds a profile (deduped by uri) and returns its index */
    fun addProfile(c: Context, p: Profile): Int {
        val l = profiles(c)
        val i = l.indexOfFirst { it.uri == p.uri }
        if (i >= 0) return i
        saveProfiles(c, l + p)
        return l.size
    }
}
