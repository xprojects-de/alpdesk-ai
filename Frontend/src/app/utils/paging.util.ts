/** How many rows a page of a list shows: 50, 100 or all of them. */
export type PageSize = 50 | 100 | "all";

/** The choices of a page size; `label` is a translation key. */
export const PAGE_SIZES: {value: PageSize; label: string}[] = [
    {value: 50, label: "paging.size50"},
    {value: 100, label: "paging.size100"},
    {value: "all", label: "paging.all"},
];

/** A text to translate: its key and parameters. */
export interface PageRangeLabel {
    key: string;
    params: Record<string, number>;
}

/** The number of pages - at least one, also for an empty list. */
export function pageCount(total: number, size: PageSize): number {
    return size === "all" ? 1 : Math.max(1, Math.ceil(total / size));
}

/** A page index that exists: a filter or a deletion can shrink the list below the page shown. */
export function clampPage(pageIndex: number, total: number, size: PageSize): number {
    return Math.min(Math.max(0, pageIndex), pageCount(total, size) - 1);
}

/** The rows of the page (its index is clamped first). */
export function pageOf<T>(items: T[], pageIndex: number, size: PageSize): T[] {
    if (size === "all") {
        return items;
    }
    const start = clampPage(pageIndex, items.length, size) * size;
    return items.slice(start, start + size);
}

/** "51–100 of 120" (key `paging.range`), "0 of 0" for an empty list (`paging.empty`). */
export function pageRange(pageIndex: number, total: number, size: PageSize): PageRangeLabel {
    if (total === 0) {
        return {key: "paging.empty", params: {}};
    }
    if (size === "all") {
        return {key: "paging.range", params: {start: 1, end: total, total}};
    }
    const start = clampPage(pageIndex, total, size) * size;
    return {key: "paging.range", params: {start: start + 1, end: Math.min(start + size, total), total}};
}
