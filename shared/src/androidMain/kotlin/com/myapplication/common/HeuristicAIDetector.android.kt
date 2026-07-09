package com.myapplication.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeImage(bytes: ByteArray, maxSide: Int): DecodedImage {
    // Bounds-only pass first, then decode subsampled. Decoding a modern
    // 48–200 MP photo at full resolution allocates a 200 MB+ bitmap just to
    // immediately downscale it — an OOM crash on mid-range devices. With
    // inSampleSize the decoder never materialises more than ~4x the target.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IllegalArgumentException("Failed to decode image")
    }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) {
        sample *= 2
    }

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        ?: throw IllegalArgumentException("Failed to decode image")
    val scale = maxSide.toFloat() / max(raw.width, raw.height)
    val w = (raw.width * scale).toInt().coerceAtLeast(1)
    val h = (raw.height * scale).toInt().coerceAtLeast(1)
    val scaled = if (scale < 1f) {
        val s = Bitmap.createScaledBitmap(raw, w, h, true)
        if (s !== raw) raw.recycle()
        s
    } else raw
    val pixels = IntArray(scaled.width * scaled.height)
    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    return DecodedImage(
        pixels = pixels,
        width = scaled.width,
        height = scaled.height,
        composeImage = scaled.asImageBitmap()
    )
}

private fun max(a: Int, b: Int): Int = if (a > b) a else b
