/// <reference types="@vicinae/api">

/*
 * This file is auto-generated from the extension's manifest.
 * Do not modify manually. Instead, update the `package.json` file.
 */

type ExtensionPreferences = {
  /** Development Mode - Connect to the debug build of Synara (synara-dev) */
	"devMode"?: boolean;

	/** Default Search Action - Primary action when selecting a result in Search or a playlist in Playlists */
	"defaultSearchAction": "play" | "playNext" | "addToQueue";

	/** Default Queue Action - Primary action when selecting an item in the Queue */
	"defaultQueueAction": "play" | "playNext" | "addToQueue";
}

declare type Preferences = ExtensionPreferences

declare namespace Preferences {
  /** Command: Search Music */
	export type Search = ExtensionPreferences & {
		
	}

	/** Command: Now Playing */
	export type NowPlaying = ExtensionPreferences & {
		
	}

	/** Command: Show Queue */
	export type Queue = ExtensionPreferences & {
		
	}

	/** Command: Show Playlists */
	export type Playlists = ExtensionPreferences & {
		
	}

	/** Command: Show Lyrics */
	export type Lyrics = ExtensionPreferences & {
		
	}

	/** Command: Play / Pause */
	export type PlayPause = ExtensionPreferences & {
		
	}

	/** Command: Next Track */
	export type NextTrack = ExtensionPreferences & {
		
	}

	/** Command: Previous Track */
	export type PreviousTrack = ExtensionPreferences & {
		
	}

	/** Command: Like Current Song */
	export type LikeCurrent = ExtensionPreferences & {
		
	}
}

declare namespace Arguments {
  /** Command: Search Music */
	export type Search = {
		/** Search query */
		"query"?: string
	}

	/** Command: Now Playing */
	export type NowPlaying = {
		
	}

	/** Command: Show Queue */
	export type Queue = {
		
	}

	/** Command: Show Playlists */
	export type Playlists = {
		
	}

	/** Command: Show Lyrics */
	export type Lyrics = {
		
	}

	/** Command: Play / Pause */
	export type PlayPause = {
		
	}

	/** Command: Next Track */
	export type NextTrack = {
		
	}

	/** Command: Previous Track */
	export type PreviousTrack = {
		
	}

	/** Command: Like Current Song */
	export type LikeCurrent = {
		
	}
}