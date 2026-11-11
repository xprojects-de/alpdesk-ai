/** A warning or error of the backend, from its in-memory buffer (GET /admin/application-log). */
export interface ApplicationLogEntry {
    timestamp: string;
    level: "WARN" | "ERROR";
    logger: string;
    thread: string;
    message: string;
    stackTrace: string | null;
}

export interface ApplicationLogResponse {
    /** Newest first. */
    entries: ApplicationLogEntry[];
    /** How many entries the buffer keeps - older ones are only in the container log. */
    capacity: number;
    /** When the backend started: the buffer holds nothing from before. */
    startedAt: string;
}

/** level null: warnings and errors; "ERROR": errors only. */
export interface ApplicationLogFilter {
    level: "ERROR" | null;
    search: string;
}
