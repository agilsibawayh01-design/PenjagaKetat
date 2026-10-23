package com.mas.strictguard

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler

/**
 * Memantau jaringan dasar (bukan VPN) lewat callback ConnectivityManager. Tanpa polling.
 * Semua callback jalan di thread pengelola VPN; hasilnya disimpan sebagai snapshot @Volatile
 * supaya thread DNS bisa membacanya tanpa kunci.
 */
class VpnNetworkMonitor(
    private val ctx: Context,
    private val handler: Handler,
    private val onChange: (String) -> Unit
) {
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val caps = LinkedHashMap<Network, NetworkCapabilities?>()
    private val props = HashMap<Network, LinkProperties?>()
    private var cb: ConnectivityManager.NetworkCallback? = null
    private var signature = ""
    private val fire = Runnable { onChange("jaringan berubah") }

    @Volatile
    var network: Network? = null
        private set

    @Volatile
    var dns: List<String> = emptyList()
        private set

    @Volatile
    var transport: String = "-"
        private set

    val hasNetwork: Boolean get() = network != null

    fun start() {
        if (cb != null) return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                if (!caps.containsKey(n)) caps[n] = null
                changed()
            }

            override fun onCapabilitiesChanged(n: Network, nc: NetworkCapabilities) {
                caps[n] = nc
                changed()
            }

            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                props[n] = lp
                changed()
            }

            override fun onLost(n: Network) {
                caps.remove(n)
                props.remove(n)
                changed()
            }
        }
        cb = c
        try {
            cm.registerNetworkCallback(req, c, handler)
        } catch (t: Throwable) {
            Store.log(ctx, "Pemantau jaringan gagal: ${t.message}")
            cb = null
        }
        recompute()
    }

    fun stop() {
        handler.removeCallbacks(fire)
        try {
            cb?.let { cm.unregisterNetworkCallback(it) }
        } catch (t: Throwable) {
            // abaikan
        }
        cb = null
        caps.clear()
        props.clear()
        network = null
        dns = emptyList()
        signature = ""
    }

    private fun rank(c: NetworkCapabilities?): Int {
        if (c == null) return 0
        var r = 1
        if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) r += 10
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) r += 3
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) r += 2
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) r += 1
        return r
    }

    private fun recompute(): Boolean {
        var best: Network? = null
        var bestRank = -1
        for ((n, c) in caps) {
            val r = rank(c)
            if (r > bestRank) {
                best = n
                bestRank = r
            }
        }
        val list = ArrayList<String>()
        try {
            val lp = if (best != null) props[best] ?: cm.getLinkProperties(best) else null
            if (lp != null) {
                for (a in lp.dnsServers) {
                    val h = a.hostAddress ?: continue
                    if (a is java.net.Inet4Address && h != DnsEngine.DNS_IP) list.add(h)
                }
            }
        } catch (t: Throwable) {
            // pakai DNS publik saja
        }
        val c = if (best != null) caps[best] else null
        val t = when {
            c == null -> "-"
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Seluler"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Lain"
        }
        val sig = (best?.toString() ?: "none") + "|" + list.joinToString(",") + "|" + t
        val diff = sig != signature
        signature = sig
        network = best
        dns = list
        transport = t
        return diff
    }

    private fun changed() {
        if (recompute()) {
            handler.removeCallbacks(fire)
            handler.postDelayed(fire, 700L)
        }
    }
}
