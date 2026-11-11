import {FileStore} from "../models/file-store.model";
import {KnowledgeSource} from "../models/knowledge-source.model";
import {
    buildSourceRequest,
    KnowledgeSourceFormValue,
    normalizedFolder,
    overlappingSource,
    readsOtherFiles,
    sourceErrorParams,
    sourceFileReason,
    sourceStatus,
} from "./knowledge-source.util";

const source = (change: Partial<KnowledgeSource> = {}): KnowledgeSource => ({
    id: 1,
    name: "Projekte",
    fileStoreId: 7,
    fileStore: "Ablage",
    folder: "Projekte",
    areaId: null,
    area: null,
    pattern: null,
    schedule: "DAILY",
    active: true,
    nextRunAt: null,
    running: false,
    lastStartedAt: null,
    lastFinishedAt: "2026-10-27T10:00:00Z",
    lastError: null,
    lastAdded: 0,
    lastChanged: 0,
    lastDeleted: 0,
    lastRefused: 0,
    lastSkipped: 0,
    lastSucceededAt: "2026-10-27T10:00:00Z",
    openFiles: 0,
    openBytes: 0,
    ...change,
});

describe("buildSourceRequest", () => {
    const form: KnowledgeSourceFormValue = {
        name: " Projekte ",
        fileStoreId: 7,
        folder: " Projekte ",
        areaId: 3,
        pattern: "  ",
        schedule: "HOURLY",
        active: true,
    };

    it("trims the fields and leaves out an empty pattern", () => {
        expect(buildSourceRequest(form)).toEqual({
            name: "Projekte",
            fileStoreId: 7,
            folder: "Projekte",
            areaId: 3,
            schedule: "HOURLY",
            active: true,
        });
    });

    it("sends a pattern", () => {
        expect(buildSourceRequest({...form, pattern: " *.pdf "}).pattern).toBe("*.pdf");
    });
});

describe("readsOtherFiles", () => {
    it("is false for a new source and for the same store and folder however written", () => {
        expect(readsOtherFiles(null, {fileStoreId: 7, folder: "x"})).toBe(false);
        expect(readsOtherFiles(source(), {fileStoreId: 7, folder: " /Projekte// "})).toBe(false);
    });

    it("is true for another store or folder", () => {
        expect(readsOtherFiles(source(), {fileStoreId: 8, folder: "Projekte"})).toBe(true);
        expect(readsOtherFiles(source(), {fileStoreId: 7, folder: "Archiv"})).toBe(true);
    });

    it("normalizes a folder like the backend", () => {
        expect(normalizedFolder(" //Projekte//2025/ ")).toBe("Projekte/2025");
        expect(normalizedFolder("/")).toBe("");
    });
});

describe("sourceStatus", () => {
    it("tells running, new, ok, cancelled and incomplete apart", () => {
        expect(sourceStatus(source({running: true})).label).toBe("knowledgeSources.status.running");
        expect(sourceStatus(source({lastFinishedAt: null})).label).toBe("knowledgeSources.status.new");
        expect(sourceStatus(source()).chip).toBe("ok");
        expect(sourceStatus(source({lastError: "source.cancelled"})).chip).toBe("warn");
        expect(sourceStatus(source({lastError: "source.tooManyFiles"})).label).toBe(
            "knowledgeSources.status.incomplete",
        );
    });

    it("names since when a failing source last worked", () => {
        expect(sourceStatus(source({lastError: "fileStore.unreachable"}))).toEqual({
            chip: "err",
            label: "knowledgeSources.status.failedSince",
            since: "2026-10-27T10:00:00Z",
        });
        expect(sourceStatus(source({lastError: "fileStore.unreachable", lastSucceededAt: null})).label).toBe(
            "knowledgeSources.status.failed",
        );
    });
});

describe("overlappingSource", () => {
    const projects = source({id: 1, name: "Projekte", folder: "Firmenwissen/Projekte"});
    const elsewhere = source({id: 2, name: "Anderswo", fileStoreId: 8, folder: "Firmenwissen"});

    it("finds another source of the same store on the same folder, above or below it", () => {
        expect(overlappingSource([projects], null, 7, "Firmenwissen/Projekte")?.name).toBe("Projekte");
        expect(overlappingSource([projects], null, 7, "/Firmenwissen/")?.name).toBe("Projekte");
        expect(overlappingSource([projects], null, 7, "Firmenwissen/Projekte/2025")?.name).toBe("Projekte");
        expect(overlappingSource([projects], null, 7, "")?.name).toBe("Projekte");
    });

    it("leaves siblings, other stores and the source itself alone", () => {
        expect(overlappingSource([projects], null, 7, "Firmenwissen/Projekte-alt")).toBeNull();
        expect(overlappingSource([projects], null, 7, "Firmenwissen/Handbuch")).toBeNull();
        expect(overlappingSource([elsewhere], null, 7, "Firmenwissen")).toBeNull();
        expect(overlappingSource([projects], 1, 7, "Firmenwissen")).toBeNull();
    });
});

describe("sourceErrorParams", () => {
    const store = (change: Partial<FileStore>): FileStore =>
        ({id: 7, name: "Ablage", host: null, bucket: null, siteUrl: null, library: null, ...change}) as FileStore;

    it("names the store's server, bucket, site and library for the backend's texts", () => {
        expect(sourceErrorParams(source(), store({host: "files.example.com"}))).toMatchObject({
            host: "files.example.com",
            store: "Ablage",
            path: "Projekte",
        });
        expect(
            sourceErrorParams(
                source(),
                store({bucket: "firma-docs", siteUrl: "https://firma.sharepoint.com/sites/p", library: "Dokumente"}),
            ),
        ).toMatchObject({bucket: "firma-docs", site: "https://firma.sharepoint.com/sites/p", library: "Dokumente"});
    });

    it("falls back to the source's store name and the root folder without a store", () => {
        expect(sourceErrorParams(source({folder: ""}))).toMatchObject({
            host: "Ablage",
            store: "Ablage",
            bucket: "",
            path: "/",
        });
    });
});

describe("sourceFileReason", () => {
    it("names a file deleted by hand, a type not read, and every other refusal by its code", () => {
        expect(sourceFileReason(null)).toBe("knowledgeSources.files.deletedByHand");
        expect(sourceFileReason("document.typeNotSupported")).toBe("knowledgeSources.files.typeNotSupported");
        expect(sourceFileReason("document.empty")).toBe("errors.backend.document.empty");
        expect(sourceFileReason("source.pathTooLong")).toBe("errors.backend.source.pathTooLong");
        expect(sourceFileReason("fileStore.readFailed")).toBe("knowledgeSources.files.unreadable");
    });
});
