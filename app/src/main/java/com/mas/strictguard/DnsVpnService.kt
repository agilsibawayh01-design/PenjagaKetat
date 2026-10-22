package com.mas.strictguard

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Filter DNS lokal lewat VPN. Hanya lalu lintas DNS (ke 10.0.0.1) yang masuk ke terowongan;
 * seluruh data lain tetap lewat jaringan biasa. Nama situs yang diblokir dijawab NXDOMAIN,
 * sisanya diteruskan ke DNS upstream pilihan.
 */
class DnsVpnService : VpnService() {
    private var pfd: ParcelFileDescriptor? = null
    private var pool: ExecutorService? = null
    private var out: FileOutputStream? = null
    private val outLock = Any()

    @Volatile
    private var active = false

    @Volatile
    private var domains: Set<String> = emptySet()

    @Volatile
    private var keywords: Set<String> = emptySet()

    @Volatile
    private var blockDoh = true
    private var cacheAt = 0L
    private val lastLog = HashMap<String, Long>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!active) startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        try {
            val b = Builder()
                .setSession("Penjaga Ketat DNS")
                .addAddress(CLIENT_IP, 32)
                .addDnsServer(DNS_IP)
                .addRoute(DNS_IP, 32)
                .setMtu(1500)
            val p = b.establish()
            if (p == null) {
                stopVpn()
                return
            }
            pfd = p
            out = FileOutputStream(p.fileDescriptor)
            pool = Executors.newFixedThreadPool(4)
            active = true
            running = true
            val input = FileInputStream(p.fileDescriptor)
            Thread { readLoop(input) }.start()
            Store.log(this, "VPN DNS aktif")
        } catch (t: Throwable) {
            stopVpn()
        }
    }

    private fun stopVpn() {
        active = false
        running = false
        try {
            pool?.shutdownNow()
        } catch (t: Throwable) {
            // abaikan
        }
        pool = null
        try {
            pfd?.close()
        } catch (t: Throwable) {
            // abaikan
        }
        pfd = null
        out = null
    }

    override fun onRevoke() {
        Store.log(this, "VPN DNS dicabut oleh sistem atau pengguna")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun readLoop(input: FileInputStream) {
        val buf = ByteArray(32767)
        while (active) {
            val n = try {
                input.read(buf)
            } catch (e: Exception) {
                -1
            }
            if (n < 0) break
            if (n == 0) {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }
            val pkt = buf.copyOf(n)
            try {
                handlePacket(pkt)
            } catch (t: Throwable) {
                // lewati paket yang rusak
            }
        }
        if (active) stopVpn()
    }

    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun handlePacket(pkt: ByteArray) {
        if (pkt.size < 20) return
        val ver = (pkt[0].toInt() shr 4) and 0x0F
        if (ver != 4) return
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (ihl < 20 || pkt.size < ihl + 8) return
        val proto = pkt[9].toInt() and 0xFF
        val srcIp = pkt.copyOfRange(12, 16)
        val dstIp = pkt.copyOfRange(16, 20)
        if (proto == 6) {
            sendRst(pkt, ihl, srcIp, dstIp)
            return
        }
        if (proto != 17) return
        val srcPort = u16(pkt, ihl)
        val dstPort = u16(pkt, ihl + 2)
        if (dstPort != 53) return
        val udpLen = u16(pkt, ihl + 4)
        val payloadLen = Math.min(udpLen - 8, pkt.size - ihl - 8)
        if (payloadLen < 12) return
        val query = pkt.copyOfRange(ihl + 8, ihl + 8 + payloadLen)
        pool?.execute {
            try {
                answer(query, srcIp, dstIp, srcPort)
            } catch (t: Throwable) {
                // abaikan
            }
        }
    }

    private fun answer(query: ByteArray, srcIp: ByteArray, dstIp: ByteArray, srcPort: Int) {
        val name = parseName(query)
        val reply: ByteArray? = if (name != null && blocked(name)) nxdomain(query) else forward(query)
        if (reply != null) writeUdp(dstIp, srcIp, 53, srcPort, reply)
    }

    private fun parseName(q: ByteArray): String? {
        var i = 12
        val sb = StringBuilder()
        while (i < q.size) {
            val len = q[i].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) != 0) return null
            i++
            if (i + len > q.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 0 until len) sb.append((q[i + k].toInt() and 0xFF).toChar())
            i += len
        }
        if (sb.isEmpty()) return null
        return sb.toString().lowercase()
    }

    /** Indeks setelah bagian pertanyaan (nama + tipe + kelas), atau -1 bila tidak valid. */
    private fun questionEnd(q: ByteArray): Int {
        var i = 12
        while (i < q.size) {
            val len = q[i].toInt() and 0xFF
            if (len == 0) {
                val end = i + 1 + 4
                return if (end <= q.size) end else -1
            }
            if ((len and 0xC0) != 0) return -1
            i += 1 + len
        }
        return -1
    }

    private fun nxdomain(q: ByteArray): ByteArray? {
        val end = questionEnd(q)
        if (end < 0) return null
        val r = q.copyOfRange(0, end)
        r[2] = (0x80 or (q[2].toInt() and 0x79)).toByte()
        r[3] = 0x83.toByte()
        r[4] = 0
        r[5] = 1
        for (k in 6..11) r[k] = 0
        return r
    }

    private fun forward(q: ByteArray): ByteArray? {
        val s = DatagramSocket()
        return try {
            protect(s)
            s.soTimeout = 4000
            val addr = InetAddress.getByName(UPSTREAMS[Store.vpnUpstream(this).coerceIn(0, UPSTREAMS.size - 1)])
            s.send(DatagramPacket(q, q.size, addr, 53))
            val rb = ByteArray(4096)
            val rp = DatagramPacket(rb, rb.size)
            s.receive(rp)
            rb.copyOf(rp.length)
        } catch (e: Exception) {
            null
        } finally {
            s.close()
        }
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        if (now - cacheAt < 3000L) return
        cacheAt = now
        val act = ProfileStore.load(this).filter { ProfileEngine.isActive(this, it) }
        domains = Store.domains(this) + Categories.domains(this) + act.flatMap { it.domains }
        keywords = Store.keywords(this) + Categories.keywords(this) + act.flatMap { it.keywords }
        blockDoh = Store.vpnDoh(this)
    }

    private fun blocked(name: String): Boolean {
        refresh()
        var hit = false
        if (blockDoh) {
            for (d in DOH) if (name == d || name.endsWith(".$d")) hit = true
        }
        if (!hit && Rules.matchesDomain(name, domains) != null) hit = true
        if (!hit && Rules.matchesKeyword(name, keywords) != null) hit = true
        if (hit) {
            val now = System.currentTimeMillis()
            val prev = synchronized(lastLog) { lastLog[name] ?: 0L }
            if (now - prev > 30000L) {
                synchronized(lastLog) {
                    lastLog[name] = now
                    if (lastLog.size > 500) lastLog.clear()
                }
                StatsDb.addBlock(this, "dns", name)
            }
        }
        return hit
    }

    private fun csum(data: ByteArray, off: Int, len: Int, init: Long): Int {
        var sum = init
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun ipHeader(total: Int, proto: Int, src: ByteArray, dst: ByteArray): ByteArray {
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte()
        p[3] = total.toByte()
        p[6] = 0x40
        p[8] = 64
        p[9] = proto.toByte()
        for (k in 0 until 4) {
            p[12 + k] = src[k]
            p[16 + k] = dst[k]
        }
        val c = csum(p, 0, 20, 0L)
        p[10] = (c shr 8).toByte()
        p[11] = c.toByte()
        return p
    }

    private fun writeUdp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray) {
        val total = 20 + 8 + payload.size
        val p = ipHeader(total, 17, src, dst)
        p[20] = (srcPort shr 8).toByte()
        p[21] = srcPort.toByte()
        p[22] = (dstPort shr 8).toByte()
        p[23] = dstPort.toByte()
        val ul = 8 + payload.size
        p[24] = (ul shr 8).toByte()
        p[25] = ul.toByte()
        System.arraycopy(payload, 0, p, 28, payload.size)
        synchronized(outLock) { out?.write(p) }
    }

    /** Balas koneksi TCP ke DNS palsu kita dengan RST agar DNS-over-TLS (Private DNS) cepat gagal dan beralih ke DNS biasa. */
    private fun sendRst(pkt: ByteArray, ihl: Int, srcIp: ByteArray, dstIp: ByteArray) {
        if (pkt.size < ihl + 20) return
        val flags = pkt[ihl + 13].toInt() and 0xFF
        if ((flags and 0x04) != 0) return // sudah RST
        val srcPort = u16(pkt, ihl)
        val dstPort = u16(pkt, ihl + 2)
        var seq = 0L
        for (k in 0 until 4) seq = (seq shl 8) or (pkt[ihl + 4 + k].toLong() and 0xFF)
        var ack = 0L
        for (k in 0 until 4) ack = (ack shl 8) or (pkt[ihl + 8 + k].toLong() and 0xFF)
        val syn = (flags and 0x02) != 0
        val hasAck = (flags and 0x10) != 0
        val replySeq = if (hasAck) ack else 0L
        val replyAck = if (syn) (seq + 1) and 0xFFFFFFFFL else seq
        val p = ipHeader(40, 6, dstIp, srcIp)
        p[20] = (dstPort shr 8).toByte()
        p[21] = dstPort.toByte()
        p[22] = (srcPort shr 8).toByte()
        p[23] = srcPort.toByte()
        for (k in 0 until 4) {
            p[24 + k] = (replySeq shr (24 - 8 * k)).toByte()
            p[28 + k] = (replyAck shr (24 - 8 * k)).toByte()
        }
        p[32] = 0x50
        p[33] = 0x14 // RST + ACK
        // checksum TCP dengan pseudo-header
        var init = 0L
        for (k in 0 until 4 step 2) {
            init += ((dstIp[k].toInt() and 0xFF) shl 8) or (dstIp[k + 1].toInt() and 0xFF)
            init += ((srcIp[k].toInt() and 0xFF) shl 8) or (srcIp[k + 1].toInt() and 0xFF)
        }
        init += 6
        init += 20
        val c = csum(p, 20, 20, init)
        p[36] = (c shr 8).toByte()
        p[37] = c.toByte()
        synchronized(outLock) { out?.write(p) }
    }

    companion object {
        const val DNS_IP = "10.0.0.1"
        const val CLIENT_IP = "10.0.0.2"
        val UPSTREAMS = arrayOf("1.1.1.3", "1.1.1.1", "8.8.8.8")
        val DOH = listOf(
            "dns.google", "cloudflare-dns.com", "mozilla.cloudflare-dns.com", "dns.quad9.net",
            "doh.opendns.com", "dns.nextdns.io", "dns.adguard.com", "doh.cleanbrowsing.org"
        )

        @Volatile
        var running = false

        fun consentNeeded(c: Context): Intent? = VpnService.prepare(c)

        fun start(c: Context) {
            try {
                if (VpnService.prepare(c) != null) return
                c.startService(Intent(c, DnsVpnService::class.java))
            } catch (t: Throwable) {
                // abaikan
            }
        }

        fun stop(c: Context) {
            try {
                c.stopService(Intent(c, DnsVpnService::class.java))
            } catch (t: Throwable) {
                // abaikan
            }
            running = false
        }
    }
}
