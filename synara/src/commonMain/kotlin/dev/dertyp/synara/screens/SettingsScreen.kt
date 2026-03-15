package dev.dertyp.synara.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.synara.Config
import dev.dertyp.synara.IS_DEBUG
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.scrobble.LastFmScrobbler
import dev.dertyp.synara.theme.PywalLoader
import dev.dertyp.synara.ui.IconPackType
import dev.dertyp.synara.ui.LocalIconPack
import dev.dertyp.synara.ui.SynaraIconStyle
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.ColorPicker
import dev.dertyp.synara.ui.components.SettingsCard
import dev.dertyp.synara.ui.components.SynaraMenu
import dev.dertyp.synara.ui.components.dialogs.SynaraAlertDialog
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*
import kotlin.math.roundToInt

class SettingsScreen : Screen {

    override val key: ScreenKey = uniqueScreenKey

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val language by Config.language.collectAsState()
        val lightThemeColor by Config.lightThemeColor.collectAsState()
        val darkThemeColor by Config.darkThemeColor.collectAsState()
        val iconPackType by Config.iconPack.collectAsState()
        val iconStyleId by Config.iconStyle.collectAsState()
        val iconFilled by Config.iconFilled.collectAsState()
        val useSongColor by Config.useSongColor.collectAsState()
        val usePywal by Config.usePywal.collectAsState()
        val particleMultiplier by Config.particleMultiplier.collectAsState()
        val hideOnClose by Config.hideOnClose.collectAsState()

        val isProxyEnabled by Config.isProxyEnabled.collectAsState()
        val proxyHost by Config.proxyHost.collectAsState()
        val proxyPort by Config.proxyPort.collectAsState()
        val proxyId by Config.proxyId.collectAsState()
        val proxySsl by Config.proxySsl.collectAsState()

        val isListenBrainzEnabled by Config.isListenBrainzEnabled.collectAsState()
        val listenBrainzToken by Config.listenBrainzToken.collectAsState()
        val isLastFmEnabled by Config.isLastFmEnabled.collectAsState()
        val lastFmApiKey by Config.lastFmApiKey.collectAsState()
        val lastFmSharedSecret by Config.lastFmSharedSecret.collectAsState()
        val lastFmSessionKey by Config.lastFmSessionKey.collectAsState()
        val lastFmUsername by Config.lastFmUsername.collectAsState()
        val isDiscordRpcEnabled by Config.isDiscordRpcEnabled.collectAsState()

        val rpcServiceManager = koinInject<RpcServiceManager>()

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(Res.string.settings),
                            style = MaterialTheme.typography.headlineMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = SynaraIcons.Back.get(),
                                contentDescription = stringResource(Res.string.back)
                            )
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
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 580.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    LanguageSetting(currentLanguage = language)

                    if (PywalLoader.isSupported()) {
                        SettingSwitch(
                            title = stringResource(Res.string.use_pywal),
                            checked = usePywal,
                            onCheckedChange = { Config.setUsePywal(it) }
                        )
                    }

                    SettingSwitch(
                        title = stringResource(Res.string.use_song_color),
                        checked = useSongColor,
                        onCheckedChange = { Config.setUseSongColor(it) }
                    )

                    if (!useSongColor && !usePywal) {
                        ThemeColorSetting(
                            title = stringResource(Res.string.light_theme_color),
                            color = lightThemeColor,
                            onColorSelected = { Config.setLightThemeColor(it) }
                        )
                        ThemeColorSetting(
                            title = stringResource(Res.string.dark_theme_color),
                            color = darkThemeColor,
                            onColorSelected = { Config.setDarkThemeColor(it) }
                        )
                    }

                    IconSettings(
                        currentPackType = iconPackType,
                        currentStyleId = iconStyleId,
                        iconFilled = iconFilled
                    )

                    ParticleMultiplierSetting(
                        multiplier = particleMultiplier,
                        onMultiplierChange = { Config.setParticleMultiplier(it) }
                    )

                    Text(
                        text = stringResource(Res.string.window),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    SettingSwitch(
                        title = stringResource(Res.string.hide_on_close),
                        checked = hideOnClose,
                        onCheckedChange = { Config.setHideOnClose(it) }
                    )

                    Text(
                        text = stringResource(Res.string.proxy),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    SettingsCard(innerPadding = PaddingValues(0.dp)) {
                        SettingSwitch(
                            title = stringResource(Res.string.enable_proxy),
                            checked = isProxyEnabled,
                            onCheckedChange = { Config.setIsProxyEnabled(it) },
                            useElevatedCard = false
                        )
                        if (isProxyEnabled && proxyHost.isNotBlank()) {
                            Column(
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    bottom = 16.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${if (proxySsl) "wss" else "ws"}://$proxyHost:$proxyPort${if (proxyId.isNotBlank()) "/$proxyId" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Text(
                        text = stringResource(Res.string.scrobbling),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    SettingSwitch(
                        title = stringResource(Res.string.enable_discord_rpc),
                        checked = isDiscordRpcEnabled,
                        onCheckedChange = { Config.setIsDiscordRpcEnabled(it) }
                    )

                    SettingsCard(innerPadding = PaddingValues(0.dp)) {
                        SettingSwitch(
                            title = stringResource(Res.string.enable_listenbrainz),
                            checked = isListenBrainzEnabled,
                            onCheckedChange = { Config.setIsListenBrainzEnabled(it) },
                            useElevatedCard = false
                        )
                        if (isListenBrainzEnabled) {
                            Column(
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    bottom = 16.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                InternalTextField(
                                    value = listenBrainzToken,
                                    onValueChange = { Config.setListenBrainzToken(it) },
                                    label = { Text(stringResource(Res.string.listenbrainz_token)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    LastFmSettings(
                        isEnabled = isLastFmEnabled,
                        apiKey = lastFmApiKey,
                        sharedSecret = lastFmSharedSecret,
                        sessionKey = lastFmSessionKey,
                        username = lastFmUsername
                    )

                    Text(
                        text = stringResource(Res.string.account),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    SettingsCard(onClick = { rpcServiceManager.logout() }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(Res.string.logout),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    if (IS_DEBUG) {
                        Button(
                            onClick = { navigator.push(DebugScreen()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open Debug Color Scheme")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun IconSettings(
        currentPackType: IconPackType,
        currentStyleId: String,
        iconFilled: Boolean
    ) {
        val currentPack = currentPackType.getPack()
        
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                IconPackPicker(
                    currentPackType = currentPackType,
                    onPackSelected = { Config.setIconPack(it) }
                )

                if (currentPack.styles.size > 1) {
                    IconStylePicker(
                        currentStyleId = currentStyleId,
                        styles = currentPack.styles,
                        onStyleSelected = { Config.setIconStyle(it) }
                    )
                }

                if (currentPack.hasFilledOption) {
                    SettingSwitch(
                        title = stringResource(Res.string.icon_filled),
                        checked = iconFilled,
                        onCheckedChange = { Config.setIconFilled(it) },
                        useElevatedCard = false
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun IconPackPicker(currentPackType: IconPackType, onPackSelected: (IconPackType) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.icon_pack),
                style = MaterialTheme.typography.titleMedium
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                IconPackType.entries.forEachIndexed { index, packType ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = IconPackType.entries.size),
                        onClick = { onPackSelected(packType) },
                        selected = packType == currentPackType,
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CompositionLocalProvider(LocalIconPack provides packType.getPack()) {
                                    Icon(
                                        imageVector = SynaraIcons.Library.get(),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Text(stringResource(packType.label))
                            }
                        }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun IconStylePicker(
        currentStyleId: String,
        styles: List<SynaraIconStyle>,
        onStyleSelected: (String) -> Unit
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.icon_style),
                style = MaterialTheme.typography.titleMedium
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                styles.forEachIndexed { index, style ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = styles.size),
                        onClick = { onStyleSelected(style.id) },
                        selected = style.id == currentStyleId,
                        label = { Text(stringResource(style.label)) }
                    )
                }
            }
        }
    }

    @Composable
    private fun LastFmSettings(
        isEnabled: Boolean,
        apiKey: String,
        sharedSecret: String,
        sessionKey: String,
        username: String
    ) {
        var showAuthDialog by remember { mutableStateOf(false) }

        SettingsCard(innerPadding = PaddingValues(0.dp)) {
            SettingSwitch(
                title = stringResource(Res.string.enable_lastfm),
                checked = isEnabled,
                onCheckedChange = { Config.setIsLastFmEnabled(it) },
                useElevatedCard = false
            )
            if (isEnabled) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    InternalTextField(
                        value = apiKey,
                        onValueChange = { Config.setLastFmApiKey(it) },
                        label = { Text(stringResource(Res.string.lastfm_api_key)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    InternalTextField(
                        value = sharedSecret,
                        onValueChange = { Config.setLastFmSharedSecret(it) },
                        label = { Text(stringResource(Res.string.lastfm_shared_secret)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (sessionKey.isBlank()) {
                        Button(
                            onClick = { showAuthDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = apiKey.isNotBlank() && sharedSecret.isNotBlank()
                        ) {
                            Text(stringResource(Res.string.lastfm_login))
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(
                                    Res.string.lastfm_authenticated,
                                    username
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            TextButton(
                                onClick = {
                                    Config.setLastFmSessionKey("")
                                    Config.setLastFmUsername("")
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(stringResource(Res.string.logout))
                            }
                        }
                    }
                }
            }
        }

        LastFmAuthDialog(
            isOpen = showAuthDialog,
            onDismiss = { showAuthDialog = false }
        )
    }

    @Composable
    private fun LastFmAuthDialog(
        isOpen: Boolean,
        onDismiss: () -> Unit,
        lastFmScrobbler: LastFmScrobbler = koinInject()
    ) {
        var usernameInput by remember { mutableStateOf("") }
        var passwordInput by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        SynaraAlertDialog(
            isOpen = isOpen,
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.lastfm_auth_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InternalTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        label = { Text(stringResource(Res.string.lastfm_username)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    InternalTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text(stringResource(Res.string.lastfm_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    if (error != null) {
                        Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                val loginFailedText = stringResource(Res.string.lastfm_login_failed)
                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            error = null
                            val session =
                                lastFmScrobbler.getMobileSession(usernameInput, passwordInput)
                            if (session != null) {
                                Config.setLastFmSessionKey(session.key)
                                Config.setLastFmUsername(session.name)
                                onDismiss()
                            } else {
                                error = loginFailedText
                            }
                            isLoading = false
                        }
                    },
                    enabled = !isLoading && usernameInput.isNotBlank() && passwordInput.isNotBlank()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(Res.string.login))
                    }
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
    private fun SettingSwitch(
        title: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        useElevatedCard: Boolean = true
    ) {
        val rowContent = @Composable { modifier: Modifier ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(modifier),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = checked,
                    onCheckedChange = { onCheckedChange(it) }
                )
            }
        }

        if (useElevatedCard) {
            SettingsCard(onClick = { onCheckedChange(!checked) }) {
                rowContent(Modifier)
            }
        } else {
            rowContent(Modifier.clickable { onCheckedChange(!checked) }.padding(16.dp))
        }
    }

    @Composable
    private fun LanguageSetting(currentLanguage: String?) {
        var expanded by remember { mutableStateOf(false) }
        val languages = listOf(
            null to stringResource(Res.string.system_default),
            "en" to stringResource(Res.string.lang_english),
            "de" to stringResource(Res.string.lang_german)
        )

        Box {
            SettingsCard(
                onClick = { expanded = true }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(Res.string.language),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = languages.find { it.first == currentLanguage }?.second
                                ?: stringResource(Res.string.system_default),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        imageVector = SynaraIcons.ChevronDown.get(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SynaraMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 200.dp)
            ) {
                languages.forEach { (code, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            Config.setLanguage(code)
                            expanded = false
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun ThemeColorSetting(title: String, color: Color, onColorSelected: (Color) -> Unit) {
        var showPicker by remember { mutableStateOf(false) }

        SettingsCard(
            onClick = { showPicker = true }
        ) {
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
    private fun ParticleMultiplierSetting(multiplier: Float, onMultiplierChange: (Float) -> Unit) {
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.particle_multiplier),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = ((multiplier * 10).roundToInt() / 10f).toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = multiplier,
                    onValueChange = { onMultiplierChange(it) },
                    valueRange = 0f..5f,
                    steps = 49
                )
            }
        }
    }
}
