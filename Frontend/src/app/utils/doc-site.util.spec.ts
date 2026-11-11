import {DocSite} from "../models/doc-site.model";
import {
    ADDRESS_RENEWAL_MS,
    addressDue,
    isDocSiteUrl,
    isZip,
    maxZipMb,
    readerSiteLabel,
    refusedZip,
    siteLabel,
    siteSize,
    sortSites,
} from "./doc-site.util";

describe("doc-site.util", () => {
    const site = (id: number, areaId: number | null, areaName: string | null): DocSite => ({
        id,
        areaId,
        areaName,
        fileCount: 1,
        sizeBytes: 1,
        uploadedAt: "",
    });

    it("puts the website for everyone first, then the areas' by name", () => {
        const sorted = sortSites([site(3, 9, "Werkstatt"), site(2, 8, "Vertrieb"), site(1, null, null)]);
        expect(sorted.map(found => found.id)).toEqual([1, 2, 3]);
    });

    it("names a website for everyone by key and an area's by its name", () => {
        expect(siteLabel(site(1, null, null))).toEqual({key: "handbook.company", params: {}});
        expect(siteLabel(site(2, 8, "Vertrieb"))).toEqual({key: "knowledgeAreas.name", params: {name: "Vertrieb"}});
    });

    it("names the website for everyone 'General' for the user reading the handbook", () => {
        expect(readerSiteLabel(site(1, null, null))).toEqual({key: "handbook.general", params: {}});
        expect(readerSiteLabel(site(2, 8, "Vertrieb"))).toEqual({
            key: "knowledgeAreas.name",
            params: {name: "Vertrieb"},
        });
    });

    it("tells sizes in KB below a megabyte, else in MB", () => {
        expect(siteSize(100)).toEqual({key: "handbook.sizeKb", params: {value: "1"}});
        expect(siteSize(300 * 1024)).toEqual({key: "handbook.sizeKb", params: {value: "300"}});
        expect(siteSize(5.25 * 1024 * 1024)).toEqual({key: "handbook.sizeMb", params: {value: "5.3"}});
    });

    it("offers ZIP files only", () => {
        expect(isZip(new File([], "Handbuch.ZIP"))).toBe(true);
        expect(isZip(new File([], "index.html"))).toBe(false);
    });

    it("lets the iframe show a website's address below the API's base only", () => {
        expect(isDocSiteUrl("/alpdesk-ai/doc-sites/1.2.3.sig/", "/alpdesk-ai")).toBe(true);
        expect(isDocSiteUrl("http://localhost:19100/doc-sites/1.2.3.sig/", "http://localhost:19100")).toBe(true);
        expect(isDocSiteUrl("https://example.com/doc-sites/x/", "")).toBe(false);
        expect(isDocSiteUrl("/doc-sites/../tenant/account", "")).toBe(false);
        expect(isDocSiteUrl("javascript:alert(1)", "")).toBe(false);
    });

    it("renews the address by the clock - also when the computer slept past the hour", () => {
        const openedAt = 1_000_000;
        expect(addressDue(openedAt, openedAt + ADDRESS_RENEWAL_MS - 1)).toBe(false);
        expect(addressDue(openedAt, openedAt + ADDRESS_RENEWAL_MS)).toBe(true);
        expect(addressDue(openedAt, openedAt + 8 * 60 * 60 * 1000)).toBe(true);
    });

    it("refuses a file that is no ZIP, or a ZIP larger than the backend takes, before it is sent", () => {
        const mb = 1024 * 1024;
        const file = (name: string, size: number) => new File([new Uint8Array(size)], name);
        expect(refusedZip(file("handbuch.pdf", 10), 100 * mb)).toEqual({key: "handbook.upload.notZip", params: {}});
        expect(refusedZip(file("handbuch.zip", 2 * mb + 1), 2 * mb)).toEqual({
            key: "handbook.upload.tooLarge",
            params: {maxMb: "2"},
        });
        expect(refusedZip(file("handbuch.zip", 2 * mb), 2 * mb)).toBeNull();
        expect(refusedZip(file("Handbuch.ZIP", 3 * mb), null)).toBeNull();
        expect(maxZipMb(250 * mb)).toBe("250");
    });
});
