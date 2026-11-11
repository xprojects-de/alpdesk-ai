package de.alpdesk.controller.common

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/**
 * The origins whose pages may call the API from the browser (application.properties, APP_CORS_ORIGINS): a frontend of
 * the tenant's own on another origin, listed exactly - and never with credentials, so a WAM proxy's session cookie
 * never goes along with a request of another page.
 */
@MicronautTest
@Property(name = "app.cors.origins", value = "https://chat.example.com,https://intranet.example.com")
class CorsSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    def "a listed origin may sign in and chat from the browser - without credentials: #origin"() {
        when:
        def response = client.toBlocking().exchange(preflight(origin, "POST", "/login"))

        then:
        response.status == HttpStatus.OK
        response.header("Access-Control-Allow-Origin") == origin
        response.header("Access-Control-Allow-Credentials") == null

        where:
        origin << ["https://chat.example.com", "https://intranet.example.com"]
    }

    // http://localhost:4300 cannot be checked here: Micronaut lets a localhost origin through to a server on localhost.
    def "an origin not listed is refused: #origin"() {
        when:
        client.toBlocking().exchange(preflight(origin, "POST", "/tenant/chat/stream"))

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN

        where:
        origin << ["https://evil.example.com", "https://chat.example.com.evil.example", "http://chat.example.com"]
    }

    private static HttpRequest<?> preflight(String origin, String method, String path) {
        HttpRequest.create(HttpMethod.OPTIONS, path)
                .header("Origin", origin)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", "Authorization,Content-Type,X-Login-Key")
    }
}
