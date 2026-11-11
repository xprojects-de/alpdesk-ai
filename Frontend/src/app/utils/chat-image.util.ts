import {ChatFile} from "../models/chat.model";

/** The image types a browser shows - what the image model sends (PNG, JPEG, WebP) and code hands out (also BMP). */
const SHOWN_IMAGES = ["image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp"];

/** An image the model created (tool create_image) - the answer shows it, not only its chip. */
export function isCreatedImage(file: ChatFile): boolean {
    return file.origin === "GENERATED" && SHOWN_IMAGES.includes(file.contentType) && !file.expired;
}

/**
 * The object URLs to release: those created that the store no longer holds (another conversation, logout). An object
 * URL keeps its image in memory until it is released.
 */
export function releasedPreviewUrls(created: readonly string[], previews: Record<number, string | null>): string[] {
    const kept = new Set(Object.values(previews));
    return created.filter(url => !kept.has(url));
}

/** A clip the model handed out (an MP4 the browser plays) - the answer shows a player, not only its chip. */
export function isCreatedVideo(file: ChatFile): boolean {
    return file.origin === "GENERATED" && file.contentType === "video/mp4" && !file.expired;
}

/** An answer's files: its created images as one sequence (two or more - shown with a slider), and the rest. */
export function splitSequence(files: ChatFile[]): {sequence: ChatFile[]; others: ChatFile[]} {
    const images = files.filter(isCreatedImage);
    if (images.length < 2) {
        return {sequence: [], others: files};
    }
    return {sequence: images, others: files.filter(file => !isCreatedImage(file))};
}

/** One step through a sequence - stops at its ends (stepping by hand). */
export function stepInSequence(index: number, step: number, count: number): number {
    return Math.min(Math.max(index + step, 0), count - 1);
}

/** The next image while playing - from the last back to the first, like a clip on a loop. */
export function nextWhilePlaying(index: number, count: number): number {
    return count === 0 ? 0 : (index + 1) % count;
}

/** The speeds to play a sequence at, in images per second - a click on the speed takes the next. */
export const SEQUENCE_SPEEDS = [2, 4, 8, 1];

/**
 * Whether a key pressed inside the sequence is one its focused control uses itself (selector: the controls that do),
 * so the sequence's own key handling must leave it alone.
 */
export function keyBelongsToTarget(event: Event, selector: string): boolean {
    return event.target instanceof Element && event.target.matches(selector);
}
