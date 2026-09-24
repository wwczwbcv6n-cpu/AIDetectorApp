package com.myapplication.common

import androidx.compose.runtime.Composable

expect class ImagePickerFactory {
    @Composable
    fun createPicker(): ImagePicker
}

interface ImagePicker {
    fun pickImage(
        title: String? = null,
        maxSelection: Int = 1,
        onImagePicked: (List<ByteArray>) -> Unit
    )

    /**
     * Pick ONE image for analysis. Platforms that can (Android) check the
     * size before reading, read off the main thread and report the file's
     * display name; the default wraps [pickImage] and re-checks the size
     * after reading (audit 2026-09-24 APP-07).
     */
    fun pickImageForAnalysis(onPicked: (PickedImage) -> Unit) {
        pickImage(null, 1) { list ->
            val bytes = list.firstOrNull() ?: return@pickImage onPicked(PickedImage.Cancelled)
            val tooLarge = com.myapplication.common.data.uploadTooLargeMessage(bytes.size.toLong())
            onPicked(if (tooLarge != null) PickedImage.Refused(tooLarge) else PickedImage.Picked(bytes, null))
        }
    }
}

/** Outcome of [ImagePicker.pickImageForAnalysis]. */
sealed class PickedImage {
    class Picked(val bytes: ByteArray, val displayName: String?) : PickedImage()
    /** Not read or not sendable; [message] is shown to the user. */
    class Refused(val message: String) : PickedImage()
    object Cancelled : PickedImage()
}