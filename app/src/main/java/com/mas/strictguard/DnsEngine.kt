package com.mas.strictguard

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mesin DNS: meneruskan pertanyaan ke DNS upstream.
 * - satu soket UDP dipakai ulang per thread (bukan satu soket per pertanyaan)
 * - batas waktu jelas, maksimal 3 upstream berurutan (pilihan, DNS jaringan, DNS publik)
 * - jawaban divalidasi (ID, bit QR); SERVFAIL/REFUSED berarti coba upstream berikutnya
 * - jawaban terpotong (TC) diulang lewat TCP
 * - cache kecil, antrean terbatas (pertanyaan berlebih dibuang, bukan menumpuk)
 */
class DnsEngine(
    private val ctx: Context,
    private val monitor: VpnNetworkMonitor,
    private val protect: (DatagramSocket) -> Boolean,
    private val protectTcp: (Socket) -> Boolean
) {
    private class Holder(var sock: DatagramSocket?, var gen: Int)

    private val gen = AtomicInteger(0)
    private val local = ThreadLocal<Holder>()
    private val cache = object : LinkedHashMap<String, Pair<Long, ByteArray>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, ByteArray>>?): Boolean = size > 512
    }
    private val health = Any()
    private val failCount = HashMap<String, Int>()
    private val skipUntil = HashMap<String, Long>()
    private val dropped = AtomicInteger(0)

    @Volatile
    private var closed = false

    /** Waktu (elapsed ms) terakhir ada jawaban sah dari upstream. */
    @Volatile
    var lastOkAt = 0L
        private set

    /** Jumlah pertanyaan berturut-turut yang gagal total. */
    val failStreak = AtomicInteger(0)

    private val executor = ThreadPoolExecutor(
        6, 6, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(64),
        RejectedExecutionHandler { _, _ -> dropped.incrementAndGet() }
    ).also { it.allowCoreThreadTimeOut(true) }

    fun submit(task: Runnable) {
        if (!closed) executor.execute(task)
    }

    fun shutdown() {
        closed = true
        try {
            executor.shutdownNow()
        } catch (t: Throwable) {
            // abaikan
        }
        synchronized(cache) { cache.clear() }
    }

    /** Dipanggil saat jaringan berganti: soket lama dibuang, cache dikosongkan, penghitung gagal diulang. */
    fun onNetworkChanged() {
        gen.incrementAndGet()
        synchronized(cache) { cache.clear() }
        synchronized(health) {
            failCount.clear()
            skipUntil.clear()
        }
        failStreak.set(0)
    }

    private fun sock(): DatagramSocket {
        var h = local.get()
        if (h == null) {
            h = Holder(null, -1)
            local.set(h)
        }
        val cur = gen.get()
        val s = h.sock
        if (s != null && h.gen == cur && !s.isClosed) return s
        try {
            s?.close()
        } catch (t: Throwable) {
            // abaikan
        }
        val n = DatagramSocket()
        protect(n)
        try {
            monitor.network?.bindSocket(n)
        } catch (t: Throwable) {
            // tetap pakai soket yang sudah dilindungi
        }
        h.sock = n
        h.gen = cur
        return n
    }

    private fun dropSock() {
        val h = local.get() ?: return
        try {
            h.sock?.close()
        } catch (t: Throwable) {
            // abaikan
        }
        h.sock = null
    }

    private fun queryUdp(q: ByteArray, ip: String, timeoutMs: Int): ByteArray? {
        return try {
            val s = sock()
            val addr = InetAddress.getByName(ip)
            s.send(DatagramPacket(q, q.size, addr, 53))
            val deadline = System.currentTimeMillis() + timeoutMs
            val rb = ByteArray(4096)
            var result: ByteArray? = null
            while (result == null) {
                val left = (deadline - System.currentTimeMillis()).toInt()
                if (left <= 0) break
                s.soTimeout = left
                val rp = DatagramPacket(rb, rb.size)
                try {
                    s.receive(rp)
                } catch (e: SocketTimeoutException) {
                    break
                }
                if (rp.address != addr) continue
                if (VpnPackets.validReply(q, rb, rp.length)) result = rb.copyOf(rp.length)
            }
            result
        } catch (e: Exception) {
            dropSock()
            null
        }
    }

    private fun queryTcp(q: ByteArray, ip: String): ByteArray? {
        val s = Socket()
        return try {
            protectTcp(s)
            try {
                monitor.network?.bindSocket(s)
            } catch (t: Throwable) {
                // abaikan
            }
            s.soTimeout = 2000
            s.connect(InetSocketAddress(InetAddress.getByName(ip), 53), 1500)
            val o = s.getOutputStream()
            o.write(byteArrayOf((q.size shr 8).toByte(), q.size.toByte()))
            o.write(q)
            o.flush()
            val i = s.getInputStream()
            val h = ByteArray(2)
            var got = 0
            while (got < 2) {
                val n = i.read(h, got, 2 - got)
                if (n < 0) return null
                got += n
            }
            val len = ((h[0].toInt() and 0xFF) shl 8) or (h[1].toInt() and 0xFF)
            if (len < 12 || len > 65535) return null
            val b = ByteArray(len)
            got = 0
            while (got < len) {
                val n = i.read(b, got, len - got)
                if (n < 0) return null
                got += n
            }
            if (VpnPackets.validReply(q, b, len)) b else null
        } catch (e: Exception) {
            null
        } finally {
            try {
                s.close()
            } catch (t: Throwable) {
                // abaikan
            }
        }
    }

    /** Urutan upstream: pilihan, DNS bawaan jaringan (ISP/Wi-Fi), lalu DNS publik lain. */
    fun chain(): List<String> {
        val chosen = UPSTREAMS[Store.vpnUpstream(ctx).coerceIn(0, UPSTREAMS.size - 1)]
        val list = ArrayList<String>()
        list.add(chosen)
        list.addAll(monitor.dns)
        for (u in UPSTREAMS) list.add(u)
        return list.distinct()
    }

    private fun note(ip: String, ok: Boolean) {
        var skipped = false
        synchronized(health) {
            if (ok) {
                failCount[ip] = 0
                skipUntil.remove(ip)
            } else {
                val f = (failCount[ip] ?: 0) + 1
                if (f >= 3) {
                    failCount[ip] = 0
                    skipUntil[ip] = System.currentTimeMillis() + 45000L
                    skipped = true
                } else {
                    failCount[ip] = f
                }
            }
        }
        if (skipped) Store.log(ctx, "DNS $ip gagal 3x, dilewati 45 detik")
    }

    private fun ok() {
        lastOkAt = android.os.SystemClock.elapsedRealtime()
        failStreak.set(0)
    }

    /** Selesaikan satu pertanyaan DNS. Selalu mengembalikan jawaban (SERVFAIL bila semua upstream gagal) kecuali pertanyaan rusak. */
    fun resolve(q: ByteArray): ByteArray? {
        val key = VpnPackets.cacheKey(q)
        if (key != null) {
            val hit: ByteArray? = synchronized(cache) {
                val e = cache[key]
                if (e != null && e.first > System.currentTimeMillis()) e.second else null
            }
            if (hit != null) {
                VpnStatus.qCached.incrementAndGet()
                val r = hit.copyOf()
                r[0] = q[0]
                r[1] = q[1]
                return r
            }
        }
        val all = chain()
        val now = System.currentTimeMillis()
        val usable = all.filter { ip -> synchronized(health) { (skipUntil[ip] ?: 0L) <= now } }
        val order = (if (usable.isEmpty()) all else usable).take(3)
        var soft: ByteArray? = null
        var idx = 0
        for (ip in order) {
            val timeout = 1200 + 300 * idx
            idx++
            var r = queryUdp(q, ip, timeout)
            if (r == null) {
                note(ip, false)
                continue
            }
            if (VpnPackets.truncated(r)) {
                val t = queryTcp(q, ip)
                if (t != null) r = t
            }
            note(ip, true)
            ok()
            val rc = VpnPackets.rcode(r)
            if (rc == 2 || rc == 5) {
                soft = r
                continue
            }
            VpnStatus.qForwarded.incrementAndGet()
            if (key != null) {
                val ttl = if (rc == 0 && VpnPackets.answerCount(r) > 0) 60000L else if (rc == 3) 30000L else 0L
                if (ttl > 0) synchronized(cache) { cache[key] = Pair(System.currentTimeMillis() + ttl, r) }
            }
            return r
        }
        if (soft != null) {
            VpnStatus.qForwarded.incrementAndGet()
            return soft
        }
        failStreak.incrementAndGet()
        VpnStatus.qFailed.incrementAndGet()
        return VpnPackets.servfail(q)
    }

    /** Uji langsung ke upstream (tanpa cache dan tanpa blocklist). */
    fun probeUpstream(): Boolean {
        val id = (System.nanoTime() and 0x7FFF).toInt()
        val q = VpnPackets.buildQuery("example.com", id)
        for (ip in chain().take(3)) {
            if (queryUdp(q, ip, 1500) != null) {
                ok()
                return true
            }
        }
        return false
    }

    companion object {
        const val DNS_IP = "10.0.0.1"
        const val CLIENT_IP = "10.0.0.2"
        val UPSTREAMS = arrayOf("1.1.1.3", "1.1.1.1", "8.8.8.8")
    }
}
