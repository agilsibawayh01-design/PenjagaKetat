package com.mas.strictguard

import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Uji kesehatan VPN: apakah upstream terjangkau, dan apakah terowongan benar-benar menjawab. */
object VpnHealth {
    private val pool = Executors.newCachedThreadPool()

    /**
     * Cari nama acak lewat resolver sistem (aplikasi ini ikut terowongan). Jawaban apa pun, termasuk
     * NXDOMAIN atau SERVFAIL, berarti terowongan hidup. Terowongan mati membuat resolver menunggu
     * lebih lama dari batas waktu di sini.
     */
    fun tunnelAnswers(timeoutMs: Long): Boolean {
        val name = "pk" + java.lang.Long.toString(System.nanoTime() and 0xFFFFFFFL, 36) + ".example.com"
        val f = pool.submit(Callable<Boolean> {
            try {
                InetAddress.getByName(name)
            } catch (e: Exception) {
                // gagal cepat tetap berarti ada yang menjawab
            }
            true
        })
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            f.cancel(true)
            false
        }
    }

    fun upstreamReachable(engine: DnsEngine): Boolean = try {
        engine.probeUpstream()
    } catch (t: Throwable) {
        false
    }
}
