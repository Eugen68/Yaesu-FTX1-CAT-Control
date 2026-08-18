package com.example.yaesucatcontroladv.deepcw.data

import android.content.Context
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

    /** Decodare cu modelul narrow de 5 bin-uri (pileup). */
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
