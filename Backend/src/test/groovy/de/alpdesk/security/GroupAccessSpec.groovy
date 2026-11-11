package de.alpdesk.security

import de.alpdesk.services.ai.AiApi
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
 * Access needs a group (docs/architecture/tenancy.md "User groups", decided 7 November 2026): a tenant user who is no
 * tenant admin gets in only through a group - its password login, every request of its token, the tenants it may
 * switch to. Every tenant has a group "Standard" without rights for it. WamGroupsSpec has the IDM's side.
 */
@MicronautTest(transactional = false)
class GroupAccessSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
    }

    def "a new tenant has its group Standard - no rights, nobody in it"() {
        when:
        def tenant = api.tenant()
        def groups = api.admin("GET", "/admin/tenants/${tenant.id}/groups") as List<Map>

        then:
        groups*.name == ["Standard"]
        groups[0].userIds == []
        groups[0].areaIds == []
        groups[0].levels == []
        !groups[0].webSearch
        groups[0].wamGroup == null
    }

    def "a user created by hand goes into Standard unless groups are named"() {
        given:
        def tenant = api.tenant()
        def standard = api.standardGroup(tenant.id)

        when:
        def created = api.admin("POST", "/admin/tenants/${tenant.id}/users",
                [username: "neu-" + AiApi.unique(), displayName: "Neu", password: AiApi.USER_PASSWORD]) as Map

        then:
        created.groupIds == [standard]
    }

    def "a user in no group does not get in - told so after the right password only; a tenant admin does"() {
        given:
        def tenant = api.tenant()
        def username = "ohne-" + AiApi.unique()
        api.admin("POST", "/admin/tenants/${tenant.id}/users",
                [username: username, displayName: "Ohne Gruppe", password: AiApi.USER_PASSWORD, groupIds: []])

        expect: "the right password: 409 user.noGroup - not 'wrong password', which would send the admin astray"
        login(username) == HttpStatus.CONFLICT
        loginCode(username) == "user.noGroup"

        and: "a wrong password: the plain 401, nothing about groups"
        login(username, "Falsches-Passwort-1") == HttpStatus.UNAUTHORIZED

        and:
        api.status(api.tenantAdminToken(tenant.id), "GET", "/tenant/account") == HttpStatus.OK
    }

    def "taken out of its last group, a user's next request is refused - put back, it gets in again"() {
        given:
        def tenant = api.tenant()
        def user = api.user(tenant.id)
        def standard = api.standardGroup(tenant.id)

        expect: "in Standard: the chat and the company knowledge"
        api.status(user.token as String, "GET", "/tenant/account") == HttpStatus.OK

        when:
        assign(tenant, user, [])

        then:
        api.status(user.token as String, "GET", "/tenant/account") == HttpStatus.FORBIDDEN
        login(user.username as String) == HttpStatus.CONFLICT

        when:
        assign(tenant, user, [standard])

        then:
        login(user.username as String) == HttpStatus.OK
    }

    def "a person chooses and switches only among the tenants where a group lets it in"() {
        given: "the same person added by the admin to a second tenant - in its Standard -, then taken out of it there"
        def first = api.tenant()
        def second = api.tenant()
        def user = api.user(first.id)
        def other = api.admin("POST", "/admin/tenants/${second.id}/users/from-tenant", [userId: user.id]) as Map
        def added = api.admin("GET", "/admin/tenants/${second.id}/groups") as List<Map>
        assert added.find { it.name == "Standard" }.userIds == [other.id]
        api.admin("PUT", "/admin/tenants/${second.id}/users/${other.id}",
                [username: user.username, displayName: "Maria", password: AiApi.USER_PASSWORD, groupIds: []])

        expect:
        (api.call(user.token as String, "GET", "/tenant/account") as Map).tenants*.id == [first.id]
        api.status(user.token as String, "POST", "/tenant/account/switch",
                [tenantId: second.id, password: AiApi.USER_PASSWORD]) == HttpStatus.NOT_FOUND

        when: "put into the second tenant's Standard"
        assign(second, [id: other.id, username: user.username], [api.standardGroup(second.id)])

        then:
        (api.call(user.token as String, "GET", "/tenant/account") as Map).tenants*.id.toSet() == [first.id, second.id].toSet()
    }

    private void assign(Map tenant, Map user, List groupIds) {
        api.admin("PUT", "/admin/tenants/${tenant.id}/users/${user.id}",
                [username: user.username, displayName: "Maria", groupIds: groupIds])
    }

    private HttpStatus login(String username, String password = AiApi.USER_PASSWORD) {
        try {
            client.toBlocking().exchange(HttpRequest.POST("/login", [username: username, password: password])).status
        } catch (HttpClientResponseException e) {
            e.status
        }
    }

    private String loginCode(String username) {
        try {
            client.toBlocking().exchange(HttpRequest.POST("/login", [username: username, password: AiApi.USER_PASSWORD]))
            null
        } catch (HttpClientResponseException e) {
            e.response.getBody(Map).map { it.code }.orElse(null)
        }
    }
}
