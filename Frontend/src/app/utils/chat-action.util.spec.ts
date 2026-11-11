import {PendingAction} from "../models/chat.model";
import {actionArguments, actionOutcome} from "./chat-action.util";

describe("chat-action.util", () => {
    const action: PendingAction = {
        id: 1,
        software: "Buchhaltung",
        tool: "cancel_invoice",
        arguments: {number: "4711", reason: {code: 2}},
        status: "PENDING",
        result: null,
        createdAt: "",
    };

    it("lists the arguments as lines, nested values as JSON", () => {
        expect(actionArguments(action)).toEqual(["number: 4711", 'reason: {"code":2}']);
    });

    it.each([
        ["PENDING", null],
        ["RUN", "chat.action.outcome.run"],
        ["CANCELLED", "chat.action.outcome.cancelled"],
        ["EXPIRED", "chat.action.outcome.expired"],
    ] as const)("%s -> %s", (status, outcome) => {
        expect(actionOutcome({...action, status})).toBe(outcome);
    });
});
