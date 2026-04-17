/// <reference types="@raycast/api">

/* 🚧 🚧 🚧
 * This file is auto-generated from the extension's manifest.
 * Do not modify manually. Instead, update the `package.json` file.
 * 🚧 🚧 🚧 */

/* eslint-disable @typescript-eslint/ban-types */

type ExtensionPreferences = {
  /** Default Search Action - The action to perform when pressing Enter on a search result. */
  "defaultSearchAction"?: "play" | "playNext" | "addToQueue",
  /** Default Queue Action - The action to perform when pressing Enter on a queue item. */
  "defaultQueueAction"?: "play" | "playNext" | "addToQueue"
}

/** Preferences accessible in all the extension's commands */
declare type Preferences = ExtensionPreferences

declare namespace Preferences {
  /** Preferences accessible in the `search` command */
  export type Search = ExtensionPreferences & {}
  /** Preferences accessible in the `now-playing` command */
  export type NowPlaying = ExtensionPreferences & {}
  /** Preferences accessible in the `queue` command */
  export type Queue = ExtensionPreferences & {}
}

declare namespace Arguments {
  /** Arguments passed to the `search` command */
  export type Search = {}
  /** Arguments passed to the `now-playing` command */
  export type NowPlaying = {}
  /** Arguments passed to the `queue` command */
  export type Queue = {}
}

