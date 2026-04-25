package com.myapplication.common

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.max as kmax

actual fun decodeImage(bytes: ByteArray, maxSide: Int): DecodedImage {
    val image = Image.makeFromEncoded(bytes)
    val origW = image.width
    val origH = image.height
    val scale = maxSide.toFloat() / kmax(origW, origH)
    val w = if (scale < 1f) (origW * scale).toInt().coerceAtLeast(1) else origW
    val h = if (scale < 1f) (origH * scale).toInt().coerceAtLeast(1) else origH

    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo.makeN32Premul(w, h))
    image.scalePixels(bitmap.peekPixels()!!, org.jetbrains.skia.SamplingMode.LINEAR, false)

    val pixels = IntArray(w * h)
    val raw = bitmap.readPixels(bitmap.imageInfo, w * 4, 0, 0)
        ?: throw IllegalStateException("Failed to read decoded pixels")
    for (i in 0 until w * h) {
        val o = i * 4
        val rr = raw[o].toInt() and 0xFF
        val gg = raw[o + 1].toInt() and 0xFF
        val bb = raw[o + 2].toInt() and 0xFF
        val aa = raw[o + 3].toInt() and 0xFF
        pixels[i] = (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
    return DecodedImage(
        pixels = pixels,
        width = w,
        height = h,
        composeImage = bitmap.toComposeImageBitmap()
    )
}
