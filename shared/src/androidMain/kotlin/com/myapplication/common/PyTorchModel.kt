package com.myapplication.common

/**
 * Android stub for the LibTorch-Mobile-backed PyTorchModel.
 *
 * Nothing in the app ever called loadModel()/predict(), yet the org.pytorch
 * dependency shipped ~300 MB of native libs (4 ABIs) plus a 74 MB bundled
 * .ptl asset — ~95% of the APK for a code path that never ran. The Android
 * build now mirrors the desktop stub: analysis goes to the server, with the
 * HeuristicAIDetector as the offline fallback. When the on-device DINOv2
 * (sprint 2) lands it will use ONNX Runtime / LiteRT per the deployment
 * plan, not LibTorch — so this class stays a stub until then.
 */
actual class PyTorchModel actual constructor(context: Any) {
    init {
        Logger.debug("PyTorchModel android stub created (server + heuristic paths used)")
    }

    actual fun loadModel(assetName: String) {
        Logger.debug("PyTorchModel.loadModel ignored on android ($assetName — no bundled model)")
    }

    actual fun predict(input: FloatArray, inputShape: LongArray): FloatArray {
        return floatArrayOf(0.5f)
    }
}
