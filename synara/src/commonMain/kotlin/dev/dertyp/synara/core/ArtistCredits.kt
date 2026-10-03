package dev.dertyp.synara.core

import dev.dertyp.data.Artist
import dev.dertyp.data.ArtistCredit

fun Artist.toCredit(): ArtistCredit = ArtistCredit(
    id = id,
    name = name,
    isGroup = isGroup,
    artists = artists.map { it.toCredit() },
    genres = genres,
    imageId = imageId,
    blurHash = blurHash,
    musicBrainzId = musicBrainzId,
    isFollowed = isFollowed,
    creditedName = creditedName,
    joinPhrase = joinPhrase,
)
