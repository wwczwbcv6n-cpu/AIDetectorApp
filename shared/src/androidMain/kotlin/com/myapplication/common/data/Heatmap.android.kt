package com.myapplication.common.data

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android decode: Jetpack Compose has no skia backing, so go through the
 * platform BitmapFactory and adapt to a Compose ImageBitmap.
 */
actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return bmp.asImageBitmap()
}
