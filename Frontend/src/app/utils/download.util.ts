/** Saves loaded content as a download under `fileName` (the object URL is released right after). */
export function saveBlob(blob: Blob, fileName: string): void {
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = fileName;
    link.click();
    // Released on the next turn: the click has started the download by then.
    setTimeout(() => URL.revokeObjectURL(url));
}
