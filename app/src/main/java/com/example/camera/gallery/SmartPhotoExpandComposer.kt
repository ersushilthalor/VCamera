package com.example.camera.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min

/**
 * Creates an expanded-FOV composite: the 0.5x frame supplies the outer scene while
 * the 1x frame replaces its central field. This is a local geometric blend, not a
 * generative-AI model; capture pairs are retained unchanged as the originals.
 */
object SmartPhotoExpandComposer {
    fun compose(
        mainOneX: Bitmap,
        ultraWide: Bitmap,
        maxOutputDimension: Int = 4096
    ): Bitmap? {
        if (mainOneX.isRecycled || ultraWide.isRecycled ||
            mainOneX.width <= 0 || mainOneX.height <= 0 ||
            ultraWide.width <= 0 || ultraWide.height <= 0
        ) return null

        val scale = min(2f, maxOutputDimension.toFloat() /
            maxOf(ultraWide.width, ultraWide.height).toFloat()).coerceAtLeast(0.1f)
        val outputWidth = (ultraWide.width * scale).toInt().coerceAtLeast(1)
        val outputHeight = (ultraWide.height * scale).toInt().coerceAtLeast(1)
        val output = try {
            Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }

        val canvas = Canvas(output)
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            ultraWide,
            null,
            Rect(0, 0, outputWidth, outputHeight),
            basePaint
        )

        // 1x framing covers approximately the centre half of a 0.5x frame in both axes.
        val centerWidth = outputWidth * 0.5f
        val centerHeight = outputHeight * 0.5f
        val target = RectF(
            (outputWidth - centerWidth) * 0.5f,
            (outputHeight - centerHeight) * 0.5f,
            (outputWidth + centerWidth) * 0.5f,
            (outputHeight + centerHeight) * 0.5f
        )

        val centerLayer = try {
            Bitmap.createBitmap(mainOneX.width, mainOneX.height, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            output.recycle()
            return null
        }
        try {
            val sourceCanvas = Canvas(centerLayer)
            sourceCanvas.drawBitmap(mainOneX, 0f, 0f, basePaint)
            applyFeatherAlpha(centerLayer, (min(mainOneX.width, mainOneX.height) * 0.035f).toInt().coerceAtLeast(2))
            canvas.drawBitmap(centerLayer, null, target, basePaint)
        } finally {
            centerLayer.recycle()
        }
        return output
    }

    private fun applyFeatherAlpha(bitmap: Bitmap, featherWidth: Int) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (y in 0 until height) {
            val verticalDistance = min(y, height - 1 - y)
            for (x in 0 until width) {
                val edgeDistance = min(min(x, width - 1 - x), verticalDistance)
                val alpha = (edgeDistance.toFloat() / featherWidth.toFloat()).coerceIn(0f, 1f)
                val index = y * width + x
                pixels[index] = (pixels[index] and 0x00FFFFFF) or
                    ((alpha * 255f).toInt() shl 24)
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
