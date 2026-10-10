import { getPreferenceValues } from "@vicinae/api";
import {
	type ClientInterface,
	DBusError,
	type MessageBus,
	sessionBus,
	Variant,
} from "dbus-next";
import type {
	ImageSize,
	LikeLevel,
	PlayableType,
	PlaybackStatus,
	SearchFilter,
	SynaraPlaybackState,
	SynaraPlaylists,
	SynaraQueueItem,
	SynaraSearchResult,
	SynaraServerInfo,
	UserSong,
} from "./types";

const API_PATH = "/dev/dertyp/synara";
const API_INTERFACE = "dev.dertyp.synara.Api";
const MPRIS_PATH = "/org/mpris/MediaPlayer2";
const PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player";
const PROPERTIES_INTERFACE = "org.freedesktop.DBus.Properties";
const PROPERTIES_CHANGED = "PropertiesChanged";

const NOT_RUNNING_ERRORS: ReadonlySet<string> = new Set([
	"org.freedesktop.DBus.Error.ServiceUnknown",
	"org.freedesktop.DBus.Error.NameHasNoOwner",
]);

const SEARCH_FILTERS: SearchFilter[] = [
	"includeSongs",
	"includeAlbums",
	"includeArtists",
	"includePlaylists",
];

interface ApiProxy extends ClientInterface {
	GetServerInfo(): Promise<string>;
	GetPlaybackState(): Promise<string>;
	GetCurrentSong(): Promise<string>;
	Search(query: string, options: Record<string, Variant>): Promise<string>;
	GetCurrentLyrics(): Promise<string>;
	GetQueue(limit: number): Promise<string>;
	PlayQueueItem(queueId: string): Promise<void>;
	PlaySong(id: string): Promise<void>;
	PlayAlbum(id: string): Promise<void>;
	PlayArtist(id: string): Promise<void>;
	PlayPlaylist(id: string): Promise<void>;
	PlayNext(id: string, type: PlayableType): Promise<void>;
	AddToQueue(id: string, type: PlayableType): Promise<void>;
	GetPlaylists(): Promise<string>;
	SetLikeLevel(id: string, level: LikeLevel): Promise<string>;
	AddToPlaylist(
		playlistId: string,
		id: string,
		type: PlayableType,
	): Promise<void>;
}

interface PlayerProxy extends ClientInterface {
	PlayPause(): Promise<void>;
	Next(): Promise<void>;
	Previous(): Promise<void>;
	Seek(offset: bigint): Promise<void>;
}

interface PropertiesProxy extends ClientInterface {
	Get(interfaceName: string, property: string): Promise<Variant<unknown>>;
}

interface Connection {
	api: ApiProxy;
	player: PlayerProxy;
	properties: PropertiesProxy;
}

export class SynaraNotRunningError extends Error {
	constructor() {
		super("Synara is not running");
		this.name = "SynaraNotRunningError";
	}
}

let bus: MessageBus | null = null;
let connection: Promise<Connection> | null = null;
let imageBaseUrl = "";

export function disconnect() {
	bus?.disconnect();
	bus = null;
	connection = null;
}

async function connect(): Promise<Connection> {
	if (!bus) {
		bus = sessionBus();
		bus.on("error", disconnect);
	}
	const { devMode } = getPreferenceValues<Preferences>();
	const busName = `org.mpris.MediaPlayer2.synara${devMode ? "-dev" : ""}`;
	const [apiObject, mprisObject] = await Promise.all([
		bus.getProxyObject(busName, API_PATH),
		bus.getProxyObject(busName, MPRIS_PATH),
	]);
	return {
		api: apiObject.getInterface<ApiProxy>(API_INTERFACE),
		player: mprisObject.getInterface<PlayerProxy>(PLAYER_INTERFACE),
		properties: mprisObject.getInterface<PropertiesProxy>(PROPERTIES_INTERFACE),
	};
}

async function call<T>(run: (target: Connection) => Promise<T>): Promise<T> {
	connection ??= connect().catch((error: unknown) => {
		connection = null;
		throw error;
	});
	try {
		return await run(await connection);
	} catch (error) {
		if (error instanceof DBusError && NOT_RUNNING_ERRORS.has(error.type)) {
			throw new SynaraNotRunningError();
		}
		throw error;
	}
}

async function loadImageBaseUrl(api: ApiProxy) {
	if (imageBaseUrl) return;
	const info = JSON.parse(await api.GetServerInfo()) as SynaraServerInfo;
	imageBaseUrl = info.imageBaseUrl;
}

function readWithImages(
	read: (api: ApiProxy) => Promise<string>,
): Promise<string> {
	return call(async ({ api }) => {
		const [json] = await Promise.all([read(api), loadImageBaseUrl(api)]);
		return json;
	});
}

function parseSong(json: string): UserSong | null {
	return json ? (JSON.parse(json) as UserSong) : null;
}

export function imageUrl(
	imageId: string | null | undefined,
	size: ImageSize,
): string | undefined {
	if (!imageId || !imageBaseUrl) return undefined;
	return `${imageBaseUrl}${imageId}?size=${size}`;
}

export async function search(
	query: string,
	only?: SearchFilter,
): Promise<SynaraSearchResult> {
	const options: Record<string, Variant> = {};
	if (only) {
		for (const filter of SEARCH_FILTERS) {
			options[filter] = new Variant("b", filter === only);
		}
	}
	const json = await readWithImages((api) => api.Search(query, options));
	return JSON.parse(json) as SynaraSearchResult;
}

export async function getCurrentSong(): Promise<UserSong | null> {
	return parseSong(await readWithImages((api) => api.GetCurrentSong()));
}

export async function getPlaybackState(): Promise<SynaraPlaybackState> {
	const json = await call(({ api }) => api.GetPlaybackState());
	return JSON.parse(json) as SynaraPlaybackState;
}

export function getCurrentLyrics(): Promise<string> {
	return call(({ api }) => api.GetCurrentLyrics());
}

export async function getQueue(limit: number): Promise<SynaraQueueItem[]> {
	const json = await readWithImages((api) => api.GetQueue(limit));
	return JSON.parse(json) as SynaraQueueItem[];
}

export async function getPlaylists(): Promise<SynaraPlaylists> {
	const json = await readWithImages((api) => api.GetPlaylists());
	return JSON.parse(json) as SynaraPlaylists;
}

export function playQueueItem(queueId: string): Promise<void> {
	return call(({ api }) => api.PlayQueueItem(queueId));
}

export function play(id: string, type: PlayableType): Promise<void> {
	return call(({ api }) => {
		switch (type) {
			case "song":
				return api.PlaySong(id);
			case "album":
				return api.PlayAlbum(id);
			case "artist":
				return api.PlayArtist(id);
			case "playlist":
				return api.PlayPlaylist(id);
		}
	});
}

export function playNext(id: string, type: PlayableType): Promise<void> {
	return call(({ api }) => api.PlayNext(id, type));
}

export function addToQueue(id: string, type: PlayableType): Promise<void> {
	return call(({ api }) => api.AddToQueue(id, type));
}

export async function setLikeLevel(
	id: string,
	level: LikeLevel,
): Promise<UserSong | null> {
	return parseSong(await call(({ api }) => api.SetLikeLevel(id, level)));
}

export function addToPlaylist(
	playlistId: string,
	id: string,
	type: PlayableType,
): Promise<void> {
	return call(({ api }) => api.AddToPlaylist(playlistId, id, type));
}

export function playPause(): Promise<void> {
	return call(({ player }) => player.PlayPause());
}

export function nextTrack(): Promise<void> {
	return call(({ player }) => player.Next());
}

export function previousTrack(): Promise<void> {
	return call(({ player }) => player.Previous());
}

export function seekBy(offsetMs: number): Promise<void> {
	return call(({ player }) => player.Seek(BigInt(offsetMs) * 1000n));
}

export async function getPlaybackStatus(): Promise<PlaybackStatus> {
	const status = await call(({ properties }) =>
		properties.Get(PLAYER_INTERFACE, "PlaybackStatus"),
	);
	return status.value as PlaybackStatus;
}

export function subscribePlayerChanges(
	listener: (changedProperties: string[]) => void,
): Promise<() => void> {
	return call(async ({ properties }) => {
		const handler = (
			interfaceName: string,
			changed: Record<string, Variant>,
		) => {
			if (interfaceName === PLAYER_INTERFACE) listener(Object.keys(changed));
		};
		properties.on(PROPERTIES_CHANGED, handler);
		return () => {
			properties.off(PROPERTIES_CHANGED, handler);
		};
	});
}
