import { Detail } from "@raycast/api";
import React, { useEffect, useState } from "react";
import * as synara from "./lib/api";
import type { UserSong } from "./lib/types";

export default function NowPlaying() {
  const [song, setSong] = useState<UserSong | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    synara.getCurrentSong()
      .then(setSong)
      .finally(() => setIsLoading(false));
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
${song.lyrics || "*No lyrics available*"}
  `;

  return (
    <Detail
      markdown={markdown}
      metadata={
        <Detail.Metadata>
          <Detail.Metadata.Label title="Title" text={song.title} />
          <Detail.Metadata.Label title="Artist" text={song.artists.map((a) => a.name).join(", ")} />
          <Detail.Metadata.Label title="Album" text={song.album?.name ?? "-"} />
        </Detail.Metadata>
      }
    />
  );
}
