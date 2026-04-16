import {
  Action,
  ActionPanel,
  List,
  showToast,
  Toast,
} from "@raycast/api";
import React, { useEffect, useState } from "react";
import * as synara from "./lib/api";
import type { SynaraQueueItem } from "./lib/types";

export default function Queue() {
  const [items, setItems] = useState<SynaraQueueItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);

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

  return (
    <List isLoading={isLoading} searchBarPlaceholder="Filter queue...">
      {items.map((item) => (
        <List.Item
          key={item.queueId}
          title={item.song.title}
          subtitle={item.song.artists.map((a) => a.name).join(", ")}
          icon={item.song.coverId}
          actions={
            <ActionPanel>
              <Action title="Play Now" onAction={() => playItem(item.queueId)} />
            </ActionPanel>
          }
        />
      ))}
      {items.length === 0 && !isLoading && <List.EmptyView title="Queue is empty" />}
    </List>
  );
}
