package com.example.yaesucatcontroladv.deepcw.data

import com.example.yaesucatcontroladv.deepcw.dsp.Constants.FFT_LENGTH
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.HOP_LENGTH
import kotlin.math.roundToInt

data class CharSpan(val char: String, val startFrame: Int, var endFrame: Int)

data class WordSpaceSpan(val startFrame: Int, val endFrame: Int)

/** Echivalentul `DecodedPredictionResult` din `src/utils/textDecoder.ts`. */
data class DetailedTranscript(
    val displayText: String,
    val plainText: String,
    val wordSpaceSpans: List<WordSpaceSpan>,
    val characterSpans: List<CharSpan>
)

/** Text + span-uri, dupa normalizarea spatiilor (echivalent `StreamingDecodeLane`). */
data class TranscriptLane(val text: String, val characterSpans: List<CharSpan>)

/**
 * Decodor CTC greedy, port fidel al `decodePredictionsDetailed` din
 * `src/utils/textDecoder.ts` (varianta engleza, cu BLANK_INDEX).
 */
class CTCDecoder(val lang: String = "en") {

    private val vocabulary = MorseVocabularies.ENGLISH_VOCABULARY
    private val blankIndex = MorseVocabularies.ENGLISH_BLANK_INDEX
    private val spaceIndex = vocabulary.indexOf(" ")

    /** Decodeaza primul element de batch al tensorului de iesire. */
    fun decodeDetailed(probabilities: FloatArray, dims: LongArray): DetailedTranscript {
        return decodeDetailedBatch(probabilities, dims).firstOrNull()
            ?: DetailedTranscript("", "", emptyList(), emptyList())
    }

    fun decodeDetailedBatch(probabilities: FloatArray, dims: LongArray): List<DetailedTranscript> {
        return getPredictionIndices(probabilities, dims).map { predIndices ->
            DetailedTranscript(
                displayText = decodeCtcForDisplay(predIndices),
                plainText = decodeCtcPlain(predIndices),
                wordSpaceSpans = getWordSpaceSpans(predIndices),
                characterSpans = getCharacterSpans(predIndices)
            )
        }
    }

    /**
     * argmax pe fiecare cadru, pentru fiecare element de batch.
     * Ultima dimensiune = numarul de clase, penultima = numarul de cadre; dimensiunile
     * anterioare formeaza batch-ul (tensorii ONNX pot veni ca [B,T,C] sau [B,1,T,C]).
     */
    private fun getPredictionIndices(pred: FloatArray, predShape: LongArray): List<IntArray> {
        if (predShape.size < 2) return emptyList()

        val numClasses = predShape[predShape.size - 1].toInt()
        val timeSteps = predShape[predShape.size - 2].toInt()
        if (numClasses <= 0 || timeSteps <= 0) return emptyList()

        var batchSize = 1
        for (i in 0 until predShape.size - 2) batchSize *= predShape[i].toInt()
        if (batchSize <= 0) return emptyList()

        val results = ArrayList<IntArray>(batchSize)
        for (b in 0 until batchSize) {
            val predIndices = IntArray(timeSteps)
            for (t in 0 until timeSteps) {
                val offset = b * timeSteps * numClasses + t * numClasses
                if (offset + numClasses > pred.size) break
                var maxProb = Float.NEGATIVE_INFINITY
                var maxIndex = 0
                for (c in 0 until numClasses) {
                    val value = pred[offset + c]
                    if (value > maxProb) {
                        maxProb = value
                        maxIndex = c
                    }
                }
                predIndices[t] = maxIndex
            }
            results.add(predIndices)
        }
        return results
    }

    private fun decodeCtcForDisplay(predIndices: IntArray): String {
        val builder = StringBuilder(predIndices.size)
        var previousIndex = -1

        for (index in predIndices) {
            if (index == blankIndex) {
                builder.append(' ')
                previousIndex = -1
                continue
            }
            if (index == previousIndex) {
                builder.append(' ')
                continue
            }
            previousIndex = index
            builder.append(vocabulary.getOrElse(index) { " " })
        }

        return builder.toString()
    }

    private fun decodeCtcPlain(predIndices: IntArray): String {
        val builder = StringBuilder()
        var previousIndex = -1

        for (index in predIndices) {
            if (index == blankIndex) {
                previousIndex = -1
                continue
            }
            if (index == previousIndex) continue
            previousIndex = index
            builder.append(vocabulary.getOrElse(index) { "" })
        }

        return builder.toString()
    }

    private fun getWordSpaceSpans(predIndices: IntArray): List<WordSpaceSpan> {
        if (spaceIndex < 0) return emptyList()

        val spans = mutableListOf<WordSpaceSpan>()
        var currentStart = -1

        for (frameIndex in predIndices.indices) {
            if (predIndices[frameIndex] == spaceIndex) {
                if (currentStart < 0) currentStart = frameIndex
                continue
            }
            if (currentStart >= 0) {
                spans.add(WordSpaceSpan(currentStart, frameIndex - 1))
                currentStart = -1
            }
        }

        if (currentStart >= 0) {
            spans.add(WordSpaceSpan(currentStart, predIndices.size - 1))
        }

        return spans
    }

    private fun getCharacterSpans(predIndices: IntArray): List<CharSpan> {
        val spans = mutableListOf<CharSpan>()
        var previousIndex = -1
        var activeSpanIndex = -1

        for (frameIndex in predIndices.indices) {
            val index = predIndices[frameIndex]

            if (index == blankIndex) {
                previousIndex = -1
                activeSpanIndex = -1
                continue
            }

            if (index == previousIndex) {
                if (activeSpanIndex >= 0) spans[activeSpanIndex].endFrame = frameIndex
                continue
            }

            previousIndex = index
            val char = vocabulary.getOrElse(index) { "" }
            if (char.isEmpty()) {
                activeSpanIndex = -1
                continue
            }

            spans.add(CharSpan(char, frameIndex, frameIndex))
            activeSpanIndex = spans.size - 1
        }

        return spans
    }
}

/**
 * Utilitare de segmentare streaming, portate din `src/hooks/useStreamingDecode.ts`
 * (`getSpanSplitSample`, `trimDetailedResultToFrame`, `normalizeDetailedResult`).
 */
object StreamingTranscript {

    /** Eșantionul din mijlocul unei pauze de cuvant - punctul candidat de "commit". */
    fun getSpanSplitSample(span: WordSpaceSpan): Int {
        val midFrame = (span.startFrame + span.endFrame) / 2f
        return (midFrame * HOP_LENGTH + FFT_LENGTH / 2f).roundToInt()
    }

    fun trimToFrame(result: DetailedTranscript, endFrame: Int): DetailedTranscript {
        val characterSpans = result.characterSpans.filter { it.endFrame <= endFrame }
        val plainText = characterSpans.joinToString("") { it.char }
        return DetailedTranscript(
            displayText = plainText,
            plainText = plainText,
            wordSpaceSpans = result.wordSpaceSpans.filter { it.endFrame <= endFrame },
            characterSpans = characterSpans
        )
    }

    /**
     * Colapseaza spatiile multiple intr-unul singur si taie spatiile de la inceput,
     * pastrand span-urile aliniate cu textul rezultat.
     */
    fun normalize(result: DetailedTranscript): TranscriptLane {
        val inputChars = result.plainText.map { it.toString() }
        if (inputChars.isEmpty() || inputChars.size != result.characterSpans.size) {
            return TranscriptLane(collapseWhitespace(result.plainText), emptyList())
        }

        val normalizedChars = StringBuilder()
        val normalizedSpans = mutableListOf<CharSpan>()
        var pendingWhitespaceStart: Int? = null
        var pendingWhitespaceEnd: Int? = null

        inputChars.forEachIndexed { index, char ->
            val span = result.characterSpans[index]

            if (char.isBlank()) {
                if (normalizedChars.isEmpty()) return@forEachIndexed
                if (pendingWhitespaceStart == null) pendingWhitespaceStart = span.startFrame
                pendingWhitespaceEnd = span.endFrame
                return@forEachIndexed
            }

            val whitespaceStart = pendingWhitespaceStart
            val whitespaceEnd = pendingWhitespaceEnd
            if (whitespaceStart != null && whitespaceEnd != null) {
                normalizedChars.append(' ')
                normalizedSpans.add(CharSpan(" ", whitespaceStart, whitespaceEnd))
                pendingWhitespaceStart = null
                pendingWhitespaceEnd = null
            }

            normalizedChars.append(char)
            normalizedSpans.add(CharSpan(char, span.startFrame, span.endFrame))
        }

        return TranscriptLane(normalizedChars.toString(), normalizedSpans)
    }

    private fun collapseWhitespace(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()
}
