import {HttpRequest} from "@angular/common/http";
import {isLongRunning} from "./timeout.interceptor";

describe("isLongRunning", () => {
    it.each([
        ["POST", "/alpdesk-ai/tenant/chat/stream", true],
        ["POST", "/tenant/chat/actions/42/run", true],
        ["POST", "/tenant/chat/actions/42/cancel", true],
        ["PUT", "/tenant/admin/embedding-model", true],
        ["PUT", "/alpdesk-ai/admin/tenants/7/embedding-model", true],
        ["POST", "/tenant/admin/mcp-connections", true],
        ["POST", "/admin/tenants/7/mcp-connections", true],
        ["POST", "/tenant/admin/mcp-connections/3/reload", true],
        ["PUT", "/tenant/admin/mcp-connections/3", true],
        ["PUT", "/tenant/admin/mcp-connections/3/tools/9", false],
        ["GET", "/tenant/admin/mcp-connections", false],
        ["GET", "/tenant/chat/conversations", false],
        ["POST", "/tenant/chat/actions/42/other", false],
    ])("%s %s -> %s", (method, url, long) => {
        expect(isLongRunning(new HttpRequest(method as "POST", url, null))).toBe(long);
    });

    it("an upload is long running", () => {
        expect(isLongRunning(new HttpRequest("POST", "/tenant/admin/documents", new FormData()))).toBe(true);
    });
});
