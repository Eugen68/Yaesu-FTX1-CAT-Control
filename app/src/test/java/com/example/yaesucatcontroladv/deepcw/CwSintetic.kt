package com.example.yaesucatcontroladv.deepcw

import com.example.yaesucatcontroladv.deepcw.dsp.Constants
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CW sintetic pentru teste: text Morse cu fronturi ridicat-cosinus de 5 ms, la 9600 Hz,
 * plus zgomot gaussian alb cu SNR raportat la 2500 Hz si la puterea medie a intregii
 * inregistrari (conventia benchmark-ului DeepCW).
 */
object CwSintetic {

    private val morse = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.",
        'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.",
        'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-", 'U' to "..-",
        'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--", 'Z' to "--..", '0' to "-----",
        '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....",
        '7' to "--...", '8' to "---..", '9' to "----.", '/' to "-..-.", '?' to "..--..", '.' to ".-.-.-",
        ',' to "--..--"
    )

    /** Audio-ul si, pentru fiecare caracter ne-spatiu, esantionul din mijlocul lui. */
    class Rezultat(val audio: FloatArray, val centreCaractere: List<Pair<Char, Int>>)

    fun genereaza(text: String, wpm: Double, tonHz: Double, snrDb: Double, seed: Long): Rezultat {
        val fs = Constants.SAMPLE_RATE
        val dit = 1.2 / wpm
        val segmente = ArrayList<Triple<Boolean, Double, Char?>>() // (on, durata s, caracterul)
        segmente.add(Triple(false, 1.0, null))
        for ((wi, cuv) in text.trim().split(Regex("\\s+")).withIndex()) {
            if (wi > 0) segmente.add(Triple(false, 7 * dit, null))
            for ((ci, ch) in cuv.withIndex()) {
                if (ci > 0) segmente.add(Triple(false, 3 * dit, null))
                val cod = morse[ch] ?: continue
                for ((ei, el) in cod.withIndex()) {
                    if (ei > 0) segmente.add(Triple(false, dit, ch))
                    segmente.add(Triple(true, if (el == '.') dit else 3 * dit, ch))
                }
            }
        }
        segmente.add(Triple(false, 1.0, null))

        val n = segmente.sumOf { (it.second * fs).toInt() }
        val cheie = FloatArray(n)
        val centre = ArrayList<Pair<Char, Int>>()
        var pos = 0
        var startCaracter = -1
        var caracterCurent: Char? = null
        for ((on, d, ch) in segmente) {
            val m = (d * fs).toInt()
            if (ch != caracterCurent) {
                if (caracterCurent != null) centre.add(caracterCurent to (startCaracter + pos) / 2)
                caracterCurent = ch
                startCaracter = pos
            }
            if (on) for (i in 0 until m) cheie[pos + i] = 1f
            pos += m
        }

        val r = (0.005 * fs).toInt()
        val nucleu = FloatArray(2 * r + 1) { (0.5 - 0.5 * cos(2 * PI * (it + 0.5) / (2 * r + 1))).toFloat() }
        val suma = nucleu.sum()
        val amplitudine = 0.3
        val semnal = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0f
            for (j in -r..r) {
                val idx = i + j
                if (idx in 0 until n) acc += cheie[idx] * nucleu[j + r]
            }
            semnal[i] = (amplitudine * acc / suma * sin(2 * PI * tonHz * i / fs)).toFloat()
        }

        if (!snrDb.isInfinite()) {
            var putere = 0.0
            for (v in semnal) putere += v.toDouble() * v
            putere /= n
            val sigma = sqrt(putere / 10.0.pow(snrDb / 10) * (fs / 2.0) / 2500.0)
            val rnd = Random(seed)
            for (i in 0 until n) semnal[i] += (rnd.nextGaussian() * sigma).toFloat()
        }
        return Rezultat(semnal, centre)
    }

    fun levenshtein(a: String, b: String): Int {
        var p = IntArray(b.length + 1) { it }
        var c = IntArray(b.length + 1)
        for (i in 1..a.length) {
            c[0] = i
            for (j in 1..b.length) c[j] = minOf(c[j - 1] + 1, p[j] + 1, p[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            val t = p; p = c; c = t
        }
        return p[b.length]
    }

    fun normalizeaza(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
