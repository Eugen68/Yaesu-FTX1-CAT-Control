package com.example.yaesucatcontroladv.deeprtty

import kotlin.math.roundToInt

/**
 * Parametrii modelului DeepRTTY (e04, github.com/e04/DeepRTTY, MIT) - din model.onnx.json.
 * Modelul e in assets ca model_rtty_2026-10.onnx.
 */
object DeepRttyConst {
    const val MODEL_FILE = "model_rtty_2026-10.onnx"

    const val SAMPLE_RATE = 3200
    const val CENTRU_HZ = 800.0
    const val BAUD = 45.45

    const val FFT_LENGTH = 128
    const val WIN_LENGTH = 96
    const val HOP_LENGTH = 24
    const val OUTPUT_HOP_LENGTH = 48

    /** Bin-urile 650-950 Hz: ceil(650 / 25) .. floor(950 / 25). */
    const val START_BIN = 26
    const val STOP_BIN = 39
    const val BINS = STOP_BIN - START_BIN

    /** Indexul clasei blank din iesire; clasele 0..31 sunt codurile ITA2. */
    const val BLANK_INDEX = 32

    const val NOISE_LEVEL_QUANTILE = 0.25
    const val SILENT_FRAME_REFERENCE_QUANTILE = 0.9
    const val SILENT_FRAME_RELATIVE_LEVEL = 0.01f
    const val NOISE_LEVEL_RAYLEIGH_FACTOR = 0.7585276164409321f
    const val NOISE_LEVEL_MIN_RELATIVE_TO_PEAK = 0.001f
    const val NOISE_LEVEL_MIN = 0.00001f

    /** Fereastra de decodare: 12 s. */
    const val WINDOW = 12 * SAMPLE_RATE

    /** Pasul ferestrelor: ~8 s, pe grila cadrelor de iesire (533 x 48 = 25584). */
    val HOP = (8.0 * SAMPLE_RATE / OUTPUT_HOP_LENGTH).roundToInt() * OUTPUT_HOP_LENGTH

    /** Marginea nesigura a fiecarei ferestre interioare: 0.5 s. */
    const val EDGE_GUARD = SAMPLE_RATE / 2

    /** Toleranta de imperechere a caracterelor intre ferestre: 0.45 dintr-un caracter (7.5 biti). */
    const val TOLERANCE = 0.45 * 7.5 / BAUD * SAMPLE_RATE
}
