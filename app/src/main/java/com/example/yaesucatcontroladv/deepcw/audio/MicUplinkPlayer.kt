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
 * Traseul de emisie (TX) pentru SSB: microfonul intern al telefonului -> iesire audio USB
 * catre statie. Spre deosebire de [AudioListenPlayer] (care ruleaza continuu cat timp e
 * pornit din UI), acesta porneste/opreste direct odata cu apasarea/eliberarea PTT - nu are
 * sens sa capturam microfonul in afara ferestrei de emisie.
 *
 * Nu foloseste [AudioRecorder] (acela citeste DE LA USB si resampleaza la 9600Hz pentru
 * modelele CW/denoise) - aici e traseul opus: citim de la microfonul intern, scriem CATRE
 * USB, fara resampling (rata de captura = rata de reda re, aleasa din ce accepta microfonul).
 * Fiind mic intern -> USB (nu casca -> mic intern), nu exista risc de bucla de feedback cu
 * receptia (care merge de obicei pe casti Bluetooth).
 */
class MicUplinkPlayer(private val context: Context) {

    enum class StartResult { OK, NO_USB_AUDIO, PERMISSION_DENIED, CAPTURE_FAILED, ALREADY_RUNNING }

    @Volatile
    var isRunning: Boolean = false
        private set

    /** Microfonul folosit efectiv la ultima pornire (Bluetooth SCO sau intern) - util pentru UI. */
    @Volatile
    var activeMicIsBluetooth: Boolean = false
        private set

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var resampleCursor = 0f
    private var resamplePrev: Short = 0

    @SuppressLint("MissingPermission")
    fun start(): StartResult {
        if (isRunning) return StartResult.ALREADY_RUNNING

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return StartResult.PERMISSION_DENIED
        }

        val usbOutput = findUsbOutputDevice() ?: return StartResult.NO_USB_AUDIO
        val micTarget = selectMicTarget()
        activeMicIsBluetooth = BluetoothScoState.connected
        val rate = pickSupportedRate(micTarget.rateCandidates)

        val record = try {
            openMic(rate, micTarget)
        } catch (e: Exception) {
            Log.e(TAG, "Deschidere microfon esuata", e)
            null
        } ?: return StartResult.CAPTURE_FAILED

        // Track-ul de iesire ruleaza mereu la o rata fixa, larg suportata de interfetele
        // audio USB (multe nu accepta deloc 16000/8000Hz, rata nativa a legaturii SCO) -
        // resample-uim intre captura si scriere daca ratele difera (vezi bucla de mai jos).
        resampleCursor = 0f
        resamplePrev = 0

        val track = try {
            buildUsbTrack(OUTPUT_RATE, usbOutput, useCommUsage = activeMicIsBluetooth)
        } catch (e: Exception) {
            Log.e(TAG, "Deschidere AudioTrack USB esuata", e)
            record.release()
            return StartResult.CAPTURE_FAILED
        }

        try {
            record.startRecording()
            track.play()
        } catch (e: Exception) {
            Log.e(TAG, "Pornire captura/reda re esuata", e)
            try { record.release() } catch (ignored: Exception) {}
            try { track.release() } catch (ignored: Exception) {}
            return StartResult.CAPTURE_FAILED
        }

        audioRecord = record
        audioTrack = track
        isRunning = true

        job = scope.launch {
            val chunkSamples = (rate / 10).coerceAtLeast(256) // ~100ms
            val buf = ShortArray(chunkSamples)
            var lastLogMs = 0L
            var peakSinceLog = 0
            while (isRunning) {
                val read = try {
                    record.read(buf, 0, buf.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Eroare citire microfon", e)
                    break
                }
                if (read <= 0) continue

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

                val toWrite = if (rate == OUTPUT_RATE) buf else resample(buf, read, rate, OUTPUT_RATE)
                try {
                    track.write(toWrite, 0, toWrite.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Eroare scriere AudioTrack USB", e)
                    break
                }
            }
        }

        return StartResult.OK
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        job?.cancel()
        job = null

        try { audioRecord?.stop() } catch (ignored: Exception) {}
        try { audioRecord?.release() } catch (ignored: Exception) {}
        audioRecord = null

        try { audioTrack?.stop() } catch (ignored: Exception) {}
        try { audioTrack?.release() } catch (ignored: Exception) {}
        audioTrack = null
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

    private fun buildUsbTrack(rate: Int, usbOutput: AudioDeviceInfo, useCommUsage: Boolean): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = minBuf.coerceAtLeast(bytesForMs(rate, TARGET_BUFFER_MS))

        // Cat timp sursa e SCO Bluetooth, telefonul e in MODE_IN_COMMUNICATION (practic "in
        // apel") - un track USAGE_MEDIA obisnuit poate fi atenuat/ignorat de politica audio
        // in acest context, chiar daca e rutat corect. USAGE_VOICE_COMMUNICATION se potriveste
        // cu semantica intrarii (si a modului sistemului) si ar trebui tratat ca parte a "apelului".
        val attrs = if (useCommUsage) {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        } else {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        }

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
     * Pastreaza [resampleCursor]/[resamplePrev] intre apeluri pentru continuitate intre chunk-uri.
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

        private val CANDIDATE_RATES = intArrayOf(48000, 44100, 16000)

        /** Aceeasi prioritate ca AudioRecorder: evitam AGC/NS-ul Android cat se poate. */
        private val MIC_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        /** Legatura SCO e narrowband (8kHz clasic, 16kHz doar cu mSBC/HD Voice pe ambele
         * capete) - ratele si sursa difera fata de microfonul intern. VOICE_COMMUNICATION e
         * sursa pe care stack-ul Bluetooth Android o ruteaza cel mai fiabil spre SCO. */
        private val BT_SCO_RATE_CANDIDATES = intArrayOf(16000, 8000)
        private val BT_SCO_SOURCES = intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)

        private val USB_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        )
    }
}
