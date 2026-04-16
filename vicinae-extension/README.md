# Synara Vicinae Extension

A high-performance launcher extension for **Vicinae** (Linux) to search and control your **Synara** music library.

## Features

- **Instant Search**: Find songs, albums, artists, and playlists as you type.
- **Customizable Actions**: Configure whether to Play, Play Next, or Add to Queue by default in the extension settings.
- **Queue Management**: View upcoming tracks and jump to any position.
- **Lyrics View**: See the lyrics of the currently playing song instantly.
- **Dev Mode**: Seamlessly switch between production and development builds of Synara.

## Installation

1. **Build Synara**: Ensure you have Synara running (either the production build or the dev build).
2. **Install Dependencies**:
   ```shell
   pnpm install
   ```

## Development

Run the following command to start the extension in development mode:

```shell
pnpm dev
```

### Scripts

- `pnpm build`: Build the extension for production.
- `pnpm dev`: Start the extension in development mode.
- `pnpm format`: Format the source code using Biome.
- `pnpm lint`: Lint the source code.

### Project Structure

- `src/lib/dbus.ts`: D-Bus connection and API wrapper using `dbus-next`.
- `src/search.tsx`: Main search interface with "Now Playing" detail view.
- `src/queue.tsx`: Queue viewer with "Play at Position" actions.
- `src/lyrics.tsx`: Detailed lyrics view.

### Configuration

Open the extension settings in Vicinae to toggle **Development Mode** or change the **Default Search Action**.
