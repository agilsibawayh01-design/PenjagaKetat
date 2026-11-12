package com.mas.strictguard

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pendeteksi gambar tidak pantas (nude) yang berjalan sepenuhnya di HP, tanpa internet.
 * Model: NSFW.tflite dari proyek GantMan/nsfw_model, MobileNetV2 (lisensi MIT, salinannya ada di assets/nsfw).
 * Label model: drawings, hentai, neutral, porn, sexy. Skor = porn + hentai + setengah sexy.
 */
object NsfwAi {
    const val THRESHOLD = 0.5f

    @Volatile var loadError = ""
    @Volatile var scans = 0
    @Volatile var hits = 0
    @Volatile var lastScore = -1f
    @Volatile var lastAt = 0L
    @Volatile var lastFail = ""
    @Volatile var lastWhere = ""
    @Volatile var hitInfo = ""

    private var interp: Interpreter? = null
    private var pornIdx = 0
    private var hentaiIdx = -1
    private var sexyIdx = -1
    private var inW = 224
    private var inH = 224
    private var inType = DataType.UINT8
    private var outType = DataType.UINT8
    private var outN = 2

    @Synchronized
    fun ready(c: Context): Boolean {
        if (interp != null) return true
        if (loadError.isNotEmpty()) return false
        try {
            val bytes = c.assets.open("nsfw/NSFW.tflite").use { it.readBytes() }
            val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            bb.put(bytes)
            bb.rewind()
            val labels = c.assets.open("nsfw/dict.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
            pornIdx = labels.indexOf("porn")
            hentaiIdx = labels.indexOf("hentai")
            sexyIdx = labels.indexOf("sexy")
            if (pornIdx < 0) {
                loadError = "label 'porn' tidak ada di dict.txt"
                return false
            }
            val i = Interpreter(bb, Interpreter.Options().setNumThreads(2))
            val it = i.getInputTensor(0)
            val s = it.shape() // [1, H, W, 3]
            inH = s[1]
            inW = s[2]
            inType = it.dataType()
            val ot = i.getOutputTensor(0)
            outType = ot.dataType()
            outN = ot.shape().last()
            if (outN != labels.size) {
                loadError = "keluaran model tidak cocok dengan label"
                return false
            }
            interp = i
            return true
        } catch (t: Throwable) {
            loadError = "gagal memuat model: " + (t.message ?: t.javaClass.simpleName)
            return false
        }
    }

    /** Skor tertinggi (0..1) dari seluruh layar dan dua separuhnya (atas, bawah), supaya gambar kecil tetap tertangkap. */
    fun score(c: Context, bmp: Bitmap): Float {
        if (!ready(c)) return 0f
        var best = run(bmp)
        var where = "seluruh layar"
        // Potongan persegi selebar layar digeser setengah-setengah dari atas ke bawah, supaya gambar
        // yang kecil atau terbelah di tengah layar tetap terbaca utuh dan tidak gepeng.
        val w = bmp.width
        val h = bmp.height
        val side = Math.min(w, h)
        if (side > 0 && h > side) {
            val step = Math.max(side / 2, 1)
            var y = 0
            while (true) {
                val yy = Math.min(y, h - side)
                try {
                    val t = Bitmap.createBitmap(bmp, 0, yy, side, side)
                    val sc = run(t)
                    if (sc > best) {
                        best = sc
                        where = "potongan di ketinggian " + (yy * 100 / h) + "% layar"
                    }
                    if (t !== bmp) t.recycle()
                } catch (t: Throwable) {
                    // lewati potongan
                }
                if (yy >= h - side) break
                y += step
            }
        }
        lastWhere = where
        scans++
        lastScore = best
        lastAt = System.currentTimeMillis()
        return best
    }

    @Synchronized
    private fun run(src: Bitmap): Float {
        val i = interp ?: return 0f
        val scaled = Bitmap.createScaledBitmap(src, inW, inH, true)
        val px = IntArray(inW * inH)
        scaled.getPixels(px, 0, inW, 0, 0, inW, inH)
        if (scaled !== src) scaled.recycle()
        val input = ByteBuffer.allocateDirect(inW * inH * 3 * (if (inType == DataType.FLOAT32) 4 else 1)).order(ByteOrder.nativeOrder())
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (inType == DataType.FLOAT32) {
                input.putFloat(r / 255f)
                input.putFloat(g / 255f)
                input.putFloat(b / 255f)
            } else {
                input.put(r.toByte())
                input.put(g.toByte())
                input.put(b.toByte())
            }
        }
        input.rewind()
        val p = FloatArray(outN)
        if (outType == DataType.FLOAT32) {
            val out = Array(1) { FloatArray(outN) }
            i.run(input, out)
            for (k in 0 until outN) p[k] = out[0][k]
        } else {
            val out = Array(1) { ByteArray(outN) }
            i.run(input, out)
            for (k in 0 until outN) p[k] = (out[0][k].toInt() and 0xFF) / 255f
        }
        // Skor: porno + hentai penuh, "sexy" (setengah telanjang/pakaian dalam) dihitung setengah.
        var sc = p[pornIdx]
        if (hentaiIdx >= 0) sc += p[hentaiIdx]
        if (sexyIdx >= 0) sc += 0.5f * p[sexyIdx]
        return sc.coerceIn(0f, 1f)
    }

    fun status(c: Context): String {
        val eng = if (interp != null) "model siap" else if (loadError.isNotEmpty()) loadError else "model belum dimuat"
        val last = if (lastScore >= 0f) " • skor terakhir " + String.format("%.2f", lastScore) else ""
        val fail = if (lastFail.isNotEmpty()) " • " + lastFail else ""
        return "$eng • dipindai $scans kali • diblokir $hits kali$last$fail"
    }
}
