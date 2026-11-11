package de.alpdesk.security

import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/**
 * No page or answer of the app may be framed by another site, no content type is sniffed, nothing is indexed - and only
 * a streamed answer tells an nginx-based proxy not to buffer it (ChatSpec: the chat's stream carries it).
 */
@MicronautTest
class ResponseHeadersSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    def "every answer forbids framing, sniffing and indexing: #path"() {
        when:
        def headers = headersOf(path)

        then:
        headers["X-Frame-Options"] == "DENY"
        headers["Content-Security-Policy"] == "frame-ancestors 'none'"
        headers["X-Content-Type-Options"] == "nosniff"
        headers["X-Robots-Tag"] == "noindex, nofollow"
        headers["X-Accel-Buffering"] == null

        where:
        path << ["/watchdog", "/login", "/branding", "/swagger", "/admin/tenants", "/mcp", "/does-not-exist",
                 "/doc-sites/no-token/index.html"]
    }

    /** The headers of the answer - also of a refused or failed request. */
    private Map<String, String> headersOf(String path) {
        try {
            return client.toBlocking().exchange(HttpRequest.GET(path), String).headers.asMap(String, String)
        } catch (HttpClientResponseException e) {
            return e.response.headers.asMap(String, String)
        }
    }
}
