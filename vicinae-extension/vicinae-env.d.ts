/// <reference types="@vicinae/api">

/*
 * This file is auto-generated from the extension's manifest.
 * Do not modify manually. Instead, update the `package.json` file.
 */

type ExtensionPreferences = {
  /** Development Mode - Connect to the debug build of Synara (synara-dev) */
	"devMode": boolean;

	/** Default Search Action - Primary action when selecting a result in Search */
	"defaultSearchAction"?: "play" | "playNext" | "addToQueue";

	/** Default Queue Action - Primary action when selecting an item in the Queue */
	"defaultQueueAction"?: "play" | "playNext" | "addToQueue";
}

declare type Preferences = ExtensionPreferences

declare namespace Preferences {
  /** Command: Search Music */
	export type Search = ExtensionPreferences & {
		
	}

	/** Command: Show Queue */
	export type Queue = ExtensionPreferences & {
		
	}

	/** Command: Show Lyrics */
	export type Lyrics = ExtensionPreferences & {
		
	}
}

declare namespace Arguments {
  /** Command: Search Music */
	export type Search = {
		/** Search query */
		"query": string
	}

	/** Command: Show Queue */
	export type Queue = {
		
	}

	/** Command: Show Lyrics */
	export type Lyrics = {
		
	}
}