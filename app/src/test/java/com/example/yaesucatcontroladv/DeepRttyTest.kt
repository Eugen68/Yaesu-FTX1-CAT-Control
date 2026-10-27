package com.example.yaesucatcontroladv

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.yaesucatcontroladv.deeprtty.DeepRttyConst
import com.example.yaesucatcontroladv.deeprtty.DeepRttyStream
import com.example.yaesucatcontroladv.deeprtty.RttyFrontEnd
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.FloatBuffer

/**
 * DeepRTTY cap-coada pe RTTY sintetic de 48 kHz, ca in ecranul RTTY: RttyFrontEnd (mixer complex
 * + decimare, NORM si REV) -> DeepRttyStream (ferestre de 12 s imbinate + coada provizorie) ->
 * modelul din assets (onnxruntime JVM) -> BaudotCodec.
 */
class DeepRttyTest {

    companion object {
        private val env = OrtEnvironment.getEnvironment()
        private lateinit var sesiune: OrtSession

        @BeforeClass
        @JvmStatic
        fun incarca() {
            sesiune = env.createSession("src/main/assets/" + DeepRttyConst.MODEL_FILE, OrtSession.SessionOptions())
        }

        @AfterClass
        @JvmStatic
        fun inchide() {
            sesiune.close()
        }

        fun inferenta(date: FloatArray, dims: LongArray): Pair<FloatArray, LongArray>? {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(date), dims).use { t ->
                sesiune.run(mapOf(sesiune.inputNames.first() to t)).use { out ->
                    val r = out.get(0) as OnnxTensor
                    val f = FloatArray(r.floatBuffer.remaining())
                    r.floatBuffer.get(f)
                    return f to r.info.shape
                }
            }
        }
    }

    private val text = "CQ CQ CQ DE YO9VA YO9VA YO9VA PSE K THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 1234567890 " +
        "UR RST 599 599 NAME EUGEN QTH PLOIESTI KN 35 73 SK "

    /** Decodeaza ca ecranul RTTY: blocuri de 3840 esantioane, confirmate + coada de la final. */
    private fun decodeaza(pcm: ShortArray, markHz: Double, reverse: Boolean): String {
        val fe = RttyFrontEnd(48000)
        val stream = DeepRttyStream(::inferenta)
        val rx = BaudotCodec()
        val sb = StringBuilder()
        val bloc = 3840
        val buf = ShortArray(bloc)
        val iesire = FloatArray(bloc / 15 + 2)
        val centru = markHz + if (reverse) -85.0 else 85.0
        var pos = 0
        while (pos < pcm.size) {
            val n = minOf(bloc, pcm.size - pos)
            System.arraycopy(pcm, pos, buf, 0, n)
            pos += n
            val m = fe.proceseaza(buf, n, centru, reverse, iesire, 0)
            for (t in stream.push(iesire, m)) rx.decode(t.index).let { if (it != '\u0000') sb.append(it) }
        }
        for (t in stream.preview()) rx.decode(t.index).let { if (it != '\u0000') sb.append(it) }
        return sb.toString()
    }

    private fun cer(decodat: String): Double {
        fun norm(s: String) = s.replace('\r', ' ').replace('\n', ' ').replace(Regex(" +"), " ").trim()
        val ref = norm(text)
        var p = IntArray(ref.length + 1) { it }
        var c = IntArray(ref.length + 1)
        val d = norm(decodat)
        for (i in 1..d.length) {
            c[0] = i
            for (j in 1..ref.length) c[j] = minOf(c[j - 1] + 1, p[j] + 1, p[j - 1] + if (d[i - 1] == ref[j - 1]) 0 else 1)
            val t = p; p = c; c = t
        }
        return p[ref.length].toDouble() / ref.length
    }

    private fun medie(snr: Double, reverse: Boolean): Double {
        RttyDemodulatorTest.spaceSub = reverse
        try {
            var s = 0.0
            for (seed in 1L..3L) {
                val pcm = RttyDemodulatorTest.genereaza(text + text, snr, seed, 0.0, RttyDemodulator.BAUD_RTTY, 0.0)
                val d = decodeaza(pcm, 2125.0, reverse)
                // text + text: comparam cu a doua jumatate dublata, ca sa nu conteze inceputul
                s += cer(d.substringAfter("SK ", d)) / 3
            }
            return s
        } finally {
            RttyDemodulatorTest.spaceSub = false
        }
    }

    @Test
    fun semnalSlabNorm() {
        val r = (listOf(-3.0, -6.0, -9.0)).map { it to medie(it, false) }
        println("DEEPRTTY NORM " + r.joinToString { (s, c) -> String.format("%+.0f dB: %.1f%%", s, c * 100) })
        assertTrue("CER la -6 dB = ${r[1].second}", r[1].second < 0.06)
    }

    @Test
    fun semnalSlabReverse() {
        val c = medie(-6.0, true)
        println(String.format("DEEPRTTY REV -6 dB: %.1f%%", c * 100))
        assertTrue("CER REV la -6 dB = $c", c < 0.06)
    }
}
