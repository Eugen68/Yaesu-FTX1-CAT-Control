package com.example.yaesucatcontroladv.deepcw.dsp

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** Un candidat de ton gasit de detectAllTonesHz, cel mai puternic primul odata sortat. */
data class ToneCandidate(val freqHz: Float, val snrDb: Float)

/**
 * Gaseste varfuri de tip ton intr-o fereastra de audio, in banda decodabila - sustine un
 * buton "Detecteaza tonul", un inlocuitor rapid pentru a cauta manual semnalul pe waterfall.
 *
 * Algoritmul de gasire a varfurilor (prag local de zgomot + SNR + proeminenta) e portat din
 * aplicatia Windows sora a acestui port (DeepCw.Core/Dsp/ToneDetector.cs), care la randul ei
 * l-a portat din findRawPeaks al aplicatiei web originale (src/utils/peakDetector.ts). Spre
 * deosebire de varianta Windows (care trebuie sa evite benzi deja ocupate de ALTE benzi
 * active, fiind multi-banda), acest port e cu un singur semnal tintit o data - aplicatia
 * Android decodeaza un singur canal tunat, nu mai multe simultan.
 *
 * Nu e thread-safe (STFT reutilizeaza buffere interne) - de folosit dintr-un singur thread
 * (aici: click pe buton, pe thread-ul UI, cu snapshot-ul audio luat sincron).
 */
object ToneDetector {
    // Estimare locala a pragului de zgomot (percentila 25, fereastra ±50 bin-uri) -
    // identic cu peakDetector.ts / ToneDetector.cs.
    private const val NOISE_WINDOW_HALF_BINS = 50
    private const val NOISE_PERCENTILE = 0.25

    // Detectie de varf pe baza de SNR (aceleasi praguri ca peakDetector.ts/ToneDetector.cs -
    // deja in dB real, nu scala 0-255 a AnalyserNode-ului original).
    private const val SNR_THRESHOLD_DB = 8.0
    private const val MIN_PROMINENCE_DB = 4.0
    private const val PROMINENCE_SEARCH_BINS = 30
    private const val LOCAL_MAX_WINDOW_BINS = 3

    /**
     * Toate varfurile tip-ton din [minFreqHz, maxFreqHz] care trec pragul SNR/proeminenta
     * peste zgomotul lor local, sortate de la cel mai puternic (SNR maxim) - lista goala daca
     * audio-ul e prea scurt pentru un cadru FFT intreg sau daca nimic nu trece pragul. Fara
     * candidat de rezerva: bin-ul cu energia bruta maxima dintr-un spectru fara ton e doar
     * zgomot, iar acordarea pe el muta decodorul de pe semnalul vizibil intr-un loc gol.
     */
    @JvmStatic
    fun detectAllTonesHz(
        audio: FloatArray,
        sampleRate: Double,
        minFreqHz: Double,
        maxFreqHz: Double,
        fftLength: Int,
        hopLength: Int
    ): List<ToneCandidate> {
        val stft = STFT(fftLength, hopLength)
        val totalBins = fftLength / 2 + 1
        val binResolution = sampleRate / fftLength

        val startBin = max(0, Math.round(minFreqHz / binResolution).toInt())
        val endBin = min(totalBins, Math.round(maxFreqHz / binResolution).toInt() + 1)
        if (endBin <= startBin) return emptyList()

        val binEnergy = DoubleArray(endBin - startBin)
        val frames = stft.forEachSpectrum(audio) { complexFrame, _ ->
            for (bin in startBin until endBin) {
                val real = complexFrame[bin * 2]
                val imag = complexFrame[bin * 2 + 1]
                binEnergy[bin - startBin] += real.toDouble() * real + imag.toDouble() * imag
            }
        }
        if (frames == 0) return emptyList()

        val bandBins = endBin - startBin
        val db = DoubleArray(bandBins) { i -> 10.0 * log10(binEnergy[i] + 1e-12) }
        val noiseFloor = estimateLocalNoiseFloor(db)
        val snr = DoubleArray(bandBins) { i -> db[i] - noiseFloor[i] }

        val candidates = mutableListOf<ToneCandidate>()
        for (b in LOCAL_MAX_WINDOW_BINS until bandBins - LOCAL_MAX_WINDOW_BINS) {
            var isLocalMax = true
            for (w in 1..LOCAL_MAX_WINDOW_BINS) {
                if (snr[b] <= snr[b - w] || snr[b] <= snr[b + w]) {
                    isLocalMax = false
                    break
                }
            }
            if (!isLocalMax || snr[b] < SNR_THRESHOLD_DB) continue

            var leftMin = snr[b]
            var i = b - 1
            while (i >= max(0, b - PROMINENCE_SEARCH_BINS)) {
                if (snr[i] > snr[b]) break
                leftMin = min(leftMin, snr[i])
                i--
            }
            var rightMin = snr[b]
            var j = b + 1
            while (j <= min(bandBins - 1, b + PROMINENCE_SEARCH_BINS)) {
                if (snr[j] > snr[b]) break
                rightMin = min(rightMin, snr[j])
                j++
            }
            val prominence = snr[b] - max(leftMin, rightMin)
            if (prominence < MIN_PROMINENCE_DB) continue

            candidates.add(ToneCandidate(((startBin + b) * binResolution).toFloat(), snr[b].toFloat()))
        }

        candidates.sortByDescending { it.snrDb }
        return candidates
    }

    /**
     * Prag de zgomot per-bin: percentila 25 a valorilor dB intr-o fereastra de ±50 bin-uri in
     * jurul fiecarui bin (identic cu estimateLocalNoiseFloor din peakDetector.ts/ToneDetector.cs).
     * Local, nu global, ca estimarea sa se adapteze la o forma neuniforma a zgomotului pe banda,
     * nu la un singur prag plat.
     */
    private fun estimateLocalNoiseFloor(db: DoubleArray): DoubleArray {
        val noiseFloor = DoubleArray(db.size)
        for (b in db.indices) {
            val lo = max(0, b - NOISE_WINDOW_HALF_BINS)
            val hi = min(db.size - 1, b + NOISE_WINDOW_HALF_BINS)
            val window = db.copyOfRange(lo, hi + 1)
            window.sort()
            noiseFloor[b] = window[(window.size * NOISE_PERCENTILE).toInt()]
        }
        return noiseFloor
    }
}
