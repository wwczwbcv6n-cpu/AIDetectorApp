package com.myapplication.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual class ImagePickerFactory {
    @Composable
    actual fun createPicker(): ImagePicker {
        return remember { DesktopImagePicker() }
    }
}

private class DesktopImagePicker : ImagePicker {
    override fun pickImage(
        title: String?,
        maxSelection: Int,
        onImagePicked: (List<ByteArray>) -> Unit
    ) {
        val dialog = FileDialog(null as Frame?, title ?: "Select image", FileDialog.LOAD).apply {
            isMultipleMode = maxSelection > 1
            setFilenameFilter { _, name ->
                val n = name.lowercase()
                n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                n.endsWith(".png") || n.endsWith(".webp") ||
                n.endsWith(".heic") || n.endsWith(".heif")
            }
            isVisible = true
        }
        val files: Array<File> = dialog.files ?: emptyArray()
        if (files.isEmpty()) {
            onImagePicked(emptyList())
            return
        }
        val limit = if (maxSelection > 0) maxSelection else files.size
        val payload = files.take(limit).mapNotNull { f ->
            try {
                f.readBytes()
            } catch (e: Exception) {
                Logger.warn("Failed to read selected file ${f.name}: ${e.message}")
                null
            }
        }
        onImagePicked(payload)
    }
}
