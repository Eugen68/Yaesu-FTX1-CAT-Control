package com.example.yaesucatcontroladv.deeprtty

import com.example.yaesucatcontroladv.deepcw.data.CtcToken
import com.example.yaesucatcontroladv.deepcw.data.CtcTokens
import com.example.yaesucatcontroladv.deepcw.data.OverlapMerger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spectrograma de intrare a DeepRTTY - port al `audio_to_spectrogram` din
 * examples/python/rtty_stream.py: STFT centrat (reflect-pad 64), fereastra Hann periodica de 96
 * in cadru de 128, pas 24, bin-urile 26..38 (650-950 Hz) si normalizarea
 * "noise_q25_active_log1p".
 */
object DeepRttySpectrogram {
    private val cosTab: Array<FloatArray>
    private val sinTab: Array<FloatArray>

    init {
        val fereastra = DoubleArray(DeepRttyConst.FFT_LENGTH)
        val off = (DeepRttyConst.FFT_LENGTH - DeepRttyConst.WIN_LENGTH) / 2
        for (i in 0 until DeepRttyConst.WIN_LENGTH) {
            fereastra[off + i] = 0.5 - 0.5 * cos(2 * PI * i / DeepRttyConst.WIN_LENGTH)
        }
        cosTab = Array(DeepRttyConst.BINS) { b ->
            val k = DeepRttyConst.START_BIN + b
            FloatArray(DeepRttyConst.FFT_LENGTH) { n -> (fereastra[n] * cos(2 * PI * k * n / DeepRttyConst.FFT_LENGTH)).toFloat() }
        }
        sinTab = Array(DeepRttyConst.BINS) { b ->
            val k = DeepRttyConst.START_BIN + b
            FloatArray(DeepRttyConst.FFT_LENGTH) { n -> (fereastra[n] * sin(2 * PI * k * n / DeepRttyConst.FFT_LENGTH)).toFloat() }
        }
    }

    /** @return datele [1, 1, cadre, 13] si dimensiunile, sau null daca audio-ul e prea scurt. */
    fun calculeaza(audio: FloatArray): Pair<FloatArray, LongArray>? {
        val pad = DeepRttyConst.FFT_LENGTH / 2
        if (audio.size <= pad + 1) return null
        val p = FloatArray(audio.size + 2 * pad)
        for (i in 0 until pad) {
            p[i] = audio[pad - i]
            p[pad + audio.size + i] = audio[audio.size - 2 - i]
        }
        audio.copyInto(p, pad)

        val bins = DeepRttyConst.BINS
        val cadre = (p.size - DeepRttyConst.FFT_LENGTH) / DeepRttyConst.HOP_LENGTH + 1
        val mag = FloatArray(cadre * bins)
        for (t in 0 until cadre) {
            val o = t * DeepRttyConst.HOP_LENGTH
            for (b in 0 until bins) {
                val c = cosTab[b]
                val s = sinTab[b]
                var re = 0f
                var im = 0f
                for (n in 0 until DeepRttyConst.FFT_LENGTH) {
                    val v = p[o + n]
                    re += v * c[n]
                    im -= v * s[n]
                }
                mag[t * bins + b] = sqrt(re * re + im * im)
            }
        }

        // noise_q25_active_log1p: nivelul zgomotului = percentila 25 a magnitudinilor din
        // cadrele "active" (nu tacere), corectata Rayleigh; cadrele tacute ar trage-o in jos.
        val rangCadru = floor(DeepRttyConst.NOISE_LEVEL_QUANTILE * (bins - 1)).toInt()
        val niveluri = FloatArray(cadre) { t -> mag.copyOfRange(t * bins, (t + 1) * bins).also { it.sort() }[rangCadru] }
        val referinta = niveluri.copyOf().also { it.sort() }[floor(DeepRttyConst.SILENT_FRAME_REFERENCE_QUANTILE * (cadre - 1)).toInt()]
        var activeCount = 0
        for (t in 0 until cadre) if (niveluri[t] >= referinta * DeepRttyConst.SILENT_FRAME_RELATIVE_LEVEL) activeCount++
        val sursa = FloatArray(activeCount * bins)
        var j = 0
        var maxim = 0f
        for (t in 0 until cadre) {
            val activ = niveluri[t] >= referinta * DeepRttyConst.SILENT_FRAME_RELATIVE_LEVEL
            for (b in 0 until bins) {
                val v = mag[t * bins + b]
                if (v > maxim) maxim = v
                if (activ) sursa[j++] = v
            }
        }
        sursa.sort()
        val q25 = if (sursa.isEmpty()) 0f else sursa[floor(DeepRttyConst.NOISE_LEVEL_QUANTILE * (sursa.size - 1)).toInt()]
        val zgomot = maxOf(q25 / DeepRttyConst.NOISE_LEVEL_RAYLEIGH_FACTOR,
            maxim * DeepRttyConst.NOISE_LEVEL_MIN_RELATIVE_TO_PEAK, DeepRttyConst.NOISE_LEVEL_MIN)
        for (i in mag.indices) mag[i] = ln1p(mag[i] / zgomot)
        return mag to longArrayOf(1, 1, cadre.toLong(), bins.toLong())
    }
}

/**
 * Decodarea continua DeepRTTY (port al StreamingDecoder din rtty_stream.py): ferestre de 12 s
 * pornite la ~8 s pe grila cadrelor de iesire, imbinate cu OverlapMerger - fiecare caracter iese
 * o singura data. `preview()` decodeaza coada inca neconfirmata (textul gri din ecran).
 *
 * `inferenta` primeste [1, 1, T, 13] si intoarce log-probabilitatile [1, T', 33] (ONNX Runtime
 * pe telefon, sau JVM in teste). Nu e thread-safe: un singur fir de decodare.
 */
class DeepRttyStream(private val inferenta: (FloatArray, LongArray) -> Pair<FloatArray, LongArray>?) {

    private var buf = FloatArray(0)
    private var bufStart = 0L
    private var nextWindow = 0L
    private var lastWindowStart = -1L
    private var merger = OverlapMerger(DeepRttyConst.EDGE_GUARD.toLong(), DeepRttyConst.TOLERANCE)
    private var ultimulConfirmat = Long.MIN_VALUE

    val total: Long get() = bufStart + buf.size

    fun reseteaza() {
        buf = FloatArray(0)
        bufStart = 0L
        nextWindow = 0L
        lastWindowStart = -1L
        merger = OverlapMerger(DeepRttyConst.EDGE_GUARD.toLong(), DeepRttyConst.TOLERANCE)
        ultimulConfirmat = Long.MIN_VALUE
    }

    /** Adauga audio (3200 Hz); intoarce caracterele confirmate de ferestrele complete noi. */
    fun push(x: FloatArray, n: Int): List<CtcToken> {
        if (n > 0) {
            val nou = FloatArray(buf.size + n)
            buf.copyInto(nou)
            x.copyInto(nou, buf.size, 0, n)
            buf = nou
        }
        val confirmate = ArrayList<CtcToken>()
        while (nextWindow + DeepRttyConst.WINDOW <= total) {
            val off = (nextWindow - bufStart).toInt()
            val fereastra = buf.copyOfRange(off, off + DeepRttyConst.WINDOW)
            val tokeni = decodeaza(fereastra, nextWindow) ?: emptyList()
            val c = merger.push(nextWindow, DeepRttyConst.WINDOW.toLong(), tokeni)
            c.lastOrNull()?.let { ultimulConfirmat = it.sample }
            confirmate.addAll(c)
            lastWindowStart = nextWindow
            nextWindow += DeepRttyConst.HOP
        }
        // Pastram doar ce mai trebuie: de la inceputul ultimei ferestre (pentru preview) incolo.
        val pastreazaDe = max(bufStart, if (lastWindowStart >= 0) lastWindowStart else bufStart)
        if (pastreazaDe > bufStart) {
            buf = buf.copyOfRange((pastreazaDe - bufStart).toInt(), buf.size)
            bufStart = pastreazaDe
        }
        return confirmate
    }

    /**
     * Coada neconfirmata: decodeaza ultimele <= 12 s (completate in oglinda pana la 12 s, ca in
     * referinta) si intoarce caracterele de dupa textul deja confirmat.
     */
    fun preview(): List<CtcToken> {
        val start = max(if (lastWindowStart >= 0) lastWindowStart else bufStart, total - DeepRttyConst.WINDOW)
            .coerceAtLeast(bufStart)
        val lungime = (total - start).toInt()
        if (lungime < DeepRttyConst.SAMPLE_RATE) return emptyList()
        val audio = buf.copyOfRange((start - bufStart).toInt(), buf.size)
        val tokeni = decodeaza(oglinda(audio, DeepRttyConst.WINDOW), start) ?: return emptyList()
        val dupa = max(merger.committedUntil, ultimulConfirmat + DeepRttyConst.TOLERANCE)
        return tokeni.filter { it.sample > dupa && it.sample < total }
    }

    private fun decodeaza(audio: FloatArray, start: Long): List<CtcToken>? {
        val (date, dims) = DeepRttySpectrogram.calculeaza(audio) ?: return null
        val (iesire, forma) = inferenta(date, dims) ?: return null
        return CtcTokens.greedy(iesire, forma, DeepRttyConst.BLANK_INDEX).map {
            CtcToken(start + Math.round(it.frame * DeepRttyConst.OUTPUT_HOP_LENGTH), it.index, it.score)
        }
    }

    /** Completare "symmetric" (ca numpy.pad) pana la `lungime`. */
    private fun oglinda(a: FloatArray, lungime: Int): FloatArray {
        if (a.size >= lungime) return a
        val perioada = 2 * a.size
        return FloatArray(lungime) { i ->
            val k = i % perioada
            if (k < a.size) a[k] else a[perioada - 1 - k]
        }
    }
}
