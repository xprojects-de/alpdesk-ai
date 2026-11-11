package de.alpdesk.security

import de.alpdesk.services.mcp.FakeMcpSoftware
import de.alpdesk.services.tenant.UserGroupService
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

/**
 * A user group checked against the IDM groups the WAM proxy sends (docs/architecture/tenancy.md "User groups"): a group
 * with a WAM group takes its members from the IDM alone - every user of its tenant while the proxy lists that IDM
 * group, assigned or not; fail closed, a request without the header lists none. Groups without a WAM group count for
 * their assigned members. And the IDM gives accounts: a person listed in such an IDM group gets a user in the tenant.
 */
@MicronautTest(transactional = false)
@Property(name = "app.wam.enabled", value = "true")
@Property(name = "app.wam.groups-header", value = "X-Remote-Groups")
@Property(name = "app.wam.name-header", value = "X-Remote-Name")
class WamGroupsSpec extends Specification {

    static final String USER_HEADER = "X-Remote-User"
    static final String GROUPS_HEADER = "X-Remote-Groups"

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    UserGroupService groups

    @Inject
    de.alpdesk.repositories.tenant.UserGroupRepository groupRepository

    @Shared
    String adminToken

    FakeMcpSoftware software

    def setup() {
        if (!adminToken) {
            adminToken = client.toBlocking().retrieve(
                    HttpRequest.POST("/login", [username: "alpdesk", password: "alpdesk"]), Map).access_token
        }
    }

    def cleanup() {
        software?.stop()
    }

    def "a group with a WAM group counts only while the proxy lists it; a group without one always counts"() {
        given:
        def tenant = createTenant()
        def user = approvedUser(tenant)
        def sales = area(tenant, "Vertrieb")
        def everyone = area(tenant, "Alle")
        admin("POST", "/admin/tenants/${tenant.id}/groups",
                [name: "Vertrieb", wamGroup: "Gruppe1", areaIds: [sales.id]])
        admin("POST", "/admin/tenants/${tenant.id}/groups", [name: "Alle", userIds: [user.id], areaIds: [everyone.id]])

        expect: "the login page learns that the groups are checked"
        request(null, null, null, "GET", "/login/wam").body == [enabled: true, groupsChecked: true]

        when: "the proxy lists the IDM group among others - in another case, with spaces"
        request(user.token, user.username, "gruppe1 , Gruppe2", "GET", "/tenant/account")

        then:
        groups.areasOfUser(user.id as Long) == [sales.id, everyone.id].sort()
        (admin("GET", "/admin/tenants/${tenant.id}/users").body as List<Map>).find { it.id == user.id }.wamGroups ==
                ["gruppe1", "gruppe2"]

        when: "the IDM group is taken away"
        request(user.token, user.username, "Gruppe2", "GET", "/tenant/account")

        then:
        groups.areasOfUser(user.id as Long) == [everyone.id]

        when: "the proxy lists it again, then sends no groups header at all"
        request(user.token, user.username, "Gruppe1,Gruppe2", "GET", "/tenant/account")
        def listed = groups.areasOfUser(user.id as Long)
        request(user.token, user.username, null, "GET", "/tenant/account")

        then: "fail closed"
        listed == [sales.id, everyone.id].sort()
        groups.areasOfUser(user.id as Long) == [everyone.id]
    }

    def "each group is checked on its own - the software's level and the areas come from the listed ones only"() {
        given: "two groups with different WAM groups: READ and an area, WRITE and another area"
        def tenant = createTenant()
        def user = approvedUser(tenant)
        def connection = connect(tenant)
        def sales = area(tenant, "Vertrieb")
        def workshop = area(tenant, "Werkstatt")
        def readers = admin("POST", "/admin/tenants/${tenant.id}/groups",
                [name: "Leser", wamGroup: "Gruppe1", areaIds: [sales.id]]).body as Map
        def writers = admin("POST", "/admin/tenants/${tenant.id}/groups",
                [name: "Schreiber", wamGroup: "Gruppe2", areaIds: [workshop.id]]).body as Map
        setLevel(tenant, connection.id, readers.id, "READ")
        setLevel(tenant, connection.id, writers.id, "WRITE")

        when: "the proxy lists both"
        request(user.token, user.username, "Gruppe1,Gruppe2", "GET", "/tenant/account")

        then: "the highest level, both areas"
        levels(user) == [[connection.id, "WRITE"]]
        groups.areasOfUser(user.id as Long) == [sales.id, workshop.id].sort()

        when: "only Gruppe1"
        request(user.token, user.username, "Gruppe1", "GET", "/tenant/account")

        then:
        levels(user) == [[connection.id, "READ"]]
        groups.areasOfUser(user.id as Long) == [sales.id]

        when: "none of them"
        request(user.token, user.username, "Gruppe3", "GET", "/tenant/account")

        then:
        levels(user).isEmpty()
        groups.areasOfUser(user.id as Long).isEmpty()
    }

    def "the chat offers the software's tools only while the proxy lists the group"() {
        given:
        def tenant = createTenant()
        def user = approvedUser(tenant)
        def connection = connect(tenant)
        def group = admin("POST", "/admin/tenants/${tenant.id}/groups",
                [name: "Buchhaltung", wamGroup: "Gruppe1"]).body as Map
        setLevel(tenant, connection.id, group.id, "READ")
        def prompt = (connection.prompts as List<Map>)[0]
        admin("PUT", "/admin/tenants/${tenant.id}/mcp-connections/${connection.id}/prompts/${prompt.id}", [enabled: true])

        expect:
        (request(user.token, user.username, "Gruppe1", "GET", "/tenant/chat/prompts").body as List).size() == 1
        request(user.token, user.username, "Gruppe2", "GET", "/tenant/chat/prompts").body == []
    }

    def "membership follows the header alone: listed counts without assigning, an assigned row from before never counts"() {
        given:
        def tenant = createTenant()
        def listed = approvedUser(tenant)
        def assignedBefore = approvedUser(tenant)
        def sales = area(tenant, "Vertrieb")
        def group = admin("POST", "/admin/tenants/${tenant.id}/groups",
                [name: "Vertrieb", wamGroup: "Gruppe1", areaIds: [sales.id]]).body as Map
        // A member row from before 2.0.0, when a group with a WAM group was also assigned.
        groupRepository.addMember(group.id as Long, assignedBefore.id as Long)

        when:
        request(listed.token, listed.username, "Gruppe1", "GET", "/tenant/account")
        request(assignedBefore.token, assignedBefore.username, "Gruppe2", "GET", "/tenant/account")

        then:
        groups.areasOfUser(listed.id as Long) == [sales.id]
        groups.areasOfUser(assignedBefore.id as Long).isEmpty()

        and: "the group shows the members the header gave, the users' list their group"
        (admin("GET", "/admin/tenants/${tenant.id}/groups").body as List<Map>).find { it.id == group.id }.userIds == [listed.id]
        // Approved by the admin, it is in the tenant's Standard too.
        ((admin("GET", "/admin/tenants/${tenant.id}/users").body as List<Map>).find { it.id == listed.id }.groupIds as List)
                .contains(group.id)

        when: "taken out of the IDM group: keeps its account and Standard's access, reaches nothing of Vertrieb"
        request(listed.token, listed.username, "Gruppe2", "GET", "/tenant/account").status == HttpStatus.OK

        then:
        groups.areasOfUser(listed.id as Long).isEmpty()
        request(listed.token, listed.username, "Gruppe2", "GET", "/tenant/account").status == HttpStatus.OK
    }

    def "the IDM gives accounts: a person listed gets an active user in every tenant whose group names the IDM group"() {
        given: "a topic and a general chat, each with a group of its own IDM group; a third tenant without"
        def topic = createTenant()
        def general = createTenant()
        def other = createTenant()
        def username = unique("idm")
        def thema = unique("AI-Thema")
        def allgemein = unique("AI-Allgemein")
        admin("POST", "/admin/tenants/${topic.id}/groups", [name: "Thema", wamGroup: thema])
        admin("POST", "/admin/tenants/${general.id}/groups", [name: "Allgemein", wamGroup: allgemein])

        when: "listed in the topic's group only - signed in there at once"
        def first = request(null, username, "$thema,Mail-Users".toString(), "POST", "/login/wam", null, "Maria Muster")

        then:
        first.status == HttpStatus.OK
        def account = request(first.body.access_token, username, "$thema,Mail-Users".toString(), "GET", "/tenant/account").body
        account.tenantId == topic.id
        account.displayName == "Maria Muster"
        !account.admin
        (admin("GET", "/admin/access-requests").body as List<Map>).every { it.username != username }
        (admin("GET", "/admin/tenants/${other.id}/users").body as List).every { it.username != username }

        and: "its IDM groups count from the first request"
        (admin("GET", "/admin/tenants/${topic.id}/users").body as List<Map>).find { it.username == username }.wamGroups ==
                [thema.toLowerCase(), "mail-users"]

        when: "listed in both - one more account, and the choice"
        def both = request(null, username, "$thema,$allgemein".toString(), "POST", "/login/wam")

        then:
        both.status == HttpStatus.OK
        both.body.access_token == null
        (both.body.tenants as List<Map>)*.id.toSet() == [topic.id, general.id].toSet()
    }

    def "the IDM's accounts speak the browser's language - or the one the person chose in another tenant"() {
        given:
        def topic = createTenant()
        def general = createTenant()
        def username = unique("idm-lang")
        def thema = unique("AI-Thema")
        def allgemein = unique("AI-Allgemein")
        admin("POST", "/admin/tenants/${topic.id}/groups", [name: "Thema", wamGroup: thema])
        admin("POST", "/admin/tenants/${general.id}/groups", [name: "Allgemein", wamGroup: allgemein])

        when: "the first account from a German browser"
        def first = request(null, username, thema, "POST", "/login/wam", [language: "de"])

        then:
        request(first.body.access_token, username, thema, "GET", "/tenant/account").body.language == "de"

        when: "the person switches to English there, then the IDM lists it in the general chat - the browser still German"
        request(first.body.access_token, username, thema, "PUT", "/tenant/account/language", [language: "en"])
        request(null, username, "$thema,$allgemein".toString(), "POST", "/login/wam", [language: "de"])
        def inGeneral = request(null, username, "$thema,$allgemein".toString(), "POST", "/login/wam",
                [tenantId: general.id, language: "de"])

        then: "the person's choice, not the browser's"
        request(inGeneral.body.access_token, username, "$thema,$allgemein".toString(), "GET", "/tenant/account")
                .body.language == "en"

        and: "a browser language that is no tag is left out - English"
        def other = unique("idm-lang")
        def odd = request(null, other, thema, "POST", "/login/wam", [language: "Deutsch!"])
        request(odd.body.access_token, other, thema, "GET", "/tenant/account").body.language == "en"
    }

    def "taken out of the IDM group, the IDM's user gets no further - told so at the sign-in; listed again, it is back"() {
        given:
        def tenant = createTenant()
        def username = unique("idm-out")
        def wamGroup = unique("AI-Allgemein")
        admin("POST", "/admin/tenants/${tenant.id}/groups", [name: "Allgemein", wamGroup: wamGroup])
        def token = request(null, username, wamGroup, "POST", "/login/wam").body.access_token as String

        expect:
        request(token, username, wamGroup, "GET", "/tenant/account").status == HttpStatus.OK

        when: "the IDM no longer lists the group"
        def next = request(token, username, "Mail-Users", "GET", "/tenant/account")
        def signIn = request(null, username, "Mail-Users", "POST", "/login/wam")

        then:
        next.status == HttpStatus.FORBIDDEN
        signIn.status == HttpStatus.CONFLICT
        signIn.body.code == "user.noGroup"

        when: "listed again"
        def back = request(null, username, wamGroup, "POST", "/login/wam")

        then:
        back.status == HttpStatus.OK
        request(back.body.access_token as String, username, wamGroup, "GET", "/tenant/account").status == HttpStatus.OK
    }

    def "a remembered tenant where the person lost its last group answers 404 while it has another - so the browser forgets it"() {
        given: "a person with an account in a topic and in the general chat, each through its IDM group"
        def topic = createTenant()
        def general = createTenant()
        def username = unique("idm-remembered")
        def thema = unique("AI-Thema")
        def allgemein = unique("AI-Allgemein")
        admin("POST", "/admin/tenants/${topic.id}/groups", [name: "Thema", wamGroup: thema])
        admin("POST", "/admin/tenants/${general.id}/groups", [name: "Allgemein", wamGroup: allgemein])
        request(null, username, "$thema,$allgemein".toString(), "POST", "/login/wam")

        when: "the IDM drops the topic's group, the browser still remembers the topic"
        def remembered = request(null, username, allgemein, "POST", "/login/wam", [tenantId: topic.id])
        def withoutChoice = request(null, username, allgemein, "POST", "/login/wam")

        then: "not found - and signed in to the general chat once the choice is forgotten"
        remembered.status == HttpStatus.NOT_FOUND
        remembered.body.code == "wam.tenantNotFound"
        withoutChoice.status == HttpStatus.OK
        request(withoutChoice.body.access_token as String, username, allgemein, "GET", "/tenant/account").body.tenantId ==
                general.id

        when: "the IDM drops the general chat's group too - the person is in no group anywhere"
        def nowhere = request(null, username, "Mail-Users", "POST", "/login/wam", [tenantId: topic.id])

        then: "told so, nothing to wait for"
        nowhere.status == HttpStatus.CONFLICT
        nowhere.body.code == "user.noGroup"
    }

    def "an existing user is not touched by the IDM, an open access request goes, a person in no such group waits"() {
        given:
        def tenant = createTenant()
        def thema = unique("AI-Thema")
        admin("POST", "/admin/tenants/${tenant.id}/groups", [name: "Thema", wamGroup: thema])
        def known = approvedUser(tenant)
        admin("PUT", "/admin/tenants/${tenant.id}/users/${known.id}", [username: known.username, displayName: "Bekannt", admin: true])
        def waiting = unique("wartend")
        request(null, waiting, null, "POST", "/login/wam")
        def stranger = unique("fremd")

        when:
        def knownAgain = request(null, known.username, thema, "POST", "/login/wam", null, "Anderer Name")
        def nowListed = request(null, waiting, thema, "POST", "/login/wam")
        def notListed = request(null, stranger, "Mail-Users", "POST", "/login/wam")

        then: "the known user keeps its name and rights"
        knownAgain.status == HttpStatus.OK
        def users = admin("GET", "/admin/tenants/${tenant.id}/users").body as List<Map>
        users.find { it.id == known.id }.displayName == "Bekannt"
        users.find { it.id == known.id }.admin
        users.count { it.username == known.username } == 1

        and: "the waiting person got its account, its request is gone"
        nowListed.status == HttpStatus.OK
        (admin("GET", "/admin/access-requests").body as List<Map>).every { it.username != waiting }

        and: "someone in no IDM group of a group waits as before"
        notListed.status == HttpStatus.CONFLICT
        (admin("GET", "/admin/access-requests").body as List<Map>).any { it.username == stranger }
    }

    def "a deactivated tenant gets no account from the IDM"() {
        given:
        def tenant = createTenant()
        def thema = unique("AI-Thema")
        admin("POST", "/admin/tenants/${tenant.id}/groups", [name: "Thema", wamGroup: thema])
        admin("PUT", "/admin/tenants/${tenant.id}", [name: tenant.name, active: false])
        def username = unique("aus")

        expect:
        request(null, username, thema, "POST", "/login/wam").status == HttpStatus.CONFLICT
        (admin("GET", "/admin/tenants/${tenant.id}/users").body as List).every { it.username != username }
    }

    def "only the admin sets, changes or removes a WAM group - it gives access; a tenant admin's save that keeps it passes"() {
        given: "a tenant admin the proxy let through, and a group whose WAM group the admin set"
        def tenant = createTenant()
        def username = unique("chef")
        request(null, username, null, "POST", "/login/wam")
        def requestId = (admin("GET", "/admin/access-requests").body as List<Map>).find { it.username == username }.id
        admin("POST", "/admin/access-requests/${requestId}/assign", [tenantId: tenant.id, active: true, admin: true])
        def token = request(null, username, null, "POST", "/login/wam").body.access_token as String
        def thema = unique("AI-Thema")
        def group = admin("POST", "/admin/tenants/${tenant.id}/groups", [name: "Thema", wamGroup: thema]).body as Map

        expect: "the tenant admin cannot set, change or remove it - 409"
        def created = request(token, username, null, "POST", "/tenant/admin/groups", [name: "Alle", wamGroup: unique("AI-Alle")])
        created.status == HttpStatus.CONFLICT
        created.body.code == "group.wamGroupByAdmin"
        request(token, username, null, "PUT", "/tenant/admin/groups/${group.id}", [name: "Thema", wamGroup: "AI-Andere"]).status ==
                HttpStatus.CONFLICT
        request(token, username, null, "PUT", "/tenant/admin/groups/${group.id}", [name: "Thema", wamGroup: ""]).status ==
                HttpStatus.CONFLICT

        and: "a save that keeps it - sent again, in another case, or left out - and a group without one pass"
        request(token, username, null, "PUT", "/tenant/admin/groups/${group.id}", [name: "Thema neu", wamGroup: thema.toUpperCase()]).status ==
                HttpStatus.OK
        request(token, username, null, "PUT", "/tenant/admin/groups/${group.id}", [name: "Thema neu"]).status == HttpStatus.OK
        request(token, username, null, "POST", "/tenant/admin/groups", [name: "Ohne", wamGroup: ""]).status == HttpStatus.CREATED

        and: "the admin changes it"
        admin("PUT", "/admin/tenants/${tenant.id}/groups/${group.id}", [name: "Thema neu", wamGroup: "AI-Andere"]).status ==
                HttpStatus.OK
    }

    /** A tenant user the proxy let through, assigned and approved by the admin at once. */
    private Map approvedUser(Map tenant) {
        def username = unique("maria")
        request(null, username, null, "POST", "/login/wam")
        def id = (admin("GET", "/admin/access-requests").body as List<Map>).find { it.username == username }.id
        def assigned = admin("POST", "/admin/access-requests/${id}/assign", [tenantId: tenant.id, active: true])
        assert assigned.status == HttpStatus.CREATED
        def signedIn = request(null, username, null, "POST", "/login/wam")
        assert signedIn.status == HttpStatus.OK
        [id: assigned.body.id, username: username, token: signedIn.body.access_token]
    }

    private Map createTenant() {
        admin("POST", "/admin/tenants", [name: unique("Firma")]).body as Map
    }

    private Map area(Map tenant, String name) {
        admin("POST", "/admin/tenants/${tenant.id}/knowledge-areas", [name: name]).body as Map
    }

    private Map connect(Map tenant) {
        software = new FakeMcpSoftware()
        admin("POST", "/admin/tenants/${tenant.id}/mcp-connections", [name: "Buchhaltung", baseUrl: software.url()]).body as Map
    }

    private void setLevel(Map tenant, Object connectionId, Object groupId, String level) {
        // As the group's dialog: the level goes in the group's own request, its other levels kept.
        def base = "/admin/tenants/${tenant.id}/groups"
        def group = (admin("GET", base).body as List<Map>).find { it.id == groupId }
        def levels = (group.levels as List<Map>).findAll { it.connectionId != connectionId } +
                [[connectionId: connectionId, level: level]]
        admin("PUT", "${base}/${groupId}", [name: group.name, levels: levels])
    }

    private List<List<Object>> levels(Map user) {
        groups.accessOfUser(user.id as Long).collect { [it.connectionId(), it.level()] }
    }

    private Map admin(String method, String path, Map body = null) {
        request(adminToken, null, null, method, path, body)
    }

    /** status and parsed body; proxyUser, proxyGroups and name: the headers the WAM proxy would send. */
    private Map request(String token, String proxyUser, String proxyGroups, String method, String path, Map body = null,
                        String name = null) {
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
        if (proxyGroups != null) {
            request = request.header(GROUPS_HEADER, proxyGroups)
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
        prefix + "-" + UUID.randomUUID().toString().take(8)
    }
}
