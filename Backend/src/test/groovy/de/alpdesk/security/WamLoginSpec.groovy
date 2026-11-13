package de.alpdesk.security

import de.alpdesk.entities.tenant.TenantUser
import de.alpdesk.repositories.tenant.TenantUserRepository
import io.micronaut.context.annotation.Property
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
 * Sign-in through a WAM proxy (docs/architecture/tenancy.md): the proxy sends the user in a header; someone unknown
 * waits until the admin assigns the request to a tenant and - unless the admin did so at once - the tenant admin
 * approves the user. Tenant users have no password; the admin keeps its own login.
 */
@MicronautTest(transactional = false)
@Property(name = "app.wam.enabled", value = "true")
@Property(name = "app.wam.name-header", value = "X-Remote-Name")
class WamLoginSpec extends Specification {

    static final String USER_HEADER = "X-Remote-User"

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
            adminToken = client.toBlocking().retrieve(
                    HttpRequest.POST("/login", [username: "alpdesk", password: "alpdesk"]), Map).access_token
        }
    }

    def "someone unknown waits, the admin assigns them, the tenant admin approves - then they sign in"() {
        given: "a tenant whose first user the admin approves at once and makes its tenant admin"
        def tenant = createTenant()
        def tenantAdmin = firstAdmin(tenant)
        def username = unique("maria")

        expect: "the login page learns that WAM is on"
        request(null, null, "GET", "/login/wam").body == [enabled: true, groupsChecked: false]

        when: "someone unknown signs in - twice"
        def first = signIn(username, "Maria Huber")
        def again = signIn(username, "Maria Huber")
        def waiting = requestsFor(username)

        then:
        first.status == HttpStatus.CONFLICT
        first.body.message == "Your access is waiting for approval"
        again.status == HttpStatus.CONFLICT
        waiting.size() == 1
        waiting[0].displayName == "Maria Huber"

        when: "the admin assigns them to the tenant, still waiting for the tenant admin"
        def assigned = admin("POST", "/admin/access-requests/${waiting[0].id}/assign", [tenantId: tenant.id])

        then:
        assigned.status == HttpStatus.CREATED
        assigned.body.username == username
        assigned.body.active == false
        assigned.body.admin == false
        assigned.body.groupIds == []
        requestsFor(username).isEmpty()
        signIn(username).status == HttpStatus.CONFLICT

        when: "the tenant admin approves them"
        def approved = request(tenantAdmin.token, tenantAdmin.username, "PUT", "/tenant/admin/users/${assigned.body.id}",
                [username: username, displayName: "Maria Huber", active: true])
        def signedIn = signIn(username)

        then:
        approved.status == HttpStatus.OK
        signedIn.status == HttpStatus.OK
        signedIn.body.roles == [Roles.TENANT_USER]
        signedIn.body.username == username

        and: "the token works while the proxy sends the same user - in any case"
        def account = request(signedIn.body.access_token, username.toUpperCase(), "GET", "/tenant/account")
        account.status == HttpStatus.OK
        account.body.tenantId == tenant.id
        users.findById(assigned.body.id as Long).get().lastLoginAt() != null
    }

    def "a token only works while the proxy sends its user - another person at the same computer is signed in anew"() {
        given:
        def tenant = createTenant()
        def anna = firstAdmin(tenant)

        expect:
        request(anna.token, anna.username, "GET", "/tenant/account").status == HttpStatus.OK
        request(anna.token, "bernd-" + anna.username, "GET", "/tenant/account").status == HttpStatus.FORBIDDEN
        request(anna.token, null, "GET", "/tenant/account").status == HttpStatus.FORBIDDEN

        and: "the admin's token does not depend on the header"
        request(adminToken, "jemand-anders", "GET", "/admin/tenants").status == HttpStatus.OK
        request(adminToken, null, "GET", "/admin/tenants").status == HttpStatus.OK
    }

    def "a user approved by the admin at once and made tenant admin gets both roles"() {
        given:
        def tenant = createTenant()

        when:
        def chef = firstAdmin(tenant)

        then:
        chef.roles.toSet() == [Roles.TENANT_USER, Roles.TENANT_ADMIN].toSet()
        request(chef.token, chef.username, "GET", "/tenant/admin/users").status == HttpStatus.OK
    }

    def "a deactivated user, or one of a deactivated tenant, waits again"() {
        given:
        def tenant = createTenant()
        def user = firstAdmin(tenant)

        when:
        users.update(users.findAllByUsernameIgnoreCaseOrderByTenantId(user.username).first().with { u ->
            new TenantUser(u.id(), u.tenantId(), u.username(), u.displayName(), u.passwordHash(), false, u.admin(),
                    u.createdAt(), u.updatedAt(), u.lastLoginAt(), u.sessionsValidFrom(), u.language(), u.person())
        })

        then:
        signIn(user.username).status == HttpStatus.CONFLICT

        when:
        users.update(users.findAllByUsernameIgnoreCaseOrderByTenantId(user.username).first().with { u ->
            new TenantUser(u.id(), u.tenantId(), u.username(), u.displayName(), u.passwordHash(), true, u.admin(),
                    u.createdAt(), u.updatedAt(), u.lastLoginAt(), u.sessionsValidFrom(), u.language(), u.person())
        })
        admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, active: false])

        then:
        signIn(user.username).status == HttpStatus.CONFLICT
    }

    def "tenant users have no password: no password login, none set, none changed"() {
        given: "a user who still has a password from before WAM was turned on"
        def tenant = createTenant()
        def chef = firstAdmin(tenant)
        def username = unique("alt")
        def now = Instant.now()
        def old = users.save(new TenantUser(null, tenant.id as Long, username, "Alt", PasswordHasher.hash("geheim-genug-123"),
                true, false, now, now, null, null, "en", null))
        groups.joinStandard(old.tenantId(), old.id())

        expect: "the password no longer signs in - the WAM proxy does"
        request(null, null, "POST", "/login", [username: username, password: "geheim-genug-123"]).status == HttpStatus.UNAUTHORIZED
        signIn(username).status == HttpStatus.OK

        and: "a new user gets no password"
        admin("POST", "/admin/tenants/${tenant.id}/users", [username: unique("neu"), displayName: "Neu",
                                                           password: "geheim-genug-123"]).status == HttpStatus.BAD_REQUEST
        def created = admin("POST", "/admin/tenants/${tenant.id}/users", [username: unique("neu"), displayName: "Neu"])
        created.status == HttpStatus.CREATED
        users.findById(created.body.id as Long).get().passwordHash() == null

        and: "nobody sets or changes one"
        request(chef.token, chef.username, "PUT", "/tenant/admin/users/${created.body.id}",
                [username: created.body.username, displayName: "Neu", password: "geheim-genug-123"]).status == HttpStatus.BAD_REQUEST
        request(chef.token, chef.username, "PUT", "/tenant/account/password",
                [currentPassword: "x", newPassword: "geheim-genug-456"]).status == HttpStatus.CONFLICT

        and: "the admin still logs in with its password"
        request(null, null, "POST", "/login", [username: "alpdesk", password: "alpdesk"]).status == HttpStatus.OK
    }

    def "the proxy's header must name a possible user: #reason"() {
        expect:
        request(null, header, "POST", "/login/wam").status == HttpStatus.BAD_REQUEST

        where:
        reason         | header
        "missing"      | null
        "no domain"    | "\\maria"
        "no user"      | "CORP\\"
        "two domains"  | "A\\b\\c"
        "too short"    | "ab"
    }

    def "a Windows domain account signs in as user@domain - whatever case the proxy sends"() {
        given:
        def tenant = createTenant()
        def account = unique("hans")

        when: "it asks for access"
        signIn("EUROPE\\" + account)
        def waiting = requestsFor(account + "@europe")

        then:
        waiting.size() == 1

        when: "assigned, it signs in - and its token holds while the proxy sends the same account"
        admin("POST", "/admin/access-requests/${waiting[0].id}/assign", [tenantId: tenant.id, active: true])
        def signedIn = signIn("europe\\" + account.toUpperCase())

        then:
        signedIn.status == HttpStatus.OK
        signedIn.body.username == account + "@europe"
        request(signedIn.body.access_token, "EUROPE\\" + account, "GET", "/tenant/account").status == HttpStatus.OK

        and: "the same name in another domain is someone else"
        request(signedIn.body.access_token, "AMERICAS\\" + account, "GET", "/tenant/account").status == HttpStatus.FORBIDDEN
        signIn("AMERICAS\\" + account).status == HttpStatus.CONFLICT
    }

    def "the admin rejects a request - the person asks again on their next visit"() {
        given:
        def username = unique("fremd")
        signIn(username)
        def id = requestsFor(username)[0].id

        expect:
        admin("DELETE", "/admin/access-requests/${id}").status == HttpStatus.NO_CONTENT
        requestsFor(username).isEmpty()
        admin("DELETE", "/admin/access-requests/${id}").status == HttpStatus.NOT_FOUND

        and:
        signIn(username).status == HttpStatus.CONFLICT
        requestsFor(username).size() == 1
    }

    def "assigning a request is refused: #reason"() {
        given:
        def tenant = createTenant()
        def username = unique("anfrage")
        signIn(username)
        def id = requestsFor(username)[0].id
        if (taken) {
            admin("POST", "/admin/tenants/${tenant.id}/users", [username: username, displayName: "Schon da"])
        }

        expect:
        admin("POST", "/admin/access-requests/${unknownRequest ? 999_999 : id}/assign",
                [tenantId: tenantId == "TENANT" ? tenant.id : tenantId, language: language]).status == status

        where:
        reason               | tenantId  | unknownRequest | taken | language  || status
        "no tenant"          | null      | false          | false | null      || HttpStatus.BAD_REQUEST
        "an unknown tenant"  | 999_999   | false          | false | null      || HttpStatus.NOT_FOUND
        "an unknown request" | "TENANT"  | true           | false | null      || HttpStatus.NOT_FOUND
        "a taken username"   | "TENANT"  | false          | true  | null      || HttpStatus.CONFLICT
        "no language tag"    | "TENANT"  | false          | false | "Deutsch" || HttpStatus.BAD_REQUEST
    }

    def "a request whose user the admin created directly goes when the user signs in"() {
        given:
        def tenant = createTenant()
        def username = unique("direkt")
        signIn(username)
        admin("POST", "/admin/tenants/${tenant.id}/users", [username: username, displayName: "Direkt"])

        when:
        def signedIn = signIn(username.toUpperCase())

        then:
        signedIn.status == HttpStatus.OK
        requestsFor(username).isEmpty()
    }

    def "the admin assigns a request with the user's language - English when left out"() {
        given:
        def tenant = createTenant()
        def german = unique("de")
        def english = unique("en")
        signIn(german)
        signIn(english)

        when:
        def withLanguage = admin("POST", "/admin/access-requests/${requestsFor(german)[0].id}/assign",
                [tenantId: tenant.id, active: true, language: "de"])
        def without = admin("POST", "/admin/access-requests/${requestsFor(english)[0].id}/assign",
                [tenantId: tenant.id, active: true])

        then:
        withLanguage.body.language == "de"
        without.body.language == "en"
        def signedIn = signIn(german)
        request(signedIn.body.access_token, german, "GET", "/tenant/account").body.language == "de"
    }

    def "a waiting person's request keeps the browser's language - the admin's assignment takes it unless it names one"() {
        given:
        def tenant = createTenant()
        def german = unique("browser-de")
        def chosen = unique("browser-de-admin-en")
        request(null, german, "POST", "/login/wam", [language: "de"])
        request(null, chosen, "POST", "/login/wam", [language: "de"])

        expect:
        requestsFor(german)[0].language == "de"

        when:
        def taken = admin("POST", "/admin/access-requests/${requestsFor(german)[0].id}/assign", [tenantId: tenant.id])
        def named = admin("POST", "/admin/access-requests/${requestsFor(chosen)[0].id}/assign",
                [tenantId: tenant.id, language: "en"])

        then:
        taken.body.language == "de"
        named.body.language == "en"
    }

    def "the language follows the person: chosen in one tenant, every user of the person speaks it"() {
        given: "the same person a user of two tenants, and someone else in the first"
        def first = createTenant()
        def second = createTenant()
        def person = firstAdmin(first)
        def other = firstAdmin(first)
        admin("POST", "/admin/tenants/${second.id}/users/from-tenant", [userId: idOf(person, first)])
        def inFirst = signInTo(person.username, first.id).body.access_token as String

        when:
        def changed = request(inFirst, person.username, "PUT", "/tenant/account/language", [language: "de"])

        then:
        changed.status == HttpStatus.NO_CONTENT
        def inSecond = signInTo(person.username, second.id).body.access_token as String
        request(inSecond, person.username, "GET", "/tenant/account").body.language == "de"
        request(other.token, other.username, "GET", "/tenant/account").body.language == "en"
    }

    def "a person with a user in several tenants chooses - no token until it has, and only its own tenants"() {
        given: "the same person made a user of two tenants by the admin, and of a third where it is deactivated"
        def first = createTenant()
        def second = createTenant()
        def third = createTenant()
        def stranger = createTenant()
        def person = firstAdmin(first)
        def inSecond = admin("POST", "/admin/tenants/${second.id}/users/from-tenant", [userId: idOf(person, first)])
        admin("POST", "/admin/tenants/${third.id}/users/from-tenant", [userId: idOf(person, first), active: false])

        when:
        def choice = signIn(person.username)

        then: "200 without a token - the tenants it may log in to, by name"
        choice.status == HttpStatus.OK
        choice.body.access_token == null
        (choice.body.tenants as List<Map>)*.id == [first, second].sort { it.name.toLowerCase() }*.id
        (choice.body.tenants as List<Map>).every { it.name && it.loginKey }

        when: "it chooses the second"
        def chosen = signInTo(person.username, second.id)

        then: "a token of its user there - a plain user, though tenant admin in the first"
        chosen.status == HttpStatus.OK
        chosen.body.roles == [Roles.TENANT_USER]
        def account = request(chosen.body.access_token, person.username, "GET", "/tenant/account").body
        account.tenantId == second.id
        account.userId == inSecond.body.id
        request(chosen.body.access_token, person.username, "GET", "/tenant/admin/users").status == HttpStatus.FORBIDDEN

        and: "a tenant where it has no user, or one it may not log in to, is not found"
        signInTo(person.username, stranger.id).status == HttpStatus.NOT_FOUND
        signInTo(person.username, third.id).status == HttpStatus.NOT_FOUND
        signInTo(person.username, 999_999).status == HttpStatus.NOT_FOUND

        and: "another person cannot choose the tenants of this one"
        signInTo(unique("fremd"), second.id).status == HttpStatus.CONFLICT
    }

    def "a person with one user left who may log in is signed in there without a choice"() {
        given:
        def first = createTenant()
        def second = createTenant()
        def person = firstAdmin(first)
        admin("POST", "/admin/tenants/${second.id}/users/from-tenant", [userId: idOf(person, first), active: false])

        when:
        def signedIn = signIn(person.username)

        then:
        signedIn.status == HttpStatus.OK
        request(signedIn.body.access_token, person.username, "GET", "/tenant/account").body.tenantId == first.id
    }

    def "the switch in the user menu needs no password with WAM - the proxy's header proves the person"() {
        given:
        def first = createTenant()
        def second = createTenant()
        def person = firstAdmin(first)
        admin("POST", "/admin/tenants/${second.id}/users/from-tenant", [userId: idOf(person, first)])

        expect: "the account offers both tenants"
        (request(person.token, person.username, "GET", "/tenant/account").body.tenants as List<Map>)*.id.toSet() ==
                [first.id, second.id].toSet()

        when:
        def switched = request(person.token, person.username, "POST", "/tenant/account/switch", [tenantId: second.id])

        then:
        switched.status == HttpStatus.OK
        request(switched.body.access_token, person.username, "GET", "/tenant/account").body.tenantId == second.id

        and: "the switched token holds only while the proxy sends the same person"
        request(switched.body.access_token, "bernd-" + person.username, "GET", "/tenant/account").status == HttpStatus.FORBIDDEN
        request(switched.body.access_token, null, "GET", "/tenant/account").status == HttpStatus.FORBIDDEN

        and: "nobody switches with another person's header"
        request(person.token, "bernd-" + person.username, "POST", "/tenant/account/switch", [tenantId: second.id]).status ==
                HttpStatus.FORBIDDEN
    }

    def "without a groups header configured the IDM gives no account - a header sent counts for nothing"() {
        given:
        def tenant = createTenant()
        def thema = unique("AI-Thema")
        admin("POST", "/admin/tenants/${tenant.id}/groups", [name: "Thema", wamGroup: thema])
        def username = unique("ohne")

        when:
        def signIn = client.toBlocking().exchange(HttpRequest.POST("/login/wam", [:]).header(USER_HEADER, username)
                .header("X-Remote-Groups", thema), Map)

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.CONFLICT
        (admin("GET", "/admin/tenants/${tenant.id}/users").body as List).every { it.username != username }
    }

    /** The tenant's first user: someone the WAM proxy let through, assigned by the admin active and as tenant admin. */
    private Map firstAdmin(Map tenant) {
        def username = unique("chef")
        signIn(username)
        def id = requestsFor(username)[0].id
        assert admin("POST", "/admin/access-requests/${id}/assign",
                [tenantId: tenant.id, active: true, admin: true]).status == HttpStatus.CREATED
        def signedIn = signIn(username)
        assert signedIn.status == HttpStatus.OK
        [username: username, token: signedIn.body.access_token, roles: signedIn.body.roles]
    }

    private Long idOf(Map person, Map tenant) {
        (admin("GET", "/admin/tenants/${tenant.id}/users").body as List<Map>).find { it.username == person.username }.id as Long
    }

    private Map signInTo(String user, Object tenantId) {
        request(null, user, "POST", "/login/wam", [tenantId: tenantId])
    }

    private List<Map> requestsFor(String username) {
        (admin("GET", "/admin/access-requests").body as List<Map>).findAll { it.username == username }
    }

    private Map signIn(String user, String name = null) {
        request(null, user, "POST", "/login/wam", null, name)
    }

    private Map admin(String method, String path, Map body = null) {
        request(adminToken, null, method, path, body)
    }

    private Map createTenant() {
        admin("POST", "/admin/tenants", [name: unique("Firma")]).body as Map
    }

    /** status and parsed body; proxyUser: the header the WAM proxy would send. */
    private Map request(String token, String proxyUser, String method, String path, Map body = null, String name = null) {
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
        if (name != null) {
            request = request.header("X-Remote-Name", name)
        }
        try {
            def response = client.toBlocking().exchange(request, Argument.of(Object))
            [status: response.status, body: response.body.orElse(null)]
        } catch (HttpClientResponseException e) {
            [status: e.status, body: e.response.getBody(Map).orElse(null)]
        }
    }

    private static String unique(String prefix) {
        prefix + "-" + UUID.randomUUID().toString().substring(0, 8)
    }
}
