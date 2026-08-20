package com.example.yaesucatcontroladv.deepcw.denoise

import android.util.Log

internal object VoiceDenoiserNative {
    init {
        try {
            System.loadLibrary("hamnoise_cw")
        } catch (e: Throwable) {
            Log.e("VoiceDenoiserNative", "Failed to load hamnoise_cw library: ${e.message}")
        }
    }

    external fun nativeCreate(): Long
    external fun nativeReset(handle: Long)
    external fun nativeProcessHop(handle: Long, input: FloatArray, output: FloatArray): Int
    external fun nativeDestroy(handle: Long)
    external fun nativeHopLength(): Int
    external fun nativeSampleRate(): Int
}

/**
 * High-level wrapper for the HamNoise Voice (SSB) Denoiser. Oglinda exacta a [CwDenoiser],
 * dar peste modelul de voce (`k_voice_reduction_model`, front-end band-split) din
 * `jni_bridge_voice.c`. Aceeasi biblioteca nativa `hamnoise_cw` exporta ambele seturi de
 * simboluri JNI (Cw* si Voice*), deci System.loadLibrary e sigur de apelat din ambele
 * obiecte - a doua incarcare e no-op.
 *
 * Threading: identic cu CwDenoiser - motorul nativ nu e thread-safe, lock-ul de mai jos
 * protejeaza impotriva unui `release()` concurent cu un `process()` in curs.
 */
class VoiceDenoiser : Denoiser {
    private val lock = Any()

    @Volatile
    private var handle: Long = 0
    private var hopLength: Int = 0

    private var carry: FloatArray = FloatArray(0)

    private var hopIn: FloatArray? = null
    private var hopOut: FloatArray? = null

    override val isAvailable: Boolean get() = handle != 0L

    override fun initialize(): Boolean = synchronized(lock) {
        if (handle != 0L) return true

        try {
            val created = VoiceDenoiserNative.nativeCreate()
            if (created != 0L) {
                hopLength = VoiceDenoiserNative.nativeHopLength()
                hopIn = FloatArray(hopLength)
                hopOut = FloatArray(hopLength)
                carry = FloatArray(0)
                handle = created
                Log.i("VoiceDenoiser", "Native denoiser initialized. Hop length: $hopLength")
            }
        } catch (e: Throwable) {
            Log.e("VoiceDenoiser", "Failed to initialize native denoiser: ${e.message}")
            handle = 0L
        }

        return isAvailable
    }

    override fun process(chunk: FloatArray, wet: Float): FloatArray = synchronized(lock) {
        val h = handle
        val hl = hopLength
        val hi = hopIn
        val ho = hopOut

        if (h == 0L || hl == 0 || hi == null || ho == null) return chunk

        val totalInput = if (carry.isEmpty()) {
            chunk
        } else {
            val combined = FloatArray(carry.size + chunk.size)
            carry.copyInto(combined)
            chunk.copyInto(combined, carry.size)
            combined
        }

        var offset = 0
        val outputChunks = mutableListOf<FloatArray>()

        while (offset + hl <= totalInput.size) {
            totalInput.copyInto(hi, 0, offset, offset + hl)

            val status = VoiceDenoiserNative.nativeProcessHop(h, hi, ho)
            if (status == 1) {
                if (wet < 1.0f) {
                    for (i in 0 until hl) {
                        ho[i] = hi[i] * (1f - wet) + ho[i] * wet
                    }
                }
                outputChunks.add(ho.copyOf())
            } else if (status == 0) {
                // Warmup - iesirea e zero, o sarim ca sa evitam o gaura de tacere la start.
            } else {
                Log.w("VoiceDenoiser", "nativeProcessHop returned error status: $status")
            }

            offset += hl
        }

        val remainingCount = totalInput.size - offset
        carry = if (remainingCount > 0) {
            totalInput.copyOfRange(offset, totalInput.size)
        } else {
            FloatArray(0)
        }

        if (outputChunks.isEmpty()) return FloatArray(0)

        val finalOutput = FloatArray(outputChunks.size * hl)
        var writePos = 0
        for (arr in outputChunks) {
            arr.copyInto(finalOutput, writePos)
            writePos += hl
        }

        return finalOutput
    }

    override fun reset() = synchronized(lock) {
        if (handle != 0L) {
            VoiceDenoiserNative.nativeReset(handle)
            carry = FloatArray(0)
        }
    }

    override fun release() = synchronized(lock) {
        if (handle != 0L) {
            val h = handle
            handle = 0L
            VoiceDenoiserNative.nativeDestroy(h)
            carry = FloatArray(0)
            hopIn = null
            hopOut = null
            hopLength = 0
        }
    }
}
