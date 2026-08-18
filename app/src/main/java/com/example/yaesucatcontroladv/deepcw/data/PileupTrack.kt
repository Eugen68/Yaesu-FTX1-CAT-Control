package com.example.yaesucatcontroladv.deepcw.data

import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_CONFIRM_MS
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_FILTER_WIDTH_HZ
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_HOLD_MS
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_MATCH_HZ
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_MAX_PEAKS
import kotlin.math.abs
import kotlin.math.roundToInt

interface PileupCandidate {
    val frequency: Float
    val snrDb: Float
}

data class TrackedPileupCandidate(
    val id: Int,
    var frequency: Float,
    var snrDb: Float,
    val firstSeen: Long,
    var lastSeen: Long
)

/** O stație confirmata, raportata catre UI si catre loop-ul de decodare. */
data class PileupTrack(val id: Int, val frequency: Int)

/** Port fidel al `src/utils/pileupCandidates.ts`. */
object PileupCandidates {

    fun <T : PileupCandidate> selectStrongSeparated(
        candidates: List<T>,
        maxCandidates: Int = PILEUP_MAX_PEAKS,
        minSeparationHz: Float = PILEUP_FILTER_WIDTH_HZ
    ): List<T> {
        val selected = mutableListOf<T>()

        for (candidate in candidates) {
            if (selected.size >= maxCandidates) break
            if (selected.all { abs(it.frequency - candidate.frequency) >= minSeparationHz }) {
                selected.add(candidate)
            }
        }

        return selected
    }

    /**
     * Asociaza detectiile brute cu track-urile existente (smoothing 0.3/0.7 pe frecventa),
     * creeaza track-uri noi si elimina cele nevazute mai mult de PILEUP_HOLD_MS.
     * Muteaza si returneaza lista filtrata (ca in referinta).
     */
    fun updateTracked(
        tracked: MutableList<TrackedPileupCandidate>,
        rawCandidates: List<PileupCandidate>,
        now: Long,
        createTrackId: () -> Int
    ): MutableList<TrackedPileupCandidate> {
        val matched = mutableSetOf<Int>()

        for (candidate in rawCandidates) {
            var matchIndex = -1
            for (i in tracked.indices) {
                if (matched.contains(i)) continue
                if (abs(tracked[i].frequency - candidate.frequency) < PILEUP_MATCH_HZ) {
                    matchIndex = i
                    break
                }
            }

            if (matchIndex >= 0) {
                matched.add(matchIndex)
                val track = tracked[matchIndex]
                track.frequency = track.frequency * 0.3f + candidate.frequency * 0.7f
                track.snrDb = candidate.snrDb
                track.lastSeen = now
                continue
            }

            tracked.add(
                TrackedPileupCandidate(
                    id = createTrackId(),
                    frequency = candidate.frequency,
                    snrDb = candidate.snrDb,
                    firstSeen = now,
                    lastSeen = now
                )
            )
        }

        return tracked.filterTo(mutableListOf()) { now - it.lastSeen < PILEUP_HOLD_MS }
    }

    fun getConfirmed(tracked: List<TrackedPileupCandidate>, now: Long): List<PileupTrack> {
        val confirmed = tracked
            .filter { now - it.firstSeen >= PILEUP_CONFIRM_MS }
            .sortedByDescending { it.snrDb }
            .map { DetectedTrack(it.id, it.frequency, it.snrDb) }

        return selectStrongSeparated(confirmed)
            .map { PileupTrack(it.id, it.frequency.roundToInt()) }
            .sortedBy { it.frequency }
    }

    fun tracksChanged(oldTracks: List<PileupTrack>, newTracks: List<PileupTrack>): Boolean {
        if (oldTracks.size != newTracks.size) return true

        for (index in oldTracks.indices) {
            if (oldTracks[index].id != newTracks[index].id) return true
            if (abs(oldTracks[index].frequency - newTracks[index].frequency) >= PILEUP_MATCH_HZ) {
                return true
            }
        }

        return false
    }

    private data class DetectedTrack(
        val id: Int,
        override val frequency: Float,
        override val snrDb: Float
    ) : PileupCandidate
}
