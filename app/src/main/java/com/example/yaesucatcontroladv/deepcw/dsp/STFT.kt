package com.example.yaesucatcontroladv.deepcw.dsp

import kotlin.math.*

class STFT(val fftSize: Int, val hopSize: Int) {
    private val fft = FFT(fftSize)
    private val window = generateHannWindow()
    private val frame = FloatArray(fftSize)
    private val complexFrame = FloatArray(fftSize * 2)

    init {
        require(fftSize > 0) { "FFT size must be positive." }
        require(hopSize > 0) { "Hop size must be positive." }
    }

    private fun generateHannWindow(): FloatArray {
        val w = FloatArray(fftSize)
        for (i in 0 until fftSize) {
            w[i] = (0.5 * (1.0 - cos((2.0 * PI * i) / fftSize))).toFloat()
        }
        return w
    }

    fun getFrameCount(signalLength: Int): Int {
        if (signalLength < fftSize) return 0
        return (signalLength - fftSize) / hopSize + 1
    }

    fun forEachSpectrum(
        signal: FloatArray,
        iteratee: (complexFrame: FloatArray, frameIndex: Int) -> Unit
    ): Int {
        val frameCount = getFrameCount(signal.size)
        if (frameCount == 0) return 0

        var frameIndex = 0
        var i = 0
        while (i + fftSize <= signal.size) {
            // Copy signal slice and apply window
            for (j in 0 until fftSize) {
                frame[j] = signal[i + j] * window[j]
            }

            // Prepare complex frame
            for (j in 0 until fftSize) {
                complexFrame[j * 2] = frame[j]
                complexFrame[j * 2 + 1] = 0f
            }

            fft.transform(complexFrame)
            iteratee(complexFrame, frameIndex)
            frameIndex++
            i += hopSize
        }

        return frameIndex
    }
}
