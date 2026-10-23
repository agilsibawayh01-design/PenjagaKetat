package com.mas.strictguard

/** Pengolah paket IPv4 mentah dan pesan DNS. Tanpa state, aman dipakai banyak thread. */
object VpnPackets {
    fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    class Udp(val src: ByteArray, val dst: ByteArray, val srcPort: Int, val dstPort: Int, val payload: ByteArray)

    /** Hasil penguraian paket dari terowongan. */
    class Parsed(val proto: Int, val ihl: Int, val src: ByteArray, val dst: ByteArray)

    fun parse(pkt: ByteArray, n: Int): Parsed? {
        if (n < 20) return null
        if (((pkt[0].toInt() shr 4) and 0x0F) != 4) return null
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (ihl < 20 || n < ihl + 8) return null
        return Parsed(pkt[9].toInt() and 0xFF, ihl, pkt.copyOfRange(12, 16), pkt.copyOfRange(16, 20))
    }

    fun udpOf(pkt: ByteArray, n: Int, p: Parsed): Udp? {
        val srcPort = u16(pkt, p.ihl)
        val dstPort = u16(pkt, p.ihl + 2)
        val udpLen = u16(pkt, p.ihl + 4)
        val len = Math.min(udpLen - 8, n - p.ihl - 8)
        if (len < 1) return null
        return Udp(p.src, p.dst, srcPort, dstPort, pkt.copyOfRange(p.ihl + 8, p.ihl + 8 + len))
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

    /** Paket UDP balasan (src/dst sudah dibalik oleh pemanggil). */
    fun buildUdp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val p = ipHeader(total, 17, src, dst)
        p[20] = (srcPort shr 8).toByte()
        p[21] = srcPort.toByte()
        p[22] = (dstPort shr 8).toByte()
        p[23] = dstPort.toByte()
        val ul = 8 + payload.size
        p[24] = (ul shr 8).toByte()
        p[25] = ul.toByte()
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    /** RST+ACK sebagai balasan sambungan TCP ke DNS palsu, supaya DNS-over-TLS cepat gagal. */
    fun buildRst(pkt: ByteArray, n: Int, p: Parsed): ByteArray? {
        val ihl = p.ihl
        if (n < ihl + 20) return null
        val flags = pkt[ihl + 13].toInt() and 0xFF
        if ((flags and 0x04) != 0) return null
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
        val out = ipHeader(40, 6, p.dst, p.src)
        out[20] = (dstPort shr 8).toByte()
        out[21] = dstPort.toByte()
        out[22] = (srcPort shr 8).toByte()
        out[23] = srcPort.toByte()
        for (k in 0 until 4) {
            out[24 + k] = (replySeq shr (24 - 8 * k)).toByte()
            out[28 + k] = (replyAck shr (24 - 8 * k)).toByte()
        }
        out[32] = 0x50
        out[33] = 0x14
        var init = 0L
        for (k in 0 until 4 step 2) {
            init += ((p.dst[k].toInt() and 0xFF) shl 8) or (p.dst[k + 1].toInt() and 0xFF)
            init += ((p.src[k].toInt() and 0xFF) shl 8) or (p.src[k + 1].toInt() and 0xFF)
        }
        init += 6
        init += 20
        val c = csum(out, 20, 20, init)
        out[36] = (c shr 8).toByte()
        out[37] = c.toByte()
        return out
    }

    // ---------- pesan DNS ----------

    fun queryName(q: ByteArray): String? {
        if (q.size < 13) return null
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

    /** Indeks setelah bagian pertanyaan, atau -1 bila tidak valid. */
    fun questionEnd(q: ByteArray): Int {
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

    /** Kunci cache: nama + tipe + kelas (tanpa ID). */
    fun cacheKey(q: ByteArray): String? {
        val end = questionEnd(q)
        if (end < 0) return null
        val sb = StringBuilder()
        for (k in 12 until end) sb.append((q[k].toInt() and 0xFF).toChar())
        return sb.toString().lowercase()
    }

    fun nxdomain(q: ByteArray): ByteArray? {
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

    fun servfail(q: ByteArray): ByteArray? {
        val end = questionEnd(q)
        if (end < 0) return null
        val r = q.copyOfRange(0, end)
        r[2] = (0x80 or (q[2].toInt() and 0x79)).toByte()
        r[3] = 0x82.toByte()
        r[4] = 0
        r[5] = 1
        for (k in 6..11) r[k] = 0
        return r
    }

    /** Jawaban sah: ID sama, bit QR menyala, panjang cukup. */
    fun validReply(q: ByteArray, r: ByteArray, len: Int): Boolean {
        if (len < 12) return false
        if (r[0] != q[0] || r[1] != q[1]) return false
        if ((r[2].toInt() and 0x80) == 0) return false
        return true
    }

    fun rcode(r: ByteArray): Int = r[3].toInt() and 0x0F
    fun truncated(r: ByteArray): Boolean = (r[2].toInt() and 0x02) != 0
    fun answerCount(r: ByteArray): Int = u16(r, 6)

    /** Pertanyaan DNS tipe A untuk satu nama (dipakai uji kesehatan). */
    fun buildQuery(name: String, id: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8)
        out.write(id and 0xFF)
        out.write(0x01)
        out.write(0x00)
        out.write(0)
        out.write(1)
        for (k in 0 until 6) out.write(0)
        for (part in name.split('.')) {
            out.write(part.length)
            for (ch in part) out.write(ch.code)
        }
        out.write(0)
        out.write(0)
        out.write(1)
        out.write(0)
        out.write(1)
        return out.toByteArray()
    }
}
