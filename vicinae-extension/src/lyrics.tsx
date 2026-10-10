import { Detail } from "@vicinae/api";
import { getCurrentLyrics } from "./lib/dbus";
import { NOT_RUNNING_MARKDOWN } from "./lib/feedback";
import { useLoader } from "./lib/hooks";

function cleanLyrics(raw: string): string {
	return raw
		.split("\n")
		.map((line) => line.replace(/\[\d+:\d+(?:[:.]\d+)?\]/g, "").trim())
		.filter((line) => line.length > 0)
		.join("\n\n");
}

function lyricsMarkdown(lyrics: string | null, notRunning: boolean): string {
	if (notRunning) return NOT_RUNNING_MARKDOWN;
	if (lyrics === null) return "";
	return cleanLyrics(lyrics) || "*No lyrics found for the current song*";
}

export function Lyrics() {
	const { data, notRunning } = useLoader(
		getCurrentLyrics,
		"Failed to load the lyrics",
	);

	return (
		<Detail
			markdown={lyricsMarkdown(data, notRunning)}
			navigationTitle="Current Lyrics"
		/>
	);
}

export default function Command() {
	return <Lyrics />;
}
