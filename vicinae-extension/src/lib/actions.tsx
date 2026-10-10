import {
	Action,
	ActionPanel,
	Icon,
	type Keyboard,
	showToast,
	Toast,
} from "@vicinae/api";
import { type ReactElement, useState } from "react";
import * as synara from "./dbus";
import { showFailure } from "./feedback";
import { likeLabel, likeLevelOf } from "./format";
import {
	type LikeLevel,
	type PlayableType,
	type UserPlaylist,
	type UserSong,
	WRITE_ROLES,
} from "./types";

type PrimaryAction = "play" | "playNext" | "addToQueue";

const PRIMARY_ACTIONS: PrimaryAction[] = ["play", "playNext", "addToQueue"];

export const SHORTCUTS = {
	playNext: { modifiers: ["ctrl", "shift"], key: "n" },
	addToQueue: { modifiers: ["ctrl", "shift"], key: "q" },
	like: { modifiers: ["ctrl", "shift"], key: "l" },
	superLike: { modifiers: ["ctrl", "shift"], key: "s" },
	addToPlaylist: { modifiers: ["ctrl", "shift"], key: "a" },
	showLyrics: { modifiers: ["ctrl", "shift"], key: "y" },
	nextTrack: { modifiers: ["ctrl", "shift"], key: "n" },
	previousTrack: { modifiers: ["ctrl", "shift"], key: "p" },
	seekForward: { modifiers: ["ctrl", "shift"], key: "f" },
	seekBack: { modifiers: ["ctrl", "shift"], key: "b" },
} satisfies Record<string, Keyboard.Shortcut>;

let writablePlaylists: UserPlaylist[] | null = null;

export function PlaybackActions(props: {
	id: string;
	type: PlayableType;
	primary: PrimaryAction;
	play?: { title: string; run: () => Promise<void> };
	onPlayed?: () => void;
}) {
	const { id, type, primary, onPlayed } = props;
	const play = props.play ?? {
		title: "Play",
		run: () => synara.play(id, type),
	};
	const run = (action: Promise<void>, done?: () => void) => {
		action.then(done).catch((error: unknown) => {
			void showFailure("Playback failed", error);
		});
	};

	const actions: Record<PrimaryAction, ReactElement> = {
		play: (
			<Action
				key="play"
				title={play.title}
				icon={Icon.Play}
				onAction={() => run(play.run(), onPlayed)}
			/>
		),
		playNext: (
			<Action
				key="playNext"
				title="Play Next"
				icon={Icon.Forward}
				shortcut={SHORTCUTS.playNext}
				onAction={() => run(synara.playNext(id, type))}
			/>
		),
		addToQueue: (
			<Action
				key="addToQueue"
				title="Add to Queue"
				icon={Icon.Plus}
				shortcut={SHORTCUTS.addToQueue}
				onAction={() => run(synara.addToQueue(id, type))}
			/>
		),
	};

	return (
		<>
			{actions[primary]}
			{PRIMARY_ACTIONS.filter((action) => action !== primary).map(
				(action) => actions[action],
			)}
		</>
	);
}

export function LikeActions(props: {
	song: UserSong;
	onSongChange: (song: UserSong) => void;
}) {
	const { song, onSongChange } = props;
	const level = likeLevelOf(song);
	const setLevel = (target: LikeLevel) => {
		synara
			.setLikeLevel(song.id, target)
			.then((updated) => {
				if (!updated) throw new Error("Synara did not change the like");
				onSongChange(updated);
				return showToast(Toast.Style.Success, likeLabel(likeLevelOf(updated)));
			})
			.catch((error: unknown) => {
				void showFailure("Failed to change the like", error);
			});
	};

	return (
		<>
			{level === "NONE" ? (
				<Action
					title="Like"
					icon={Icon.Heart}
					shortcut={SHORTCUTS.like}
					onAction={() => setLevel("LIKE")}
				/>
			) : (
				<Action
					title="Remove Like"
					icon={Icon.HeartDisabled}
					shortcut={SHORTCUTS.like}
					onAction={() => setLevel("NONE")}
				/>
			)}
			{level === "SUPER" ? (
				<Action
					title="Remove Super Like"
					icon={Icon.StarDisabled}
					shortcut={SHORTCUTS.superLike}
					onAction={() => setLevel("LIKE")}
				/>
			) : (
				<Action
					title="Super Like"
					icon={Icon.Star}
					shortcut={SHORTCUTS.superLike}
					onAction={() => setLevel("SUPER")}
				/>
			)}
		</>
	);
}

export function AddToPlaylistSubmenu(props: {
	id: string;
	type: PlayableType;
}) {
	const { id, type } = props;
	const [playlists, setPlaylists] = useState(writablePlaylists ?? []);

	const load = () => {
		if (writablePlaylists) return;
		synara
			.getPlaylists()
			.then((result) => {
				const writableIds = new Set(
					result.playlistInfo
						.filter((info) => WRITE_ROLES.has(info.role))
						.map((info) => info.playlistId),
				);
				const writable = result.playlists.filter((playlist) =>
					writableIds.has(playlist.id),
				);
				writablePlaylists = writable;
				setPlaylists(writable);
				if (writable.length > 0) return;
				return showToast(
					Toast.Style.Failure,
					"No playlist you can add songs to",
				);
			})
			.catch((error: unknown) => {
				void showFailure("Failed to load playlists", error);
			});
	};

	const add = (playlist: UserPlaylist) => {
		synara
			.addToPlaylist(playlist.id, id, type)
			.then(() => showToast(Toast.Style.Success, `Added to ${playlist.name}`))
			.catch((error: unknown) => {
				void showFailure("Failed to add to the playlist", error);
			});
	};

	return (
		<ActionPanel.Submenu
			title="Add to Playlist"
			icon={Icon.PlusCircle}
			shortcut={SHORTCUTS.addToPlaylist}
			onOpen={load}
		>
			{playlists
				.filter((playlist) => playlist.id !== id)
				.map((playlist) => (
					<Action
						key={playlist.id}
						title={playlist.name}
						icon={synara.imageUrl(playlist.imageId, 64) ?? Icon.AppWindowList}
						onAction={() => add(playlist)}
					/>
				))}
		</ActionPanel.Submenu>
	);
}
