package de.alpdesk.controller.tenant

import de.alpdesk.repositories.tenant.UserGroupRepository
import de.alpdesk.services.mcp.FakeMcpSoftware
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.tenant.UserGroupService
import io.micronaut.context.ApplicationContext
import io.micronaut.context.event.StartupEvent
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * User groups (docs/architecture/tenancy.md "User groups"): a user's level for each connected software and its
 * knowledge areas come from its groups - the highest level of them, the union of their areas. Kept by the tenant admins
 * and the admin alike; a tenant never reaches another's groups.
 */
@MicronautTest(transactional = false)
class UserGroupSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    UserGroupService groups

    @Inject
    UserGroupRepository groupRepository

    @Inject
    ApplicationContext context

    @Shared
    AiApi api

    FakeMcpSoftware software

    def setup() {
        api = api ?: new AiApi(client)
    }

    def cleanup() {
        software?.stop()
    }

    def "a tenant admin creates, changes and deletes a group with members and areas; the admin keeps the same list"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def maria = api.user(tenant.id)
        def max = api.user(tenant.id)
        def sales = api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "Vertrieb"]) as Map

        when:
        def created = api.call(admin, "POST", "/tenant/admin/groups",
                [name: "  Vertrieb ", userIds: [maria.id], areaIds: [sales.id]]) as Map
        def byAdmin = api.admin("POST", "/admin/tenants/${tenant.id}/groups", [name: "Werkstatt", userIds: [max.id]]) as Map

        then:
        created.name == "Vertrieb"
        created.wamGroup == null
        created.userIds == [maria.id]
        created.areaIds == [sales.id]
        (api.admin("GET", "/admin/tenants/${tenant.id}/groups") as List<Map>)*.name == ["Standard", "Vertrieb", "Werkstatt"]
        userList(admin).find { it.id == maria.id }.groupIds.sort() == [api.standardGroup(tenant.id), created.id].sort()

        when:
        def changed = api.call(admin, "PUT", "/tenant/admin/groups/${created.id}",
                [name: "Verkauf", userIds: [maria.id, max.id], areaIds: []]) as Map

        then:
        changed.name == "Verkauf"
        changed.wamGroup == null
        changed.userIds == [maria.id, max.id].sort()
        changed.areaIds == []
        userList(admin).find { it.id == max.id }.groupIds.sort() == [api.standardGroup(tenant.id), created.id, byAdmin.id].sort()

        when:
        def deleted = api.status(admin, "DELETE", "/tenant/admin/groups/${byAdmin.id}")

        then:
        deleted == HttpStatus.NO_CONTENT
        (api.call(admin, "GET", "/tenant/admin/groups") as List<Map>)*.name == ["Standard", "Verkauf"]
        userList(admin).find { it.id == max.id }.groupIds.sort() == [api.standardGroup(tenant.id), created.id].sort()
    }

    def "left-out members and areas stay as they are"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def maria = api.user(tenant.id)
        def sales = api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "Vertrieb"]) as Map
        def group = api.group(admin, [maria.id], [sales.id])

        when:
        def renamed = api.call(admin, "PUT", "/tenant/admin/groups/${group.id}", [name: "Neu"]) as Map

        then:
        renamed.userIds == [maria.id]
        renamed.areaIds == [sales.id]
    }

    def "a group is refused: #reason"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        api.call(admin, "POST", "/tenant/admin/groups", [name: "Vertrieb"])
        def stranger = api.user(api.tenant().id)

        expect:
        api.status(admin, "POST", "/tenant/admin/groups", body(stranger.id)) == status

        where:
        reason                   | body                                                    | status
        "no name"                | { [name: null] }                                        | HttpStatus.BAD_REQUEST
        "a blank name"           | { [name: "   "] }                                       | HttpStatus.BAD_REQUEST
        "too long a name"        | { [name: "x" * 101] }                                   | HttpStatus.BAD_REQUEST
        "a name taken, any case" | { [name: "VERTRIEB"] }                                  | HttpStatus.CONFLICT
        "too long a WAM group"   | { [name: "G", wamGroup: "x" * 501] }                    | HttpStatus.BAD_REQUEST
        "another tenant's user"  | { Object strangerId -> [name: "G", userIds: [strangerId]] } | HttpStatus.BAD_REQUEST
        "an unknown area"        | { [name: "G", areaIds: [999_999]] }                     | HttpStatus.BAD_REQUEST
        "an unknown software"    | { [name: "G", levels: [[connectionId: 999_999, level: "READ"]]] } | HttpStatus.BAD_REQUEST
    }

    def "a group's levels per software are set with the group in one save - and are the ones the chat goes by"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def user = api.user(tenant.id)
        def connection = connect(admin)
        // The same fake software for the other tenant - connect() keeps one to stop at the end.
        def foreign = api.call(api.tenantAdminToken(api.tenant().id), "POST", "/tenant/admin/mcp-connections",
                [name: "Fremd", baseUrl: software.url()]) as Map

        when: "created with WRITE"
        def group = api.call(admin, "POST", "/tenant/admin/groups",
                [name: "Buchhaltung", userIds: [user.id], levels: [[connectionId: connection.id, level: "WRITE"]]]) as Map

        then: "the group shows it, the software's access list too, and its member gets it"
        group.levels == [[connectionId: connection.id, level: "WRITE"]]
        (api.call(admin, "GET", "/tenant/admin/mcp-connections/${connection.id}/access") as List<Map>)
                .find { it.groupId == group.id }.level == "WRITE"
        groups.accessOfUser(user.id as Long).collect { [it.connectionId(), it.level()] } == [[connection.id, "WRITE"]]

        when: "renamed without levels, then lowered to READ"
        def renamed = api.call(admin, "PUT", "/tenant/admin/groups/${group.id}", [name: "Buchhaltung 2"]) as Map
        def lowered = api.call(admin, "PUT", "/tenant/admin/groups/${group.id}",
                [name: "Buchhaltung 2", levels: [[connectionId: connection.id, level: "READ"]]]) as Map

        then:
        renamed.levels == [[connectionId: connection.id, level: "WRITE"]]
        lowered.levels == [[connectionId: connection.id, level: "READ"]]
        (api.admin("GET", "/admin/tenants/${tenant.id}/groups") as List<Map>).find { it.id == group.id }.levels ==
                [[connectionId: connection.id, level: "READ"]]

        when: "an empty list takes the software away"
        def none = api.call(admin, "PUT", "/tenant/admin/groups/${group.id}", [name: "Buchhaltung 2", levels: []]) as Map

        then:
        none.levels == []
        groups.accessOfUser(user.id as Long).isEmpty()

        expect: "another tenant's software and a level without a value are refused - nothing of the save is kept"
        api.error(admin, "PUT", "/tenant/admin/groups/${group.id}",
                [name: "Anders", levels: [[connectionId: foreign.id, level: "READ"]]]) == "Unknown connected software"
        api.error(admin, "POST", "/tenant/admin/groups",
                [name: "Ohne Stufe", levels: [[connectionId: connection.id, level: null]]]) == "A level is READ or WRITE"
        !(api.call(admin, "GET", "/tenant/admin/groups") as List<Map>)*.name.contains("Anders")
        !(api.call(admin, "GET", "/tenant/admin/groups") as List<Map>)*.name.contains("Ohne Stufe")
    }

    def "another tenant's group is not found, and joining it is refused"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def user = api.user(tenant.id)
        def foreign = api.group(api.tenantAdminToken(api.tenant().id), [])

        expect:
        api.status(admin, "PUT", "/tenant/admin/groups/${foreign.id}", [name: "X"]) == HttpStatus.NOT_FOUND
        api.status(admin, "DELETE", "/tenant/admin/groups/${foreign.id}") == HttpStatus.NOT_FOUND
        api.status(admin, "PUT", "/tenant/admin/users/${user.id}",
                [username: user.username, displayName: "Maria", groupIds: [foreign.id]]) == HttpStatus.BAD_REQUEST
        api.status(api.adminToken, "GET", "/admin/tenants/999999/groups") == HttpStatus.NOT_FOUND
        !(api.call(admin, "GET", "/tenant/admin/groups") as List<Map>)*.id.contains(foreign.id)
    }

    def "a user gets the highest level of its groups for each software and the union of their areas"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def user = api.user(tenant.id)
        def sales = api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "Vertrieb"]) as Map
        def workshop = api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "Werkstatt"]) as Map
        def connection = connect(admin)
        def readers = api.group(admin, [user.id], [sales.id])
        def writers = api.group(admin, [user.id], [workshop.id, sales.id])
        setLevel(admin, connection.id, readers.id, "READ")
        setLevel(admin, connection.id, writers.id, "WRITE")

        expect:
        groups.accessOfUser(user.id as Long).collect { [it.connectionId(), it.level()] } == [[connection.id, "WRITE"]]
        groups.areasOfUser(user.id as Long) == [sales.id, workshop.id].sort()

        when: "the WRITE group is deleted"
        api.status(admin, "DELETE", "/tenant/admin/groups/${writers.id}")

        then:
        groups.accessOfUser(user.id as Long).collect { it.level() } == ["READ"]
        groups.areasOfUser(user.id as Long) == [sales.id]
    }

    def "a user in no group has no software and no area; unchecked, a group with a WAM group counts for nobody"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def user = api.user(tenant.id)
        def member = api.user(tenant.id)
        def sales = api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "Vertrieb"]) as Map
        def workshop = api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "Werkstatt"]) as Map
        api.admin("POST", "/admin/tenants/${tenant.id}/groups", [name: "Vertrieb", wamGroup: "Vertrieb", areaIds: [sales.id]])
        groupRepository.addWamGroupOfUser(member.id as Long, "vertrieb")
        api.group(admin, [member.id], [workshop.id])

        expect: "no groups header configured: fail closed, the group without a WAM group counts"
        groups.accessOfUser(user.id as Long).isEmpty()
        groups.areasOfUser(user.id as Long).isEmpty()
        groups.areasOfUser(member.id as Long) == [workshop.id]
        (api.call(admin, "GET", "/tenant/admin/groups") as List<Map>).find { it.name == "Vertrieb" }.userIds == []
    }

    def "a group with a WAM group takes its members from the IDM - members sent are refused, a WAM group set drops the assigned ones"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def maria = api.user(tenant.id)
        def group = api.group(admin, [maria.id])
        String groups = "/admin/tenants/${tenant.id}/groups"

        expect: "members sent with a WAM group: 400, on create and on change - an empty list is none"
        api.status(api.adminToken, "POST", groups, [name: "IDM", wamGroup: "Vertrieb", userIds: [maria.id]]) == HttpStatus.BAD_REQUEST
        api.error(api.adminToken, "POST", groups, [name: "IDM", wamGroup: "Vertrieb", userIds: [maria.id]]) ==
                "A group with a WAM group takes its members from the IDM - assign none"
        api.status(api.adminToken, "POST", groups, [name: "IDM", wamGroup: "Vertrieb", userIds: []]) == HttpStatus.CREATED

        when: "a WAM group is set on the group with its assigned member"
        def changed = api.admin("PUT", "${groups}/${group.id}", [name: group.name, wamGroup: "Werkstatt"]) as Map

        then: "the assigned member is gone - the IDM gives the members now"
        changed.userIds == []
        groupRepository.findUserIdsOfGroup(group.id as Long, true) == []
        api.status(api.adminToken, "PUT", "${groups}/${group.id}", [name: group.name, userIds: [maria.id]]) == HttpStatus.BAD_REQUEST

        and: "a user's own groups pass over it - the IDM decides there"
        api.status(admin, "PUT", "/tenant/admin/users/${maria.id}",
                [username: maria.username, displayName: "Maria", groupIds: [group.id]]) == HttpStatus.OK
        groupRepository.findUserIdsOfGroup(group.id as Long, true) == []
    }

    def "a left-out WAM group stays as it is, an empty one removes it"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        String groups = "/admin/tenants/${tenant.id}/groups"
        def group = api.admin("POST", groups, [name: "Vertrieb", wamGroup: "Vertrieb"]) as Map

        when: "the tenant admin renames it, leaving the WAM group out"
        def renamed = api.call(admin, "PUT", "/tenant/admin/groups/${group.id}", [name: "Verkauf"]) as Map

        then:
        renamed.wamGroup == "Vertrieb"

        when:
        def cleared = api.admin("PUT", "${groups}/${group.id}", [name: "Verkauf", wamGroup: "  "]) as Map

        then:
        cleared.wamGroup == null
    }

    def "without WAM too, only the admin sets a group's WAM group - it would give the IDM's people a user once WAM is on"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def group = api.call(admin, "POST", "/tenant/admin/groups", [name: "Vertrieb"]) as Map

        expect:
        api.status(admin, "POST", "/tenant/admin/groups", [name: "Alle", wamGroup: "Alle-Mitarbeiter"]) == HttpStatus.CONFLICT
        api.status(admin, "PUT", "/tenant/admin/groups/${group.id}", [name: "Vertrieb", wamGroup: "Vertrieb"]) == HttpStatus.CONFLICT
        api.status(admin, "POST", "/tenant/admin/groups", [name: "Ohne", wamGroup: ""]) == HttpStatus.CREATED
    }

    def "without the check the IDM groups of the last run are deleted at the start"() {
        given:
        def tenant = api.tenant()
        def user = api.user(tenant.id)
        groupRepository.addWamGroupOfUser(user.id as Long, "gruppe1")

        when: "called as on the start - not published: that would migrate every tenant database of the suite again"
        groups.forgetWamGroupsWhenUnchecked(new StartupEvent(context))

        then:
        groupRepository.findWamGroupsOfUser(user.id as Long).isEmpty()
    }

    def "an IDM group name longer than any WAM group may be is not kept"() {
        given:
        def user = api.user(api.tenant().id)

        when:
        groups.recordWamGroups(user.id as Long, ["x" * 3000, "Gruppe1"])

        then:
        groups.wamGroupsOfUser(user.id as Long) == ["gruppe1"]
    }

    def "deleting a user (with its IDM groups), an area or a connection takes it out of every group"() {
        given:
        def tenant = api.tenant()
        def admin = api.tenantAdminToken(tenant.id)
        def user = api.user(tenant.id)
        def colleague = api.user(tenant.id)
        def sales = api.call(admin, "POST", "/tenant/admin/knowledge-areas", [name: "Vertrieb"]) as Map
        def connection = connect(admin)
        def group = api.group(admin, [user.id, colleague.id], [sales.id])
        setLevel(admin, connection.id, group.id, "READ")
        groupRepository.addWamGroupOfUser(user.id as Long, "gruppe1")

        when:
        api.status(admin, "DELETE", "/tenant/admin/users/${user.id}")
        api.status(admin, "DELETE", "/tenant/admin/knowledge-areas/${sales.id}")
        api.status(admin, "DELETE", "/tenant/admin/mcp-connections/${connection.id}")
        def after = (api.call(admin, "GET", "/tenant/admin/groups") as List<Map>).find { it.id == group.id }

        then:
        after.userIds == [colleague.id]
        after.areaIds == []
        groups.accessOfUser(colleague.id as Long).isEmpty()
        groupRepository.findWamGroupsOfUser(user.id as Long).isEmpty()
    }

    def "a user reaches none of the groups' admin API"() {
        given:
        def tenant = api.tenant()
        def user = api.userToken(tenant.id)

        expect:
        api.status(user, "GET", "/tenant/admin/groups") == HttpStatus.FORBIDDEN
        api.status(user, "POST", "/tenant/admin/groups", [name: "X"]) == HttpStatus.FORBIDDEN
        api.status(user, "GET", "/admin/tenants/${tenant.id}/groups") == HttpStatus.FORBIDDEN
    }

    private Map connect(String admin) {
        software = new FakeMcpSoftware()
        api.call(admin, "POST", "/tenant/admin/mcp-connections", [name: "Buchhaltung", baseUrl: software.url()]) as Map
    }

    private void setLevel(String admin, Object connectionId, Object groupId, String level) {
        api.setLevel(admin, groupId, connectionId, level)
    }

    private List<Map> userList(String admin) {
        api.call(admin, "GET", "/tenant/admin/users") as List<Map>
    }
}
