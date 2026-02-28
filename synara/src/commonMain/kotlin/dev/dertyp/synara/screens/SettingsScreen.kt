package dev.dertyp.synara.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.synara.Config
import dev.dertyp.synara.IS_DEBUG
import dev.dertyp.synara.theme.PywalLoader
import dev.dertyp.synara.ui.components.ColorPicker
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

class SettingsScreen : Screen {

    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val language by Config.language.collectAsState()
        val lightThemeColor by Config.lightThemeColor.collectAsState()
        val darkThemeColor by Config.darkThemeColor.collectAsState()
        val useSongColor by Config.useSongColor.collectAsState()
        val usePywal by Config.usePywal.collectAsState()

        Scaffold(
            containerColor = Color.Transparent
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(Res.string.back)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.settings),
                        style = MaterialTheme.typography.headlineMedium
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Column(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    LanguageSetting(currentLanguage = language)

                    if (PywalLoader.isSupported()) {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { Config.setUsePywal(!usePywal) }
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(Res.string.use_pywal),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Switch(
                                    checked = usePywal,
                                    onCheckedChange = { Config.setUsePywal(it) }
                                )
                            }
                        }
                    }

                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { Config.setUseSongColor(!useSongColor) }
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(Res.string.use_song_color),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Switch(
                                checked = useSongColor,
                                onCheckedChange = { Config.setUseSongColor(it) }
                            )
                        }
                    }

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
    private fun LanguageSetting(currentLanguage: String?) {
        var expanded by remember { mutableStateOf(false) }
        val languages = listOf(
            null to stringResource(Res.string.system_default),
            "en" to stringResource(Res.string.lang_english),
            "de" to stringResource(Res.string.lang_german)
        )

        Box {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true }
                        .padding(16.dp),
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
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DropdownMenu(
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

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPicker = true }
                    .padding(16.dp),
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

        if (showPicker) {
            ColorPicker(
                title = title,
                initialColor = color,
                onColorSelected = onColorSelected,
                onDismissRequest = { showPicker = false }
            )
        }
    }
}
