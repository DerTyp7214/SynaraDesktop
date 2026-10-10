import { Action, ActionPanel, Detail, Icon } from "@vicinae/api";
import { useCallback, useEffect, useState } from "react";
import { AddToPlaylistSubmenu, LikeActions, SHORTCUTS } from "./lib/actions";
import * as synara from "./lib/dbus";
import { NOT_RUNNING_MARKDOWN, showFailure } from "./lib/feedback";
import { artistLine, formatDuration, songTitle } from "./lib/format";
import { coverMarkdown, SongMetadata } from "./lib/rows";
import type {
	PlaybackStatus,
	SynaraPlaybackState,
	UserSong,
} from "./lib/types";
import { Lyrics } from "./lyrics";

const SEEK_STEP_MS = 10_000;
const POSITION_POLL_MS = 1000;

const REFRESH_PROPERTIES: ReadonlySet<string> = new Set([
	"PlaybackStatus",
	"Metadata",
]);

const STATUS_ICONS: Record<PlaybackStatus, Icon> = {
	Playing: Icon.PlayFilled,
	Paused: Icon.PauseFilled,
	Stopped: Icon.Stop,
};

interface NowPlaying {
	song: UserSong | null;
	status: PlaybackStatus;
}

function samePlayback(
	left: SynaraPlaybackState | null,
	right: SynaraPlaybackState,
): boolean {
	return (
		left !== null &&
		left.isPlaying === right.isPlaying &&
		left.position === right.position &&
		left.duration === right.duration
	);
}

export default function Command() {
	const [nowPlaying, setNowPlaying] = useState<NowPlaying | null>(null);
	const [playback, setPlayback] = useState<SynaraPlaybackState | null>(null);
	const [notRunning, setNotRunning] = useState(false);

	const report = useCallback((title: string, error: unknown) => {
		if (error instanceof synara.SynaraNotRunningError) {
			setNotRunning(true);
		} else {
			void showFailure(title, error);
		}
	}, []);

	const refreshPosition = useCallback(async () => {
		const next = await synara.getPlaybackState();
		setPlayback((current) => (samePlayback(current, next) ? current : next));
	}, []);

	const refresh = useCallback(async () => {
		const [song, status] = await Promise.all([
			synara.getCurrentSong(),
			synara.getPlaybackStatus(),
			refreshPosition(),
		]);
		setNowPlaying({ song, status });
		setNotRunning(false);
	}, [refreshPosition]);

	useEffect(() => {
		let active = true;
		let unsubscribe: (() => void) | undefined;
		const reportRefresh = (error: unknown) =>
			report("Failed to load the current song", error);

		const start = async () => {
			await refresh();
			const stop = await synara.subscribePlayerChanges((changed) => {
				if (changed.some((property) => REFRESH_PROPERTIES.has(property))) {
					refresh().catch(reportRefresh);
				}
			});
			if (active) {
				unsubscribe = stop;
			} else {
				stop();
			}
		};
		start().catch(reportRefresh);

		const timer = setInterval(() => {
			refreshPosition().catch((error: unknown) => {
				if (error instanceof synara.SynaraNotRunningError) {
					setNotRunning(true);
					clearInterval(timer);
				}
			});
		}, POSITION_POLL_MS);

		return () => {
			active = false;
			unsubscribe?.();
			clearInterval(timer);
		};
	}, [refresh, refreshPosition, report]);

	const run = (title: string, action: Promise<void>) => {
		action.then(refresh).catch((error: unknown) => report(title, error));
	};

	if (notRunning) {
		return <Detail markdown={NOT_RUNNING_MARKDOWN} />;
	}
	if (!nowPlaying) {
		return <Detail markdown="" />;
	}

	const { song, status } = nowPlaying;
	if (!song) {
		return (
			<Detail markdown={"# Nothing is playing\n\nStart a song in Synara."} />
		);
	}

	const title = songTitle(song);

	return (
		<Detail
			navigationTitle={title}
			markdown={`${coverMarkdown(song.coverId, title)}\n\n${artistLine(song.artists)}`}
			metadata={
				<SongMetadata song={song}>
					<Detail.Metadata.Label
						title="State"
						text={status}
						icon={STATUS_ICONS[status]}
					/>
					{playback && (
						<Detail.Metadata.Label
							title="Position"
							text={`${formatDuration(playback.position)} / ${formatDuration(playback.duration)}`}
						/>
					)}
					<Detail.Metadata.Separator />
				</SongMetadata>
			}
			actions={
				<ActionPanel title={title}>
					<Action
						title={status === "Playing" ? "Pause" : "Play"}
						icon={status === "Playing" ? Icon.Pause : Icon.Play}
						onAction={() => run("Playback failed", synara.playPause())}
					/>
					<ActionPanel.Section title="Playback">
						<Action
							title="Next Track"
							icon={Icon.Forward}
							shortcut={SHORTCUTS.nextTrack}
							onAction={() => run("Playback failed", synara.nextTrack())}
						/>
						<Action
							title="Previous Track"
							icon={Icon.Rewind}
							shortcut={SHORTCUTS.previousTrack}
							onAction={() => run("Playback failed", synara.previousTrack())}
						/>
						<Action
							title="Seek Forward 10 Seconds"
							icon={Icon.ForwardFilled}
							shortcut={SHORTCUTS.seekForward}
							onAction={() => run("Seek failed", synara.seekBy(SEEK_STEP_MS))}
						/>
						<Action
							title="Seek Back 10 Seconds"
							icon={Icon.RewindFilled}
							shortcut={SHORTCUTS.seekBack}
							onAction={() => run("Seek failed", synara.seekBy(-SEEK_STEP_MS))}
						/>
					</ActionPanel.Section>
					<ActionPanel.Section title="Song">
						<LikeActions
							song={song}
							onSongChange={(updated) =>
								setNowPlaying(
									(current) => current && { ...current, song: updated },
								)
							}
						/>
						<AddToPlaylistSubmenu id={song.id} type="song" />
						<Action.Push
							title="Show Lyrics"
							icon={Icon.Paragraph}
							shortcut={SHORTCUTS.showLyrics}
							target={<Lyrics />}
						/>
					</ActionPanel.Section>
				</ActionPanel>
			}
		/>
	);
}
