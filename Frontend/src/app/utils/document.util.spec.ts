import {ImportedFile, TenantDocument} from "../models/document.model";
import {
    canRetry,
    countOutcomes,
    documentPath,
    fileTypeLabel,
    filterDocuments,
    documentProgress,
    formatBytes,
    hasPendingDocuments,
    MAX_UPLOAD_BYTES,
    STUCK_AFTER_MS,
    sortByPath,
    splitBySize,
} from "./document.util";

describe("document.util", () => {
    it("formats sizes human-readable", () => {
        expect(formatBytes(512, "en-GB")).toBe("512 B");
        expect(formatBytes(1536, "en-GB")).toBe("1.5 KB");
        expect(formatBytes(1536, "de-DE")).toBe("1,5 KB");
        expect(formatBytes(15 * 1024 * 1024, "de-DE")).toBe("15 MB");
    });

    it("sees pending and processing documents as pending", () => {
        const document = (status: TenantDocument["status"]) => ({status}) as TenantDocument;
        expect(hasPendingDocuments([document("READY"), document("FAILED")])).toBe(false);
        expect(hasPendingDocuments([document("READY"), document("PENDING")])).toBe(true);
        expect(hasPendingDocuments([document("PROCESSING")])).toBe(true);
    });

    it("offers processing again for a failed document and one stuck waiting or being embedded", () => {
        const now = Date.parse("2026-10-11T12:00:00Z");
        const since = (ms: number) => new Date(now - ms).toISOString();
        const document = (status: TenantDocument["status"], updatedAt: string) => ({status, updatedAt});
        expect(canRetry(document("FAILED", since(0)), now)).toBe(true);
        expect(canRetry(document("READY", since(STUCK_AFTER_MS * 2)), now)).toBe(false);
        expect(canRetry(document("PENDING", since(STUCK_AFTER_MS - 1000)), now)).toBe(false);
        expect(canRetry(document("PROCESSING", since(STUCK_AFTER_MS - 1000)), now)).toBe(false);
        expect(canRetry(document("PENDING", since(STUCK_AFTER_MS + 1000)), now)).toBe(true);
        expect(canRetry(document("PROCESSING", since(STUCK_AFTER_MS + 1000)), now)).toBe(true);
    });

    it("splits off files over the upload limit", () => {
        const file = (name: string, size: number) => ({name, size}) as File;
        const {accepted, tooLarge} = splitBySize([file("a.pdf", 10), file("b.pdf", MAX_UPLOAD_BYTES + 1)]);
        expect(accepted.map(f => f.name)).toEqual(["a.pdf"]);
        expect(tooLarge.map(f => f.name)).toEqual(["b.pdf"]);
    });

    it("names a document by folder and file name", () => {
        expect(documentPath({folder: "Handbuch/Kapitel", fileName: "technik.pdf"})).toBe(
            "Handbuch/Kapitel/technik.pdf",
        );
        expect(documentPath({folder: "", fileName: "planung.pdf"})).toBe("planung.pdf");
    });

    it("sorts like a file tree: top level first, then by folder and name", () => {
        const document = (folder: string, fileName: string) => ({folder, fileName}) as TenantDocument;
        const sorted = sortByPath([
            document("Zentralhandbuch 2", "kraft.pdf"),
            document("", "notiz.txt"),
            document("Coaching", "b.pdf"),
            document("Coaching", "a.pdf"),
            document("Zentralhandbuch", "z.pdf"),
        ]);
        expect(sorted.map(documentPath)).toEqual([
            "notiz.txt",
            "Coaching/a.pdf",
            "Coaching/b.pdf",
            "Zentralhandbuch/z.pdf",
            "Zentralhandbuch 2/kraft.pdf",
        ]);
    });

    it("counts an import's outcomes", () => {
        const file = (outcome: ImportedFile["outcome"]) => ({outcome}) as ImportedFile;
        expect(countOutcomes([file("CREATED"), file("CREATED"), file("SKIPPED")])).toEqual({CREATED: 2, SKIPPED: 1});
        expect(countOutcomes([])).toEqual({});
    });

    it("counts ready, open and failed documents", () => {
        const document = (status: TenantDocument["status"]) => ({status}) as TenantDocument;
        expect(
            documentProgress([document("READY"), document("PENDING"), document("PROCESSING"), document("FAILED")]),
        ).toEqual({total: 4, ready: 1, waiting: 1, processing: 1, open: 2, failed: 1});
        expect(documentProgress([])).toEqual({total: 0, ready: 0, waiting: 0, processing: 0, open: 0, failed: 0});
    });

    describe("filterDocuments", () => {
        const doc = (folder: string, fileName: string, status: TenantDocument["status"]) =>
            ({folder, fileName, status}) as TenantDocument;
        const documents = [
            doc("Technik", "Handbuch.pdf", "READY"),
            doc("", "Preise.xlsx", "PENDING"),
            doc("Recht", "Vertrag.rtf", "FAILED"),
            doc("Technik", "Teile.csv", "PROCESSING"),
        ];

        it.each([
            ["all", ["Handbuch.pdf", "Preise.xlsx", "Vertrag.rtf", "Teile.csv"]],
            ["ready", ["Handbuch.pdf"]],
            ["open", ["Preise.xlsx", "Teile.csv"]],
            ["failed", ["Vertrag.rtf"]],
        ] as const)("keeps the %s documents", (filter, names) => {
            expect(filterDocuments(documents, "", filter).map(d => d.fileName)).toEqual(names);
        });

        it("searches folder and name, in any case", () => {
            expect(filterDocuments(documents, " technik ", "all").map(d => d.fileName)).toEqual([
                "Handbuch.pdf",
                "Teile.csv",
            ]);
            expect(filterDocuments(documents, "VERTRAG", "all").map(d => d.fileName)).toEqual(["Vertrag.rtf"]);
            expect(filterDocuments(documents, "technik", "open").map(d => d.fileName)).toEqual(["Teile.csv"]);
        });
    });

    it.each([
        ["Handbuch.pdf", "PDF"],
        ["preise.XLSX", "XLSX"],
        ["notiz", null],
        [".bashrc", null],
        ["archiv.backup", null],
    ])("labels %s as %s", (fileName, label) => {
        expect(fileTypeLabel(fileName)).toBe(label);
    });
});
