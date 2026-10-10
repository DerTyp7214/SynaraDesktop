import { Icon, List, showToast, Toast } from "@vicinae/api";
import { SynaraNotRunningError } from "./dbus";

const NOT_RUNNING_TITLE = "Synara is not running";
const NOT_RUNNING_HINT = "Start Synara and open this command again.";

export const NOT_RUNNING_MARKDOWN = `# ${NOT_RUNNING_TITLE}\n\n${NOT_RUNNING_HINT}`;

export function showFailure(title: string, error: unknown): Promise<Toast> {
	if (error instanceof SynaraNotRunningError) {
		return showToast(Toast.Style.Failure, NOT_RUNNING_TITLE);
	}
	return showToast(
		Toast.Style.Failure,
		title,
		error instanceof Error ? error.message : String(error),
	);
}

export function NotRunningView() {
	return (
		<List.EmptyView
			title={NOT_RUNNING_TITLE}
			description={NOT_RUNNING_HINT}
			icon={Icon.Plug}
		/>
	);
}
