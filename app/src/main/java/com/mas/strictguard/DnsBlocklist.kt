package com.mas.strictguard

import android.content.Context

/** Keputusan blokir DNS. Daftar dimuat ulang paling cepat tiap 3 detik. */
class DnsBlocklist(private val ctx: Context) {
    @Volatile
    private var domains: Set<String> = emptySet()

    @Volatile
    private var keywords: Set<String> = emptySet()

    @Volatile
    private var blockDoh = false

    @Volatile
    private var paused = false
    private var loadedAt = 0L
    private val lastLog = HashMap<String, Long>()

    private fun refresh() {
        val now = System.currentTimeMillis()
        if (now - loadedAt < 3000L) return
        loadedAt = now
        try {
            val act = ProfileStore.load(ctx).filter { ProfileEngine.isActive(ctx, it) }
            domains = Store.domains(ctx) + Categories.domains(ctx) + act.flatMap { it.domains }
            keywords = Store.keywords(ctx) + Categories.keywords(ctx) + act.flatMap { it.keywords }
            blockDoh = Store.vpnDoh(ctx)
            paused = Store.pausedNow(ctx)
        } catch (t: Throwable) {
            // pakai daftar lama
        }
    }

    fun isBlocked(name: String): Boolean {
        refresh()
        if (paused) return false
        var hit = false
        if (blockDoh) {
            for (d in DOH) if (name == d || name.endsWith(".$d")) hit = true
        }
        if (!hit && Rules.matchesDomain(name, domains) != null) hit = true
        if (!hit && Rules.matchesKeyword(name, keywords) != null) hit = true
        if (hit) {
            val now = System.currentTimeMillis()
            var log = false
            synchronized(lastLog) {
                val prev = lastLog[name] ?: 0L
                if (now - prev > 30000L) {
                    lastLog[name] = now
                    if (lastLog.size > 500) lastLog.clear()
                    log = true
                }
            }
            if (log) StatsDb.addBlock(ctx, "dns", name)
        }
        return hit
    }

    companion object {
        val DOH = listOf(
            "dns.google", "cloudflare-dns.com", "mozilla.cloudflare-dns.com", "dns.quad9.net",
            "doh.opendns.com", "dns.nextdns.io", "dns.adguard.com", "doh.cleanbrowsing.org"
        )
    }
}
