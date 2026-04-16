export interface Artist {
  id: string;
  name: string;
  isGroup: boolean;
  imageId?: string;
}

export interface Album {
  id: string;
  name: string;
  artists: Artist[];
  coverId?: string;
  releaseDate?: string;
  songCount: number;
}

export interface UserSong {
  id: string;
  title: string;
  artists: Artist[];
  album?: Album;
  duration: number;
  coverId?: string;
  lyrics: string;
}

export interface UserPlaylist {
  id: string;
  name: string;
  imageId?: string;
  description: string;
}

export interface ServerInfo {
  host: string;
  port: number;
}

export interface SynaraSearchResult {
  songs: UserSong[];
  albums: Album[];
  artists: Artist[];
  playlists: UserPlaylist[];
}

export interface SynaraQueueItem {
  queueId: string; // Long comes as string in JSON usually, or number. D-Bus Long is often number or bigint.
  song: UserSong;
}

export enum SearchFilter {
  includeSongs = "includeSongs",
  includeAlbums = "includeAlbums",
  includeArtists = "includeArtists",
  includePlaylists = "includePlaylists"
}

export const AllowedImageSizes = [64, 128, 256, 350, 475, 512, 696, 850, 1000, 1280, 1600, 2500] as const;
export type AllowedImageSize = (typeof AllowedImageSizes)[number];
