import {DocSite} from "../models/doc-site.model";
import {AreaText} from "./knowledge-area.util";

/** The whole tenant's website first, then the areas' by name. */
export function sortSites(sites: DocSite[]): DocSite[] {
    return [...sites].sort((a, b) => {
        if (a.areaId === null || b.areaId === null) {
            return a.areaId === null ? (b.areaId === null ? 0 : -1) : 1;
        }
        return (a.areaName ?? "").localeCompare(b.areaName ?? "");
    });
}

/** The website as shown: the whole tenant's ("Firmenhandbuch"), or its area's name as the tenant entered it. */
export function siteLabel(site: DocSite): AreaText {
    return site.areaId === null
        ? {key: "handbook.company", params: {}}
        : {key: "knowledgeAreas.name", params: {name: site.areaName ?? ""}};
}

/** As the user reading the handbook sees it: the website for everyone is "General" beside the areas'. */
export function readerSiteLabel(site: DocSite): AreaText {
    return site.areaId === null ? {key: "handbook.general", params: {}} : siteLabel(site);
}

/** A size for people: KB below a megabyte, else MB with one decimal. */
export function siteSize(bytes: number): AreaText {
    if (bytes < 1024 * 1024) {
        return {key: "handbook.sizeKb", params: {value: String(Math.max(1, Math.round(bytes / 1024)))}};
    }
    return {key: "handbook.sizeMb", params: {value: (bytes / (1024 * 1024)).toFixed(1)}};
}

/** Only a ZIP is offered for upload - the backend checks the content itself. */
export function isZip(file: File): boolean {
    return file.name.toLowerCase().endsWith(".zip");
}

/** The ZIP's limit in whole megabytes, as the backend names it. */
export function maxZipMb(maxZipBytes: number): string {
    return String(Math.floor(maxZipBytes / (1024 * 1024)));
}

/**
 * Why the chosen file is not uploaded: no ZIP, or larger than the backend takes - refused before it is sent, as the
 * backend would read it whole first. maxZipBytes null (not known yet): only the backend checks.
 */
export function refusedZip(file: File, maxZipBytes: number | null): AreaText | null {
    if (!isZip(file)) {
        return {key: "handbook.upload.notZip", params: {}};
    }
    if (maxZipBytes !== null && file.size > maxZipBytes) {
        return {key: "handbook.upload.tooLarge", params: {maxMb: maxZipMb(maxZipBytes)}};
    }
    return null;
}

/**
 * Whether the address is one of a handbook's websites below the API's base - the only address the iframe is given.
 * Set as the iframe's src by hand (no binding that would have to bypass Angular's sanitizer): checked here instead.
 */
export function isDocSiteUrl(url: string, apiUrl: string): boolean {
    return url.startsWith(`${apiUrl}/doc-sites/`) && !url.includes("..");
}

/**
 * When the handbook takes a new address for the website shown: an address holds for an hour (the backend's
 * DocSiteService.SESSION), so a link clicked after it would open nothing - five minutes before, the website is loaded
 * again at its first page instead.
 */
export const ADDRESS_RENEWAL_MS = 55 * 60 * 1000;

/** How often the handbook looks whether its address is due - by the clock, so also right after the computer slept. */
export const ADDRESS_CHECK_MS = 60 * 1000;

/**
 * Whether the address fetched at openedAt (Date.now()) is to be renewed now. Measured by the clock, not by a timer
 * over the whole time: a browser's timer stands still while the computer sleeps, the address's hour does not.
 */
export function addressDue(openedAt: number, now: number): boolean {
    return now - openedAt >= ADDRESS_RENEWAL_MS;
}
