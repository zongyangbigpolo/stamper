package com.stamper.app.domain.stamper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the "project info card" watermark: a colored title bar with the project name and a
 * white body listing 拍摄时间 / 拍摄内容 / 地点, laid out like common construction-site
 * camera apps.
 */
object InfoCardRenderer {

    private const val LABEL_CHARS = 4
    private val TEXT_COLOR = Color.rgb(34, 34, 34)
    private val DOT_COLOR = Color.rgb(246, 196, 67)

    /** Returns a new bitmap; [source] is left untouched. */
    fun render(source: Bitmap, content: InfoCardContent, style: InfoCardStyle): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        if (content.title.isBlank() && content.rows.isEmpty()) return out
        drawOnto(Canvas(out), out.width, out.height, content, style)
        return out
    }

    fun drawOnto(canvas: Canvas, width: Int, height: Int, content: InfoCardContent, style: InfoCardStyle) {
        val shortEdge = min(width, height).toFloat()
        val scale = style.scale.coerceIn(0.6f, 1.6f)
        val unit = (shortEdge * 0.03f * scale).coerceIn(12f, 220f)
        val margin = shortEdge * 0.03f
        val cardWidth = min(unit * 17f, width - margin * 2f)
        if (cardWidth <= unit * 6f) return

        val radius = unit * 0.3f
        val titlePadH = unit * 1.3f
        val titlePadV = unit * 0.55f
        val bodyPadH = unit * 0.7f
        val bodyPadV = unit * 0.55f
        val rowGap = unit * 0.35f
        val labelWidth = unit * LABEL_CHARS
        val colonGap = unit * 0.9f

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit * 1.12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_COLOR
            textSize = unit
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val titleLayout = content.title.takeIf { it.isNotBlank() }?.let {
            buildLayout(it, titlePaint, (cardWidth - titlePadH * 2f).toInt(), Layout.Alignment.ALIGN_CENTER, unit * 0.12f)
        }
        val valueWidth = (cardWidth - bodyPadH * 2f - labelWidth - colonGap).toInt().coerceAtLeast(1)
        val rowLayouts = content.rows.map { row ->
            row to buildLayout(row.value, bodyPaint, valueWidth, Layout.Alignment.ALIGN_NORMAL, unit * 0.12f)
        }

        val titleHeight = titleLayout?.let { it.height + titlePadV * 2f } ?: 0f
        val bodyHeight = if (rowLayouts.isEmpty()) {
            0f
        } else {
            bodyPadV * 2f + rowLayouts.sumOf { it.second.height.toDouble() }.toFloat() +
                rowGap * (rowLayouts.size - 1)
        }
        val cardHeight = titleHeight + bodyHeight
        if (cardHeight > height - margin * 2f) return

        val left = if (style.position.isLeft) margin else width - margin - cardWidth
        val top = if (style.position.isTop) margin else height - margin - cardHeight
        val cardRect = RectF(left, top, left + cardWidth, top + cardHeight)

        val alpha = (style.opacity.coerceIn(0.3f, 1f) * 255f).roundToInt()
        val layer = canvas.saveLayerAlpha(cardRect, alpha)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(cardRect, radius, radius, Path.Direction.CW) })

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        if (titleLayout != null) {
            fill.color = style.headerColor.argb.toInt()
            canvas.drawRect(left, top, cardRect.right, top + titleHeight, fill)

            fill.color = DOT_COLOR
            canvas.drawCircle(left + titlePadH * 0.45f, top + titleHeight / 2f, unit * 0.3f, fill)

            canvas.save()
            canvas.translate(left + titlePadH, top + titlePadV)
            titleLayout.draw(canvas)
            canvas.restore()
        }

        if (rowLayouts.isNotEmpty()) {
            fill.color = Color.WHITE
            canvas.drawRect(left, top + titleHeight, cardRect.right, cardRect.bottom, fill)

            var y = top + titleHeight + bodyPadV
            val labelX = left + bodyPadH
            val valueX = labelX + labelWidth + colonGap
            for ((row, layout) in rowLayouts) {
                val baseline = y - bodyPaint.fontMetrics.ascent
                drawJustifiedLabel(canvas, row.label, labelX, baseline, labelWidth, bodyPaint)
                canvas.drawText("：", labelX + labelWidth, baseline, bodyPaint)
                canvas.save()
                canvas.translate(valueX, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height + rowGap
            }
        }

        canvas.restore()
        canvas.restoreToCount(layer)
    }

    /** Spreads the label's characters across [width] like "拍 摄 时 间" / "地      点". */
    private fun drawJustifiedLabel(canvas: Canvas, label: String, x: Float, baseline: Float, width: Float, paint: TextPaint) {
        val chars = label.map { it.toString() }
        if (chars.size <= 1) {
            canvas.drawText(label, x, baseline, paint)
            return
        }
        val widths = chars.map { paint.measureText(it) }
        val gap = max(0f, (width - widths.sum()) / (chars.size - 1))
        var cx = x
        chars.forEachIndexed { i, ch ->
            canvas.drawText(ch, cx, baseline, paint)
            cx += widths[i] + gap
        }
    }

    private fun buildLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment,
        spacingAdd: Float
    ): StaticLayout {
        val safeWidth = width.coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
            .setAlignment(alignment)
            .setLineSpacing(spacingAdd, 1f)
            .setIncludePad(false)
            .setMaxLines(4)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
    }
}
