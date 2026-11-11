package de.alpdesk.security

import de.alpdesk.entities.tenant.TenantUser
import de.alpdesk.repositories.tenant.TenantUserRepository
import io.micronaut.core.type.Argument
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.security.token.generator.AccessRefreshTokenGenerator
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource
import java.time.Instant

/**
 * The two ways in, side by side: the same scenarios once without and once with the WAM proxy
 * ({@link LoginWithoutWamSpec}, {@link LoginWithWamSpec}). Each way works on its own, and nothing of the one works in
 * the other - no password while WAM is on, no header while it is off. The admin's password login is the same in both.
 */
abstract class LoginModesSpec extends Specification {

    static final String PASSWORD = "geheim-genug-123"
    static final String OTHER_PASSWORD = "anders-genug-456"
    static final String USER_HEADER = "X-Remote-User"

    @Inject
    de.alpdesk.services.tenant.UserGroupService groups

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    TenantUserRepository users

    @Inject
    AccessRefreshTokenGenerator tokens

    @Shared
    String adminToken

    /** Through the pool itself: outside a transaction the injected DataSource hands out no connection. */
    DataSource managementDatabase

    @Inject
    void setManagementDatabase(DataSource dataSource) {
        managementDatabase = DelegatingDataSource.unwrapDataSource(dataSource)
    }

    /** Whether this run has the WAM proxy switched on. */
    abstract boolean wam()

    def setup() {
        if (!adminToken) {
            adminToken = request(null, null, "POST", "/login", [username: "alpdesk", password: "alpdesk"]).body.access_token
        }
    }

    def "the login page learns which way is open"() {
        expect:
        request(null, null, "GET", "/login/wam").body == [enabled: wam(), groupsChecked: false]
    }

    def "the admin logs in with its password either way - its token never depends on the proxy's header"() {
        expect:
        request(null, null, "POST", "/login", [username: "alpdesk", password: "alpdesk"]).status == HttpStatus.OK
        request(null, null, "POST", "/login", [username: "alpdesk", password: "falsch"]).status == HttpStatus.UNAUTHORIZED
        request(adminToken, null, "GET", "/admin/tenants").status == HttpStatus.OK
        request(adminToken, "jemand", "GET", "/admin/tenants").status == HttpStatus.OK

        and: "the WAM proxy never signs anyone in as the admin"
        request(null, "alpdesk", "POST", "/login/wam").status == (wam() ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND)
    }

    def "a user with a password logs in with it - only without WAM; with WAM only through the proxy"() {
        given:
        def user = userWithPassword()

        when:
        def byPassword = request(null, null, "POST", "/login", [username: user.username(), password: PASSWORD])
        def byProxy = request(null, user.username(), "POST", "/login/wam")

        then:
        byPassword.status == (wam() ? HttpStatus.UNAUTHORIZED : HttpStatus.OK)
        byProxy.status == (wam() ? HttpStatus.OK : HttpStatus.NOT_FOUND)

        and: "whichever way let it in, its token works"
        def token = (wam() ? byProxy : byPassword).body.access_token as String
        request(token, wam() ? user.username() : null, "GET", "/tenant/account").body.username == user.username()
    }

    def "a user without a password - someone the WAM proxy brought in - has no way in without WAM"() {
        given:
        def user = userWithoutPassword()

        expect:
        request(null, null, "POST", "/login", [username: user.username(), password: PASSWORD]).status == HttpStatus.UNAUTHORIZED
        request(null, null, "POST", "/login", [username: user.username(), password: ""]).status in [HttpStatus.UNAUTHORIZED, HttpStatus.BAD_REQUEST]
        request(null, user.username(), "POST", "/login/wam").status == (wam() ? HttpStatus.OK : HttpStatus.NOT_FOUND)
    }

    def "the proxy's header counts only with WAM: then every request of a user must carry its user"() {
        given:
        def user = userWithPassword()
        def token = tokenFor(user)

        expect:
        request(token, user.username(), "GET", "/tenant/account").status == HttpStatus.OK
        request(token, "jemand-anders", "GET", "/tenant/account").status == (wam() ? HttpStatus.FORBIDDEN : HttpStatus.OK)
        request(token, null, "GET", "/tenant/account").status == (wam() ? HttpStatus.FORBIDDEN : HttpStatus.OK)
    }

    def "a token issued the other way - e.g. before WAM was switched on - holds only on this way's terms"() {
        given: "a token of a user who has a password, as the password login issues it"
        def user = userWithPassword()
        def token = tokenFor(user)

        expect: "with WAM it still needs the proxy to send its user; without, it needs nothing more"
        request(token, null, "GET", "/tenant/chat/conversations").status == (wam() ? HttpStatus.FORBIDDEN : HttpStatus.OK)
        request(token, user.username(), "GET", "/tenant/chat/conversations").status == HttpStatus.OK
    }

    def "passwords exist only without WAM: required on create, set by an admin, changed by the user"() {
        given:
        def tenant = createTenant()
        def user = userWithPassword(tenant)
        def token = tokenFor(user)

        expect: "creating"
        admin("POST", "/admin/tenants/${tenant.id}/users", [username: unique("mit"), displayName: "Mit", password: PASSWORD])
                .status == (wam() ? HttpStatus.BAD_REQUEST : HttpStatus.CREATED)
        admin("POST", "/admin/tenants/${tenant.id}/users", [username: unique("ohne"), displayName: "Ohne"])
                .status == (wam() ? HttpStatus.CREATED : HttpStatus.BAD_REQUEST)

        and: "an admin setting one"
        admin("PUT", "/admin/tenants/${tenant.id}/users/${user.id()}",
                [username: user.username(), displayName: user.displayName(), password: "neues-geheimnis-12"])
                .status == (wam() ? HttpStatus.BAD_REQUEST : HttpStatus.OK)

        and: "the user changing its own"
        request(tokenFor(users.findById(user.id()).get()), wam() ? user.username() : null, "PUT", "/tenant/account/password",
                [currentPassword: wam() ? PASSWORD : "neues-geheimnis-12", newPassword: "noch-ein-geheimnis"])
                .status == (wam() ? HttpStatus.CONFLICT : HttpStatus.NO_CONTENT)
    }

    def "a tenant admin creates users and changes usernames only without WAM - with WAM the admin does"() {
        given:
        def tenant = createTenant()
        def chef = saveUser(tenant, PasswordHasher.hash(PASSWORD), true)
        def colleague = userWithPassword(tenant)
        def asChef = { String method, String path, Map body -> request(tokenFor(chef), chef.username(), method, path, body) }
        def newUser = [username: unique("neu"), displayName: "Neu"] + (wam() ? [:] : [password: PASSWORD])
        def expected = wam() ? HttpStatus.CONFLICT : HttpStatus.OK

        when:
        def created = asChef("POST", "/tenant/admin/users", newUser)
        def renamed = asChef("PUT", "/tenant/admin/users/${colleague.id()}", [username: unique("anders"), displayName: "Test"])

        then: "with WAM a username is a person's sign-in at the proxy - new people come through their access request"
        created.status == (wam() ? HttpStatus.CONFLICT : HttpStatus.CREATED)
        renamed.status == expected
        !wam() || created.body.message == "Users sign in through the WAM proxy - new ones come through their access " +
                "request, and only the admin creates users or changes a username"

        and: "everything else of a user stays the tenant admin's either way"
        def current = users.findById(colleague.id()).get().username()
        asChef("PUT", "/tenant/admin/users/${colleague.id()}",
                [username: current, displayName: "Kollegin", active: false, admin: true]).status == HttpStatus.OK
        asChef("DELETE", "/tenant/admin/users/${colleague.id()}", null).status == HttpStatus.NO_CONTENT

        and: "the admin creates users and changes usernames either way"
        def byAdmin = admin("POST", "/admin/tenants/${tenant.id}/users", [username: unique("vom-admin"), displayName: "A"]
                + (wam() ? [:] : [password: PASSWORD]))
        byAdmin.status == HttpStatus.CREATED
        admin("PUT", "/admin/tenants/${tenant.id}/users/${byAdmin.body.id}", [username: unique("umbenannt"), displayName: "A"])
                .status == HttpStatus.OK
    }

    def "a new username ends the user's sessions - with WAM the old person's proxy would still match the old token"() {
        given:
        def tenant = createTenant()
        def user = userWithPassword(tenant)
        def token = tokenFor(user)
        // A JWT's "iat" has whole seconds: the rename must come in a later second than the token.
        Thread.sleep(1100)

        when: "only the display name changes"
        admin("PUT", "/admin/tenants/${tenant.id}/users/${user.id()}", [username: user.username(), displayName: "Neu"])

        then:
        request(token, user.username(), "GET", "/tenant/account").status == HttpStatus.OK

        when: "the admin hands the account to someone else"
        def renamed = admin("PUT", "/admin/tenants/${tenant.id}/users/${user.id()}",
                [username: unique("nachfolger"), displayName: "Nachfolger"])

        then: "the old person's token with the old name is refused"
        renamed.status == HttpStatus.OK
        request(token, user.username(), "GET", "/tenant/account").status == HttpStatus.FORBIDDEN
        request(token, user.username(), "GET", "/tenant/chat/conversations").status == HttpStatus.FORBIDDEN
    }

    def "the client software's API key and the MCP key work either way - the proxy's header counts for users only"() {
        given:
        def tenant = createTenant()
        def apiKey = admin("POST", "/admin/tenants/${tenant.id}/api-keys", [name: "Software"]).body.plainKey as String
        def mcpKey = admin("POST", "/admin/tenants/${tenant.id}/mcp-keys", [name: "Claude"]).body.plainKey as String
        def ping = [jsonrpc: "2.0", id: 1, method: "ping"]

        expect: "without any header of the proxy - software sends none"
        request(apiKey, null, "GET", "/api/v1/me").status == HttpStatus.OK
        request(mcpKey, null, "POST", "/mcp", ping).status == HttpStatus.OK

        and: "and a header of someone else changes nothing"
        request(apiKey, "jemand", "GET", "/api/v1/me").status == HttpStatus.OK
        request(mcpKey, "jemand", "POST", "/mcp", ping).status == HttpStatus.OK
    }

    def "access requests come only from the WAM proxy"() {
        given:
        def username = unique("unbekannt")

        when:
        def signIn = request(null, username, "POST", "/login/wam")

        then:
        signIn.status == (wam() ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND)
        (admin("GET", "/admin/access-requests").body as List<Map>).any { it.username == username } == wam()
    }

    def "a username in two tenants signs in at /login only with the tenant's login key - and only without WAM"() {
        given: "one name, two tenants, two users with passwords of their own - an admin in the first"
        def name = unique("doppelt")
        def first = createTenant()
        def second = createTenant()
        def third = createTenant()
        def inFirst = saveUser(first, PasswordHasher.hash(PASSWORD), true, name)
        def inSecond = saveUser(second, PasswordHasher.hash(OTHER_PASSWORD), false, name)

        expect: "the name alone does not say which user - refused like a wrong password"
        login(name, PASSWORD, null).status == HttpStatus.UNAUTHORIZED
        login(name, OTHER_PASSWORD, null).status == HttpStatus.UNAUTHORIZED

        when: "each tenant's login page sends its key"
        def viaFirst = login(name, PASSWORD, loginKey(first))
        def viaSecond = login(name, OTHER_PASSWORD, loginKey(second))

        then: "each signs into its own tenant, with its own roles"
        if (wam()) {
            assert viaFirst.status == HttpStatus.UNAUTHORIZED
            assert viaSecond.status == HttpStatus.UNAUTHORIZED
        } else {
            assert viaFirst.status == HttpStatus.OK
            assert viaFirst.body.roles.toSet() == [Roles.TENANT_USER, Roles.TENANT_ADMIN].toSet()
            assert request(viaFirst.body.access_token, null, "GET", "/tenant/account").body.userId == inFirst.id()
            assert viaSecond.status == HttpStatus.OK
            assert viaSecond.body.roles == [Roles.TENANT_USER]
            def account = request(viaSecond.body.access_token, null, "GET", "/tenant/account").body
            assert account.userId == inSecond.id()
            assert account.tenantId == second.id
            assert request(viaSecond.body.access_token, null, "GET", "/tenant/admin/users").status == HttpStatus.FORBIDDEN
        }

        and: "a password of the other tenant's user, a key nobody has, a tenant without the name - refused alike"
        login(name, OTHER_PASSWORD, loginKey(first)).status == HttpStatus.UNAUTHORIZED
        login(name, PASSWORD, loginKey(second)).status == HttpStatus.UNAUTHORIZED
        login(name, PASSWORD, "0" * 32).status == HttpStatus.UNAUTHORIZED
        login(name, PASSWORD, loginKey(third)).status == HttpStatus.UNAUTHORIZED

        and: "a name in one tenant signs in without the key as before - a wrong key refuses it"
        def single = userWithPassword(third)
        login(single.username(), PASSWORD, null).status == (wam() ? HttpStatus.UNAUTHORIZED : HttpStatus.OK)
        login(single.username(), PASSWORD, loginKey(third)).status == (wam() ? HttpStatus.UNAUTHORIZED : HttpStatus.OK)
        login(single.username(), PASSWORD, loginKey(first)).status == HttpStatus.UNAUTHORIZED

        and: "the admin's login does not care for the key"
        login("alpdesk", "alpdesk", loginKey(first)).status == HttpStatus.OK
    }

    def "the switch to the same name's user in another tenant: without WAM with that user's password, with WAM by the proxy"() {
        given: "a person with an admin user in A and a plain one in B; a deactivated one in C; none in D"
        def name = unique("wechsel")
        def a = createTenant()
        def b = createTenant()
        def c = createTenant()
        def d = createTenant()
        def inA = saveUser(a, PasswordHasher.hash(PASSWORD), true, name)
        def inB = saveUser(b, PasswordHasher.hash(OTHER_PASSWORD), false, name)
        saveUser(c, PasswordHasher.hash(PASSWORD), false, name, false)
        saveUser(d, PasswordHasher.hash(PASSWORD), false, unique("jemand"))
        def token = tokenFor(inA)
        def proxy = wam() ? name : null

        expect: "the account names the tenants to switch to - this one included, the deactivated one not"
        def offered = request(token, proxy, "GET", "/tenant/account").body.tenants as List<Map>
        offered*.id.toSet() == [a.id, b.id].toSet()

        and: "their login keys only with WAM - without, the same name may be another person"
        offered.every { wam() ? it.loginKey : it.loginKey == null }

        and: "a user of the name without a password (added by the admin, not handed over yet) is listed with WAM only"
        def e = createTenant()
        saveUser(e, null as String, false, name)
        (request(token, proxy, "GET", "/tenant/account").body.tenants as List<Map>)*.id.contains(e.id) == wam()

        when:
        def switched = request(token, proxy, "POST", "/tenant/account/switch",
                wam() ? [tenantId: b.id] : [tenantId: b.id, password: OTHER_PASSWORD])

        then: "a token of B's user - with B's roles only"
        switched.status == HttpStatus.OK
        switched.body.roles == [Roles.TENANT_USER]
        def account = request(switched.body.access_token, proxy, "GET", "/tenant/account").body
        account.tenantId == b.id
        account.userId == inB.id()
        request(switched.body.access_token, proxy, "GET", "/tenant/admin/users").status == HttpStatus.FORBIDDEN

        and: "with WAM the new token holds only while the proxy sends the same person"
        if (wam()) {
            assert request(switched.body.access_token, "jemand-anders", "GET", "/tenant/account").status == HttpStatus.FORBIDDEN
        }

        and: "no switch to a tenant without such a user who may log in"
        request(token, proxy, "POST", "/tenant/account/switch", [tenantId: c.id, password: PASSWORD]).status == HttpStatus.NOT_FOUND
        request(token, proxy, "POST", "/tenant/account/switch", [tenantId: d.id, password: PASSWORD]).status == HttpStatus.NOT_FOUND
        request(token, proxy, "POST", "/tenant/account/switch", [password: PASSWORD]).status == HttpStatus.NOT_FOUND
        request(token, proxy, "POST", "/tenant/account/switch", [tenantId: 999_999, password: PASSWORD]).status == HttpStatus.NOT_FOUND

        and: "without WAM the target's password is asked - a 400, never a 401 that would log the person out"
        def withoutPassword = request(token, proxy, "POST", "/tenant/account/switch", [tenantId: b.id])
        def wrongPassword = request(token, proxy, "POST", "/tenant/account/switch", [tenantId: b.id, password: PASSWORD])
        if (wam()) {
            assert withoutPassword.status == HttpStatus.OK
            assert wrongPassword.status == HttpStatus.OK
        } else {
            assert withoutPassword.status == HttpStatus.BAD_REQUEST
            assert withoutPassword.body.code == "account.passwordRequired"
            assert wrongPassword.status == HttpStatus.BAD_REQUEST
            assert wrongPassword.body.code == "account.switchPasswordWrong"
        }
    }

    def "a user's own language: with WAM for every user of the person, without WAM for its own user only"() {
        given: "the same name in two tenants - with WAM one person, without WAM maybe two"
        def name = unique("sprache")
        def inA = saveUser(createTenant(), PasswordHasher.hash(PASSWORD), false, name)
        def inB = saveUser(createTenant(), PasswordHasher.hash(OTHER_PASSWORD), false, name)
        def proxy = wam() ? name : null

        when:
        def changed = request(tokenFor(inA), proxy, "PUT", "/tenant/account/language", [language: "de"])

        then:
        changed.status == HttpStatus.NO_CONTENT
        users.findById(inA.id()).get().language() == "de"
        users.findById(inB.id()).get().language() == (wam() ? "de" : "en")
    }

    def "a language change never undoes what an admin changed meanwhile on the person's user in another tenant"() {
        given: "the same name in two tenants, B's user a tenant admin"
        def name = unique("sprache")
        def inA = saveUser(createTenant(), PasswordHasher.hash(PASSWORD), false, name)
        def inB = saveUser(createTenant(), PasswordHasher.hash(OTHER_PASSWORD), true, name)
        def proxy = wam() ? name : null

        and: "B's admin deactivates and demotes the user - not committed yet when the language change comes"
        def deactivation = managementDatabase.connection
        deactivation.autoCommit = false
        def statement = deactivation.prepareStatement("UPDATE tenant_user SET active = false, admin = false WHERE id = ?")
        statement.setLong(1, inB.id())
        statement.executeUpdate()

        when: "the person changes its language in A while B's change holds the row"
        def changed = null
        def change = Thread.start {
            changed = request(tokenFor(inA), proxy, "PUT", "/tenant/account/language", [language: "de"])
        }
        waitUntil { change.alive && waitingForALock() || !change.alive }
        deactivation.commit()
        change.join(15_000)

        then: "B's user stays deactivated and demoted - with WAM its language follows"
        changed.status == HttpStatus.NO_CONTENT
        def b = users.findById(inB.id()).get()
        !b.active()
        !b.admin()
        b.language() == (wam() ? "de" : "en")

        cleanup:
        deactivation?.close()
    }

    // --- helpers ---

    /** A user straight in the database - the API refuses a password with WAM and wants one without. */
    TenantUser userWithPassword(Map tenant = createTenant()) {
        saveUser(tenant, PasswordHasher.hash(PASSWORD))
    }

    TenantUser userWithoutPassword(Map tenant = createTenant()) {
        saveUser(tenant, null)
    }

    TenantUser saveUser(Map tenant, String passwordHash, boolean admin = false, String username = unique("user"),
                                boolean active = true) {
        def now = Instant.now()
        def user = users.save(new TenantUser(null, tenant.id as Long, username, "Test", passwordHash, active, admin, now,
                now, null, null, "en"))
        groups.joinStandard(user.tenantId(), user.id())
        user
    }

    /** POST /login, with the tenant's login key as its own login page sends it (null: the neutral page). */
    Map login(String username, String password, String loginKey) {
        def request = HttpRequest.POST("/login", [username: username, password: password])
        if (loginKey != null) {
            request = request.header("X-Login-Key", loginKey)
        }
        try {
            def response = client.toBlocking().exchange(request, Map)
            [status: response.status, body: response.body.orElse(null)]
        } catch (HttpClientResponseException e) {
            [status: e.status, body: e.response.getBody(Map).orElse(null)]
        }
    }

    String loginKey(Map tenant) {
        admin("GET", "/admin/tenants/${tenant.id}/branding").body.loginKey
    }

    /** A JWT of the user, as any of the two logins issues it. */
    String tokenFor(TenantUser user) {
        tokens.generate(TenantUserClaims.of(user)).get().accessToken
    }

    Map createTenant() {
        admin("POST", "/admin/tenants", [name: unique("Firma")]).body as Map
    }

    Map admin(String method, String path, Map body = null) {
        request(adminToken, null, method, path, body)
    }

    /** status and parsed body; proxyUser: the header a WAM proxy would send (sent in both modes). */
    Map request(String token, String proxyUser, String method, String path, Map body = null) {
        def request = HttpRequest.create(HttpMethod.valueOf(method), path)
        if (body != null || method in ["POST", "PUT"]) {
            request = request.body(body ?: [:])
        }
        if (token) {
            request = request.bearerAuth(token)
        }
        if (proxyUser != null) {
            request = request.header(USER_HEADER, proxyUser)
        }
        try {
            def response = client.toBlocking().exchange(request, Argument.of(Object))
            [status: response.status, body: response.body.orElse(null)]
        } catch (HttpClientResponseException e) {
            [status: e.status, body: e.response.getBody(Map).orElse(null)]
        }
    }

    /** Whether a request of the app waits for a row another transaction holds. */
    boolean waitingForALock() {
        managementDatabase.connection.withCloseable { connection ->
            def result = connection.createStatement()
                    .executeQuery("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'")
            result.next() && result.getInt(1) > 0
        }
    }

    static void waitUntil(Closure<Boolean> condition) {
        def deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    static String unique(String prefix) {
        prefix + "-" + UUID.randomUUID().toString().substring(0, 8)
    }
}
