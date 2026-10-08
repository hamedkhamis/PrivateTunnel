package app.privatetunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Sys {
    /** true when another VPN (for example AetherST) is currently the active network */
    fun vpnActive(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (e: Exception) { false }
}
