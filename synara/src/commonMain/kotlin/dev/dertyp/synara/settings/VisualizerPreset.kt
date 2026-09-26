package dev.dertyp.synara.settings

import dev.dertyp.serializers.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

enum class VisualizerShape { Strip, Radial }

enum class VisualizerRenderMode { Bars, Wave }

enum class VisualizerAnchor { Center, Bottom, Top }

enum class VisualizerColorMode { Theme, Cover, Custom }

enum class VisualizerFillMode { HeightBlend, GradientAcross, Vertical }

enum class FrequencyScale { Log, Linear }

enum class VisualizerSensitivity { Auto, Fixed }

@Serializable
data class VisualizerPreset(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val reaction: VisualizerStyle = VisualizerStyle.Synara,
    val shape: VisualizerShape = VisualizerShape.Strip,
    val renderMode: VisualizerRenderMode = VisualizerRenderMode.Bars,
    val mirrored: Boolean = true,
    val stereo: Boolean = true,
    val anchor: VisualizerAnchor = VisualizerAnchor.Center,
    val barWidth: Float = 5f,
    val barGap: Float = 1f,
    val cornerRadius: Float = 2f,
    val minBarHeight: Float = 3f,
    val height: Float = 120f,
    val widthFraction: Float = 1f,
    val opacity: Float = 1f,
    val waveFill: Boolean = true,
    val waveStroke: Float = 2f,
    val radialRoundness: Float = 1f,
    val radialInnerPadding: Float = 12f,
    val radialMaxLength: Float = 60f,
    val radialRotationSpeed: Float = 0f,
    val radialAngle: Float = 0f,
    val colorMode: VisualizerColorMode = VisualizerColorMode.Theme,
    val fillMode: VisualizerFillMode = VisualizerFillMode.HeightBlend,
    val customBaseColor: Int = 0xFF3D2B6B.toInt(),
    val customHighlightColor: Int = 0xFF00E5FF.toInt(),
    val lowHz: Float = 40f,
    val highHz: Float = 16000f,
    val frequencyScale: FrequencyScale = FrequencyScale.Log,
    val sensitivity: VisualizerSensitivity = VisualizerSensitivity.Auto,
    val minDb: Float = -60f,
    val maxDb: Float = -20f,
    val riseSpeed: Float = 0.5f,
    val fallSpeed: Float = 0.5f,
    val falloff: Float = 2f,
    val bassFalloff: Float = 1.5f,
    val trebleTilt: Float = 0.5f,
    val flameEnabled: Boolean = true,
    val flameIntensity: Float = 1f,
    val flameThreshold: Float = 0.98f,
    val glowEnabled: Boolean = true,
    val glowRadius: Float = 9f,
    val glowStrength: Float = 0.7f,
)

object VisualizerLimits {
    const val MAX_GLOW_RADIUS = 16f

    val barWidth = 2f..16f
    val barGap = 0f..6f
    val cornerRadius = 0f..8f
    val minBarHeight = 0f..6f
    val height = 60f..240f
    val widthFraction = 0.5f..1f
    val opacity = 0.2f..1f
    val waveStroke = 0f..6f
    val radialRoundness = 0f..1f
    val radialInnerPadding = 0f..48f
    val radialMaxLength = 20f..140f
    val radialRotationSpeed = -90f..90f
    val radialAngle = -180f..180f
    val lowHz = 20f..500f
    val highHz = 2000f..20000f
    val minDb = -90f..-30f
    val maxDb = -40f..0f
    val riseSpeed = 0f..1f
    val fallSpeed = 0f..1f
    val falloff = 1f..4f
    val bassFalloff = 1f..3f
    val trebleTilt = 0f..1.5f
    val flameIntensity = 0f..2f
    val flameThreshold = 0.8f..1f
    val glowRadius = 0f..MAX_GLOW_RADIUS
    val glowStrength = 0f..1f
}

object VisualizerPresets {
    const val SYNARA_ID = "builtin.synara"
    const val MONSTERCAT_ID = "builtin.monstercat"
    const val MINIMAL_ID = "builtin.minimal"
    const val NEON_ID = "builtin.neon"
    const val FLOW_ID = "builtin.flow"
    const val HALO_ID = "builtin.halo"

    val builtIns: List<VisualizerPreset> = listOf(
        VisualizerPreset(
            id = SYNARA_ID,
            name = "Synara",
            builtIn = true,
            reaction = VisualizerStyle.Synara,
            frequencyScale = FrequencyScale.Linear,
            sensitivity = VisualizerSensitivity.Fixed,
            lowHz = 20f,
            highHz = 11025f,
            minDb = -60f,
            maxDb = -20f,
        ),
        VisualizerPreset(
            id = MONSTERCAT_ID,
            name = "Monstercat",
            builtIn = true,
            reaction = VisualizerStyle.Monstercat,
            frequencyScale = FrequencyScale.Log,
            sensitivity = VisualizerSensitivity.Auto,
            lowHz = 40f,
            highHz = 16000f,
        ),
        VisualizerPreset(
            id = MINIMAL_ID,
            name = "Minimal",
            builtIn = true,
            reaction = VisualizerStyle.Monstercat,
            anchor = VisualizerAnchor.Bottom,
            mirrored = false,
            barWidth = 3f,
            barGap = 2f,
            cornerRadius = 1f,
            flameEnabled = false,
            glowEnabled = false,
        ),
        VisualizerPreset(
            id = NEON_ID,
            name = "Neon",
            builtIn = true,
            reaction = VisualizerStyle.Monstercat,
            colorMode = VisualizerColorMode.Custom,
            customBaseColor = 0xFFFF2BD6.toInt(),
            customHighlightColor = 0xFF00E5FF.toInt(),
            fillMode = VisualizerFillMode.GradientAcross,
            glowRadius = 14f,
            glowStrength = 1f,
            flameEnabled = false,
        ),
        VisualizerPreset(
            id = FLOW_ID,
            name = "Flow",
            builtIn = true,
            reaction = VisualizerStyle.Monstercat,
            renderMode = VisualizerRenderMode.Wave,
            fillMode = VisualizerFillMode.HeightBlend,
            glowRadius = 10f,
            glowStrength = 0.5f,
            flameEnabled = false,
        ),
        VisualizerPreset(
            id = HALO_ID,
            name = "Halo",
            builtIn = true,
            reaction = VisualizerStyle.Monstercat,
            shape = VisualizerShape.Radial,
            colorMode = VisualizerColorMode.Cover,
            barWidth = 3f,
            barGap = 2f,
            radialRoundness = 1f,
            radialInnerPadding = 12f,
            radialMaxLength = 60f,
            flameEnabled = false,
        ),
    )

    fun forLegacyStyle(style: String?): String =
        if (style == VisualizerStyle.Monstercat.name) MONSTERCAT_ID else SYNARA_ID

    fun resolveActiveId(storedActiveId: String?, legacyStyle: String?, userPresets: List<VisualizerPreset>): String {
        if (storedActiveId != null && (builtIns.any { it.id == storedActiveId } || userPresets.any { it.id == storedActiveId })) {
            return storedActiveId
        }
        return forLegacyStyle(legacyStyle)
    }

    fun encode(userPresets: List<VisualizerPreset>): String =
        AppJson.encodeToString(userPresets.filterNot { it.builtIn })

    fun decode(json: String?): List<VisualizerPreset> {
        if (json.isNullOrBlank()) return emptyList()
        val elements = try {
            AppJson.decodeFromString<JsonElement>(json) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return elements
            .mapNotNull { element ->
                try {
                    AppJson.decodeFromJsonElement<VisualizerPreset>(element)
                } catch (_: Exception) {
                    null
                }
            }
            .filterNot { preset -> preset.builtIn || builtIns.any { it.id == preset.id } }
            .distinctBy { it.id }
    }
}
