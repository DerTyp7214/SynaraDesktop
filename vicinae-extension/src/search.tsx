import {
	Action,
	ActionPanel,
	getPreferenceValues,
	Icon,
	Image,
	type LaunchProps,
	List,
} from "@vicinae/api";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
	AddToPlaylistSubmenu,
	LikeActions,
	PlaybackActions,
	SHORTCUTS,
} from "./lib/actions";
import * as synara from "./lib/dbus";
import { NotRunningView, showFailure } from "./lib/feedback";
import {
	albumName,
	albumSubtitle,
	artistLine,
	formatDuration,
	playlistSubtitle,
	songTitle,
} from "./lib/format";
import {
	coverIcon,
	coverMarkdown,
	PUBLIC_ACCESSORY,
	SHARED_ACCESSORY,
	SongDetail,
	songAccessories,
} from "./lib/rows";
import {
	type SearchFilter,
	SHARED_ROLES,
	type SynaraPlaylistInfo,
	type SynaraSearchResult,
	type UserPlaylist,
	type UserSong,
} from "./lib/types";
import { Lyrics } from "./lyrics";

const ALL_SCOPE = "all";

const SCOPES: { filter: SearchFilter; title: string }[] = [
	{ filter: "includeSongs", title: "Songs" },
	{ filter: "includeAlbums", title: "Albums" },
	{ filter: "includeArtists", title: "Artists" },
	{ filter: "includePlaylists", title: "Playlists" },
];

function playlistAccessories(
	playlist: UserPlaylist,
	info: SynaraPlaylistInfo | undefined,
): List.Item.Accessory[] {
	const accessories: List.Item.Accessory[] = [];
	if (playlist.isPublic) accessories.push(PUBLIC_ACCESSORY);
	if (info && SHARED_ROLES.has(info.role)) accessories.push(SHARED_ACCESSORY);
	return accessories;
}

export default function Command(
	props: LaunchProps<{ arguments: Arguments.Search }>,
) {
	const [searchText, setSearchText] = useState(props.arguments.query ?? "");
	const [scope, setScope] = useState(ALL_SCOPE);
	const [results, setResults] = useState<SynaraSearchResult | null>(null);
	const [nowPlaying, setNowPlaying] = useState<UserSong | null>(null);
	const [isLoading, setIsLoading] = useState(false);
	const [notRunning, setNotRunning] = useState(false);
	const primary =
		getPreferenceValues<Preferences>().defaultSearchAction ?? "play";

	const report = useCallback((title: string, error: unknown) => {
		if (error instanceof synara.SynaraNotRunningError) {
			setNotRunning(true);
			setResults(null);
			setNowPlaying(null);
		} else {
			void showFailure(title, error);
		}
	}, []);

	const refreshNowPlaying = useCallback(() => {
		synara
			.getCurrentSong()
			.then((song) => {
				setNowPlaying(song);
				setNotRunning(false);
			})
			.catch((error: unknown) =>
				report("Failed to load the current song", error),
			);
	}, [report]);

	useEffect(refreshNowPlaying, [refreshNowPlaying]);

	useEffect(() => {
		if (!searchText) {
			setResults(null);
			setIsLoading(false);
			return;
		}

		let current = true;
		setIsLoading(true);
		synara
			.search(
				searchText,
				SCOPES.find((entry) => entry.filter === scope)?.filter,
			)
			.then((found) => {
				if (!current) return;
				setResults(found);
				setNotRunning(false);
			})
			.catch((error: unknown) => {
				if (current) report("Search failed", error);
			})
			.finally(() => {
				if (current) setIsLoading(false);
			});
		return () => {
			current = false;
		};
	}, [searchText, scope, report]);

	const playlistInfo = useMemo(
		() =>
			new Map(
				(results?.playlistInfo ?? []).map((info) => [info.playlistId, info]),
			),
		[results],
	);

	const updateSong = (updated: UserSong) => {
		setNowPlaying((song) => (song?.id === updated.id ? updated : song));
		setResults(
			(found) =>
				found && {
					...found,
					songs: found.songs.map((song) =>
						song.id === updated.id ? updated : song,
					),
				},
		);
	};

	return (
		<List
			isLoading={isLoading}
			searchText={searchText}
			onSearchTextChange={setSearchText}
			searchBarPlaceholder="Search music..."
			throttle
			isShowingDetail={!!(results || (!searchText && nowPlaying))}
			searchBarAccessory={
				<List.Dropdown
					tooltip="Search in"
					defaultValue={ALL_SCOPE}
					onChange={setScope}
				>
					<List.Dropdown.Item title="All" value={ALL_SCOPE} />
					{SCOPES.map((entry) => (
						<List.Dropdown.Item
							key={entry.filter}
							title={entry.title}
							value={entry.filter}
						/>
					))}
				</List.Dropdown>
			}
		>
			{notRunning && <NotRunningView />}
			{!searchText && nowPlaying && (
				<List.Section title="Now Playing">
					<List.Item
						id={`now-playing-${nowPlaying.id}`}
						title={songTitle(nowPlaying)}
						subtitle={artistLine(nowPlaying.artists)}
						icon={coverIcon(nowPlaying.coverId, Icon.Music)}
						accessories={songAccessories(nowPlaying)}
						detail={<SongDetail song={nowPlaying} />}
						actions={
							<ActionPanel title={songTitle(nowPlaying)}>
								<Action.Push
									title="Show Lyrics"
									icon={Icon.Paragraph}
									shortcut={SHORTCUTS.showLyrics}
									target={<Lyrics />}
								/>
								<ActionPanel.Section title="Song">
									<LikeActions song={nowPlaying} onSongChange={updateSong} />
									<AddToPlaylistSubmenu id={nowPlaying.id} type="song" />
								</ActionPanel.Section>
							</ActionPanel>
						}
					/>
				</List.Section>
			)}
			<List.Section title="Songs">
				{results?.songs.map((song) => (
					<List.Item
						key={song.id}
						id={`song-${song.id}`}
						title={songTitle(song)}
						subtitle={artistLine(song.artists)}
						icon={coverIcon(song.coverId, Icon.Music)}
						accessories={songAccessories(song)}
						detail={<SongDetail song={song} />}
						actions={
							<ActionPanel title={songTitle(song)}>
								<PlaybackActions
									id={song.id}
									type="song"
									primary={primary}
									onPlayed={refreshNowPlaying}
								/>
								<ActionPanel.Section title="Song">
									<LikeActions song={song} onSongChange={updateSong} />
									<AddToPlaylistSubmenu id={song.id} type="song" />
								</ActionPanel.Section>
							</ActionPanel>
						}
					/>
				))}
			</List.Section>
			<List.Section title="Albums">
				{results?.albums.map((album) => (
					<List.Item
						key={album.id}
						id={`album-${album.id}`}
						title={albumName(album)}
						subtitle={albumSubtitle(album)}
						icon={coverIcon(album.coverId, Icon.Cd)}
						detail={
							<List.Item.Detail
								markdown={coverMarkdown(album.coverId, albumName(album))}
								metadata={
									<List.Item.Detail.Metadata>
										<List.Item.Detail.Metadata.Label
											title="Artists"
											text={artistLine(album.artists)}
										/>
										{album.releaseDate && (
											<List.Item.Detail.Metadata.Label
												title="Released"
												text={album.releaseDate}
											/>
										)}
										<List.Item.Detail.Metadata.Label
											title="Tracks"
											text={String(album.songCount)}
										/>
										<List.Item.Detail.Metadata.Label
											title="Duration"
											text={formatDuration(album.totalDuration)}
										/>
									</List.Item.Detail.Metadata>
								}
							/>
						}
						actions={
							<ActionPanel title={albumName(album)}>
								<PlaybackActions
									id={album.id}
									type="album"
									primary={primary}
									onPlayed={refreshNowPlaying}
								/>
								<ActionPanel.Section>
									<AddToPlaylistSubmenu id={album.id} type="album" />
								</ActionPanel.Section>
							</ActionPanel>
						}
					/>
				))}
			</List.Section>
			<List.Section title="Artists">
				{results?.artists.map((artist) => (
					<List.Item
						key={artist.id}
						id={`artist-${artist.id}`}
						title={artist.name}
						icon={coverIcon(artist.imageId, Icon.Person, Image.Mask.Circle)}
						detail={
							<List.Item.Detail
								markdown={coverMarkdown(artist.imageId, artist.name)}
							/>
						}
						actions={
							<ActionPanel title={artist.name}>
								<PlaybackActions
									id={artist.id}
									type="artist"
									primary={primary}
									onPlayed={refreshNowPlaying}
								/>
								<ActionPanel.Section>
									<AddToPlaylistSubmenu id={artist.id} type="artist" />
								</ActionPanel.Section>
							</ActionPanel>
						}
					/>
				))}
			</List.Section>
			<List.Section title="Playlists">
				{results?.playlists.map((playlist) => {
					const info = playlistInfo.get(playlist.id);
					return (
						<List.Item
							key={playlist.id}
							id={`playlist-${playlist.id}`}
							title={playlist.name}
							subtitle={playlistSubtitle(playlist, info)}
							icon={coverIcon(playlist.imageId, Icon.AppWindowList)}
							accessories={playlistAccessories(playlist, info)}
							detail={
								<List.Item.Detail
									markdown={coverMarkdown(playlist.imageId, playlist.name)}
									metadata={
										<List.Item.Detail.Metadata>
											<List.Item.Detail.Metadata.Label
												title="Details"
												text={playlistSubtitle(playlist, info)}
											/>
											{playlist.description && (
												<List.Item.Detail.Metadata.Label
													title="Description"
													text={playlist.description}
												/>
											)}
										</List.Item.Detail.Metadata>
									}
								/>
							}
							actions={
								<ActionPanel title={playlist.name}>
									<PlaybackActions
										id={playlist.id}
										type="playlist"
										primary={primary}
										onPlayed={refreshNowPlaying}
									/>
									<ActionPanel.Section>
										<AddToPlaylistSubmenu id={playlist.id} type="playlist" />
									</ActionPanel.Section>
								</ActionPanel>
							}
						/>
					);
				})}
			</List.Section>
		</List>
	);
}
