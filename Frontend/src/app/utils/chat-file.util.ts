import {MAX_UPLOAD_BYTES, formatBytes} from "./document.util";

/** The file picker's filter: what the backend can read (it answers 415 for anything else). */
export const CHAT_FILE_ACCEPT = [
    ".pdf",
    ".docx",
    ".odt",
    ".rtf",
    ".txt",
    ".md",
    ".markdown",
    ".html",
    ".htm",
    ".pptx",
    ".xlsx",
    ".ods",
    ".csv",
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".bmp",
    // Audio: the backend reads any format ffmpeg reads; video: the code execution examines it. The common ones.
    ".mp3",
    ".m4a",
    ".wav",
    ".ogg",
    ".aac",
    ".flac",
    ".mp4",
    ".mov",
    ".webm",
    ".mkv",
].join(",");

/** Like the backend: files per upload. */
export const MAX_FILES_PER_UPLOAD = 10;

const SPREADSHEET = /\.(xlsx|ods|csv)$|spreadsheet|csv/i;
/** An archive - a connected software's tool may return one. */
const ARCHIVE = /\.(zip|gz|tgz|tar|7z)$|^application\/(zip|gzip|x-gzip|x-tar|x-7z-compressed)$/i;

/** The material icon of a file: image, audio, video, PDF, spreadsheet, archive or any other document. */
export function chatFileIcon(contentType: string, fileName: string): string {
    if (contentType.startsWith("image/")) {
        return "image";
    }
    if (contentType.startsWith("audio/")) {
        return "graphic_eq";
    }
    if (contentType.startsWith("video/")) {
        return "movie";
    }
    if (contentType === "application/pdf" || /\.pdf$/i.test(fileName)) {
        return "picture_as_pdf";
    }
    if (SPREADSHEET.test(contentType) || SPREADSHEET.test(fileName)) {
        return "table_chart";
    }
    if (ARCHIVE.test(contentType) || ARCHIVE.test(fileName)) {
        return "folder_zip";
    }
    return "description";
}

/** A text for the user: its translation key and parameters. */
export interface TranslatedText {
    key: string;
    params: Record<string, string | number>;
}

const VIDEO = /\.(mp4|mov|webm|mkv)$/i;

/** A video - the backend limits it by a size of the installation's own, not by the upload's. */
export function isVideoFile(file: File): boolean {
    return (file.type ?? "").startsWith("video/") || VIDEO.test(file.name);
}

/**
 * Why the selected files cannot be uploaded at once (for the snackbar), sizes in the given locale; null if they can.
 * Videos are left to the backend: their limit is the installation's setting.
 */
export function chatUploadError(files: File[], locale: string): TranslatedText | null {
    if (files.length > MAX_FILES_PER_UPLOAD) {
        return {key: "chat.upload.tooMany", params: {max: MAX_FILES_PER_UPLOAD}};
    }
    const total = files.filter(file => !isVideoFile(file)).reduce((sum, file) => sum + file.size, 0);
    if (total > MAX_UPLOAD_BYTES) {
        return {
            key: "chat.upload.tooLarge",
            params: {size: formatBytes(total, locale), max: formatBytes(MAX_UPLOAD_BYTES, locale)},
        };
    }
    return null;
}

/** The multipart body: every file as a part "files", the conversation (if any) as a part "conversationId". */
export function buildChatFileUpload(files: File[], conversationId: number | null): FormData {
    const body = new FormData();
    for (const file of files) {
        body.append("files", file, file.name);
    }
    if (conversationId !== null) {
        body.append("conversationId", String(conversationId));
    }
    return body;
}
