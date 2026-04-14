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
}