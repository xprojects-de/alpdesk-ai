package de.alpdesk.security

import com.nimbusds.jwt.JWTParser
import de.alpdesk.entities.tenant.TenantUser
import de.alpdesk.repositories.tenant.TenantUserRepository
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import java.time.Instant

/**
 * A tenant's employees: created by the admin only, logging in through the same /login as the admin, reaching their
 * own area (/tenant/**) and nothing else - and losing access at once when they or their tenant are switched off.
 */
@MicronautTest(transactional = false)
class TenantUserSpec extends Specification {

    static final String PASSWORD = "geheim-genug-123"

    @Inject
    de.alpdesk.services.tenant.UserGroupService groups

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    TenantUserRepository users

    @Shared
    String adminToken

    def setup() {
        if (!adminToken) {
            adminToken = login("alpdesk", "alpdesk").access_token
        }
    }

    def "without WAM switched on the proxy's header counts for nothing"() {
        given:
        def tenant = createTenant()
        def username = uniqueName()
        admin("POST", "/admin/tenants/${tenant.id}/users", [username: username, displayName: "Maria", password: PASSWORD])
        def token = login(username, PASSWORD).access_token

        expect: "no sign-in through it, and a token works whatever user it names"
        client.toBlocking().retrieve(HttpRequest.GET("/login/wam"), Map) == [enabled: false, groupsChecked: false]
        statusOf(HttpRequest.POST("/login/wam", [:]).header("X-Remote-User", username)) == HttpStatus.NOT_FOUND
        statusOf(HttpRequest.GET("/tenant/account").bearerAuth(token).header("X-Remote-User", "jemand-anders")) == HttpStatus.OK

        and: "a user still needs a password"
        statusOf(HttpRequest.POST("/admin/tenants/${tenant.id}/users", [username: uniqueName(), displayName: "Ohne"])
                .bearerAuth(adminToken)) == HttpStatus.BAD_REQUEST
    }

    private HttpStatus statusOf(HttpRequest<?> request) {
        try {
            client.toBlocking().exchange(request, Map).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    def "the admin creates a user, who logs in and sees its account and tenant"() {
        given:
        def tenant = createTenant()
        def username = uniqueName()

        when:
        def created = admin("POST", "/admin/tenants/${tenant.id}/users",
                [username: username, displayName: "Maria Huber", password: PASSWORD])

        then:
        created.username == username
        created.active == true
        !created.containsKey("password") && !created.containsKey("passwordHash")

        when:
        def response = login(username.toUpperCase(), PASSWORD)
        def claims = JWTParser.parse(response.access_token).JWTClaimsSet

        then: "the username is case-insensitive, the JWT carries the tenant user's role, tenant and user"
        response.roles == [Roles.TENANT_USER]
        claims.getClaim("tenantId") == tenant.id
        claims.getClaim("userId") == created.id

        and:
        def account = get("/tenant/account", response.access_token)
        account.findAll { it.key != "tenants" } ==
                [userId: created.id, username: username, displayName: "Maria Huber", tenantId: tenant.id, tenantName: tenant.name,
                 admin: false, language: "en", mcpServer: false, tasksAllowed: false, answersThroughAgent: false]

        and: "the one tenant it may switch to is its own"
        (account.tenants as List<Map>).collect { [id: it.id, name: it.name] } == [[id: tenant.id, name: tenant.name]]

        and: "the login was recorded"
        list(tenant.id)[0].lastLoginAt != null
    }

    def "a tenant user reaches neither the admin API nor the client software's API"() {
        given:
        def token = tokenOfNewUser(createTenant())

        expect:
        status(token, method, path) == HttpStatus.FORBIDDEN

        where:
        method | path
        "GET"  | "/admin/tenants"
        "POST" | "/admin/tenants"
        "GET"  | "/admin/tenants/1/users"
        "POST" | "/admin/tenants/1/users"
        "GET"  | "/admin/tenants/1/models"
        "PUT"  | "/admin/tenants/1/models"
        "GET"  | "/admin/tenants/1/sandbox"
        "GET"  | "/version"
        "GET"  | "/admin/models"
        "POST" | "/admin/models"
        "GET"  | "/admin/provider-keys"
        "PUT"  | "/admin/provider-keys/OPENAI"
        "GET"  | "/admin/usage"
        "GET"  | "/api/v1/me"
        "GET"  | "/api/v1/documents"
        "POST" | "/api/v1/chat"
        "POST" | "/api/v1/chat/stream"
    }

    def "the admin is no tenant user"() {
        expect:
        status(adminToken, "GET", "/tenant/account") == HttpStatus.FORBIDDEN
    }

    def "invalid input is refused: #reason"() {
        given:
        def tenant = createTenant()

        when:
        admin("POST", "/admin/tenants/${tenant.id}/users", body)

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        reason               | body
        "no username"        | [displayName: "A", password: PASSWORD]
        "username too short" | [username: "ab", displayName: "A", password: PASSWORD]
        "space in username"  | [username: "maria huber", displayName: "A", password: PASSWORD]
        "no name"            | [username: "maria", displayName: " ", password: PASSWORD]
        "no password"        | [username: "maria", displayName: "A"]
        "password too short" | [username: "maria", displayName: "A", password: "kurz"]
        "language not a tag" | [username: "maria", displayName: "A", password: PASSWORD, language: "Deutsch"]
    }

    def "the admin sets a user's language - English when left out, unchanged on an update without it"() {
        given:
        def tenant = createTenant()

        when:
        def english = admin("POST", "/admin/tenants/${tenant.id}/users", [username: uniqueName(), displayName: "A", password: PASSWORD])
        def german = admin("POST", "/admin/tenants/${tenant.id}/users",
                [username: uniqueName(), displayName: "B", password: PASSWORD, language: "de"])

        then:
        english.language == "en"
        german.language == "de"

        when: "an update without a language keeps it, one with a language replaces it"
        def kept = admin("PUT", "/admin/tenants/${tenant.id}/users/${german.id}", [username: german.username, displayName: "B2"])
        def changed = admin("PUT", "/admin/tenants/${tenant.id}/users/${english.id}",
                [username: english.username, displayName: "A", language: "pt-BR"])

        then:
        kept.language == "de"
        changed.language == "pt-BR"
        list(tenant.id).collectEntries { [it.username, it.language] } == [(german.username): "de", (english.username): "pt-BR"]
    }

    def "a user from before the language column speaks English - its account and the user list say so"() {
        given: "a user whose row has no language, as V4 leaves the users of an older database"
        def tenant = createTenant()
        def username = uniqueName()
        def now = Instant.now()
        def old = users.save(new TenantUser(null, tenant.id as Long, username, "Alt", PasswordHasher.hash(PASSWORD), true,
                false, now, now, null, null, null, null))
        groups.joinStandard(old.tenantId(), old.id())

        expect:
        get("/tenant/account", login(username, PASSWORD).access_token).language == "en"
        list(tenant.id).find { it.username == username }.language == "en"
    }

    def "a user changes its own language - its account shows it, no one else's changes"() {
        given:
        def tenant = createTenant()
        def username = uniqueName()
        admin("POST", "/admin/tenants/${tenant.id}/users", [username: username, displayName: "A", password: PASSWORD, language: "de"])
        def token = login(username, PASSWORD).access_token
        def colleague = admin("POST", "/admin/tenants/${tenant.id}/users",
                [username: uniqueName(), displayName: "B", password: PASSWORD, language: "de"])

        expect:
        get("/tenant/account", token).language == "de"
        status(token, "PUT", "/tenant/account/language", [language: "en"]) == HttpStatus.NO_CONTENT
        get("/tenant/account", token).language == "en"
        list(tenant.id).find { it.id == colleague.id }.language == "de"

        and: "the change keeps the session - unlike a new password"
        status(token, "GET", "/tenant/account") == HttpStatus.OK
    }

    def "a user's own language is refused unless a language tag: #reason"() {
        given:
        def token = tokenOfNewUser(createTenant())

        expect:
        status(token, "PUT", "/tenant/account/language", body) == HttpStatus.BAD_REQUEST
        get("/tenant/account", token).language == "en"

        where:
        reason          | body
        "none"          | [:]
        "empty"         | [language: ""]
        "a name"        | [language: "Deutsch"]
        "upper case"    | [language: "DE"]
        "too long"      | [language: "de-abcdefgh-abcdefgh-ab"]
        "a script"      | [language: "<b>"]
    }

    def "the admin has no account language to change"() {
        expect:
        status(adminToken, "PUT", "/tenant/account/language", [language: "de"]) == HttpStatus.FORBIDDEN
    }

    def "a username is unique per tenant, regardless of case - the same person may have a user in several; the admin's is taken everywhere"() {
        given:
        def username = uniqueName()
        def tenant = createTenant()
        admin("POST", "/admin/tenants/${tenant.id}/users", [username: username, displayName: "A", password: PASSWORD])

        expect:
        createStatus(tenant.id, username.toUpperCase()) == HttpStatus.CONFLICT
        createStatus(createTenant().id, username.toUpperCase()) == HttpStatus.CREATED
        createStatus(createTenant().id, "ALPDESK") == HttpStatus.CONFLICT
    }

    def "renaming a user to a name taken in its tenant is refused - a name of another tenant is not"() {
        given:
        def tenant = createTenant()
        def taken = uniqueName()
        def elsewhere = uniqueName()
        admin("POST", "/admin/tenants/${tenant.id}/users", [username: taken, displayName: "A", password: PASSWORD])
        admin("POST", "/admin/tenants/${createTenant().id}/users", [username: elsewhere, displayName: "B", password: PASSWORD])
        def user = admin("POST", "/admin/tenants/${tenant.id}/users", [username: uniqueName(), displayName: "C", password: PASSWORD])

        expect:
        status(adminToken, "PUT", "/admin/tenants/${tenant.id}/users/${user.id}", [username: taken, displayName: "C"]) == HttpStatus.CONFLICT
        status(adminToken, "PUT", "/admin/tenants/${tenant.id}/users/${user.id}", [username: elsewhere, displayName: "C"]) == HttpStatus.OK
    }

    def "the admin adds a person's user of another tenant - same name, display name and language, no password"() {
        given:
        def source = createTenant()
        def target = createTenant()
        def username = uniqueName()
        def original = admin("POST", "/admin/tenants/${source.id}/users",
                [username: username, displayName: "Maria Muster", password: PASSWORD, language: "de", admin: true])

        when:
        def added = admin("POST", "/admin/tenants/${target.id}/users/from-tenant", [userId: original.id])

        then:
        added.username == username
        added.displayName == "Maria Muster"
        added.language == "de"
        added.active == true
        added.admin == false
        added.id != original.id
        users.findById(added.id as Long).get().passwordHash() == null
        list(target.id)*.username == [username]

        and: "the original stays as it was"
        list(source.id).find { it.id == original.id }.admin == true

        and: "waiting and as tenant admin when the admin says so"
        def third = createTenant()
        def waiting = admin("POST", "/admin/tenants/${third.id}/users/from-tenant", [userId: original.id, active: false, admin: true])
        waiting.active == false
        waiting.admin == true

        and: "the three are one person's - also when the person is taken from a user added before"
        def person = users.findById(original.id as Long).get().person()
        person != null
        users.findById(added.id as Long).get().person() == person
        users.findById(waiting.id as Long).get().person() == person
        def fourth = createTenant()
        def again = admin("POST", "/admin/tenants/${fourth.id}/users/from-tenant", [userId: added.id])
        users.findById(again.id as Long).get().person() == person
    }

    def "a user created by name is nobody's person - also when its name is another tenant's user's"() {
        given:
        def username = uniqueName()
        def first = admin("POST", "/admin/tenants/${createTenant().id}/users", [username: username, displayName: "A", password: PASSWORD])
        def second = admin("POST", "/admin/tenants/${createTenant().id}/users", [username: username, displayName: "B", password: PASSWORD])

        expect:
        users.findById(first.id as Long).get().person() == null
        users.findById(second.id as Long).get().person() == null
    }

    def "adding another tenant's user is refused: #reason"() {
        given:
        def source = createTenant()
        def target = createTenant()
        def original = admin("POST", "/admin/tenants/${source.id}/users", [username: uniqueName(), displayName: "A", password: PASSWORD])
        if (taken) {
            admin("POST", "/admin/tenants/${target.id}/users/from-tenant", [userId: original.id])
        }
        def tenantId = unknownTenant ? 999_999 : target.id
        def body = noUser ? [:] : [userId: unknownUser ? 999_999_999 : original.id]

        expect:
        status(adminToken, "POST", "/admin/tenants/${tenantId}/users/from-tenant", body) == expected

        where:
        reason               | noUser | unknownUser | unknownTenant | taken || expected
        "no user"            | true   | false       | false         | false || HttpStatus.BAD_REQUEST
        "an unknown user"    | false  | true        | false         | false || HttpStatus.NOT_FOUND
        "an unknown tenant"  | false  | false       | true          | false || HttpStatus.NOT_FOUND
        "the name is taken"  | false  | false       | false         | true  || HttpStatus.CONFLICT
    }

    def "a wrong password, an unknown user, a deactivated user and a deactivated tenant get no token"() {
        given:
        def tenant = createTenant()
        def username = uniqueName()
        def user = admin("POST", "/admin/tenants/${tenant.id}/users", [username: username, displayName: "A", password: PASSWORD])

        expect:
        loginStatus(username, "falsches-passwort") == HttpStatus.UNAUTHORIZED
        loginStatus("unknown-" + username, PASSWORD) == HttpStatus.UNAUTHORIZED

        when:
        admin("PUT", "/admin/tenants/${tenant.id}/users/${user.id}", [username: username, displayName: "A", active: false])

        then:
        loginStatus(username, PASSWORD) == HttpStatus.UNAUTHORIZED

        when:
        admin("PUT", "/admin/tenants/${tenant.id}/users/${user.id}", [username: username, displayName: "A", active: true])
        admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, active: false])

        then:
        loginStatus(username, PASSWORD) == HttpStatus.UNAUTHORIZED
    }

    def "a token already issued stops working when the user is deactivated, deleted or its tenant deactivated"() {
        given:
        def tenant = createTenant()
        def username = uniqueName()
        def user = admin("POST", "/admin/tenants/${tenant.id}/users", [username: username, displayName: "A", password: PASSWORD])
        def token = login(username, PASSWORD).access_token

        when: "the tenant is deactivated"
        admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, active: false])

        then:
        status(token, "GET", "/tenant/account") == HttpStatus.FORBIDDEN

        when: "it is active again, but the user is deactivated"
        admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, active: true])
        def before = status(token, "GET", "/tenant/account")
        admin("PUT", "/admin/tenants/${tenant.id}/users/${user.id}", [username: username, displayName: "A", active: false])

        then:
        before == HttpStatus.OK
        status(token, "GET", "/tenant/account") == HttpStatus.FORBIDDEN

        when: "the user is deleted"
        admin("PUT", "/admin/tenants/${tenant.id}/users/${user.id}", [username: username, displayName: "A", active: true])
        client.toBlocking().exchange(HttpRequest.DELETE("/admin/tenants/${tenant.id}/users/${user.id}").bearerAuth(adminToken))

        then:
        status(token, "GET", "/tenant/account") == HttpStatus.FORBIDDEN
        list(tenant.id).isEmpty()
    }

    def "an update without a password keeps it, one with a password replaces it"() {
        given:
        def tenant = createTenant()
        def username = uniqueName()
        def user = admin("POST", "/admin/tenants/${tenant.id}/users", [username: username, displayName: "A", password: PASSWORD])

        when:
        def updated = admin("PUT", "/admin/tenants/${tenant.id}/users/${user.id}", [username: username, displayName: "Maria"])

        then:
        updated.displayName == "Maria"
        loginStatus(username, PASSWORD) == HttpStatus.OK

        when:
        admin("PUT", "/admin/tenants/${tenant.id}/users/${user.id}", [username: username, displayName: "Maria", password: "ein-neues-passwort"])

        then:
        loginStatus(username, PASSWORD) == HttpStatus.UNAUTHORIZED
        loginStatus(username, "ein-neues-passwort") == HttpStatus.OK
    }

    def "a password set by the admin ends the user's sessions - other changes do not"() {
        given:
        def tenant = createTenant()
        def username = uniqueName()
        def user = admin("POST", "/admin/tenants/${tenant.id}/users", [username: username, displayName: "A", password: PASSWORD])
        def token = login(username, PASSWORD).access_token
        // A JWT's "iat" has whole seconds: the new password must come in a later second than the token.
        Thread.sleep(1100)

        when:
        admin("PUT", "/admin/tenants/${tenant.id}/users/${user.id}", [username: username, displayName: "Maria"])

        then:
        status(token, "GET", "/tenant/account") == HttpStatus.OK

        when:
        admin("PUT", "/admin/tenants/${tenant.id}/users/${user.id}", [username: username, displayName: "Maria", password: "ein-neues-passwort"])

        then: "the old token is refused, a login with the new password works"
        status(token, "GET", "/tenant/account") == HttpStatus.FORBIDDEN
        status(login(username, "ein-neues-passwort").access_token, "GET", "/tenant/account") == HttpStatus.OK
    }

    def "a user changes its own password - only with the current one"() {
        given:
        def tenant = createTenant()
        def username = uniqueName()
        admin("POST", "/admin/tenants/${tenant.id}/users", [username: username, displayName: "A", password: PASSWORD])
        def token = login(username, PASSWORD).access_token

        expect:
        status(token, "PUT", "/tenant/account/password", [currentPassword: "falsch", newPassword: "ein-neues-passwort"]) == HttpStatus.BAD_REQUEST
        status(token, "PUT", "/tenant/account/password", [currentPassword: PASSWORD, newPassword: "kurz"]) == HttpStatus.BAD_REQUEST
        status(token, "PUT", "/tenant/account/password", [currentPassword: PASSWORD, newPassword: "ein-neues-passwort"]) == HttpStatus.NO_CONTENT
        loginStatus(username, PASSWORD) == HttpStatus.UNAUTHORIZED
        loginStatus(username, "ein-neues-passwort") == HttpStatus.OK
    }

    def "another tenant's user is not found through this tenant's path"() {
        given:
        def owner = createTenant()
        def other = createTenant()
        def username = uniqueName()
        def user = admin("POST", "/admin/tenants/${owner.id}/users", [username: username, displayName: "A", password: PASSWORD])

        expect:
        status(adminToken, "PUT", "/admin/tenants/${other.id}/users/${user.id}", [username: username, displayName: "B"]) == HttpStatus.NOT_FOUND
        status(adminToken, "DELETE", "/admin/tenants/${other.id}/users/${user.id}") == HttpStatus.NOT_FOUND
        status(adminToken, "GET", "/admin/tenants/999999999/users") == HttpStatus.NOT_FOUND
        list(other.id).isEmpty()
        list(owner.id).size() == 1
    }

    def "deleting a tenant deletes its users"() {
        given:
        def tenant = createTenant()
        def username = uniqueName()
        admin("POST", "/admin/tenants/${tenant.id}/users", [username: username, displayName: "A", password: PASSWORD])

        when:
        client.toBlocking().exchange(HttpRequest.DELETE("/admin/tenants/${tenant.id}").bearerAuth(adminToken))

        then:
        loginStatus(username, PASSWORD) == HttpStatus.UNAUTHORIZED
        createStatus(createTenant().id, username) == HttpStatus.CREATED
    }

    private Map createTenant() {
        admin("POST", "/admin/tenants", [name: "Firma " + UUID.randomUUID()])
    }

    private String tokenOfNewUser(Map tenant) {
        def username = uniqueName()
        admin("POST", "/admin/tenants/${tenant.id}/users", [username: username, displayName: "A", password: PASSWORD])
        login(username, PASSWORD).access_token
    }

    private static String uniqueName() {
        "user-" + UUID.randomUUID().toString().substring(0, 8)
    }

    private List<Map> list(Object tenantId) {
        client.toBlocking().retrieve(HttpRequest.GET("/admin/tenants/${tenantId}/users").bearerAuth(adminToken), Argument.listOf(Map))
    }

    private Map admin(String method, String path, Map body) {
        client.toBlocking().retrieve(HttpRequest.create(HttpMethod.valueOf(method), path).body(body).bearerAuth(adminToken), Map)
    }

    private Map get(String path, String token) {
        client.toBlocking().retrieve(HttpRequest.GET(path).bearerAuth(token), Map)
    }

    private Map login(String username, String password) {
        client.toBlocking().retrieve(HttpRequest.POST("/login", [username: username, password: password]), Map)
    }

    private HttpStatus loginStatus(String username, String password) {
        status(null, "POST", "/login", [username: username, password: password])
    }

    private HttpStatus createStatus(Object tenantId, String username) {
        status(adminToken, "POST", "/admin/tenants/${tenantId}/users", [username: username, displayName: "A", password: PASSWORD])
    }

    private HttpStatus status(String token, String method, String path, Map body = null) {
        try {
            def request = HttpRequest.create(HttpMethod.valueOf(method), path)
            if (body != null || method in ["POST", "PUT"]) {
                request = request.body(body ?: [:])
            }
            if (token) {
                request = request.bearerAuth(token)
            }
            client.toBlocking().exchange(request, byte[]).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }
}
