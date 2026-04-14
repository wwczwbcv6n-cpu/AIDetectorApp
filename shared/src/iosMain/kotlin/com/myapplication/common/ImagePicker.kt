package com.myapplication.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UIKit.UIImage
import platform.UIKit.NSData
import platform.Foundation.NSURL
import platform.Foundation.NSError
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.dataWithContentsOfURL_options_error
import platform.darwin.NSObject
import platform.UIKit.UIApplication
import platform.UIKit.UIPopoverPresentationController
import platform.UIKit.UIView
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIImageJPEGRepresentation
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toCValues
import platform.Foundation.NSNumber
import platform.Foundation.dictionaryWithValuesForKeys
import platform.Foundation.valueForKey

actual class ImagePickerFactory {
    @Composable
    actual fun createPicker(): ImagePicker {
        return remember {
            IOSImagePicker()
        }
    }
}

class IOSImagePicker : ImagePicker {

    private var onImagePickedCallback: ((List<ByteArray>) -> Unit)? = null

    @OptIn(ExperimentalForeignApi::class)
    override fun pickImage(
        title: String?,
        maxSelection: Int,
        onImagePicked: (List<ByteArray>) -> Unit
    ) {
        onImagePickedCallback = onImagePicked
        val imagePickerController = UIImagePickerController()
        imagePickerController.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
        imagePickerController.delegate = object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
            override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, Any>) {
                val image = didFinishPickingMediaWithInfo.valueForKey(UIImagePickerControllerOriginalImage) as? UIImage
                if (image != null) {
                    val imageData = UIImageJPEGRepresentation(image, 0.9) // Compress to JPEG with 90% quality
                    if (imageData != null) {
                        onImagePickedCallback?.invoke(listOf(imageData.toByteArray()))
                    } else {
                        onImagePickedCallback?.invoke(emptyList())
                    }
                } else {
                    onImagePickedCallback?.invoke(emptyList())
                }
                picker.dismissViewControllerAnimated(true, null)
            }

            override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
                onImagePickedCallback?.invoke(emptyList())
                picker.dismissViewControllerAnimated(true, null)
            }
        }

        UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(imagePickerController, true, null)
    }
}

// Extension function to convert NSData to Kotlin ByteArray
@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray = memScoped {
    ByteArray(length.toInt()).apply {
        this.usePinned {
            memcpy(it.addressOf(0), this@toByteArray.bytes, this@toByteArray.length)
        }
    }
}