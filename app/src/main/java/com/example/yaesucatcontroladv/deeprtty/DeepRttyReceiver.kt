package com.example.yaesucatcontroladv.deeprtty

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.yaesucatcontroladv.deepcw.data.InferenceWrapper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Decodorul DEEPRTTY al ecranului RTTY. Firul audio al ecranului apeleaza `alimenteaza` cu
 * blocurile de 48 kHz (pregatirea la 3200 Hz e ieftina si se face pe loc); decodarea ruleaza
 * pe un fir propriu, ca o inferenta de cateva sute de ms sa nu blocheze receptia.
 *
 * Iesiri (pe firul de decodare): codurile ITA2 confirmate - o singura data fiecare - si,
 * o data pe secunda, codurile provizorii ale cozii inca neconfirmate (textul gri).
 */
class DeepRttyReceiver(context: Context) {

    interface Listener {
        fun onConfirmate(coduri: IntArray)
        fun onProvizoriu(coduri: IntArray)
    }

    @Volatile
    var listener: Listener? = null

    private val inferenta = InferenceWrapper(context)
    private val frontEnd = RttyFrontEnd(48000)
    private val stream = DeepRttyStream { date, dims -> inferenta.runInference(date, dims) }
    /**
     * Blocuri audio (FloatArray) si marcaje de reset, in ordinea sosirii. CORECTIE: resetul era un
     * flag partajat, iar firul de decodare golea apoi toata coada - daca intre timp firul audio
     * pusese deja blocuri noi (sosite DUPA reset), le arunca si pe ele: la pornire si la fiecare
     * reacordare se pierdeau cateva sute de ms de audio bun. Ca marcaj in coada, resetul arunca doar
     * ce era inainte de el.
     */
    private val coada = LinkedBlockingQueue<Any>()

    @Volatile
    private var ruleaza = false
    private var worker: Thread? = null

    // doar pe firul audio
    private var ultimulCentru = Double.NaN
    private var ultimulReverse = false
    private var iesire = FloatArray(0)

    val isAvailable: Boolean
        get() = inferenta.isAvailable

    /** Incarca modelul (cateva sute de ms) - de apelat pe un fir de fundal. @return null la succes. */
    fun incarca(): String? = inferenta.loadModel(DeepRttyConst.MODEL_FILE)

    fun porneste() {
        if (ruleaza) return
        coada.clear()
        stream.reseteaza() // firul de decodare nu ruleaza inca
        ultimulCentru = Double.NaN
        ruleaza = true
        worker = Thread({ bucla() }, "DeepRTTY").apply { start() }
    }

    fun opreste() {
        ruleaza = false
        worker?.let {
            it.interrupt()
            try {
                it.join(1000)
            } catch (ignored: InterruptedException) {
            }
        }
        worker = null
        coada.clear()
    }

    fun elibereaza() {
        opreste()
        inferenta.close()
    }

    /** Arunca audio-ul si textul neconfirmat (ex. CLEAR); textul deja confirmat ramane. */
    fun reseteaza() {
        if (ruleaza) coada.offer(MarcajReset)
    }

    /**
     * Firul audio: un bloc PCM de 48 kHz. `centruHz` = centrul semnalului RTTY (Mark +/- 85 Hz),
     * `reverse` = Mark e sus (REV). O mutare brusca a acordului (tap pe waterfall, A-TUNE,
     * NORM/REV) porneste decodarea de la zero; derivele mici ale AFC sunt urmarite din mers.
     */
    fun alimenteaza(pcm: ShortArray, n: Int, centruHz: Double, reverse: Boolean) {
        if (!ruleaza) return
        if (ultimulCentru.isNaN() || abs(centruHz - ultimulCentru) > SALT_ACORD_HZ || reverse != ultimulReverse) {
            frontEnd.reseteaza()
            if (!ultimulCentru.isNaN()) coada.offer(MarcajReset) // reacordare; la pornire stream-ul e deja gol
        }
        ultimulCentru = centruHz
        ultimulReverse = reverse
        if (iesire.size < n / 15 + 2) iesire = FloatArray(n / 15 + 2)
        val m = frontEnd.proceseaza(pcm, n, centruHz, reverse, iesire, 0)
        if (m > 0) coada.offer(iesire.copyOf(m))
    }

    private fun bucla() {
        var ultimulProvizoriu = 0L
        listener?.onProvizoriu(IntArray(0))
        while (ruleaza) {
            try {
                val primul = coada.poll(100, TimeUnit.MILLISECONDS)
                if (primul != null) {
                    // tot ce s-a strans intre timp, intr-un singur push; un marcaj de reset arunca
                    // doar audio-ul de dinaintea lui
                    val elemente = ArrayList<Any>().apply { add(primul); coada.drainTo(this) }
                    val lot = ArrayList<FloatArray>()
                    for (e in elemente) {
                        if (e === MarcajReset) {
                            lot.clear()
                            stream.reseteaza()
                            listener?.onProvizoriu(IntArray(0))
                        } else {
                            lot.add(e as FloatArray)
                        }
                    }
                    if (lot.isNotEmpty()) {
                        val total = lot.sumOf { it.size }
                        val audio = FloatArray(total)
                        var o = 0
                        for (b in lot) { b.copyInto(audio, o); o += b.size }
                        val confirmate = stream.push(audio, total)
                        if (confirmate.isNotEmpty()) {
                            listener?.onConfirmate(IntArray(confirmate.size) { confirmate[it].index })
                        }
                    }
                }
                val acum = SystemClock.elapsedRealtime()
                if (acum - ultimulProvizoriu >= PROVIZORIU_MS) {
                    ultimulProvizoriu = acum
                    val p = stream.preview()
                    listener?.onProvizoriu(IntArray(p.size) { p[it].index })
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Eroare de decodare DeepRTTY", e)
            }
        }
    }

    /** Marcajul de reset din coada. */
    private object MarcajReset

    private companion object {
        const val TAG = "DeepRttyReceiver"

        /** Textul provizoriu (gri) se re-decodeaza o data pe secunda. */
        const val PROVIZORIU_MS = 1000L

        /** O mutare a centrului mai mare decat atat e o reacordare, nu deriva AFC. */
        const val SALT_ACORD_HZ = 20.0
    }
}
