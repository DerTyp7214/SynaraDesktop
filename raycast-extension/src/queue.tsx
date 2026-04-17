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
import type { SynaraQueueItem } from "./lib/types";

function formatDuration(ms: number): string {
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

export default function Queue() {
  const [items, setItems] = useState<SynaraQueueItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const preferences = getPreferenceValues<{ defaultQueueAction: string }>();

  const refresh = () => {
    setIsLoading(true);
    synara.getQueue(20)
      .then(setItems)
      .finally(() => setIsLoading(false));
  };

  useEffect(() => {
    refresh();
  }, []);

  const playItem = (queueId: string) => {
    void synara.playQueueItem(queueId)
      .then(refresh)
      .catch((err) => showToast({ style: Toast.Style.Failure, title: "Failed to play item", message: String(err) }));
  };

  const playNext = (id: string) => {
    void synara.playNext(id, "song")
      .then(refresh)
      .catch((err) => showToast({ style: Toast.Style.Failure, title: "Failed to play next", message: String(err) }));
  };

  const addToQueue = (id: string) => {
    void synara.addToQueue(id, "song")
      .then(refresh)
      .catch((err) => showToast({ style: Toast.Style.Failure, title: "Failed to add to queue", message: String(err) }));
  };

  return (
    <List isLoading={isLoading} searchBarPlaceholder="Filter queue..." isShowingDetail={items.length > 0}>
      {items.map((item) => {
        const actions = [
          <Action key="play" title="Play Now" icon={Icon.Play} onAction={() => playItem(item.queueId)} />,
          <Action key="next" title="Play Next" icon={Icon.ArrowRight} onAction={() => playNext(item.song.id)} />,
          <Action key="queue" title="Add to Queue" icon={Icon.Plus} onAction={() => addToQueue(item.song.id)} />,
        ];

        const primaryIndex =
          preferences.defaultQueueAction === "play"
            ? 0
            : preferences.defaultQueueAction === "playNext"
              ? 1
              : 2;
        const primary = actions.splice(primaryIndex, 1);

        return (
          <List.Item
            key={item.queueId}
            title={item.song.title}
            subtitle={item.song.artists.map((a) => a.name).join(", ")}
            icon={item.song.coverId || Icon.Music}
            detail={
              <List.Item.Detail
                markdown={`![Cover](${item.song.coverId?.replace("size=128", "size=512")})\n\n# ${item.song.title}\n\n**Artist:** ${item.song.artists.map((a) => a.name).join(", ")}\n\n**Album:** ${item.song.album?.name ?? "Unknown"}`}
                metadata={
                  <List.Item.Detail.Metadata>
                    <List.Item.Detail.Metadata.Label title="Title" text={item.song.title} icon={Icon.Music} />
                    <List.Item.Detail.Metadata.Label title="Artist" text={item.song.artists.map((a) => a.name).join(", ")} icon={Icon.Person} />
                    <List.Item.Detail.Metadata.Label title="Album" text={item.song.album?.name ?? "-"} icon={Icon.Album} />
                    <List.Item.Detail.Metadata.Label title="Duration" text={formatDuration(item.song.duration)} icon={Icon.Clock} />
                  </List.Item.Detail.Metadata>
                }
              />
            }
            actions={
              <ActionPanel>
                {primary}
                <ActionPanel.Section title="More Actions">
                  {actions}
                </ActionPanel.Section>
                <ActionPanel.Section title="Queue Controls">
                  <Action
                    title="Refresh Queue"
                    icon={Icon.ArrowClockwise}
                    onAction={refresh}
                    shortcut={{ modifiers: ["cmd"], key: "r" }}
                  />
                </ActionPanel.Section>
              </ActionPanel>
            }
          />
        );
      })}
      {items.length === 0 && !isLoading && <List.EmptyView title="Queue is empty" />}
    </List>
  );
}
