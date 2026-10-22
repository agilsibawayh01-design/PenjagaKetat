package com.mas.strictguard

import android.graphics.Bitmap

data class DetectionResult(val label: String, val score: Float)

/**
 * PLACEHOLDER: antarmuka mesin deteksi gambar (mis. NSFW). Saat ini TIDAK ada model dan TIDAK ada
 * pengambilan layar yang terhubung, jadi tidak ada gambar yang dianalisis.
 *
 * Untuk mengaktifkannya nanti: (1) tambahkan model (mis. TensorFlow Lite) dan dependensinya,
 * (2) buat kelas yang mengimplementasikan [ContentDetectionEngine], (3) ganti [Detection.engine],
 * (4) hubungkan tangkapan layar (AccessibilityService.takeScreenshot, Android 11+) ke [analyze].
 */
interface ContentDetectionEngine {
    val name: String
    val ready: Boolean
    fun analyze(frame: Bitmap): DetectionResult?
}

object NullDetectionEngine : ContentDetectionEngine {
    override val name = "Belum ada model"
    override val ready = false
    override fun analyze(frame: Bitmap): DetectionResult? = null
}

object Detection {
    @Volatile
    var engine: ContentDetectionEngine = NullDetectionEngine
}
