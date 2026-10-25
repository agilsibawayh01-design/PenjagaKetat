package com.mas.strictguard

import android.annotation.SuppressLint
import android.app.Activity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Peta untuk memilih titik: Leaflet + ubin OpenStreetMap di dalam WebView (tanpa kunci API dan tanpa
 * Google Play Services). Butuh internet; tanpa internet, peta menampilkan pesan dan kolom koordinat tetap bisa diisi.
 * Mengetuk peta mengisi kolom lintang/bujur; lingkaran menunjukkan radius.
 */
object MapPicker {
    class Handle(private val web: WebView) {
        fun setRadius(r: Int) {
            web.evaluateJavascript("if(window.setRadius)setRadius($r);", null)
        }

        fun center(lat: Double, lon: Double) {
            web.evaluateJavascript("if(window.setCenter)setCenter($lat,$lon);", null)
        }

        fun destroy() {
            try { web.destroy() } catch (t: Throwable) { }
        }
    }

    private const val HTML = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#m{height:100%;margin:0;background:#e8e8e8}p{font:14px sans-serif;padding:12px}</style></head>
<body><div id="m"></div><script>
if (typeof L === 'undefined') {
  document.body.innerHTML = '<p>Peta butuh internet. Isi lintang dan bujur secara manual di bawah.</p>';
} else {
  var map = L.map('m').setView([__LAT__, __LON__], __ZOOM__);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom: 19, attribution: '&copy; OpenStreetMap'}).addTo(map);
  var mk = null, ci = null, rad = __R__;
  function setPt(a, b) {
    if (mk) { mk.setLatLng([a, b]); ci.setLatLng([a, b]); }
    else { mk = L.marker([a, b]).addTo(map); ci = L.circle([a, b], {radius: rad}).addTo(map); }
  }
  window.setRadius = function (r) { rad = r; if (ci) ci.setRadius(r); };
  window.setCenter = function (a, b) { setPt(a, b); map.setView([a, b], 17); };
  map.on('click', function (e) { setPt(e.latlng.lat, e.latlng.lng); Android.pick(e.latlng.lat, e.latlng.lng); });
  __INIT__
}
</script></body></html>"""

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun attach(act: Activity, parent: LinearLayout, latF: EditText, lonF: EditText, radius: Int, heightPx: Int): Handle {
        val web = WebView(act)
        val lat0 = latF.text.toString().trim().toDoubleOrNull()
        val lon0 = lonF.text.toString().trim().toDoubleOrNull()
        val has = lat0 != null && lon0 != null && lat0 in -90.0..90.0 && lon0 in -180.0..180.0
        val fix = if (has) null else try { LocationTracker.current(act) } catch (t: Throwable) { null }
        val lat = if (has) lat0!! else fix?.latitude ?: -7.2575
        val lon = if (has) lon0!! else fix?.longitude ?: 112.7521
        val zoom = if (has || fix != null) 16 else 11
        val html = HTML
            .replace("__LAT__", lat.toString()).replace("__LON__", lon.toString())
            .replace("__ZOOM__", zoom.toString()).replace("__R__", radius.toString())
            .replace("__INIT__", if (has) "setPt($lat, $lon);" else "")
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = "PenjagaKetat/1.0 (Android)"
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun pick(a: Double, b: Double) {
                act.runOnUiThread {
                    latF.setText(String.format(java.util.Locale.US, "%.6f", a))
                    lonF.setText(String.format(java.util.Locale.US, "%.6f", b))
                }
            }
        }, "Android")
        web.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
        // Geser peta tidak boleh menggulung dialog di belakangnya.
        web.setOnTouchListener { v: View, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        parent.addView(web)
        web.loadDataWithBaseURL("https://penjagaketat.app/", html, "text/html", "utf-8", null)
        return Handle(web)
    }
}
