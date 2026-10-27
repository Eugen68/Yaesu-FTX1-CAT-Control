package com.example.yaesucatcontroladv.deepcw.data

import android.content.Context
import com.example.yaesucatcontroladv.deepcw.dsp.Constants
import com.example.yaesucatcontroladv.deepcw.dsp.SpectrogramGenerator

/**
 * Un model de decodare CW (standard sau narrow) + spectrograma corespunzatoare.
 *
 * O instanta NU e thread-safe (SpectrogramGenerator reutilizeaza buffere STFT interne):
 * fiecare loop de decodare foloseste propria instanta.
 */
class MorseDecoder(context: Context, val modelFile: String, val lang: String = "en") {
    private val inferenceWrapper = InferenceWrapper(context)
    private val spectrogramGenerator = SpectrogramGenerator()
    private val ctcDecoder = CTCDecoder(lang)

    /** true doar daca sesiunea ONNX s-a construit cu succes. */
    val isAvailable: Boolean
        get() = inferenceWrapper.isAvailable

    /** @return null la succes, altfel mesajul de eroare (modelul rămâne indisponibil). */
    fun initialize(): String? = inferenceWrapper.loadModel(modelFile)

    /**
     * Decodare completa (text + span-uri) pentru modelul standard de 65 de bin-uri.
     * Cand `targetFreq != null` se foloseste spectrograma deplasata pe centrul modelului
     * (exact ca in `handleRunInference` din referinta).
     */
    fun decodeDetailed(
        audio: FloatArray,
        targetFreq: Float?,
        filterWidth: Float
    ): DetailedTranscript? {
        if (!isAvailable) return null

        val spectrogram = if (targetFreq != null) {
            spectrogramGenerator.generateShiftedSpectrogram(audio, targetFreq, filterWidth)
        } else {
            spectrogramGenerator.generateSpectrogram(audio, null, filterWidth)
        } ?: return null

        val result = inferenceWrapper.runInference(spectrogram.data, spectrogram.dims) ?: return null
        return ctcDecoder.decodeDetailed(result.first, result.second)
    }

    /**
     * Ca `decodeDetailed` (spectrograma deplasata pe centrul modelului), dar intoarce
     * caracterele cu pozitia lor (esantion relativ la inceputul lui `audio`, centrul cadrului
     * de iesire al modelului) si increderea - intrarea pentru `OverlapMerger`.
     */
    fun decodeTokens(audio: FloatArray, targetFreq: Float, filterWidth: Float): List<CtcToken>? {
        if (!isAvailable) return null

        val spectrogram = spectrogramGenerator.generateShiftedSpectrogram(audio, targetFreq, filterWidth)
            ?: return null
        val result = inferenceWrapper.runInference(spectrogram.data, spectrogram.dims) ?: return null
        return CtcTokens.greedy(result.first, result.second, MorseVocabularies.ENGLISH_BLANK_INDEX).map {
            CtcToken(Math.round(Constants.outputFrameCenterSample(it.frame)).toLong(), it.index, it.score)
        }
    }

    /**
     * Ca `decodeNarrow`, dar intoarce caracterele cu pozitia lor (esantion relativ la inceputul
     * lui `audio`) si increderea - pentru imbinarea ferestrelor succesive ale unei statii.
     */
    fun decodeNarrowTokens(audio: FloatArray, targetFreq: Float): List<CtcToken>? {
        if (!isAvailable) return null

        val spectrogram = spectrogramGenerator.generateNarrowSpectrogram(audio, targetFreq)
            ?: return null
        val result = inferenceWrapper.runInference(spectrogram.data, spectrogram.dims) ?: return null
        return CtcTokens.greedy(result.first, result.second, MorseVocabularies.ENGLISH_BLANK_INDEX).map {
            CtcToken(Math.round(Constants.outputFrameCenterSample(it.frame)).toLong(), it.index, it.score)
        }
    }

    /** Decodare cu modelul narrow de 3 bin-uri (pileup). */
    fun decodeNarrow(audio: FloatArray, targetFreq: Float): String? {
        if (!isAvailable) return null

        val spectrogram = spectrogramGenerator.generateNarrowSpectrogram(audio, targetFreq)
            ?: return null
        val result = inferenceWrapper.runInference(spectrogram.data, spectrogram.dims) ?: return null
        return ctcDecoder.decodeDetailed(result.first, result.second).plainText
    }

    fun release() {
        inferenceWrapper.close()
    }
}
