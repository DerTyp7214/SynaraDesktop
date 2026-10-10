export type LikeLevel = "NONE" | "LIKE" | "SUPER";

export type PlaylistRole = "OWNER" | "EDITOR" | "VIEWER" | "VISITOR";

export type PlaylistAccess = "READ" | "WRITE";

export type PlayableType = "song" | "album" | "artist" | "playlist";

export type PlaybackStatus = "Playing" | "Paused" | "Stopped";

export type SearchFilter =
	| "includeSongs"
	| "includeAlbums"
	| "includeArtists"
	| "includePlaylists";

export type ImageSize =
	| 64
	| 128
	| 256
	| 350
	| 475
	| 512
	| 696
	| 850
	| 1000
	| 1280
	| 1600
	| 2500;

export type TitleTagKind =
	| "FEAT"
	| "PROD"
	| "REMIX"
	| "MIX"
	| "LIVE"
	| "COVER"
	| "ACOUSTIC"
	| "INSTRUMENTAL"
	| "EDIT"
	| "VERSION"
	| "REMASTER"
	| "DEMO"
	| "UNKNOWN";

export interface TitleTag {
	kind: TitleTagKind;
	label: string;
}

export interface ArtistCredit {
	id: string;
	name: string;
	isGroup: boolean;
	imageId: string | null;
	creditedName: string | null;
	joinPhrase: string | null;
}

export interface Artist extends ArtistCredit {
	about: string;
}

export interface Album {
	id: string;
	name: string;
	artists: ArtistCredit[];
	songCount: number;
	releaseDate: string | null;
	totalDuration: number;
	coverId: string | null;
	tags?: TitleTag[];
}

export interface UserSong {
	id: string;
	title: string;
	artists: ArtistCredit[];
	album: Album | null;
	duration: number;
	explicit: boolean;
	releaseDate: string | null;
	lyrics: string;
	trackNumber: number;
	discNumber: number;
	coverId: string | null;
	tags?: TitleTag[];
	isFavourite: boolean | null;
	likeLevel: LikeLevel | null;
}

export interface PlaylistShare {
	userId: string;
	access: PlaylistAccess;
}

export interface UserPlaylist {
	id: string;
	name: string;
	songs: string[];
	totalDuration: number;
	imageId: string | null;
	creator: string;
	description: string;
	isPublic: boolean;
	shares: PlaylistShare[];
}

export interface SynaraServerInfo {
	host: string;
	port: number;
	imageBaseUrl: string;
}

export interface SynaraPlaybackState {
	isPlaying: boolean;
	position: number;
	duration: number;
}

export interface SynaraPlaylistInfo {
	playlistId: string;
	ownerName: string;
	role: PlaylistRole;
}

export interface SynaraPlaylists {
	playlists: UserPlaylist[];
	playlistInfo: SynaraPlaylistInfo[];
}

export interface SynaraSearchResult {
	songs: UserSong[];
	albums: Album[];
	artists: Artist[];
	playlists: UserPlaylist[];
	playlistInfo: SynaraPlaylistInfo[];
}

export interface SynaraQueueItem {
	queueId: string;
	song: UserSong;
}

export const WRITE_ROLES: ReadonlySet<PlaylistRole> = new Set<PlaylistRole>([
	"OWNER",
	"EDITOR",
]);

export const SHARED_ROLES: ReadonlySet<PlaylistRole> = new Set<PlaylistRole>([
	"EDITOR",
	"VIEWER",
]);
