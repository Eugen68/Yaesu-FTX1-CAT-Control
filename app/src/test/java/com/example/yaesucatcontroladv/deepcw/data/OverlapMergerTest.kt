package com.example.yaesucatcontroladv.deepcw.data

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class OverlapMergerTest {

    @Test
    fun tokenInOverlapIsEmittedOnceFromTheMoreConfidentWindow() {
        val merger = OverlapMerger(edgeGuard = 100, tolerance = 50.0)
        assertEquals(emptyList<CtcToken>(), merger.push(0, 2000, listOf(CtcToken(500, 1, 0.9f), CtcToken(1500, 2, 0.6f))))
        val committed = merger.push(1000, 2000, listOf(CtcToken(1510, 3, 0.8f), CtcToken(2500, 4, 0.9f)))
        // Zona comuna e [1100, 1900]: 1500 si 1510 sunt acelasi caracter, castiga increderea mai mare.
        assertEquals(listOf(500L to 1, 1510L to 3), committed.map { it.sample to it.index })
        assertEquals(1900.0, merger.committedUntil, 0.0)
    }

    @Test
    fun unpairedTokenIsKeptOnlyByTheWindowOwningItsHalf() {
        val merger = OverlapMerger(edgeGuard = 100, tolerance = 50.0)
        merger.push(0, 2000, listOf(CtcToken(1200, 1, 0.9f), CtcToken(1700, 2, 0.9f)))
        val committed = merger.push(1000, 2000, listOf(CtcToken(1300, 3, 0.9f), CtcToken(1800, 4, 0.9f)))
        // Mijlocul zonei e 1500: prima fereastra detine 1200, a doua 1800.
        assertEquals(listOf(1, 4), committed.map { it.index })
    }

    @Test
    fun tokenInsideTheEdgeGuardOfTheNextWindowIsIgnored() {
        val merger = OverlapMerger(edgeGuard = 100, tolerance = 50.0)
        merger.push(0, 2000, listOf(CtcToken(1050, 1, 0.5f)))
        // 1020 e in primele 100 de esantioane ale ferestrei a doua (caracter posibil taiat).
        val committed = merger.push(1000, 2000, listOf(CtcToken(1020, 9, 1.0f)))
        assertEquals(listOf(1), committed.map { it.index })
    }

    @Test
    fun greedyCtcCollapsesRunsAndKeepsPeakScore() {
        val blank = 2
        // 6 cadre x 3 clase (log-probabilitati): A A blank B B A
        val probs = listOf(
            floatArrayOf(0.6f, 0.2f, 0.2f), floatArrayOf(0.9f, 0.05f, 0.05f), floatArrayOf(0.1f, 0.1f, 0.8f),
            floatArrayOf(0.1f, 0.7f, 0.2f), floatArrayOf(0.1f, 0.8f, 0.1f), floatArrayOf(0.5f, 0.3f, 0.2f)
        )
        val output = probs.flatMap { row -> row.map { ln(it) } }.toFloatArray()
        val tokens = CtcTokens.greedy(output, longArrayOf(1, 6, 3), blank)
        assertEquals(listOf(0, 1, 0), tokens.map { it.index })
        assertEquals(listOf(0.5f, 3.5f, 5f), tokens.map { it.frame })
        assertEquals(0.9f, tokens[0].score, 1e-5f)
        assertEquals(0.8f, tokens[1].score, 1e-5f)
    }
}
