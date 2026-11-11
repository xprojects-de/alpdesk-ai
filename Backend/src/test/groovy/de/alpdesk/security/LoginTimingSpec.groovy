package de.alpdesk.security

import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.tenant.TenantUserService
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * docs/architecture/tenancy.md: an unknown username takes as long as a wrong password - and so does a user without a
 * password (signed in through WAM before, added from another tenant), or the answer's time tells the name exists.
 */
@MicronautTest(transactional = false)
class LoginTimingSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    TenantUserService users

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
    }

    def "a user without a password is refused in the time a wrong password takes"() {
        given:
        def tenant = api.tenant()
        def withPassword = api.user(tenant.id)
        def withoutPassword = users.createWithoutPassword(tenant.id as Long, "ohne-" + AiApi.unique(), "Ohne", true,
                false, null)
        users.authenticate(withPassword.username as String, "falsch", null)

        when:
        long wrongPassword = millis { users.authenticate(withPassword.username as String, "falsch", null) }
        long noPassword = millis { users.authenticate(withoutPassword.username(), "falsch", null) }

        then:
        noPassword >= wrongPassword / 4
    }

    private static long millis(Closure work) {
        long start = System.nanoTime()
        work()
        (System.nanoTime() - start).intdiv(1_000_000) as long
    }
}
