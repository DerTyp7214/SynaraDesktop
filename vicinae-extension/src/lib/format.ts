import type {
	Album,
	ArtistCredit,
	LikeLevel,
	SynaraPlaylistInfo,
	TitleTag,
	UserPlaylist,
	UserSong,
} from "./types";

const SEPARATOR = " · ";

const LIKE_LABELS: Record<LikeLevel, string> = {
	NONE: "Not liked",
	LIKE: "Liked",
	SUPER: "Super liked",
};

function withTitleTags(title: string, tags: TitleTag[] | undefined): string {
	return (tags ?? [])
		.filter((tag) => tag.kind !== "FEAT")
		.reduce((text, tag) => `${text} (${tag.label})`, title);
}

function plural(count: number, noun: string): string {
	return `${count} ${noun}${count === 1 ? "" : "s"}`;
}

export function artistLine(artists: ArtistCredit[]): string {
	return artists
		.map((artist, index) => {
			const name = artist.creditedName?.trim()
				? artist.creditedName
				: artist.name;
			if (index === artists.length - 1) return name;
			return name + (artist.joinPhrase ? artist.joinPhrase : ", ");
		})
		.join("");
}

export function songTitle(song: UserSong): string {
	return withTitleTags(song.title, song.tags);
}

export function albumName(album: Album): string {
	return withTitleTags(album.name, album.tags);
}

export function likeLevelOf(song: UserSong): LikeLevel {
	return song.likeLevel ?? "NONE";
}

export function likeLabel(level: LikeLevel): string {
	return LIKE_LABELS[level];
}

export function formatDuration(ms: number): string {
	const totalSeconds = Math.max(0, Math.floor(ms / 1000));
	const hours = Math.floor(totalSeconds / 3600);
	const minutes = Math.floor((totalSeconds % 3600) / 60);
	const seconds = String(totalSeconds % 60).padStart(2, "0");
	if (hours === 0) return `${minutes}:${seconds}`;
	return `${hours}:${String(minutes).padStart(2, "0")}:${seconds}`;
}

export function releaseYear(releaseDate: string | null): string | undefined {
	return releaseDate?.slice(0, 4);
}

export function albumSubtitle(album: Album): string {
	return [
		artistLine(album.artists),
		releaseYear(album.releaseDate),
		plural(album.songCount, "track"),
	]
		.filter(Boolean)
		.join(SEPARATOR);
}

export function playlistSubtitle(
	playlist: UserPlaylist,
	info: SynaraPlaylistInfo | undefined,
): string {
	const parts = [plural(playlist.songs.length, "song")];
	if (playlist.totalDuration >= 0) {
		parts.push(formatDuration(playlist.totalDuration));
	}
	if (info && info.role !== "OWNER" && info.ownerName) {
		parts.push(`by ${info.ownerName}`);
	}
	return parts.join(SEPARATOR);
}
