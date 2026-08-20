package com.example.yaesucatcontroladv.deepcw.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.AUDIO_CHUNK_SAMPLES
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.SAMPLE_RATE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Captare audio pentru decodorul CW.
 *
 * - captura la rata nativa suportata (48000 > 19200 > 9600 > 44100 > 16000), NU 16000 fix;
 * - filtru anti-aliasing (Butterworth de ordin 4, 2 biquad-uri RBJ in cascada,
 *   cutoff 0.9 * Nyquist tinta, ~-24dB/octava) inainte de decimare - single-pole
 *   (-6dB/octava) lasa aliasing vizibil pe calea cea mai comuna (48000 -> 9600, decimare x5);
 * - decimare intreaga cand rata e multiplu de 9600, altfel reeșantionare liniara;
 * - sursa audio: UNPROCESSED > VOICE_RECOGNITION > MIC (fara AGC/NS pe cat posibil);
 * - device preferat: interfata USB audio, altfel microfonul intern;
 * - reconectare live la conectare/deconectare de device USB.
 *
 * Livreaza chunk-uri de exact AUDIO_CHUNK_SAMPLES eșantioane la 9600 Hz (ca in referinta).
 */
class AudioRecorder(
    private val context: Context,
    private val onBufferAvailable: (FloatArray) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private var deviceCallbackThread: HandlerThread? = null
    private var deviceCallback: AudioDeviceCallback? = null

    private val antiAliasFilter = BiquadLowpassCascade()
    private var resampleCursor = 0f

    private val chunkBuffer = FloatArray(AUDIO_CHUNK_SAMPLES)
    private var chunkFill = 0

    @Synchronized
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording) return true

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Permisiunea RECORD_AUDIO lipseste")
            return false
        }

        val opened = openRecorder() ?: return false
        val (record, captureRate) = opened
        audioRecord = record

        applyPreferredDevice(record)

        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording a eșuat", e)
            releaseRecord()
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.i(TAG, "Rutat pe device: ${describeDevice(record.routedDevice)}")
        }

        isRecording = true
        resampleCursor = 0f
        chunkFill = 0

        registerDeviceCallback()

        val integerDecimation = captureRate % SAMPLE_RATE == 0
        val decimationFactor = if (integerDecimation) captureRate / SAMPLE_RATE else 1
        val resampleStep = captureRate.toFloat() / SAMPLE_RATE

        // Filtru anti-aliasing trece-jos, cutoff sub Nyquist-ul ratei tinta.
        val cutoffHz = (SAMPLE_RATE / 2f) * 0.9f
        antiAliasFilter.configure(cutoffHz, captureRate.toFloat())

        val readSize = (captureRate / 10).coerceAtLeast(1024)

        recordingJob = scope.launch {
            val rawBuffer = ShortArray(readSize)
            val floatBuffer = FloatArray(readSize)

            while (isRecording) {
                val read = try {
                    audioRecord?.read(rawBuffer, 0, rawBuffer.size) ?: 0
                } catch (e: Exception) {
                    Log.e(TAG, "Eroare la citirea din AudioRecord", e)
                    break
                }

                if (read < 0) {
                    Log.e(TAG, "AudioRecord read a returnat $read")
                    break
                }
                if (read == 0) continue

                for (i in 0 until read) {
                    val sample = rawBuffer[i] / 32768.0f
                    floatBuffer[i] = antiAliasFilter.process(sample)
                }

                if (integerDecimation) {
                    var i = 0
                    while (i < read) {
                        emitSample(floatBuffer[i])
                        i += decimationFactor
                    }
                } else {
                    var cursor = resampleCursor
                    while (cursor < read) {
                        val index = cursor.toInt()
                        val frac = cursor - index
                        val next = if (index + 1 < read) floatBuffer[index + 1] else floatBuffer[index]
                        emitSample(floatBuffer[index] * (1f - frac) + next * frac)
                        cursor += resampleStep
                    }
                    resampleCursor = cursor - read
                }
            }
        }

        return true
    }

    private fun emitSample(sample: Float) {
        chunkBuffer[chunkFill++] = sample
        if (chunkFill == chunkBuffer.size) {
            onBufferAvailable(chunkBuffer.copyOf())
            chunkFill = 0
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): Pair<AudioRecord, Int>? {
        for (captureRate in CAPTURE_RATES) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                captureRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) continue

            for (source in AUDIO_SOURCES) {
                val record = try {
                    AudioRecord(
                        source,
                        captureRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufferSize.coerceAtLeast(captureRate * 2)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord(${sourceName(source)}, $captureRate) a eșuat", e)
                    null
                }

                if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(
                        TAG,
                        "Captura audio: ${sourceName(source)} @ $captureRate Hz " +
                            "(decimare " +
                            (if (captureRate % SAMPLE_RATE == 0) "intreaga x${captureRate / SAMPLE_RATE}"
                            else "liniara ${"%.3f".format(captureRate.toFloat() / SAMPLE_RATE)}") +
                            " -> $SAMPLE_RATE Hz)"
                    )
                    return record to captureRate
                }

                record?.release()
            }
        }

        Log.e(TAG, "Nicio combinatie de sursa/rata de captura nu a putut fi initializata")
        return null
    }

    private fun applyPreferredDevice(record: AudioRecord) {
        val device = selectPreferredInputDevice() ?: return
        val applied = try {
            record.setPreferredDevice(device)
        } catch (e: Exception) {
            Log.w(TAG, "setPreferredDevice a eșuat", e)
            false
        }
        Log.i(TAG, "Device preferat ${describeDevice(device)} aplicat=$applied")
    }

    private fun selectPreferredInputDevice(): AudioDeviceInfo? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return null
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return devices.firstOrNull { it.type in USB_DEVICE_TYPES }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    private fun registerDeviceCallback() {
        if (deviceCallback != null) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return

        val thread = HandlerThread("cw-audio-devices").apply { start() }
        val handler = Handler(thread.looper)

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                if (addedDevices?.any { it.type in USB_DEVICE_TYPES } == true) {
                    Log.i(TAG, "Device USB audio conectat - reevaluez sursa de captura")
                    restart()
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                if (removedDevices?.any { it.type in USB_DEVICE_TYPES } == true) {
                    Log.i(TAG, "Device USB audio deconectat - reevaluez sursa de captura")
                    restart()
                }
            }
        }

        audioManager.registerAudioDeviceCallback(callback, handler)
        deviceCallback = callback
        deviceCallbackThread = thread
    }

    private fun unregisterDeviceCallback() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        deviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        deviceCallbackThread?.quitSafely()
        deviceCallbackThread = null
    }

    /** Re-porneste captura pentru a reevalua device-ul preferat (hot-plug USB). */
    @Synchronized
    private fun restart() {
        if (!isRecording) return
        stopInternal(keepDeviceCallback = true)
        if (!start()) {
            Log.e(TAG, "Repornirea capturii audio a eșuat")
        }
    }

    @Synchronized
    fun stop() {
        stopInternal(keepDeviceCallback = false)
    }

    private fun stopInternal(keepDeviceCallback: Boolean) {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        if (!keepDeviceCallback) unregisterDeviceCallback()
        releaseRecord()
        chunkFill = 0
    }

    private fun releaseRecord() {
        try {
            audioRecord?.stop()
        } catch (ignored: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (ignored: Exception) {
        }
        audioRecord = null
    }

    private fun describeDevice(device: AudioDeviceInfo?): String {
        if (device == null) return "necunoscut"
        val name = device.productName?.toString() ?: "?"
        return "$name (type=${device.type})"
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> "source#$source"
    }

    companion object {
        private const val TAG = "AudioRecorder"

        /**
         * Doar rate care se impart exact la 9600 sunt preferate (decimare intreaga, fara
         * eroare de rezolutie de frecventa). 44100/16000 sunt ultimul resort, cu
         * reeșantionare liniara.
         */
        private val CAPTURE_RATES = intArrayOf(48000, 19200, 9600, 44100, 16000)

        private val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        private val USB_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        )

        /** true daca exista cel putin un device de intrare audio USB conectat (nu microfonul intern). */
        fun isUsbAudioInputConnected(context: Context): Boolean {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return false
            return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .any { it.type in USB_DEVICE_TYPES }
        }
    }
}

/**
 * Filtru anti-aliasing trece-jos Butterworth de ordin 4, implementat ca 2 biquad-uri
 * RBJ (Q = sqrt(2)/2 pentru fiecare stagiu) in cascada. Panta de ~-24dB/octava e mult
 * mai apropiata de un resampler polyphase de calitate (ex. Web Audio API din referinta)
 * decat un simplu filtru cu un pol (-6dB/octava), care lasa aliasing audibil pe caile
 * de decimare mari (ex. 48000 -> 9600 Hz, factor 5).
 */
private class BiquadLowpassCascade {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private var x1a = 0f
    private var x2a = 0f
    private var y1a = 0f
    private var y2a = 0f
    private var x1b = 0f
    private var x2b = 0f
    private var y1b = 0f
    private var y2b = 0f

    fun configure(cutoffHz: Float, sampleRateHz: Float) {
        val q = (sqrt(2.0) / 2.0).toFloat()
        val omega = (2.0 * PI * cutoffHz / sampleRateHz).toFloat()
        val sinOmega = sin(omega.toDouble()).toFloat()
        val cosOmega = cos(omega.toDouble()).toFloat()
        val alpha = sinOmega / (2f * q)

        val rawB0 = (1f - cosOmega) / 2f
        val rawB1 = 1f - cosOmega
        val rawB2 = (1f - cosOmega) / 2f
        val a0 = 1f + alpha

        b0 = rawB0 / a0
        b1 = rawB1 / a0
        b2 = rawB2 / a0
        a1 = (-2f * cosOmega) / a0
        a2 = (1f - alpha) / a0

        x1a = 0f; x2a = 0f; y1a = 0f; y2a = 0f
        x1b = 0f; x2b = 0f; y1b = 0f; y2b = 0f
    }

    fun process(sample: Float): Float {
        val stage1 = b0 * sample + b1 * x1a + b2 * x2a - a1 * y1a - a2 * y2a
        x2a = x1a; x1a = sample
        y2a = y1a; y1a = stage1

        val stage2 = b0 * stage1 + b1 * x1b + b2 * x2b - a1 * y1b - a2 * y2b
        x2b = x1b; x1b = stage1
        y2b = y1b; y1b = stage2

        return stage2
    }
}
