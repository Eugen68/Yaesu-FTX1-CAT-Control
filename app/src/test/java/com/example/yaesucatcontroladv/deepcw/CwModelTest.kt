package com.example.yaesucatcontroladv.deepcw

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.yaesucatcontroladv.deepcw.data.CTCDecoder
import com.example.yaesucatcontroladv.deepcw.data.CtcTokens
import com.example.yaesucatcontroladv.deepcw.data.MorseVocabularies
import com.example.yaesucatcontroladv.deepcw.dsp.Constants
import com.example.yaesucatcontroladv.deepcw.dsp.SpectrogramGenerator
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.FloatBuffer

/**
 * Modelul de decodare din assets + spectrograma din SpectrogramGenerator, pe CW sintetic.
 * Prinde o nepotrivire intre model si pregatirea intrarii (pas STFT, padding, normalizare):
 * cu lantul vechi, model_en_2026-10 nu mai decodeaza nimic, iar modelul vechi nu decoda
 * peste ~35 WPM nici pe semnal curat.
 */
class CwModelTest {

    companion object {
        private val env = OrtEnvironment.getEnvironment()
        private lateinit var sesiune: OrtSession
        private lateinit var sesiuneNarrow: OrtSession

        @BeforeClass
        @JvmStatic
        fun incarca() {
            sesiune = env.createSession("src/main/assets/model_en_2026-10.onnx", OrtSession.SessionOptions())
            sesiuneNarrow = env.createSession("src/main/assets/model_en_narrow_2026-10.onnx", OrtSession.SessionOptions())
        }

        @AfterClass
        @JvmStatic
        fun inchide() {
            sesiune.close()
            sesiuneNarrow.close()
        }
    }

    private val generator = SpectrogramGenerator()
    private val ctc = CTCDecoder()
    private val ton = 600.0

    private val texte = listOf(
        "CQ CQ CQ DE YO9VA YO9VA YO9VA K",
        "UR RST 599 NAME EUGEN QTH PLOIESTI",
        "TNX FER QSO 73 ES GL DE DL1ABC",
        "QRZ? DE EA3XYZ PSE KN",
    )

    private fun ruleaza(audio: FloatArray): Pair<FloatArray, LongArray> {
        val sp = generator.generateShiftedSpectrogram(audio, ton.toFloat(), 250f)!!
        OnnxTensor.createTensor(env, FloatBuffer.wrap(sp.data), sp.dims).use { t ->
            sesiune.run(mapOf(sesiune.inputNames.first() to t)).use { out ->
                val r = out.get(0) as OnnxTensor
                val f = FloatArray(r.floatBuffer.remaining())
                r.floatBuffer.get(f)
                return f to r.info.shape
            }
        }
    }

    private fun cer(wpm: Double, snrDb: Double): Double {
        var erori = 0
        var total = 0
        for ((i, txt) in texte.withIndex()) {
            val g = CwSintetic.genereaza(txt, wpm, ton, snrDb, 1000L * i + wpm.toLong())
            val (pr, dims) = ruleaza(g.audio)
            val decodat = CwSintetic.normalizeaza(ctc.decodeDetailed(pr, dims).plainText)
            erori += CwSintetic.levenshtein(CwSintetic.normalizeaza(txt), decodat)
            total += CwSintetic.normalizeaza(txt).length
        }
        return erori.toDouble() / total
    }

    @Test
    fun semnalSlabVitezaNormala() {
        val cer = cer(25.0, -8.0)
        assertTrue("CER 25 WPM la -8 dB = $cer", cer < 0.02)
    }

    @Test
    fun vitezaMare() {
        val cer = cer(42.0, 0.0)
        assertTrue("CER 42 WPM la 0 dB = $cer", cer < 0.02)
    }

    /** Modelul narrow (pileup), pe slice-ul de 3 bin-uri din jurul tonului. */
    @Test
    fun modelNarrowPileup() {
        var erori = 0
        var total = 0
        for ((i, txt) in texte.withIndex()) {
            val g = CwSintetic.genereaza(txt, 25.0, ton, 0.0, 500L + i)
            val sp = generator.generateNarrowSpectrogram(g.audio, ton.toFloat())!!
            OnnxTensor.createTensor(env, FloatBuffer.wrap(sp.data), sp.dims).use { t ->
                sesiuneNarrow.run(mapOf(sesiuneNarrow.inputNames.first() to t)).use { out ->
                    val r = out.get(0) as OnnxTensor
                    val f = FloatArray(r.floatBuffer.remaining())
                    r.floatBuffer.get(f)
                    val decodat = CwSintetic.normalizeaza(ctc.decodeDetailed(f, r.info.shape).plainText)
                    println("NARROW [$txt] -> [$decodat]")
                    erori += CwSintetic.levenshtein(CwSintetic.normalizeaza(txt), decodat)
                    total += CwSintetic.normalizeaza(txt).length
                }
            }
        }
        val cer = erori.toDouble() / total
        assertTrue("CER narrow 25 WPM la 0 dB = $cer", cer < 0.05)
    }

    /**
     * Gasirea statiilor (DSP): doua statii reale de taria diferita sunt gasite, fara statii
     * fantoma din benzile laterale ale celei puternice, din brumul retelei (100/150 Hz) sau
     * dintr-o purtatoare nemanipulata; zgomotul pur nu da nicio statie.
     */
    @Test
    fun detectieStatiiPileup() {
        val detector = com.example.yaesucatcontroladv.deepcw.data.CwDetector()
        val n = Constants.PILEUP_WINDOW_S * Constants.SAMPLE_RATE
        val tare = CwSintetic.genereaza("CQ CQ DE YO9VA YO9VA K CQ CQ", 25.0, 700.0, 30.0, 1).audio
        val slab = CwSintetic.genereaza("TEST DE DL1ABC DL1ABC K TEST", 22.0, 1100.0, Double.POSITIVE_INFINITY, 2).audio
        val rnd = java.util.Random(3)
        val mix = FloatArray(n) { i ->
            val a = if (i + 9600 < tare.size) tare[i + 9600] else 0f
            val b = if (i + 9600 < slab.size) slab[i + 9600] * 0.1f else 0f
            val brum = 0.05 * kotlin.math.sin(2 * Math.PI * 100 * i / 9600) + 0.03 * kotlin.math.sin(2 * Math.PI * 150 * i / 9600)
            val purtatoare = 0.02 * kotlin.math.sin(2 * Math.PI * 1500 * i / 9600)
            a + b + (brum + purtatoare + rnd.nextGaussian() * 0.01).toFloat()
        }
        val statii = detector.detectStations(mix, 100f,
            Constants.PILEUP_MAX_FREQ_HZ.toFloat(), Constants.PILEUP_LOCK_SNR_THRESHOLD_DB,
            Constants.PILEUP_RELEASE_SNR_THRESHOLD_DB)
        println("PILEUP statii: ${statii.map { "${it.frequency} Hz ${"%.1f".format(it.snrDb)} dB" }}")
        assertTrue("statia de 700 Hz", statii.any { kotlin.math.abs(it.frequency - 700f) <= 13f })
        assertTrue("statia de 1100 Hz", statii.any { kotlin.math.abs(it.frequency - 1100f) <= 13f })
        assertTrue("fara fantome: ${statii.map { it.frequency }}", statii.all {
            kotlin.math.abs(it.frequency - 700f) <= 13f || kotlin.math.abs(it.frequency - 1100f) <= 13f
        })

        val zgomot = FloatArray(n) { (rnd.nextGaussian() * 0.05).toFloat() }
        val nimic = detector.detectStations(zgomot, Constants.PILEUP_MIN_FREQ_HZ.toFloat(),
            Constants.PILEUP_MAX_FREQ_HZ.toFloat(), Constants.PILEUP_LOCK_SNR_THRESHOLD_DB,
            Constants.PILEUP_RELEASE_SNR_THRESHOLD_DB)
        assertTrue("zgomot pur: ${nimic.map { it.frequency }}", nimic.isEmpty())
    }

    /**
     * Armonica a doua a unei statii (distorsiune in lantul audio) nu e o statie: 425 Hz
     * distorsionat da o copie manipulata identic la 850 Hz, invizibila pe waterfall. O statie
     * reala, independenta, la 850 Hz trebuie insa gasita.
     */
    @Test
    fun faraArmoniciInPileup() {
        val detector = com.example.yaesucatcontroladv.deepcw.data.CwDetector()
        val n = Constants.PILEUP_WINDOW_S * Constants.SAMPLE_RATE
        val statie = CwSintetic.genereaza("CQ CQ DE YO9VA YO9VA K CQ CQ", 25.0, 425.0, Double.POSITIVE_INFINITY, 1).audio
        val alta = CwSintetic.genereaza("TEST DE DL1ABC DL1ABC K TEST", 22.0, 850.0, Double.POSITIVE_INFINITY, 2).audio
        fun detecteaza(cuAlta: Boolean): List<Float> {
            val rnd = java.util.Random(5)
            val mix = FloatArray(n) { i ->
                val x = if (i + 9600 < statie.size) statie[i + 9600] else 0f
                val b = if (cuAlta && i + 9600 < alta.size) alta[i + 9600] * 0.1f else 0f
                // distorsiune patratica: armonica a doua la ~-27 dB
                (x + 0.3f * x * x + b + rnd.nextGaussian() * 0.002).toFloat()
            }
            return detector.detectStations(mix, Constants.PILEUP_MIN_FREQ_HZ.toFloat(),
                Constants.PILEUP_MAX_FREQ_HZ.toFloat(), Constants.PILEUP_LOCK_SNR_THRESHOLD_DB,
                Constants.PILEUP_RELEASE_SNR_THRESHOLD_DB).map { it.frequency }
        }
        val doarStatia = detecteaza(false)
        println("ARMONICA: $doarStatia")
        assertTrue("statia de 425 Hz: $doarStatia", doarStatia.any { kotlin.math.abs(it - 425f) <= 13f })
        assertTrue("fara armonica: $doarStatia", doarStatia.all { kotlin.math.abs(it - 425f) <= 13f })

        val cuAlta = detecteaza(true)
        println("ARMONICA + statie reala 850: $cuAlta")
        assertTrue("statia reala de 850 Hz: $cuAlta", cuAlta.any { kotlin.math.abs(it - 850f) <= 13f })
    }

    /**
     * Pozitia in timp a caracterelor (Constants.outputFrameCenterSample) trebuie sa cada pe
     * caracterul real - OVERLAP si taierea la pauza de cuvant se bazeaza pe ea.
     */
    @Test
    fun pozitiaCaracterelor() {
        for (wpm in listOf(15.0, 30.0, 45.0)) {
            val txt = texte[0]
            val g = CwSintetic.genereaza(txt, wpm, ton, 0.0, 7)
            val (pr, dims) = ruleaza(g.audio)
            val tokeni = CtcTokens.greedy(pr, dims, MorseVocabularies.ENGLISH_BLANK_INDEX)
                .filter { MorseVocabularies.ENGLISH_VOCABULARY[it.index] != " " }
            assertTrue("$wpm WPM: ${tokeni.size} tokeni, ${g.centreCaractere.size} caractere",
                tokeni.size == g.centreCaractere.size)
            // cel mai lung caracter din text (Y = 13 diti) dureaza 13 * 1.2 / wpm s
            val jumatateMaxMs = 13 * 1200.0 / wpm / 2
            for (k in tokeni.indices) {
                val abatereMs = (Constants.outputFrameCenterSample(tokeni[k].frame) - g.centreCaractere[k].second) *
                    1000.0 / Constants.SAMPLE_RATE
                assertTrue("$wpm WPM caracterul $k abatere $abatereMs ms",
                    abatereMs > -jumatateMaxMs - 60 && abatereMs < jumatateMaxMs + 60)
            }
        }
    }
}
