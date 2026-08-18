package com.example.yaesucatcontroladv.deepcw.data

import android.content.Context
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_FILTER_WIDTH_HZ
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_MATCH_HZ
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_MAX_PEAKS
import com.example.yaesucatcontroladv.deepcw.dsp.SpectrogramGenerator
import kotlin.math.abs
import kotlin.math.max

data class DetectedStation(override val frequency: Float, override val snrDb: Float) :
    PileupCandidate

/**
 * Model de detectie CW pe fiecare bin de frecventa.
 * Port fidel al `handleDetectCwBins` din `src/workers/inferenceWorker.ts`.
 */
class CwDetector(context: Context) {
    private val inferenceWrapper = InferenceWrapper(context)
    private val spectrogramGenerator = SpectrogramGenerator()

    val isAvailable: Boolean
        get() = inferenceWrapper.isAvailable

    fun initialize(): String? = inferenceWrapper.loadModel(MODEL_FILE)

    fun detectStations(
        audio: FloatArray,
        minFreqHz: Float,
        maxFreqHz: Float,
        lockSnrThresholdDb: Float,
        releaseSnrThresholdDb: Float,
        lockedFrequencies: List<Float> = emptyList()
    ): List<DetectedStation> {
        if (!isAvailable) return emptyList()

        val binSequence = spectrogramGenerator.generateBinSequence(audio, minFreqHz, maxFreqHz)
            ?: return emptyList()

        val result = inferenceWrapper.runInference(binSequence.data, binSequence.dims)
            ?: return emptyList()

        val snrValues = result.first
        val frequencies = binSequence.frequencies ?: return emptyList()

        val scoredBins = frequencies.mapIndexed { index, freq ->
            DetectedStation(freq, snrValues.getOrElse(index) { 0f })
        }.sortedByDescending { it.snrDb }

        // 1. Pastreaza stațiile deja urmarite, cat timp sunt peste pragul de release.
        val retained = mutableListOf<DetectedStation>()
        val retainedFrequencies = mutableSetOf<Float>()

        for (lockedFrequency in lockedFrequencies) {
            val candidate = scoredBins.firstOrNull {
                !retainedFrequencies.contains(it.frequency) &&
                    abs(it.frequency - lockedFrequency) <= PILEUP_MATCH_HZ &&
                    it.snrDb >= releaseSnrThresholdDb
            } ?: continue

            retained.add(candidate)
            retainedFrequencies.add(candidate.frequency)
        }

        retained.sortByDescending { it.snrDb }

        // 2. Adauga stații noi, peste pragul de lock si suficient de separate.
        val aboveLock = scoredBins.filter { it.snrDb >= lockSnrThresholdDb }
        val newCandidates = PileupCandidates.selectStrongSeparated(
            aboveLock.filter { candidate ->
                retained.all { abs(it.frequency - candidate.frequency) >= PILEUP_FILTER_WIDTH_HZ }
            },
            max(0, PILEUP_MAX_PEAKS - retained.size),
            PILEUP_FILTER_WIDTH_HZ
        )

        return retained + newCandidates
    }

    fun release() {
        inferenceWrapper.close()
    }

    companion object {
        const val MODEL_FILE = "model_detect.onnx"
    }
}
