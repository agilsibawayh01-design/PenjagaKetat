package com.mas.strictguard

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pendeteksi gambar tidak pantas (nude) yang berjalan sepenuhnya di HP, tanpa internet.
 * Model: NSFW.tflite dari proyek nsfw-detector-android (lisensi MIT, salinannya ada di assets/nsfw).
 * Label model: "nonnude" dan "nude". Skor = peluang label "nude".
 */
object NsfwAi {
    const val THRESHOLD = 0.75f

    @Volatile var loadError = ""
    @Volatile var scans = 0
    @Volatile var hits = 0
    @Volatile var lastScore = -1f
    @Volatile var lastAt = 0L
    @Volatile var lastFail = ""

    private var interp: Interpreter? = null
    private var nudeIdx = 1
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
            nudeIdx = labels.indexOf("nude")
            if (nudeIdx < 0) {
                loadError = "label 'nude' tidak ada di dict.txt"
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
            if (nudeIdx >= outN) {
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
        val h = bmp.height
        if (h >= 2) {
            val half = h / 2
            try {
                val top = Bitmap.createBitmap(bmp, 0, 0, bmp.width, half)
                best = Math.max(best, run(top))
                val bottom = Bitmap.createBitmap(bmp, 0, half, bmp.width, h - half)
                best = Math.max(best, run(bottom))
            } catch (t: Throwable) {
                // lewati potongan
            }
        }
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
                input.putFloat((r - 127.5f) / 127.5f)
                input.putFloat((g - 127.5f) / 127.5f)
                input.putFloat((b - 127.5f) / 127.5f)
            } else {
                input.put(r.toByte())
                input.put(g.toByte())
                input.put(b.toByte())
            }
        }
        input.rewind()
        return if (outType == DataType.FLOAT32) {
            val out = Array(1) { FloatArray(outN) }
            i.run(input, out)
            out[0][nudeIdx].coerceIn(0f, 1f)
        } else {
            val out = Array(1) { ByteArray(outN) }
            i.run(input, out)
            (out[0][nudeIdx].toInt() and 0xFF) / 255f
        }
    }

    fun status(c: Context): String {
        val eng = if (interp != null) "model siap" else if (loadError.isNotEmpty()) loadError else "model belum dimuat"
        val last = if (lastScore >= 0f) " • skor terakhir " + String.format("%.2f", lastScore) else ""
        val fail = if (lastFail.isNotEmpty()) " • " + lastFail else ""
        return "$eng • dipindai $scans kali • diblokir $hits kali$last$fail"
    }
}
