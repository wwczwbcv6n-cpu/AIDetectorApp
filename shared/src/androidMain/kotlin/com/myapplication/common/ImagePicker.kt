package com.myapplication.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream

actual class ImagePickerFactory {
    @Composable
    actual fun createPicker(): ImagePicker {
        val context = LocalContext.current
        var onImagePickedCallback: ((List<ByteArray>) -> Unit)? = remember { null }

        val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            val images = uris.mapNotNull { uri ->
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        val byteArrayOutputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream)
                        byteArrayOutputStream.toByteArray()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            onImagePickedCallback?.invoke(images)
            onImagePickedCallback = null // Clear callback after use
        }

        return remember(context, imagePickerLauncher) {
            AndroidImagePicker(context, imagePickerLauncher) { callback ->
                onImagePickedCallback = callback
            }
        }
    }
}

class AndroidImagePicker(
    private val context: Context,
    private val imagePickerLauncher: ManagedActivityResultLauncher<String, List<android.net.Uri>>,
    private val setOnImagePickedCallback: (((List<ByteArray>) -> Unit)?) -> Unit
) : ImagePicker {
    override fun pickImage(
        title: String?,
        maxSelection: Int,
        onImagePicked: (List<ByteArray>) -> Unit
    ) {
        setOnImagePickedCallback(onImagePicked)
        // For GetMultipleContents, maxSelection is not directly supported,
        // it allows picking multiple. We'll handle the actual limit
        // in the callback if needed, but for simplicity, we'll return all selected.
        imagePickerLauncher.launch("image/*")
    }
}
