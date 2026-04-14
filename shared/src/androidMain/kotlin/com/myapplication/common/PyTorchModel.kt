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
        val androidContext = context as Context
        module = Module.load(assetFilePath(androidContext, assetName))
    }

    actual fun predict(input: FloatArray, inputShape: LongArray): FloatArray {
        val inputTensor = org.pytorch.Tensor.fromBlob(input, inputShape)
        val outputTensor = module?.forward(IValue.from(inputTensor))?.toTensor()
        return outputTensor?.dataAsFloatArray ?: floatArrayOf()
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