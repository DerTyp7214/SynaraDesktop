import { Color, Detail, Icon, Image, List } from "@vicinae/api";
import type { ReactNode } from "react";
import { imageUrl } from "./dbus";
import {
	albumName,
	artistLine,
	formatDuration,
	likeLabel,
	likeLevelOf,
	releaseYear,
	songTitle,
} from "./format";
import type { LikeLevel, UserSong } from "./types";

const LIKE_ICONS: Record<LikeLevel, Image> = {
	NONE: { source: Icon.HeartDisabled, tintColor: Color.SecondaryText },
	LIKE: { source: Icon.Heart, tintColor: Color.Red },
	SUPER: { source: Icon.Star, tintColor: Color.Yellow },
};

export const PUBLIC_ACCESSORY: List.Item.Accessory = {
	tag: { value: "Public", color: Color.Green },
};

export const SHARED_ACCESSORY: List.Item.Accessory = {
	tag: { value: "Shared", color: Color.Blue },
};

export const READ_ONLY_ACCESSORY: List.Item.Accessory = {
	tag: { value: "Read-only", color: Color.Orange },
};

export function coverIcon(
	imageId: string | null | undefined,
	fallback: Icon,
	mask: Image.Mask = Image.Mask.RoundedRectangle,
): Image.ImageLike {
	const source = imageUrl(imageId, 128);
	return source ? { source, mask } : fallback;
}

export function coverMarkdown(
	imageId: string | null | undefined,
	heading: string,
): string {
	const source = imageUrl(imageId, 512);
	return source ? `![Cover](${source})\n\n# ${heading}` : `# ${heading}`;
}

export function songAccessories(song: UserSong): List.Item.Accessory[] {
	const accessories: List.Item.Accessory[] = [];
	const level = likeLevelOf(song);
	if (song.explicit) {
		accessories.push({ tag: "E", tooltip: "Explicit" });
	}
	if (level !== "NONE") {
		accessories.push({ icon: LIKE_ICONS[level], tooltip: likeLabel(level) });
	}
	accessories.push({ text: formatDuration(song.duration) });
	return accessories;
}

export function SongMetadata(props: { song: UserSong; children?: ReactNode }) {
	const { song, children } = props;
	const level = likeLevelOf(song);
	const year = releaseYear(song.releaseDate);
	return (
		<Detail.Metadata>
			{children}
			<Detail.Metadata.Label title="Title" text={songTitle(song)} />
			<Detail.Metadata.Label title="Artists" text={artistLine(song.artists)} />
			{song.album && (
				<Detail.Metadata.Label title="Album" text={albumName(song.album)} />
			)}
			<Detail.Metadata.Label
				title="Duration"
				text={formatDuration(song.duration)}
			/>
			{year && <Detail.Metadata.Label title="Released" text={year} />}
			<Detail.Metadata.Label
				title="Like"
				text={likeLabel(level)}
				icon={LIKE_ICONS[level]}
			/>
			{song.explicit && <Detail.Metadata.Label title="Explicit" text="Yes" />}
		</Detail.Metadata>
	);
}

export function SongDetail(props: { song: UserSong }) {
	const { song } = props;
	return (
		<List.Item.Detail
			markdown={coverMarkdown(song.coverId, songTitle(song))}
			metadata={<SongMetadata song={song} />}
		/>
	);
}
