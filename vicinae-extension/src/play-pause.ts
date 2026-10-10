import { playPause } from "./lib/dbus";
import { runQuickCommand } from "./lib/quick";

export default function Command() {
	return runQuickCommand("Play / Pause failed", playPause);
}
