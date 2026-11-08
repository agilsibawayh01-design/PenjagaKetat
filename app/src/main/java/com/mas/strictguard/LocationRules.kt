package com.mas.strictguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Aturan lokasi untuk satu aplikasi ("app_<paket>") atau situs ("site_<domain>"). */
data class LocRule(
    val lat: Double,
    val lon: Double,
    val radius: Int,
    /** false = blokir saat saya di sini (di dalam radius); true = blokir saat saya jauh (di luar radius). */
    val away: Boolean,
    /** Blokir bila lokasi tidak bisa ditentukan (izin/GPS mati). */
    val blockUnavailable: Boolean
)

object LocationRules {
    /** Posisi dianggap basi bila lebih tua dari ini. */
    const val MAX_AGE_MS = 10 * 60000L

    private fun sp(c: Context) = c.getSharedPreferences("strictguard", Context.MODE_PRIVATE)

    fun get(c: Context, key: String): LocRule? {
        val raw = sp(c).getString("loc_$key", null) ?: return null
        return try {
            val p = raw.split('|')
            LocRule(p[0].toDouble(), p[1].toDouble(), p[2].toInt(), p[3] == "1", p[4] == "1")
        } catch (t: Throwable) {
            null
        }
    }

    fun set(c: Context, key: String, r: LocRule?) {
        val e = sp(c).edit()
        if (r == null) e.remove("loc_$key")
        else e.putString("loc_$key", "${r.lat}|${r.lon}|${r.radius}|${if (r.away) 1 else 0}|${if (r.blockUnavailable) 1 else 0}")
        e.commit()
        LocationTracker.ensure(c)
    }

    fun anyRule(c: Context): Boolean = sp(c).all.keys.any { it.startsWith("loc_") }

    /** True bila aturan lokasi pada [key] sedang mewajibkan blokir. */
    fun blocked(c: Context, key: String): Boolean {
        val r = get(c, key) ?: return false
        val fix = LocationTracker.current(c) ?: return r.blockUnavailable
        val out = FloatArray(1)
        Location.distanceBetween(fix.latitude, fix.longitude, r.lat, r.lon, out)
        val inside = out[0] <= r.radius
        return if (r.away) !inside else inside
    }
}

/**
 * Memantau lokasi lewat LocationManager bawaan Android (tanpa Google Play Services), hanya selama
 * ada aturan lokasi. Posisi terakhir disimpan agar tetap ada setelah proses dimulai ulang.
 */
object LocationTracker {
    private fun sp(c: Context) = c.getSharedPreferences("strictguard_loc", Context.MODE_PRIVATE)

    @Volatile
    private var last: Location? = null
    private var netReg = false
    private var gpsReg = false

    @Volatile
    private var appCtx: Context? = null
    private var lm: LocationManager? = null
    private val main = Handler(Looper.getMainLooper())

    private fun makeListener() = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            last = l
            appCtx?.let {
                Zone.update(it)
                ensure(it) // GPS menyala/mati sesuai jarak ke zona
            }
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val netListener = makeListener()
    private val gpsListener = makeListener()

    /** Jarak (meter) dari posisi [l] ke batas aturan lokasi terdekat; null bila tidak ada aturan. */
    private fun nearestBoundary(c: Context, l: Location): Float? {
        var best: Float? = null
        val out = FloatArray(1)
        for (k in sp(c).all.keys) {
            if (!k.startsWith("loc_")) continue
            val r = LocationRules.get(c, k.removePrefix("loc_")) ?: continue
            Location.distanceBetween(l.latitude, l.longitude, r.lat, r.lon, out)
            val d = Math.abs(out[0] - r.radius)
            if (best == null || d < best) best = d
        }
        return best
    }

    /**
     * GPS hanya perlu saat dekat zona (dalam ~2 km dari batasnya) atau saat posisi jaringan tidak ada.
     * Jauh dari zona cukup posisi jaringan yang murah. Histeresis 2 km menyala / 3 km mati.
     */
    private fun gpsNeeded(c: Context): Boolean {
        if (!netReg) return true
        val fix = current(c) ?: return true
        val d = nearestBoundary(c, fix) ?: return false
        return d < (if (gpsReg) 3000f else 2000f)
    }

    fun hasPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(c: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Posisi segar (tidak basi), atau null bila tidak diketahui. */
    fun current(c: Context): Location? {
        var l = last
        if (l == null) {
            val p = sp(c)
            if (p.contains("lat")) {
                l = Location("saved").apply {
                    latitude = java.lang.Double.longBitsToDouble(p.getLong("lat", 0L))
                    longitude = java.lang.Double.longBitsToDouble(p.getLong("lon", 0L))
                    time = p.getLong("at", 0L)
                }
            }
        }
        if (l == null) return null
        val age = System.currentTimeMillis() - l.time
        if (age > LocationRules.MAX_AGE_MS || age < -60000L) return null
        return l
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun ensure(c: Context) {
        val ctx = c.applicationContext
        appCtx = ctx
        val need = LocationRules.anyRule(ctx) && hasPermission(ctx)
        try {
            if (!need) {
                if (netReg || gpsReg) {
                    lm?.removeUpdates(netListener)
                    lm?.removeUpdates(gpsListener)
                    netReg = false
                    gpsReg = false
                }
                return
            }
            val m = lm ?: (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).also { lm = it }
            if (!netReg && m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60000L, 0f, netListener, Looper.getMainLooper())
                netReg = true
            }
            val gpsOk = m.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val wantGps = gpsOk && gpsNeeded(ctx)
            if (wantGps && !gpsReg) {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 120000L, 0f, gpsListener, Looper.getMainLooper())
                gpsReg = true
            } else if (!wantGps && gpsReg) {
                m.removeUpdates(gpsListener)
                gpsReg = false
            }
            // Ambil posisi terakhir yang diketahui sistem bila belum ada yang segar.
            if (current(ctx) == null) {
                var best: Location? = null
                for (pv in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                    val l = (try { m.getLastKnownLocation(pv) } catch (t: Throwable) { null }) ?: continue
                    if (best == null || l.time > best.time) best = l
                }
                if (best != null) accept(ctx, best)
            }
        } catch (t: Throwable) {
            // izin dicabut atau penyedia lokasi mati: lokasi dianggap tidak tersedia
        }
    }

    private fun accept(c: Context, l: Location) {
        last = l
        sp(c).edit()
            .putLong("lat", java.lang.Double.doubleToRawLongBits(l.latitude))
            .putLong("lon", java.lang.Double.doubleToRawLongBits(l.longitude))
            .putLong("at", l.time)
            .apply()
    }

    /** Ambil posisi sekarang untuk dipakai saat mengatur lokasi. [cb] dipanggil di thread utama; null bila gagal dalam 20 detik. */
    @SuppressLint("MissingPermission")
    fun fetchNow(c: Context, cb: (Location?) -> Unit) {
        val ctx = c.applicationContext
        if (!hasPermission(ctx)) {
            cb(null)
            return
        }
        try {
            val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var done = false
            val once = object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    if (done) return
                    done = true
                    try { m.removeUpdates(this) } catch (t: Throwable) { }
                    accept(ctx, l)
                    cb(l)
                }

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            var asked = false
            for (pv in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (m.isProviderEnabled(pv)) {
                    m.requestLocationUpdates(pv, 0L, 0f, once, Looper.getMainLooper())
                    asked = true
                }
            }
            if (!asked) {
                cb(null)
                return
            }
            main.postDelayed({
                if (!done) {
                    done = true
                    try { m.removeUpdates(once) } catch (t: Throwable) { }
                    cb(current(ctx))
                }
            }, 20000L)
        } catch (t: Throwable) {
            cb(null)
        }
    }
}
