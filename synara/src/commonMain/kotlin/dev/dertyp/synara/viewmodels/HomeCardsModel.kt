package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.ChangeTopic
import dev.dertyp.services.IUiService
import dev.dertyp.synara.rpc.ChangeHub
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.ui.UiContributionInfo
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiHomeCard
import dev.dertyp.ui.UiHomeLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeCardsModel(
    private val uiService: IUiService,
    private val rpcServiceManager: RpcServiceManager,
    private val changeHub: ChangeHub,
    private val dispatchers: SynaraDispatchers,
) : StateScreenModel<HomeCardsModel.HomeCardsState>(HomeCardsState()) {

    data class HomeCardsState(
        val cards: List<UiHomeCard> = emptyList(),
        val infos: Map<String, UiContributionInfo> = emptyMap(),
    ) {
        val pinned: List<UiHomeCard> get() = cards.filter { it.pinned }.sortedBy { it.position }
        val unpinned: List<UiHomeCard> get() = cards.filter { !it.pinned }
    }

    init {
        screenModelScope.launch(dispatchers.io) {
            rpcServiceManager.awaitAuthentication()
            coroutineScope {
                launch {
                    try {
                        val infos = uiService.listContributions(UiContributionKind.HOME_CARD).associateBy { it.id }
                        mutableState.update { it.copy(infos = infos) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
                launch {
                    changeHub.refreshOn(ChangeTopic.HOME_CARDS) { apply(uiService.getHomeCards()) }
                }
            }
        }
    }

    private fun apply(layout: UiHomeLayout) {
        mutableState.update { it.copy(cards = layout.cards) }
    }

    fun setPinned(contributionId: String, pinned: Boolean) {
        screenModelScope.launch(dispatchers.io) {
            try {
                apply(uiService.setHomeCardPinned(contributionId, pinned))
            } catch (_: Throwable) {
            }
        }
    }

    fun setOrder(contributionIds: List<String>) {
        screenModelScope.launch(dispatchers.io) {
            try {
                apply(uiService.setHomeCardOrder(contributionIds))
            } catch (_: Throwable) {
            }
        }
    }

    fun moveUp(contributionId: String) = reorder(contributionId, -1)
    fun moveDown(contributionId: String) = reorder(contributionId, 1)

    private fun reorder(contributionId: String, delta: Int) {
        val pinnedIds = state.value.pinned.map { it.contributionId }.toMutableList()
        val index = pinnedIds.indexOf(contributionId)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, pinnedIds.lastIndex)
        if (target == index) return
        pinnedIds.add(target, pinnedIds.removeAt(index))
        setOrder(pinnedIds)
    }
}
