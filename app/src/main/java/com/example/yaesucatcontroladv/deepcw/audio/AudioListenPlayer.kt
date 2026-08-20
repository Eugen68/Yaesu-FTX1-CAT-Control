package com.example.yaesucatcontroladv.deepcw.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.example.yaesucatcontroladv.deepcw.denoise.CwDenoiser
import com.example.yaesucatcontroladv.deepcw.denoise.Denoiser
import com.example.yaesucatcontroladv.deepcw.denoise.VoiceDenoiser
import com.example.yaesucatcontroladv.deepcw.dsp.Constants.SAMPLE_RATE

/**
 * Reda in timp real audio-ul captat de la statie pe difuzorul telefonului.
 * Include denoise optional folosind nucleul HamNoise.
 *
 * HamNoise are modele separate pentru CW (ton ingust) si voce (SSB, banda larga) - un
 * singur model aplicat pe modul gresit suna prost (mismatch spectral). De aceea alegerea
 * modelului activ urmeaza modul CAT curent al statiei (vezi [setRadioMode]), nu e o
 * optiune manuala: CW -> model CW, LSB/USB -> model voce, orice alt mod -> bypass (fara
 * model potrivit, nu are sens sa fortam unul dintre ele).
 */
class AudioListenPlayer(private val context: Context) {

    enum class StartResult { OK, NO_USB_AUDIO, CAPTURE_FAILED, ALREADY_RUNNING }

    private enum class RadioModeCategory { CW, VOICE, OTHER }

    private val lock = Any()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var denoiseEnabled: Boolean = false
        private set

    @Volatile
    var wetLevel: Float = 1.0f

    @Volatile
    var outputGain: Float = 1.0f

    private var bufferMs: Int = 90

    private var audioRecorder: AudioRecorder? = null
    private var audioTrack: AudioTrack? = null
    private val cwDenoiser: Denoiser = CwDenoiser()
    private val voiceDenoiser: Denoiser = VoiceDenoiser()

    @Volatile
    private var modeCategory: RadioModeCategory = RadioModeCategory.OTHER

    /** Denoiser-ul potrivit modului CAT curent, sau null daca modul nu are model dedicat. */
    private fun activeDenoiser(): Denoiser? = when (modeCategory) {
        RadioModeCategory.CW -> cwDenoiser
        RadioModeCategory.VOICE -> voiceDenoiser
        RadioModeCategory.OTHER -> null
    }

    /**
     * Actualizeaza modul CAT curent (cod Yaesu "MD", ex. "01"=LSB, "02"=USB, "03"/"07"=CW).
     * Determina ce model de denoise se aplica - CW, voce, sau niciunul (bypass).
     *
     * @return true daca modul nou nu are model dedicat si DENOISE era pornit - in acest caz
     * e oprit automat aici, iar apelantul trebuie sa sincronizeze UI-ul butonului (gri/OFF).
     */
    fun setRadioMode(catModeCode: String): Boolean {
        val next = when {
            catModeCode.startsWith("01") || catModeCode.startsWith("02") -> RadioModeCategory.VOICE
            catModeCode.startsWith("03") || catModeCode.startsWith("07") -> RadioModeCategory.CW
            else -> RadioModeCategory.OTHER
        }
        if (next == modeCategory) return false
        modeCategory = next

        if (next == RadioModeCategory.OTHER) {
            if (denoiseEnabled) {
                denoiseEnabled = false
                return true
            }
            return false
        }

        if (isRunning && denoiseEnabled) {
            val active = activeDenoiser()
            if (active != null && !active.isAvailable) {
                if (active.initialize()) active.reset() else denoiseEnabled = false
            }
        }
        return false
    }

    /** @return true daca starea a fost aplicata (init nativ reusit, sau enabled=false). */
    fun setDenoiseEnabled(enabled: Boolean): Boolean {
        if (!isRunning) {
            denoiseEnabled = enabled
            return true
        }

        return if (enabled) {
            val active = activeDenoiser()
            val ok = active == null || active.initialize()
            if (ok) {
                active?.reset()
                denoiseEnabled = true
            }
            ok
        } else {
            denoiseEnabled = false
            true
        }
    }

    fun setBufferMs(ms: Int): Boolean = synchronized(lock) {
        val newMs = ms.coerceIn(40, 160)
        if (bufferMs == newMs) return true
        bufferMs = newMs
        
        if (!isRunning) return true
        
        Log.i("AudioListenPlayer", "Rebuilding AudioTrack for $bufferMs ms buffer")
        val old = audioTrack
        val fresh = try {
            buildAudioTrack()
        } catch (e: Exception) {
            Log.e("AudioListenPlayer", "Rebuild AudioTrack failed", e)
            return false
        }
        
        fresh.play()
        audioTrack = fresh
        
        old?.let {
            try { it.stop() } catch (ignored: Exception) {}
            it.release()
        }
        true
    }

    fun start(): StartResult = synchronized(lock) {
        if (isRunning) return StartResult.ALREADY_RUNNING
        if (!AudioRecorder.isUsbAudioInputConnected(context)) return StartResult.NO_USB_AUDIO

        val track = try {
            buildAudioTrack()
        } catch (e: Exception) {
            Log.e("AudioListenPlayer", "Failed to build AudioTrack", e)
            return StartResult.CAPTURE_FAILED
        }
        
        track.play()

        if (denoiseEnabled) {
            val active = activeDenoiser()
            if (active != null) {
                val ok = active.initialize()
                if (ok) active.reset() else denoiseEnabled = false
            }
        }

        val recorder = AudioRecorder(context) { chunk -> onAudioChunk(chunk) }
        if (!recorder.start()) {
            try { track.stop() } catch (ignored: Exception) {}
            track.release()
            return StartResult.CAPTURE_FAILED
        }

        audioTrack = track
        audioRecorder = recorder
        isRunning = true
        return StartResult.OK
    }

    private fun buildAudioTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        
        val bytesForMs = (SAMPLE_RATE * bufferMs / 1000) * 2
        val bufferSize = maxOf(minBuf, bytesForMs)
        
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun onAudioChunk(chunk: FloatArray) = synchronized(lock) {
        if (!isRunning) return@synchronized
        
        val track = audioTrack ?: return@synchronized
        
        val active = if (denoiseEnabled) activeDenoiser() else null
        val processed = if (active != null && active.isAvailable) {
            val denoised = active.process(chunk, wetLevel)
            if (denoised.isEmpty()) denoised else {
                val g = outputGain
                if (g != 1f) {
                    for (i in denoised.indices) {
                        denoised[i] = (denoised[i] * g).coerceIn(-1f, 1f)
                    }
                }
                denoised
            }
        } else {
            chunk
        }
        
        if (processed.isEmpty()) return@synchronized
        
        val pcm = ShortArray(processed.size)
        for (i in processed.indices) {
            val s = (processed[i] * 32767f).coerceIn(-32768f, 32767f)
            pcm[i] = s.toInt().toShort()
        }
        
        try {
            track.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.w("AudioListenPlayer", "Error writing to AudioTrack", e)
        }
        Unit
    }

    fun stop() = synchronized(lock) {
        if (!isRunning) return
        isRunning = false
        
        audioRecorder?.stop()
        audioRecorder = null
        
        audioTrack?.let {
            try { it.stop() } catch (ignored: Exception) {}
            it.release()
        }
        audioTrack = null

        cwDenoiser.release()
        voiceDenoiser.release()
    }
}
