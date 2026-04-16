import { getPreferenceValues } from "@raycast/api";
import fetch from "node-fetch";
import {
  AllowedImageSizes,
  type PlaybackState,
  type ServerInfo,
  type SynaraQueueItem,
  type SynaraSearchResult,
  type UserSong,
} from "./types";

const PORTS = [10767, 10768, 10769];
let activePort: number | null = null;

async function getBaseUrl(): Promise<string> {
  if (activePort) return `http://localhost:${activePort}`;

  for (const port of PORTS) {
    try {
      const res = await fetch(`http://localhost:${port}/ping`, { timeout: 1000 });
      if (res.ok) {
        const data = (await res.json()) as any;
        if (data.app === "synara") {
          activePort = port;
          return `http://localhost:${port}`;
        }
      }
    } catch (e) {
      // ignore
    }
  }
  throw new Error("Synara is not running or API server not found.");
}

async function apiGet<T>(path: string, params?: Record<string, string | boolean | number>): Promise<T> {
  const baseUrl = await getBaseUrl();
  const url = new URL(`${baseUrl}${path}`);
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      url.searchParams.append(key, String(value));
    });
  }

  const res = await fetch(url.toString());
  if (!res.ok) throw new Error(`API Error: ${res.statusText}`);
  if (res.status === 204) return null as T;
  return (await res.json()) as T;
}

async function apiPost(path: string, params?: Record<string, string | boolean | number>): Promise<void> {
  const baseUrl = await getBaseUrl();
  const url = new URL(`${baseUrl}${path}`);
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      url.searchParams.append(key, String(value));
    });
  }

  const res = await fetch(url.toString(), { method: "POST" });
  if (!res.ok) throw new Error(`API Error: ${res.statusText}`);
}

export async function getServerInfo(): Promise<ServerInfo> {
  return await apiGet<ServerInfo>("/api/info");
}

let serverInfo: ServerInfo | null = null;
export async function getImageUrl(
  imageId: string | undefined,
  size = 128,
): Promise<string | undefined> {
  if (!imageId) return undefined;
  if (!serverInfo) {
    serverInfo = await getServerInfo();
  }

  const targetSize =
    AllowedImageSizes.find((s) => s >= size) ||
    AllowedImageSizes[AllowedImageSizes.length - 1];

  return `http://${serverInfo.host}:${serverInfo.port}/image/imageData/${imageId}?size=${targetSize}`;
}

export async function search(query: string): Promise<SynaraSearchResult> {
  const data = await apiGet<SynaraSearchResult>("/api/search", { q: query });

  return {
    songs: await Promise.all(
      data.songs.map(async (s) => ({
        ...s,
        coverId: await getImageUrl(s.coverId, 128),
      })),
    ),
    albums: await Promise.all(
      data.albums.map(async (a) => ({
        ...a,
        coverId: await getImageUrl(a.coverId, 128),
      })),
    ),
    artists: await Promise.all(
      data.artists.map(async (a) => ({
        ...a,
        imageId: await getImageUrl(a.imageId, 128),
      })),
    ),
    playlists: await Promise.all(
      data.playlists.map(async (p) => ({
        ...p,
        imageId: await getImageUrl(p.imageId, 128),
      })),
    ),
  };
}

export async function getCurrentSong(): Promise<UserSong | null> {
  const song = await apiGet<UserSong | null>("/api/now-playing");
  if (!song) return null;
  song.coverId = await getImageUrl(song.coverId, 128);
  return song;
}

export async function getCurrentLyrics(): Promise<string> {
  const data = await apiGet<{ lyrics: string }>("/api/lyrics");
  return data.lyrics;
}

export async function getQueue(limit: number): Promise<SynaraQueueItem[]> {
  const items = await apiGet<SynaraQueueItem[]>("/api/queue", { limit });
  return await Promise.all(
    items.map(async (item) => ({
      ...item,
      song: {
        ...item.song,
        coverId: await getImageUrl(item.song.coverId, 128),
      },
    })),
  );
}

export async function playQueueItem(queueId: string) {
  await apiPost("/api/action/play-queue-item", { queueId });
}

export async function play(id: string, type: string) {
  await apiPost("/api/action/play", { id, type });
}

export async function playNext(id: string, type: string) {
  await apiPost("/api/action/play-next", { id, type });
}

export async function addToQueue(id: string, type: string) {
  await apiPost("/api/action/add-to-queue", { id, type });
}

export async function getPlaybackState(): Promise<PlaybackState> {
  return await apiGet<PlaybackState>("/api/playback");
}
