import { closeMainWindow, showHUD } from "@vicinae/api";
import { disconnect } from "./dbus";
import { showFailure } from "./feedback";

export async function runQuickCommand(
	failureTitle: string,
	action: () => Promise<unknown>,
) {
	try {
		const message = await action();
		if (typeof message === "string") {
			await showHUD(message);
		} else {
			await closeMainWindow();
		}
	} catch (error) {
		await showFailure(failureTitle, error);
	} finally {
		disconnect();
	}
}
