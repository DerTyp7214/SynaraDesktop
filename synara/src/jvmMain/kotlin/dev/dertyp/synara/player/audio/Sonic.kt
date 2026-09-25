/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2010 Bill Cox, Sonic Library
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for Synara: ported from media3 Sonic.java to Kotlin, reduced to speed changes
 * without pitch or rate adjustment, with a speed that can change between calls.
 */
package dev.dertyp.synara.player.audio

import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.min

class Sonic(private val sampleRateHz: Int, private val channelCount: Int) {
    var speed: Float = 1f

    private val minPeriod = sampleRateHz / MAXIMUM_PITCH
    private val maxPeriod = sampleRateHz / MINIMUM_PITCH
    private val maxRequiredFrameCount = 2 * maxPeriod
    private val downSampleBuffer = ShortArray(maxRequiredFrameCount)

    private var inputBuffer = ShortArray(maxRequiredFrameCount * channelCount)
    private var inputFrameCount = 0
    private var outputBuffer = ShortArray(maxRequiredFrameCount * channelCount)
    var outputFrameCount = 0
        private set
    private var remainingInputToCopyFrameCount = 0
    private var prevPeriod = 0
    private var prevMinDiff = 0
    private var minDiff = 0
    private var maxDiff = 0

    val pendingInputFrameCount: Int get() = inputFrameCount

    val isEmpty: Boolean
        get() = inputFrameCount == 0 && outputFrameCount == 0 && remainingInputToCopyFrameCount == 0

    fun queueInput(buffer: ShortBuffer) {
        val framesToWrite = buffer.remaining() / channelCount
        val samplesToWrite = framesToWrite * channelCount
        inputBuffer = ensureSpaceForAdditionalFrames(inputBuffer, inputFrameCount, framesToWrite)
        buffer.get(inputBuffer, inputFrameCount * channelCount, samplesToWrite)
        inputFrameCount += framesToWrite
        processStreamInput()
    }

    fun queueInput(samples: ShortArray, frameCount: Int) {
        inputBuffer = ensureSpaceForAdditionalFrames(inputBuffer, inputFrameCount, frameCount)
        System.arraycopy(samples, 0, inputBuffer, inputFrameCount * channelCount, frameCount * channelCount)
        inputFrameCount += frameCount
        processStreamInput()
    }

    fun getOutput(buffer: ShortBuffer) {
        val framesToRead = min(buffer.remaining() / channelCount, outputFrameCount)
        buffer.put(outputBuffer, 0, framesToRead * channelCount)
        outputFrameCount -= framesToRead
        System.arraycopy(
            outputBuffer,
            framesToRead * channelCount,
            outputBuffer,
            0,
            outputFrameCount * channelCount
        )
    }

    fun queueEndOfStream() {
        val remainingFrameCount = inputFrameCount
        val expectedOutputFrames = outputFrameCount + (remainingFrameCount / speed + 0.5f).toInt()

        inputBuffer = ensureSpaceForAdditionalFrames(
            inputBuffer,
            inputFrameCount,
            remainingFrameCount + 2 * maxRequiredFrameCount
        )
        for (xSample in 0 until 2 * maxRequiredFrameCount * channelCount) {
            inputBuffer[remainingFrameCount * channelCount + xSample] = 0
        }
        inputFrameCount += 2 * maxRequiredFrameCount
        processStreamInput()
        if (outputFrameCount > expectedOutputFrames) {
            outputFrameCount = expectedOutputFrames
        }
        inputFrameCount = 0
        remainingInputToCopyFrameCount = 0
    }

    fun flush() {
        inputFrameCount = 0
        outputFrameCount = 0
        remainingInputToCopyFrameCount = 0
        prevPeriod = 0
        prevMinDiff = 0
        minDiff = 0
        maxDiff = 0
    }

    private fun ensureSpaceForAdditionalFrames(buffer: ShortArray, frameCount: Int, additionalFrameCount: Int): ShortArray {
        val currentCapacityFrames = buffer.size / channelCount
        return if (frameCount + additionalFrameCount <= currentCapacityFrames) {
            buffer
        } else {
            val newCapacityFrames = 3 * currentCapacityFrames / 2 + additionalFrameCount
            buffer.copyOf(newCapacityFrames * channelCount)
        }
    }

    private fun removeProcessedInputFrames(positionFrames: Int) {
        val remainingFrames = inputFrameCount - positionFrames
        System.arraycopy(
            inputBuffer,
            positionFrames * channelCount,
            inputBuffer,
            0,
            remainingFrames * channelCount
        )
        inputFrameCount = remainingFrames
    }

    private fun copyToOutput(samples: ShortArray, positionFrames: Int, frameCount: Int) {
        outputBuffer = ensureSpaceForAdditionalFrames(outputBuffer, outputFrameCount, frameCount)
        System.arraycopy(
            samples,
            positionFrames * channelCount,
            outputBuffer,
            outputFrameCount * channelCount,
            frameCount * channelCount
        )
        outputFrameCount += frameCount
    }

    private fun copyInputToOutput(positionFrames: Int): Int {
        val frameCount = min(maxRequiredFrameCount, remainingInputToCopyFrameCount)
        copyToOutput(inputBuffer, positionFrames, frameCount)
        remainingInputToCopyFrameCount -= frameCount
        return frameCount
    }

    private fun downSampleInput(samples: ShortArray, positionFrames: Int, skip: Int) {
        val frameCount = maxRequiredFrameCount / skip
        val samplesPerValue = channelCount * skip
        val position = positionFrames * channelCount
        for (i in 0 until frameCount) {
            var value = 0
            for (j in 0 until samplesPerValue) {
                value += samples[position + i * samplesPerValue + j]
            }
            value /= samplesPerValue
            downSampleBuffer[i] = value.toShort()
        }
    }

    private fun findPitchPeriodInRange(samples: ShortArray, positionFrames: Int, minPeriod: Int, maxPeriod: Int): Int {
        var bestPeriod = 0
        var worstPeriod = 255
        var minDiff = 1
        var maxDiff = 0
        val position = positionFrames * channelCount
        for (period in minPeriod..maxPeriod) {
            var diff = 0
            for (i in 0 until period) {
                val sVal = samples[position + i]
                val pVal = samples[position + period + i]
                diff += abs(sVal - pVal)
            }
            if (diff * bestPeriod < minDiff * period) {
                minDiff = diff
                bestPeriod = period
            }
            if (diff * worstPeriod > maxDiff * period) {
                maxDiff = diff
                worstPeriod = period
            }
        }
        this.minDiff = minDiff / bestPeriod
        this.maxDiff = maxDiff / worstPeriod
        return bestPeriod
    }

    private fun previousPeriodBetter(minDiff: Int, maxDiff: Int): Boolean {
        if (minDiff == 0 || prevPeriod == 0) return false
        if (maxDiff > minDiff * 3) return false
        if (minDiff * 2 <= prevMinDiff * 3) return false
        return true
    }

    private fun findPitchPeriod(samples: ShortArray, position: Int): Int {
        var period: Int
        val skip = if (sampleRateHz > AMDF_FREQUENCY) sampleRateHz / AMDF_FREQUENCY else 1
        if (channelCount == 1 && skip == 1) {
            period = findPitchPeriodInRange(samples, position, minPeriod, maxPeriod)
        } else {
            downSampleInput(samples, position, skip)
            period = findPitchPeriodInRange(downSampleBuffer, 0, minPeriod / skip, maxPeriod / skip)
            if (skip != 1) {
                period *= skip
                var minP = period - skip * 4
                var maxP = period + skip * 4
                if (minP < minPeriod) minP = minPeriod
                if (maxP > maxPeriod) maxP = maxPeriod
                if (channelCount == 1) {
                    period = findPitchPeriodInRange(samples, position, minP, maxP)
                } else {
                    downSampleInput(samples, position, 1)
                    period = findPitchPeriodInRange(downSampleBuffer, 0, minP, maxP)
                }
            }
        }
        val retPeriod = if (previousPeriodBetter(minDiff, maxDiff)) prevPeriod else period
        prevMinDiff = minDiff
        prevPeriod = period
        return retPeriod
    }

    private fun skipPitchPeriod(samples: ShortArray, speed: Float, position: Int, period: Int): Int {
        val newFrameCount: Int
        if (speed >= 2.0f) {
            newFrameCount = (period / (speed - 1.0f)).toInt()
        } else {
            newFrameCount = period
            remainingInputToCopyFrameCount = (period * (2.0f - speed) / (speed - 1.0f)).toInt()
        }
        outputBuffer = ensureSpaceForAdditionalFrames(outputBuffer, outputFrameCount, newFrameCount)
        overlapAdd(
            newFrameCount,
            channelCount,
            outputBuffer,
            outputFrameCount,
            samples,
            position,
            samples,
            position + period
        )
        outputFrameCount += newFrameCount
        return newFrameCount
    }

    private fun insertPitchPeriod(samples: ShortArray, speed: Float, position: Int, period: Int): Int {
        val newFrameCount: Int
        if (speed < 0.5f) {
            newFrameCount = (period * speed / (1.0f - speed)).toInt()
        } else {
            newFrameCount = period
            remainingInputToCopyFrameCount = (period * (2.0f * speed - 1.0f) / (1.0f - speed)).toInt()
        }
        outputBuffer = ensureSpaceForAdditionalFrames(outputBuffer, outputFrameCount, period + newFrameCount)
        System.arraycopy(
            samples,
            position * channelCount,
            outputBuffer,
            outputFrameCount * channelCount,
            period * channelCount
        )
        overlapAdd(
            newFrameCount,
            channelCount,
            outputBuffer,
            outputFrameCount + period,
            samples,
            position + period,
            samples,
            position
        )
        outputFrameCount += period + newFrameCount
        return newFrameCount
    }

    private fun changeSpeed(speed: Float) {
        if (inputFrameCount < maxRequiredFrameCount) return
        val frameCount = inputFrameCount
        var positionFrames = 0
        do {
            if (remainingInputToCopyFrameCount > 0) {
                positionFrames += copyInputToOutput(positionFrames)
            } else {
                val period = findPitchPeriod(inputBuffer, positionFrames)
                positionFrames += if (speed > 1.0) {
                    period + skipPitchPeriod(inputBuffer, speed, positionFrames, period)
                } else {
                    insertPitchPeriod(inputBuffer, speed, positionFrames, period)
                }
            }
        } while (positionFrames + maxRequiredFrameCount <= frameCount)
        removeProcessedInputFrames(positionFrames)
    }

    private fun processStreamInput() {
        val s = speed
        if (s > 1.00001 || s < 0.99999) {
            changeSpeed(s)
        } else {
            copyToOutput(inputBuffer, 0, inputFrameCount)
            inputFrameCount = 0
            remainingInputToCopyFrameCount = 0
        }
    }

    companion object {
        private const val MINIMUM_PITCH = 65
        private const val MAXIMUM_PITCH = 400
        private const val AMDF_FREQUENCY = 4000

        private fun overlapAdd(
            frameCount: Int,
            channelCount: Int,
            out: ShortArray,
            outPosition: Int,
            rampDown: ShortArray,
            rampDownPosition: Int,
            rampUp: ShortArray,
            rampUpPosition: Int
        ) {
            for (i in 0 until channelCount) {
                var o = outPosition * channelCount + i
                var u = rampUpPosition * channelCount + i
                var d = rampDownPosition * channelCount + i
                for (t in 0 until frameCount) {
                    out[o] = ((rampDown[d] * (frameCount - t) + rampUp[u] * t) / frameCount).toShort()
                    o += channelCount
                    d += channelCount
                    u += channelCount
                }
            }
        }
    }
}
