import { getCurrentSong, setLikeLevel } from "./lib/dbus";
import { likeLevelOf, songTitle } from "./lib/format";
import { runQuickCommand } from "./lib/quick";

export default function Command() {
	return runQuickCommand("Like failed", async () => {
		const song = await getCurrentSong();
		if (!song) return "Nothing is playing";
		if (likeLevelOf(song) !== "NONE") {
			return `Already liked: ${songTitle(song)}`;
		}
		const updated = await setLikeLevel(song.id, "LIKE");
		if (!updated) throw new Error("Synara did not change the like");
		return `Liked: ${songTitle(song)}`;
	});
}
