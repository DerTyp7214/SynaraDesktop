package dev.dertyp.synara.player

import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.ISongService
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.testing.InMemorySettings
import dev.dertyp.synara.testing.testSong
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SongDataSourceTest {
    private val songService = mockk<ISongService>()
    private val songCache = SongCache()
    private val settings = InMemorySettings()
    private val dataSource = SongDataSource(songService, songCache, settings)

    @Test
    fun cachedSongIsReturnedWithoutAServiceCall() = runBlocking {
        val song = testSong(1)
        songCache.put(song)

        assertSame(song, dataSource.getSong(song.id))

        coVerify(exactly = 0) { songService.byId(any()) }
    }

    @Test
    fun missingSongIsFetchedAndCached() = runBlocking {
        val song = testSong(1)
        coEvery { songService.byId(song.id) } returns song

        assertSame(song, dataSource.getSong(song.id))
        assertSame(song, dataSource.getSong(song.id))

        coVerify(exactly = 1) { songService.byId(song.id) }
        assertSame(song, songCache.get(song.id))
    }

    @Test
    fun unknownSongIsNotCached() = runBlocking {
        val id = randomPlatformUUID()
        coEvery { songService.byId(id) } returns null

        assertNull(dataSource.getSong(id))
        assertNull(dataSource.getSong(id))

        coVerify(exactly = 2) { songService.byId(id) }
        assertNull(songCache.get(id))
    }

    @Test
    fun sourceCacheKeyCarriesTheStreamingQualitySetting() {
        val id = randomPlatformUUID()
        assertEquals("$id:0", dataSource.source(id).cacheKey)

        settings.put(SettingKey.StreamingQuality, 3)

        assertEquals("$id:3", dataSource.source(id).cacheKey)
    }

    @Test
    fun sourceResolvesItsSongThroughTheCache() = runBlocking {
        val song = testSong(1)
        songCache.put(song)
        val source = dataSource.source(song.id)

        assertTrue(source.prepare())

        assertSame(song, source.song)
        coVerify(exactly = 0) { songService.byId(any()) }
    }
}
