package com.myapplication.common

import android.content.Context
import org.pytorch.Module
import org.pytorch.IValue
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

actual class PyTorchModel actual constructor(private val context: Any) {
    private var module: Module? = null

    actual fun loadModel(assetName: String) {
        try {
            val androidContext = context as Context
            module = Module.load(assetFilePath(androidContext, assetName))
        } catch (e: Exception) {
            // Surface instead of leaving `module` null and failing silently at predict() (#6).
            throw PyTorchInferenceException("Failed to load model asset '$assetName'", e)
        }
    }

    actual fun predict(input: FloatArray, inputShape: LongArray): FloatArray {
        val mod = module
            ?: throw PyTorchInferenceException("predict() called before a model was loaded")
        return try {
            val inputTensor = org.pytorch.Tensor.fromBlob(input, inputShape)
            val outputTensor = mod.forward(IValue.from(inputTensor)).toTensor()
            // An empty output is itself a failure, not a valid prediction.
            outputTensor.dataAsFloatArray.also {
                if (it.isEmpty()) throw PyTorchInferenceException("Model returned an empty output tensor")
            }
        } catch (e: PyTorchInferenceException) {
            throw e
        } catch (e: Exception) {
            throw PyTorchInferenceException("Model forward pass failed", e)
        }
    }

    private fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }

        context.assets.open(assetName).use { `is` ->
            FileOutputStream(file).use { os ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (`is`.read(buffer).also { read = it } != -1) {
                    os.write(buffer, 0, read)
                }
                os.flush()
            }
            return file.absolutePath
        }
    }
}