package dev.dertyp.synara.viewmodels

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PlaylistAccess
import dev.dertyp.data.PlaylistShare
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserProfile
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.services.IUserService
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.testing.UnconfinedDispatchers
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistShareModelTest {

    private val playlistId = randomPlatformUUID()
    private val me = User(id = randomPlatformUUID(), username = "me")
    private val myProfile = UserProfile(id = me.id, username = me.username)
    private val anna = UserProfile(id = randomPlatformUUID(), username = "anna", displayName = "Anna Example")
    private val ben = UserProfile(id = randomPlatformUUID(), username = "ben")
    private val carl = UserProfile(id = randomPlatformUUID(), username = "carl", displayName = "Charles")

    private var playlist: UserPlaylist? = UserPlaylist(
        id = playlistId,
        name = "Mine",
        songs = emptyList(),
        creator = me.id,
        description = "",
        shares = listOf(PlaylistShare(ben.id, PlaylistAccess.READ))
    )

    private val userPlaylistService = mockk<IUserPlaylistService> {
        coEvery { byId(playlistId) } answers { playlist }
        coEvery { setPublic(playlistId, any()) } answers {
            playlist = playlist?.copy(isPublic = secondArg())
            true
        }
        coEvery { setShare(playlistId, any(), any()) } answers {
            val userId = secondArg<PlatformUUID>()
            playlist = playlist?.let { current ->
                current.copy(shares = current.shares.filter { it.userId != userId } + PlaylistShare(userId, thirdArg()))
            }
        }
        coEvery { removeShare(playlistId, any()) } answers {
            val userId = secondArg<PlatformUUID>()
            val before = playlist?.shares.orEmpty()
            playlist = playlist?.copy(shares = before.filter { it.userId != userId })
            before.any { it.userId == userId }
        }
        coEvery { transferOwnership(playlistId, any()) } answers {
            val newOwner = secondArg<PlatformUUID>()
            playlist = playlist?.let { current ->
                current.copy(
                    creator = newOwner,
                    shares = current.shares.filter { it.userId != newOwner } + PlaylistShare(current.creator, PlaylistAccess.WRITE)
                )
            }
            true
        }
    }
    private val userService = mockk<IUserService> {
        coEvery { allProfiles() } returns listOf(anna, ben, carl, myProfile)
    }
    private val globalState = mockk<GlobalStateModel> {
        every { user } returns MutableStateFlow(me)
    }
    private val songCache = mockk<SongCache>(relaxed = true)
    private val logger = mockk<Logger>(relaxed = true)

    private fun createModel() = PlaylistShareModel(
        playlistId,
        userPlaylistService,
        userService,
        globalState,
        songCache,
        logger,
        UnconfinedDispatchers
    )

    private fun loadedModel() = createModel().also { it.load() }

    private fun verifyNotified(times: Int) {
        verify(exactly = times) { songCache.notifyPlaylistChanged(playlistId) }
        verify(exactly = times) { songCache.notifyPlaylistsChanged() }
    }

    @Test
    fun nothingIsRequestedBeforeTheModelIsLoaded() {
        val model = createModel()

        assertTrue(model.state.value.isLoading)
        coVerify(exactly = 0) { userService.allProfiles() }
        coVerify(exactly = 0) { userPlaylistService.byId(any()) }
    }

    @Test
    fun loadingSplitsTheUsersIntoMembersAndCandidatesWithoutTheOwner() {
        val state = loadedModel().state.value

        assertFalse(state.isLoading)
        assertFalse(state.isPublic)
        assertEquals(listOf(PlaylistMember(ben, PlaylistAccess.READ)), state.members)
        assertEquals(listOf(anna, carl), state.candidates)
        assertEquals(listOf(anna, ben, carl), state.others)
        verifyNotified(0)
    }

    @Test
    fun aFailingLoadEndsLoadingAndIsLogged() {
        val failure = IOException("down")
        coEvery { userService.allProfiles() } throws failure

        val state = loadedModel().state.value

        assertFalse(state.isLoading)
        assertTrue(state.members.isEmpty())
        assertTrue(state.candidates.isEmpty())
        verify(exactly = 1) { logger.error(any(), any(), failure, any()) }
    }

    @Test
    fun theFilterMatchesUsernameAndDisplayNameIgnoringCase() {
        val model = loadedModel()

        model.setFilter("CHAR")
        assertEquals(listOf(carl), model.state.value.candidates)
        assertEquals(listOf(PlaylistMember(ben, PlaylistAccess.READ)), model.state.value.members)

        model.setFilter(" ann ")
        assertEquals(listOf(anna), model.state.value.candidates)

        model.setFilter("be")
        assertTrue(model.state.value.candidates.isEmpty())
        assertEquals(listOf(ben), model.state.value.others)

        model.setFilter("")
        assertEquals(listOf(anna, carl), model.state.value.candidates)
        coVerify(exactly = 1) { userService.allProfiles() }
    }

    @Test
    fun addingAUserSharesThePlaylistAndMovesTheUserToTheMembers() {
        val model = loadedModel()

        model.setShare(anna.id, PlaylistAccess.READ)

        coVerify(exactly = 1) { userPlaylistService.setShare(playlistId, anna.id, PlaylistAccess.READ) }
        assertEquals(
            listOf(PlaylistMember(anna, PlaylistAccess.READ), PlaylistMember(ben, PlaylistAccess.READ)),
            model.state.value.members
        )
        assertEquals(listOf(carl), model.state.value.candidates)
        verifyNotified(1)
    }

    @Test
    fun changingTheAccessOfAMemberKeepsTheMemberWithTheNewAccess() {
        val model = loadedModel()

        model.setShare(ben.id, PlaylistAccess.WRITE)

        coVerify(exactly = 1) { userPlaylistService.setShare(playlistId, ben.id, PlaylistAccess.WRITE) }
        assertEquals(listOf(PlaylistMember(ben, PlaylistAccess.WRITE)), model.state.value.members)
        assertEquals(listOf(anna, carl), model.state.value.candidates)
        verifyNotified(1)
    }

    @Test
    fun removingAMemberMovesTheUserBackToTheCandidates() {
        val model = loadedModel()

        model.removeShare(ben.id)

        coVerify(exactly = 1) { userPlaylistService.removeShare(playlistId, ben.id) }
        assertTrue(model.state.value.members.isEmpty())
        assertEquals(listOf(anna, ben, carl), model.state.value.candidates)
        verifyNotified(1)
    }

    @Test
    fun makingThePlaylistPublicAndPrivateAgainFollowsTheServer() {
        val model = loadedModel()

        model.setPublic(true)
        assertTrue(model.state.value.isPublic)
        coVerify(exactly = 1) { userPlaylistService.setPublic(playlistId, true) }

        model.setPublic(false)
        assertFalse(model.state.value.isPublic)
        coVerify(exactly = 1) { userPlaylistService.setPublic(playlistId, false) }
        verifyNotified(2)
    }

    @Test
    fun aFailingChangeKeepsTheStateAndRefreshesNothing() {
        val failure = IOException("down")
        coEvery { userPlaylistService.setShare(playlistId, any(), any()) } throws failure
        val model = loadedModel()
        val before = model.state.value

        model.setShare(anna.id, PlaylistAccess.READ)

        assertEquals(before, model.state.value)
        verify(exactly = 1) { logger.error(any(), any(), failure, any()) }
        verifyNotified(0)
    }

    @Test
    fun aRejectedChangeKeepsTheStateAndRefreshesNothing() {
        coEvery { userPlaylistService.setPublic(playlistId, any()) } returns false
        val model = loadedModel()

        model.setPublic(true)

        assertFalse(model.state.value.isPublic)
        coVerify(exactly = 1) { userPlaylistService.byId(playlistId) }
        verifyNotified(0)
    }

    @Test
    fun transferringTheOwnershipReportsSuccessAndLeavesThePreviousOwnerAsMember() = runTest {
        val model = loadedModel()

        val transferred = model.transferOwnership(anna.id)

        assertTrue(transferred)
        coVerify(exactly = 1) { userPlaylistService.transferOwnership(playlistId, anna.id) }
        assertEquals(
            listOf(PlaylistMember(ben, PlaylistAccess.READ), PlaylistMember(myProfile, PlaylistAccess.WRITE)),
            model.state.value.members
        )
        assertEquals(listOf(carl), model.state.value.candidates)
        verifyNotified(1)
    }

    @Test
    fun aRejectedTransferReportsFailureAndRefreshesNothing() = runTest {
        coEvery { userPlaylistService.transferOwnership(playlistId, any()) } returns false
        val model = loadedModel()

        assertFalse(model.transferOwnership(anna.id))

        verifyNotified(0)
    }

    @Test
    fun aFailingTransferReportsFailureAndIsLogged() = runTest {
        val failure = IOException("down")
        coEvery { userPlaylistService.transferOwnership(playlistId, any()) } throws failure
        val model = loadedModel()

        assertFalse(model.transferOwnership(anna.id))

        verify(exactly = 1) { logger.error(any(), any(), failure, any()) }
        verifyNotified(0)
    }

    @Test
    fun leavingRemovesTheShareOfTheCurrentUserWithoutLoadingAnything() = runTest {
        val model = createModel()

        val left = model.leave()

        assertTrue(left)
        coVerify(exactly = 1) { userPlaylistService.removeShare(playlistId, me.id) }
        coVerify(exactly = 0) { userService.allProfiles() }
        coVerify(exactly = 0) { userPlaylistService.byId(any()) }
        verifyNotified(1)
    }

    @Test
    fun aFailingLeaveReportsFailureAndRefreshesNothing() = runTest {
        val failure = IOException("down")
        coEvery { userPlaylistService.removeShare(playlistId, any()) } throws failure
        val model = createModel()

        assertFalse(model.leave())

        verify(exactly = 1) { logger.error(any(), any(), failure, any()) }
        verifyNotified(0)
    }

    @Test
    fun leavingWithoutAKnownUserDoesNothing() = runTest {
        every { globalState.user } returns MutableStateFlow(null)
        val model = createModel()

        assertFalse(model.leave())

        coVerify(exactly = 0) { userPlaylistService.removeShare(any(), any()) }
        verifyNotified(0)
    }

    @Test
    fun aClosedModelAppliesNoFurtherChange() {
        val model = loadedModel()

        model.close()
        model.setPublic(true)

        coVerify(exactly = 0) { userPlaylistService.setPublic(any(), any()) }
        assertFalse(model.state.value.isPublic)
    }
}
