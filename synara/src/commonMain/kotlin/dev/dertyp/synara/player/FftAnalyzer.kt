package dev.dertyp.synara.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

class FftAnalyzer(private val bufferSize: Int = 1024) {
    private val _fftData = MutableStateFlow(FloatArray(bufferSize / 2) { 0f })
    val fftData: StateFlow<FloatArray> = _fftData.asStateFlow()

    private val real = FloatArray(bufferSize)
    private val imag = FloatArray(bufferSize)
    private val window = FloatArray(bufferSize)

    init {
        // Hann window
        for (i in 0 until bufferSize) {
            window[i] = 0.5f * (1f - cos(2f * PI.toFloat() * i / (bufferSize - 1)))
        }
    }

    fun analyze(pcmData: ShortArray) {
        _fftData.value = getMagnitudes(pcmData)
    }

    fun getMagnitudes(pcmData: ShortArray): FloatArray {
        val n = min(pcmData.size, bufferSize)
        
        for (i in 0 until n) {
            real[i] = pcmData[i].toFloat() * window[i]
            imag[i] = 0f
        }
        for (i in n until bufferSize) {
            real[i] = 0f
            imag[i] = 0f
        }

        fft(real, imag)

        val magnitudes = FloatArray(bufferSize / 2)
        val maxPossibleMag = 32767f * (bufferSize / 2f)
        
        for (i in 0 until bufferSize / 2) {
            val mag = sqrt(real[i] * real[i] + imag[i] * imag[i])
            magnitudes[i] = (mag / maxPossibleMag).coerceIn(0f, 1f)
        }
        return magnitudes
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        if (n <= 1) return

        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tempReal = real[i]
                real[i] = real[j]
                real[j] = tempReal
                val tempImag = imag[i]
                imag[i] = imag[j]
                imag[j] = tempImag
            }
            var m = n shr 1
            @Suppress("ConvertTwoComparisonsToRangeCheck")
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var length = 2
        while (length <= n) {
            val angle = -2f * PI.toFloat() / length
            val wAlphaReal = cos(angle)
            val wAlphaImag = sin(angle)
            for (i in 0 until n step length) {
                var wReal = 1f
                var wImag = 0f
                for (k in 0 until length / 2) {
                    val uReal = real[i + k]
                    val uImag = imag[i + k]
                    val vReal = real[i + k + length / 2] * wReal - imag[i + k + length / 2] * wImag
                    val vImag = real[i + k + length / 2] * wImag + imag[i + k + length / 2] * wReal
                    real[i + k] = uReal + vReal
                    imag[i + k] = uImag + vImag
                    real[i + k + length / 2] = uReal - vReal
                    imag[i + k + length / 2] = uImag - vImag
                    val nextWReal = wReal * wAlphaReal - wImag * wAlphaImag
                    wImag = wReal * wAlphaImag + wImag * wAlphaReal
                    wReal = nextWReal
                }
            }
            length *= 2
        }
    }

    fun updateData(data: FloatArray) {
        _fftData.value = data
    }
}
