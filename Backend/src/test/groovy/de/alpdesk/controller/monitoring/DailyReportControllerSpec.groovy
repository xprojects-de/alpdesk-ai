package de.alpdesk.controller.monitoring

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/**
 * POST /admin/daily-report/send without mail settings (the specs' default): 409 with its code, so the admin learns
 * what is missing. Sending with settings is in DailyReportSpec, a refusing mail server in DailyReportJobSpec.
 */
@MicronautTest
class DailyReportControllerSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    def "without a mail server the report is refused - 409 naming what to set"() {
        given:
        def token = client.toBlocking().retrieve(
                HttpRequest.POST("/login", [username: "alpdesk", password: "alpdesk"]), Map).access_token

        when:
        client.toBlocking().exchange(HttpRequest.POST("/admin/daily-report/send", [:]).bearerAuth(token), Map)

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.CONFLICT
        def body = e.response.getBody(Map).orElseThrow()
        body.code == "dailyReport.notConfigured"
        body.message.contains("APP_MAIL_HOST")
    }
}
