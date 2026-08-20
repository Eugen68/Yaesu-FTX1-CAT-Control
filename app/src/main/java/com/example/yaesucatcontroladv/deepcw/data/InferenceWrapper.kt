package com.example.yaesucatcontroladv.deepcw.data

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer

/**
 * Wrapper ONNX Runtime. Modelul e copiat din assets in cache si incarcat de pe disc
 * (metoda cea mai fiabila pe Android pentru modele mari, 15 MB+).
 *
 * Validare (Faza 0.4 din plan): incarcarea NU propaga niciodata excepții catre apelant.
 * Daca fisierul e trunchiat/corupt/invalid, `createSession` arunca, iar noi doar logam si
 * lasam `session == null` -> `isAvailable == false`. Astfel un model stricat dezactiveaza
 * numai functionalitatea care depinde de el, fara sa dea crash aplicatiei. Nu se face
 * verificare de checksum: validarea e chiar construirea sesiunii, fara cost adaugat.
 *
 * Threading: fara `SessionOptions` explicit, ONNX Runtime Android poate rula sesiunea
 * single-threaded pe unele device-uri, ceea ce incetineste inferenta (relevant mai ales
 * in modul pileup, unde se ruleaza secvential pana la 5 inferente narrow per iteratie).
 * NNAPI (accelerare hardware) NU e folosit intentionat: driverele NNAPI variaza mult ca
 * suport/corectitudine intre producatori de telefoane, iar modelele CTC de aici au
 * dimensiune temporala dinamica - riscul unei acuratete degradate silentios pe unele
 * device-uri conteaza mai mult decat cateva ms castigate.
 */
class InferenceWrapper(private val context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    private val intraOpThreads: Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    val isAvailable: Boolean
        get() = session != null

    /** @return null la succes, altfel mesajul de eroare. */
    fun loadModel(modelPath: String): String? {
        if (session != null) return null

        val cacheFile = File(context.cacheDir, modelPath)

        try {
            context.assets.open(modelPath).use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nu s-a putut copia modelul $modelPath din assets", e)
            return "Model $modelPath indisponibil: ${e.message ?: "eroare de copiere"}"
        }

        return try {
            Log.d(TAG, "Se incarca $modelPath din ${cacheFile.absolutePath}")
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(intraOpThreads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env.createSession(cacheFile.absolutePath, options)
            null
        } catch (e: Exception) {
            // Fisier corupt / trunchiat / format invalid: dezactivam doar acest model.
            Log.e(TAG, "Model ONNX invalid: $modelPath", e)
            session = null
            try {
                cacheFile.delete()
            } catch (ignored: Exception) {
            }
            "Model $modelPath invalid: ${e.message ?: "eroare necunoscuta"}"
        } catch (e: Throwable) {
            // OrtException poate fi insotita de erori native (ex. OutOfMemoryError) -
            // nici acelea nu trebuie sa omoare aplicatia.
            Log.e(TAG, "Eroare fatala la incarcarea $modelPath", e)
            session = null
            "Model $modelPath nu a putut fi incarcat: ${e.message ?: "eroare necunoscuta"}"
        }
    }

    fun runInference(inputData: FloatArray, dims: LongArray): Pair<FloatArray, LongArray>? {
        val s = session ?: return null

        return try {
            val inputName = s.inputNames.iterator().next()
            val floatBuffer = FloatBuffer.wrap(inputData)
            val inputTensor = OnnxTensor.createTensor(env, floatBuffer, dims)

            inputTensor.use {
                s.run(mapOf(inputName to inputTensor)).use { output ->
                    val resultTensor = output.get(0) as OnnxTensor
                    val shape = resultTensor.info.shape
                    val flatOutput = FloatArray(resultTensor.floatBuffer.remaining())
                    resultTensor.floatBuffer.get(flatOutput)
                    Pair(flatOutput, shape)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eroare de inferenta", e)
            null
        }
    }

    fun close() {
        try {
            session?.close()
        } catch (ignored: Exception) {
        }
        session = null
    }

    private companion object {
        const val TAG = "InferenceWrapper"
    }
}
