package dev.dertyp.synara.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.synara.Config
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.PlayerSwitcher
import dev.dertyp.synara.settings.*
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.ColorPicker
import dev.dertyp.synara.ui.components.SettingsCard
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.components.VisualizerAroundCover
import dev.dertyp.synara.ui.components.VisualizerSource
import dev.dertyp.synara.ui.components.VisualizerView
import dev.dertyp.synara.ui.components.radialExtentDp
import dev.dertyp.synara.ui.components.dialogs.SynaraAlertDialog
import dev.dertyp.synara.ui.components.rememberVisualizerColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*
import kotlin.math.*
import kotlin.random.Random

class VisualizerSettingsScreen : Screen {

    override val key: ScreenKey = uniqueScreenKey

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val presets by Config.visualizerPresets.collectAsState()
        val activeId by Config.activeVisualizerPresetId.collectAsState()
        val active by Config.activeVisualizerPreset.collectAsState()
        val draft by Config.visualizerDraft.collectAsState()
        val preset by Config.effectiveVisualizerPreset.collectAsState()

        var showSaveAsDialog by remember { mutableStateOf(false) }
        var showRenameDialog by remember { mutableStateOf(false) }
        var showDeleteDialog by remember { mutableStateOf(false) }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(Res.string.visualizer),
                            style = MaterialTheme.typography.headlineMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(SynaraIcons.Back.get(), contentDescription = stringResource(Res.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                VisualizerPreview(
                    preset = preset,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .widthIn(max = 580.dp)
                        .fillMaxWidth()
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = 580.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        PresetSection(
                            presets = presets,
                            activeId = activeId,
                            active = active,
                            hasDraft = draft != null,
                            onSaveAs = { showSaveAsDialog = true },
                            onRename = { showRenameDialog = true },
                            onDelete = { showDeleteDialog = true }
                        )

                        ShapeSection(preset)
                        ColorSection(preset)
                        MotionSection(preset)
                        EffectsSection(preset)
                    }
                }
            }
        }

        PresetNameDialog(
            isOpen = showSaveAsDialog,
            title = stringResource(Res.string.visualizer_save_as),
            initialName = preset.name,
            onConfirm = {
                Config.saveVisualizerDraftAs(it)
                showSaveAsDialog = false
            },
            onDismiss = { showSaveAsDialog = false }
        )

        PresetNameDialog(
            isOpen = showRenameDialog,
            title = stringResource(Res.string.visualizer_rename),
            initialName = active.name,
            onConfirm = {
                Config.renameVisualizerPreset(active.id, it)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )

        SynaraAlertDialog(
            isOpen = showDeleteDialog,
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(active.name) },
            text = { Text(stringResource(Res.string.visualizer_delete_confirm, active.name)) },
            confirmButton = {
                TextButton(onClick = {
                    Config.deleteVisualizerPreset(active.id)
                    showDeleteDialog = false
                }) {
                    Text(
                        stringResource(Res.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }

    @Composable
    private fun VisualizerPreview(preset: VisualizerPreset, modifier: Modifier = Modifier) {
        val playerSwitcher = koinInject<PlayerSwitcher>()
        val playerModel = koinInject<PlayerModel>()
        val isPlaying by playerSwitcher.isPlaying.collectAsState()
        val currentSong by playerModel.currentSong.collectAsState()
        val coverId = currentSong?.coverId
        val colors = rememberVisualizerColors(preset, coverId)
        val syntheticSource = rememberSyntheticSource(enabled = !isPlaying)
        val source = if (isPlaying) null else syntheticSource

        SettingsCard(modifier = modifier, innerPadding = PaddingValues(0.dp)) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PREVIEW_HEIGHT)
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                when (preset.shape) {
                    VisualizerShape.Strip -> {
                        VisualizerView(
                            preset = preset,
                            colors = colors,
                            source = source,
                            modifier = Modifier
                                .fillMaxWidth(preset.widthFraction)
                                .height(preset.height.dp)
                        )
                    }
                    VisualizerShape.Radial -> {
                        val coverSize = PREVIEW_COVER_SIZE
                        val reach = radialExtentDp(preset, coverSize.value)
                        val available = min(maxWidth.value, maxHeight.value)
                        val scale = (available / (reach * 2f)).coerceAtMost(1f)
                        Box(
                            modifier = Modifier.graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                        ) {
                            VisualizerAroundCover(
                                preset = preset,
                                colors = colors,
                                source = source,
                                modifier = Modifier.size(coverSize)
                            ) {
                                SynaraImage(
                                    imageId = coverId,
                                    modifier = Modifier.fillMaxSize(),
                                    shape = RoundedCornerShape(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun rememberSyntheticSource(enabled: Boolean): VisualizerSource {
        val fft = remember { MutableStateFlow(FloatArray(SYNTHETIC_BINS)) }
        val playing = remember { MutableStateFlow(true) }
        val sampleRate = remember { MutableStateFlow(SYNTHETIC_SAMPLE_RATE) }
        val source = remember { VisualizerSource(fftData = fft.asStateFlow(), isPlaying = playing.asStateFlow(), sampleRate = sampleRate.asStateFlow()) }

        LaunchedEffect(enabled) {
            if (!enabled) return@LaunchedEffect
            val random = Random(7)
            val jitter = FloatArray(SYNTHETIC_BINS)
            var startNanos = -1L
            while (true) {
                withFrameNanos { now ->
                    if (startNanos < 0) startNanos = now
                    val t = (now - startNanos) / 1_000_000_000f
                    fft.value = syntheticSpectrum(t, random, jitter)
                }
            }
        }
        return source
    }

    private fun syntheticSpectrum(t: Float, random: Random, jitter: FloatArray): FloatArray {
        val binHz = SYNTHETIC_SAMPLE_RATE / 2f / SYNTHETIC_BINS
        val beatPhase = (t * BEATS_PER_SECOND) % 1f
        val kick = exp(-beatPhase * 7f)
        val sweepCenter = log2(300f) + (sin(t * 0.35f) * 0.5f + 0.5f) * (log2(9000f) - log2(300f))
        val shimmer = 0.5f + 0.5f * sin(t * 2.3f)
        return FloatArray(SYNTHETIC_BINS) { i ->
            val hz = max(i * binHz, 1f)
            val octave = log2(hz)
            jitter[i] = jitter[i] * 0.7f + (random.nextFloat() * 2f - 1f) * 0.3f
            var db = -24f - 5.5f * log2(1f + hz / 150f)
            db += 16f * kick * exp(-((octave - log2(70f)) / 0.8f).pow(2))
            db += 10f * exp(-((octave - sweepCenter) / 0.6f).pow(2))
            db += 6f * shimmer * exp(-((octave - log2(3500f)) / 1.2f).pow(2))
            db += jitter[i] * 5f
            10f.pow(db / 20f).coerceIn(0f, 1f)
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun PresetSection(
        presets: List<VisualizerPreset>,
        activeId: String,
        active: VisualizerPreset,
        hasDraft: Boolean,
        onSaveAs: () -> Unit,
        onRename: () -> Unit,
        onDelete: () -> Unit
    ) {
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.visualizer_presets),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (hasDraft) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.tertiary)
                            )
                            Text(
                                text = stringResource(Res.string.visualizer_unsaved_changes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = preset.id == activeId,
                            onClick = { if (preset.id != activeId) Config.selectVisualizerPreset(preset.id) },
                            elevation = FilterChipDefaults.filterChipElevation(elevation = 0.dp, hoveredElevation = 0.dp, pressedElevation = 0.dp),
                            label = { Text(preset.name) }
                        )
                    }
                }

                if (active.builtIn && hasDraft) {
                    Text(
                        text = stringResource(Res.string.visualizer_builtin_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { Config.saveVisualizerDraft() },
                        enabled = hasDraft && !active.builtIn
                    ) {
                        Text(stringResource(Res.string.save))
                    }
                    FilledTonalButton(onClick = onSaveAs) {
                        Text(stringResource(Res.string.visualizer_save_as))
                    }
                    if (!active.builtIn) {
                        OutlinedButton(onClick = onRename) {
                            Icon(SynaraIcons.Edit.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.visualizer_rename))
                        }
                        OutlinedButton(onClick = onDelete) {
                            Icon(
                                SynaraIcons.Delete.get(),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        onClick = { Config.resetVisualizerDraft() },
                        enabled = hasDraft
                    ) {
                        Text(stringResource(Res.string.visualizer_reset_changes))
                    }
                }
            }
        }
    }

    @Composable
    private fun PresetNameDialog(
        isOpen: Boolean,
        title: String,
        initialName: String,
        onConfirm: (String) -> Unit,
        onDismiss: () -> Unit
    ) {
        if (!isOpen) return
        var name by remember(initialName) { mutableStateOf(initialName) }
        SynaraAlertDialog(
            isOpen = true,
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                InternalTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.visualizer_preset_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = { onConfirm(name.trim()) },
                    enabled = name.isNotBlank()
                ) {
                    Text(stringResource(Res.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }

    @Composable
    private fun SectionTitle(text: StringResource) {
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    @Composable
    private fun PresetSlider(
        title: StringResource,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        step: Float,
        format: (Float) -> String,
        onChange: (VisualizerPreset, Float) -> VisualizerPreset
    ) {
        SettingSlider(
            title = stringResource(title),
            value = value,
            valueText = format(value),
            onValueChange = { v -> Config.updateVisualizerDraft { onChange(it, v) } },
            valueRange = range,
            steps = stepsFor(range, step)
        )
    }

    @Composable
    private fun <T> PresetSegments(
        title: StringResource,
        options: List<T>,
        selected: T,
        label: (T) -> StringResource,
        onChange: (VisualizerPreset, T) -> VisualizerPreset
    ) {
        SettingSegmentedRow(
            title = stringResource(title),
            options = options,
            selected = selected,
            label = { stringResource(label(it)) },
            onSelected = { v -> Config.updateVisualizerDraft { onChange(it, v) } }
        )
    }

    @Composable
    private fun PresetSwitch(
        title: StringResource,
        checked: Boolean,
        summary: StringResource? = null,
        onChange: (VisualizerPreset, Boolean) -> VisualizerPreset
    ) {
        SettingSwitch(
            title = stringResource(title),
            summary = summary?.let { stringResource(it) },
            checked = checked,
            onCheckedChange = { v -> Config.updateVisualizerDraft { onChange(it, v) } }
        )
    }

    @Composable
    private fun ShapeSection(preset: VisualizerPreset) {
        val isStrip = preset.shape == VisualizerShape.Strip
        val isBars = preset.renderMode == VisualizerRenderMode.Bars

        SectionTitle(Res.string.visualizer_section_shape)

        PresetSegments(
            title = Res.string.visualizer_shape,
            options = VisualizerShape.entries,
            selected = preset.shape,
            label = { it.label() }
        ) { p, v -> p.copy(shape = v) }

        PresetSegments(
            title = Res.string.visualizer_render_mode,
            options = VisualizerRenderMode.entries,
            selected = preset.renderMode,
            label = { it.label() }
        ) { p, v -> p.copy(renderMode = v) }

        PresetSwitch(
            title = Res.string.visualizer_mirrored,
            summary = Res.string.visualizer_mirrored_summary,
            checked = preset.mirrored
        ) { p, v -> p.copy(mirrored = v) }

        if (isStrip) {
            PresetSegments(
                title = Res.string.visualizer_anchor,
                options = VisualizerAnchor.entries,
                selected = preset.anchor,
                label = { it.label() }
            ) { p, v -> p.copy(anchor = v) }
        }

        PresetSlider(Res.string.visualizer_bar_width, preset.barWidth, VisualizerLimits.barWidth, 1f, ::formatDp) { p, v -> p.copy(barWidth = v) }
        PresetSlider(Res.string.visualizer_bar_gap, preset.barGap, VisualizerLimits.barGap, 0.5f, ::formatDp) { p, v -> p.copy(barGap = v) }

        if (isBars) {
            PresetSlider(Res.string.visualizer_corner_radius, preset.cornerRadius, VisualizerLimits.cornerRadius, 0.5f, ::formatDp) { p, v -> p.copy(cornerRadius = v) }
            PresetSlider(Res.string.visualizer_min_bar_height, preset.minBarHeight, VisualizerLimits.minBarHeight, 0.5f, ::formatDp) { p, v -> p.copy(minBarHeight = v) }
        } else {
            PresetSwitch(
                title = Res.string.visualizer_wave_fill,
                checked = preset.waveFill
            ) { p, v -> p.copy(waveFill = v) }
            PresetSlider(Res.string.visualizer_wave_stroke, preset.waveStroke, VisualizerLimits.waveStroke, 0.5f, ::formatDp) { p, v -> p.copy(waveStroke = v) }
        }

        if (isStrip) {
            PresetSlider(Res.string.visualizer_height, preset.height, VisualizerLimits.height, 10f, ::formatDp) { p, v -> p.copy(height = v) }
            PresetSlider(Res.string.visualizer_width, preset.widthFraction, VisualizerLimits.widthFraction, 0.05f, ::formatPercent) { p, v -> p.copy(widthFraction = v) }
        } else {
            PresetSlider(Res.string.visualizer_radial_padding, preset.radialInnerPadding, VisualizerLimits.radialInnerPadding, 2f, ::formatDp) { p, v -> p.copy(radialInnerPadding = v) }
            PresetSlider(Res.string.visualizer_radial_roundness, preset.radialRoundness, VisualizerLimits.radialRoundness, 0.02f, ::formatPercent) { p, v -> p.copy(radialRoundness = v) }
            PresetSlider(Res.string.visualizer_radial_length, preset.radialMaxLength, VisualizerLimits.radialMaxLength, 5f, ::formatDp) { p, v -> p.copy(radialMaxLength = v) }
            PresetSlider(Res.string.visualizer_radial_rotation, preset.radialRotationSpeed, VisualizerLimits.radialRotationSpeed, 5f, ::formatDegreesPerSecond) { p, v -> p.copy(radialRotationSpeed = v) }
            PresetSlider(Res.string.visualizer_radial_angle, preset.radialAngle, VisualizerLimits.radialAngle, 5f, ::formatDegrees) { p, v -> p.copy(radialAngle = v) }
        }

        PresetSlider(Res.string.visualizer_opacity, preset.opacity, VisualizerLimits.opacity, 0.05f, ::formatPercent) { p, v -> p.copy(opacity = v) }
    }

    @Composable
    private fun ColorSection(preset: VisualizerPreset) {
        SectionTitle(Res.string.visualizer_section_colors)

        PresetSegments(
            title = Res.string.visualizer_color_mode,
            options = VisualizerColorMode.entries,
            selected = preset.colorMode,
            label = { it.label() }
        ) { p, v -> p.copy(colorMode = v) }

        PresetSegments(
            title = Res.string.visualizer_fill_mode,
            options = VisualizerFillMode.entries,
            selected = preset.fillMode,
            label = { it.label() }
        ) { p, v -> p.copy(fillMode = v) }

        if (preset.colorMode == VisualizerColorMode.Custom) {
            ColorSwatchSetting(
                title = stringResource(Res.string.visualizer_base_color),
                color = Color(preset.customBaseColor),
                onColorSelected = { c -> Config.updateVisualizerDraft { it.copy(customBaseColor = c.toArgb()) } }
            )
            ColorSwatchSetting(
                title = stringResource(Res.string.visualizer_highlight_color),
                color = Color(preset.customHighlightColor),
                onColorSelected = { c -> Config.updateVisualizerDraft { it.copy(customHighlightColor = c.toArgb()) } }
            )
        }
    }

    @Composable
    private fun ColorSwatchSetting(title: String, color: Color, onColorSelected: (Color) -> Unit) {
        var showPicker by remember { mutableStateOf(false) }

        SettingsCard(onClick = { showPicker = true }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }

        ColorPicker(
            isOpen = showPicker,
            title = title,
            initialColor = color,
            onColorSelected = onColorSelected,
            onDismissRequest = { showPicker = false }
        )
    }

    @Composable
    private fun MotionSection(preset: VisualizerPreset) {
        SectionTitle(Res.string.visualizer_section_motion)

        PresetSegments(
            title = Res.string.visualizer_reaction,
            options = VisualizerStyle.entries,
            selected = preset.reaction,
            label = { it.label }
        ) { p, v -> p.copy(reaction = v) }

        PresetSlider(Res.string.visualizer_low_hz, preset.lowHz, VisualizerLimits.lowHz, 5f, ::formatHz) { p, v -> p.copy(lowHz = v) }
        PresetSlider(Res.string.visualizer_high_hz, preset.highHz, VisualizerLimits.highHz, 500f, ::formatHz) { p, v -> p.copy(highHz = v) }

        PresetSegments(
            title = Res.string.visualizer_frequency_scale,
            options = FrequencyScale.entries,
            selected = preset.frequencyScale,
            label = { it.label() }
        ) { p, v -> p.copy(frequencyScale = v) }

        PresetSegments(
            title = Res.string.visualizer_sensitivity,
            options = VisualizerSensitivity.entries,
            selected = preset.sensitivity,
            label = { it.label() }
        ) { p, v -> p.copy(sensitivity = v) }

        if (preset.sensitivity == VisualizerSensitivity.Fixed) {
            PresetSlider(Res.string.visualizer_min_db, preset.minDb, VisualizerLimits.minDb, 1f, ::formatDb) { p, v ->
                p.copy(minDb = v.coerceAtMost(p.maxDb - MIN_DB_WINDOW))
            }
            PresetSlider(Res.string.visualizer_max_db, preset.maxDb, VisualizerLimits.maxDb, 1f, ::formatDb) { p, v ->
                p.copy(maxDb = v.coerceAtLeast(p.minDb + MIN_DB_WINDOW))
            }
        }

        PresetSlider(Res.string.visualizer_rise_speed, preset.riseSpeed, VisualizerLimits.riseSpeed, 0.05f, ::formatPercent) { p, v -> p.copy(riseSpeed = v) }
        PresetSlider(Res.string.visualizer_fall_speed, preset.fallSpeed, VisualizerLimits.fallSpeed, 0.05f, ::formatPercent) { p, v -> p.copy(fallSpeed = v) }

        if (preset.reaction == VisualizerStyle.Monstercat) {
            PresetSlider(Res.string.visualizer_falloff, preset.falloff, VisualizerLimits.falloff, 0.1f, ::formatFactor) { p, v -> p.copy(falloff = v) }
            PresetSlider(Res.string.visualizer_bass_falloff, preset.bassFalloff, VisualizerLimits.bassFalloff, 0.1f, ::formatFactor) { p, v -> p.copy(bassFalloff = v) }
            PresetSlider(Res.string.visualizer_treble_tilt, preset.trebleTilt, VisualizerLimits.trebleTilt, 0.05f, ::formatPercent) { p, v -> p.copy(trebleTilt = v) }
        }
    }

    @Composable
    private fun EffectsSection(preset: VisualizerPreset) {
        SectionTitle(Res.string.visualizer_section_effects)

        PresetSwitch(
            title = Res.string.visualizer_flame,
            checked = preset.flameEnabled
        ) { p, v -> p.copy(flameEnabled = v) }
        if (preset.flameEnabled) {
            PresetSlider(Res.string.visualizer_flame_intensity, preset.flameIntensity, VisualizerLimits.flameIntensity, 0.05f, ::formatPercent) { p, v -> p.copy(flameIntensity = v) }
            PresetSlider(Res.string.visualizer_flame_threshold, preset.flameThreshold, VisualizerLimits.flameThreshold, 0.01f, ::formatPercent) { p, v -> p.copy(flameThreshold = v) }
        }

        PresetSwitch(
            title = Res.string.visualizer_glow,
            checked = preset.glowEnabled
        ) { p, v -> p.copy(glowEnabled = v) }
        if (preset.glowEnabled) {
            PresetSlider(Res.string.visualizer_glow_radius, preset.glowRadius, VisualizerLimits.glowRadius, 0.5f, ::formatDp) { p, v -> p.copy(glowRadius = v) }
            PresetSlider(Res.string.visualizer_glow_strength, preset.glowStrength, VisualizerLimits.glowStrength, 0.05f, ::formatPercent) { p, v -> p.copy(glowStrength = v) }
        }
    }

    private fun VisualizerShape.label(): StringResource = when (this) {
        VisualizerShape.Strip -> Res.string.visualizer_shape_strip
        VisualizerShape.Radial -> Res.string.visualizer_shape_radial
    }

    private fun VisualizerRenderMode.label(): StringResource = when (this) {
        VisualizerRenderMode.Bars -> Res.string.visualizer_render_bars
        VisualizerRenderMode.Wave -> Res.string.visualizer_render_wave
    }

    private fun VisualizerAnchor.label(): StringResource = when (this) {
        VisualizerAnchor.Center -> Res.string.visualizer_anchor_center
        VisualizerAnchor.Bottom -> Res.string.visualizer_anchor_bottom
        VisualizerAnchor.Top -> Res.string.visualizer_anchor_top
    }

    private fun VisualizerColorMode.label(): StringResource = when (this) {
        VisualizerColorMode.Theme -> Res.string.visualizer_color_theme
        VisualizerColorMode.Cover -> Res.string.visualizer_color_cover
        VisualizerColorMode.Custom -> Res.string.visualizer_color_custom
    }

    private fun VisualizerFillMode.label(): StringResource = when (this) {
        VisualizerFillMode.HeightBlend -> Res.string.visualizer_fill_height
        VisualizerFillMode.GradientAcross -> Res.string.visualizer_fill_across
        VisualizerFillMode.Vertical -> Res.string.visualizer_fill_vertical
    }

    private fun FrequencyScale.label(): StringResource = when (this) {
        FrequencyScale.Log -> Res.string.visualizer_scale_log
        FrequencyScale.Linear -> Res.string.visualizer_scale_linear
    }

    private fun VisualizerSensitivity.label(): StringResource = when (this) {
        VisualizerSensitivity.Auto -> Res.string.visualizer_sensitivity_auto
        VisualizerSensitivity.Fixed -> Res.string.visualizer_sensitivity_fixed
    }

    private companion object {
        val PREVIEW_HEIGHT: Dp = 260.dp
        val PREVIEW_COVER_SIZE: Dp = 140.dp
        const val SYNTHETIC_BINS = 512
        const val SYNTHETIC_SAMPLE_RATE = 44100
        const val BEATS_PER_SECOND = 2f
        const val MIN_DB_WINDOW = 5f
    }
}

private fun stepsFor(range: ClosedFloatingPointRange<Float>, step: Float): Int =
    (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)

private fun formatDecimal(value: Float): String {
    val tenths = (abs(value) * 10).roundToInt()
    val sign = if (value < 0 && tenths != 0) "-" else ""
    return if (tenths % 10 == 0) "$sign${tenths / 10}" else "$sign${tenths / 10}.${tenths % 10}"
}

private fun formatDp(value: Float): String = "${formatDecimal(value)} dp"

private fun formatPercent(value: Float): String = "${(value * 100).roundToInt()} %"

private fun formatDb(value: Float): String = "${value.roundToInt()} dB"

private fun formatDegreesPerSecond(value: Float): String = "${value.roundToInt()} °/s"

private fun formatDegrees(value: Float): String = "${value.roundToInt()} °"

private fun formatFactor(value: Float): String = "${formatDecimal(value)}×"

private fun formatHz(value: Float): String =
    if (value >= 1000f) "${formatDecimal(value / 1000f)} kHz" else "${value.roundToInt()} Hz"
