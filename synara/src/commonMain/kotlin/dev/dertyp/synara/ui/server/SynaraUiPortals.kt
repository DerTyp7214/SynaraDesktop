package dev.dertyp.synara.ui.server

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.viewmodels.GlobalStateModel
import dev.dertyp.ui.UiPortals
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.search_hint

object SynaraUiPortals {
    @Composable
    fun default(globalState: GlobalStateModel = koinInject()): UiPortalRegistry = remember(globalState) {
        UiPortalRegistry { name ->
            if (name != UiPortals.EXTERNAL_SEARCH) null
            else { _ ->
                val query by globalState.searchQuery.collectAsState()
                OutlinedTextField(
                    value = query,
                    onValueChange = { globalState.setSearchQuery(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(Res.string.search_hint)) },
                    leadingIcon = { Icon(SynaraIcons.Search.get(), contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                )
            }
        }
    }
}
