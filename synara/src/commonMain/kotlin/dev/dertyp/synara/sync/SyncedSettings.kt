package dev.dertyp.synara.sync

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.dertyp.serializers.AppJson
import dev.dertyp.synara.Config
import dev.dertyp.synara.settings.VisualizerPreset
import dev.dertyp.synara.settings.VisualizerPresets
import dev.dertyp.synara.ui.IconPackType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

enum class SyncGroup { GENERAL, SECRETS }

/**
 * One setting that takes part in settings sync. A value that still is the factory default is never
 * uploaded, so a device that never touched a setting cannot overwrite it on the other devices.
 */
class SyncedSetting<T> internal constructor(
    val key: String,
    val group: SyncGroup,
    val flow: StateFlow<T>,
    private val default: T,
    private val encoder: (T) -> String?,
    private val decoder: (String) -> T?,
    private val setter: suspend (T) -> Unit
) {
    fun isDefault(): Boolean = flow.value == default

    fun encodeLocal(): String? = encoder(flow.value)

    fun decode(json: String?): T? {
        if (json == null) return default
        return try {
            decoder(json)
        } catch (_: Throwable) {
            null
        }
    }

    fun matchesLocal(json: String?): Boolean = decode(json)?.let { it == flow.value } ?: true

    suspend fun applyRemote(json: String?): Boolean {
        if (json == null) {
            if (default == flow.value) return false
            setter(default)
            return true
        }
        val decoded = decode(json) ?: return false
        if (decoded == flow.value) return false
        setter(decoded)
        return true
    }
}

/**
 * The set of synced settings. Keys without a prefix are shared with every client, `desktop.` keys
 * only ever move between desktop installations. Anything bound to one installation (server/auth,
 * proxy, audio device, volume, pywal, Discord, window, performance overlay, downloads and the sync
 * bookkeeping itself) is deliberately absent.
 */
class SyncedSettingsRegistry(private val cipher: SecretsCipher) {

    private fun boolean(
        key: String,
        flow: StateFlow<Boolean>,
        default: Boolean,
        group: SyncGroup = SyncGroup.GENERAL,
        setter: (Boolean) -> Unit
    ): SyncedSetting<Boolean> = SyncedSetting(
        key = key,
        group = group,
        flow = flow,
        default = default,
        encoder = { AppJson.encodeToString(it) },
        decoder = { AppJson.decodeFromString<Boolean>(it) },
        setter = { setter(it) }
    )

    private fun int(
        key: String,
        flow: StateFlow<Int>,
        default: Int,
        setter: (Int) -> Unit
    ): SyncedSetting<Int> = SyncedSetting(
        key = key,
        group = SyncGroup.GENERAL,
        flow = flow,
        default = default,
        encoder = { AppJson.encodeToString(it) },
        decoder = { AppJson.decodeFromString<Int>(it) },
        setter = { setter(it) }
    )

    private fun float(
        key: String,
        flow: StateFlow<Float>,
        default: Float,
        setter: (Float) -> Unit
    ): SyncedSetting<Float> = SyncedSetting(
        key = key,
        group = SyncGroup.GENERAL,
        flow = flow,
        default = default,
        encoder = { AppJson.encodeToString(it) },
        decoder = { AppJson.decodeFromString<Float>(it) },
        setter = { setter(it) }
    )

    private fun string(
        key: String,
        flow: StateFlow<String>,
        default: String,
        group: SyncGroup = SyncGroup.GENERAL,
        setter: (String) -> Unit
    ): SyncedSetting<String> = SyncedSetting(
        key = key,
        group = group,
        flow = flow,
        default = default,
        encoder = { AppJson.encodeToString(it) },
        decoder = { AppJson.decodeFromString<String>(it) },
        setter = { setter(it) }
    )

    /** A string whose absence is the default, so clearing it deletes the key instead of writing "". */
    private fun nullableString(
        key: String,
        flow: StateFlow<String?>,
        setter: (String?) -> Unit
    ): SyncedSetting<String?> = SyncedSetting(
        key = key,
        group = SyncGroup.GENERAL,
        flow = flow,
        default = null,
        encoder = { value -> value?.let { AppJson.encodeToString(it) } },
        decoder = { AppJson.decodeFromString<String>(it) },
        setter = { setter(it) }
    )

    private inline fun <reified E : Enum<E>> enum(
        key: String,
        flow: StateFlow<E>,
        default: E,
        crossinline setter: (E) -> Unit
    ): SyncedSetting<E> = SyncedSetting(
        key = key,
        group = SyncGroup.GENERAL,
        flow = flow,
        default = default,
        encoder = { AppJson.encodeToString(it.name) },
        decoder = { json -> enumValueOf<E>(AppJson.decodeFromString<String>(json)) },
        setter = { setter(it) }
    )

    private fun color(
        key: String,
        flow: StateFlow<Color>,
        default: Color,
        setter: (Color) -> Unit
    ): SyncedSetting<Color> = SyncedSetting(
        key = key,
        group = SyncGroup.GENERAL,
        flow = flow,
        default = default,
        encoder = { AppJson.encodeToString(it.toArgb()) },
        decoder = { Color(AppJson.decodeFromString<Int>(it)) },
        setter = { setter(it) }
    )

    private fun visualizerPresets(
        key: String,
        flow: StateFlow<List<VisualizerPreset>>,
        setter: (List<VisualizerPreset>) -> Unit
    ): SyncedSetting<List<VisualizerPreset>> = SyncedSetting(
        key = key,
        group = SyncGroup.GENERAL,
        flow = flow,
        default = emptyList(),
        encoder = { VisualizerPresets.encode(it) },
        decoder = { json -> AppJson.decodeFromString<List<VisualizerPreset>>(json).filterNot { it.builtIn } },
        setter = { setter(it) }
    )

    /**
     * A credential. The encoder returns null while the secrets are locked, so nothing is ever
     * uploaded in the clear; an empty local value counts as "not set".
     */
    private fun secretString(
        key: String,
        flow: StateFlow<String>,
        setter: (String) -> Unit
    ): SyncedSetting<String> = SyncedSetting(
        key = key,
        group = SyncGroup.SECRETS,
        flow = flow,
        default = "",
        encoder = { value -> value.takeIf { it.isNotEmpty() }?.let { cipher.encryptToJson(key, it) } },
        decoder = { json -> cipher.decryptFromJson(key, json) },
        setter = { setter(it) }
    )

    /** The theme travels as a DARK/LIGHT string; any other value has no desktop equivalent. */
    private fun themeMode(): SyncedSetting<Boolean> = SyncedSetting(
        key = "theme_mode",
        group = SyncGroup.GENERAL,
        flow = Config.darkTheme,
        default = true,
        encoder = { AppJson.encodeToString(if (it) "DARK" else "LIGHT") },
        decoder = { json ->
            when (AppJson.decodeFromString<String>(json)) {
                "DARK" -> true
                "LIGHT" -> false
                else -> null
            }
        },
        setter = { Config.setDarkTheme(it) }
    )

    val all: List<SyncedSetting<*>> = listOf(
        // Shared keys
        themeMode(),
        boolean("show_remaining_time", Config.showRemainingTime, false) { Config.setShowRemainingTime(it) },
        boolean("show_title_tags_in_text", Config.showTitleTagsInText, true) { Config.setShowTitleTagsInText(it) },
        float("particle_multiplier", Config.particleMultiplier, 2.5f) { Config.setParticleMultiplier(it) },
        boolean("is_server_scrobbling_enabled", Config.isServerScrobblingEnabled, true) {
            Config.setIsServerScrobblingEnabled(it)
        },
        boolean("is_podcasts_enabled", Config.isPodcastsEnabled, false) { Config.setPodcastsEnabled(it) },

        // Desktop only
        color("desktop.light_theme_color", Config.lightThemeColor, Color(Color.Green.toArgb())) {
            Config.setLightThemeColor(it)
        },
        color("desktop.dark_theme_color", Config.darkThemeColor, Color(Color.Red.toArgb())) {
            Config.setDarkThemeColor(it)
        },
        boolean("desktop.use_song_color", Config.useSongColor, false) { Config.setUseSongColor(it) },
        string("desktop.icon_style", Config.iconStyle, "rounded") { Config.setIconStyle(it) },
        boolean("desktop.icon_filled", Config.iconFilled, false) { Config.setIconFilled(it) },
        enum("desktop.icon_pack", Config.iconPack, IconPackType.MaterialSymbols) { Config.setIconPack(it) },
        visualizerPresets("desktop.visualizer_presets", Config.userVisualizerPresets) {
            Config.setUserVisualizerPresets(it)
        },
        string("desktop.visualizer_active_preset", Config.activeVisualizerPresetId, VisualizerPresets.SYNARA_ID) {
            Config.setActiveVisualizerPresetId(it)
        },
        int("desktop.streaming_quality", Config.streamingQuality, 0) { Config.setStreamingQuality(it) },
        nullableString("desktop.language", Config.language) { Config.setLanguage(it) },

        // Secrets group
        boolean("is_listenbrainz_enabled", Config.isListenBrainzEnabled, false, SyncGroup.SECRETS) {
            Config.setIsListenBrainzEnabled(it)
        },
        boolean("is_lastfm_enabled", Config.isLastFmEnabled, false, SyncGroup.SECRETS) {
            Config.setIsLastFmEnabled(it)
        },
        string("desktop.lastfm_username", Config.lastFmUsername, "", SyncGroup.SECRETS) {
            Config.setLastFmUsername(it)
        },
        secretString("secret.listenbrainz_token", Config.listenBrainzToken) { Config.setListenBrainzToken(it) },
        secretString("secret.lastfm_session_key", Config.lastFmSessionKey) { Config.setLastFmSessionKey(it) },
        secretString("secret.lastfm_api_key", Config.lastFmApiKey) { Config.setLastFmApiKey(it) },
        secretString("secret.lastfm_shared_secret", Config.lastFmSharedSecret) { Config.setLastFmSharedSecret(it) }
    )

    val general: List<SyncedSetting<*>> = all.filter { it.group == SyncGroup.GENERAL }
    val secrets: List<SyncedSetting<*>> = all.filter { it.group == SyncGroup.SECRETS }
    val byKey: Map<String, SyncedSetting<*>> = all.associateBy { it.key }

    fun changes(): Flow<Unit> = all.map { setting -> setting.flow.drop(1).map { } }.merge()
}
