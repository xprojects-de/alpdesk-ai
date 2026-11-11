package de.alpdesk.security

import de.alpdesk.services.ai.AiApi
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.CountDownLatch

/**
 * The MCP server's tools run on Reactor's boundedElastic and can take minutes (an answer of the assistant, a task
 * awaited): the sign-in checks of every other request - a tenant user's, an API key's, an MCP key's - must not wait
 * behind them for a thread.
 */
@MicronautTest(transactional = false)
class SignInBesideBusyMcpSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
    }

    def "with every thread of boundedElastic busy, tenant users, API keys and MCP keys are still signed in"() {
        given:
        def tenant = api.tenant()
        String admin = api.tenantAdminToken(tenant.id)
        String apiKey = (api.call(admin, "POST", "/tenant/admin/api-keys", [name: "Software"]) as Map).plainKey
        String mcpKey = (api.call(admin, "POST", "/tenant/admin/mcp-keys", [name: "Claude"]) as Map).plainKey

        and: "every thread of boundedElastic held, as by long MCP calls"
        def release = new CountDownLatch(1)
        def held = (1..Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE).collect {
            Mono.fromRunnable { release.await() }.subscribeOn(Schedulers.boundedElastic()).subscribe()
        }

        when:
        def user = api.status(admin, "GET", "/tenant/account")
        def software = api.status(apiKey, "GET", "/api/v1/me")
        def mcp = status(mcpKey)

        then: "answered - not the client's read timeout"
        user == HttpStatus.OK
        software == HttpStatus.OK
        mcp != HttpStatus.UNAUTHORIZED

        cleanup:
        release.countDown()
        held*.dispose()
    }

    private HttpStatus status(String key) {
        try {
            client.toBlocking().exchange(HttpRequest.GET("/mcp").bearerAuth(key), String).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }
}
