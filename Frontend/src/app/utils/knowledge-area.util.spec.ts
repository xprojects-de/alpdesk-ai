import {KnowledgeArea} from "../models/knowledge-area.model";
import {
    areaChoiceLabel,
    areaLabel,
    areaName,
    areaNames,
    deleteUploadsArea,
    inAreaChoice,
    sameAreas,
    sortAreas,
    uploadAreaId,
} from "./knowledge-area.util";

describe("knowledge-area.util", () => {
    const areas: KnowledgeArea[] = [
        {id: 2, name: "Werkstatt", createdAt: ""},
        {id: 1, name: "Vertrieb", createdAt: ""},
    ];

    it("sorts areas by name", () => {
        expect(sortAreas(areas).map(area => area.name)).toEqual(["Vertrieb", "Werkstatt"]);
    });

    it("names a document's area, the company knowledge without one", () => {
        expect(areaLabel(null, areas)).toEqual({key: "knowledgeAreas.companyKnowledge", params: {}});
        expect(areaLabel(2, areas)).toEqual({key: "knowledgeAreas.name", params: {name: "Werkstatt"}});
        expect(areaLabel(9, areas)).toEqual({key: "knowledgeAreas.unknown", params: {}});
    });

    it("lists assigned areas by name, or says only the company knowledge", () => {
        expect(areaNames([2, 1], areas)).toEqual({key: "knowledgeAreas.name", params: {name: "Vertrieb, Werkstatt"}});
        expect(areaNames([], areas)).toEqual({key: "knowledgeAreas.onlyCompanyKnowledge", params: {}});
    });

    it("filters by the choice and sends uploads and deletions to the chosen area", () => {
        expect(inAreaChoice(null, "all")).toBe(true);
        expect(inAreaChoice(1, "all")).toBe(true);
        expect(inAreaChoice(null, "company")).toBe(true);
        expect(inAreaChoice(1, "company")).toBe(false);
        expect(inAreaChoice(1, 1)).toBe(true);
        expect(inAreaChoice(null, 1)).toBe(false);

        expect(uploadAreaId("all")).toBeNull();
        expect(uploadAreaId("company")).toBeNull();
        expect(uploadAreaId(2)).toBe(2);

        expect(deleteUploadsArea("all")).toBeNull();
        expect(deleteUploadsArea("company")).toBe("company");
        expect(deleteUploadsArea(2)).toBe("2");

        expect(areaChoiceLabel("all", areas)).toEqual({key: "knowledgeAreas.all", params: {}});
        expect(areaChoiceLabel("company", areas)).toEqual({key: "knowledgeAreas.companyKnowledge", params: {}});
        expect(areaChoiceLabel(1, areas)).toEqual({key: "knowledgeAreas.name", params: {name: "Vertrieb"}});
    });

    it("compares selections of areas whatever their order", () => {
        expect(sameAreas([1, 2], [2, 1])).toBe(true);
        expect(sameAreas([], [])).toBe(true);
        expect(sameAreas([1], [1, 2])).toBe(false);
        expect(sameAreas([1, 3], [1, 2])).toBe(false);
    });

    it("takes an area's name trimmed, refuses an empty or too long one", () => {
        expect(areaName("  Vertrieb ")).toBe("Vertrieb");
        expect(areaName("   ")).toBeNull();
        expect(areaName("x".repeat(101))).toBeNull();
    });
});
