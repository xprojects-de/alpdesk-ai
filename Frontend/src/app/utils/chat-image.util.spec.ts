import {ChatFile} from "../models/chat.model";
import {
    isCreatedImage,
    isCreatedVideo,
    keyBelongsToTarget,
    nextWhilePlaying,
    releasedPreviewUrls,
    splitSequence,
    stepInSequence,
} from "./chat-image.util";

describe("chat-image.util", () => {
    const file = (origin: ChatFile["origin"], contentType: string): ChatFile => ({
        id: 1,
        origin,
        fileName: "bild.png",
        contentType,
        sizeBytes: 100,
        imageCount: 1,
        createdAt: "2026-10-10T10:00:00Z",
        expired: false,
    });

    it("shows the images the model created, not uploaded ones or other files", () => {
        expect(isCreatedImage(file("GENERATED", "image/png"))).toBe(true);
        expect(isCreatedImage(file("GENERATED", "image/jpeg"))).toBe(true);
        expect(isCreatedImage(file("UPLOADED", "image/png"))).toBe(false);
        expect(isCreatedImage(file("GENERATED", "application/pdf"))).toBe(false);
        expect(isCreatedImage(file("GENERATED", "image/bmp"))).toBe(true);
        expect(isCreatedImage(file("GENERATED", "image/tiff"))).toBe(false);
    });

    it("shows no image that expired - its content is gone, a file chip says so", () => {
        expect(isCreatedImage({...file("GENERATED", "image/png"), expired: true})).toBe(false);
    });

    it("releases the object URLs the store no longer holds", () => {
        expect(releasedPreviewUrls(["blob:a", "blob:b", "blob:c"], {1: "blob:b", 2: null})).toEqual([
            "blob:a",
            "blob:c",
        ]);
        expect(releasedPreviewUrls(["blob:a"], {})).toEqual(["blob:a"]);
        expect(releasedPreviewUrls([], {1: "blob:a"})).toEqual([]);
    });

    it("shows two or more created images of an answer as one sequence, everything else as before", () => {
        const image = (id: number) => ({...file("GENERATED", "image/png"), id});
        const table = {...file("GENERATED", "text/csv"), id: 9};
        const uploaded = {...file("UPLOADED", "image/png"), id: 8};

        expect(splitSequence([image(1), table])).toEqual({sequence: [], others: [image(1), table]});
        expect(splitSequence([image(1), table, image(2), uploaded])).toEqual({
            sequence: [image(1), image(2)],
            others: [table, uploaded],
        });
        expect(splitSequence([image(1), {...image(2), expired: true}])).toEqual({
            sequence: [],
            others: [image(1), {...image(2), expired: true}],
        });
    });

    it("steps by hand up to the ends, plays round in a loop", () => {
        expect(stepInSequence(0, -1, 5)).toBe(0);
        expect(stepInSequence(2, 1, 5)).toBe(3);
        expect(stepInSequence(4, 1, 5)).toBe(4);
        expect(nextWhilePlaying(3, 5)).toBe(4);
        expect(nextWhilePlaying(4, 5)).toBe(0);
    });

    it("leaves a key to the focused button or slider, takes it on the frame or the host", () => {
        const pressedOn = (element: Element | null) => ({target: element}) as unknown as Event;
        const button = document.createElement("button");
        const slider = document.createElement("input");
        const host = document.createElement("app-chat-image-sequence");

        expect(keyBelongsToTarget(pressedOn(button), "button, input")).toBe(true);
        expect(keyBelongsToTarget(pressedOn(slider), "button, input")).toBe(true);
        expect(keyBelongsToTarget(pressedOn(button), "input")).toBe(false);
        expect(keyBelongsToTarget(pressedOn(host), "button, input")).toBe(false);
        expect(keyBelongsToTarget(pressedOn(null), "button, input")).toBe(false);
    });

    it("plays a clip the model handed out - not an uploaded video, not one that expired", () => {
        expect(isCreatedVideo(file("GENERATED", "video/mp4"))).toBe(true);
        expect(isCreatedVideo(file("UPLOADED", "video/mp4"))).toBe(false);
        expect(isCreatedVideo(file("GENERATED", "video/quicktime"))).toBe(false);
        expect(isCreatedVideo({...file("GENERATED", "video/mp4"), expired: true})).toBe(false);
    });
});
