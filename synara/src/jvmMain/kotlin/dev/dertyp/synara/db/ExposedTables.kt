package dev.dertyp.synara.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID

object DownloadedImages : UUIDTable("image") {
    val path = text("path")
    val imageHash = varchar("hash", 255)
    val origin = text("origin")
}

object DownloadedUsers : UUIDTable("user") {
    val username = varchar("username", 255).uniqueIndex()
    val displayName = varchar("displayName", 255).nullable()
    val passwordHash = varchar("passwordHash", 255)
    val isAdmin = bool("isAdmin").default(false)
    val profileImage = reference("profileImageId", DownloadedImages.id, onDelete = ReferenceOption.SET_NULL).nullable()
}

object DownloadedSongs : UUIDTable("song") {
    val title = text("title").default("")
    val albumId = reference("albumId", DownloadedAlbums.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val duration = long("duration").default(0L)
    val releaseDate = varchar("releaseDate", 128).nullable()
    val lyrics = text("lyrics").default("")
    val explicit = bool("explicit").default(false)
    val filePath = text("filePath").default("")
    val cover = reference("cover", DownloadedImages.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val originalUrl = text("originalUrl").default("")
    val trackNumber = integer("trackNumber").default(1)
    val discNumber = integer("discNumber").default(1)
    val copyright = text("copyright").default("")
    val sampleRate = integer("sampleRate").default(0)
    val bitsPerSample = integer("bitsPerSample").default(0)
    val bitRate = long("bitRate").default(0)
    val fileSize = long("fileSize").default(0)
    val inserted = long("inserted").default(0L)

    // UserSong fields
    val isFavourite = bool("favourite").default(false)
    val createdAt = long("createdAt").nullable()
    val updatedAt = long("updatedAt").nullable()

    val explicitlySaved = bool("explicitlySaved").default(false)
    val musicBrainzId = javaUUID("musicBrainzId").nullable()
}

object DownloadedAlbums : UUIDTable("album") {
    val name = text("name")
    val releaseDate = varchar("releaseDate", 128).nullable()
    val songCount = integer("songCount").default(0)
    val cover = reference("cover", DownloadedImages.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val originalId = text("originalId").nullable()
    val totalDuration = long("totalDuration").default(0L)
    val totalSize = long("totalSize").default(0L)

    val explicitlySaved = bool("explicitlySaved").default(false)
    val musicBrainzId = javaUUID("musicBrainzId").nullable()
}

object DownloadedArtists : UUIDTable("artist") {
    val name = text("name")
    val isGroup = bool("group").default(false)
    val groupId = reference("groupId", id).nullable()
    val about = text("about").default("")
    val image = reference("image", DownloadedImages.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val isFollowed = bool("isFollowed").default(false)

    val explicitlySaved = bool("explicitlySaved").default(false)
    val musicBrainzId = javaUUID("musicBrainzId").nullable()
}

object DownloadedGenres : UUIDTable("genre") {
    val name = varchar("name", 255).uniqueIndex()
}

object DownloadedPlaylists : UUIDTable("playlist") {
    val name = varchar("name", 255)
    val imageId = reference("imageId", DownloadedImages.id, onDelete = ReferenceOption.SET_NULL).nullable()

    val explicitlySaved = bool("explicitlySaved").default(false)
}

object DownloadedUserPlaylists : UUIDTable("userPlaylist") {
    val name = text("name")
    val description = text("description").default("")
    val customIdentifier = text("customIdentifier").nullable()
    val creator = reference("creator", DownloadedUsers.id, onDelete = ReferenceOption.CASCADE)
    val imageId = reference("imageId", DownloadedImages.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val origin = text("origin").nullable()
    val modifiedAt = long("modifiedAt").nullable()

    val explicitlySaved = bool("explicitlySaved").default(false)
}

object DownloadedSongArtists : Table("songArtist") {
    val songId = reference("songId", DownloadedSongs.id, onDelete = ReferenceOption.CASCADE)
    val artistId = reference("artistId", DownloadedArtists.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(songId, artistId)
}

object DownloadedAlbumArtists : Table("albumArtist") {
    val albumId = reference("albumId", DownloadedAlbums.id, onDelete = ReferenceOption.CASCADE)
    val artistId = reference("artistId", DownloadedArtists.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(albumId, artistId)
}

object DownloadedArtistMembers : Table("artistMember") {
    val groupId = reference("groupId", DownloadedArtists.id, onDelete = ReferenceOption.CASCADE)
    val memberId = reference("memberId", DownloadedArtists.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(groupId, memberId)
}

object DownloadedPlaylistSongs : Table("playlistSong") {
    val playlistId = reference("playlistId", DownloadedPlaylists.id, onDelete = ReferenceOption.CASCADE)
    val songId = reference("songId", DownloadedSongs.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position").default(0)
    override val primaryKey = PrimaryKey(playlistId, songId)
}

object DownloadedUserPlaylistSongs : Table("userPlaylistSong") {
    val playlistId = reference("playlistId", DownloadedUserPlaylists.id, onDelete = ReferenceOption.CASCADE)
    val songId = reference("songId", DownloadedSongs.id, onDelete = ReferenceOption.CASCADE)
    val addedAt = long("addedAt")
    val musicBrainzId = javaUUID("musicBrainzId").nullable()
    override val primaryKey = PrimaryKey(playlistId, songId, addedAt)
}

object DownloadedSongGenres : Table("song_genre") {
    val songId = reference("songId", DownloadedSongs.id, onDelete = ReferenceOption.CASCADE)
    val genreId = reference("genreId", DownloadedGenres.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(songId, genreId)
}

object DownloadedAlbumGenres : Table("album_genre") {
    val albumId = reference("albumId", DownloadedAlbums.id, onDelete = ReferenceOption.CASCADE)
    val genreId = reference("genreId", DownloadedGenres.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(albumId, genreId)
}

object DownloadedArtistGenres : Table("artist_genre") {
    val artistId = reference("artistId", DownloadedArtists.id, onDelete = ReferenceOption.CASCADE)
    val genreId = reference("genreId", DownloadedGenres.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(artistId, genreId)
}

object RecentlyPlayedSongs : Table("recentlyPlayedSong") {
    val userId = reference("userId", DownloadedUsers.id, onDelete = ReferenceOption.CASCADE)
    val songId = varchar("songId", 36)
    val timestamp = long("timestamp")
    val payload = text("payload")
    override val primaryKey = PrimaryKey(userId, songId)
}

object RecentlyPlayedAlbums : Table("recentlyPlayedAlbum") {
    val userId = reference("userId", DownloadedUsers.id, onDelete = ReferenceOption.CASCADE)
    val albumId = varchar("albumId", 36)
    val timestamp = long("timestamp")
    val payload = text("payload")
    override val primaryKey = PrimaryKey(userId, albumId)
}

object RecentlyPlayedArtists : Table("recentlyPlayedArtist") {
    val userId = reference("userId", DownloadedUsers.id, onDelete = ReferenceOption.CASCADE)
    val artistId = varchar("artistId", 36)
    val timestamp = long("timestamp")
    val payload = text("payload")
    override val primaryKey = PrimaryKey(userId, artistId)
}

object ScrobbleQueue : IntIdTable("scrobbleQueue") {
    val userId = reference("userId", DownloadedUsers.id, onDelete = ReferenceOption.CASCADE)
    val songId = varchar("songId", 36)
    val timestamp = long("timestamp")
    val target = text("target")
    val payload = text("payload")
}

object LocalHistory : IntIdTable("localHistory") {
    val userId = reference("userId", DownloadedUsers.id, onDelete = ReferenceOption.CASCADE)
    val songId = varchar("songId", 36)
    val timestamp = long("timestamp")
    val payload = text("payload")
}
