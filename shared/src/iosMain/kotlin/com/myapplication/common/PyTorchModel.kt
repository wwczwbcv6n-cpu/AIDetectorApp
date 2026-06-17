package com.myapplication.common

import de.voize.pytorch_lite_multiplatform.PyTorchLiteModule
import de.voize.pytorch_lite_multiplatform.plmScoped
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.stringWithFormat

actual class PyTorchModel actual constructor(private val context: Any) { // context can be ignored for iOS or used for bundle
    private var module: PyTorchLiteModule? = null

    actual fun loadModel(assetName: String) {
        // For iOS, the model is typically bundled in the app's main bundle.
        val path = NSBundle.mainBundle.pathForResource(
            assetName.substringBefore("."),
            assetName.substringAfter("."),
        ) ?: throw PyTorchInferenceException(
            "Model asset '$assetName' not found in main bundle"
        )
        module = PyTorchLiteModule(path)
    }

    actual fun predict(input: FloatArray, inputShape: LongArray): FloatArray {
        val mod = module
            ?: throw PyTorchInferenceException("predict() called before a model was loaded")
        return plmScoped {
            val output = mod.forward(input, inputShape)
                ?: throw PyTorchInferenceException("Model forward pass returned no output")
            if (output.isEmpty()) {
                throw PyTorchInferenceException("Model returned an empty output")
            }
            output
        }
    }
}