package de.alpdesk.security

import de.alpdesk.services.ai.AiApi
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource

/**
 * docs/architecture/tenancy.md "Password login": after app.login.max-attempts (5) wrong passwords in a row a tenant
 * user's account takes no password for app.login.lock-minutes (15) - at the login and at the switch to it. The answer
 * is the one of a wrong password; the lock ends by itself, with a password an admin sets, and is never prolonged by
 * the attempts it refuses.
 */
@MicronautTest(transactional = false)
class LoginLockoutSpec extends Specification {

    static final String WRONG = "falsch-aber-lang"

    @Inject
    @Client("/")
    HttpClient client

    /** Through the pool itself: outside a transaction the injected DataSource hands out no connection. */
    DataSource managementDatabase

    @Inject
    void setManagementDatabase(DataSource dataSource) {
        managementDatabase = DelegatingDataSource.unwrapDataSource(dataSource)
    }

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
    }

    def "five wrong passwords lock the account - the right one is refused like a wrong one until the lock ends"() {
        given:
        def user = api.user(api.tenant().id)

        when:
        5.times { assert login(user.username, WRONG) == HttpStatus.UNAUTHORIZED }

        then: "the right password is refused now"
        login(user.username, AiApi.USER_PASSWORD) == HttpStatus.UNAUTHORIZED

        when: "the lock has ended"
        expireLock(user.id)

        then:
        login(user.username, AiApi.USER_PASSWORD) == HttpStatus.OK
    }

    def "a right password in between starts the count anew"() {
        given:
        def user = api.user(api.tenant().id)

        when:
        4.times { login(user.username, WRONG) }
        login(user.username, AiApi.USER_PASSWORD)
        4.times { login(user.username, WRONG) }

        then:
        login(user.username, AiApi.USER_PASSWORD) == HttpStatus.OK
    }

    def "attempts while it is locked do not prolong the lock"() {
        given:
        def user = api.user(api.tenant().id)
        5.times { login(user.username, WRONG) }
        def locked = lockedUntil(user.id)

        when:
        3.times { login(user.username, WRONG) }

        then:
        locked != null
        lockedUntil(user.id) == locked
    }

    def "a password the admin sets lifts the lock"() {
        given:
        def tenant = api.tenant()
        def user = api.user(tenant.id)
        5.times { login(user.username, WRONG) }

        when:
        api.admin("PUT", "/admin/tenants/${tenant.id}/users/${user.id}",
                [username: user.username, displayName: "Maria", password: "neu-und-lang-genug"])

        then:
        login(user.username, "neu-und-lang-genug") == HttpStatus.OK
    }

    def "an unknown name counts on nobody - a user of that name in no tenant is not locked by it"() {
        given:
        def user = api.user(api.tenant().id)

        when:
        5.times { login(user.username + "x", WRONG) }

        then:
        login(user.username, AiApi.USER_PASSWORD) == HttpStatus.OK
    }

    def "the switch counts on the user switched to: wrong passwords there lock it, its own login too"() {
        given: "a person's users in two tenants, the second added by the admin from the first"
        def first = api.tenant()
        def second = api.tenant()
        def user = api.user(first.id)
        def added = api.admin("POST", "/admin/tenants/${second.id}/users/from-tenant", [userId: user.id]) as Map
        api.admin("PUT", "/admin/tenants/${second.id}/users/${added.id}",
                [username: user.username, displayName: "Maria", password: "zweites-passwort-1", groupIds: [api.standardGroup(second.id)]])

        when:
        5.times { assert switchTo(user.token as String, second.id, WRONG) == HttpStatus.BAD_REQUEST }

        then: "the right password of the second user is refused at the switch and at its tenant's login page"
        switchTo(user.token as String, second.id, "zweites-passwort-1") == HttpStatus.BAD_REQUEST
        login(user.username, "zweites-passwort-1", loginKey(second.id)) == HttpStatus.UNAUTHORIZED

        and: "the first user is not locked"
        login(user.username, AiApi.USER_PASSWORD, loginKey(first.id)) == HttpStatus.OK
    }

    private HttpStatus login(String username, String password, String loginKey = null) {
        def request = HttpRequest.POST("/login", [username: username, password: password])
        if (loginKey != null) {
            request = request.header("X-Login-Key", loginKey)
        }
        try {
            client.toBlocking().exchange(request, Map).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    private HttpStatus switchTo(String token, Object tenantId, String password) {
        try {
            client.toBlocking().exchange(HttpRequest.POST("/tenant/account/switch", [tenantId: tenantId, password: password])
                    .bearerAuth(token), Map).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    private String loginKey(Object tenantId) {
        (api.admin("GET", "/admin/tenants/${tenantId}/branding") as Map).loginKey
    }

    private Object lockedUntil(Object userId) {
        managementDatabase.connection.withCloseable { db ->
            def result = db.prepareStatement("SELECT locked_until FROM tenant_user WHERE id = ${userId}").executeQuery()
            result.next()
            result.getTimestamp(1)
        }
    }

    private void expireLock(Object userId) {
        managementDatabase.connection.withCloseable { db ->
            db.prepareStatement("UPDATE tenant_user SET locked_until = now() - interval '1 minute' WHERE id = ${userId}")
                    .executeUpdate()
        }
    }
}
