import {FileStore} from "../models/file-store.model";
import {
    buildFileStoreRequest,
    childFolder,
    fieldTexts,
    FileStoreFormValue,
    folderSteps,
    passwordNeeded,
    requiredFields,
    storeAddress,
    storeStatus,
} from "./file-store.util";

const store: FileStore = {
    id: 1,
    name: "Protokolle",
    type: "SFTP",
    host: "sftp.example.com",
    port: 22,
    username: "check",
    basePath: "/logs",
    fingerprint: "SHA256:" + "A".repeat(43),
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
};

const bucket: FileStore = {
    ...store,
    id: 2,
    type: "S3",
    host: null,
    port: null,
    username: null,
    fingerprint: null,
    endpoint: "https://s3.example.com",
    region: "us-east-1",
    bucket: "protokolle",
    accessKeyId: "CHECKACCESSKEY",
};

const library: FileStore = {
    ...store,
    id: 3,
    type: "SHAREPOINT",
    host: null,
    port: null,
    username: null,
    fingerprint: null,
    directoryId: "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
    clientId: "11111111-2222-3333-4444-555555555555",
    siteUrl: "https://musterfirma.sharepoint.com/sites/projekte",
    library: "Dokumente",
};

const noS3 = {
    endpoint: "",
    region: "",
    bucket: "",
    accessKeyId: "",
    directoryId: "",
    clientId: "",
    siteUrl: "",
    library: "",
    taskWrite: false,
    security: "SSL" as const,
    mailbox: "",
    days: null,
};

describe("a mailbox (IMAP)", () => {
    const mailbox: FileStore = {
        ...store,
        id: 4,
        type: "IMAP",
        host: "imap.example.com",
        port: 993,
        username: "belege@example.com",
        basePath: "/",
        fingerprint: null,
        security: "SSL",
        mailbox: "INBOX",
        days: 365,
    };
    const value = {
        name: " Belege ",
        type: "IMAP" as const,
        host: " imap.example.com ",
        port: null,
        username: " belege@example.com ",
        password: "",
        basePath: "/egal",
        ...noS3,
        taskWrite: true,
        mailbox: " INBOX/Belege ",
        days: 90,
    };

    it("sends its fields only - never a folder, never that tasks may write", () => {
        expect(buildFileStoreRequest(value)).toEqual({
            name: "Belege",
            type: "IMAP",
            host: "imap.example.com",
            username: "belege@example.com",
            security: "SSL",
            mailbox: "INBOX/Belege",
            days: 90,
            taskWrite: false,
        });
    });

    it("needs the password again for another server, port, user or way of reaching it - not for another folder", () => {
        const same = {...value, host: "imap.example.com", username: "belege@example.com", security: "SSL" as const};
        expect(passwordNeeded(mailbox, same)).toBe(false);
        expect(passwordNeeded(mailbox, {...same, mailbox: "INBOX/Andere"} as never)).toBe(false);
        expect(passwordNeeded(mailbox, {...same, host: "imap.example.org"})).toBe(true);
        expect(passwordNeeded(mailbox, {...same, username: "andere@example.com"})).toBe(true);
        expect(passwordNeeded(mailbox, {...same, security: "STARTTLS" as const})).toBe(true);
        expect(passwordNeeded(mailbox, {...same, port: 143})).toBe(true);
    });

    it("requires host and user, and shows the account with its folder", () => {
        expect(requiredFields("IMAP", "")).toEqual(["host", "username"]);
        expect(storeAddress(mailbox)).toBe("belege@example.com@imap.example.com · INBOX");
    });
});

describe("buildFileStoreRequest", () => {
    const form: FileStoreFormValue = {
        name: " Protokolle ",
        type: "SFTP",
        host: " sftp.example.com ",
        port: null,
        username: " check ",
        password: "",
        basePath: "  ",
        ...noS3,
    };

    it("trims the fields and leaves out an empty port, folder and password", () => {
        expect(buildFileStoreRequest(form)).toEqual({
            name: "Protokolle",
            type: "SFTP",
            host: "sftp.example.com",
            username: "check",
            taskWrite: false,
        });
    });

    it("sends whether tasks may write into the store", () => {
        expect(buildFileStoreRequest({...form, taskWrite: true}).taskWrite).toBe(true);
        expect(buildFileStoreRequest(form).taskWrite).toBe(false);
    });

    it("sends port and folder when given, and the password as typed", () => {
        const request = buildFileStoreRequest({...form, port: 2222, basePath: " /logs ", password: " geheim "});
        expect(request.port).toBe(2222);
        expect(request.basePath).toBe("/logs");
        expect(request.password).toBe(" geheim ");
    });

    it("sends only an S3 store's fields - an empty endpoint and region are left out", () => {
        const s3 = {
            ...form,
            type: "S3" as const,
            port: 22,
            bucket: " protokolle ",
            accessKeyId: " KEY ",
            password: "x",
        };
        expect(buildFileStoreRequest(s3)).toEqual({
            name: "Protokolle",
            type: "S3",
            taskWrite: false,
            bucket: "protokolle",
            accessKeyId: "KEY",
            password: "x",
        });
        const compatible = buildFileStoreRequest({...s3, endpoint: " https://s3.example.com ", region: " eu-west-1 "});
        expect(compatible.endpoint).toBe("https://s3.example.com");
        expect(compatible.region).toBe("eu-west-1");
    });

    it("sends only a SharePoint store's fields - an empty library is left out", () => {
        const sharePoint = {
            ...form,
            type: "SHAREPOINT" as const,
            host: "x",
            directoryId: " 0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0 ",
            clientId: " 11111111-2222-3333-4444-555555555555 ",
            siteUrl: " https://musterfirma.sharepoint.com/sites/projekte ",
            password: "geheim",
        };
        expect(buildFileStoreRequest(sharePoint)).toEqual({
            name: "Protokolle",
            type: "SHAREPOINT",
            taskWrite: false,
            directoryId: "0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0",
            clientId: "11111111-2222-3333-4444-555555555555",
            siteUrl: "https://musterfirma.sharepoint.com/sites/projekte",
            password: "geheim",
        });
        expect(buildFileStoreRequest({...sharePoint, library: " Archiv "}).library).toBe("Archiv");
    });
});

describe("fieldTexts", () => {
    it.each([
        ["SFTP" as const, "fileStores.dialog.password", "fileStores.dialog.basePathHint"],
        ["S3" as const, "fileStores.dialog.secretKey", "fileStores.dialog.basePathHintS3"],
        ["SHAREPOINT" as const, "fileStores.dialog.clientSecret", "fileStores.dialog.basePathHintSharePoint"],
    ])("%s names its secret %s and its folder %s", (type, secret, folderHint) => {
        expect(fieldTexts(type).secret).toBe(secret);
        expect(fieldTexts(type).folderHint).toBe(folderHint);
        expect(fieldTexts(type).secretAgain).toMatch(/^fileStores\.dialog\.\w+Again$/);
    });
});

describe("requiredFields", () => {
    it.each([
        ["SFTP" as const, "", ["host", "username"]],
        ["S3" as const, "", ["bucket", "accessKeyId", "region"]],
        ["S3" as const, "https://s3.example.com", ["bucket", "accessKeyId"]],
        ["SHAREPOINT" as const, "", ["directoryId", "clientId", "siteUrl"]],
    ])("%s with endpoint '%s' requires %o - Amazon S3 its region", (type, endpoint, fields) => {
        expect(requiredFields(type, endpoint)).toEqual(fields);
    });
});

describe("storeAddress", () => {
    it("shows a server's host and port, a bucket at its endpoint or at Amazon S3 in its region", () => {
        expect(storeAddress(store)).toBe("sftp.example.com:22");
        expect(storeAddress(bucket)).toBe("protokolle · https://s3.example.com");
        expect(storeAddress({...bucket, endpoint: null, region: "eu-central-1"})).toBe(
            "protokolle · Amazon S3 eu-central-1",
        );
        expect(storeAddress(library)).toBe("https://musterfirma.sharepoint.com/sites/projekte · Dokumente");
        expect(storeAddress({...library, library: null})).toBe("https://musterfirma.sharepoint.com/sites/projekte");
    });
});

describe("passwordNeeded", () => {
    it("asks for it for a new store", () => {
        expect(passwordNeeded(null, {type: "SFTP", host: "x", port: null, ...noS3})).toBe(true);
    });

    it("an emptied port is the type's own - another server than a store on port 2222", () => {
        expect(
            passwordNeeded({...store, port: 2222}, {type: "SFTP", host: "sftp.example.com", port: null, ...noS3}),
        ).toBe(true);
    });

    it.each([
        [{type: "SFTP" as const, host: "sftp.example.com", port: null}, false],
        [{type: "SFTP" as const, host: " SFTP.example.com ", port: 22}, false],
        [{type: "FTPS" as const, host: "sftp.example.com", port: 22}, true],
        [{type: "SFTP" as const, host: "other.example.com", port: 22}, true],
        [{type: "SFTP" as const, host: "sftp.example.com", port: 2222}, true],
    ])("%o -> %s - only another server needs it again", (value, expected) => {
        expect(passwordNeeded(store, {...value, ...noS3})).toBe(expected);
    });

    const same = {
        type: "S3" as const,
        host: "",
        port: null,
        endpoint: "HTTPS://S3.example.com/",
        region: "",
        bucket: "protokolle",
        accessKeyId: "CHECKACCESSKEY",
        directoryId: "",
        clientId: "",
    };

    it.each([
        [{}, false],
        [{region: "US-East-1"}, false],
        [{endpoint: "https://other.example.com"}, true],
        [{endpoint: "", region: "us-east-1"}, true],
        [{region: "eu-west-1"}, true],
        [{bucket: "archiv"}, true],
        [{accessKeyId: "OTHERKEY"}, true],
        [{type: "SFTP" as const}, true],
    ])("an S3 store changed by %o -> %s - only another store needs the secret again", (change, expected) => {
        expect(passwordNeeded(bucket, {...same, ...change})).toBe(expected);
    });

    const sameApp = {
        ...noS3,
        type: "SHAREPOINT" as const,
        host: "",
        port: null,
        directoryId: " 0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0 ",
        clientId: "11111111-2222-3333-4444-555555555555",
    };

    it.each([
        [{}, false],
        [{siteUrl: "https://musterfirma.sharepoint.com/sites/archiv"}, false],
        [{directoryId: "99999999-9999-9999-9999-999999999999"}, true],
        [{clientId: "99999999-9999-9999-9999-999999999999"}, true],
        [{type: "S3" as const}, true],
    ])("a SharePoint store changed by %o -> %s - only another app needs the secret again", (change, expected) => {
        expect(passwordNeeded(library, {...sameApp, ...change})).toBe(expected);
    });
});

describe("storeStatus", () => {
    it.each([
        [{checkedAt: null}, null, "fileStores.status.unchecked"],
        [{checkedAt: "2026-10-13T08:00:00Z", checkError: null}, "ok", "fileStores.status.ok"],
        [
            {checkedAt: "2026-10-13T08:00:00Z", checkError: "fileStore.fingerprintUnconfirmed"},
            "warn",
            "fileStores.status.unconfirmed",
        ],
        [
            {checkedAt: "2026-10-13T08:00:00Z", checkError: "fileStore.fingerprintChanged"},
            "err",
            "fileStores.status.changed",
        ],
        [{checkedAt: "2026-10-13T08:00:00Z", checkError: "fileStore.loginFailed"}, "err", "fileStores.status.failed"],
    ])("%o -> %s %s", (change, chip, label) => {
        expect(storeStatus({...store, ...change})).toEqual({chip, label});
    });
});

describe("folderSteps", () => {
    it("names each step of the path with the path up to it", () => {
        expect(folderSteps("Projekte/2025")).toEqual([
            {name: "Projekte", path: "Projekte"},
            {name: "2025", path: "Projekte/2025"},
        ]);
        expect(folderSteps("")).toEqual([]);
    });

    it("joins a subfolder to its folder", () => {
        expect(childFolder("", "Projekte")).toBe("Projekte");
        expect(childFolder("Projekte", "2025")).toBe("Projekte/2025");
    });
});
