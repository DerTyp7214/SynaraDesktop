package dev.dertyp.synara.ui.components.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.dialogs.SynaraDialog
import dev.dertyp.synara.ui.server.UiContributionHost
import dev.dertyp.synara.ui.server.UiIconView
import dev.dertyp.synara.ui.server.isServerUiAvailable
import dev.dertyp.synara.viewmodels.HomeCardsModel
import dev.dertyp.ui.UiCardSize
import dev.dertyp.ui.UiHomeCard
import dev.dertyp.ui.UiIcon
import dev.dertyp.ui.UiIconName
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.home_cards_edit
import synara.synara.generated.resources.home_cards_available
import synara.synara.generated.resources.home_cards_pinned
import synara.synara.generated.resources.home_cards_title
import synara.synara.generated.resources.move_down
import synara.synara.generated.resources.move_up

private fun rows(pinned: List<UiHomeCard>): List<List<UiHomeCard>> {
    val rows = mutableListOf<List<UiHomeCard>>()
    var pending = mutableListOf<UiHomeCard>()
    pinned.forEach { card ->
        if (card.size == UiCardSize.WIDE) {
            if (pending.isNotEmpty()) {
                rows += pending
                pending = mutableListOf()
            }
            rows += listOf(card)
        } else {
            pending += card
            if (pending.size == 2) {
                rows += pending
                pending = mutableListOf()
            }
        }
    }
    if (pending.isNotEmpty()) rows += pending
    return rows
}

@Composable
fun UiHomeCardsSection(model: HomeCardsModel, modifier: Modifier = Modifier) {
    if (!isServerUiAvailable()) return
    val state by model.state.collectAsState()
    val pinned = state.pinned
    if (pinned.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(Res.string.home_cards_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        rows(pinned).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { card ->
                    UiContributionHost(card.contributionId, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun UiHomeCardsEditButton(model: HomeCardsModel) {
    if (!isServerUiAvailable()) return
    val state by model.state.collectAsState()
    if (state.cards.isEmpty()) return

    var showPicker by remember { mutableStateOf(false) }
    IconButton(onClick = { showPicker = true }) {
        Icon(SynaraIcons.Edit.get(), contentDescription = stringResource(Res.string.home_cards_edit))
    }

    SynaraDialog(isOpen = showPicker, onDismissRequest = { showPicker = false }) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.widthIn(max = 480.dp).padding(vertical = 8.dp)) {
                Text(
                    text = stringResource(Res.string.home_cards_edit),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    val pinned = state.pinned
                    if (pinned.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(Res.string.home_cards_pinned),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                        items(pinned, key = { "pinned.${it.contributionId}" }) { card ->
                            val index = pinned.indexOf(card)
                            HomeCardRow(
                                model = model,
                                card = card,
                                pinned = true,
                                canMoveUp = index > 0,
                                canMoveDown = index < pinned.lastIndex,
                            )
                        }
                    }
                    val unpinned = state.unpinned
                    if (unpinned.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(Res.string.home_cards_available),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                        items(unpinned, key = { "unpinned.${it.contributionId}" }) { card ->
                            HomeCardRow(
                                model = model,
                                card = card,
                                pinned = false,
                                canMoveUp = false,
                                canMoveDown = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeCardRow(
    model: HomeCardsModel,
    card: UiHomeCard,
    pinned: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
) {
    val info = model.state.collectAsState().value.infos[card.contributionId]
    ListItem(
        headlineContent = { Text(info?.title ?: card.contributionId) },
        supportingContent = info?.description?.let { { Text(it, maxLines = 2) } },
        leadingContent = {
            UiIconView(
                info?.icon ?: UiIcon.Named(UiIconName.STATS),
                size = 24.dp,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Row {
                if (pinned) {
                    IconButton(onClick = { model.moveUp(card.contributionId) }, enabled = canMoveUp) {
                        Icon(SynaraIcons.ExpandUp.get(), contentDescription = stringResource(Res.string.move_up))
                    }
                    IconButton(onClick = { model.moveDown(card.contributionId) }, enabled = canMoveDown) {
                        Icon(SynaraIcons.ExpandDown.get(), contentDescription = stringResource(Res.string.move_down))
                    }
                }
                Switch(
                    checked = pinned,
                    onCheckedChange = { model.setPinned(card.contributionId, it) },
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
