/** When a knowledge source runs by itself - a fixed list; MANUAL only by hand. */
export type SourceSchedule = "MANUAL" | "HOURLY" | "EVERY_3_HOURS" | "EVERY_6_HOURS" | "EVERY_12_HOURS" | "DAILY";

export const SOURCE_SCHEDULES: SourceSchedule[] = [
    "DAILY",
    "EVERY_12_HOURS",
    "EVERY_6_HOURS",
    "EVERY_3_HOURS",
    "HOURLY",
    "MANUAL",
];

/**
 * A folder of one of the tenant's file stores synced into its knowledge. lastError null: the last run worked;
 * lastSucceededAt: the last run that listed the whole tree; openFiles / openBytes: new and changed files not read yet;
 * running: a run is under way now.
 */
export interface KnowledgeSource {
    id: number;
    name: string;
    fileStoreId: number;
    fileStore: string | null;
    folder: string;
    areaId: number | null;
    area: string | null;
    pattern: string | null;
    schedule: SourceSchedule;
    active: boolean;
    nextRunAt: string | null;
    running: boolean;
    lastStartedAt: string | null;
    lastFinishedAt: string | null;
    lastError: string | null;
    lastAdded: number;
    lastChanged: number;
    lastDeleted: number;
    lastRefused: number;
    lastSkipped: number;
    lastSucceededAt: string | null;
    openFiles: number;
    openBytes: number;
}

/** folder empty: the store's folder; areaId null: the company knowledge; pattern left out: every file. */
export interface KnowledgeSourceRequest {
    name: string;
    fileStoreId: number;
    folder: string;
    areaId: number | null;
    pattern?: string;
    schedule: SourceSchedule;
    active: boolean;
}

/** A file of a source without a document: reason the refusal's code, null - its document was deleted by hand. */
export interface SourceFile {
    path: string;
    size: number;
    modified: string | null;
    reason: string | null;
}
