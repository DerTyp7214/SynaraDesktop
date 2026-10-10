import { useCallback, useEffect, useState } from "react";
import { SynaraNotRunningError } from "./dbus";
import { showFailure } from "./feedback";

export function useLoader<T>(load: () => Promise<T>, failureTitle: string) {
	const [data, setData] = useState<T | null>(null);
	const [isLoading, setIsLoading] = useState(true);
	const [notRunning, setNotRunning] = useState(false);

	const reload = useCallback(() => {
		setIsLoading(true);
		load()
			.then((value) => {
				setData(value);
				setNotRunning(false);
			})
			.catch((error: unknown) => {
				if (error instanceof SynaraNotRunningError) {
					setNotRunning(true);
				} else {
					void showFailure(failureTitle, error);
				}
			})
			.finally(() => setIsLoading(false));
	}, [load, failureTitle]);

	useEffect(reload, [reload]);

	return { data, setData, isLoading, notRunning, reload };
}
