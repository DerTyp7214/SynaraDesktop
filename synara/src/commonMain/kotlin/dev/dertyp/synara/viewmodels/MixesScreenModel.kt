package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.synara.Config
import dev.dertyp.synara.mixes.MixSpec
import dev.dertyp.synara.mixes.MixType
import dev.dertyp.synara.mixes.defaultSpec
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.launch

class MixesScreenModel(
    private val dispatchers: SynaraDispatchers
) : StateScreenModel<List<MixSpec>>(Config.mixOptions.value.toSpecs()) {

    init {
        screenModelScope.launch(dispatchers.default) {
            Config.mixOptions.collect { options -> mutableState.value = options.toSpecs() }
        }
    }
}

private fun Map<MixType, MixSpec>.toSpecs(): List<MixSpec> = MixType.entries.map { this[it] ?: it.defaultSpec }
