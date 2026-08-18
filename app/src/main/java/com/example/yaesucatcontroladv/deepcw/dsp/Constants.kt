package com.example.yaesucatcontroladv.deepcw.dsp

import kotlin.math.roundToInt

/**
 * Constante DSP aliniate 1:1 la proiectul de referinta (web-deep-cw-decoder, src/const.ts).
 * Orice abatere de la aceste valori dezaliniaza spectrograma fata de datele pe care au fost
 * antrenate modelele ONNX (65 de bin-uri, 12.5 Hz/bin, 20 ms/frame).
 */
object Constants {
    const val FFT_LENGTH = 768
    const val HOP_LENGTH = 192
    const val SAMPLE_RATE = 9600

    /** 9600 / 768 = 12.5 Hz per bin */
    const val BIN_RESOLUTION = SAMPLE_RATE.toFloat() / FFT_LENGTH

    /** 192 / 9600 = 20 ms per frame STFT */
    const val FRAME_DURATION_S = HOP_LENGTH.toFloat() / SAMPLE_RATE

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

    const val NARROW_SHIFT_BINS = 5

    /** Slice-ul narrow de 5 bin-uri acopera 4 intervale intre centrele bin-urilor extreme. */
    val PILEUP_FILTER_WIDTH_HZ = BIN_RESOLUTION * (NARROW_SHIFT_BINS - 1)

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
    const val PILEUP_MIN_FREQ_HZ = 100
    const val PILEUP_MAX_FREQ_HZ = 2000
    const val PILEUP_MAX_PEAKS = 5
    val PILEUP_MATCH_HZ = BIN_RESOLUTION * 1.2f
    const val PILEUP_LOCK_SNR_THRESHOLD_DB = -10f
    const val PILEUP_RELEASE_SNR_THRESHOLD_DB = -10f
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
