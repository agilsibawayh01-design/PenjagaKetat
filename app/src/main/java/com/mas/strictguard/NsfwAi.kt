package com.mas.strictguard

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
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
    const val THRESHOLD = 0.9f

    @Volatile var loadError = ""
    @Volatile var scans = 0
    @Volatile var hits = 0
    @Volatile var lastScore = -1f
    @Volatile var lastAt = 0L
    @Volatile var lastFail = ""
    @Volatile var lastWhere = ""
    @Volatile var hitInfo = ""

    private var interp: Interpreter? = null

    // Mesin utama: model ONNX "image-safety-classifier" (kelas: NSFL, NSFW, SFW). Bila tidak ada di APK,
    // dipakai model TFLite cadangan (5 kategori).
    private var onnxTried = false
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var ortIn = "image"
    private var ortSide = 224
    @Volatile var backend = ""
    @Volatile var onnxNote = ""

    private fun loadOnnx(c: Context) {
        onnxTried = true
        try {
            val names = c.assets.list("nsfw") ?: emptyArray()
            if (!names.contains("safety.onnx")) {
                onnxNote = "model ONNX tidak ikut di APK (pakai model cadangan)"
                return
            }
            val stamp = try {
                c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime
            } catch (t: Throwable) {
                0L
            }
            val f = java.io.File(c.filesDir, "safety-$stamp.onnx")
            if (!f.exists() || f.length() < 1_000_000L) {
                c.filesDir.listFiles()?.forEach { if (it.name.startsWith("safety-")) it.delete() }
                val tmp = java.io.File(c.filesDir, "safety-tmp.onnx")
                c.assets.open("nsfw/safety.onnx").use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
                tmp.renameTo(f)
            }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(2)
            val sess = env.createSession(f.absolutePath, opts)
            ortIn = sess.inputNames.iterator().next()
            val ti = sess.inputInfo[ortIn]?.info as? TensorInfo
            val sh = ti?.shape
            if (sh != null && sh.size == 4 && sh[2] > 0) ortSide = sh[2].toInt()
            ortEnv = env
            ortSession = sess
            backend = "ONNX (NSFW/NSFL/SFW)"
        } catch (t: Throwable) {
            onnxNote = "ONNX gagal: " + (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun runOrt(src: Bitmap): Float {
        val env = ortEnv ?: return 0f
        val sess = ortSession ?: return 0f
        val sd = ortSide
        val scaled = Bitmap.createScaledBitmap(src, sd, sd, true)
        val px = IntArray(sd * sd)
        scaled.getPixels(px, 0, sd, 0, 0, sd, sd)
        if (scaled !== src) scaled.recycle()
        val plane = sd * sd
        val arr = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = px[i]
            arr[i] = ((p shr 16) and 0xFF).toFloat()
            arr[plane + i] = ((p shr 8) and 0xFF).toFloat()
            arr[2 * plane + i] = (p and 0xFF).toFloat()
        }
        val tensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(arr), longArrayOf(1, 3, sd.toLong(), sd.toLong()))
        try {
            val res = sess.run(mapOf(ortIn to tensor))
            try {
                @Suppress("UNCHECKED_CAST")
                val o = (res.get(0).value as Array<FloatArray>)[0]
                var sum = 0f
                var neg = false
                for (v in o) { sum += v; if (v < 0f) neg = true }
                val pr = FloatArray(o.size)
                if (neg || sum > 1.01f || sum < 0.99f) {
                    var mx = o[0]
                    for (v in o) if (v > mx) mx = v
                    var z = 0f
                    for (k in o.indices) { pr[k] = Math.exp((o[k] - mx).toDouble()).toFloat(); z += pr[k] }
                    for (k in pr.indices) pr[k] /= z
                } else {
                    for (k in o.indices) pr[k] = o[k]
                }
                // Urutan kelas: 0 = NSFL, 1 = NSFW, 2 = SFW. Skor = NSFW + NSFL.
                return if (pr.size >= 3) (pr[0] + pr[1]).coerceIn(0f, 1f) else pr[pr.size - 1].coerceIn(0f, 1f)
            } finally {
                res.close()
            }
        } finally {
            tensor.close()
        }
    }
    private var pornIdx = 0
    private var hentaiIdx = -1
    // Label "sexy" sering mencakup foto orang berpakaian normal/renang.
    // Tidak dipakai sebagai dasar pemblokiran otomatis; hanya porn + hentai.
    private var sexyIdx = -1
    private var inW = 224
    private var inH = 224
    private var inType = DataType.UINT8
    private var outType = DataType.UINT8
    private var outN = 2

    @Synchronized
    fun ready(c: Context): Boolean {
        if (ortSession != null || interp != null) return true
        if (!onnxTried) {
            loadOnnx(c)
            if (ortSession != null) return true
        }
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
            backend = "TFLite cadangan"
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
        // Maksimal tiga crop (atas, tengah, bawah), bukan loop setengah-geser
        // yang dapat menjalankan 4-6 inferensi untuk setiap screenshot.
        val w = bmp.width
        val h = bmp.height
        val side = Math.min(w, h)
        if (side > 0 && h > side) {
            val maxY = h - side
            val positions = intArrayOf(0, maxY / 2, maxY).distinct()
            for (yy in positions) {
                try {
                    val t = Bitmap.createBitmap(bmp, 0, yy, side, side)
                    val sc = run(t)
                    if (sc > best) {
                        best = sc
                        where = "potongan di ketinggian " + (yy * 100 / h) + "% layar"
                    }
                    if (t !== bmp) t.recycle()
                } catch (t: Throwable) {
                    // lewati potongan yang gagal
                }
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
        if (ortSession != null) return runOrt(src)
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
        // Pemblokiran otomatis hanya berdasarkan kelas eksplisit.
        // Kelas "sexy" sengaja tidak dijumlahkan karena sering memicu false positive.
        var sc = p[pornIdx]
        if (hentaiIdx >= 0) sc += p[hentaiIdx]
        return sc.coerceIn(0f, 1f)
    }

    fun status(c: Context): String {
        val eng = if (interp != null || ortSession != null) "model siap: " + backend + (if (onnxNote.isNotEmpty()) " [" + onnxNote + "]" else "") else if (loadError.isNotEmpty()) loadError else "model belum dimuat"
        val last = if (lastScore >= 0f) " • skor terakhir " + String.format("%.2f", lastScore) else ""
        val fail = if (lastFail.isNotEmpty()) " • " + lastFail else ""
        return "$eng • dipindai $scans kali • dilewati ${NsfwScope.skips} kali (aplikasi non-media) • diblokir $hits kali$last$fail"
    }
}
