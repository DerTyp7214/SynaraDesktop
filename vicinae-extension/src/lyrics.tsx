import { Detail, showToast, Toast } from "@vicinae/api";
import React, { useEffect, useMemo, useState } from "react";
import * as synara from "./lib/dbus";

function cleanLyrics(raw: string): string {
  return raw
    .split("\n")
    .map((line) => line.replace(/\[\d+:\d+(?:[:.]\d+)?\]/g, "").trim())
    .filter((line) => line.length > 0)
    .join("\n\n");
}

export function Lyrics() {
  const [rawLyrics, setRawLyrics] = useState<string>("");
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    void synara
      .getCurrentLyrics()
      .then(setRawLyrics)
      .catch((err) => {
        void showToast({
          style: Toast.Style.Failure,
          title: "Failed to fetch lyrics",
          message: String(err),
        });
      })
      .finally(() => setIsLoading(false));
  }, []);

  const markdown = useMemo(() => {
    if (!rawLyrics) return "*No lyrics found for the current song*";
    return cleanLyrics(rawLyrics);
  }, [rawLyrics]);

  return (
    <Detail
      isLoading={isLoading}
      markdown={markdown}
      navigationTitle="Current Lyrics"
    />
  );
}

export default function Command() {
  return <Lyrics />;
}
