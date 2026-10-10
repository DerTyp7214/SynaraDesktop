package dev.dertyp.synara.ui.components

import dev.dertyp.data.LikeLevel
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import kotlin.test.Test
import kotlin.test.assertEquals

class LikeLevelTest {
    private fun song(isFavourite: Boolean?, likeLevel: LikeLevel?) = UserSong(
        id = randomPlatformUUID(),
        title = "Song",
        artists = emptyList(),
        album = null,
        duration = 1_000,
        explicit = false,
        path = "/song",
        isFavourite = isFavourite,
        likeLevel = likeLevel,
    )

    @Test
    fun likeLevelWinsOverFavouriteFlag() {
        assertEquals(LikeLevel.NONE, song(isFavourite = true, likeLevel = LikeLevel.NONE).effectiveLikeLevel)
        assertEquals(LikeLevel.LIKE, song(isFavourite = false, likeLevel = LikeLevel.LIKE).effectiveLikeLevel)
    }

    @Test
    fun favouriteWithoutLikeLevelCountsAsLike() {
        assertEquals(LikeLevel.LIKE, song(isFavourite = true, likeLevel = null).effectiveLikeLevel)
    }

    @Test
    fun neitherFlagMeansNone() {
        assertEquals(LikeLevel.NONE, song(isFavourite = null, likeLevel = null).effectiveLikeLevel)
    }

    @Test
    fun notFavouriteWithoutLikeLevelMeansNone() {
        assertEquals(LikeLevel.NONE, song(isFavourite = false, likeLevel = null).effectiveLikeLevel)
    }

    @Test
    fun superLikeIsKeptWhenFavouriteIsFalse() {
        assertEquals(LikeLevel.SUPER, song(isFavourite = false, likeLevel = LikeLevel.SUPER).effectiveLikeLevel)
    }
}
