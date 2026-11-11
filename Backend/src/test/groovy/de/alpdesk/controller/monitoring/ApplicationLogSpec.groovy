package de.alpdesk.controller.monitoring

import de.alpdesk.logging.RecentLogAppender
import de.alpdesk.services.ai.AiApi
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

import java.time.Instant

/**
 * GET /admin/application-log: the warnings and errors the application logged, read from the RECENT appender of
 * logback.xml - the one logging configuration, so the appender is live in the specs too. Other specs log warnings
 * as well, so every assertion looks for this spec's own lines.
 */
@MicronautTest
class ApplicationLogSpec extends Specification {

    static final LOG = LoggerFactory.getLogger("de.alpdesk.ApplicationLogSpecLogger")

    @Inject
    @Client("/")
    HttpClient client

    @Shared
    String adminToken

    def setup() {
        if (!adminToken) {
            adminToken = client.toBlocking().retrieve(
                    HttpRequest.POST("/login", [username: "alpdesk", password: "alpdesk"]), Map).access_token
        }
    }

    def "warnings and errors are listed newest first with their stack trace, info lines are not kept"() {
        given:
        def marker = UUID.randomUUID().toString()
        LOG.info("info $marker")
        LOG.warn("warn {} from {}", marker, "a spec")
        LOG.error("error $marker", new IllegalStateException("cause $marker"))

        when:
        def response = recent(search: marker)

        then:
        response.entries*.level == ["ERROR", "WARN"]
        response.entries*.message == ["error $marker", "warn $marker from a spec"]
        response.entries*.logger == ["de.alpdesk.ApplicationLogSpecLogger"] * 2
        response.entries*.thread.every { it }
        response.entries*.timestamp.every { Instant.parse(it) }
        response.entries[0].stackTrace.startsWith("java.lang.IllegalStateException: cause $marker")
        response.entries[1].stackTrace == null

        and: "the admin learns how far back the buffer reaches"
        response.capacity == RecentLogAppender.installed().maxSize
        Instant.parse(response.startedAt) < Instant.now()
    }

    def "level ERROR leaves the warnings out, WARN and no level show everything"() {
        given:
        def marker = UUID.randomUUID().toString()
        LOG.warn("warn $marker")
        LOG.error("error $marker")

        expect:
        recent(search: marker, level: "ERROR").entries*.level == ["ERROR"]
        recent(search: marker, level: "WARN").entries*.level == ["ERROR", "WARN"]
        recent(search: marker).entries*.level == ["ERROR", "WARN"]
    }

    def "the search is case-insensitive and looks into logger, message and stack trace"() {
        given:
        def marker = UUID.randomUUID().toString()
        LoggerFactory.getLogger("de.alpdesk.Needle$marker").warn("by logger")
        LOG.warn("by MESSAGE $marker")
        LOG.error("by trace", new IllegalStateException("in the cause $marker"))

        expect:
        recent(search: "needle$marker").entries*.message == ["by logger"]
        recent(search: "by message $marker").entries*.message == ["by MESSAGE $marker"]
        recent(search: "the cause $marker").entries*.message == ["by trace"]
        recent(search: "nothing-$marker").entries == []
    }

    def "an unknown level is refused"() {
        when:
        recent(level: "DEBUG")

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
        e.response.getBody(Map).get().message == "level must be WARN or ERROR"
        e.response.getBody(Map).get().code == "applicationLog.levelInvalid"
    }

    def "only the admin reads the log - not a tenant admin, not without a login"() {
        given:
        def api = new AiApi(client)
        def tenant = api.tenant()

        expect:
        api.status(api.tenantAdminToken(tenant.id), "GET", "/admin/application-log") == HttpStatus.FORBIDDEN
        api.status(null, "GET", "/admin/application-log") == HttpStatus.UNAUTHORIZED
    }

    private Map recent(Map<String, String> query = [:]) {
        def params = query.collect { k, v -> "$k=${URLEncoder.encode(v, 'UTF-8')}" }.join("&")
        client.toBlocking().retrieve(HttpRequest.GET("/admin/application-log" + (params ? "?$params" : ""))
                .bearerAuth(adminToken), Map)
    }
}
