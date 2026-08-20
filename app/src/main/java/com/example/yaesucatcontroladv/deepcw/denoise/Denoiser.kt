package com.example.yaesucatcontroladv.deepcw.denoise

/** Interfata comuna pentru wrapper-ele de denoising HamNoise (CW si voce). */
interface Denoiser {
    val isAvailable: Boolean
    fun initialize(): Boolean
    fun process(chunk: FloatArray, wet: Float = 1.0f): FloatArray
    fun reset()
    fun release()
}
