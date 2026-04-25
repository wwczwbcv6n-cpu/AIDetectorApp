package com.myapplication.common

import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.SamplingMode
import kotlin.math.max

actual fun decodeImage(bytes: ByteArray, maxSide: Int): DecodedImage {
    val image = Image.makeFromEncoded(bytes)
    val scale = maxSide.toFloat() / max(image.width, image.height)
    val w = if (scale < 1f) (image.width * scale).toInt().coerceAtLeast(1) else image.width
    val h = if (scale < 1f) (image.height * scale).toInt().coerceAtLeast(1) else image.height

    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo.makeN32Premul(w, h))
    image.scalePixels(bitmap.peekPixels()!!, SamplingMode.LINEAR, false)

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
