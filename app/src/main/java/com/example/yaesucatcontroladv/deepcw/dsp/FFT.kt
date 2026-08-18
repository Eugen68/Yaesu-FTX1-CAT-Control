package com.example.yaesucatcontroladv.deepcw.dsp

import kotlin.math.*

class FFT(val fftSize: Int) {
    private val isPowerOfTwo: Boolean = (fftSize > 0) && (fftSize and (fftSize - 1) == 0)
    private var reverseTable: IntArray? = null
    private var sinTable: FloatArray? = null
    private var cosTable: FloatArray? = null
    private var bluesteinSize: Int? = null
    private var bluesteinChirpTable: FloatArray? = null
    private var bluesteinKernelFft: FloatArray? = null
    private var bluesteinFft: FFT? = null

    init {
        require(fftSize > 0) { "FFT size must be positive." }
        if (isPowerOfTwo) {
            initializePowerOfTwoTables()
        } else {
            initializeBluesteinTables()
        }
    }

    private fun initializePowerOfTwoTables() {
        reverseTable = IntArray(fftSize)
        sinTable = FloatArray(fftSize)
        cosTable = FloatArray(fftSize)

        var limit = 1
        var bit = fftSize shr 1
        while (limit < fftSize) {
            for (i in 0 until limit) {
                reverseTable!![i + limit] = reverseTable!![i] + bit
            }
            limit = limit shl 1
            bit = bit shr 1
        }

        for (i in 0 until fftSize) {
            val angle = (-2.0 * PI * i) / fftSize
            sinTable!![i] = sin(angle).toFloat()
            cosTable!![i] = cos(angle).toFloat()
        }
    }

    private fun initializeBluesteinTables() {
        val convolutionSize = nextPowerOfTwo(fftSize * 2 - 1)
        val chirpTable = FloatArray(fftSize * 2)
        val kernel = FloatArray(convolutionSize * 2)

        for (i in 0 until fftSize) {
            val angle = (PI * ((i.toLong() * i) % (fftSize * 2))) / fftSize
            val c = cos(angle).toFloat()
            val s = sin(angle).toFloat()

            chirpTable[i * 2] = c
            chirpTable[i * 2 + 1] = s

            kernel[i * 2] = c
            kernel[i * 2 + 1] = s
            if (i != 0) {
                val mirroredIndex = convolutionSize - i
                kernel[mirroredIndex * 2] = c
                kernel[mirroredIndex * 2 + 1] = s
            }
        }

        val bFft = FFT(convolutionSize)
        bFft.transform(kernel)

        bluesteinSize = convolutionSize
        bluesteinChirpTable = chirpTable
        bluesteinKernelFft = kernel
        bluesteinFft = bFft
    }

    fun transform(complexArray: FloatArray) {
        require(complexArray.size == fftSize * 2) { "FFT input length does not match configured FFT size." }
        if (isPowerOfTwo) {
            powerOfTwoTransform(complexArray)
        } else {
            bluesteinTransform(complexArray)
        }
    }

    private fun powerOfTwoTransform(complexArray: FloatArray) {
        val rev = reverseTable!!
        val sinT = sinTable!!
        val cosT = cosTable!!

        for (i in 0 until fftSize) {
            val reversedIndex = rev[i]
            if (i < reversedIndex) {
                var temp = complexArray[i * 2]
                complexArray[i * 2] = complexArray[reversedIndex * 2]
                complexArray[reversedIndex * 2] = temp

                temp = complexArray[i * 2 + 1]
                complexArray[i * 2 + 1] = complexArray[reversedIndex * 2 + 1]
                complexArray[reversedIndex * 2 + 1] = temp
            }
        }

        var halfSize = 1
        while (halfSize < fftSize) {
            val step = 2 * halfSize
            val angleStep = fftSize / step
            for (i in 0 until fftSize step step) {
                for (j in 0 until halfSize) {
                    val angleIndex = j * angleStep
                    val wReal = cosT[angleIndex]
                    val wImag = sinT[angleIndex]

                    val ij = (i + j) * 2
                    val ijHalf = (i + j + halfSize) * 2

                    val tr = wReal * complexArray[ijHalf] - wImag * complexArray[ijHalf + 1]
                    val ti = wReal * complexArray[ijHalf + 1] + wImag * complexArray[ijHalf]

                    val ur = complexArray[ij]
                    val ui = complexArray[ij + 1]

                    complexArray[ij] = ur + tr
                    complexArray[ij + 1] = ui + ti
                    complexArray[ijHalf] = ur - tr
                    complexArray[ijHalf + 1] = ui - ti
                }
            }
            halfSize *= 2
        }
    }

    private fun bluesteinTransform(complexArray: FloatArray) {
        val convSize = bluesteinSize!!
        val chirpT = bluesteinChirpTable!!
        val kernelF = bluesteinKernelFft!!
        val bFft = bluesteinFft!!
        val work = FloatArray(convSize * 2)

        for (i in 0 until fftSize) {
            val inputReal = complexArray[i * 2]
            val inputImag = complexArray[i * 2 + 1]
            val chirpReal = chirpT[i * 2]
            val chirpImag = chirpT[i * 2 + 1]

            work[i * 2] = inputReal * chirpReal + inputImag * chirpImag
            work[i * 2 + 1] = inputImag * chirpReal - inputReal * chirpImag
        }

        bFft.transform(work)
        for (i in 0 until convSize) {
            val workReal = work[i * 2]
            val workImag = work[i * 2 + 1]
            val kernelReal = kernelF[i * 2]
            val kernelImag = kernelF[i * 2 + 1]

            work[i * 2] = workReal * kernelReal - workImag * kernelImag
            work[i * 2 + 1] = workReal * kernelImag + workImag * kernelReal
        }

        inverseTransform(work, bFft)
        for (i in 0 until fftSize) {
            val workReal = work[i * 2]
            val workImag = work[i * 2 + 1]
            val chirpReal = chirpT[i * 2]
            val chirpImag = chirpT[i * 2 + 1]

            complexArray[i * 2] = workReal * chirpReal + workImag * chirpImag
            complexArray[i * 2 + 1] = workImag * chirpReal - workReal * chirpImag
        }
    }

    companion object {
        fun nextPowerOfTwo(value: Int): Int {
            var power = 1
            while (power < value) {
                power = power shl 1
            }
            return power
        }

        fun inverseTransform(complexArray: FloatArray, fft: FFT) {
            for (i in 0 until fft.fftSize) {
                complexArray[i * 2 + 1] = -complexArray[i * 2 + 1]
            }

            fft.transform(complexArray)

            for (i in 0 until fft.fftSize) {
                complexArray[i * 2] /= fft.fftSize.toFloat()
                complexArray[i * 2 + 1] = -complexArray[i * 2 + 1] / fft.fftSize.toFloat()
            }
        }
    }
}
