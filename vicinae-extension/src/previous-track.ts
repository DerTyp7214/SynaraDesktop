import { previousTrack } from "./lib/dbus";
import { runQuickCommand } from "./lib/quick";

export default function Command() {
	return runQuickCommand("Previous Track failed", previousTrack);
}
