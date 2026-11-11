package de.alpdesk.security;

import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;

/**
 * Headers on every answer - the frontend's pages, the API and errors alike. Set by the app, not the reverse proxy, so an
 * installation behind its own proxy (nginx under a sub-path, an appliance's reverse proxy where no response header can
 * be added) gets them too; the public Caddyfile adds only what the proxy alone can know: HSTS.
 * <ul>
 * <li>Never in a frame of another site (clickjacking: a hostile page lays its own buttons over "run" of a waiting
 * changing tool call). Both headers, the old and the current one, so every browser understands one of them.</li>
 * <li>The content type is final: the browser never sniffs a chat file or a document into a script.</li>
 * <li>Nothing here is for search engines - the login page, /swagger and the API's answers alike. A header instead of
 * a robots.txt "Disallow": that only stops crawling, and a URL found through a link can still be listed.</li>
 * <li>A streamed answer (server-sent events: the chat, the MCP server) is passed on as it comes: nginx and the proxies
 * built on it buffer a response until it ends unless the answer says "X-Accel-Buffering: no" - behind
 * them the chat's words would all arrive at once, and a quiet stream would be closed as idle (decided 24 October 2026).</li>
 * </ul>
 * An answer that sets its own Content-Security-Policy keeps it and its frame rule - only a handbook's website does
 * (DocSiteFilesController: sandboxed, framed by the app itself).
 */
@ServerFilter(ServerFilter.MATCH_ALL_PATTERN)
public class ResponseHeaders {

    @ResponseFilter
    public void add(MutableHttpResponse<?> response) {
        if (!response.getHeaders().contains("Content-Security-Policy")) {
            response.header("X-Frame-Options", "DENY");
            response.header("Content-Security-Policy", "frame-ancestors 'none'");
        }
        response.header("X-Content-Type-Options", "nosniff");
        response.header("X-Robots-Tag", "noindex, nofollow");
        if (response.getContentType().filter(MediaType.TEXT_EVENT_STREAM_TYPE::matches).isPresent()) {
            response.header("X-Accel-Buffering", "no");
        }
    }
}
