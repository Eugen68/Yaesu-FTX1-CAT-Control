package com.example.yaesucatcontroladv.deepcw.dsp

import com.example.yaesucatcontroladv.deepcw.dsp.Constants.BIN_RESOLUTION
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.FFT_LENGTH
import kotlin.math.log10

data class Peak(val bin: Int, val frequency: Float, val magnitude: Float)

object PeakDetector {
    fun findTopPeaks(
        spectrum: FloatArray,
        startBin: Int,
        endBin: Int,
        minDistanceBins: Int = 5,
        maxPeaks: Int = 2
    ): List<Peak> {
        val peaks = mutableListOf<Peak>()

        for (i in startBin until endBin) {
            val valCenter = spectrum[i]
            val valLeft = if (i > 0) spectrum[i - 1] else 0f
            val valRight = if (i < spectrum.size - 1) spectrum[i + 1] else 0f

            if (valCenter > valLeft && valCenter > valRight) {
                peaks.add(Peak(i, i * BIN_RESOLUTION, valCenter))
            }
        }

        peaks.sortByDescending { it.magnitude }

        val result = mutableListOf<Peak>()
        for (peak in peaks) {
            if (result.size >= maxPeaks) break

            val isTooClose = result.any { Math.abs(it.bin - peak.bin) < minDistanceBins }
            if (!isTooClose) {
                result.add(peak)
            }
        }

        return result
    }
}
