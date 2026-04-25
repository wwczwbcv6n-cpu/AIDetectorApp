package com.myapplication.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeImage(bytes: ByteArray, maxSide: Int): DecodedImage {
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IllegalArgumentException("Failed to decode image")
    val scale = maxSide.toFloat() / max(raw.width, raw.height)
    val w = (raw.width * scale).toInt().coerceAtLeast(1)
    val h = (raw.height * scale).toInt().coerceAtLeast(1)
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(raw, w, h, true) else raw
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
