package com.example.yaesucatcontroladv.deepcw.dsp

import com.example.yaesucatcontroladv.deepcw.dsp.Constants.BIN_RESOLUTION
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.CROPPED_BINS
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.DECODE_HOP_LENGTH
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.END_BIN
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.FFT_LENGTH
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.HOP_LENGTH
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.NARROW_SHIFT_BINS
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.NORMAL_CENTER_BIN
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_FILTER_WIDTH_HZ
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.SAMPLE_RATE
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.START_BIN
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.TOTAL_BINS
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

data class SpectrogramResult(
    val data: FloatArray,
    val dims: LongArray
)

/**
 * Port fidel al `src/utils/spectrogramUtils.ts` din proiectul de referinta.
 *
 * Toate spectrogramele pentru modele (decodare normala si narrow/pileup, modelele
 * model_en_2026-10 / model_en_narrow_2026-10) folosesc versiunea e04 din 2026-10-27: pas 144,
 * audio center-padded prin reflexie, normalizare "noise_q90_log1p". Gasirea statiilor in
 * pileup (estimateCwBinSnr) e DSP pur, pe pasul de 192.
 *
 * Instanta NU e thread-safe (STFT reutilizeaza buffere interne): fiecare thread/loop de
 * decodare trebuie sa aiba propria instanta.
 */
class SpectrogramGenerator {
    private val detectStft = STFT(FFT_LENGTH, HOP_LENGTH)
    private val decodeStft = STFT(FFT_LENGTH, DECODE_HOP_LENGTH)
    private val narrowCenterIdx = NARROW_SHIFT_BINS / 2

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

        val paddedAudio = centerPad(processedAudio) ?: return null
        val timeSteps = decodeStft.getFrameCount(paddedAudio.size)

        val flattenedSpectrogram = FloatArray(timeSteps * CROPPED_BINS)

        decodeStft.forEachSpectrum(paddedAudio) { complexFrame, frameIndex ->
            val offset = frameIndex * CROPPED_BINS
            for (bin in START_BIN until END_BIN) {
                val real = complexFrame[bin * 2]
                val imag = complexFrame[bin * 2 + 1]
                flattenedSpectrogram[offset + bin - START_BIN] = sqrt(real * real + imag * imag)
            }
        }

        normalizeSpectrogramNoiseLog1p(flattenedSpectrogram, flattenedSpectrogram)

        return SpectrogramResult(
            data = flattenedSpectrogram,
            dims = longArrayOf(1, 1, timeSteps.toLong(), CROPPED_BINS.toLong())
        )
    }

    /**
     * Extrage `bandwidth` Hz in jurul lui targetFreq si il muta pe centrul modelului (800 Hz).
     * Restul bin-urilor ramane zero. Aceasta e calea folosita pentru decodarea normala tunata.
     * Nivelul de zgomot se estimeaza doar din bin-urile populate - zerourile din afara benzii
     * ar trage percentila in jos.
     */
    fun generateShiftedSpectrogram(
        audio: FloatArray,
        targetFreq: Float,
        bandwidth: Float
    ): SpectrogramResult? {
        val paddedAudio = centerPad(audio) ?: return null
        val timeSteps = decodeStft.getFrameCount(paddedAudio.size)

        val targetBin = round(targetFreq / BIN_RESOLUTION).toInt()
        val halfWidthBins = ceil(bandwidth / 2f / BIN_RESOLUTION).toInt()
        val destCenterIdx = NORMAL_CENTER_BIN - START_BIN

        val flattenedSpectrogram = FloatArray(timeSteps * CROPPED_BINS)

        decodeStft.forEachSpectrum(paddedAudio) { complexFrame, frameIndex ->
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

        val destStart = max(0, destCenterIdx - halfWidthBins)
        val destEnd = min(CROPPED_BINS, destCenterIdx + halfWidthBins + 1)
        val width = destEnd - destStart
        val populated = FloatArray(timeSteps * width)
        for (t in 0 until timeSteps) {
            flattenedSpectrogram.copyInto(populated, t * width, t * CROPPED_BINS + destStart, t * CROPPED_BINS + destEnd)
        }
        normalizeSpectrogramNoiseLog1p(flattenedSpectrogram, populated)

        return SpectrogramResult(
            data = flattenedSpectrogram,
            dims = longArrayOf(1, 1, timeSteps.toLong(), CROPPED_BINS.toLong())
        )
    }

    /** Slice de 3 bin-uri centrat pe targetFreq, pentru modelul narrow (pileup). */
    fun generateNarrowSpectrogram(
        audio: FloatArray,
        targetFreq: Float
    ): SpectrogramResult? {
        val paddedAudio = centerPad(audio) ?: return null
        val timeSteps = decodeStft.getFrameCount(paddedAudio.size)

        val targetBin = round(targetFreq / BIN_RESOLUTION).toInt()
        val flattenedSpectrogram = FloatArray(timeSteps * NARROW_SHIFT_BINS)

        decodeStft.forEachSpectrum(paddedAudio) { complexFrame, frameIndex ->
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

        normalizeSpectrogramNoiseLog1p(flattenedSpectrogram, flattenedSpectrogram)

        return SpectrogramResult(
            data = flattenedSpectrogram,
            dims = longArrayOf(1, 1, timeSteps.toLong(), NARROW_SHIFT_BINS.toLong())
        )
    }

    /** SNR-ul estimat al fiecarui bin din [minFreqHz, maxFreqHz] - vezi estimateCwBinSnr. */
    class BinSnr(val frequencies: FloatArray, val snrDb: FloatArray)

    /**
     * SNR CW per bin (gaseste statiile in modul pileup; inlocuieste modelul de detectie):
     * puterea bin-ului "cu cheia apasata" (percentila 90 in timp) fata de mediana puterii
     * intregii benzi. Zgomotul pur da in jur de 5 dB. Bin-urile cu mult mai slabe decat un
     * bin puternic din apropiere sunt benzile laterale ale manipularii lui si primesc 0 dB -
     * altfel ar aparea ca statii-fantoma langa un semnal puternic.
     *
     * Adaugat fata de e04: un bin trebuie si sa fie manipulat - percentila 90 cu cel putin
     * CW_MIN_KEYING_DB peste percentila 20 a aceluiasi bin. Telegrafia are pauze (~50% din
     * timp); zgomotul pur fluctueaza singur ~10 dB intre cele doua percentile, o purtatoare
     * puternica sau brumul retelei doar 0-3 dB.
     */
    fun estimateCwBinSnr(audio: FloatArray, minFreqHz: Float, maxFreqHz: Float): BinSnr? {
        val timeSteps = detectStft.getFrameCount(audio.size)
        if (timeSteps == 0) return null

        val minBin = max(0, ceil(max(0f, minFreqHz) / BIN_RESOLUTION).toInt())
        val maxBin = min(TOTAL_BINS - 1, floor(min(SAMPLE_RATE / 2f, maxFreqHz) / BIN_RESOLUTION).toInt())
        val binCount = maxBin - minBin + 1
        if (binCount <= 0) return null

        val powers = FloatArray(binCount * timeSteps)
        detectStft.forEachSpectrum(audio) { complexFrame, frameIndex ->
            for (bin in minBin..maxBin) {
                val real = complexFrame[bin * 2]
                val imag = complexFrame[bin * 2 + 1]
                powers[(bin - minBin) * timeSteps + frameIndex] = real * real + imag * imag
            }
        }

        val noisePower = max(powers.copyOf().also { it.sort() }[powers.size / 2], 1e-12f)
        val snrDb = FloatArray(binCount)
        for (index in 0 until binCount) {
            val binPowers = powers.copyOfRange(index * timeSteps, (index + 1) * timeSteps)
            binPowers.sort()
            val onPower = binPowers[(CW_ON_LEVEL_QUANTILE * (timeSteps - 1)).toInt()]
            val offPower = binPowers[(CW_OFF_LEVEL_QUANTILE * (timeSteps - 1)).toInt()]
            val keyingDb = 10f * log10(max(onPower, 1e-12f) / max(offPower, 1e-12f))
            // Fara pauze de manipulare nu e telegrafie: brumul retelei (100/150 Hz), o
            // purtatoare constanta sau un semnal digital continuu ar aparea altfel ca statii.
            snrDb[index] = if (keyingDb < CW_MIN_KEYING_DB) 0f
            else 10f * log10(max(onPower, 1e-12f) / noisePower)
        }

        val maskBins = round(CW_SIDEBAND_MASK_HZ / BIN_RESOLUTION).toInt()
        val maskedSnrDb = snrDb.copyOf()
        for (index in 0 until binCount) {
            val lo = max(0, index - maskBins)
            val hi = min(binCount - 1, index + maskBins)
            for (other in lo..hi) {
                if (snrDb[other] - snrDb[index] > CW_SIDEBAND_MASK_DB) {
                    maskedSnrDb[index] = 0f
                    break
                }
            }
        }

        maskHarmonics(powers, timeSteps, minBin, snrDb, maskedSnrDb)

        return BinSnr(FloatArray(binCount) { (minBin + it) * BIN_RESOLUTION }, maskedSnrDb)
    }

    /**
     * Armonicele (2f, 3f) unei statii puternice primesc 0 dB. O distorsiune in lantul audio
     * (placa de sunet, AGC, volum prea mare) face din statia de la f o copie la 2f si 3f,
     * manipulata identic, prea slaba ca sa se vada pe waterfall, dar peste pragul de lock
     * fata de mediana benzii - ar aparea in pileup ca a doua statie cu acelasi text.
     * Un bin e armonica doar daca e mai slab decat fundamentala SI puterea lui urmeaza in
     * timp manipularea ei (corelatie mare); o statie reala aflata intamplator la 2f are alt
     * text, deci alta manipulare, si ramane.
     */
    private fun maskHarmonics(
        powers: FloatArray,
        timeSteps: Int,
        minBin: Int,
        snrDb: FloatArray,
        maskedSnrDb: FloatArray
    ) {
        val binCount = snrDb.size
        val filterBins = round(PILEUP_FILTER_WIDTH_HZ / BIN_RESOLUTION).toInt()
        for (f0 in 0 until binCount) {
            if (maskedSnrDb[f0] < HARMONIC_MIN_FUNDAMENTAL_DB) continue
            if (f0 > 0 && snrDb[f0 - 1] > snrDb[f0]) continue
            if (f0 < binCount - 1 && snrDb[f0 + 1] > snrDb[f0]) continue
            val absoluteF0 = minBin + f0
            for (k in 2..HARMONIC_MAX_ORDER) {
                // varful armonicii: cel mai puternic bin in jurul lui k*f0 (rotunjirea bin-ului
                // fundamentalei se inmulteste cu k)
                val center = k * absoluteF0 - minBin
                var peak = -1
                for (h in center - k..center + k) {
                    if (h !in 0 until binCount) continue
                    if (peak < 0 || snrDb[h] > snrDb[peak]) peak = h
                }
                if (peak < 0 || maskedSnrDb[peak] == 0f) continue
                if (snrDb[f0] - snrDb[peak] < HARMONIC_MIN_DROP_DB) continue
                if (powerCorrelation(powers, timeSteps, f0, peak) < HARMONIC_MIN_CORRELATION) continue
                // ca la o statie: tot ce e mai slab sub PILEUP_FILTER_WIDTH_HZ de varf (scurgerea
                // spectrala a armonicii) dispare odata cu ea
                for (h in max(0, peak - filterBins + 1)..min(binCount - 1, peak + filterBins - 1)) {
                    if (snrDb[h] <= snrDb[peak]) maskedSnrDb[h] = 0f
                }
            }
        }
    }

    /** Corelatia Pearson intre puterile in timp ale bin-urilor a si b. */
    private fun powerCorrelation(powers: FloatArray, timeSteps: Int, a: Int, b: Int): Float {
        val offA = a * timeSteps
        val offB = b * timeSteps
        var meanA = 0.0
        var meanB = 0.0
        for (t in 0 until timeSteps) {
            meanA += powers[offA + t]
            meanB += powers[offB + t]
        }
        meanA /= timeSteps
        meanB /= timeSteps
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (t in 0 until timeSteps) {
            val da = powers[offA + t] - meanA
            val db = powers[offB + t] - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        if (varA <= 0.0 || varB <= 0.0) return 0f
        return (cov / sqrt(varA * varB)).toFloat()
    }

    /**
     * Reflect-padding de FFT_LENGTH / 2 la ambele capete, ca la un STFT centrat: cadrul t
     * e centrat pe esantionul t * DECODE_HOP_LENGTH al audio-ului original.
     */
    private fun centerPad(audio: FloatArray): FloatArray? {
        if (audio.size <= CENTER_PAD + 1) return null
        val padded = FloatArray(audio.size + CENTER_PAD * 2)
        for (i in 0 until CENTER_PAD) {
            padded[i] = audio[CENTER_PAD - i]
            padded[CENTER_PAD + audio.size + i] = audio[audio.size - 2 - i]
        }
        audio.copyInto(padded, CENTER_PAD)
        return padded
    }

    /**
     * Normalizarea "noise_q90_log1p" a modelului din 2026-10: nivelul zgomotului e percentila
     * 90 a magnitudinilor din `noiseSource`, impartita la factorul Rayleigh corespunzator
     * (sqrt(-2 ln 0.1) ~ 2.146), cu doua plafoane de jos; apoi log1p(x / zgomot).
     */
    private fun normalizeSpectrogramNoiseLog1p(spectrogram: FloatArray, noiseSource: FloatArray) {
        if (noiseSource.isEmpty()) return
        val sorted = noiseSource.copyOf()
        sorted.sort()
        val noiseLevel = maxOf(
            sorted[(NOISE_LEVEL_QUANTILE * (sorted.size - 1)).toInt()] / NOISE_LEVEL_RAYLEIGH_FACTOR,
            sorted[sorted.size - 1] * NOISE_LEVEL_MIN_RELATIVE_TO_PEAK,
            NOISE_LEVEL_MIN
        )
        for (i in spectrogram.indices) {
            spectrogram[i] = ln1p(spectrogram[i] / noiseLevel)
        }
    }

    private companion object {
        const val CENTER_PAD = FFT_LENGTH / 2
        const val NOISE_LEVEL_QUANTILE = 0.9
        val NOISE_LEVEL_RAYLEIGH_FACTOR = sqrt(-2.0 * ln(1.0 - NOISE_LEVEL_QUANTILE)).toFloat()
        const val NOISE_LEVEL_MIN_RELATIVE_TO_PEAK = 1e-3f
        const val NOISE_LEVEL_MIN = 1e-5f
        const val CW_ON_LEVEL_QUANTILE = 0.9
        const val CW_SIDEBAND_MASK_HZ = 150f
        const val CW_SIDEBAND_MASK_DB = 18f
        const val CW_OFF_LEVEL_QUANTILE = 0.2
        const val CW_MIN_KEYING_DB = 6f
        const val HARMONIC_MAX_ORDER = 3
        const val HARMONIC_MIN_FUNDAMENTAL_DB = 12f
        const val HARMONIC_MIN_DROP_DB = 3f
        const val HARMONIC_MIN_CORRELATION = 0.6f
    }
}
