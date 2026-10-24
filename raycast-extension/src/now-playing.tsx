import { Detail, Icon } from "@raycast/api";
import React, { useEffect, useState } from "react";
import * as synara from "./lib/api";
import type { UserSong } from "./lib/types";

export default function NowPlaying() {
  const [song, setSong] = useState<UserSong | null>(null);
  const [lyrics, setLyrics] = useState("");
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      synara.getCurrentSong().then(setSong),
      synara.getCurrentLyrics().then(setLyrics).catch(() => setLyrics("")),
    ]).finally(() => setIsLoading(false));
  }, []);

  if (isLoading) return <Detail isLoading />;
  if (!song) return <Detail markdown="# No music playing" />;

  const markdown = `
![Cover](${song.coverId?.replace("size=128", "size=512")})

# ${song.title}
**Artist:** ${song.artists.map((a) => a.name).join(", ")}
**Album:** ${song.album?.name ?? "-"}

---

## Lyrics
${lyrics || "*No lyrics available*"}
  `;

  return (
    <Detail
      markdown={markdown}
      metadata={
        <Detail.Metadata>
          <Detail.Metadata.Label title="Title" text={song.title} icon={Icon.Music} />
          <Detail.Metadata.Label title="Artist" text={song.artists.map((a) => a.name).join(", ")} icon={Icon.Person} />
          <Detail.Metadata.Label title="Album" text={song.album?.name ?? "-"} icon={Icon.Album} />
        </Detail.Metadata>
      }
    />
  );
}
