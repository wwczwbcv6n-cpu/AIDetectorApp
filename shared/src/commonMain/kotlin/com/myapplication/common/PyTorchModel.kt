package com.myapplication.common

/**
 * Thrown when the on-device model fails to load or run (#6).
 *
 * The implementations used to swallow asset-extraction / forward() failures and
 * return an empty FloatArray, which downstream code could mistake for a valid
 * (but meaningless) prediction. Surfacing a typed exception lets the caller fall
 * back deliberately (e.g. to the server or heuristic) instead of acting on junk.
 */
class PyTorchInferenceException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

expect class PyTorchModel(context: Any) {
    /** Load a TorchScript model from the given asset/bundle name.
     *  @throws PyTorchInferenceException if the asset can't be found or loaded. */
    fun loadModel(assetName: String)

    /** Run a forward pass.
     *  @throws PyTorchInferenceException if no model is loaded or the forward fails. */
    fun predict(input: FloatArray, inputShape: LongArray): FloatArray
}
