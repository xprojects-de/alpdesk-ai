import {KnowledgeArea} from "../models/knowledge-area.model";

/** How the documents without an area are called (translation key): the company knowledge everyone finds. */
export const COMPANY_KNOWLEDGE_KEY = "knowledgeAreas.companyKnowledge";

/**
 * An area as shown: a translation key and its params - the template translates it. An area's own name, as the tenant
 * entered it, goes through the key "knowledgeAreas.name", which shows the name as it is.
 */
export interface AreaText {
    key: string;
    params: Record<string, string>;
}

function nameText(name: string): AreaText {
    return {key: "knowledgeAreas.name", params: {name}};
}

/**
 * Which documents the list shows and where uploads go: everything ("all", uploads into the company knowledge), the
 * company knowledge ("company") or one area (its id).
 */
export type AreaChoice = "all" | "company" | number;

/** The areas by name, as people read them (the browser's collation). */
export function sortAreas(areas: KnowledgeArea[]): KnowledgeArea[] {
    return [...areas].sort((a, b) => a.name.localeCompare(b.name));
}

/** A document's area as shown: its name, the company knowledge without one. */
export function areaLabel(areaId: number | null, areas: KnowledgeArea[]): AreaText {
    if (areaId === null) {
        return {key: COMPANY_KNOWLEDGE_KEY, params: {}};
    }
    const area = areas.find(candidate => candidate.id === areaId);
    return area ? nameText(area.name) : {key: "knowledgeAreas.unknown", params: {}};
}

/** The names of these areas, by name - "only the company knowledge" without one. */
export function areaNames(areaIds: number[], areas: KnowledgeArea[]): AreaText {
    const names = sortAreas(areas.filter(area => areaIds.includes(area.id))).map(area => area.name);
    return names.length === 0 ? {key: "knowledgeAreas.onlyCompanyKnowledge", params: {}} : nameText(names.join(", "));
}

/** Whether two selections hold the same areas, whatever their order. */
export function sameAreas(first: number[], second: number[]): boolean {
    return first.length === second.length && first.every(areaId => second.includes(areaId));
}

/** Whether a document of this area is in the choice. */
export function inAreaChoice(areaId: number | null, choice: AreaChoice): boolean {
    if (choice === "all") {
        return true;
    }
    return choice === "company" ? areaId === null : areaId === choice;
}

/** The area uploads go into with this choice: null (the company knowledge) unless one area is chosen. */
export function uploadAreaId(choice: AreaChoice): number | null {
    return typeof choice === "number" ? choice : null;
}

/** What the backend's deletion of uploads takes as area: "company" or the area's id; null with "all". */
export function deleteUploadsArea(choice: AreaChoice): string | null {
    if (choice === "all") {
        return null;
    }
    return choice === "company" ? "company" : String(choice);
}

/** The name of the choice as shown: the company knowledge or the area's name; "all areas" with "all". */
export function areaChoiceLabel(choice: AreaChoice, areas: KnowledgeArea[]): AreaText {
    if (choice === "all") {
        return {key: "knowledgeAreas.all", params: {}};
    }
    return areaLabel(choice === "company" ? null : choice, areas);
}

/** An area's name as sent: trimmed; null when empty or too long (at most 100 characters, as the backend). */
export function areaName(value: string): string | null {
    const name = value.trim();
    return name.length > 0 && name.length <= 100 ? name : null;
}
