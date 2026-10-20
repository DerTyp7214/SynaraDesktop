package dev.dertyp.synara.mixes

import dev.dertyp.data.RecommendationWindow
import dev.dertyp.data.StatsRange
import dev.dertyp.data.TopOrder
import dev.dertyp.data.UserSong
import dev.dertyp.services.IListeningStatsService
import dev.dertyp.services.IRecommendationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.util.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertSame

class MixSpecFetchTest {

    private val recs = mockk<IRecommendationService>()
    private val stats = mockk<IListeningStatsService>()
    private val mixSongs = listOf(mockk<UserSong>())
    private val moodSongs = listOf(mockk<UserSong>())
    private val topSongs = listOf(mockk<UserSong>())

    @BeforeTest
    fun setUp() {
        coEvery { recs.getMix(any(), any()) } returns mixSongs
        coEvery { recs.getMoodPlaylist(any(), any()) } returns moodSongs
        coEvery { stats.getTopSongs(any(), any(), any(), any()) } returns topSongs
    }

    @Test
    fun recentWindowsFetchTheMix() = runTest {
        val result = MixSpec.YourMix(MixSource.Recent(RecommendationWindow.DAY), 25).fetch(recs, stats)

        assertSame(mixSongs, result)
        coVerify { recs.getMix(RecommendationWindow.DAY, 25) }
        confirmVerified(recs, stats)
    }

    @Test
    fun moodsFetchTheMoodPlaylist() = runTest {
        val result = MixSpec.YourMix(MixSource.Mood("chill"), 10).fetch(recs, stats)

        assertSame(moodSongs, result)
        coVerify { recs.getMoodPlaylist("chill", 10) }
        confirmVerified(recs, stats)
    }

    @Test
    fun topSongsFetchWithTheSystemTimeZone() = runTest {
        val result = MixSpec.TopSongs(StatsRange.MONTH, TopOrder.LISTEN_COUNT, 100).fetch(recs, stats)

        assertSame(topSongs, result)
        coVerify { stats.getTopSongs(StatsRange.MONTH, TimeZone.getDefault().id, 100, TopOrder.LISTEN_COUNT) }
        confirmVerified(recs, stats)
    }
}
