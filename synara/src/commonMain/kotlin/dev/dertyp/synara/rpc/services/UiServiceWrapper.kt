package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.services.IUiService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.ui.IntakeItem
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiContributionInfo
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiHomeLayout
import dev.dertyp.ui.UiHookEvent
import dev.dertyp.ui.UiHookHandler
import dev.dertyp.ui.UiHookHandlerInfo
import dev.dertyp.ui.UiHookKind
import dev.dertyp.ui.UiIntakeResult
import dev.dertyp.ui.UiInvokePayload
import dev.dertyp.ui.UiInvokeResult
import dev.dertyp.ui.UiLiveUpdate
import dev.dertyp.ui.UiRender
import dev.dertyp.ui.UiSlotRender
import kotlinx.coroutines.flow.Flow

class UiServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IUiService {
    override suspend fun listContributions(kind: UiContributionKind?, slot: String?): List<UiContributionInfo> {
        return manager.getService<IUiService>().listContributions(kind, slot)
    }

    override suspend fun renderSlot(slot: String, context: UiContext): UiSlotRender {
        return manager.getService<IUiService>().renderSlot(slot, context)
    }

    override suspend fun render(contributionId: String, context: UiContext): UiRender {
        return manager.getService<IUiService>().render(contributionId, context)
    }

    override fun subscribe(contributionId: String, entityId: PlatformUUID?): Flow<UiRender> {
        return manager.getService<IUiService>().subscribe(contributionId, entityId)
    }

    override fun subscribeLive(contributionId: String, key: String, entityId: PlatformUUID?): Flow<UiLiveUpdate> {
        return manager.getService<IUiService>().subscribeLive(contributionId, key, entityId)
    }

    override suspend fun invoke(
        contributionId: String,
        actionId: String,
        payload: UiInvokePayload,
    ): UiInvokeResult {
        return manager.getService<IUiService>().invoke(contributionId, actionId, payload)
    }

    override suspend fun dispatchHook(event: UiHookEvent): List<UiHookHandler> {
        return manager.getService<IUiService>().dispatchHook(event)
    }

    override suspend fun listHookHandlers(kind: UiHookKind?): List<UiHookHandlerInfo> {
        return manager.getService<IUiService>().listHookHandlers(kind)
    }

    override suspend fun intake(items: List<IntakeItem>, resolverId: String?): UiIntakeResult {
        return manager.getService<IUiService>().intake(items, resolverId)
    }

    override suspend fun resolveIntake(items: List<IntakeItem>): List<UiHookHandler> {
        return manager.getService<IUiService>().resolveIntake(items)
    }

    override suspend fun getHomeCards(): UiHomeLayout {
        return manager.getService<IUiService>().getHomeCards()
    }

    override suspend fun setHomeCardPinned(contributionId: String, pinned: Boolean): UiHomeLayout {
        return manager.getService<IUiService>().setHomeCardPinned(contributionId, pinned)
    }

    override suspend fun setHomeCardOrder(contributionIds: List<String>): UiHomeLayout {
        return manager.getService<IUiService>().setHomeCardOrder(contributionIds)
    }

    override fun getHomeCardsFlow(): Flow<UiHomeLayout> {
        return manager.getService<IUiService>().getHomeCardsFlow()
    }
}
