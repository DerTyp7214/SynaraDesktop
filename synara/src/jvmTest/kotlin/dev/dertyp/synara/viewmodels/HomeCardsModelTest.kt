package dev.dertyp.synara.viewmodels

import dev.dertyp.data.ChangeTopic
import dev.dertyp.services.IUiService
import dev.dertyp.synara.rpc.ChangeHub
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.UnconfinedDispatchers
import dev.dertyp.ui.UiCardSize
import dev.dertyp.ui.UiContributionInfo
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiHomeCard
import dev.dertyp.ui.UiHomeLayout
import io.mockk.*
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCardsModelTest {

    private fun card(id: String, pinned: Boolean, position: Int) =
        UiHomeCard(contributionId = id, pinned = pinned, position = position, size = UiCardSize.MEDIUM)

    private val uiService = mockk<IUiService> {
        coEvery { listContributions(any(), any()) } returns emptyList()
    }
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }
    private val changeHub = mockk<ChangeHub> {
        coEvery { refreshOn(any(), any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
    }

    private fun createModel(cards: List<UiHomeCard>): HomeCardsModel {
        coEvery { uiService.getHomeCards() } returns UiHomeLayout(cards)
        coEvery { uiService.setHomeCardOrder(any()) } returns UiHomeLayout(cards)
        return HomeCardsModel(uiService, rpcServiceManager, changeHub, UnconfinedDispatchers)
    }

    @Test
    fun theHomeCardsLoadOnCreationAndPinnedOnesAreSortedByPosition() {
        val third = card("third", pinned = true, position = 2)
        val first = card("first", pinned = true, position = 0)
        val loose = card("loose", pinned = false, position = 1)

        val model = createModel(listOf(third, loose, first))

        assertEquals(listOf(first, third), model.state.value.pinned)
        assertEquals(listOf(loose), model.state.value.unpinned)
        coVerify(exactly = 1) { changeHub.refreshOn(ChangeTopic.HOME_CARDS, any()) }
    }

    @Test
    fun theContributionInfosAreIndexedById() {
        val info = UiContributionInfo(id = "weather", source = "server", kind = UiContributionKind.HOME_CARD, title = "Weather")
        coEvery { uiService.listContributions(UiContributionKind.HOME_CARD, any()) } returns listOf(info)

        val model = createModel(emptyList())

        assertEquals(mapOf("weather" to info), model.state.value.infos)
    }

    @Test
    fun movingTheFirstCardUpMakesNoCall() {
        val model = createModel(listOf(card("a", true, 0), card("b", true, 1), card("c", true, 2)))

        model.moveUp("a")

        coVerify(exactly = 0) { uiService.setHomeCardOrder(any()) }
    }

    @Test
    fun movingTheLastCardDownMakesNoCall() {
        val model = createModel(listOf(card("a", true, 0), card("b", true, 1), card("c", true, 2)))

        model.moveDown("c")

        coVerify(exactly = 0) { uiService.setHomeCardOrder(any()) }
    }

    @Test
    fun movingACardDownSwapsItWithItsSuccessor() {
        val model = createModel(listOf(card("a", true, 0), card("b", true, 1), card("c", true, 2)))

        model.moveDown("a")

        coVerify(exactly = 1) { uiService.setHomeCardOrder(listOf("b", "a", "c")) }
    }

    @Test
    fun movingACardUpSwapsItWithItsPredecessor() {
        val model = createModel(listOf(card("a", true, 0), card("b", true, 1), card("c", true, 2)))

        model.moveUp("c")

        coVerify(exactly = 1) { uiService.setHomeCardOrder(listOf("a", "c", "b")) }
    }

    @Test
    fun movingAnUnpinnedOrUnknownCardMakesNoCall() {
        val model = createModel(listOf(card("a", true, 0), card("b", true, 1), card("loose", false, 2)))

        model.moveUp("loose")
        model.moveDown("loose")
        model.moveUp("missing")
        model.moveDown("missing")

        coVerify(exactly = 0) { uiService.setHomeCardOrder(any()) }
    }

    @Test
    fun pinningACardAppliesTheReturnedLayout() {
        val model = createModel(listOf(card("a", false, 0)))
        val pinned = listOf(card("a", true, 0))
        coEvery { uiService.setHomeCardPinned("a", true) } returns UiHomeLayout(pinned)

        model.setPinned("a", true)

        assertEquals(pinned, model.state.value.cards)
    }

    @Test
    fun aFailedPinLeavesTheStateUnchanged() {
        val cards = listOf(card("a", false, 0), card("b", true, 1))
        val model = createModel(cards)
        coEvery { uiService.setHomeCardPinned(any(), any()) } throws IOException("down")

        model.setPinned("a", true)

        assertEquals(cards, model.state.value.cards)
    }
}
