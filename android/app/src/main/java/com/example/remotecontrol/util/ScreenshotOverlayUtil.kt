package com.example.remotecontrol.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

object ScreenshotOverlayUtil {
    fun addCircleOverlay(pngBytes: ByteArray, x: Float, y: Float, radius: Float = 40f): ByteArray? {
        val options = BitmapFactory.Options().apply { inMutable = true }
        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, options) ?: return null
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.argb(128, 255, 0, 0)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, radius, paint)
        val result = ScreenshotUtil.bitmapToPng(bitmap)
        bitmap.recycle()
        return result
    }

    fun addRulers(pngBytes: ByteArray): ByteArray? {
        val original = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size) ?: return null

        val topH = 28
        val rightW = 56
        val segment = 100
        val gridInterval = 200
        val origW = original.width
        val origH = original.height

        val bitmap = Bitmap.createBitmap(origW + rightW, origH + topH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw original image offset by ruler area
        canvas.drawBitmap(original, 0f, topH.toFloat(), null)
        original.recycle()

        // Grid lines across the screen (every 200px, semi-transparent)
        val gridPaint = Paint().apply {
            color = Color.argb(40, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        // Horizontal grid lines (Y axis)
        for (y in gridInterval..origH step gridInterval) {
            canvas.drawLine(0f, topH + y.toFloat(), origW.toFloat(), topH + y.toFloat(), gridPaint)
        }
        // Vertical grid lines (X axis)
        for (x in gridInterval..origW step gridInterval) {
            canvas.drawLine(x.toFloat(), topH.toFloat(), x.toFloat(), (topH + origH).toFloat(), gridPaint)
        }

        val bgPaints = arrayOf(
            Paint().apply { color = Color.rgb(220, 50, 50); style = Paint.Style.FILL },
            Paint().apply { color = Color.rgb(50, 100, 220); style = Paint.Style.FILL }
        )
        val textPaint = Paint().apply {
            color = Color.WHITE; textSize = 18f; isAntiAlias = true
            textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE
        }

        // Top ruler (X axis)
        for (i in 0..(origW / segment)) {
            val x0 = (i * segment).toFloat()
            val x1 = minOf(((i + 1) * segment).toFloat(), origW.toFloat())
            canvas.drawRect(x0, 0f, x1, topH.toFloat(), bgPaints[i % 2])
            val label = (i * segment).toString()
            canvas.drawText(label, x0 + (x1 - x0) / 2, topH / 2f - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        }

        // Right ruler (Y axis) - offset by topH to align with image
        for (i in 0..(origH / segment)) {
            val y0 = topH + (i * segment).toFloat()
            val y1 = minOf(topH + ((i + 1) * segment).toFloat(), (origH + topH).toFloat())
            canvas.drawRect(origW.toFloat(), y0, (origW + rightW).toFloat(), y1, bgPaints[i % 2])
            val label = (i * segment).toString()
            canvas.drawText(label, origW + rightW / 2f, y0 + (y1 - y0) / 2 - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        }

        // Corner fill
        canvas.drawRect(origW.toFloat(), 0f, (origW + rightW).toFloat(), topH.toFloat(), bgPaints[0])

        val result = ScreenshotUtil.bitmapToPng(bitmap)
        bitmap.recycle()
        return result
    }
}
