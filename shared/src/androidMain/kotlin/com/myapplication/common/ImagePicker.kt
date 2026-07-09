package com.myapplication.common

import android.content.Context
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Holds the pending pick callback across recompositions.
 *
 * The previous implementation kept it in a local `var` initialised with
 * `remember { null }` — every recomposition reset that var to null while the
 * remembered picker still wrote to the FIRST composition's copy, so after any
 * recomposition a completed pick was silently dropped (picker "did nothing").
 * A single remembered holder object gives both sides one stable reference.
 */
internal class PickCallbackHolder {
    var callback: ((List<ByteArray>) -> Unit)? = null
    fun consume(): ((List<ByteArray>) -> Unit)? = callback.also { callback = null }
}

actual class ImagePickerFactory {
    @Composable
    actual fun createPicker(): ImagePicker {
        val context = LocalContext.current
        val holder = remember { PickCallbackHolder() }

        val imagePickerLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.GetMultipleContents()
        ) { uris ->
            val images = uris.mapNotNull { uri ->
                try {
                    // Read the ORIGINAL bytes — no decode/re-encode. The old
                    // decode→JPEG-90 roundtrip stripped EXIF/C2PA metadata
                    // (which the server's provenance fast-path reads) and
                    // rewrote the compression traces the forensic branches
                    // analyze; it also full-res-decoded huge photos (OOM risk).
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    Logger.warn("ImagePicker: failed to read picked uri: ${e.message}")
                    null
                }
            }
            holder.consume()?.invoke(images)
        }

        return remember(context, imagePickerLauncher) {
            AndroidImagePicker(imagePickerLauncher, holder)
        }
    }
}

internal class AndroidImagePicker(
    private val imagePickerLauncher: ManagedActivityResultLauncher<String, List<android.net.Uri>>,
    private val holder: PickCallbackHolder,
) : ImagePicker {
    override fun pickImage(
        title: String?,
        maxSelection: Int,
        onImagePicked: (List<ByteArray>) -> Unit
    ) {
        holder.callback = onImagePicked
        imagePickerLauncher.launch("image/*")
    }
}
