package com.example.yaesucatcontroladv.deepcw

import com.example.yaesucatcontroladv.deepcw.data.DetectedStation
import com.example.yaesucatcontroladv.deepcw.data.DormantStations
import com.example.yaesucatcontroladv.deepcw.data.PileupCandidates
import com.example.yaesucatcontroladv.deepcw.data.TrackedPileupCandidate
import com.example.yaesucatcontroladv.deepcw.dsp.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regulile de identitate ale statiilor din pileup: o statie care tace (asculta corespondentul)
 * isi pastreaza id-ul - deci randul si textul - PILEUP_ROW_KEEP_MS, si il recapata cand revine.
 */
class PileupRowsTest {

    /** Simuleaza bucla de detectie: o detectie la fiecare secunda. */
    private class Bucla {
        val tracked = mutableListOf<TrackedPileupCandidate>()
        val dormant = DormantStations()
        var nextId = 1
        var now = 0L

        fun pas(vararg frecvente: Float): List<Int> {
            val raw = frecvente.map { DetectedStation(it, 30f) }
            val updated = PileupCandidates.updateTracked(tracked, raw, now) { f ->
                dormant.reacquire(f) ?: nextId++
            }
            dormant.update(tracked, updated, now)
            tracked.clear()
            tracked.addAll(updated)
            now += 1000
            return PileupCandidates.getConfirmed(tracked, now).map { it.id }
        }

        fun ruleaza(secunde: Int, vararg frecvente: Float): List<Int> {
            var ids = emptyList<Int>()
            repeat(secunde) { ids = pas(*frecvente) }
            return ids
        }
    }

    @Test
    fun statiaCareRevineIsiPastreazaId() {
        val b = Bucla()
        val id = b.ruleaza(20, 700f).single()
        assertEquals("tace 60 s", emptyList<Int>(), b.ruleaza(60))
        // revine putin mutata (alt operator la tastatura, drift): acelasi rand
        assertEquals(id, b.ruleaza(5, 712f).single())
    }

    @Test
    fun dupaTreiMinuteEOStatieNoua() {
        val b = Bucla()
        val id = b.ruleaza(20, 700f).single()
        b.ruleaza((Constants.PILEUP_ROW_KEEP_MS / 1000).toInt() + 10)
        assertNotEquals(id, b.ruleaza(5, 700f).single())
    }

    @Test
    fun statiaDepartataNuPreiaRandul() {
        val b = Bucla()
        val id = b.ruleaza(20, 700f).single()
        b.ruleaza(30)
        val alta = b.ruleaza(5, 760f).single()
        assertNotEquals(id, alta)
        // iar cand revine prima, isi recapata id-ul
        assertEquals(setOf(id, alta), b.ruleaza(5, 700f, 760f).toSet())
    }

    @Test
    fun oSclipireNuFuraRandul() {
        val b = Bucla()
        val id = b.ruleaza(20, 700f).single()
        b.ruleaza(30)
        // o detectie izolata langa statia care tace (neconfirmata), apoi nimic
        b.pas(705f)
        b.ruleaza(10)
        assertEquals(id, b.ruleaza(5, 700f).single())
    }

    @Test
    fun zeroBeatMutaSiStatiileCareTac() {
        val b = Bucla()
        val id = b.ruleaza(20, 900f).single()
        b.ruleaza(30)
        b.tracked.forEach { it.frequency += -300 }
        b.dormant.shift(-300)
        assertEquals(id, b.ruleaza(5, 600f).single())
    }
}
