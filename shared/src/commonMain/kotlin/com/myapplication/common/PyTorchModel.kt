package com.myapplication.common

expect class PyTorchModel(context: Any) {
    fun loadModel(assetName: String)
    fun predict(input: FloatArray, inputShape: LongArray): FloatArray
}