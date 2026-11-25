package com.stamper.app.domain.stamper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.min

object WatermarkRenderer {

    fun resolveWatermarkText(note: String): String = note.trim()

    /**
     * Draws ONLY the user's voice/text note onto the chosen corner of [source].
     * Never prints date/time or project name on the visual watermark.
     */
    fun renderWatermark(
        source: Bitmap,
        note: String,
        corner: WatermarkCorner,
        scale: WatermarkScale
    ): Bitmap {
        val cleanNote = resolveWatermarkText(note)
        if (cleanNote.isEmpty()) return source

        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source

        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        val shortEdge = min(width, height).toFloat()
        val textSizePx = (shortEdge * 0.036f * scale.factor).coerceIn(22f, 180f)
        val edgeMargin = (shortEdge * 0.032f).coerceAtLeast(16f)
        val padH = textSizePx * 0.65f
        val padV = textSizePx * 0.45f
        val cornerRadius = textSizePx * 0.42f

        val maxTextWidth = ((width - edgeMargin * 2f - padH * 2f) * 0.78f)
            .toInt()
            .coerceAtLeast(120)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = textSizePx
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(textSizePx * 0.12f, 0f, textSizePx * 0.04f, Color.argb(160, 0, 0, 0))
        }

        val staticLayout = StaticLayout.Builder
            .obtain(cleanNote, 0, cleanNote.length, textPaint, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(textSizePx * 0.14f, 1.0f)
            .setIncludePad(false)
            .build()

        var tightWidth = 0
        for (line in 0 until staticLayout.lineCount) {
            val lineW = ceil(staticLayout.getLineWidth(line).toDouble()).toInt()
            if (lineW > tightWidth) tightWidth = lineW
        }
        tightWidth = tightWidth.coerceIn(1, maxTextWidth)
        val textHeight = staticLayout.height.toFloat()

        val boxWidth = tightWidth + padH * 2f
        val boxHeight = textHeight + padV * 2f

        val boxLeft = when (corner) {
            WatermarkCorner.BOTTOM_LEFT, WatermarkCorner.TOP_LEFT -> edgeMargin
            WatermarkCorner.BOTTOM_RIGHT, WatermarkCorner.TOP_RIGHT -> width - edgeMargin - boxWidth
        }
        val boxTop = when (corner) {
            WatermarkCorner.TOP_LEFT, WatermarkCorner.TOP_RIGHT -> edgeMargin
            WatermarkCorner.BOTTOM_LEFT, WatermarkCorner.BOTTOM_RIGHT -> height - edgeMargin - boxHeight
        }

        val boxRect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(176, 16, 20, 26)
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (textSizePx * 0.05f).coerceAtLeast(1.5f)
            color = Color.argb(72, 255, 255, 255)
        }

        canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, borderPaint)

        canvas.save()
        canvas.translate(boxLeft + padH, boxTop + padV)
        staticLayout.draw(canvas)
        canvas.restore()

        return mutable
    }
}
