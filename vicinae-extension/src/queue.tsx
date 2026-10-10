import {
	Action,
	ActionPanel,
	getPreferenceValues,
	Icon,
	Keyboard,
	List,
} from "@vicinae/api";
import {
	AddToPlaylistSubmenu,
	LikeActions,
	PlaybackActions,
} from "./lib/actions";
import * as synara from "./lib/dbus";
import { NotRunningView } from "./lib/feedback";
import { artistLine, songTitle } from "./lib/format";
import { useLoader } from "./lib/hooks";
import { coverIcon, SongDetail, songAccessories } from "./lib/rows";
import type { UserSong } from "./lib/types";

const QUEUE_LIMIT = 20;

function loadQueue() {
	return synara.getQueue(QUEUE_LIMIT);
}

export default function Command() {
	const { data, setData, isLoading, notRunning, reload } = useLoader(
		loadQueue,
		"Failed to load the queue",
	);
	const items = data ?? [];
	const primary =
		getPreferenceValues<Preferences>().defaultQueueAction ?? "play";

	const updateSong = (updated: UserSong) => {
		setData(
			(current) =>
				current?.map((item) =>
					item.song.id === updated.id ? { ...item, song: updated } : item,
				) ?? null,
		);
	};

	return (
		<List
			isLoading={isLoading}
			searchBarPlaceholder="Filter queue..."
			isShowingDetail={items.length > 0}
		>
			{notRunning && <NotRunningView />}
			{!notRunning && !isLoading && items.length === 0 && (
				<List.EmptyView
					title="The queue is empty"
					description="Nothing is queued after the current song."
					icon={Icon.Music}
				/>
			)}
			{items.map((item) => (
				<List.Item
					key={item.queueId}
					id={item.queueId}
					title={songTitle(item.song)}
					subtitle={artistLine(item.song.artists)}
					icon={coverIcon(item.song.coverId, Icon.Music)}
					accessories={songAccessories(item.song)}
					detail={<SongDetail song={item.song} />}
					actions={
						<ActionPanel title={songTitle(item.song)}>
							<PlaybackActions
								id={item.song.id}
								type="song"
								primary={primary}
								play={{
									title: "Play at Position",
									run: () => synara.playQueueItem(item.queueId),
								}}
								onPlayed={reload}
							/>
							<ActionPanel.Section title="Song">
								<LikeActions song={item.song} onSongChange={updateSong} />
								<AddToPlaylistSubmenu id={item.song.id} type="song" />
							</ActionPanel.Section>
							<ActionPanel.Section title="Queue">
								<Action
									title="Refresh Queue"
									icon={Icon.ArrowClockwise}
									shortcut={Keyboard.Shortcut.Common.Refresh}
									onAction={reload}
								/>
							</ActionPanel.Section>
						</ActionPanel>
					}
				/>
			))}
		</List>
	);
}
