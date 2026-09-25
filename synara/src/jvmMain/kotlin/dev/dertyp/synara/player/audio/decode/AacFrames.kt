package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.UnsupportedAudioFormatException
import net.sourceforge.jaad.aac.AACDecoderConfig
import net.sourceforge.jaad.aac.SampleBuffer
import net.sourceforge.jaad.aac.filterbank.FilterBank
import net.sourceforge.jaad.aac.syntax.BitStream
import net.sourceforge.jaad.aac.syntax.ICStream
import net.sourceforge.jaad.aac.syntax.SyntacticElements
import java.lang.reflect.Field
import kotlin.math.sqrt

internal class AacFrameDecoder(audioSpecificConfig: ByteArray) {
    private val config = AudioSpecificConfig.coreConfig(audioSpecificConfig)
    private var sbrEnabled = true
    private var core = JaadCore(config, sbrEnabled)
    private val buffer = SampleBuffer().apply { isBigEndian = false }
    private var shorts = ShortArray(0)
    private var produced = false
    private var failures = 0

    suspend fun decode(frame: ByteArray, sink: PcmSink) {
        if (!core.decode(frame, buffer)) {
            if (!produced && sbrEnabled && ++failures >= SBR_FAILURES_BEFORE_FALLBACK) {
                sbrEnabled = false
                core = JaadCore(config, sbrEnabled)
            }
            return
        }
        val data = buffer.data ?: return
        if (buffer.bitsPerSample != 16) return
        val count = data.size / 2
        if (count == 0) return
        if (!sink.accepts(buffer.sampleRate, buffer.channels)) return
        produced = true
        if (shorts.size < count) shorts = ShortArray(count)
        if (buffer.isBigEndian) {
            for (i in 0 until count) {
                shorts[i] = ((data[i * 2].toInt() shl 8) or (data[i * 2 + 1].toInt() and 0xFF)).toShort()
            }
        } else {
            for (i in 0 until count) {
                shorts[i] = ((data[i * 2 + 1].toInt() shl 8) or (data[i * 2].toInt() and 0xFF)).toShort()
            }
        }
        sink.write(shorts, count)
    }

    companion object {
        private const val SBR_FAILURES_BEFORE_FALLBACK = 2
    }
}

private class JaadCore(audioSpecificConfig: ByteArray, sbrEnabled: Boolean) {
    private val config: AACDecoderConfig = AACDecoderConfig.parseMP4DecoderSpecificInfo(audioSpecificConfig)
        ?: throw UnsupportedAudioFormatException("aac")
    private val elements: SyntacticElements
    private val filterBank: FilterBank
    private val bits = BitStream()

    init {
        if (!config.profile.isDecodingSupported) {
            throw UnsupportedAudioFormatException("aac ${config.profile.description}")
        }
        config.isSBREnabled = sbrEnabled
        elements = SyntacticElements(config)
        filterBank = FilterBank(config.isSmallFrameUsed, config.channelConfiguration.channelCount)
    }

    fun decode(frame: ByteArray, buffer: SampleBuffer): Boolean = try {
        bits.setData(frame)
        elements.startNewFrame()
        elements.decode(bits)
        NoiseSubstitution.apply(elements)
        elements.process(filterBank)
        elements.sendToOutput(buffer)
        true
    } catch (_: Exception) {
        false
    }
}

internal object AudioSpecificConfig {
    private const val OBJECT_TYPE_SBR = 5
    private const val OBJECT_TYPE_PS = 29

    fun coreConfig(asc: ByteArray): ByteArray {
        if (asc.size < 2) return asc
        val reader = BitReader(asc)
        val objectType = reader.objectType()
        if (objectType != OBJECT_TYPE_SBR && objectType != OBJECT_TYPE_PS) return asc
        val sampleRateIndex = reader.read(4)
        if (sampleRateIndex == 15) return asc
        val channelConfig = reader.read(4)
        val extensionIndex = reader.read(4)
        if (extensionIndex == 15) reader.read(24)
        if (reader.remaining() < 5) return asc
        val coreType = reader.objectType()
        if (coreType <= 0 || coreType >= 31) return asc
        val value = (coreType shl 11) or (sampleRateIndex shl 7) or (channelConfig shl 3)
        return byteArrayOf((value shr 8).toByte(), value.toByte())
    }

    private class BitReader(private val data: ByteArray) {
        private var position = 0

        fun remaining(): Int = data.size * 8 - position

        fun read(count: Int): Int {
            var value = 0
            repeat(count) {
                val byte = data.getOrNull(position / 8)?.toInt() ?: 0
                val bit = (byte shr (7 - position % 8)) and 1
                value = (value shl 1) or bit
                position++
            }
            return value
        }

        fun objectType(): Int {
            val type = read(5)
            return if (type == 31) 32 + read(6) else type
        }
    }
}

private object NoiseSubstitution {
    private const val NOISE_CODEBOOK = 13

    private val elementsField: Field? = field(SyntacticElements::class.java, "elements")
    private val codebooksField: Field? = field(ICStream::class.java, "sfbCB")
    private val scaleFactorsField: Field? = field(ICStream::class.java, "scaleFactors")
    private val streamFields = HashMap<Class<*>, List<Field>>()
    private var randomState = 0x1F2E3D4C

    private fun field(owner: Class<*>, name: String): Field? = try {
        owner.getDeclaredField(name).apply { isAccessible = true }
    } catch (_: Exception) {
        null
    }

    private fun streamsOf(type: Class<*>): List<Field> = synchronized(streamFields) {
        streamFields.getOrPut(type) {
            type.declaredFields.filter { it.type == ICStream::class.java }.onEach { it.isAccessible = true }
        }
    }

    fun apply(elements: SyntacticElements) {
        val all = elementsField?.get(elements) as? Array<*> ?: return
        for (element in all) {
            if (element == null) continue
            for (field in streamsOf(element.javaClass)) {
                val stream = field.get(element) as? ICStream ?: continue
                repair(stream)
            }
        }
    }

    private fun repair(stream: ICStream) {
        val codebooks = codebooksField?.get(stream) as? IntArray ?: return
        val scaleFactors = scaleFactorsField?.get(stream) as? FloatArray ?: return
        val info = stream.info
        val data = stream.invQuantData
        val offsets = info.swbOffsets
        val maxSfb = info.maxSFB
        var groupOffset = 0
        var index = 0
        for (group in 0 until info.windowGroupCount) {
            val groupLength = info.getWindowGroupLength(group)
            for (sfb in 0 until maxSfb) {
                if (index < codebooks.size && codebooks[index] == NOISE_CODEBOOK) {
                    val width = offsets[sfb + 1] - offsets[sfb]
                    var offset = groupOffset + offsets[sfb]
                    repeat(groupLength) {
                        var energy = 0.0
                        for (k in 0 until width) {
                            randomState = randomState * 1664525 + 1013904223
                            val value = randomState.toFloat()
                            data[offset + k] = value
                            energy += value.toDouble() * value
                        }
                        val scale = if (energy > 0) (scaleFactors[index] / sqrt(energy)).toFloat() else 0f
                        for (k in 0 until width) data[offset + k] *= scale
                        offset += 128
                    }
                }
                index++
            }
            groupOffset += groupLength shl 7
        }
    }
}
