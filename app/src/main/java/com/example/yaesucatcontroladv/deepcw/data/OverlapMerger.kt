package com.example.yaesucatcontroladv.deepcw.data

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Un caracter decodat de CTC, cu pozitia absoluta in stream (esantioane) si increderea
 * (probabilitatea maxima a "spike"-ului CTC).
 */
data class CtcToken(val sample: Long, val index: Int, val score: Float)

/**
 * Greedy CTC pe primul element de batch, pastrand pentru fiecare caracter pozitia (centrul
 * run-ului, in cadre, poate fi .5) si probabilitatea maxima. Aceeasi secventa de caractere
 * ca `CTCDecoder.decodeDetailed(...).plainText`.
 *
 * @return perechi (cadru, token) cu `sample` = 0; apelantul converteste cadrul in esantioane.
 */
object CtcTokens {
    data class FrameToken(val frame: Float, val index: Int, val score: Float)

    fun greedy(output: FloatArray, dims: LongArray, blankIndex: Int): List<FrameToken> {
        if (dims.size < 2) return emptyList()
        val classes = dims[dims.size - 1].toInt()
        val frames = dims[dims.size - 2].toInt()
        if (classes <= 0 || frames <= 0 || output.size < frames * classes) return emptyList()

        val tokens = ArrayList<FrameToken>()
        var runIndex = -1
        var runStart = 0
        var runPeak = Float.NEGATIVE_INFINITY

        fun close(stop: Int) {
            if (runIndex >= 0 && runIndex != blankIndex) {
                tokens.add(FrameToken((runStart + stop - 1) * 0.5f, runIndex, exp(runPeak)))
            }
        }

        for (t in 0 until frames) {
            val offset = t * classes
            var best = 0
            var bestValue = Float.NEGATIVE_INFINITY
            for (c in 0 until classes) {
                val value = output[offset + c]
                if (value > bestValue) {
                    bestValue = value
                    best = c
                }
            }
            if (best != runIndex) {
                close(t)
                runIndex = best
                runStart = t
                runPeak = bestValue
            } else if (bestValue > runPeak) {
                runPeak = bestValue
            }
        }
        close(frames)
        return tokens
    }
}

/**
 * Imbina tokenii unor ferestre suprapuse intr-un singur stream, fiecare caracter o singura
 * data (metoda din receptorul streaming DeepRTTY, adaptata pentru CW).
 *
 * Ferestrele se imping in ordinea crescatoare a inceputului. Din fiecare fereastra conteaza
 * doar partea "sigura", fara `edgeGuard` esantioane la marginile interioare (acolo un
 * caracter poate fi taiat). In zona sigura comuna a doua ferestre consecutive, tokenii sunt
 * aliniati in timp: dintr-o pereche se pastreaza cel cu increderea mai mare, iar un token
 * fara pereche e pastrat doar de fereastra careia ii apartine jumatatea respectiva a zonei.
 * Ce intoarce `push` e definitiv.
 */
class OverlapMerger(private val edgeGuard: Long, private val tolerance: Double) {
    private var pending: List<CtcToken> = emptyList()
    private var pendingReliableEnd = Double.NEGATIVE_INFINITY
    private var windows = 0

    /** Tot ce e inainte de acest esantion a fost deja returnat de `push`. */
    var committedUntil = Double.NEGATIVE_INFINITY
        private set

    fun push(windowStart: Long, windowLength: Long, windowTokens: List<CtcToken>): List<CtcToken> {
        val reliableStart = windowStart + if (windows > 0) edgeGuard else 0L
        val tokens = windowTokens.filter { it.sample >= reliableStart }
        windows++
        if (windows == 1) {
            pending = tokens
            pendingReliableEnd = (windowStart + windowLength - edgeGuard).toDouble()
            return emptyList()
        }

        var zoneStart = max(reliableStart.toDouble(), committedUntil)
        var zoneEnd = pendingReliableEnd
        if (zoneEnd < zoneStart) {
            // Fara suprapunere sigura: fiecare fereastra e crezuta pana la mijlocul golului.
            zoneStart = 0.5 * (zoneStart + zoneEnd)
            zoneEnd = zoneStart
        }
        val middle = 0.5 * (zoneStart + zoneEnd)
        fun inZone(token: CtcToken) = token.sample >= zoneStart && token.sample <= zoneEnd

        val committed = pending.filter { it.sample < zoneStart }.toMutableList()
        val merged = ArrayList<CtcToken>()
        for ((old, new) in align(pending.filter(::inZone), tokens.filter(::inZone))) {
            when {
                old != null && new != null -> merged.add(if (old.score >= new.score) old else new)
                old != null && old.sample < middle -> merged.add(old)
                new != null && new.sample >= middle -> merged.add(new)
            }
        }
        // Tokenii fara pereche dintre doua perechi pot veni in ordine a/b arbitrara.
        merged.sortBy { it.sample }
        committed.addAll(merged)

        committedUntil = zoneEnd
        pending = tokens.filter { it.sample > zoneEnd }
        pendingReliableEnd = (windowStart + windowLength - edgeGuard).toDouble()
        return committed
    }

    /**
     * Aliniere in timp a doua secvente scurte. Perechile sunt permise doar in `tolerance`
     * esantioane; caracterele egale costa mai putin decat substitutiile, si ambele mai putin
     * decat doi tokeni nepereche, deci un token parazit nu poate "fura" perechea unuia real.
     */
    private fun align(a: List<CtcToken>, b: List<CtcToken>): List<Pair<CtcToken?, CtcToken?>> {
        val n = a.size
        val m = b.size
        val gap = 1.0
        val cost = Array(n + 1) { DoubleArray(m + 1) { Double.POSITIVE_INFINITY } }
        val move = Array(n + 1) { IntArray(m + 1) } // 0 pereche, 1 doar a, 2 doar b
        for (i in 0..n) {
            cost[i][0] = i * gap
            move[i][0] = 1
        }
        for (j in 0..m) {
            cost[0][j] = j * gap
            move[0][j] = 2
        }
        for (i in 1..n) {
            for (j in 1..m) {
                var best = cost[i - 1][j] + gap
                var step = 1
                if (cost[i][j - 1] + gap < best) {
                    best = cost[i][j - 1] + gap
                    step = 2
                }
                val dt = abs(a[i - 1].sample - b[j - 1].sample).toDouble()
                if (dt <= tolerance) {
                    val pair = cost[i - 1][j - 1] + 0.5 * dt / tolerance +
                        if (a[i - 1].index == b[j - 1].index) 0.0 else 0.9
                    if (pair <= best) {
                        best = pair
                        step = 0
                    }
                }
                cost[i][j] = best
                move[i][j] = step
            }
        }
        val pairs = ArrayList<Pair<CtcToken?, CtcToken?>>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when (move[i][j]) {
                0 -> { pairs.add(a[i - 1] to b[j - 1]); i--; j-- }
                1 -> { pairs.add(a[i - 1] to null); i-- }
                else -> { pairs.add(null to b[j - 1]); j-- }
            }
        }
        pairs.reverse()
        return pairs
    }
}
