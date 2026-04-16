import {
  Action,
  ActionPanel,
  getPreferenceValues,
  List,
  showToast,
  Toast,
} from "@vicinae/api";
import React, { useEffect, useState } from "react";
import * as synara from "./lib/dbus";
import type { SynaraQueueItem } from "./lib/types";

interface Preferences {
  defaultQueueAction: "play" | "playNext" | "addToQueue";
}

export default function Command() {
  const [items, setItems] = useState<SynaraQueueItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const { defaultQueueAction } = getPreferenceValues<Preferences.Queue>();

  const fetchQueue = () => {
    setIsLoading(true);
    void synara
      .getQueue(20)
      .then(setItems)
      .catch((err) => {
        void showToast({
          style: Toast.Style.Failure,
          title: "Failed to fetch queue",
          message: String(err),
        });
      })
      .finally(() => setIsLoading(false));
  };

  useEffect(() => {
    fetchQueue();
  }, []);

  const renderActions = (item: SynaraQueueItem) => {
    const playAtPosition = () => {
      void synara
        .playQueueItem(item.queueId)
        .then(fetchQueue)
        .catch((err) =>
          showToast({
            style: Toast.Style.Failure,
            title: "Playback failed",
            message: String(err),
          }),
        );
    };

    const playNext = () =>
      void synara.playNext(item.song.id, "song").catch((err) =>
        showToast({
          style: Toast.Style.Failure,
          title: "Action failed",
          message: String(err),
        }),
      );

    const addToQueue = () =>
      void synara.addToQueue(item.song.id, "song").catch((err) =>
        showToast({
          style: Toast.Style.Failure,
          title: "Action failed",
          message: String(err),
        }),
      );

    const actions = [
      <Action key="play" title="Play at Position" onAction={playAtPosition} />,
      <Action key="next" title="Play Next" onAction={playNext} />,
      <Action key="queue" title="Add to Queue" onAction={addToQueue} />,
    ];

    // Primary action based on preference
    const primaryIndex =
      defaultQueueAction === "play"
        ? 0
        : defaultQueueAction === "playNext"
          ? 1
          : 2;
    const primary = actions.splice(primaryIndex, 1);

    return (
      <ActionPanel title={item.song.title}>
        {primary}
        <ActionPanel.Section title="More Actions">
          {actions}
        </ActionPanel.Section>
        <ActionPanel.Section title="Queue Controls">
          <Action
            title="Refresh Queue"
            onAction={fetchQueue}
            shortcut={{ modifiers: ["cmd"], key: "r" }}
          />
        </ActionPanel.Section>
      </ActionPanel>
    );
  };

  return (
    <List
      isLoading={isLoading}
      searchBarPlaceholder="Filter queue..."
      isShowingDetail={items.length > 0}
    >
      {items.map((item) => (
        <List.Item
          key={item.queueId}
          title={item.song.title}
          subtitle={item.song.artists.map((a) => a.name).join(", ")}
          icon={item.song.coverId}
          detail={
            <List.Item.Detail
              markdown={`![Cover](${item.song.coverId?.replace(
                "size=128",
                "size=512",
              )})\n\n# ${item.song.title}\n\n**Artist:** ${item.song.artists
                .map((a) => a.name)
                .join(", ")}\n\n**Album:** ${item.song.album?.name ?? "Unknown"}`}
              metadata={
                <List.Item.Detail.Metadata>
                  <List.Item.Detail.Metadata.Label
                    title="Title"
                    text={item.song.title}
                  />
                  <List.Item.Detail.Metadata.Label
                    title="Artist"
                    text={item.song.artists.map((a) => a.name).join(", ")}
                  />
                  <List.Item.Detail.Metadata.Label
                    title="Album"
                    text={item.song.album?.name ?? "-"}
                  />
                </List.Item.Detail.Metadata>
              }
            />
          }
          actions={renderActions(item)}
        />
      ))}
    </List>
  );
}
