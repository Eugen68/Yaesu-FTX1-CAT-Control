package com.example.yaesucatcontroladv.deepcw.denoise

import android.util.Log

internal object CwDenoiserNative {
    init {
        try {
            System.loadLibrary("hamnoise_cw")
        } catch (e: Throwable) {
            Log.e("CwDenoiserNative", "Failed to load hamnoise_cw library: ${e.message}")
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
 * High-level wrapper for the HamNoise CW Denoiser.
 * Handles audio buffering (carry) to match the internal hop size.
 *
 * Motorul nativ NU e thread-safe (stare mutabila in `denoise_stream_t`), iar in practica
 * apelurile vin de pe doua thread-uri diferite: `process()` de pe thread-ul de citire audio
 * (AudioRecorder), iar `initialize()`/`reset()`/`release()` de pe corutinele
 * MorseAudioProcessor (Dispatchers.IO/Default). Fara sincronizare, `release()` in timpul unui
 * `process()` in curs inseamna use-after-free (SIGSEGV) pe handle-ul nativ deja eliberat.
 * Lock-ul de mai jos e practic necontestat (un hop la ~15 ms) deci costul e neglijabil.
 */
class CwDenoiser {
    private val lock = Any()

    @Volatile
    private var handle: Long = 0
    private var hopLength: Int = 0

    private var carry: FloatArray = FloatArray(0)

    private var hopIn: FloatArray? = null
    private var hopOut: FloatArray? = null

    val isAvailable: Boolean get() = handle != 0L

    fun initialize(): Boolean = synchronized(lock) {
        if (handle != 0L) return true

        try {
            val created = CwDenoiserNative.nativeCreate()
            if (created != 0L) {
                hopLength = CwDenoiserNative.nativeHopLength()
                hopIn = FloatArray(hopLength)
                hopOut = FloatArray(hopLength)
                carry = FloatArray(0)
                // handle devine != 0 doar dupa ce bufferele sunt gata, ca `process()` de pe
                // thread-ul audio sa nu vada niciodata un handle valid cu buffere lipsa.
                handle = created
                Log.i("CwDenoiser", "Native denoiser initialized. Hop length: $hopLength")
            }
        } catch (e: Throwable) {
            Log.e("CwDenoiser", "Failed to initialize native denoiser: ${e.message}")
            handle = 0L
        }

        return isAvailable
    }

    /**
     * Processes an audio chunk. Returns the filtered audio.
     * Denoising is performed in hops of fixed size.
     * @param wet Mix level: 1.0 = fully denoised, 0.0 = original audio.
     */
    fun process(chunk: FloatArray, wet: Float = 1.0f): FloatArray = synchronized(lock) {
        val h = handle
        val hl = hopLength
        val hi = hopIn
        val ho = hopOut
        
        if (h == 0L || hl == 0 || hi == null || ho == null) return chunk

        // Merge carry with new data
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
            
            val status = CwDenoiserNative.nativeProcessHop(h, hi, ho)
            if (status == 1) {
                // Model produced valid output (post-warmup).
                // Apply wet/dry mix if needed.
                if (wet < 1.0f) {
                    for (i in 0 until hl) {
                        ho[i] = hi[i] * (1f - wet) + ho[i] * wet
                    }
                }
                outputChunks.add(ho.copyOf())
            } else if (status == 0) {
                // Warmup period - output is zeroed out by native code, 
                // but we skip emitting it to avoid silent gaps at the start of a stream.
            } else {
                // Error - pass through original hop if desired, or skip
                Log.w("CwDenoiser", "nativeProcessHop returned error status: $status")
            }
            
            offset += hl
        }

        // Save remaining samples
        val remainingCount = totalInput.size - offset
        carry = if (remainingCount > 0) {
            totalInput.copyOfRange(offset, totalInput.size)
        } else {
            FloatArray(0)
        }

        if (outputChunks.isEmpty()) return FloatArray(0)
        
        // Assemble output
        val finalOutput = FloatArray(outputChunks.size * hl)
        var writePos = 0
        for (arr in outputChunks) {
            arr.copyInto(finalOutput, writePos)
            writePos += hl
        }
        
        return finalOutput
    }

    fun reset() = synchronized(lock) {
        if (handle != 0L) {
            CwDenoiserNative.nativeReset(handle)
            carry = FloatArray(0)
        }
    }

    fun release() = synchronized(lock) {
        if (handle != 0L) {
            val h = handle
            handle = 0L
            CwDenoiserNative.nativeDestroy(h)
            carry = FloatArray(0)
            hopIn = null
            hopOut = null
            hopLength = 0
        }
    }
}
