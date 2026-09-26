package dev.dertyp.synara.player.audio

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object MonoMix {
    private val ATTENUATION = 1.0 / sqrt(2.0)
    private val MIN_SAMPLE = Short.MIN_VALUE.toDouble()
    private val MAX_SAMPLE = Short.MAX_VALUE.toDouble()

    private enum class Role {
        FRONT_LEFT,
        FRONT_RIGHT,
        CENTRE,
        LOW_FREQUENCY,
        SURROUND_LEFT,
        SURROUND_RIGHT
    }

    private val LAYOUTS: Map<Int, List<Role>> = mapOf(
        1 to listOf(Role.CENTRE),
        2 to listOf(Role.FRONT_LEFT, Role.FRONT_RIGHT),
        3 to listOf(Role.FRONT_LEFT, Role.FRONT_RIGHT, Role.CENTRE),
        4 to listOf(Role.FRONT_LEFT, Role.FRONT_RIGHT, Role.SURROUND_LEFT, Role.SURROUND_RIGHT),
        5 to listOf(
            Role.FRONT_LEFT, Role.FRONT_RIGHT, Role.CENTRE,
            Role.SURROUND_LEFT, Role.SURROUND_RIGHT
        ),
        6 to listOf(
            Role.FRONT_LEFT, Role.FRONT_RIGHT, Role.CENTRE, Role.LOW_FREQUENCY,
            Role.SURROUND_LEFT, Role.SURROUND_RIGHT
        ),
        7 to listOf(
            Role.FRONT_LEFT, Role.FRONT_RIGHT, Role.CENTRE, Role.LOW_FREQUENCY,
            Role.CENTRE, Role.SURROUND_LEFT, Role.SURROUND_RIGHT
        ),
        8 to listOf(
            Role.FRONT_LEFT, Role.FRONT_RIGHT, Role.CENTRE, Role.LOW_FREQUENCY,
            Role.SURROUND_LEFT, Role.SURROUND_RIGHT, Role.SURROUND_LEFT, Role.SURROUND_RIGHT
        )
    )

    private fun roles(channels: Int): List<Role> = LAYOUTS[channels] ?: List(channels) { index ->
        when (index) {
            0 -> Role.FRONT_LEFT
            1 -> Role.FRONT_RIGHT
            2 -> Role.CENTRE
            3 -> Role.LOW_FREQUENCY
            else -> if (index % 2 == 0) Role.SURROUND_LEFT else Role.SURROUND_RIGHT
        }
    }

    private class SideVectors(val left: DoubleArray, val right: DoubleArray, val norm: Double)

    private val sideVectorCache = ConcurrentHashMap<Int, SideVectors>()

    private fun sideVectors(channels: Int): SideVectors = sideVectorCache.getOrPut(channels) {
        val left = DoubleArray(channels)
        val right = DoubleArray(channels)
        roles(channels).forEachIndexed { index, role ->
            when (role) {
                Role.FRONT_LEFT -> left[index] = 1.0
                Role.FRONT_RIGHT -> right[index] = 1.0
                Role.CENTRE -> {
                    left[index] = ATTENUATION
                    right[index] = ATTENUATION
                }

                Role.LOW_FREQUENCY -> Unit
                Role.SURROUND_LEFT -> left[index] = ATTENUATION
                Role.SURROUND_RIGHT -> right[index] = ATTENUATION
            }
        }
        SideVectors(left, right, max(left.sum(), right.sum()))
    }

    fun coefficients(channels: Int): DoubleArray {
        if (channels <= 0) return DoubleArray(0)

        val vectors = sideVectors(channels)
        val norm = vectors.norm
        val gain = loudnessCompensation(channels)
        return DoubleArray(channels) {
            if (norm > 0.0) gain * (vectors.left[it] + vectors.right[it]) / (2.0 * norm) else 0.0
        }
    }

    fun sideCoefficients(channels: Int): Pair<FloatArray, FloatArray> {
        if (channels <= 0) return FloatArray(0) to FloatArray(0)

        val vectors = sideVectors(channels)
        val norm = vectors.norm
        val gain = loudnessCompensation(channels)
        fun side(values: DoubleArray) = FloatArray(channels) {
            if (norm > 0.0) (gain * values[it] / norm).toFloat() else 0f
        }
        return side(vectors.left) to side(vectors.right)
    }

    fun loudnessCompensation(channels: Int): Double = when {
        channels <= 2 -> 1.0
        channels <= 6 -> SURROUND_GAIN
        else -> WIDE_SURROUND_GAIN
    }

    private const val SURROUND_GAIN = 1.68
    private const val WIDE_SURROUND_GAIN = 2.0

    fun toPcm16(value: Double): Short = value.coerceIn(MIN_SAMPLE, MAX_SAMPLE).roundToInt().toShort()
}
