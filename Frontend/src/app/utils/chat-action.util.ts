import {PendingAction} from "../models/chat.model";

/** The call's arguments as lines to read: "name: value", nested values as JSON. */
export function actionArguments(action: PendingAction): string[] {
    return Object.entries(action.arguments).map(
        ([name, value]) => `${name}: ${typeof value === "string" ? value : JSON.stringify(value)}`,
    );
}

/** What became of a decided call, as a translation key; null while it waits for the user. */
export function actionOutcome(action: PendingAction): string | null {
    switch (action.status) {
        case "PENDING":
            return null;
        case "RUN":
            return "chat.action.outcome.run";
        case "CANCELLED":
            return "chat.action.outcome.cancelled";
        case "EXPIRED":
            return "chat.action.outcome.expired";
    }
}
