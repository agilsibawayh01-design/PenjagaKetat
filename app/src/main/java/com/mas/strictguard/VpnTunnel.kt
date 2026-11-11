package com.mas.strictguard

import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Terowongan TUN: hanya lalu lintas ke DNS palsu (10.0.0.1) yang masuk; data lain tetap lewat
 * jaringan biasa. Satu thread membaca paket, jawaban dikerjakan oleh DnsEngine.
 */
class VpnTunnel(
    private val svc: DnsVpnService,
    private val engine: DnsEngine,
    private val blocklist: DnsBlocklist,
    private val onDead: (String) -> Unit
) {
    private var pfd: ParcelFileDescriptor? = null
    private var out: FileOutputStream? = null
    private val outLock = Any()

    @Volatile
    private var closing = false
    private var reader: Thread? = null

    val isOpen: Boolean get() = pfd != null && !closing

    /** Membuat terowongan. Mengembalikan null bila berhasil, atau alasan gagal. */
    fun open(excluded: Set<String>): String? {
        close()
        closing = false
        try {
            val b = svc.newBuilder()
                .setSession("Penjaga Ketat DNS")
                .addAddress(DnsEngine.CLIENT_IP, 32)
                .addDnsServer(DnsEngine.DNS_IP)
                .addRoute(DnsEngine.DNS_IP, 32)
                .setMtu(1500)
                .setBlocking(true)
                .setMetered(false)
                .allowFamily(OsConstants.AF_INET)
                .allowFamily(OsConstants.AF_INET6)
            // Semua aplikasi selain Telegram resmi tidak bisa menjangkau server Telegram (paket dibuang di readLoop).
            for ((ip, len) in TgClients.NET_V4) {
                try { b.addRoute(ip, len) } catch (e: Exception) { /* lewati */ }
            }
            for ((ip, len) in TgClients.NET_V6) {
                try { b.addRoute(ip, len) } catch (e: Exception) { /* lewati */ }
            }
            var skipped = 0
            for (p in excluded) {
                if (p == svc.packageName) continue
                try {
                    b.addDisallowedApplication(p)
                } catch (e: Exception) {
                    skipped++
                }
            }
            val p = b.establish() ?: return "izin VPN dicabut atau diambil VPN lain"
            pfd = p
            out = FileOutputStream(p.fileDescriptor)
            val input = FileInputStream(p.fileDescriptor)
            val t = Thread({ readLoop(input) }, "vpn-read")
            reader = t
            t.start()
            return null
        } catch (t: Throwable) {
            close()
            return t.message ?: t.javaClass.simpleName
        }
    }

    fun close() {
        closing = true
        try {
            pfd?.close()
        } catch (t: Throwable) {
            // abaikan
        }
        pfd = null
        out = null
        try {
            reader?.interrupt()
        } catch (t: Throwable) {
            // abaikan
        }
        reader = null
    }

    private fun readLoop(input: FileInputStream) {
        val buf = ByteArray(32767)
        var zeros = 0
        var cause = "terowongan ditutup"
        while (!closing) {
            val n = try {
                input.read(buf)
            } catch (e: Exception) {
                cause = "baca gagal: " + e.message
                -1
            }
            if (n < 0) break
            if (n == 0) {
                zeros++
                if (zeros > 500) {
                    cause = "terowongan tidak merespons"
                    break
                }
                try {
                    Thread.sleep(20)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }
            zeros = 0
            try {
                handle(buf.copyOf(n), n)
            } catch (t: Throwable) {
                // lewati paket rusak
            }
        }
        if (!closing) {
            closing = true
            onDead(cause)
        }
    }

    private fun handle(pkt: ByteArray, n: Int) {
        val p = VpnPackets.parse(pkt, n) ?: return
        if (p.proto == 6) {
            val rst = VpnPackets.buildRst(pkt, n, p) ?: return
            write(rst)
            return
        }
        if (p.proto != 17) return
        val u = VpnPackets.udpOf(pkt, n, p) ?: return
        if (u.dstPort != 53 || u.payload.size < 12) return
        engine.submit(Runnable {
            try {
                answer(u)
            } catch (t: Throwable) {
                // abaikan
            }
        })
    }

    private fun answer(u: VpnPackets.Udp) {
        VpnStatus.qTotal.incrementAndGet()
        val name = VpnPackets.queryName(u.payload)
        val blocked = name != null && blocklist.isBlocked(name)
        val reply: ByteArray? = if (blocked) {
            VpnStatus.qBlocked.incrementAndGet()
            VpnPackets.nxdomain(u.payload)
        } else {
            engine.resolve(u.payload)
        }
        if (reply != null) write(VpnPackets.buildUdp(u.dst, u.src, 53, u.srcPort, reply))
    }

    private fun write(p: ByteArray) {
        try {
            synchronized(outLock) { out?.write(p) }
        } catch (e: Exception) {
            // terowongan sedang ditutup
        }
    }
}
