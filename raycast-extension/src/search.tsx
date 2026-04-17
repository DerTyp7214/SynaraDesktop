import {
  Action,
  ActionPanel,
  getPreferenceValues,
  List,
  showToast,
  Toast,
  Icon,
} from "@raycast/api";
import React, { useEffect, useState } from "react";
import * as synara from "./lib/api";
import type { SynaraSearchResult, UserSong, Album, Artist, UserPlaylist } from "./lib/types";
import NowPlaying from "./now-playing";

function formatDuration(ms: number): string {
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

export default function Command() {
  const [searchText, setSearchText] = useState("");
  const [results, setResults] = useState<SynaraSearchResult | null>(null);
  const [nowPlaying, setNowPlaying] = useState<UserSong | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const preferences = getPreferenceValues<{ defaultSearchAction: string }>();

  const refreshNowPlaying = () => {
    void synara.getCurrentSong().then(setNowPlaying).catch(console.error);
  };

  useEffect(() => {
    refreshNowPlaying();
  }, []);

  useEffect(() => {
    if (!searchText) {
      setResults(null);
      return;
    }

    setIsLoading(true);
    void synara
      .search(searchText)
      .then(setResults)
      .catch((err) => {
        void showToast({
          style: Toast.Style.Failure,
          title: "Search failed",
          message: String(err),
        });
      })
      .finally(() => setIsLoading(false));
  }, [searchText]);

  const renderActions = (
    id: string,
    type: "song" | "album" | "artist" | "playlist",
    title: string,
  ) => {
    const play = () => {
      void synara.play(id, type)
        .then(refreshNowPlaying)
        .catch((err) =>
          showToast({
            style: Toast.Style.Failure,
            title: "Playback failed",
            message: String(err),
          }),
        );
    };

    const next = () =>
      void synara.playNext(id, type).catch((err) =>
        showToast({
          style: Toast.Style.Failure,
          title: "Playback failed",
          message: String(err),
        }),
      );
    const queue = () =>
      void synara.addToQueue(id, type).catch((err) =>
        showToast({
          style: Toast.Style.Failure,
          title: "Playback failed",
          message: String(err),
        }),
      );

    const actions = [
      <Action key="play" title="Play" icon={Icon.Play} onAction={play} />,
      <Action key="next" title="Play Next" icon={Icon.ArrowRight} onAction={next} />,
      <Action key="queue" title="Add to Queue" icon={Icon.Plus} onAction={queue} />,
    ];

    const primaryIndex =
      preferences.defaultSearchAction === "play"
        ? 0
        : preferences.defaultSearchAction === "playNext"
          ? 1
          : 2;
    const primary = actions.splice(primaryIndex, 1);

    return (
      <ActionPanel title={title}>
        {primary}
        <ActionPanel.Section title="More Actions">
          {actions}
        </ActionPanel.Section>
      </ActionPanel>
    );
  };

  return (
    <List
      isLoading={isLoading}
      searchText={searchText}
      onSearchTextChange={setSearchText}
      searchBarPlaceholder="Search music..."
      throttle
      isShowingDetail={!!(results || (!searchText && nowPlaying))}
    >
      {!searchText && nowPlaying && (
        <List.Section title="Now Playing">
          <List.Item
            key={nowPlaying.id}
            title={nowPlaying.title}
            subtitle={nowPlaying.artists.map((a) => a.name).join(", ")}
            icon={nowPlaying.coverId}
            accessories={[{ text: "Currently playing" }]}
            detail={
              <List.Item.Detail
                markdown={`![Cover](${nowPlaying.coverId?.replace("size=128", "size=512")})\n\n# ${nowPlaying.title}\n\n**Artist:** ${nowPlaying.artists.map((a) => a.name).join(", ")}\n\n**Album:** ${nowPlaying.album?.name ?? "Unknown"}`}
                metadata={
                  <List.Item.Detail.Metadata>
                    <List.Item.Detail.Metadata.Label
                      title="Title"
                      text={nowPlaying.title}
                    />
                    <List.Item.Detail.Metadata.Label
                      title="Artist"
                      text={nowPlaying.artists.map((a) => a.name).join(", ")}
                    />
                    <List.Item.Detail.Metadata.Label
                      title="Album"
                      text={nowPlaying.album?.name ?? "-"}
                    />
                  </List.Item.Detail.Metadata>
                }
              />
            }
            actions={
              <ActionPanel>
                <Action.Push
                  title="Show Now Playing"
                  icon={Icon.Music}
                  target={<NowPlaying />}
                />
              </ActionPanel>
            }
          />
        </List.Section>
      )}
      <List.Section title="Songs">
        {results?.songs.map((song) => (
          <List.Item
            key={song.id}
            title={song.title}
            subtitle={song.artists.map((a) => a.name).join(", ")}
            icon={song.coverId || Icon.Music}
            detail={
              <List.Item.Detail
                markdown={`![Cover](${song.coverId?.replace("size=128", "size=512")})\n\n# ${song.title}\n\n**Artist:** ${song.artists.map((a) => a.name).join(", ")}\n\n**Album:** ${song.album?.name ?? "Unknown"}`}
                metadata={
                  <List.Item.Detail.Metadata>
                    <List.Item.Detail.Metadata.Label title="Title" text={song.title} icon={Icon.Music} />
                    <List.Item.Detail.Metadata.Label title="Artist" text={song.artists.map((a) => a.name).join(", ")} icon={Icon.Person} />
                    <List.Item.Detail.Metadata.Label title="Album" text={song.album?.name ?? "-"} icon={Icon.Album} />
                    <List.Item.Detail.Metadata.Label title="Duration" text={formatDuration(song.duration)} icon={Icon.Clock} />
                  </List.Item.Detail.Metadata>
                }
              />
            }
            actions={renderActions(song.id, "song", song.title)}
          />
        ))}
      </List.Section>
      <List.Section title="Albums">
        {results?.albums.map((album) => (
          <List.Item
            key={album.id}
            title={album.name}
            subtitle={album.artists.map((a) => a.name).join(", ")}
            icon={album.coverId || Icon.Album}
            detail={
              <List.Item.Detail
                markdown={`![Cover](${album.coverId?.replace("size=128", "size=512")})\n\n# ${album.name}\n\n**Artist:** ${album.artists.map((a) => a.name).join(", ")}`}
                metadata={
                  <List.Item.Detail.Metadata>
                    <List.Item.Detail.Metadata.Label title="Title" text={album.name} icon={Icon.Album} />
                    <List.Item.Detail.Metadata.Label title="Artist" text={album.artists.map((a) => a.name).join(", ")} icon={Icon.Person} />
                  </List.Item.Detail.Metadata>
                }
              />
            }
            actions={renderActions(album.id, "album", album.name)}
          />
        ))}
      </List.Section>
      <List.Section title="Artists">
        {results?.artists.map((artist) => (
          <List.Item
            key={artist.id}
            title={artist.name}
            icon={artist.imageId || Icon.Person}
            detail={
              <List.Item.Detail
                markdown={`![Artist](${artist.imageId?.replace("size=128", "size=512")})\n\n# ${artist.name}`}
                metadata={
                  <List.Item.Detail.Metadata>
                    <List.Item.Detail.Metadata.Label title="Name" text={artist.name} icon={Icon.Person} />
                  </List.Item.Detail.Metadata>
                }
              />
            }
            actions={renderActions(artist.id, "artist", artist.name)}
          />
        ))}
      </List.Section>
      <List.Section title="Playlists">
        {results?.playlists.map((playlist) => (
          <List.Item
            key={playlist.id}
            title={playlist.name}
            icon={playlist.imageId || Icon.List}
            detail={
              <List.Item.Detail
                markdown={`![Playlist](${playlist.imageId?.replace("size=128", "size=512")})\n\n# ${playlist.name}`}
                metadata={
                  <List.Item.Detail.Metadata>
                    <List.Item.Detail.Metadata.Label title="Name" text={playlist.name} icon={Icon.List} />
                  </List.Item.Detail.Metadata>
                }
              />
            }
            actions={renderActions(playlist.id, "playlist", playlist.name)}
          />
        ))}
      </List.Section>
    </List>
  );
}
