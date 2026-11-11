import {clampPage, pageCount, pageOf, pageRange} from "./paging.util";

const rows = Array.from({length: 120}, (_, index) => index + 1);

describe("paging util", () => {
    it.each([
        [120, 50, 3],
        [100, 50, 2],
        [120, 100, 2],
        [120, "all", 1],
        [0, 50, 1],
    ] as const)("splits %i rows into pages of %s: %i pages", (total, size, pages) => {
        expect(pageCount(total, size)).toBe(pages);
    });

    it("gives the rows of a page", () => {
        expect(pageOf(rows, 0, 50)).toEqual(rows.slice(0, 50));
        expect(pageOf(rows, 2, 50)).toEqual(rows.slice(100, 120));
        expect(pageOf(rows, 0, "all")).toHaveLength(120);
    });

    it("keeps to the last page when the list shrinks below the page shown", () => {
        expect(clampPage(2, 60, 50)).toBe(1);
        expect(clampPage(5, 0, 50)).toBe(0);
        expect(pageOf(rows.slice(0, 60), 2, 50)).toEqual(rows.slice(50, 60));
    });

    it.each([
        [0, 120, 50, 1, 50],
        [2, 120, 50, 101, 120],
        [1, 120, 100, 101, 120],
        [0, 120, "all", 1, 120],
    ] as const)("names page %i of %i rows (pages of %s) %i-%i", (pageIndex, total, size, start, end) => {
        expect(pageRange(pageIndex, total, size)).toEqual({key: "paging.range", params: {start, end, total}});
    });

    it("names an empty list", () => {
        expect(pageRange(0, 0, 50)).toEqual({key: "paging.empty", params: {}});
    });
});
