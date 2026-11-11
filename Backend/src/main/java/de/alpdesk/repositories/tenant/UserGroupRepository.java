package de.alpdesk.repositories.tenant;

import de.alpdesk.entities.tenant.UserGroup;
import de.alpdesk.repositories.mcp.McpConnectionRepository;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

/**
 * The user groups, their members, levels and areas - and what a user gets from its groups (management database). A
 * group without a WAM group has the members assigned to it; one with a WAM group takes its members from the IDM alone
 * (decided 5 November 2026, roadmap point 51): every user of its tenant whose IDM groups - as the WAM proxy listed them
 * on the user's last request - name it, while they are checked (checkWamGroups); nobody else, assigned or not. Fail
 * closed: without the check, or without the header, a group with a WAM group has no members.
 */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UserGroupRepository extends CrudRepository<UserGroup, Long> {

    /** The groups the user (:userId) is a member of - assigned to one without a WAM group, or listed by the IDM. */
    String GROUPS_OF_USER = """
            (SELECT m.group_id FROM user_group_member m JOIN user_group g ON g.id = m.group_id
              WHERE m.user_id = :userId AND g.wam_group IS NULL
             UNION
             SELECT g.id FROM user_group g JOIN tenant_user u ON u.tenant_id = g.tenant_id
              JOIN tenant_user_wam_group w ON w.user_id = u.id AND LOWER(w.name) = LOWER(g.wam_group)
              WHERE u.id = :userId AND :checkWamGroups = TRUE)""";

    /** Every member of the tenant's (:tenantId) groups - assigned, or listed by the IDM - as (group_id, item_id). */
    String MEMBERS_OF_TENANT = """
            SELECT m.group_id, m.user_id AS item_id FROM user_group_member m JOIN user_group g ON g.id = m.group_id
             WHERE g.tenant_id = :tenantId AND g.wam_group IS NULL
            UNION
            SELECT g.id AS group_id, u.id AS item_id FROM user_group g JOIN tenant_user u ON u.tenant_id = g.tenant_id
             JOIN tenant_user_wam_group w ON w.user_id = u.id AND LOWER(w.name) = LOWER(g.wam_group)
             WHERE g.tenant_id = :tenantId AND :checkWamGroups = TRUE""";

    List<UserGroup> findByTenantIdOrderByName(Long tenantId);

    Optional<UserGroup> findByIdAndTenantId(Long id, Long tenantId);

    /** The group, locked until the transaction ends against another update. */
    @Query("SELECT * FROM user_group WHERE id = :id AND tenant_id = :tenantId FOR NO KEY UPDATE")
    Optional<UserGroup> findByIdAndTenantIdForNoKeyUpdate(Long id, Long tenantId);

    Optional<UserGroup> findByTenantIdAndNameIgnoreCase(Long tenantId, String name);

    int deleteByIdAndTenantId(Long id, Long tenantId);

    /** The connections the user may use through its groups, with the highest level of them. */
    @Query("SELECT a.connection_id, CASE WHEN bool_or(a.level = 'WRITE') THEN 'WRITE' ELSE 'READ' END AS level"
            + " FROM group_mcp_access a WHERE a.group_id IN " + GROUPS_OF_USER
            + " GROUP BY a.connection_id ORDER BY a.connection_id")
    List<McpConnectionRepository.AccessRow> findAccessOfUser(Long userId, boolean checkWamGroups);

    /** The areas the user finds through its groups. */
    @Query("SELECT DISTINCT a.area_id FROM group_area a WHERE a.group_id IN " + GROUPS_OF_USER
            + " ORDER BY a.area_id")
    List<Long> findAreaIdsOfUser(Long userId, boolean checkWamGroups);

    /** Whether one of the user's groups lets it search the web. */
    @Query("SELECT EXISTS (SELECT 1 FROM user_group g WHERE g.web_search AND g.id IN " + GROUPS_OF_USER + ")")
    boolean searchesWeb(Long userId, boolean checkWamGroups);

    @Query(MEMBERS_OF_TENANT + " ORDER BY item_id")
    List<Item> findMembers(Long tenantId, boolean checkWamGroups);

    /** The group's members: assigned, or - for a group with a WAM group - the users the IDM lists in it. */
    @Query("""
            SELECT m.user_id FROM user_group_member m JOIN user_group g ON g.id = m.group_id
             WHERE m.group_id = :groupId AND g.wam_group IS NULL
            UNION
            SELECT u.id FROM user_group g JOIN tenant_user u ON u.tenant_id = g.tenant_id
             JOIN tenant_user_wam_group w ON w.user_id = u.id AND LOWER(w.name) = LOWER(g.wam_group)
             WHERE g.id = :groupId AND :checkWamGroups = TRUE
            ORDER BY 1""")
    List<Long> findUserIdsOfGroup(Long groupId, boolean checkWamGroups);

    /** The tenants and WAM groups of every group with a WAM group - the IDM's groups that give an account. */
    @Query("SELECT tenant_id, wam_group FROM user_group WHERE wam_group IS NOT NULL")
    List<WamGroupOfTenant> findWamGroupsOfTenants();

    @Query("SELECT area_id FROM group_area WHERE group_id = :groupId ORDER BY area_id")
    List<Long> findAreaIdsOfGroup(Long groupId);

    @Query("SELECT group_id FROM " + GROUPS_OF_USER + " AS member ORDER BY group_id")
    List<Long> findGroupIdsOfUser(Long userId, boolean checkWamGroups);

    @Query("DELETE FROM user_group_member WHERE group_id = :groupId")
    void removeMembers(Long groupId);

    @Query("DELETE FROM user_group_member WHERE user_id = :userId")
    void removeFromGroups(Long userId);

    @Query("INSERT INTO user_group_member (group_id, user_id) VALUES (:groupId, :userId) ON CONFLICT DO NOTHING")
    void addMember(Long groupId, Long userId);

    @Query("""
            SELECT a.group_id, a.area_id AS item_id FROM group_area a JOIN user_group g ON g.id = a.group_id
            WHERE g.tenant_id = :tenantId ORDER BY a.area_id""")
    List<Item> findAreas(Long tenantId);

    @Query("DELETE FROM group_area WHERE group_id = :groupId")
    void removeAreas(Long groupId);

    @Query("INSERT INTO group_area (group_id, area_id) VALUES (:groupId, :areaId)")
    void addArea(Long groupId, Long areaId);

    @Query("SELECT group_id, level FROM group_mcp_access WHERE connection_id = :connectionId")
    List<GroupLevel> findAccessOfConnection(Long connectionId);

    @Query("""
            INSERT INTO group_mcp_access (group_id, connection_id, level) VALUES (:groupId, :connectionId, :level)
            ON CONFLICT (group_id, connection_id) DO UPDATE SET level = EXCLUDED.level""")
    void setLevel(Long groupId, Long connectionId, String level);

    @Query("DELETE FROM group_mcp_access WHERE group_id = :groupId")
    void removeLevels(Long groupId);

    @Query("""
            SELECT a.group_id, a.connection_id, a.level FROM group_mcp_access a JOIN user_group g ON g.id = a.group_id
            WHERE g.tenant_id = :tenantId ORDER BY a.connection_id""")
    List<Level> findLevels(Long tenantId);

    @Query("SELECT group_id, connection_id, level FROM group_mcp_access WHERE group_id = :groupId ORDER BY connection_id")
    List<Level> findLevelsOfGroup(Long groupId);

    /** The tenant's connected software - a group's levels may name only these. */
    @Query("SELECT id FROM mcp_connection WHERE tenant_id = :tenantId")
    List<Long> findConnectionIds(Long tenantId);

    @Query("SELECT name FROM tenant_user_wam_group WHERE user_id = :userId ORDER BY name")
    List<String> findWamGroupsOfUser(Long userId);

    @Query("""
            SELECT w.user_id, w.name FROM tenant_user_wam_group w JOIN tenant_user u ON u.id = w.user_id
            WHERE u.tenant_id = :tenantId ORDER BY w.name""")
    List<WamGroupOfUser> findWamGroups(Long tenantId);

    /** Locks the user against another change of its IDM groups until the transaction ends. */
    @Query("SELECT id FROM tenant_user WHERE id = :userId FOR NO KEY UPDATE")
    Optional<Long> lockUser(Long userId);

    @Query("DELETE FROM tenant_user_wam_group")
    void removeAllWamGroups();

    @Query("DELETE FROM tenant_user_wam_group WHERE user_id = :userId")
    void removeWamGroupsOfUser(Long userId);

    @Query("INSERT INTO tenant_user_wam_group (user_id, name) VALUES (:userId, :name) ON CONFLICT DO NOTHING")
    void addWamGroupOfUser(Long userId, String name);

    @Introspected
    record WamGroupOfTenant(Long tenantId, String wamGroup) {
    }

    /** A member or an area of a group (itemId: the user's or the area's id). */
    @Introspected
    record Item(Long groupId, Long itemId) {
    }

    @Introspected
    record GroupLevel(Long groupId, String level) {
    }

    /** A group's level for one connection. */
    @Introspected
    record Level(Long groupId, Long connectionId, String level) {
    }

    @Introspected
    record WamGroupOfUser(Long userId, String name) {
    }
}
