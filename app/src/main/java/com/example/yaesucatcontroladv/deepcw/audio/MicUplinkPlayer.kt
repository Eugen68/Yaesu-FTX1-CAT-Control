package com.example.yaesucatcontroladv.deepcw.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Traseul de emisie (TX) pentru SSB: microfonul telefonului/castii -> iesire audio USB catre
 * statie.
 *
 * Captura microfonului si emisia efectiva sunt doua cicluri de viata separate:
 * - [startCapture]/[stopCapture] deschid/inchid microfonul, independent de PTT.
 * - [startTransmit]/[stopTransmit] deschid/inchid doar AudioTrack-ul de iesire spre USB si
 *   comuta forwarding-ul (esantioanele citite sunt scrise catre USB doar cat timp emisia e
 *   activa) - apelate direct de PTT.
 *
 * Motivul separarii: legatura Bluetooth SCO are ~1-2s de "warm-up" (negociere codec/sincronizare
 * la nivel de cip) de fiecare data cand se deschide un AudioRecord nou pe ea - deschisa/inchisa
 * la fiecare apasare de PTT, prima secunda-doua din fiecare emisie ajungea la statie ca tacere.
 * EcranComenzi porneste [startCapture] o singura data, la conectarea SCO (nu la fiecare PTT),
 * astfel incat traseul sa fie deja "cald" cand operatorul apasa PTT - vezi [startTransmit].
 *
 * Pentru microfonul intern (fara casca BT, care nu are aceasta problema de warm-up) [startCapture]
 * nu e apelat proactiv de EcranComenzi - [startTransmit] il deschide pe loc, la vechiul
 * comportament, si [stopTransmit] il inchide la loc (nu tinem microfonul telefonului deschis in
 * afara ferestrei de emisie fara motiv).
 */
class MicUplinkPlayer(private val context: Context) {

    enum class StartResult { OK, NO_USB_AUDIO, PERMISSION_DENIED, CAPTURE_FAILED, ALREADY_RUNNING }

    /** Microfonul e deschis (captura activa) - independent de PTT, vezi [startCapture]. */
    @Volatile
    var isCapturing: Boolean = false
        private set

    /** Emisia (forwarding mic -> USB) e activa - reflecta strict starea PTT. */
    @Volatile
    var isTransmitting: Boolean = false
        private set

    /** Microfonul folosit efectiv la ultima pornire a capturii (Bluetooth SCO sau intern). */
    @Volatile
    var activeMicIsBluetooth: Boolean = false
        private set

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    /** true daca [startCapture] a fost pornita implicit de [startTransmit] (microfon intern,
     * fara pre-warm) - in acest caz [stopTransmit] o inchide la loc. */
    private var captureOwnedByTransmit = false

    @Volatile
    private var resampleCursor = 0f
    @Volatile
    private var resamplePrev: Short = 0

    /**
     * Deschide microfonul si il tine captat continuu (esantioanele sunt aruncate cat timp
     * [isTransmitting] e fals) - vezi antetul clasei pentru motiv. Idempotenta: daca deja
     * ruleaza, intoarce [StartResult.ALREADY_RUNNING].
     */
    @SuppressLint("MissingPermission")
    fun startCapture(): StartResult {
        if (isCapturing) return StartResult.ALREADY_RUNNING

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return StartResult.PERMISSION_DENIED
        }

        val micTarget = selectMicTarget()
        activeMicIsBluetooth = BluetoothScoState.connected
        val rate = pickSupportedRate(micTarget.rateCandidates)

        val record = try {
            openMic(rate, micTarget)
        } catch (e: Exception) {
            Log.e(TAG, "Deschidere microfon esuata", e)
            null
        } ?: return StartResult.CAPTURE_FAILED

        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Pornire captura esuata", e)
            try { record.release() } catch (ignored: Exception) {}
            return StartResult.CAPTURE_FAILED
        }

        resampleCursor = 0f
        resamplePrev = 0
        audioRecord = record
        isCapturing = true

        // NOTA: cand sursa e casca Bluetooth, MODE_IN_COMMUNICATION (necesar pentru intrarea
        // SCO) trage si iesirea spre casca (sidetone) la urmatoarea emisie, in ciuda USAGE_MEDIA
        // si setPreferredDevice() din startTransmit() - semnalul nu ajunge deloc la statie.
        // "Scuturarea" politicii audio care rezolva asta (deschidere/inchidere stream USB) se
        // face in EcranComenzi, cu AudioListenPlayer real (nu aici, cu un AudioRecord minimal
        // de test - incercat, dar empiric nu a avut acelasi efect ca folosirea reala a LISTEN,
        // posibil din cauza ratei/sursei diferite sau a lipsei citirilor efective de date).

        job = scope.launch {
            val chunkSamples = (rate / 10).coerceAtLeast(256) // ~100ms
            val buf = ShortArray(chunkSamples)
            var lastLogMs = 0L
            var peakSinceLog = 0
            while (isCapturing) {
                val read = try {
                    record.read(buf, 0, buf.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Eroare citire microfon", e)
                    break
                }
                if (read <= 0) continue

                val track = audioTrack
                if (track == null || !isTransmitting) continue

                // DIAGNOSTIC TEMPORAR: amplitudinea reala citita de pe microfon, logata o
                // data pe secunda, ca sa vedem daca ajunge audio real sau doar tacere/zerouri
                // (util mai ales pentru sursa BT SCO, unde side-tone-ul castii poate insela).
                for (i in 0 until read) {
                    val a = kotlin.math.abs(buf[i].toInt())
                    if (a > peakSinceLog) peakSinceLog = a
                }
                val now = System.currentTimeMillis()
                if (now - lastLogMs >= 1000L) {
                    val routed = track.routedDevice
                    Log.i(
                        TAG,
                        "DIAG peak amplitudine mic (ultima secunda) = $peakSinceLog / 32767" +
                            " | AudioTrack rutat efectiv la device type=${routed?.type} (22=USB, 7=BT_SCO)"
                    )
                    lastLogMs = now
                    peakSinceLog = 0
                }

                val toWrite = if (rate == OUTPUT_RATE) buf.copyOf(read) else resample(buf, read, rate, OUTPUT_RATE)
                try {
                    track.write(toWrite, 0, toWrite.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Eroare scriere AudioTrack USB", e)
                }
            }
        }

        return StartResult.OK
    }

    /** Opreste si elibereaza microfonul (si emisia, daca era activa). Apelata de EcranComenzi
     * la deconectarea SCO sau la iesirea din modul voce. */
    fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false
        isTransmitting = false

        job?.cancel()
        job = null

        try { audioRecord?.stop() } catch (ignored: Exception) {}
        try { audioRecord?.release() } catch (ignored: Exception) {}
        audioRecord = null

        releaseTrack()
    }

    /**
     * Porneste emisia (mic -> USB) - apelata la apasarea PTT. Daca microfonul nu era deja
     * captat continuu (vezi [startCapture] - cazul microfonului intern), il deschide pe loc;
     * [stopTransmit] il va inchide la loc in acest caz.
     */
    fun startTransmit(): StartResult {
        if (isTransmitting) return StartResult.ALREADY_RUNNING

        val usbOutput = findUsbOutputDevice() ?: return StartResult.NO_USB_AUDIO

        if (!isCapturing) {
            val r = startCapture()
            if (r != StartResult.OK) return r
            captureOwnedByTransmit = true
        } else {
            captureOwnedByTransmit = false
        }

        val track = try {
            buildUsbTrack(OUTPUT_RATE, usbOutput)
        } catch (e: Exception) {
            Log.e(TAG, "Deschidere AudioTrack USB esuata", e)
            if (captureOwnedByTransmit) stopCapture()
            return StartResult.CAPTURE_FAILED
        }

        try {
            track.play()
        } catch (e: Exception) {
            Log.e(TAG, "Pornire reda re esuata", e)
            try { track.release() } catch (ignored: Exception) {}
            if (captureOwnedByTransmit) stopCapture()
            return StartResult.CAPTURE_FAILED
        }

        // Fiecare emisie noua porneste cu resample-ul "curat" - fara asta, esantioanele
        // aruncate intre emisii (cat captura era calda dar nu transmiteam) ar strica
        // continuitatea presupusa de cursor intre apeluri.
        resampleCursor = 0f
        resamplePrev = 0
        audioTrack = track

        // NOTA: s-a incercat aici setCommunicationDevice(usbOutput) pentru a forta iesirea pe
        // USB cat tinem PTT (vezi motivatia in istoricul acestui fisier) - dar in teste pe teren
        // a avut efect advers: fortarea "dispozitivului de comunicare" catre USB pare sa
        // convinga stiva Bluetooth ca SCO nu mai e necesar, iar legatura se deconecta la ~10-13s
        // (fata de ~50-70s normal), fiecare deconectare omorand emisia curenta (vezi
        // stopCapture() apelat din receiver-ul SCO din EcranComenzi). Renuntat.

        // track.play() doar initiaza pornirea - rutarea reala catre USB e asincrona in Android
        // (mai ales imediat dupa setPreferredDevice()/schimbare de mod) si poate dura cateva
        // sute de ms sa se stabilizeze. EcranComenzi cheiaza statia (RTS/DTR) imediat ce
        // acest apel returneaza OK, deci fara aceasta asteptare prima apasare de PTT putea
        // keya statia inainte ca audio-ul sa ajunga efectiv la USB.
        waitForRouting(track, usbOutput)

        isTransmitting = true
        return StartResult.OK
    }

    /** Opreste emisia - apelata la eliberarea PTT. */
    fun stopTransmit() {
        if (!isTransmitting) return
        isTransmitting = false
        releaseTrack()

        if (captureOwnedByTransmit) {
            stopCapture()
            captureOwnedByTransmit = false
        }
    }

    private fun releaseTrack() {
        val track = audioTrack ?: return
        audioTrack = null
        try { track.stop() } catch (ignored: Exception) {}
        try { track.release() } catch (ignored: Exception) {}
    }

    private class MicTarget(
        val device: AudioDeviceInfo?,
        val sources: IntArray,
        val rateCandidates: IntArray
    )

    /**
     * Alege microfonul cascii Bluetooth (SCO) daca legatura chiar e conectata acum, altfel
     * cade inapoi pe microfonul intern - fara asta, cu un device USB conectat, Android ar
     * putea ruta implicit captura spre USB (adica spre receptia de la statie, nu spre operator).
     *
     * SCO nu se initiaza aici - e pornit/oprit la nivel de mod (vezi EcranComenzi, pornit o
     * data la intrarea in LSB/USB, nu la fiecare apasare de PTT). [BluetoothScoState.connected]
     * e actualizata din broadcast-ul ACTION_SCO_AUDIO_STATE_UPDATED - mult mai fiabil decat sa
     * cautam un device BLUETOOTH_SCO in getDevices() (care pe multe telefoane nu apare deloc
     * in lista de intrari, desi legatura SCO functioneaza).
     *
     * Pentru SCO nu fixam un AudioDeviceInfo anume prin setPreferredDevice() - odata ce
     * AudioManager.mode=MODE_IN_COMMUNICATION si SCO e pornit (facut de EcranComenzi), un
     * AudioRecord cu sursa VOICE_COMMUNICATION se ruteaza automat spre SCO de catre politica
     * audio a sistemului; fixarea explicita a device-ului aici s-a dovedit nefiabila.
     */
    private fun selectMicTarget(): MicTarget {
        if (BluetoothScoState.connected) {
            return MicTarget(null, BT_SCO_SOURCES, BT_SCO_RATE_CANDIDATES)
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val inputs = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: emptyArray()
        val builtIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        return MicTarget(builtIn, MIC_SOURCES, CANDIDATE_RATES)
    }

    @SuppressLint("MissingPermission")
    private fun openMic(rate: Int, target: MicTarget): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null

        val bufBytes = minBuf.coerceAtLeast(bytesForMs(rate, TARGET_BUFFER_MS))

        for (source in target.sources) {
            val record = try {
                AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
            } catch (e: Exception) {
                null
            }

            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                if (target.device != null) {
                    try {
                        record.setPreferredDevice(target.device)
                    } catch (e: Exception) {
                        Log.w(TAG, "setPreferredDevice (${target.device.type}) esuat", e)
                    }
                }
                return record
            }
            record?.release()
        }
        return null
    }

    private fun findUsbOutputDevice(): AudioDeviceInfo? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type in USB_DEVICE_TYPES }
    }

    private fun buildUsbTrack(rate: Int, usbOutput: AudioDeviceInfo): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = minBuf.coerceAtLeast(bytesForMs(rate, TARGET_BUFFER_MS))

        // Experimentăm cu USAGE_MEDIA chiar și pentru SCO - USAGE_VOICE_COMMUNICATION
        // tinde să forțeze rutarea ieșirii înapoi în cască (sidetone), ignorând USB-ul.
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            track.setPreferredDevice(usbOutput)
        } catch (e: Exception) {
            Log.w(TAG, "setPreferredDevice (USB out) esuat", e)
        }
        return track
    }


    /**
     * Blocheaza (pe thread-ul apelant - de obicei UI, la apasarea PTT) pana cand [track] apare
     * efectiv rutat spre [usbOutput], sau pana la [ROUTING_WAIT_MS]. Fara acest gard, EcranComenzi
     * ar putea cheia statia (RTS/DTR) inainte ca politica audio sa fi terminat rutarea catre USB.
     * Daca timeout-ul expira fara confirmare, continuam oricum (mai bine PTT cu risc de "aer mort"
     * la inceput decat PTT blocat definitiv - vezi log-ul de avertizare pentru diagnosticare).
     */
    private fun waitForRouting(track: AudioTrack, usbOutput: AudioDeviceInfo) {
        val deadline = System.currentTimeMillis() + ROUTING_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val routedType = track.routedDevice?.type
            if (routedType == usbOutput.type) return
            try {
                Thread.sleep(ROUTING_POLL_MS)
            } catch (ignored: InterruptedException) {
                return
            }
        }
        Log.w(
            TAG,
            "Ruta USB nu s-a confirmat in ${ROUTING_WAIT_MS}ms " +
                "(routedDevice type=${track.routedDevice?.type}, asteptat=${usbOutput.type}) - continui oricum"
        )
    }

    private fun pickSupportedRate(candidates: IntArray): Int {
        for (rate in candidates) {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf > 0) return rate
        }
        return candidates.lastOrNull() ?: 44100
    }

    private fun bytesForMs(rate: Int, ms: Int): Int = (rate * ms / 1000) * 2 // 16-bit mono

    /**
     * Resampling liniar (interpolare), suficient de bun pentru voce - aduce mereu ce trimitem
     * catre USB la [OUTPUT_RATE], indiferent de rata nativa a microfonului sursa (16000/8000Hz
     * pe SCO Bluetooth, care majoritatea interfetelor audio USB nu o accepta ca rata de iesire).
     * Pastreaza [resampleCursor]/[resamplePrev] intre apeluri pentru continuitate intre chunk-uri,
     * resetate la fiecare inceput de emisie noua (vezi [startTransmit]).
     */
    private fun resample(input: ShortArray, count: Int, inRate: Int, outRate: Int): ShortArray {
        if (inRate == outRate) return if (count == input.size) input else input.copyOf(count)

        val step = inRate.toFloat() / outRate
        val combined = ShortArray(count + 1)
        combined[0] = resamplePrev
        System.arraycopy(input, 0, combined, 1, count)

        val outCount = (count / step).toInt()
        val out = ShortArray(outCount)
        var pos = resampleCursor
        for (i in 0 until outCount) {
            val idx = pos.toInt().coerceIn(0, count - 1)
            val frac = pos - idx
            val a = combined[idx]
            val b = combined[idx + 1]
            out[i] = (a + (b - a) * frac).toInt().toShort()
            pos += step
        }
        resampleCursor = (pos - count).coerceAtLeast(0f)
        resamplePrev = input[count - 1]
        return out
    }

    companion object {
        private const val TAG = "MicUplinkPlayer"
        private const val TARGET_BUFFER_MS = 100

        /** Rata fixa de iesire catre USB - larg suportata, indiferent de sursa microfonului. */
        private const val OUTPUT_RATE = 48000

        /** Timp maxim de asteptare (ms) ca AudioTrack sa se confirme rutat spre USB inainte de
         * a cheia statia - vezi [waitForRouting]. */
        private const val ROUTING_WAIT_MS = 300L
        private const val ROUTING_POLL_MS = 20L

        private val CANDIDATE_RATES = intArrayOf(48000, 44100, 16000)

        /** Aceeasi prioritate ca AudioRecorder: evitam AGC/NS-ul Android cat se poate. */
        private val MIC_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        /** Legatura SCO e narrowband (8kHz clasic, 16kHz doar cu mSBC/HD Voice pe ambele
         * capete) - ratele si sursa difera fata de microfonul intern.
         * Incercam MIC inainte de VOICE_COMMUNICATION - pe unele device-uri Samsung,
         * VOICE_COMMUNICATION forțează un profil de tip "Call" care blochează rutarea
         * ieșirii către USB. */
        private val BT_SCO_RATE_CANDIDATES = intArrayOf(16000, 8000)
        private val BT_SCO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )

        private val USB_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        )
    }
}
