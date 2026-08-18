package com.example.yaesucatcontroladv.deepcw.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Port fidel al `src/utils/audioFilters.ts` din proiectul de referinta. */
object AudioFilters {

    fun applyBandpassFilter(
        audioData: FloatArray,
        sampleRate: Float,
        centerFreq: Float,
        bandwidth: Float,
        passes: Int = 4
    ): FloatArray {
        if (audioData.isEmpty() || bandwidth <= 0f || centerFreq <= 0f) return audioData

        val q = centerFreq / bandwidth
        val omega = (2.0 * PI * centerFreq / sampleRate)
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2.0 * q)

        val b0 = alpha
        val b1 = 0.0
        val b2 = -alpha
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosOmega
        val a2 = 1.0 - alpha

        val normB0 = (b0 / a0).toFloat()
        val normB1 = (b1 / a0).toFloat()
        val normB2 = (b2 / a0).toFloat()
        val normA1 = (a1 / a0).toFloat()
        val normA2 = (a2 / a0).toFloat()

        var input = audioData
        var output = FloatArray(audioData.size)

        for (p in 0 until passes) {
            var x1 = 0f
            var x2 = 0f
            var y1 = 0f
            var y2 = 0f

            for (i in input.indices) {
                val x0 = input[i]
                val y0 = normB0 * x0 + normB1 * x1 + normB2 * x2 - normA1 * y1 - normA2 * y2
                output[i] = y0
                x2 = x1
                x1 = x0
                y2 = y1
                y1 = y0
            }

            input = output
            if (p < passes - 1) {
                output = FloatArray(audioData.size)
            }
        }

        return output
    }
}
