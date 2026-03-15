package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dertyp.data.Artist
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraImage
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.artists
import synara.synara.generated.resources.cancel

@Composable
fun ArtistListDialog(
    isOpen: Boolean,
    artists: List<Artist>,
    onArtistClick: (Artist) -> Unit,
    onDismissRequest: () -> Unit
) {
    SynaraAlertDialog(
        isOpen = isOpen,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(Res.string.artists),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Box(modifier = Modifier.heightIn(max = 400.dp)) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(artists.sortedBy { it.name }) { artist ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    onArtistClick(artist)
                                    onDismissRequest()
                                },
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SynaraImage(
                                    imageId = artist.imageId,
                                    size = 48.dp,
                                    fallbackIcon = SynaraIcons.Person
                                )
                                
                                Spacer(modifier = Modifier.width(16.dp))
                                
                                Text(
                                    text = artist.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}
