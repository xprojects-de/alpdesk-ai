package de.alpdesk.services.mcp;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/**
 * A JSON-RPC error answering a resources request of the MCP server goes out with HTTP 200 (decided 12 November 2026,
 * docs/architecture/mcp.md "Resources of the software"). Micronaut MCP's controller gives every JSON-RPC error an HTTP
 * status - 400 or 500 - and the official SDKs' clients take that for a broken server and end their whole session, the
 * tools with it. For a tool we answer a tool error instead; a resource has no such way: "not found" can only be a
 * JSON-RPC error, and an address a model guessed wrong must not cost the client its session. The error itself is
 * unchanged. Every other request keeps the status the controller gave it. The specification names no HTTP status for
 * such an answer (2025-11-25 and 2026-07-28 alike); to be checked again when Zirmo moves to 2026-07-28.
 */
@ServerFilter("/mcp")
public class ResourceErrorStatus {

    @ResponseFilter
    public void answered(HttpRequest<?> request, MutableHttpResponse<?> response) {
        if (response.body() instanceof McpSchema.JSONRPCResponse answer && answer.error() != null
                && isResourceRequest(request)) {
            response.status(HttpStatus.OK);
        }
    }

    private static boolean isResourceRequest(HttpRequest<?> request) {
        return request.getBody(Map.class)
                .map(message -> message.get("method") instanceof String method && method.startsWith("resources/"))
                .orElse(false);
    }
}
