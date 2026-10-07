package com.checkinassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * v6.1: 一整幅连贯的城市天际线 + 轻轨插画（矢量绘制，替代 emoji 拼贴）。
 * 绘制顺序：夜空渐变背景（由外层横幅提供）→ 远景建筑 → 近景建筑 →
 * 高架轨道 → 轻轨列车 → 车窗灯光。宽高比固定，横向铺满。
 */
class SkylineBannerView(context: Context) : View(context) {

    private val farPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val nearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255) }
    private val windowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 236, 179) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val trainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val trainWindowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(59, 130, 246) }
    private val trainFrontPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 204, 21) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val ground = h * 0.86f

        // ── 远景天际线（矮、淡）──
        val farHeights = listOf(0.30f, 0.42f, 0.26f, 0.48f, 0.34f, 0.52f, 0.30f, 0.44f, 0.24f, 0.40f, 0.32f)
        val farW = w / farHeights.size
        farHeights.forEachIndexed { i, frac ->
            val left = i * farW; val top = ground - h * frac
            canvas.drawRect(left, top, left + farW * 0.92f, ground, farPaint)
            // 塔尖
            if (i % 3 == 1) canvas.drawRect(left + farW * 0.40f, top - h * 0.05f, left + farW * 0.52f, top, farPaint)
        }

        // ── 近景建筑（高、浓，带窗灯）──
        val nearHeights = listOf(0.22f, 0.38f, 0.30f, 0.20f)
        val nearW = w / nearHeights.size
        nearHeights.forEachIndexed { i, frac ->
            val left = i * nearW + nearW * 0.10f
            val right = left + nearW * 0.68f
            val top = ground - h * frac
            canvas.drawRect(left, top, right, ground, nearPaint)
            // 窗灯网格
            val cols = 3; val rows = (frac * 8).toInt().coerceIn(2, 6)
            for (r in 0 until rows) for (c in 0 until cols) {
                if ((i + r + c) % 3 == 0) continue // 部分窗熄灭，更自然
                val wx = left + right * (0.18f + 0.30f * c) - 3f
                val wy = top + (ground - top) * (0.12f + 0.76f * r / rows) - 3f
                canvas.drawRect(wx, wy, wx + 6f, wy + 7f, windowPaint)
            }
        }

        // ── 高架轨道 ──
        val trackY = ground - h * 0.10f
        canvas.drawLine(0f, trackY, w, trackY, trackPaint)
        // 轨道支柱
        val pierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = farPaint.color }
        var px = w * 0.08f
        while (px < w) {
            canvas.drawRect(px - 2.5f, trackY, px + 2.5f, ground, pierPaint)
            px += w * 0.22f
        }

        // ── 轻轨列车（车身+窗+车头灯）──
        val trainW = w * 0.34f; val trainH = h * 0.115f
        val trainLeft = w * 0.55f; val trainTop = trackY - trainH
        val body = RectF(trainLeft, trainTop, trainLeft + trainW, trainTop + trainH)
        canvas.drawRoundRect(body, trainH * 0.28f, trainH * 0.28f, trainPaint)
        // 车窗
        val winY = trainTop + trainH * 0.22f; val winH = trainH * 0.42f
        for (i in 0 until 4) {
            val wx = trainLeft + trainW * (0.12f + 0.19f * i)
            canvas.drawRoundRect(RectF(wx, winY, wx + trainW * 0.13f, winY + winH), 4f, 4f, trainWindowPaint)
        }
        // 车头（右端）+ 前灯
        canvas.drawCircle(trainLeft + trainW - trainH * 0.18f, trainTop + trainH * 0.5f, trainH * 0.09f, trainFrontPaint)
        // 车顶受电弓
        val pantY = trainTop
        val pant = Path().apply {
            moveTo(trainLeft + trainW * 0.42f, pantY)
            lineTo(trainLeft + trainW * 0.5f, pantY - trainH * 0.28f)
            lineTo(trainLeft + trainW * 0.58f, pantY)
            close()
        }
        canvas.drawPath(pant, trainPaint)
    }
}
