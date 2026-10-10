import { nextTrack } from "./lib/dbus";
import { runQuickCommand } from "./lib/quick";

export default function Command() {
	return runQuickCommand("Next Track failed", nextTrack);
}
