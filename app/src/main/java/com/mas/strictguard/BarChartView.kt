package com.mas.strictguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Diagram batang sederhana untuk Statistik. Hanya menggambar data yang diberikan; bila semua nilai 0
 * ditulis "Data belum tersedia" (tidak ada angka karangan).
 */
class BarChartView(
    c: Context,
    private val values: List<Long>,
    private val labels: List<String>,
    private val barColor: Int,
    private val textColor: Int,
    private val subColor: Int,
    private val valueFmt: (Long) -> String
) : View(c) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = subColor; textAlign = Paint.Align.CENTER }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = subColor; textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        val d = resources.displayMetrics.density
        setMeasuredDimension(MeasureSpec.getSize(w), (150 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        labelPaint.textSize = 10 * d
        valuePaint.textSize = 10 * d
        emptyPaint.textSize = 12 * d
        val max = values.maxOrNull() ?: 0L
        if (values.isEmpty() || max <= 0L) {
            canvas.drawText("Data belum tersedia", width / 2f, height / 2f, emptyPaint)
            return
        }
        val n = values.size
        val top = 18 * d
        val bottom = height - 18 * d
        val slot = width.toFloat() / n
        val bw = slot * 0.62f
        for (i in 0 until n) {
            val cx = slot * i + slot / 2f
            val h = (bottom - top) * values[i] / max
            if (values[i] > 0L) {
                rect.set(cx - bw / 2f, bottom - h, cx + bw / 2f, bottom)
                canvas.drawRoundRect(rect, 4 * d, 4 * d, barPaint)
                if (n <= 8) canvas.drawText(valueFmt(values[i]), cx, bottom - h - 3 * d, valuePaint)
            }
            if (n <= 8 || i % 5 == 0 || i == n - 1) {
                canvas.drawText(labels.getOrElse(i) { "" }, cx, height - 4 * d, labelPaint)
            }
        }
    }
}
