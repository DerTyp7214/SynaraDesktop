package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dertyp.PlatformUUID
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraMenu
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.cancel
import synara.synara.generated.resources.discovery_criterion
import synara.synara.generated.resources.discovery_criterion_bpm
import synara.synara.generated.resources.discovery_criterion_composers
import synara.synara.generated.resources.discovery_criterion_default
import synara.synara.generated.resources.discovery_criterion_energy
import synara.synara.generated.resources.discovery_criterion_lyricists
import synara.synara.generated.resources.discovery_criterion_mood
import synara.synara.generated.resources.discovery_criterion_producers
import synara.synara.generated.resources.discovery_dialog_title
import synara.synara.generated.resources.discovery_limit
import synara.synara.generated.resources.find_songs

enum class DiscoveryCriterion {
    Default, Bpm, Energy, Mood, Composers, Lyricists, Producers;

    @Composable
    fun getLabel(): String {
        return when (this) {
            Default -> stringResource(Res.string.discovery_criterion_default)
            Bpm -> stringResource(Res.string.discovery_criterion_bpm)
            Energy -> stringResource(Res.string.discovery_criterion_energy)
            Mood -> stringResource(Res.string.discovery_criterion_mood)
            Composers -> stringResource(Res.string.discovery_criterion_composers)
            Lyricists -> stringResource(Res.string.discovery_criterion_lyricists)
            Producers -> stringResource(Res.string.discovery_criterion_producers)
        }
    }
}

sealed class SimilarSongsSeed {
    data class Songs(val songIds: List<PlatformUUID>, val label: String) : SimilarSongsSeed()
    data class Playlist(val playlistId: PlatformUUID, val label: String) : SimilarSongsSeed()
    data class Album(val albumId: PlatformUUID, val label: String) : SimilarSongsSeed()
}

@Composable
fun SimilarSongsDialog(
    isOpen: Boolean,
    seed: SimilarSongsSeed,
    onConfirm: (DiscoveryCriterion, Int) -> Unit,
    onDismissRequest: () -> Unit
) {
    var selectedCriterion by remember { mutableStateOf(DiscoveryCriterion.Default) }
    var limit by remember { mutableStateOf(20f) }
    var expanded by remember { mutableStateOf(false) }

    SynaraAlertDialog(
        isOpen = isOpen,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(Res.string.discovery_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = when (seed) {
                        is SimilarSongsSeed.Songs -> seed.label
                        is SimilarSongsSeed.Playlist -> seed.label
                        is SimilarSongsSeed.Album -> seed.label
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(Res.string.discovery_criterion),
                        style = MaterialTheme.typography.labelLarge
                    )
                    
                    Box {
                        Surface(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            color = Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = selectedCriterion.getLabel(),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Icon(
                                    imageVector = SynaraIcons.ChevronDown.get(),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        SynaraMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DiscoveryCriterion.entries.forEach { criterion ->
                                DropdownMenuItem(
                                    text = { Text(criterion.getLabel()) },
                                    onClick = {
                                        selectedCriterion = criterion
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(Res.string.discovery_limit),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = limit.toInt().toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = limit,
                        onValueChange = { limit = it },
                        valueRange = 1f..100f,
                        steps = 99
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(selectedCriterion, limit.toInt())
                    onDismissRequest()
                }
            ) {
                Text(stringResource(Res.string.find_songs))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}
