import {FileStore, FileStoreCheck} from "../../models/file-store.model";
import * as FileStoreActions from "./file-store.actions";
import {fileStoreReducer, initialState} from "./file-store.reducer";

describe("fileStoreReducer", () => {
    const store = (id: number, name: string, change: Partial<FileStore> = {}): FileStore => ({
        id,
        name,
        type: "SFTP",
        host: "sftp.example.com",
        port: 22,
        username: "check",
        basePath: "/logs",
        fingerprint: null,
        endpoint: null,
        region: null,
        bucket: null,
        accessKeyId: null,
        directoryId: null,
        clientId: null,
        siteUrl: null,
        library: null,
        checkedAt: null,
        checkError: null,
        taskWrite: false,
        security: null,
        mailbox: null,
        days: null,
        ...change,
    });
    const check = (status: FileStoreCheck["status"], checked: FileStore, entries: string[] = []): FileStoreCheck => ({
        status,
        fingerprint: "SHA256:" + "A".repeat(43),
        entries,
        errorCode: null,
        errorParams: {},
        store: checked,
    });

    function loaded(stores: FileStore[]) {
        const state = fileStoreReducer(initialState, FileStoreActions.loadStores({scope: 1}));
        return fileStoreReducer(state, FileStoreActions.loadStoresSuccess({scope: 1, stores}));
    }

    it("keeps the stores by name; a created or updated one takes its place", () => {
        let state = loaded([store(1, "Protokolle")]);
        state = fileStoreReducer(state, FileStoreActions.createStoreSuccess({scope: 1, store: store(2, "Ablage")}));
        expect(state.stores.map(s => s.name)).toEqual(["Ablage", "Protokolle"]);

        state = fileStoreReducer(state, FileStoreActions.updateStoreSuccess({scope: 1, store: store(1, "Zentrale")}));
        expect(state.stores.map(s => s.name)).toEqual(["Ablage", "Zentrale"]);
    });

    it("a check marks the store while it runs, then takes its outcome and - when it worked - the folder's names", () => {
        let state = loaded([store(1, "Protokolle")]);
        state = fileStoreReducer(state, FileStoreActions.checkStore({scope: 1, storeId: 1}));
        expect(state.checkingId).toBe(1);

        const checked = store(1, "Protokolle", {checkedAt: "2026-10-13T08:00:00Z"});
        state = fileStoreReducer(
            state,
            FileStoreActions.checkStoreSuccess({scope: 1, check: check("OK", checked, ["a.log"]), error: null}),
        );
        expect(state.checkingId).toBeNull();
        expect(state.stores[0].checkedAt).toBe("2026-10-13T08:00:00Z");
        expect(state.entries[1]).toEqual(["a.log"]);

        state = fileStoreReducer(
            state,
            FileStoreActions.checkStoreSuccess({scope: 1, check: check("CHANGED", checked), error: null}),
        );
        expect(state.entries[1]).toBeUndefined();
    });

    it("a confirmed fingerprint, a change or deleting drops the last listing", () => {
        let state = loaded([store(1, "Protokolle")]);
        state = fileStoreReducer(
            state,
            FileStoreActions.checkStoreSuccess({
                scope: 1,
                check: check("OK", store(1, "Protokolle"), ["a.log"]),
                error: null,
            }),
        );
        state = fileStoreReducer(
            state,
            FileStoreActions.confirmFingerprintSuccess({scope: 1, store: store(1, "Protokolle")}),
        );
        expect(state.entries[1]).toBeUndefined();

        state = fileStoreReducer(state, FileStoreActions.deleteStoreSuccess({scope: 1, storeId: 1}));
        expect(state.stores).toEqual([]);
    });

    it("forgets another tenant's stores when one is opened and ignores late results for it", () => {
        let state = loaded([store(1, "Protokolle")]);
        state = fileStoreReducer(state, FileStoreActions.loadStores({scope: 2}));
        expect(state.stores).toEqual([]);
        state = fileStoreReducer(
            state,
            FileStoreActions.loadStoresSuccess({scope: 1, stores: [store(1, "Protokolle")]}),
        );
        state = fileStoreReducer(state, FileStoreActions.createStoreSuccess({scope: 1, store: store(3, "Fremd")}));
        expect(state.stores).toEqual([]);
    });
});
