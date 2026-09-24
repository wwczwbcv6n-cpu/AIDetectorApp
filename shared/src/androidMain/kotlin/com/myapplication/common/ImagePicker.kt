package com.myapplication.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.myapplication.common.data.UPLOAD_MAX_BYTES
import com.myapplication.common.data.uploadTooLargeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var callback: ((PickedImage) -> Unit)? = null
    fun consume(): ((PickedImage) -> Unit)? = callback.also { callback = null }
}

/**
 * Reads ONE picked image for analysis (audit 2026-09-24 APP-07). The old
 * picker was GetMultipleContents and did readBytes() on EVERY selected uri
 * inside the ActivityResult callback — on the main thread (a cloud-only
 * Google Photos original downloads there: ANR) and all at once (OOM) — then
 * silently analyzed only the first. Now: single selection; the size from
 * OpenableColumns is checked BEFORE reading; the read runs on Dispatchers.IO
 * and stops past the cap for providers that report no size; the display
 * name comes back for history.
 */
fun readPickedImage(context: Context, uri: Uri): PickedImage {
    var name: String? = null
    var size = -1L
    try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
    } catch (e: Exception) {
        Logger.warn("ImagePicker: metadata query failed: ${e::class.simpleName}")
    }
    uploadTooLargeMessage(size)?.let { return PickedImage.Refused(it) }
    return try {
        // Read the ORIGINAL bytes — no decode/re-encode (a JPEG-90 roundtrip
        // stripped EXIF/C2PA and rewrote the compression traces).
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > UPLOAD_MAX_BYTES) {
                    return PickedImage.Refused(uploadTooLargeMessage(total)!!)
                }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return PickedImage.Refused("Couldn't read the selected image.")
        PickedImage.Picked(bytes, name)
    } catch (e: Exception) {
        Logger.warn("ImagePicker: failed to read picked uri: ${e::class.simpleName}")
        PickedImage.Refused("Couldn't read the selected image.")
    }
}

actual class ImagePickerFactory {
    @Composable
    actual fun createPicker(): ImagePicker {
        val context = LocalContext.current
        val holder = remember { PickCallbackHolder() }
        val scope = rememberCoroutineScope()

        val imagePickerLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            val callback = holder.consume() ?: return@rememberLauncherForActivityResult
            if (uri == null) {
                callback(PickedImage.Cancelled)
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                val picked = withContext(Dispatchers.IO) { readPickedImage(context, uri) }
                callback(picked)
            }
        }

        return remember(context, imagePickerLauncher) {
            AndroidImagePicker(imagePickerLauncher, holder)
        }
    }
}

internal class AndroidImagePicker(
    private val imagePickerLauncher: ManagedActivityResultLauncher<String, Uri?>,
    private val holder: PickCallbackHolder,
) : ImagePicker {
    override fun pickImageForAnalysis(onPicked: (PickedImage) -> Unit) {
        holder.callback = onPicked
        imagePickerLauncher.launch("image/*")
    }

    /** Legacy multi-callback API: one image at most, same size-checked read. */
    override fun pickImage(
        title: String?,
        maxSelection: Int,
        onImagePicked: (List<ByteArray>) -> Unit
    ) {
        pickImageForAnalysis { picked ->
            onImagePicked(if (picked is PickedImage.Picked) listOf(picked.bytes) else emptyList())
        }
    }
}
