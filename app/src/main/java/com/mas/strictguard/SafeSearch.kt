package com.mas.strictguard

import java.io.ByteArrayOutputStream

/** Memaksa SafeSearch Google lewat DNS: www.google.* dijawab dengan alamat forcesafesearch.google.com. */
object SafeSearch {
    @Volatile var on = true

    private const val TARGET = "forcesafesearch.google.com"
    private val HOST = Regex("^www\\.google\\.(com|[a-z]{2,3}|co\\.[a-z]{2}|com\\.[a-z]{2,3})$")

    fun applies(name: String): Boolean = HOST.matches(name)

    private fun qtype(q: ByteArray): Int {
        val end = VpnPackets.questionEnd(q)
        return if (end < 0) -1 else VpnPackets.u16(q, end - 4)
    }

    /** Jawaban pengganti untuk pertanyaan, atau null bila tidak berlaku (lanjut ke resolusi biasa). */
    fun answer(q: ByteArray, name: String, resolve: (ByteArray) -> ByteArray?): ByteArray? {
        if (!on || !applies(name)) return null
        val t = qtype(q)
        if (t == 65) return VpnPackets.nodata(q)
        if (t != 1 && t != 28) return null
        val swapped = withName(q, TARGET) ?: return null
        val resp = resolve(swapped) ?: return null
        if (resp.size < 12 || VpnPackets.rcode(resp) != 0 || VpnPackets.answerCount(resp) == 0) return null
        return restoreName(q, resp)
    }

    private fun withName(q: ByteArray, newName: String): ByteArray? {
        val end = VpnPackets.questionEnd(q)
        if (end < 0) return null
        val out = ByteArrayOutputStream()
        out.write(q, 0, 12)
        val b = out.toByteArray()
        out.reset()
        for (k in 4..11) b[k] = 0
        b[5] = 1
        out.write(b)
        for (part in newName.split('.')) {
            out.write(part.length)
            for (ch in part) out.write(ch.code)
        }
        out.write(0)
        out.write(q, end - 4, 4)
        return out.toByteArray()
    }

    private fun restoreName(orig: ByteArray, resp: ByteArray): ByteArray? {
        val oe = VpnPackets.questionEnd(orig)
        val re = VpnPackets.questionEnd(resp)
        if (oe < 0 || re < 0) return null
        val out = ByteArrayOutputStream()
        out.write(resp, 0, 12)
        out.write(orig, 12, oe - 12)
        out.write(resp, re, resp.size - re)
        val r = out.toByteArray()
        r[0] = orig[0]
        r[1] = orig[1]
        return r
    }
}
