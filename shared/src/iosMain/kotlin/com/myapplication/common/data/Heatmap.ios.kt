package com.myapplication.common.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/** iOS (skiko) decode. */
actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
