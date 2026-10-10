import { ActionPanel, getPreferenceValues, Icon, List } from "@vicinae/api";
import { AddToPlaylistSubmenu, PlaybackActions } from "./lib/actions";
import { getPlaylists } from "./lib/dbus";
import { NotRunningView } from "./lib/feedback";
import { playlistSubtitle } from "./lib/format";
import { useLoader } from "./lib/hooks";
import { coverIcon, PUBLIC_ACCESSORY, READ_ONLY_ACCESSORY } from "./lib/rows";
import type { SynaraPlaylistInfo, UserPlaylist } from "./lib/types";

interface PlaylistRow {
	playlist: UserPlaylist;
	info: SynaraPlaylistInfo | undefined;
}

function accessories(row: PlaylistRow): List.Item.Accessory[] {
	const items: List.Item.Accessory[] = [];
	if (row.playlist.isPublic) items.push(PUBLIC_ACCESSORY);
	if (row.info?.role === "VIEWER") items.push(READ_ONLY_ACCESSORY);
	return items;
}

export default function Command() {
	const { data, isLoading, notRunning } = useLoader(
		getPlaylists,
		"Failed to load playlists",
	);
	const primary =
		getPreferenceValues<Preferences>().defaultSearchAction ?? "play";

	const infoById = new Map(
		(data?.playlistInfo ?? []).map((info) => [info.playlistId, info]),
	);
	const rows: PlaylistRow[] = (data?.playlists ?? []).map((playlist) => ({
		playlist,
		info: infoById.get(playlist.id),
	}));
	const own = rows.filter((row) => row.info?.role === "OWNER");
	const shared = rows.filter((row) => row.info?.role !== "OWNER");

	const renderRow = (row: PlaylistRow) => (
		<List.Item
			key={row.playlist.id}
			id={row.playlist.id}
			title={row.playlist.name}
			subtitle={playlistSubtitle(row.playlist, row.info)}
			icon={coverIcon(row.playlist.imageId, Icon.AppWindowList)}
			accessories={accessories(row)}
			actions={
				<ActionPanel title={row.playlist.name}>
					<PlaybackActions
						id={row.playlist.id}
						type="playlist"
						primary={primary}
					/>
					<ActionPanel.Section>
						<AddToPlaylistSubmenu id={row.playlist.id} type="playlist" />
					</ActionPanel.Section>
				</ActionPanel>
			}
		/>
	);

	return (
		<List isLoading={isLoading} searchBarPlaceholder="Filter playlists...">
			{notRunning && <NotRunningView />}
			{!notRunning && !isLoading && rows.length === 0 && (
				<List.EmptyView
					title="No playlists"
					description="Create a playlist in Synara to see it here."
					icon={Icon.AppWindowList}
				/>
			)}
			<List.Section title="My Playlists">{own.map(renderRow)}</List.Section>
			<List.Section title="Shared with Me">
				{shared.map(renderRow)}
			</List.Section>
		</List>
	);
}
