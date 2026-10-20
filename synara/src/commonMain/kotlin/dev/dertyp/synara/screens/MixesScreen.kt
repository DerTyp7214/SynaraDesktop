package dev.dertyp.synara.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.synara.mixes.type
import dev.dertyp.synara.ui.components.MixCard
import dev.dertyp.synara.ui.components.optionsLabel
import dev.dertyp.synara.ui.components.title
import dev.dertyp.synara.viewmodels.MixesScreenModel
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

class MixesScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<MixesScreenModel>()
        val navigator = LocalNavigator.currentOrThrow
        val mixes by screenModel.state.collectAsState()

        Scaffold(containerColor = Color.Transparent) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = stringResource(Res.string.mixes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                items(mixes, key = { it.type.name }) { spec ->
                    MixCard(
                        spec = spec,
                        title = spec.title(),
                        subtitle = "${spec.optionsLabel()} · ${spec.size} ${stringResource(Res.string.songs)}",
                        onClick = { navigator.push(MixScreen(spec)) },
                        modifier = Modifier.fillMaxWidth().testTag("mix_type_${spec.type.name}")
                    )
                }
            }
        }
    }
}
