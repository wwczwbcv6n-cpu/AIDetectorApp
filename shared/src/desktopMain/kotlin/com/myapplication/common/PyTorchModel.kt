package com.myapplication.common

/**
 * Desktop stub for the LibTorch-Mobile-backed PyTorchModel.
 *
 * The desktop target ships without a bundled .pt model and routes all
 * analysis through the on-device HeuristicAIDetector (no model file needed).
 * predict() returns a single 0.5 score so AIDetector's "API failed → local
 * fallback" path takes over cleanly. Replace with a JTorch / DJL backend
 * if/when desktop gets a bundled checkpoint.
 */
actual class PyTorchModel actual constructor(context: Any) {
    init {
        Logger.debug("PyTorchModel desktop stub created (heuristic path will be used)")
    }

    actual fun loadModel(assetName: String) {
        Logger.debug("PyTorchModel.loadModel ignored on desktop ($assetName)")
    }

    actual fun predict(input: FloatArray, inputShape: LongArray): FloatArray {
        return floatArrayOf(0.5f)
    }
}
