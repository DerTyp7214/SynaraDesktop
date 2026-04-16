import {
  Action,
  ActionPanel,
  getPreferenceValues,
  LaunchProps,
  List,
  showToast,
  Toast,
} from "@vicinae/api";
import React, { useEffect, useState } from "react";
import * as synara from "./lib/dbus";
import type { SynaraSearchResult, UserSong } from "./lib/types";
import { Lyrics } from "./lyrics";

export default function Command(props: LaunchProps<{ arguments: Arguments.Search }>) {
  const [searchText, setSearchText] = useState(props.arguments.query || "");
  const [results, setResults] = useState<SynaraSearchResult | null>(null);
  const [nowPlaying, setNowPlaying] = useState<UserSong | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const { defaultSearchAction } = getPreferenceValues<Preferences.Search>();

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
      const fn =
        type === "song"
          ? synara.playSong
          : type === "album"
            ? synara.playAlbum
            : type === "artist"
              ? synara.playArtist
              : synara.playPlaylist;
      void fn(id)
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
      <Action key="play" title="Play" onAction={play} />,
      <Action key="next" title="Play Next" onAction={next} />,
      <Action key="queue" title="Add to Queue" onAction={queue} />,
    ];

    // Reorder based on preference
    const primaryIndex =
      defaultSearchAction === "play"
        ? 0
        : defaultSearchAction === "playNext"
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
                <Action.Push title="Show Lyrics" target={<Lyrics />} />
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
            icon={song.coverId}
            detail={
              <List.Item.Detail
                markdown={`![Cover](${song.coverId?.replace("size=128", "size=512")})\n\n# ${song.title}`}
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
            icon={album.coverId}
            detail={
              <List.Item.Detail
                markdown={`![Cover](${album.coverId?.replace("size=128", "size=512")})\n\n# ${album.name}`}
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
            icon={artist.imageId}
            detail={
              <List.Item.Detail
                markdown={`![Artist](${artist.imageId?.replace("size=128", "size=512")})\n\n# ${artist.name}`}
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
            icon={playlist.imageId}
            detail={
              <List.Item.Detail
                markdown={`![Playlist](${playlist.imageId?.replace("size=128", "size=512")})\n\n# ${playlist.name}`}
              />
            }
            actions={renderActions(playlist.id, "playlist", playlist.name)}
          />
        ))}
      </List.Section>
    </List>
  );
}
