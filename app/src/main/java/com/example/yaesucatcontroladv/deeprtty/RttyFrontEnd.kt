package com.example.yaesucatcontroladv.deeprtty

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Aduce audio-ul de receptie RTTY (48 kHz, real) la intrarea DeepRTTY: 3200 Hz, cu semnalul
 * centrat pe 800 Hz si Mark JOS (715 Hz) / Space SUS (885 Hz).
 *
 * Mixer complex: semnalul e coborat in banda de baza (centrul RTTY -> 0 Hz), filtrat trece-jos
 * si decimat 48000 -> 3200 intr-un singur FIR (se calculeaza doar esantioanele pastrate), apoi
 * urcat pe 800 Hz. Imaginea frecventelor negative ajunge la -2 x centru (de ordinul -4 kHz) si e
 * taiata de acelasi filtru - fara zgomotul in plus pe care l-ar aduce un simplu mixaj real.
 * In REV (Mark sus) banda de baza se conjuga, adica spectrul se oglindeste in jurul centrului.
 *
 * Fara dependente Android. Nu e thread-safe: o instanta pe firul audio.
 */
class RttyFrontEnd(private val fsIn: Int = 48000) {

    private val decimare = fsIn / DeepRttyConst.SAMPLE_RATE
    private val h: DoubleArray
    private val n: Int
    private val bufRe: DoubleArray
    private val bufIm: DoubleArray
    private var poz = 0
    private var contor = 0

    // oscilatorul de coborare (fazor e^{-j phi}, rotit recursiv) si cel de urcare pe 800 Hz
    private var osRe = 1.0
    private var osIm = 0.0
    private var urcRe = 1.0
    private var urcIm = 0.0
    private val urcPasRe = cos(2 * PI * DeepRttyConst.CENTRU_HZ / DeepRttyConst.SAMPLE_RATE)
    private val urcPasIm = sin(2 * PI * DeepRttyConst.CENTRU_HZ / DeepRttyConst.SAMPLE_RATE)

    init {
        require(fsIn % DeepRttyConst.SAMPLE_RATE == 0) { "Rata de intrare trebuie sa fie multiplu de 3200 Hz" }
        // Trece-jos ~1000 Hz (banda modelului e +-150 Hz in jurul centrului), tranzitie pana la
        // Nyquist-ul de iesire (1600 Hz); Hamming, ~3.3 * fs / tranzitie coeficienti.
        val fc = 1000.0 / fsIn
        n = (3.3 * fsIn / 600.0).toInt() or 1
        h = DoubleArray(n) {
            val m = it - n / 2
            val sinc = if (m == 0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
            sinc * (0.54 - 0.46 * cos(2 * PI * it / (n - 1)))
        }
        bufRe = DoubleArray(n)
        bufIm = DoubleArray(n)
    }

    fun reseteaza() {
        bufRe.fill(0.0)
        bufIm.fill(0.0)
        poz = 0
        contor = 0
    }

    /**
     * Proceseaza `count` esantioane PCM 16-bit; scrie esantioanele de 3200 Hz in `out` (de la
     * `outOffset`) si intoarce cate a scris (cel mult count / 15 + 1).
     *
     * @param centruHz centrul semnalului RTTY in audio-ul de intrare (Mark +/- 85 Hz)
     * @param reverse  true daca in audio-ul de intrare Mark e SUS (modul REV al ecranului)
     */
    fun proceseaza(pcm: ShortArray, count: Int, centruHz: Double, reverse: Boolean,
                   out: FloatArray, outOffset: Int): Int {
        val w = 2 * PI * centruHz / fsIn
        val pasRe = cos(w)
        val pasIm = -sin(w)
        var scrise = 0
        for (i in 0 until count) {
            val x = pcm[i] / 32768.0
            bufRe[poz] = x * osRe
            bufIm[poz] = x * osIm
            poz = (poz + 1) % n
            val nr = osRe * pasRe - osIm * pasIm
            osIm = osRe * pasIm + osIm * pasRe
            osRe = nr

            if (++contor < decimare) continue
            contor = 0
            var yr = 0.0
            var yi = 0.0
            var idx = poz // cel mai vechi esantion
            for (k in 0 until n) {
                yr += h[k] * bufRe[idx]
                yi += h[k] * bufIm[idx]
                if (++idx == n) idx = 0
            }
            if (reverse) yi = -yi
            out[outOffset + scrise++] = (yr * urcRe - yi * urcIm).toFloat()
            val ur = urcRe * urcPasRe - urcIm * urcPasIm
            urcIm = urcRe * urcPasIm + urcIm * urcPasRe
            urcRe = ur
        }
        // renormalizare anti-drift a celor doi fazori
        val n1 = sqrt(osRe * osRe + osIm * osIm)
        osRe /= n1; osIm /= n1
        val n2 = sqrt(urcRe * urcRe + urcIm * urcIm)
        urcRe /= n2; urcIm /= n2
        return scrise
    }
}
