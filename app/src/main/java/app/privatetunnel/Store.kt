package app.privatetunnel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Profile(val name: String, val uri: String)

/** Tiny SharedPreferences wrapper. */
object Store {
    private fun sp(c: Context) = c.getSharedPreferences("pt", Context.MODE_PRIVATE)

    // modes: proxy | warp | gool | psiphon | masque
    fun mode(c: Context) = sp(c).getString("mode", "psiphon")!!
    fun setMode(c: Context, v: String) = sp(c).edit().putString("mode", v).apply()

    fun port(c: Context) = sp(c).getInt("port", 10808)
    fun setPort(c: Context, v: Int) = sp(c).edit().putInt("port", v).apply()

    fun hotspot(c: Context) = sp(c).getBoolean("hotspot", false)
    fun setHotspot(c: Context, v: Boolean) = sp(c).edit().putBoolean("hotspot", v).apply()

    fun chain(c: Context) = sp(c).getBoolean("chain", false)
    fun setChain(c: Context, v: Boolean) = sp(c).edit().putBoolean("chain", v).apply()

    fun scan(c: Context) = sp(c).getBoolean("scan", true)
    fun setScan(c: Context, v: Boolean) = sp(c).edit().putBoolean("scan", v).apply()

    fun country(c: Context) = sp(c).getString("country", "US")!!
    fun setCountry(c: Context, v: String) = sp(c).edit().putString("country", v).apply()

    fun selected(c: Context) = sp(c).getInt("sel", 0)
    fun setSelected(c: Context, v: Int) = sp(c).edit().putInt("sel", v).apply()

    fun profiles(c: Context): List<Profile> {
        val arr = JSONArray(sp(c).getString("profiles", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Profile(o.getString("name"), o.getString("uri"))
        }
    }

    fun saveProfiles(c: Context, list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("uri", it.uri)) }
        sp(c).edit().putString("profiles", arr.toString()).apply()
    }

    fun selectedProfile(c: Context): Profile? = profiles(c).getOrNull(selected(c))
}
