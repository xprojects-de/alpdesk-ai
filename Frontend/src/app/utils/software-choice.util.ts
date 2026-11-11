import {ChoiceId, SoftwareChoice} from "../models/chat.model";

/**
 * The topics of the chat (roadmap point 51): the connected software the user reaches, each a chip above the input -
 * all on for a new conversation, switched off per conversation, the choice sent with every question. For a tenant
 * answering through its A2A agent the chips are the agent's skills ("skill:<id>", docs/architecture/a2a.md).
 */

const SKILL = "skill:";

/** The ids with this one switched: on becomes off, off becomes on. */
export function toggled(excluded: ChoiceId[], id: ChoiceId): ChoiceId[] {
    return excluded.includes(id) ? excluded.filter(other => other !== id) : [...excluded, id];
}

/** The chips show only with two or more topics - one or none needs no choice. */
export function showsSoftwareChoice(software: SoftwareChoice[]): boolean {
    return software.length > 1;
}

/**
 * What a question sends: the switched-off topics the user still reaches - one deleted or taken from the user's groups
 * since would be refused (400). With the chips not shown (the topics not loaded yet, or only one left) nothing: the
 * conversation's stored choice stays as it is.
 */
export function excludedToSend(excluded: ChoiceId[], software: SoftwareChoice[]): ChoiceId[] | undefined {
    if (!showsSoftwareChoice(software)) {
        return undefined;
    }
    return excluded.filter(id => software.some(choice => choice.id === id));
}

/**
 * The choice as the request carries it: connections by their ids, the agent's skills by theirs without "skill:" -
 * only the kind the chips show (nothing while they are not shown).
 */
export function choiceToSend(
    excluded: ChoiceId[],
    software: SoftwareChoice[],
): {excludedConnectionIds?: number[]; excludedSkillIds?: string[]} {
    const sent = excludedToSend(excluded, software);
    if (sent === undefined) {
        return {};
    }
    if (software.some(choice => typeof choice.id === "string")) {
        return {excludedSkillIds: sent.map(id => String(id).substring(SKILL.length))};
    }
    return {excludedConnectionIds: sent.filter((id): id is number => typeof id === "number")};
}

/** The stored choice of a conversation as the chips' ids. */
export function storedChoice(excludedConnectionIds: number[], excludedSkillIds: string[] | undefined): ChoiceId[] {
    return [...excludedConnectionIds, ...(excludedSkillIds ?? []).map(id => SKILL + id)];
}

/** Every topic switched off: the answer comes from the chat's own knowledge alone - the row says so. */
export function allSoftwareOff(excluded: ChoiceId[], software: SoftwareChoice[]): boolean {
    return showsSoftwareChoice(software) && software.every(choice => excluded.includes(choice.id));
}

/** How many topics answer - the phone's topics button counts them; a stored choice of one gone since counts nothing. */
export function topicsSwitchedOn(excluded: ChoiceId[], software: SoftwareChoice[]): number {
    return software.filter(choice => !excluded.includes(choice.id)).length;
}
