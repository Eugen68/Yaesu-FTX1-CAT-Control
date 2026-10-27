package com.example.yaesucatcontroladv.deepcw.data

import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_FILTER_WIDTH_HZ
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_MATCH_HZ
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_MAX_PEAKS
import com.example.yaesucatcontroladv.deepcw.dsp.SpectrogramGenerator
import kotlin.math.abs
import kotlin.math.max

data class DetectedStation(override val frequency: Float, override val snrDb: Float) :
    PileupCandidate

/**
 * Gaseste statiile CW din banda pentru modul pileup. Port fidel al `handleDetectCwBins` din
 * `src/workers/inferenceWorker.ts` (versiunea e04 din 2026-10-27): scorul pe bin vine din
 * SpectrogramGenerator.estimateCwBinSnr (DSP) - modelul de detectie de dinainte a disparut.
 *
 * Instanta NU e thread-safe (SpectrogramGenerator reutilizeaza buffere STFT).
 */
class CwDetector {
    private val spectrogramGenerator = SpectrogramGenerator()

    fun detectStations(
        audio: FloatArray,
        minFreqHz: Float,
        maxFreqHz: Float,
        lockSnrThresholdDb: Float,
        releaseSnrThresholdDb: Float,
        lockedFrequencies: List<Float> = emptyList()
    ): List<DetectedStation> {
        val binSnr = spectrogramGenerator.estimateCwBinSnr(audio, minFreqHz, maxFreqHz)
            ?: return emptyList()

        val scoredBins = binSnr.frequencies.mapIndexed { index, freq ->
            DetectedStation(freq, binSnr.snrDb[index])
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
}
