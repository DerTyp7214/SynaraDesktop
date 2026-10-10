package dev.dertyp.synara.player

import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.testing.testSongs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SongCacheTest {
    private val cache = SongCache()

    private fun TestScope.collectUpdates(): List<CacheUpdate> {
        val updates = mutableListOf<CacheUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cache.updates.toList(updates) }
        return updates
    }

    private fun TestScope.collectPlaylistUpdates(): List<PlaylistUpdate> {
        val updates = mutableListOf<PlaylistUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cache.playlistUpdates.toList(updates) }
        return updates
    }

    @Test
    fun missReturnsNull() = runTest {
        assertNull(cache.get(randomPlatformUUID()))
        assertEquals(0, cache.size)
    }

    @Test
    fun putSongIsReturnedByGet() = runTest {
        val song = testSong(1)

        cache.put(song)

        assertEquals(song, cache.get(song.id))
        assertEquals(1, cache.size)
    }

    @Test
    fun putReplacesTheSongWithTheSameId() = runTest {
        val song = testSong(1)
        val changed = song.copy(duration = song.duration + 1_000)

        cache.put(song)
        cache.put(changed)

        assertEquals(changed, cache.get(song.id))
        assertEquals(1, cache.size)
    }

    @Test
    fun putEmitsASongUpdate() = runTest {
        val updates = collectUpdates()
        val song = testSong(1)

        cache.put(song)
        runCurrent()

        assertEquals(listOf<CacheUpdate>(CacheUpdate.SongUpdated(song)), updates)
    }

    @Test
    fun putAllStoresEverySongAndEmitsAnUpdatePerSong() = runTest {
        val updates = collectUpdates()
        val songs = testSongs(3)

        cache.putAll(songs)
        runCurrent()

        assertEquals(songs.map { CacheUpdate.SongUpdated(it) }, updates)
        songs.forEach { assertEquals(it, cache.get(it.id)) }
        assertEquals(3, cache.size)
    }

    @Test
    fun refreshCachedReplacesOnlyCachedAndChangedSongs() = runTest {
        val unchanged = testSong(1)
        val stale = testSong(2)
        val uncached = testSong(3)
        val fresh = stale.copy(duration = stale.duration + 1_000)
        cache.putAll(listOf(unchanged, stale))
        val updates = collectUpdates()

        cache.refreshCached(listOf(unchanged.copy(), fresh, uncached))
        runCurrent()

        assertEquals(listOf<CacheUpdate>(CacheUpdate.SongUpdated(fresh)), updates)
        assertEquals(unchanged, cache.get(unchanged.id))
        assertEquals(fresh, cache.get(stale.id))
        assertNull(cache.get(uncached.id))
        assertEquals(2, cache.size)
    }

    @Test
    fun oldestSongIsEvictedOnceTheCacheIsFull() = runTest {
        val songs = testSongs(10_001)

        songs.forEach { cache.put(it) }

        assertEquals(10_000, cache.size)
        assertNull(cache.get(songs[0].id))
        assertEquals(songs[1], cache.get(songs[1].id))
        assertEquals(songs[10_000], cache.get(songs[10_000].id))
    }

    @Test
    fun readingASongProtectsItFromTheNextEviction() = runTest {
        val songs = testSongs(10_001)
        songs.take(10_000).forEach { cache.put(it) }

        assertEquals(songs[0], cache.get(songs[0].id))
        cache.put(songs[10_000])

        assertEquals(10_000, cache.size)
        assertEquals(songs[0], cache.get(songs[0].id))
        assertNull(cache.get(songs[1].id))
    }

    @Test
    fun notifyPlaylistChangedEmitsTheContentChangeOfThatPlaylist() = runTest {
        val updates = collectPlaylistUpdates()
        val playlistId = randomPlatformUUID()

        cache.notifyPlaylistChanged(playlistId)
        runCurrent()

        assertEquals(listOf<PlaylistUpdate>(PlaylistUpdate.PlaylistContentChanged(playlistId)), updates)
    }

    @Test
    fun notifyPlaylistsChangedEmitsThePlaylistsReload() = runTest {
        val updates = collectPlaylistUpdates()

        cache.notifyPlaylistsChanged()
        runCurrent()

        assertEquals(listOf<PlaylistUpdate>(PlaylistUpdate.PlaylistsReloadRequired), updates)
    }

    @Test
    fun notifyLikedSongsChangedEmitsTheLikedSongsReload() = runTest {
        val updates = collectPlaylistUpdates()

        cache.notifyLikedSongsChanged()
        runCurrent()

        assertEquals(listOf<PlaylistUpdate>(PlaylistUpdate.LikedSongsReloadRequired), updates)
    }
}
