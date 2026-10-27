package com.example.yaesucatcontroladv.deepcw.dsp

import kotlin.math.roundToInt

/**
 * Constante DSP aliniate 1:1 la proiectul de referinta (web-deep-cw-decoder, src/const.ts).
 * Orice abatere de la aceste valori dezaliniaza spectrograma fata de datele pe care au fost
 * antrenate modelele ONNX (65 de bin-uri, 12.5 Hz/bin, pas de intrare 15 ms).
 */
object Constants {
    const val FFT_LENGTH = 768

    /**
     * Pas STFT de 20 ms (192 / 9600): waterfall, "Detecteaza tonul" si scorul SNR per bin
     * care gaseste statiile in modul pileup (estimateCwBinSnr).
     */
    const val HOP_LENGTH = 192
    const val SAMPLE_RATE = 9600

    /** 9600 / 768 = 12.5 Hz per bin */
    const val BIN_RESOLUTION = SAMPLE_RATE.toFloat() / FFT_LENGTH

    // ---- Modelul de decodare model_en_2026-10.onnx (e04, 2026-10-27) ----
    // Antrenat la 3200 Hz cu fft 256 / hop 48; la 9600 Hz parametrii se scaleaza x3 ca sa
    // pastreze aceleasi durate de fereastra/pas si rezolutia de 12.5 Hz/bin.

    /** Pas STFT al intrarii modelului de decodare: 144 / 9600 = 15 ms. */
    const val DECODE_HOP_LENGTH = 144

    /** Modelul scoate un cadru la fiecare 2 cadre de intrare. */
    const val OUTPUT_TIME_STRIDE = 2

    /** 288 esantioane = 30 ms intre cadrele de iesire ale modelului. */
    const val OUTPUT_HOP_LENGTH = DECODE_HOP_LENGTH * OUTPUT_TIME_STRIDE

    /**
     * Intrarea e center-padded, deci cadrul de iesire t (poate fi fractionar) e centrat pe
     * esantionul t * OUTPUT_HOP_LENGTH al ferestrei decodate.
     */
    fun outputFrameCenterSample(frame: Float): Float = frame * OUTPUT_HOP_LENGTH

    /** Marimea unui chunk de audio livrat de AudioRecorder catre procesor. */
    const val AUDIO_CHUNK_SAMPLES = 2048

    const val DECODABLE_MIN_FREQ_HZ = 400
    const val DECODABLE_MAX_FREQ_HZ = 1200

    val START_BIN = (DECODABLE_MIN_FREQ_HZ / BIN_RESOLUTION).roundToInt()
    val END_BIN = (DECODABLE_MAX_FREQ_HZ / BIN_RESOLUTION).roundToInt() + 1
    val CROPPED_BINS = END_BIN - START_BIN

    /** bin 64 = 800 Hz, centrul intrarii modelului standard */
    val NORMAL_CENTER_BIN = ((START_BIN + END_BIN - 1) / 2f).roundToInt()

    const val TOTAL_BINS = FFT_LENGTH / 2 + 1

    /** Intrarea modelului narrow (pileup) model_en_narrow_2026-10: 3 bin-uri in jurul tonului. */
    const val NARROW_SHIFT_BINS = 3

    /** Separarea minima intre doua statii urmarite in pileup (4 bin-uri = 50 Hz). */
    val PILEUP_FILTER_WIDTH_HZ = BIN_RESOLUTION * 4

    // ---- Afisare / tuning manual (mod Normal) ----
    const val MIN_FREQ_HZ = 100
    const val MAX_FREQ_HZ = 1500
    val DEFAULT_DECODE_CENTER_FREQ_HZ =
        ((DECODABLE_MIN_FREQ_HZ + DECODABLE_MAX_FREQ_HZ) / 2f).roundToInt()
    val FILTER_WIDTH_OPTIONS = intArrayOf(100, 150, 250, 500, 800)
    const val DEFAULT_FILTER_WIDTH_HZ = 800
    const val FILTER_FREQ_STEP_HZ = 20

    // ---- Pileup ----
    const val PILEUP_WINDOW_S = 8
    /** De la 200 Hz: mai jos stau armonicele retelei (100 / 150 Hz). */
    const val PILEUP_MIN_FREQ_HZ = 200
    const val PILEUP_MAX_FREQ_HZ = 2000
    const val PILEUP_MAX_PEAKS = 5
    val PILEUP_MATCH_HZ = BIN_RESOLUTION * 1.2f
    // Scorul din SpectrogramGenerator.estimateCwBinSnr; zgomotul pur da in jur de 5 dB.
    const val PILEUP_LOCK_SNR_THRESHOLD_DB = 12f
    const val PILEUP_RELEASE_SNR_THRESHOLD_DB = 9f
    const val PILEUP_TEXT_HOLD_MS = 1500L
    const val PILEUP_CONFIRM_MS = 400L
    const val PILEUP_HOLD_MS = 5000L

    init {
        require(END_BIN <= TOTAL_BINS) {
            "Banda de decodare configurata depaseste spectrul FFT ($END_BIN > $TOTAL_BINS)."
        }
        // Modelele ONNX (en / en_narrow) au intrarea fixata pe 65 de bin-uri.
        require(CROPPED_BINS == 65) {
            "Intrarea modelului trebuie sa ramana de 65 de bin-uri, s-a obtinut $CROPPED_BINS."
        }
    }
}
