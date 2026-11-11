import {ApplicationLogResponse} from "../../models/application-log.model";
import * as ApplicationLogActions from "./application-log.actions";
import {applicationLogReducer, initialState} from "./application-log.reducer";

const response: ApplicationLogResponse = {
    entries: [
        {
            timestamp: "2026-10-03T06:00:00Z",
            level: "ERROR",
            logger: "de.alpdesk.Test",
            thread: "main",
            message: "boom",
            stackTrace: null,
        },
    ],
    capacity: 1000,
    startedAt: "2026-10-02T05:00:00Z",
};

describe("applicationLogReducer", () => {
    it("keeps the filter of the running load and shows the progress bar until it answers", () => {
        let state = applicationLogReducer(
            initialState,
            ApplicationLogActions.loadEntries({filter: {level: "ERROR", search: "boom"}}),
        );
        expect(state.loading).toBe(true);
        expect(state.filter).toEqual({level: "ERROR", search: "boom"});
        state = applicationLogReducer(state, ApplicationLogActions.loadEntriesSuccess({response}));
        expect(state.loading).toBe(false);
        expect(state.entries).toEqual(response.entries);
        expect(state.capacity).toBe(1000);
        expect(state.startedAt).toBe("2026-10-02T05:00:00Z");
    });

    it("a failed load keeps the last entries and ends the progress bar", () => {
        let state = applicationLogReducer(initialState, ApplicationLogActions.loadEntriesSuccess({response}));
        state = applicationLogReducer(state, ApplicationLogActions.loadEntries({filter: {level: null, search: ""}}));
        state = applicationLogReducer(state, ApplicationLogActions.loadEntriesFailure({error: "offline"}));
        expect(state.loading).toBe(false);
        expect(state.entries).toEqual(response.entries);
    });

    it("shows the report as being sent until it was sent or refused", () => {
        let state = applicationLogReducer(initialState, ApplicationLogActions.sendDailyReport());
        expect(state.sending).toBe(true);
        expect(
            applicationLogReducer(state, ApplicationLogActions.sendDailyReportSuccess({recipients: 2})).sending,
        ).toBe(false);
        state = applicationLogReducer(state, ApplicationLogActions.sendDailyReportFailure({error: "no mail server"}));
        expect(state.sending).toBe(false);
    });
});
