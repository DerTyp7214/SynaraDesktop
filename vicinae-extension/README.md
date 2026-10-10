# Synara Vicinae Extension

A launcher extension for **Vicinae** (Linux) that searches and controls the **Synara** desktop app.

The extension talks to the running desktop app over D-Bus (`dev.dertyp.synara.Api` and MPRIS on the bus name `org.mpris.MediaPlayer2.synara`). The app has to be running. If it is not, every command says so instead of failing with a D-Bus error.

## Commands

| Command | Kind | What it does |
| :--- | :--- | :--- |
| Search Music | view | Searches songs, albums, artists and playlists. With an empty query it shows the current song. Takes an optional `query` argument. |
| Now Playing | view | Shows the current song with cover, position and like state, and controls playback. |
| Show Queue | view | Lists the upcoming songs. |
| Show Playlists | view | Lists your own playlists and the ones shared with you. |
| Show Lyrics | view | Shows the lyrics of the current song. |
| Play / Pause | no view | Toggles playback. |
| Next Track | no view | Skips to the next song. |
| Previous Track | no view | Goes back to the previous song. |
| Like Current Song | no view | Likes the current song and reports what it did. |

### Search Music

- The dropdown in the search bar limits the search to All, Songs, Albums, Artists or Playlists. The app only searches what is selected.
- Songs show the credited artists, title tags such as `(Live)`, an `E` marker for explicit songs, a heart or star for liked and super liked songs, and the duration.
- Albums show the artists, the release year and the track count.
- Playlists show the song count, the duration when it is known, and the owner when the playlist belongs to someone else. A `Public` tag marks public playlists, a `Shared` tag marks playlists that are shared with you.

### Now Playing

The view follows the app: it updates when the song or the playback state changes and refreshes the position once a second.

### Show Playlists

Playlists are grouped into "My Playlists" and "Shared with Me". A `Public` tag marks public playlists, a `Read-only` tag marks shared playlists you cannot add songs to. Typing filters by name.

## Actions

| Action | Where | Shortcut |
| :--- | :--- | :--- |
| Play (Play at Position in the queue) | Search, Queue, Playlists | |
| Play Next | Search, Queue, Playlists | `Ctrl+Shift+N` |
| Add to Queue | Search, Queue, Playlists | `Ctrl+Shift+Q` |
| Like / Remove Like | songs in Search and Queue, Now Playing | `Ctrl+Shift+L` |
| Super Like / Remove Super Like | songs in Search and Queue, Now Playing | `Ctrl+Shift+S` |
| Add to Playlist | Search, Queue, Playlists, Now Playing | `Ctrl+Shift+A` |
| Show Lyrics | current song in Search, Now Playing | `Ctrl+Shift+Y` |
| Play / Pause | Now Playing | `Enter` |
| Next Track | Now Playing | `Ctrl+Shift+N` |
| Previous Track | Now Playing | `Ctrl+Shift+P` |
| Seek Forward 10 Seconds | Now Playing | `Ctrl+Shift+F` |
| Seek Back 10 Seconds | Now Playing | `Ctrl+Shift+B` |
| Refresh Queue | Queue | the launcher's refresh shortcut |

Play, Play Next and Add to Queue are always offered together. The preference decides which of them is the primary action.

"Add to Playlist" lists the playlists you may add songs to: your own and the ones shared with you with write access. It works for songs, albums, artists and playlists.

## Preferences

| Preference | Values | Meaning |
| :--- | :--- | :--- |
| Development Mode | on / off | Connects to the debug build of Synara (`org.mpris.MediaPlayer2.synara-dev`). |
| Default Search Action | Play, Play Next, Add to Queue | Primary action in Search and Playlists. |
| Default Queue Action | Play at Position, Play Next, Add to Queue | Primary action in the Queue. |

## Build and install

`@vicinae/api` is versioned with the launcher. The version in `package.json` has to match the installed Vicinae version.

```shell
pnpm install
pnpm build
```

`pnpm build` validates the manifest, regenerates `vicinae-env.d.ts`, type-checks the sources and stops on a type error. It then bundles one file per command and replaces `~/.local/share/vicinae/extensions/synara-vicinae` with the result, so building also installs.

### Scripts

- `pnpm build`: type-check, bundle and install the extension.
- `pnpm dev`: run the extension in development mode. Needs a running launcher.
- `pnpm format`: format the sources with Biome.
- `pnpm lint`: validate the manifest.

`x11` and `debug` are development dependencies because the bundler has to resolve them for `dbus-next`. The extension loads no native module.

### Project structure

- `src/lib/dbus.ts`: the D-Bus connection and one typed function per call.
- `src/lib/types.ts`: the part of the Synara models the extension uses.
- `src/lib/format.ts`: artist lines, titles with tags, durations and subtitles.
- `src/lib/actions.tsx`: the playback, like and "Add to Playlist" actions and their shortcuts.
- `src/lib/rows.tsx`: cover images, accessories and the song detail shared by the lists.
- `src/lib/feedback.tsx`: failure toasts and the "not running" state.
- `src/lib/hooks.ts`: loading state for the views.
- `src/lib/quick.ts`: the frame of the commands without a view.
- `src/<command>.tsx` and `src/<command>.ts`: one file per command in `package.json`.
