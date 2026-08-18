package com.example.yaesucatcontroladv.deepcw.dsp

import com.example.yaesucatcontroladv.deepcw.dsp.Constants.BIN_RESOLUTION
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.CROPPED_BINS
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.END_BIN
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.FFT_LENGTH
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.HOP_LENGTH
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.NARROW_SHIFT_BINS
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.NORMAL_CENTER_BIN
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.SAMPLE_RATE
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.START_BIN
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.TOTAL_BINS
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

data class SpectrogramResult(
    val data: FloatArray,
    val dims: LongArray,
    val frequencies: FloatArray? = null
)

/**
 * Port fidel al `src/utils/spectrogramUtils.ts` din proiectul de referinta.
 *
 * ATENTIE: normalizarea CMVN este GLOBALA (o singura medie/deviatie pentru toata
 * spectrograma), exact ca in referinta - nu per cadru temporal. Varianta per-cadru
 * (folosita anterior in acest port) distruge informatia de amplitudine relativa intre
 * cadre, adica exact semnalul pe care se bazeaza modelul pentru a distinge ton de pauza.
 *
 * Instanta NU e thread-safe (STFT reutilizeaza buffere interne): fiecare thread/loop de
 * decodare trebuie sa aiba propria instanta.
 */
class SpectrogramGenerator {
    private val stft = STFT(FFT_LENGTH, HOP_LENGTH)
    private val narrowCenterIdx = NARROW_SHIFT_BINS / 2

    fun getFrameCount(signalLength: Int): Int = stft.getFrameCount(signalLength)

    /**
     * Spectrograma "plina" a benzii decodabile (400-1200 Hz), fara deplasare de frecventa.
     * Folosita doar cand nu exista o frecventa tunata (filterFreq == null).
     */
    fun generateSpectrogram(
        audio: FloatArray,
        filterFreq: Float?,
        filterWidth: Float
    ): SpectrogramResult? {
        val processedAudio = if (filterFreq != null && filterWidth > 0f) {
            AudioFilters.applyBandpassFilter(audio, SAMPLE_RATE.toFloat(), filterFreq, filterWidth)
        } else {
            audio
        }

        val timeSteps = stft.getFrameCount(processedAudio.size)
        if (timeSteps == 0) return null

        val flattenedSpectrogram = FloatArray(timeSteps * CROPPED_BINS)

        stft.forEachSpectrum(processedAudio) { complexFrame, frameIndex ->
            val offset = frameIndex * CROPPED_BINS
            for (bin in START_BIN until END_BIN) {
                val real = complexFrame[bin * 2]
                val imag = complexFrame[bin * 2 + 1]
                flattenedSpectrogram[offset + bin - START_BIN] = sqrt(real * real + imag * imag)
            }
        }

        normalizeSpectrogramPerSampleCmvn(flattenedSpectrogram)

        return SpectrogramResult(
            data = flattenedSpectrogram,
            dims = longArrayOf(1, 1, timeSteps.toLong(), CROPPED_BINS.toLong())
        )
    }

    /**
     * Extrage `bandwidth` Hz in jurul lui targetFreq si il muta pe centrul modelului (800 Hz).
     * Restul bin-urilor ramane zero. Aceasta e calea folosita pentru decodarea normala tunata.
     */
    fun generateShiftedSpectrogram(
        audio: FloatArray,
        targetFreq: Float,
        bandwidth: Float
    ): SpectrogramResult? {
        val timeSteps = stft.getFrameCount(audio.size)
        if (timeSteps == 0) return null

        val targetBin = round(targetFreq / BIN_RESOLUTION).toInt()
        val halfWidthBins = ceil(bandwidth / 2f / BIN_RESOLUTION).toInt()
        val destCenterIdx = NORMAL_CENTER_BIN - START_BIN

        val flattenedSpectrogram = FloatArray(timeSteps * CROPPED_BINS)

        stft.forEachSpectrum(audio) { complexFrame, frameIndex ->
            val offset = frameIndex * CROPPED_BINS
            for (d in -halfWidthBins..halfWidthBins) {
                val srcBin = targetBin + d
                val destIdx = destCenterIdx + d
                if (srcBin in 0 until TOTAL_BINS && destIdx in 0 until CROPPED_BINS) {
                    val real = complexFrame[srcBin * 2]
                    val imag = complexFrame[srcBin * 2 + 1]
                    flattenedSpectrogram[offset + destIdx] = sqrt(real * real + imag * imag)
                }
            }
        }

        normalizeSpectrogramPerSampleCmvn(flattenedSpectrogram)

        return SpectrogramResult(
            data = flattenedSpectrogram,
            dims = longArrayOf(1, 1, timeSteps.toLong(), CROPPED_BINS.toLong())
        )
    }

    /** Slice de 5 bin-uri centrat pe targetFreq, pentru modelul narrow (pileup). */
    fun generateNarrowSpectrogram(
        audio: FloatArray,
        targetFreq: Float
    ): SpectrogramResult? {
        val timeSteps = stft.getFrameCount(audio.size)
        if (timeSteps == 0) return null

        val targetBin = round(targetFreq / BIN_RESOLUTION).toInt()
        val flattenedSpectrogram = FloatArray(timeSteps * NARROW_SHIFT_BINS)

        stft.forEachSpectrum(audio) { complexFrame, frameIndex ->
            val offset = frameIndex * NARROW_SHIFT_BINS
            for (d in -narrowCenterIdx..narrowCenterIdx) {
                val srcBin = targetBin + d
                val destIdx = narrowCenterIdx + d
                if (srcBin in 0 until TOTAL_BINS) {
                    val real = complexFrame[srcBin * 2]
                    val imag = complexFrame[srcBin * 2 + 1]
                    flattenedSpectrogram[offset + destIdx] = sqrt(real * real + imag * imag)
                }
            }
        }

        normalizeSpectrogramPerSampleCmvn(flattenedSpectrogram)

        return SpectrogramResult(
            data = flattenedSpectrogram,
            dims = longArrayOf(1, 1, timeSteps.toLong(), NARROW_SHIFT_BINS.toLong())
        )
    }

    /**
     * Un "batch" de secvente temporale, una pentru fiecare bin din intervalul cerut.
     * Intrarea modelului de detectie CW. CMVN aici e per bin (per element de batch), ca in
     * referinta, si pe magnitudine logaritmica.
     */
    fun generateBinSequence(
        audio: FloatArray,
        minFreqHz: Float,
        maxFreqHz: Float
    ): SpectrogramResult? {
        val timeSteps = stft.getFrameCount(audio.size)
        if (timeSteps == 0) return null

        val clampedMin = max(0f, minFreqHz)
        val clampedMax = min(SAMPLE_RATE / 2f, maxFreqHz)
        if (clampedMin > clampedMax) return null

        val minBin = max(0, floor(clampedMin / BIN_RESOLUTION).toInt())
        val maxBin = min(TOTAL_BINS - 1, ceil(clampedMax / BIN_RESOLUTION).toInt())
        val batchSize = maxBin - minBin + 1
        if (batchSize <= 0) return null

        val flattenedSequences = FloatArray(batchSize * timeSteps)
        val frequencies = FloatArray(batchSize) { (minBin + it) * BIN_RESOLUTION }

        stft.forEachSpectrum(audio) { complexFrame, frameIndex ->
            for (bin in minBin..maxBin) {
                val real = complexFrame[bin * 2]
                val imag = complexFrame[bin * 2 + 1]
                val magnitude = sqrt(real * real + imag * imag)
                val batchIndex = bin - minBin
                flattenedSequences[batchIndex * timeSteps + frameIndex] = magnitude
            }
        }

        for (b in 0 until batchSize) {
            val offset = b * timeSteps
            var mean = 0f
            for (t in 0 until timeSteps) {
                val logMag = ln1p(flattenedSequences[offset + t])
                flattenedSequences[offset + t] = logMag
                mean += logMag
            }
            mean /= timeSteps

            var variance = 0f
            for (t in 0 until timeSteps) {
                val centered = flattenedSequences[offset + t] - mean
                variance += centered * centered
            }
            val std = max(sqrt(variance / timeSteps), 1e-4f)
            for (t in 0 until timeSteps) {
                flattenedSequences[offset + t] = (flattenedSequences[offset + t] - mean) / std
            }
        }

        return SpectrogramResult(
            data = flattenedSequences,
            dims = longArrayOf(batchSize.toLong(), 1, timeSteps.toLong()),
            frequencies = frequencies
        )
    }

    /** CMVN global pe toata spectrograma (identic cu referinta). */
    private fun normalizeSpectrogramPerSampleCmvn(spectrogram: FloatArray) {
        if (spectrogram.isEmpty()) return

        var mean = 0f
        for (value in spectrogram) mean += value
        mean /= spectrogram.size

        var variance = 0f
        for (value in spectrogram) {
            val centered = value - mean
            variance += centered * centered
        }

        val std = max(sqrt(variance / spectrogram.size), 1e-5f)
        for (i in spectrogram.indices) {
            spectrogram[i] = (spectrogram[i] - mean) / std
        }
    }
}
