import { getPreferenceValues } from "@vicinae/api";
import * as dbus from "dbus-next";
import {
  AllowedImageSizes,
  type SearchFilter,
  type ServerInfo,
  type SynaraQueueItem,
  type SynaraSearchResult,
  type UserSong,
} from "./types";

interface Preferences {
  devMode: boolean;
}

const OBJ_PATH = "/dev/dertyp/synara";
const INTERFACE_NAME = "dev.dertyp.synara.Api";

let apiInterface: any = null;

async function getApi() {
  if (apiInterface) return apiInterface;

  const { devMode } = getPreferenceValues<Preferences>();
  const busName = `org.mpris.MediaPlayer2.synara${devMode ? "-dev" : ""}`;

  const bus = dbus.sessionBus();
  const obj = await bus.getProxyObject(busName, OBJ_PATH);
  apiInterface = obj.getInterface(INTERFACE_NAME);
  return apiInterface;
}

export async function getServerInfo(): Promise<ServerInfo> {
  const api = await getApi();
  const result = await api.GetServerInfo();
  return JSON.parse(result);
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

export async function search(
  query: string,
  filters: Partial<Record<SearchFilter, boolean>> = {},
): Promise<SynaraSearchResult> {
  const api = await getApi();
  const dbusFilters: Record<string, dbus.Variant> = {};

  for (const [key, value] of Object.entries(filters)) {
    dbusFilters[key] = new dbus.Variant("b", value);
  }

  const result = await api.Search(query, dbusFilters);
  const data = JSON.parse(result) as SynaraSearchResult;

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
  const api = await getApi();
  const result = await api.GetCurrentSong();
  if (!result) return null;

  const song = JSON.parse(result) as UserSong;
  song.coverId = await getImageUrl(song.coverId, 128);
  return song;
}

export async function getCurrentLyrics(): Promise<string> {
  const api = await getApi();
  return await api.GetCurrentLyrics();
}

export async function getQueue(limit: number): Promise<SynaraQueueItem[]> {
  const api = await getApi();
  const result = await api.GetQueue(limit);
  const items = JSON.parse(result) as SynaraQueueItem[];

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
  const api = await getApi();
  await api.PlayQueueItem(queueId);
}

export async function playSong(id: string) {
  const api = await getApi();
  await api.PlaySong(id);
}

export async function playAlbum(id: string) {
  const api = await getApi();
  await api.PlayAlbum(id);
}

export async function playArtist(id: string) {
  const api = await getApi();
  await api.PlayArtist(id);
}

export async function playPlaylist(id: string) {
  const api = await getApi();
  await api.PlayPlaylist(id);
}

export async function playNext(
  id: string,
  type: "song" | "album" | "artist" | "playlist",
) {
  const api = await getApi();
  await api.PlayNext(id, type);
}

export async function addToQueue(
  id: string,
  type: "song" | "album" | "artist" | "playlist",
) {
  const api = await getApi();
  await api.AddToQueue(id, type);
}
