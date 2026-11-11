import {buildChatFileUpload, chatFileIcon, chatUploadError, isVideoFile} from "./chat-file.util";
import {MAX_UPLOAD_BYTES} from "./document.util";

describe("chat-file.util", () => {
    const file = (name: string, size = 10) => new File([new Uint8Array(size)], name);

    it("picks the icon by content type, else by extension", () => {
        expect(chatFileIcon("image/png", "foto.png")).toBe("image");
        expect(chatFileIcon("application/pdf", "preise.pdf")).toBe("picture_as_pdf");
        expect(chatFileIcon("application/octet-stream", "scan.PDF")).toBe("picture_as_pdf");
        expect(chatFileIcon("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "liste.xlsx")).toBe(
            "table_chart",
        );
        expect(chatFileIcon("text/csv", "gaeste.csv")).toBe("table_chart");
        expect(chatFileIcon("text/plain", "notiz.txt")).toBe("description");
        expect(chatFileIcon("application/zip", "demo-project.zip")).toBe("folder_zip");
        expect(chatFileIcon("application/octet-stream", "export.tgz")).toBe("folder_zip");
        expect(chatFileIcon("application/x-7z-compressed", "export.7z")).toBe("folder_zip");
        expect(chatFileIcon("audio/mpeg", "anruf.mp3")).toBe("graphic_eq");
        expect(chatFileIcon("video/mp4", "rundgang.mp4")).toBe("movie");
    });

    it("refuses more than 10 files or more than 50 MB together", () => {
        expect(chatUploadError([file("a.pdf"), file("b.pdf")], "de-DE")).toBeNull();
        expect(
            chatUploadError(
                Array.from({length: 11}, (_, i) => file(`${i}.txt`)),
                "de-DE",
            ),
        ).toEqual({
            key: "chat.upload.tooMany",
            params: {max: 10},
        });
        const big = {name: "gross.pdf", size: MAX_UPLOAD_BYTES} as File;
        expect(chatUploadError([big, file("klein.txt")], "de-DE")).toEqual({
            key: "chat.upload.tooLarge",
            params: {size: "50 MB", max: "50 MB"},
        });
    });

    it("leaves a video's size to the backend - the installation sets it - and does not count it in the total", () => {
        const video = {name: "lauf.mp4", size: 400 * 1024 * 1024, type: "video/mp4"} as File;
        const byName = {name: "lauf.MOV", size: 400 * 1024 * 1024, type: ""} as File;
        expect(chatUploadError([video, byName, file("notiz.txt")], "de-DE")).toBeNull();
        expect(isVideoFile(video)).toBe(true);
        expect(isVideoFile(byName)).toBe(true);
        expect(isVideoFile(file("bild.png"))).toBe(false);
    });

    it("sends every file as a part 'files' and the conversation if there is one", () => {
        const body = buildChatFileUpload([file("a.pdf"), file("b.docx")], 7);
        expect((body.getAll("files") as File[]).map(f => f.name)).toEqual(["a.pdf", "b.docx"]);
        expect(body.get("conversationId")).toBe("7");
        expect(buildChatFileUpload([file("a.pdf")], null).has("conversationId")).toBe(false);
    });
});
