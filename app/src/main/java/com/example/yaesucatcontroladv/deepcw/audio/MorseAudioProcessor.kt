package com.example.yaesucatcontroladv.deepcw.audio

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.yaesucatcontroladv.deepcw.data.CtcToken
import com.example.yaesucatcontroladv.deepcw.data.CwDetector
import com.example.yaesucatcontroladv.deepcw.data.MorseDecoder
import com.example.yaesucatcontroladv.deepcw.data.MorseVocabularies
import com.example.yaesucatcontroladv.deepcw.data.OverlapMerger
import com.example.yaesucatcontroladv.deepcw.data.PileupCandidates
import com.example.yaesucatcontroladv.deepcw.data.PileupTrack
import com.example.yaesucatcontroladv.deepcw.data.StreamingTranscript
import com.example.yaesucatcontroladv.deepcw.data.TrackedPileupCandidate
import com.example.yaesucatcontroladv.deepcw.data.WordSpaceSpan
import com.example.yaesucatcontroladv.deepcw.dsp.Constants
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.BIN_RESOLUTION
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.FFT_LENGTH
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.HOP_LENGTH
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.OUTPUT_HOP_LENGTH
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_LOCK_SNR_THRESHOLD_DB
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_MAX_FREQ_HZ
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_MIN_FREQ_HZ
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_RELEASE_SNR_THRESHOLD_DB
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_TEXT_HOLD_MS
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.PILEUP_WINDOW_S
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.SAMPLE_RATE
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.TOTAL_BINS
import com.example.yaesucatcontroladv.deepcw.dsp.STFT
import com.example.yaesucatcontroladv.deepcw.denoise.CwDenoiser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class DecoderMode { NORMAL, PILEUP }

/**
 * Cum transforma modul NORMAL bufferul audio in text confirmat:
 * - WORD_GAP: re-decodeaza tot bufferul in asteptare si confirma textul la o pauza de
 *   cuvant (port al `useStreamingDecode.ts`).
 * - OVERLAP: ferestre fixe de 12 s, pornite la fiecare 8 s, imbinate dupa pozitia in timp
 *   a caracterelor (metoda receptorului streaming DeepRTTY). Textul gri ramane live.
 */
enum class StreamingMethod { WORD_GAP, OVERLAP }

/**
 * Disponibilitatea celor doua moduri, in functie de modelele ONNX care s-au incarcat.
 * `normalAvailable` depinde de model_en_2026-10.onnx, `pileupAvailable` de
 * model_en_narrow_2026-10.onnx (gasirea statiilor in pileup e DSP, fara model).
 */
data class DecoderAvailability(
    val normalAvailable: Boolean,
    val pileupAvailable: Boolean,
    val denoiseAvailable: Boolean = true
)

/**
 * Hub-ul de procesare audio al decodorului CW AI.
 *
 * Echivalentul combinat al hook-urilor din proiectul de referinta:
 * - `useAudioProcessing`  -> buffer ring pentru pileup + alimentarea waterfall-ului
 * - `useStreamingDecode`  -> buffer glisant + decodare streaming incrementala (mod Normal)
 * - `usePileupDetection`  -> loop de detectie + urmarire de track-uri (mod Pileup)
 * - `usePileupDecode`     -> loop de decodare narrow per track (mod Pileup)
 *
 * Nu exista auto-peak-detection: in modul Normal frecventa se alege manual (tap pe
 * waterfall / butoane de acordaj fina), exact ca in referinta.
 */
class MorseAudioProcessor(private val context: Context) {

    // ---- Callback-uri prietenoase cu Java ----
    interface SpectrumListener {
        fun onSpectrumReady(row: FloatArray)
    }

    interface WaterfallRangeListener {
        fun onWaterfallRangeChanged(minFreqHz: Int, maxFreqHz: Int)
    }

    interface StreamingListener {
        fun onSegmentsUpdated(segments: List<String>, pendingText: String)
    }

    interface PileupListener {
        fun onTracksUpdated(tracks: List<PileupTrack>)
        fun onTextsUpdated(texts: Map<Int, String>)
    }

    interface ModeListener {
        fun onModeChanged(mode: DecoderMode)
    }

    interface AvailabilityListener {
        fun onAvailabilityChanged(availability: DecoderAvailability)
    }

    interface ErrorListener {
        fun onError(message: String)
    }

    interface StatusListener {
        fun onStatus(message: String)
    }

    var spectrumListener: SpectrumListener? = null
    var waterfallRangeListener: WaterfallRangeListener? = null
    var streamingListener: StreamingListener? = null
    var pileupListener: PileupListener? = null
    var modeListener: ModeListener? = null
    var availabilityListener: AvailabilityListener? = null
    var errorListener: ErrorListener? = null
    var statusListener: StatusListener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---- Stare de configurare (setata din UI) ----
    @Volatile
    var mode: DecoderMode = DecoderMode.NORMAL
        private set

    @Volatile
    var filterFreqHz: Int = Constants.DEFAULT_DECODE_CENTER_FREQ_HZ
        private set

    @Volatile
    var filterWidthHz: Int = Constants.DEFAULT_FILTER_WIDTH_HZ
        private set

    @Volatile
    var streamingMethod: StreamingMethod = StreamingMethod.WORD_GAP
        private set

    @Volatile
    private var normalAvailable = true

    @Volatile
    private var pileupAvailable = true

    @Volatile
    private var denoiseAvailable = true

    @Volatile
    private var isRunning = false

    private val cwDenoiser = CwDenoiser()

    @Volatile
    var denoiseEnabled: Boolean = false
        private set

    @Volatile
    var wetLevel: Float = 1.0f
        private set

    @Volatile
    var outputGain: Float = 1.0f
        private set

    private val segments = mutableListOf<String>()
    private val segmentsMutex = Mutex()

    // ---- Buffere audio ----
    private val pendingSamples = PendingSamples()
    private val pileupRing = RingBuffer(PILEUP_WINDOW_S * SAMPLE_RATE)
    private val toneHistory = RingBuffer(TONE_HISTORY_S * SAMPLE_RATE)
    private val waterfallChannel = Channel<FloatArray>(
        capacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // ---- Modele ----
    private val mainDecoder = MorseDecoder(context, MODEL_EN)
    private var narrowDecoder: MorseDecoder? = null
    private var cwDetector: CwDetector? = null

    private var waterfallJob: Job? = null
    private val modeJobs = mutableListOf<Job>()
    private val loopsMutex = Mutex()

    private val audioRecorder = AudioRecorder(context) { chunk ->
        var processedChunk = chunk
        if (denoiseEnabled && cwDenoiser.isAvailable) {
            processedChunk = cwDenoiser.process(chunk, wetLevel)
            val g = outputGain
            if (g != 1f && processedChunk.isNotEmpty()) {
                for (i in processedChunk.indices) {
                    processedChunk[i] = (processedChunk[i] * g).coerceIn(-1f, 1f)
                }
            }
        }

        if (processedChunk.isNotEmpty()) {
            when (mode) {
                DecoderMode.NORMAL -> pendingSamples.append(processedChunk)
                DecoderMode.PILEUP -> pileupRing.write(processedChunk)
            }
            toneHistory.write(processedChunk)
            waterfallChannel.trySend(processedChunk)
        }
    }

    // ============================================================
    // API public
    // ============================================================

    fun getAvailability(): DecoderAvailability = DecoderAvailability(normalAvailable, pileupAvailable, denoiseAvailable)

    /**
     * Snapshot al bufferului de audio brut in asteptare (mod NORMAL) - pentru butonul
     * "Detecteaza tonul" (vezi ToneDetector.kt), care are nevoie de audio ne-decodat, nu de
     * textul deja transcris. Aceeasi sursa pe care o citeste si streamingLoop la fiecare tur.
     */
    fun snapshotPendingAudio(): FloatArray = pendingSamples.snapshot().first

    /**
     * Ultimele TONE_HISTORY_S secunde de audio, in ordine cronologica - sursa butonului
     * "Detecteaza tonul". Spre deosebire de bufferul "pending" (golit la fiecare cuvant
     * confirmat si la fiecare reacordare), istoricul asta nu e afectat de decodare, deci
     * contine si semnalul de dinaintea ultimei pauze a stației.
     */
    fun snapshotRecentAudio(): FloatArray = toneHistory.snapshotFilled()

    fun getWaterfallMinFreqHz(): Int =
        if (mode == DecoderMode.PILEUP) PILEUP_MIN_FREQ_HZ else Constants.MIN_FREQ_HZ

    fun getWaterfallMaxFreqHz(): Int =
        if (mode == DecoderMode.PILEUP) PILEUP_MAX_FREQ_HZ else Constants.MAX_FREQ_HZ

    fun setFilterFreq(freqHz: Int) {
        val clamped = freqHz.coerceIn(Constants.MIN_FREQ_HZ, Constants.MAX_FREQ_HZ)
        if (clamped == filterFreqHz) return
        filterFreqHz = clamped
        // Schimbarea acordajului invalideaza transcriptul in curs (ca in referinta, unde
        // semnatura de setari de decodare reseteaza bufferul si textul "pending").
        if (isRunning && mode == DecoderMode.NORMAL) restartModeLoops()
    }

    fun setFilterWidth(widthHz: Int) {
        if (widthHz == filterWidthHz) return
        filterWidthHz = widthHz
        if (isRunning && mode == DecoderMode.NORMAL) restartModeLoops()
    }

    fun useStreamingMethod(method: StreamingMethod) {
        if (method == streamingMethod) return
        streamingMethod = method
        if (isRunning && mode == DecoderMode.NORMAL) restartModeLoops()
    }

    fun setWetLevel(wet: Float) {
        wetLevel = wet.coerceIn(0f, 1f)
    }

    fun setOutputGain(gain: Float) {
        outputGain = gain.coerceIn(0f, 2f)
    }

    fun setDenoiseEnabled(enabled: Boolean) {
        if (denoiseEnabled == enabled) return
        denoiseEnabled = enabled

        if (isRunning) {
            scope.launch {
                if (enabled) {
                    val ok = withContext(Dispatchers.IO) { cwDenoiser.initialize() }
                    if (ok) {
                        cwDenoiser.reset()
                        reportStatus("CW Denoiser ACTIVATED")
                    } else {
                        denoiseEnabled = false
                        denoiseAvailable = false
                        notifyAvailability()
                        reportStatus("Noise reduction is not available on this device")
                    }
                } else {
                    cwDenoiser.reset()
                    reportStatus("CW Denoiser DEACTIVATED")
                }
            }
        }
    }

    fun setMode(newMode: DecoderMode) {
        if (newMode == mode) return
        if (newMode == DecoderMode.PILEUP && !pileupAvailable) {
            reportStatus("PILEUP model unavailable (missing or invalid)")
            return
        }
        mode = newMode
        clearData() // Curățăm starea la schimbarea modului
        notifyWaterfallRange()
        modeListener?.onModeChanged(newMode)
        
        if (isRunning) {
            if (newMode == DecoderMode.NORMAL) {
                reportStatus("Switched to NORMAL ($MODEL_EN OK)")
            } else {
                reportStatus("Switched to PILEUP ($MODEL_EN_NARROW OK)")
            }
            restartModeLoops()
        }
    }

    /**
     * Curăță istoricul decodării și bufferele audio.
     */
    fun clearData() {
        scope.launch {
            segmentsMutex.withLock {
                segments.clear()
            }
            pendingSamples.clear()
            pileupRing.clear()
            notifySegments(emptyList(), "")
            pileupListener?.onTextsUpdated(emptyMap())
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            loopsMutex.withLock {
                val error = withContext(Dispatchers.IO) { mainDecoder.initialize() }
                normalAvailable = mainDecoder.isAvailable
                notifyAvailability()

                if (!normalAvailable) {
                    reportError(
                        "Main CW model could not be loaded: ${error ?: "unknown error"}"
                    )
                    return@withLock
                }
                
                reportStatus("Model NORMAL ($MODEL_EN) OK")

                if (denoiseEnabled) {
                    val ok = withContext(Dispatchers.IO) { cwDenoiser.initialize() }
                    if (ok) {
                        cwDenoiser.reset()
                    } else {
                        denoiseEnabled = false
                        denoiseAvailable = false
                        // notifyAvailability() de mai sus a rulat deja (inainte de init-ul
                        // denoiser-ului, care are nevoie de modelul principal validat), deci
                        // UI-ul trebuie notificat din nou ca sa isi corecteze toggle-ul.
                        notifyAvailability()
                        reportStatus("Noise reduction is not available on this device")
                    }
                }

                if (!audioRecorder.start()) {
                    reportError("Audio capture error. Check the microphone permission.")
                    return@withLock
                }

                notifyWaterfallRange()
                waterfallJob = scope.launch { waterfallLoop() }
                startModeLoopsLocked()
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        audioRecorder.stop()

        // Oprirea efectiva a buclelor se face asincron: o inferenta ONNX in curs nu e
        // intreruptibila, iar un join sincron ar bloca thread-ul UI pentru ~1 secunda.
        scope.launch {
            loopsMutex.withLock {
                cancelLoopsLocked()

                pendingSamples.clear()
                pileupRing.clear()
                toneHistory.clear()

                mainDecoder.release()
                cwDenoiser.release()
                narrowDecoder?.release()
                narrowDecoder = null
                cwDetector = null
            }
        }
    }

    // ============================================================
    // Loop-uri
    // ============================================================

    private fun startModeLoopsLocked() {
        modeJobs.clear()
        when (mode) {
            DecoderMode.NORMAL -> modeJobs.add(
                scope.launch {
                    when (streamingMethod) {
                        StreamingMethod.WORD_GAP -> streamingLoop()
                        StreamingMethod.OVERLAP -> overlapStreamingLoop()
                    }
                }
            )
            DecoderMode.PILEUP -> {
                val tracksState = PileupTracksState()
                modeJobs.add(scope.launch { pileupDetectLoop(tracksState) })
                modeJobs.add(scope.launch { pileupDecodeLoop(tracksState) })
            }
        }
    }

    private suspend fun cancelLoopsLocked() {
        val jobs = modeJobs.toList()
        modeJobs.clear()
        jobs.forEach { runCatching { it.cancelAndJoin() } }
        waterfallJob?.let { runCatching { it.cancelAndJoin() } }
        waterfallJob = null
    }

    private fun restartModeLoops() {
        scope.launch {
            loopsMutex.withLock {
                val jobs = modeJobs.toList()
                modeJobs.clear()
                jobs.forEach { runCatching { it.cancelAndJoin() } }
                pendingSamples.clear()
                pileupRing.clear()
                if (isRunning) startModeLoopsLocked()
            }
        }
    }

    /** Alimenteaza waterfall-ul; ruleaza pe un singur consumator (STFT nu e thread-safe). */
    private suspend fun waterfallLoop() {
        val stft = STFT(FFT_LENGTH, HOP_LENGTH)

        for (chunk in waterfallChannel) {
            if (!isRunning) return

            val minBin = max(0, floor(getWaterfallMinFreqHz() / BIN_RESOLUTION).toInt())
            val maxBin = min(TOTAL_BINS - 1, ceil(getWaterfallMaxFreqHz() / BIN_RESOLUTION).toInt())
            val binCount = maxBin - minBin + 1
            if (binCount <= 0) continue

            stft.forEachSpectrum(chunk) { complexFrame, _ ->
                val row = FloatArray(binCount)
                for (bin in minBin..maxBin) {
                    val real = complexFrame[bin * 2]
                    val imag = complexFrame[bin * 2 + 1]
                    row[bin - minBin] = sqrt(real * real + imag * imag)
                }
                spectrumListener?.onSpectrumReady(row)
            }
        }
    }

    /**
     * Decodare streaming incrementala (mod Normal).
     * Port al buclei din `useStreamingDecode.ts`: acumuleaza audio, decodeaza tot bufferul
     * "pending", si "commit-uieste" text doar pana la ultima pauza de cuvant sigura.
     */
    private suspend fun streamingLoop() {
        var lastVersion = -1L
        var lastInferenceAt = 0L

        segmentsMutex.withLock {
            notifySegments(segments, "")
        }

        while (coroutineScopeActive()) {
            val (snapshot, version) = pendingSamples.snapshot()

            if (version == lastVersion) {
                delay(POLL_INTERVAL_MS)
                continue
            }
            lastVersion = version

            if (snapshot.size < MIN_PENDING_SAMPLES) {
                segmentsMutex.withLock {
                    notifySegments(segments, "")
                }
                continue
            }

            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastInferenceAt
            if (lastInferenceAt > 0L && elapsed < ANALYSIS_MIN_INTERVAL_MS) {
                delay(ANALYSIS_MIN_INTERVAL_MS - elapsed)
                if (!coroutineScopeActive()) return
            }

            val analysisLength = min(snapshot.size, MAX_SEGMENT_SAMPLES)
            val analysisAudio = snapshot.copyOfRange(0, analysisLength)
            lastInferenceAt = SystemClock.elapsedRealtime()

            val analysisResult = mainDecoder.decodeDetailed(
                analysisAudio,
                filterFreqHz.toFloat(),
                filterWidthHz.toFloat()
            ) ?: continue
            if (!coroutineScopeActive()) return

            segmentsMutex.withLock {
                notifySegments(segments, StreamingTranscript.normalize(analysisResult).text)
            }

            val reachedMaxSegment = snapshot.size >= MAX_SEGMENT_SAMPLES
            val splitPoint = getConfirmedSplitPoint(analysisResult.wordSpaceSpans, analysisLength, false)
                ?: if (reachedMaxSegment) {
                    getConfirmedSplitPoint(analysisResult.wordSpaceSpans, analysisLength, true)
                } else {
                    null
                }

            val confirmedLength = splitPoint?.sample
                ?: if (reachedMaxSegment) MAX_SEGMENT_SAMPLES else null

            if (confirmedLength == null || confirmedLength < MIN_CONFIRMED_SAMPLES) continue

            val confirmedLane = if (splitPoint != null) {
                StreamingTranscript.normalize(
                    StreamingTranscript.trimToFrame(analysisResult, splitPoint.endFrame)
                )
            } else {
                val confirmedAudio = analysisAudio.copyOfRange(0, min(confirmedLength, analysisAudio.size))
                val confirmedResult = mainDecoder.decodeDetailed(
                    confirmedAudio,
                    filterFreqHz.toFloat(),
                    filterWidthHz.toFloat()
                ) ?: continue
                StreamingTranscript.normalize(confirmedResult)
            }
            if (!coroutineScopeActive()) return

            segmentsMutex.withLock {
                if (confirmedLane.text.isNotEmpty()) segments.add(confirmedLane.text)
                pendingSamples.dropLeading(confirmedLength)
                notifySegments(segments, "")
            }
        }
    }

    /**
     * Decodare streaming cu ferestre suprapuse (mod Normal, StreamingMethod.OVERLAP).
     *
     * Fiecare fereastra de OVERLAP_WINDOW_S secunde e decodata o singura data, iar
     * caracterele ei (cu pozitie absoluta si incredere) sunt imbinate cu fereastra
     * anterioara de `OverlapMerger` - un caracter taiat de marginea unei ferestre e citit din
     * fereastra care l-a vazut intreg. Textul confirmat e trimis doar pana la ultima pauza de
     * cuvant (UI-ul pune spatiu intre segmente), restul cuvantului ramane in textul gri.
     * Textul gri e re-decodat cel mult o data pe secunda, de la inceputul ultimei ferestre.
     */
    private suspend fun overlapStreamingLoop() {
        val window = OVERLAP_WINDOW_SAMPLES.toLong()
        val hop = OVERLAP_HOP_SAMPLES.toLong()
        val tolerance = OVERLAP_TOLERANCE_SAMPLES.toDouble()

        var epoch = -1L
        var merger = OverlapMerger(OVERLAP_EDGE_GUARD_SAMPLES.toLong(), tolerance)
        var nextWindow = 0L
        var lastWindowStart = -1L
        val carry = ArrayList<CtcToken>() // confirmate, dar dupa ultima pauza de cuvant
        var lastCommittedSample = Long.MIN_VALUE
        var lastVersion = -1L
        var lastPreviewAt = 0L

        segmentsMutex.withLock {
            notifySegments(segments, "")
        }

        while (coroutineScopeActive()) {
            val snapshot = pendingSamples.positionedSnapshot()
            if (snapshot.epoch != epoch) {
                // Buffer golit (CLEAR): stream nou, fara legatura in timp cu cel vechi.
                epoch = snapshot.epoch
                merger = OverlapMerger(OVERLAP_EDGE_GUARD_SAMPLES.toLong(), tolerance)
                nextWindow = snapshot.start
                lastWindowStart = -1L
                carry.clear()
                lastCommittedSample = Long.MIN_VALUE
            }
            if (snapshot.version == lastVersion) {
                delay(POLL_INTERVAL_MS)
                continue
            }
            lastVersion = snapshot.version
            val end = snapshot.start + snapshot.data.size

            var cutWindow = false
            while (nextWindow + window <= end) {
                val offset = (nextWindow - snapshot.start).toInt()
                val audio = snapshot.data.copyOfRange(offset, offset + window.toInt())
                val tokens = mainDecoder.decodeTokens(audio, filterFreqHz.toFloat(), filterWidthHz.toFloat())
                    ?: emptyList()
                if (!coroutineScopeActive()) return
                val windowStart = nextWindow
                val committed = merger.push(windowStart, window, tokens.map { it.copy(sample = it.sample + windowStart) })
                lastWindowStart = windowStart
                nextWindow += hop
                cutWindow = true

                carry.addAll(committed)
                committed.lastOrNull()?.let { lastCommittedSample = it.sample }
                val confirmedText = takeConfirmedWords(carry)
                if (confirmedText.isNotEmpty()) {
                    segmentsMutex.withLock { segments.add(confirmedText) }
                }
            }
            if (cutWindow) {
                // Audio-ul de dinaintea ultimei ferestre nu mai e necesar nici pentru textul gri.
                pendingSamples.dropBefore(lastWindowStart, epoch)
            }

            val now = SystemClock.elapsedRealtime()
            if (!cutWindow && now - lastPreviewAt < ANALYSIS_MIN_INTERVAL_MS) {
                delay(POLL_INTERVAL_MS)
                continue
            }

            val previewStart = if (lastWindowStart >= 0) lastWindowStart else snapshot.start
            val previewOffset = (previewStart - snapshot.start).toInt()
            var previewTokens = emptyList<CtcToken>()
            if (snapshot.data.size - previewOffset >= MIN_PENDING_SAMPLES) {
                lastPreviewAt = now
                val audio = snapshot.data.copyOfRange(previewOffset, snapshot.data.size)
                previewTokens = mainDecoder.decodeTokens(audio, filterFreqHz.toFloat(), filterWidthHz.toFloat())
                    ?.map { it.copy(sample = it.sample + previewStart) }
                    ?: emptyList()
                if (!coroutineScopeActive()) return
            }
            // Doar ce urmeaza dupa textul deja confirmat (cu toleranta pentru jitter-ul pozitiei).
            val after = max(merger.committedUntil, lastCommittedSample + tolerance)
            val pendingText = collapseWhitespace(
                tokensText(carry) + tokensText(previewTokens.filter { it.sample > after })
            )
            segmentsMutex.withLock {
                notifySegments(segments, pendingText)
            }
        }
    }

    /**
     * Scoate din `carry` caracterele pana la ultimul spatiu (inclusiv) si le intoarce ca text.
     * Daca nu exista spatiu dar s-au strans prea multe caractere, le confirma pe toate.
     */
    private fun takeConfirmedWords(carry: MutableList<CtcToken>): String {
        var lastSpace = -1
        for (i in carry.indices.reversed()) {
            if (MorseVocabularies.ENGLISH_VOCABULARY.getOrNull(carry[i].index) == " ") {
                lastSpace = i
                break
            }
        }
        val count = if (lastSpace >= 0) lastSpace + 1 else if (carry.size > OVERLAP_MAX_CARRY_CHARS) carry.size else 0
        if (count == 0) return ""
        val text = collapseWhitespace(tokensText(carry.subList(0, count)))
        carry.subList(0, count).clear()
        return text
    }

    private fun tokensText(tokens: List<CtcToken>): String =
        tokens.joinToString("") { MorseVocabularies.ENGLISH_VOCABULARY.getOrElse(it.index) { "" } }

    private fun collapseWhitespace(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** Loop de detectie pileup (echivalent `usePileupDetection`). */
    private suspend fun pileupDetectLoop(state: PileupTracksState) {
        val detector = ensureCwDetector()
        val tracked = mutableListOf<TrackedPileupCandidate>()
        var reportedTracks = emptyList<PileupTrack>()
        var nextTrackId = 1
        var lastVersion = -1L

        while (coroutineScopeActive()) {
            // Doar audio-ul real din inel: zerourile de la pornire (sau dupa CLEAR / schimbarea
            // modului) ar cobori mediana benzii cu care se compara fiecare bin, si zgomotul pur
            // ar aparea ca 5 statii fantoma in primele ~4 s.
            val (snapshot, version) = pileupRing.snapshotFilledVersioned()
            if (version == lastVersion) {
                delay(POLL_INTERVAL_MS)
                continue
            }
            lastVersion = version
            if (snapshot.size < PILEUP_MIN_DETECT_SAMPLES) continue

            val rawCandidates = detector.detectStations(
                snapshot,
                PILEUP_MIN_FREQ_HZ.toFloat(),
                PILEUP_MAX_FREQ_HZ.toFloat(),
                PILEUP_LOCK_SNR_THRESHOLD_DB,
                PILEUP_RELEASE_SNR_THRESHOLD_DB,
                reportedTracks.map { it.frequency.toFloat() }
            )
            if (!coroutineScopeActive()) return

            val now = SystemClock.elapsedRealtime()
            val updated = PileupCandidates.updateTracked(tracked, rawCandidates, now) { nextTrackId++ }
            tracked.clear()
            tracked.addAll(updated)

            val confirmed = PileupCandidates.getConfirmed(tracked, now)
            if (PileupCandidates.tracksChanged(reportedTracks, confirmed)) {
                reportedTracks = confirmed
                state.tracks = confirmed
                pileupListener?.onTracksUpdated(confirmed)
            }
        }
    }

    /** Loop de decodare narrow per track confirmat (echivalent `usePileupDecode`). */
    private suspend fun pileupDecodeLoop(state: PileupTracksState) {
        val decoder = ensureNarrowDecoder() ?: return
        val texts = mutableMapOf<Int, String>()
        val lastNonEmptyAt = mutableMapOf<Int, Long>()
        var lastVersion = -1L

        while (coroutineScopeActive()) {
            val (snapshot, version) = pileupRing.snapshot()
            if (version == lastVersion) {
                delay(POLL_INTERVAL_MS)
                continue
            }
            lastVersion = version

            val tracks = state.tracks
            if (tracks.isEmpty()) {
                if (texts.isNotEmpty()) {
                    texts.clear()
                    lastNonEmptyAt.clear()
                    pileupListener?.onTextsUpdated(emptyMap())
                }
                continue
            }

            val decoded = mutableMapOf<Int, String>()
            for (track in tracks) {
                val text = decoder.decodeNarrow(snapshot, track.frequency.toFloat())
                if (!coroutineScopeActive()) return
                if (text != null) decoded[track.id] = text
            }

            val now = SystemClock.elapsedRealtime()
            val activeIds = tracks.map { it.id }.toSet()
            texts.keys.retainAll(activeIds)
            lastNonEmptyAt.keys.retainAll(activeIds)

            for (track in tracks) {
                val text = decoded[track.id] ?: ""
                val hasNewText = text.isNotBlank()
                val nextText = when {
                    hasNewText -> text
                    // "hold": pastreaza ultimul text non-gol pentru PILEUP_TEXT_HOLD_MS
                    texts[track.id] != null &&
                        now - (lastNonEmptyAt[track.id] ?: 0L) < PILEUP_TEXT_HOLD_MS ->
                        texts.getValue(track.id)
                    else -> ""
                }

                if (nextText.isNotEmpty()) {
                    texts[track.id] = nextText
                    if (hasNewText) lastNonEmptyAt[track.id] = now
                } else {
                    texts.remove(track.id)
                    lastNonEmptyAt.remove(track.id)
                }
            }

            pileupListener?.onTextsUpdated(texts.toMap())
        }
    }

    // ============================================================
    // Modele lazy pentru pileup
    // ============================================================

    private suspend fun ensureNarrowDecoder(): MorseDecoder? {
        narrowDecoder?.let { return if (it.isAvailable) it else null }

        val decoder = MorseDecoder(context, MODEL_EN_NARROW)
        val error = withContext(Dispatchers.IO) { decoder.initialize() }
        narrowDecoder = decoder

        if (!decoder.isAvailable) {
            disablePileup("Pileup model could not be loaded: ${error ?: "unknown error"}")
            return null
        }
        return decoder
    }

    /** Gasirea statiilor e DSP pur (fara model), deci nu poate esua la incarcare. */
    private fun ensureCwDetector(): CwDetector =
        cwDetector ?: CwDetector().also { cwDetector = it }

    private fun disablePileup(message: String) {
        pileupAvailable = false
        notifyAvailability()
        reportStatus(message)
        Log.e(TAG, message)
        // Revenire automata pe modul Normal, ca decodorul sa rămână utilizabil.
        if (mode == DecoderMode.PILEUP) {
            mode = DecoderMode.NORMAL
            clearData()
            notifyWaterfallRange()
            modeListener?.onModeChanged(DecoderMode.NORMAL)
            if (isRunning) restartModeLoops()
        }
    }

    // ============================================================
    // Segmentare streaming
    // ============================================================

    private data class ConfirmedSplitPoint(val sample: Int, val endFrame: Int)

    private fun getConfirmedSplitPoint(
        spans: List<WordSpaceSpan>,
        analysisLength: Int,
        allowNearEnd: Boolean
    ): ConfirmedSplitPoint? {
        val maxCommittedSample = if (allowNearEnd) {
            analysisLength
        } else {
            max(MIN_CONFIRMED_SAMPLES, analysisLength - TAIL_GUARD_SAMPLES)
        }

        for (index in spans.indices.reversed()) {
            val span = spans[index]
            val splitSample = StreamingTranscript.getSpanSplitSample(span)
            if (splitSample >= MIN_CONFIRMED_SAMPLES && splitSample <= maxCommittedSample) {
                return ConfirmedSplitPoint(splitSample, span.endFrame)
            }
        }

        return null
    }

    // ============================================================
    // Notificari
    // ============================================================

    private fun notifySegments(segments: List<String>, pendingText: String) {
        streamingListener?.onSegmentsUpdated(segments.toList(), pendingText)
    }

    private fun notifyAvailability() {
        availabilityListener?.onAvailabilityChanged(getAvailability())
    }

    private fun notifyWaterfallRange() {
        waterfallRangeListener?.onWaterfallRangeChanged(
            getWaterfallMinFreqHz(),
            getWaterfallMaxFreqHz()
        )
    }

    private fun reportError(message: String) {
        isRunning = false
        Log.e(TAG, message)
        errorListener?.onError(message)
    }

    private fun reportStatus(message: String) {
        Log.i(TAG, message)
        statusListener?.onStatus(message)
    }

    private suspend fun coroutineScopeActive(): Boolean =
        isRunning && kotlin.coroutines.coroutineContext.isActive

    // ============================================================
    // Buffere
    // ============================================================

    /** Buffer glisant pentru decodarea streaming (echivalent `pendingSamplesRef`). */
    private class PendingSamples {
        private var data = FloatArray(0)
        private var version = 0L

        /** Pozitia absoluta (in stream) a lui data[0]; reporneste de la 0 la `clear`. */
        private var start = 0L

        /** Creste la fiecare `clear`, ca bucla OVERLAP sa stie ca a inceput un stream nou. */
        private var epoch = 0L

        class Positioned(val data: FloatArray, val version: Long, val start: Long, val epoch: Long)

        @Synchronized
        fun append(chunk: FloatArray) {
            val next = FloatArray(data.size + chunk.size)
            data.copyInto(next)
            chunk.copyInto(next, data.size)
            data = next
            version++
        }

        @Synchronized
        fun snapshot(): Pair<FloatArray, Long> = data.copyOf() to version

        @Synchronized
        fun positionedSnapshot(): Positioned = Positioned(data.copyOf(), version, start, epoch)

        @Synchronized
        fun dropLeading(count: Int) {
            if (count <= 0) return
            val dropped = min(count, data.size)
            data = if (count >= data.size) FloatArray(0) else data.copyOfRange(count, data.size)
            start += dropped
            version++
        }

        /** Elimina esantioanele de dinaintea pozitiei absolute `sample`, daca stream-ul e acelasi. */
        @Synchronized
        fun dropBefore(sample: Long, expectedEpoch: Long) {
            if (expectedEpoch != epoch || sample <= start) return
            dropLeading(min(sample - start, data.size.toLong()).toInt())
        }

        @Synchronized
        fun clear() {
            data = FloatArray(0)
            start = 0L
            epoch++
            version++
        }
    }

    /** Buffer ring de lungime fixa pentru pileup (fereastra de PILEUP_WINDOW_S secunde). */
    private class RingBuffer(size: Int) {
        private val data = FloatArray(size)
        private var writeIndex = 0
        private var version = 0L

        /** Cate esantioane reale contine bufferul (restul e inca zero de la creare/clear). */
        private var filled = 0

        @Synchronized
        fun write(chunk: FloatArray) {
            for (sample in chunk) {
                data[writeIndex] = sample
                writeIndex = (writeIndex + 1) % data.size
            }
            filled = min(data.size, filled + chunk.size)
            version++
        }

        /** Doar esantioanele scrise efectiv, cele mai vechi primele. */
        @Synchronized
        fun snapshotFilled(): FloatArray {
            val start = (writeIndex - filled + data.size) % data.size
            return FloatArray(filled) { data[(start + it) % data.size] }
        }

        @Synchronized
        fun snapshotFilledVersioned(): Pair<FloatArray, Long> = snapshotFilled() to version

        @Synchronized
        fun snapshot(): Pair<FloatArray, Long> {
            val result = FloatArray(data.size)
            for (i in data.indices) result[i] = data[(writeIndex + i) % data.size]
            return result to version
        }

        @Synchronized
        fun clear() {
            data.fill(0f)
            writeIndex = 0
            filled = 0
            version++
        }
    }

    private class PileupTracksState {
        @Volatile
        var tracks: List<PileupTrack> = emptyList()
    }

    private companion object {
        const val TAG = "MorseAudioProcessor"

        const val MODEL_EN = "model_en_2026-10.onnx"
        const val MODEL_EN_NARROW = "model_en_narrow_2026-10.onnx"

        // Constante de streaming portate din useStreamingDecode.ts
        const val MAX_SEGMENT_S = 30
        const val TAIL_GUARD_S = 1.25f
        const val MIN_PENDING_S = 2
        const val MIN_CONFIRMED_S = 2
        const val ANALYSIS_MIN_INTERVAL_MS = 1000L
        const val POLL_INTERVAL_MS = 50L

        /** Cat audio real trebuie sa aiba inelul pileup inainte sa cautam statii in el. */
        val PILEUP_MIN_DETECT_SAMPLES = 2 * SAMPLE_RATE

        /** Istoricul audio analizat de "Detecteaza tonul". */
        const val TONE_HISTORY_S = 10

        val MAX_SEGMENT_SAMPLES = MAX_SEGMENT_S * SAMPLE_RATE
        val TAIL_GUARD_SAMPLES = (TAIL_GUARD_S * SAMPLE_RATE).roundToInt()
        val MIN_PENDING_SAMPLES = MIN_PENDING_S * SAMPLE_RATE
        val MIN_CONFIRMED_SAMPLES = MIN_CONFIRMED_S * SAMPLE_RATE

        // StreamingMethod.OVERLAP. Fereastra si pasul sunt multipli de OUTPUT_HOP_LENGTH (pasul
        // cadrelor de iesire ale modelului, 288 esantioane), ca pozitiile caracterelor din
        // ferestre diferite sa cada pe aceeasi grila de cadre. 12 s = 400 cadre; 8 s nu e
        // multiplu, deci pasul e rotunjit la cel mai apropiat (267 cadre = 8.01 s).
        const val OVERLAP_WINDOW_S = 12
        const val OVERLAP_HOP_S = 8
        const val OVERLAP_EDGE_GUARD_S = 1.0f
        const val OVERLAP_TOLERANCE_S = 0.1f
        const val OVERLAP_MAX_CARRY_CHARS = 30

        val OVERLAP_WINDOW_SAMPLES = OVERLAP_WINDOW_S * SAMPLE_RATE
        val OVERLAP_HOP_SAMPLES =
            (OVERLAP_HOP_S * SAMPLE_RATE / OUTPUT_HOP_LENGTH.toFloat()).roundToInt() * OUTPUT_HOP_LENGTH
        val OVERLAP_EDGE_GUARD_SAMPLES = (OVERLAP_EDGE_GUARD_S * SAMPLE_RATE).roundToInt()
        val OVERLAP_TOLERANCE_SAMPLES = (OVERLAP_TOLERANCE_S * SAMPLE_RATE).roundToInt()

        init {
            require(OVERLAP_WINDOW_SAMPLES % OUTPUT_HOP_LENGTH == 0 && OVERLAP_HOP_SAMPLES % OUTPUT_HOP_LENGTH == 0)
        }
    }
}
