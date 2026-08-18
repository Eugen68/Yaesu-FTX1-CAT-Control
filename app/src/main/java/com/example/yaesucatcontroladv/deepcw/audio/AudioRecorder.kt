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

/**
 * Captare audio pentru decodorul CW.
 *
 * - captura la rata nativa suportata (48000 > 19200 > 9600 > 44100 > 16000), NU 16000 fix;
 * - filtru anti-aliasing (1 pol, cutoff 0.9 * Nyquist tinta) inainte de decimare;
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

    private var lpState = 0f
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
        lpState = 0f
        resampleCursor = 0f
        chunkFill = 0

        registerDeviceCallback()

        val integerDecimation = captureRate % SAMPLE_RATE == 0
        val decimationFactor = if (integerDecimation) captureRate / SAMPLE_RATE else 1
        val resampleStep = captureRate.toFloat() / SAMPLE_RATE

        // Filtru trece-jos de un pol, cutoff sub Nyquist-ul ratei tinta.
        val cutoffHz = (SAMPLE_RATE / 2f) * 0.9f
        val dt = 1f / captureRate
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        val alpha = dt / (rc + dt)

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

                var filtered = lpState
                for (i in 0 until read) {
                    val sample = rawBuffer[i] / 32768.0f
                    filtered += alpha * (sample - filtered)
                    floatBuffer[i] = filtered
                }
                lpState = filtered

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

    private companion object {
        const val TAG = "AudioRecorder"

        /**
         * Doar rate care se impart exact la 9600 sunt preferate (decimare intreaga, fara
         * eroare de rezolutie de frecventa). 44100/16000 sunt ultimul resort, cu
         * reeșantionare liniara.
         */
        val CAPTURE_RATES = intArrayOf(48000, 19200, 9600, 44100, 16000)

        val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        val USB_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        )
    }
}
