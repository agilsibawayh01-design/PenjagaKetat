package com.mas.strictguard

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * Pengelola siklus hidup VPN DNS. Satu-satunya pemilik status VPN.
 *
 * Aturan:
 * - semua perubahan status terjadi di satu thread (mgr), tanpa balapan
 * - status RUNNING baru dicapai setelah terowongan terbukti menjawab pertanyaan DNS
 * - jaringan berganti: soket dan cache direset, bukan VPN dibangun ulang
 * - terowongan mati: sambung ulang dengan jeda bertahap (2s, 5s, 15s, 30s, 1m, 2m, 5m)
 * - VPN tidak sehat setelah dipulihkan 2x, atau putus terlalu sering: VPN DIHENTIKAN agar internet tetap hidup (FAILSAFE)
 * - izin VPN dicabut atau diambil VPN lain: berhenti di NEED_PERMISSION, tidak ada perulangan
 * - tidak ada pemanggil berkala dari luar; Watchdog tidak boleh memaksa VPN hidup
 */
object VpnManager {
    private val thread = HandlerThread("vpn-mgr").also { it.start() }
    private val h = Handler(thread.looper)
    private val io = Executors.newCachedThreadPool()

    private val BACKOFF = longArrayOf(2000, 5000, 15000, 30000, 60000, 120000, 300000)
    private val FAILSAFE_RETRY = longArrayOf(60000, 300000, 900000)
    private const val HEALTH_MS = 20000L
    private val BANK_WORDS = listOf("wondr", "brimo", "mybca", "livin", "bibit", "digitalbanking")

    @Volatile
    private var app: Context? = null

    @Volatile
    private var svc: DnsVpnService? = null
    private var monitor: VpnNetworkMonitor? = null
    private var engine: DnsEngine? = null
    private var blocklist: DnsBlocklist? = null
    private var tunnel: VpnTunnel? = null

    private var token = 0
    private var backoffIdx = 0
    private var recoverTries = 0
    private var failsafeIdx = 0
    private var lastFailsafeAt = 0L
    private var runningSince = 0L
    private val deaths = ArrayList<Long>()

    // ---------------- API publik (aman dipanggil dari thread mana pun) ----------------

    /** Pengguna menyalakan VPN. Izin VPN harus sudah diberikan (lihat consentNeeded). */
    fun start(c: Context) {
        val a = c.applicationContext
        Store.setVpnOn(a, true)
        h.post {
            app = a
            backoffIdx = 0
            recoverTries = 0
            failsafeIdx = 0
            connect("dinyalakan pengguna")
        }
    }

    /** Pengguna mematikan VPN. */
    fun stop(c: Context) {
        val a = c.applicationContext
        Store.setVpnOn(a, false)
        h.post {
            app = a
            shutdown("dimatikan pengguna")
        }
    }

    /** Dipanggil sekali pada peristiwa siklus hidup (boot, layanan aksesibilitas tersambung, aplikasi dibuka). Tidak memaksa bila sedang mencoba atau dijeda. */
    fun ensure(c: Context) {
        val a = c.applicationContext
        h.post {
            app = a
            if (!Store.vpnOn(a)) return@post
            val s = VpnStatus.state
            if (s == VpnState.STOPPED || s == VpnState.NEED_PERMISSION) connect("ensure")
        }
    }

    /** Tombol "Sambung ulang" di layar. */
    fun restart(c: Context) {
        val a = c.applicationContext
        h.post {
            app = a
            if (!Store.vpnOn(a)) return@post
            token++
            h.removeCallbacksAndMessages(null)
            closeTunnel()
            backoffIdx = 0
            recoverTries = 0
            failsafeIdx = 0
            connect("sambung ulang manual")
        }
    }

    fun consentNeeded(c: Context): Intent? = VpnService.prepare(c)

    // ---------------- dari DnsVpnService ----------------

    fun onServiceStarted(s: DnsVpnService) {
        h.post {
            app = s.applicationContext
            val a = s.applicationContext
            if (!Store.vpnOn(a)) {
                s.stopSelf()
                return@post
            }
            if (svc !== s) {
                closeTunnel()
                engine?.shutdown()
                engine = null
                svc = s
            }
            if (tunnel == null || tunnel?.isOpen != true) connect("layanan siap")
        }
    }

    fun onRevoked(s: DnsVpnService) {
        h.post {
            if (svc !== s) return@post
            closeTunnel()
            val a = app
            val needs = a != null && VpnService.prepare(a) != null
            if (needs) {
                Store.log(s, "Izin VPN dicabut atau diambil VPN lain. Nyalakan lagi dari menu VPN.")
                setState(VpnState.NEED_PERMISSION, "dicabut")
            } else {
                scheduleReconnect("dicabut sistem")
            }
        }
    }

    fun onServiceDestroyed(s: DnsVpnService) {
        h.post {
            if (svc === s) {
                svc = null
                closeTunnel()
                engine?.shutdown()
                engine = null
                if (VpnStatus.state == VpnState.RUNNING || VpnStatus.state == VpnState.STARTING) {
                    val a = app
                    if (a != null && Store.vpnOn(a)) setState(VpnState.STOPPED, "layanan berhenti")
                    else setState(VpnState.STOPPED, "")
                }
            }
        }
    }

    // ---------------- inti (hanya di thread mgr) ----------------

    private fun setState(st: VpnState, detail: String = "") {
        VpnStatus.state = st
        VpnStatus.detail = detail
        try {
            svc?.updateNotification(VpnStatus.summary())
        } catch (t: Throwable) {
            // abaikan
        }
    }

    private fun parts(c: Context, s: DnsVpnService) {
        if (monitor == null) {
            monitor = VpnNetworkMonitor(c, h) { r -> onNet(r) }
            monitor!!.start()
        }
        SafeSearch.on = Store.safeSearch(c)
        if (blocklist == null) blocklist = DnsBlocklist(c)
        if (engine == null) {
            engine = DnsEngine(c, monitor!!, { sk -> svc?.protect(sk) ?: false }, { sk -> svc?.protect(sk) ?: false })
        }
        if (tunnel == null) {
            tunnel = VpnTunnel(s, engine!!, blocklist!!) { cause -> onTunnelDead(cause) }
        }
    }

    private fun connect(reason: String) {
        val c = app ?: return
        if (!Store.vpnOn(c)) return
        if (tunnel?.isOpen == true) return
        if (VpnService.prepare(c) != null) {
            Store.log(c, "VPN DNS butuh izin VPN. Buka menu VPN dan nyalakan.")
            setState(VpnState.NEED_PERMISSION, "izin belum diberikan")
            return
        }
        val s = svc
        if (s == null) {
            setState(VpnState.STARTING, "memulai layanan")
            try {
                c.startForegroundService(Intent(c, DnsVpnService::class.java))
            } catch (t: Throwable) {
                Store.log(c, "Layanan VPN tidak bisa dimulai: ${t.message}")
                setState(VpnState.STOPPED, "layanan ditolak sistem")
            }
            return
        }
        parts(c, s)
        if (monitor?.hasNetwork != true) {
            setState(VpnState.WAITING_NETWORK, "tidak ada jaringan")
            return
        }
        openTunnel()
    }

    private fun excludedApps(c: Context): Set<String> {
        val set = HashSet(Store.vpnExcluded(c))
        if (Store.vpnExcludeBanks(c)) {
            try {
                for (ai in c.packageManager.getInstalledApplications(0)) {
                    val p = ai.packageName
                    if (p in Config.BANK_HINT || BANK_WORDS.any { p.contains(it) }) set.add(p)
                }
            } catch (t: Throwable) {
                set.addAll(Config.BANK_HINT)
            }
        }
        // Telegram resmi dan Channel Blocker dikecualikan dari VPN karena rentang IP Telegram dialihkan lalu dibuang.
        set.addAll(TgClients.ALLOWED)
        set.remove(c.packageName)
        return set
    }

    private fun openTunnel() {
        val c = app ?: return
        val t = tunnel ?: return
        setState(VpnState.STARTING, "membuka terowongan")
        val err = t.open(excludedApps(c))
        if (err != null) {
            Store.log(c, "VPN DNS gagal dibuat: $err")
            if (VpnService.prepare(c) != null) setState(VpnState.NEED_PERMISSION, "izin VPN hilang")
            else scheduleReconnect("gagal membuat terowongan")
            return
        }
        verify()
    }

    private fun verify() {
        setState(VpnState.STARTING, "memverifikasi")
        val my = ++token
        io.execute {
            val ok = VpnHealth.tunnelAnswers(6000L)
            h.post { if (my == token) onVerified(ok) }
        }
    }

    private fun onVerified(ok: Boolean) {
        val c = app ?: return
        if (ok) {
            recoverTries = 0
            runningSince = SystemClock.elapsedRealtime()
            setState(VpnState.RUNNING, monitor?.transport ?: "")
            Store.log(c, "VPN DNS aktif (${monitor?.transport})")
            h.removeCallbacks(healthTick)
            h.postDelayed(healthTick, HEALTH_MS)
        } else {
            recover("terowongan tidak menjawab")
        }
    }

    private fun recover(reason: String) {
        val c = app ?: return
        recoverTries++
        if (recoverTries > 2) {
            failsafe(reason)
            return
        }
        Store.log(c, "VPN DNS dipulihkan ($recoverTries/2): $reason")
        token++
        closeTunnel()
        engine?.onNetworkChanged()
        setState(VpnState.RECONNECTING, "memulihkan")
        h.postDelayed({
            val a = app
            if (a != null && Store.vpnOn(a) && tunnel?.isOpen != true) connect("pemulihan")
        }, 1500L)
    }

    /** Hentikan VPN dengan aman supaya internet tetap hidup lewat DNS bawaan jaringan. */
    private fun failsafe(reason: String) {
        val c = app ?: return
        token++
        h.removeCallbacksAndMessages(null)
        closeTunnel()
        lastFailsafeAt = SystemClock.elapsedRealtime()
        val delay = FAILSAFE_RETRY[Math.min(failsafeIdx, FAILSAFE_RETRY.size - 1)]
        failsafeIdx++
        setState(VpnState.FAILSAFE, "coba lagi ${delay / 60000L} menit")
        Store.log(c, "VPN DNS dijeda otomatis agar internet tetap jalan: $reason")
        h.postDelayed({
            val a = app
            if (a != null && Store.vpnOn(a)) {
                recoverTries = 0
                deaths.clear()
                connect("coba lagi setelah jeda aman")
            }
        }, delay)
    }

    private val reconnectRunnable = Runnable {
        val a = app
        if (a != null && Store.vpnOn(a) && tunnel?.isOpen != true) connect("sambung ulang")
    }

    private fun scheduleReconnect(reason: String) {
        val c = app ?: return
        if (!Store.vpnOn(c)) return
        val now = SystemClock.elapsedRealtime()
        deaths.add(now)
        deaths.removeAll { now - it > 600000L }
        if (deaths.size > 6) {
            failsafe("terlalu sering putus ($reason)")
            return
        }
        val delay = BACKOFF[Math.min(backoffIdx, BACKOFF.size - 1)]
        backoffIdx++
        setState(VpnState.RECONNECTING, "coba lagi ${delay / 1000L} dtk")
        h.removeCallbacks(reconnectRunnable)
        h.postDelayed(reconnectRunnable, delay)
    }

    private fun onTunnelDead(cause: String) {
        h.post {
            val c = app ?: return@post
            if (tunnel?.isOpen == true) return@post
            Store.log(c, "VPN DNS terputus: $cause")
            token++
            closeTunnel()
            scheduleReconnect(cause)
        }
    }

    private fun closeTunnel() {
        token++
        h.removeCallbacks(healthTick)
        try {
            tunnel?.close()
        } catch (t: Throwable) {
            // abaikan
        }
        // objek terowongan dibuat ulang saat dibuka lagi
        tunnel = null
    }

    private fun shutdown(reason: String) {
        token++
        h.removeCallbacksAndMessages(null)
        closeTunnel()
        monitor?.stop()
        monitor = null
        engine?.shutdown()
        engine = null
        blocklist = null
        backoffIdx = 0
        recoverTries = 0
        failsafeIdx = 0
        deaths.clear()
        setState(VpnState.STOPPED, "")
        app?.let { Store.log(it, "VPN DNS mati ($reason)") }
        val s = svc
        svc = null
        try {
            s?.stopForeground(true)
            s?.stopSelf()
        } catch (t: Throwable) {
            // abaikan
        }
    }

    /** Jaringan berubah (Wi-Fi, seluler, hilang, kembali). */
    private fun onNet(reason: String) {
        val c = app ?: return
        if (!Store.vpnOn(c)) return
        val m = monitor ?: return
        if (!m.hasNetwork) {
            if (VpnStatus.state == VpnState.RUNNING || VpnStatus.state == VpnState.STARTING) {
                setState(VpnState.WAITING_NETWORK, "tidak ada jaringan")
            }
            return
        }
        engine?.onNetworkChanged()
        when (VpnStatus.state) {
            VpnState.WAITING_NETWORK -> if (tunnel?.isOpen == true) verify() else connect("jaringan kembali")
            VpnState.RUNNING -> {
                h.removeCallbacks(healthTick)
                h.postDelayed(healthTick, 1500L)
            }
            VpnState.RECONNECTING -> {
                h.removeCallbacks(reconnectRunnable)
                h.post(reconnectRunnable)
            }
            VpnState.FAILSAFE -> {
                if (SystemClock.elapsedRealtime() - lastFailsafeAt > 30000L) {
                    recoverTries = 0
                    connect("jaringan berubah saat dijeda")
                }
            }
            else -> {}
        }
    }

    /** Pemeriksa kesehatan khusus VPN (bukan Watchdog aplikasi). */
    private val healthTick = object : Runnable {
        override fun run() {
            val c = app ?: return
            if (VpnStatus.state != VpnState.RUNNING || !Store.vpnOn(c)) return
            val e = engine ?: return
            val now = SystemClock.elapsedRealtime()
            if (now - runningSince > 60000L) {
                backoffIdx = 0
                failsafeIdx = 0
            }
            val suspicious = e.failStreak.get() >= 4 && now - e.lastOkAt > 15000L
            if (!suspicious) {
                h.postDelayed(this, HEALTH_MS)
                return
            }
            val my = ++token
            io.execute {
                val up = VpnHealth.upstreamReachable(e)
                val tun = VpnHealth.tunnelAnswers(5000L)
                h.post { if (my == token) onDeepCheck(up, tun) }
            }
        }
    }

    private fun onDeepCheck(up: Boolean, tun: Boolean) {
        if (VpnStatus.state != VpnState.RUNNING) return
        if (monitor?.hasNetwork != true) {
            setState(VpnState.WAITING_NETWORK, "tidak ada jaringan")
            return
        }
        when {
            up && tun -> h.postDelayed(healthTick, HEALTH_MS)
            up && !tun -> recover("terowongan tidak menjawab")
            else -> {
                recoverTries++
                if (recoverTries >= 2) {
                    failsafe("DNS upstream tidak terjangkau")
                } else {
                    engine?.onNetworkChanged()
                    h.postDelayed(healthTick, 8000L)
                }
            }
        }
    }
}
