export interface Artist {
  id: string;
  name: string;
  imageId?: string;
}

export interface Album {
  id: string;
  name: string;
  artists: Artist[];
  coverId?: string;
}

export interface UserSong {
  id: string;
  title: string;
  artists: Artist[];
  album?: Album;
  coverId?: string;
  duration: number;
  lyrics?: string;
}

export interface UserPlaylist {
  id: string;
  name: string;
  imageId?: string;
}

export interface SynaraSearchResult {
  songs: UserSong[];
  albums: Album[];
  artists: Artist[];
  playlists: UserPlaylist[];
}

export interface ServerInfo {
  host: string;
  port: number;
}

export interface PlaybackState {
  isPlaying: boolean;
  position: number;
  duration: number;
}

export interface SynaraQueueItem {
  queueId: string;
  song: UserSong;
}

export type SearchFilter =
  | "includeSongs"
  | "includeAlbums"
  | "includeArtists"
  | "includePlaylists";

export const AllowedImageSizes = [128, 256, 512, 1024] as const;
