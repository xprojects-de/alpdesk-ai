package de.alpdesk.services.tenant;

import de.alpdesk.dto.tenant.UserGroupLevel;
import de.alpdesk.dto.tenant.UserGroupRequest;
import de.alpdesk.dto.tenant.UserGroupResponse;
import de.alpdesk.entities.document.KnowledgeArea;
import de.alpdesk.entities.mcp.McpAccessLevel;
import de.alpdesk.entities.tenant.TenantUser;
import de.alpdesk.entities.tenant.UserGroup;
import de.alpdesk.repositories.document.KnowledgeAreaRepository;
import de.alpdesk.repositories.mcp.McpConnectionRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.repositories.tenant.TenantUserRepository;
import de.alpdesk.repositories.tenant.UserGroupRepository;
import de.alpdesk.security.WamProxy;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.transaction.TransactionOperations;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A tenant's user groups (docs/architecture/tenancy.md "User groups"), kept by its tenant admins and the admin: a
 * user's level for each connected software and the knowledge areas it finds come from its groups and nowhere else - a
 * user in no group that counts gets no access at all unless it is a tenant admin (TenantUserService.inAGroup; a group
 * without rights gives the chat and the company knowledge). With several groups the highest level counts, and the union
 * of their areas. A group with a WAM group counts for a member only while the WAM proxy's groups are checked
 * ({@link WamProxy#checksGroups}) and the proxy lists that IDM group - fail closed: without the check it counts for
 * nobody.
 */
@Singleton
public class UserGroupService {

    public static final int MAX_NAME = 100;
    /** Every tenant's group without rights - created with the tenant (and for the tenants of before by V17). */
    public static final String STANDARD_GROUP = "Standard";
    public static final int MAX_WAM_GROUP = 500;

    private final UserGroupRepository groups;
    private final TenantRepository tenants;
    private final TenantUserRepository users;
    private final KnowledgeAreaRepository areas;
    private final WamProxy wam;
    private final TransactionOperations<Connection> transactions;

    public UserGroupService(UserGroupRepository groups, TenantRepository tenants, TenantUserRepository users,
                            KnowledgeAreaRepository areas, WamProxy wam, TransactionOperations<Connection> transactions) {
        this.groups = groups;
        this.tenants = tenants;
        this.users = users;
        this.areas = areas;
        this.wam = wam;
        this.transactions = transactions;
    }

    /** The tenant's groups by name with their members and areas; 404 for an unknown tenant. */
    public List<UserGroupResponse> findAll(Long tenantId) {
        requireTenant(tenantId);
        Map<Long, List<Long>> members = byGroup(groups.findMembers(tenantId, wam.checksGroups()));
        Map<Long, List<Long>> areasOfGroups = byGroup(groups.findAreas(tenantId));
        Map<Long, List<UserGroupLevel>> levelsOfGroups = new HashMap<>();
        for (UserGroupRepository.Level level : groups.findLevels(tenantId)) {
            levelsOfGroups.computeIfAbsent(level.groupId(), _ -> new ArrayList<>()).add(level(level));
        }
        List<UserGroupResponse> all = new ArrayList<>();
        for (UserGroup group : groups.findByTenantIdOrderByName(tenantId)) {
            all.add(UserGroupResponse.from(group, members.getOrDefault(group.id(), List.of()),
                    areasOfGroups.getOrDefault(group.id(), List.of()), levelsOfGroups.getOrDefault(group.id(), List.of())));
        }
        return all;
    }

    /**
     * 400 for an invalid name or WAM group, another tenant's user, area or connection, a level without a value; 409 for
     * a name the tenant has. The group with its members, areas and levels - all in one transaction, or nothing.
     */
    @Transactional
    public UserGroupResponse create(Long tenantId, UserGroupRequest request, boolean byTenantAdmin) {
        requireTenant(tenantId);
        String wamGroup = wamGroup(request.wamGroup());
        refuseWamGroupByTenantAdmin(byTenantAdmin, null, wamGroup);
        refuseMembersFromIdm(wamGroup, request.userIds());
        UserGroup group = groups.save(new UserGroup(null, tenantId, requireAvailableName(tenantId, request.name(), null),
                wamGroup, Boolean.TRUE.equals(request.webSearch()), Instant.now()));
        replaceMembers(tenantId, group.id(), request.userIds() == null ? List.of() : request.userIds());
        replaceAreas(tenantId, group.id(), request.areaIds() == null ? List.of() : request.areaIds());
        replaceLevels(tenantId, group.id(), request.levels() == null ? List.of() : request.levels());
        return response(group);
    }

    /**
     * Left-out WAM group, members, areas and levels stay as they are - an empty WAM group removes it: a rename must never drop
     * the IDM check unasked. Empty for another tenant's or an unknown group; else as {@link #create}.
     */
    @Transactional
    public Optional<UserGroupResponse> update(Long tenantId, Long groupId, UserGroupRequest request,
                                              boolean byTenantAdmin) {
        // Locked: two saves at the same time would otherwise merge their members and areas.
        Optional<UserGroup> existing = groups.findByIdAndTenantIdForNoKeyUpdate(groupId, tenantId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        String wamGroup = request.wamGroup() == null ? existing.get().wamGroup() : wamGroup(request.wamGroup());
        refuseWamGroupByTenantAdmin(byTenantAdmin, existing.get().wamGroup(), wamGroup);
        refuseMembersFromIdm(wamGroup, request.userIds());
        UserGroup group = groups.update(new UserGroup(groupId, tenantId,
                requireAvailableName(tenantId, request.name(), groupId), wamGroup,
                request.webSearch() == null ? existing.get().webSearch() : request.webSearch(),
                existing.get().createdAt()));
        if (wamGroup != null) {
            // The IDM gives the members now - assigned ones from before must not linger behind it.
            groups.removeMembers(groupId);
        } else if (request.userIds() != null) {
            replaceMembers(tenantId, groupId, request.userIds());
        }
        if (request.areaIds() != null) {
            replaceAreas(tenantId, groupId, request.areaIds());
        }
        if (request.levels() != null) {
            replaceLevels(tenantId, groupId, request.levels());
        }
        return Optional.of(response(group));
    }

    /** Its members lose what it gave them; false for another tenant's or an unknown group. */
    @Transactional
    public boolean delete(Long tenantId, Long groupId) {
        return groups.deleteByIdAndTenantId(groupId, tenantId) > 0;
    }

    /** The groups each user of the tenant is in (users in none are left out). */
    public Map<Long, List<Long>> groupsOfUsers(Long tenantId) {
        Map<Long, List<Long>> byUser = new HashMap<>();
        for (UserGroupRepository.Item member : groups.findMembers(tenantId, wam.checksGroups())) {
            byUser.computeIfAbsent(member.itemId(), _ -> new ArrayList<>()).add(member.groupId());
        }
        return byUser;
    }

    /**
     * Puts the user into the tenant's group "Standard" - where the admin gives a person access with no group to choose
     * (an access request assigned, a user added from another tenant). Nothing when the tenant has none any more (a
     * tenant admin deleted or renamed it): the user then waits for a group like any other.
     */
    public void joinStandard(Long tenantId, Long userId) {
        groups.findByTenantIdAndNameIgnoreCase(tenantId, STANDARD_GROUP)
                .filter(group -> group.wamGroup() == null)
                .ifPresent(group -> groups.addMember(group.id(), userId));
    }

    /** Whether the WAM proxy's IDM groups are checked - only then a group with a WAM group counts for anyone. */
    public boolean checksGroups() {
        return wam.checksGroups();
    }

    public List<Long> groupsOfUser(Long userId) {
        return groups.findGroupIdsOfUser(userId, wam.checksGroups());
    }

    /**
     * Replaces the user's groups - in the caller's transaction (creating or changing the user). null: unchanged. 400
     * for a group that is not the tenant's. A group with a WAM group is passed over: the IDM gives its members - the
     * user dialog shows such a membership and sends it back unchanged.
     */
    public void assignUser(Long tenantId, Long userId, @Nullable List<Long> groupIds) {
        if (groupIds == null) {
            return;
        }
        Set<Long> checked = requireGroups(tenantId, groupIds);
        groups.removeFromGroups(userId);
        for (Long groupId : checked) {
            boolean fromIdm = groups.findById(groupId).map(group -> group.wamGroup() != null).orElse(true);
            if (!fromIdm) {
                groups.addMember(groupId, userId);
            }
        }
    }

    /**
     * The tenants with a group whose WAM group the IDM groups name - a person listed there gets a user in each
     * (AccessRequestService.signIn). None while the groups are not checked: fail closed.
     */
    public Set<Long> tenantsOfWamGroups(List<String> idmGroups) {
        if (!wam.checksGroups() || idmGroups.isEmpty()) {
            return Set.of();
        }
        Set<String> listed = new HashSet<>();
        idmGroups.forEach(name -> listed.add(name.toLowerCase(Locale.ROOT)));
        Set<Long> tenantIds = new TreeSet<>();
        for (UserGroupRepository.WamGroupOfTenant group : groups.findWamGroupsOfTenants()) {
            if (listed.contains(group.wamGroup().toLowerCase(Locale.ROOT))) {
                tenantIds.add(group.tenantId());
            }
        }
        return tenantIds;
    }

    /**
     * Only the admin sets, changes or removes a group's WAM group (409 for a tenant admin): it gives access - everyone
     * the IDM lists in it gets a user in the tenant at the sign-in -, and under WAM only the admin decides who gets a
     * user (decided 7 October 2026). Without WAM too: one set then would take effect the day WAM is turned on. A tenant
     * admin's save that leaves it as it is passes.
     */
    private void refuseWamGroupByTenantAdmin(boolean byTenantAdmin, @Nullable String before, @Nullable String after) {
        boolean changed = before == null ? after != null : !before.equalsIgnoreCase(after == null ? "" : after);
        if (byTenantAdmin && changed) {
            throw ServiceException.conflict("group.wamGroupByAdmin",
                    "Only the admin sets a group's WAM group - it gives everyone in that IDM group a user here");
        }
    }

    /** 400 for members sent to a group with a WAM group - the IDM gives them (an empty list is no member). */
    private static void refuseMembersFromIdm(@Nullable String wamGroup, @Nullable List<Long> userIds) {
        if (wamGroup != null && userIds != null && !userIds.isEmpty()) {
            throw ServiceException.badRequest("group.membersFromIdm",
                    "A group with a WAM group takes its members from the IDM - assign none");
        }
    }

    /** The connections the user may use through its groups, each with the highest level of them. */
    public List<McpConnectionRepository.AccessRow> accessOfUser(Long userId) {
        return groups.findAccessOfUser(userId, wam.checksGroups());
    }

    /** Whether one of the user's groups lets it search the web in the chat - a tenant admin too only through one. */
    public boolean searchesWeb(Long userId) {
        return groups.searchesWeb(userId, wam.checksGroups());
    }

    /** The knowledge areas the user finds through its groups (a tenant admin finds every area anyway). */
    public List<Long> areasOfUser(Long userId) {
        return groups.findAreaIdsOfUser(userId, wam.checksGroups());
    }

    /**
     * Keeps the IDM groups the WAM proxy sent on the user's request, so every check of the user's rights goes by them -
     * also those an answer makes after the request was read (its tools, chosen when it starts; a waiting action when it
     * is decided). It runs on every request: read without a transaction,
     * written in one only when they changed - with the user locked and compared again, so two requests at the same time
     * with different groups store one of them, never a mix of both. A name longer than a WAM group may be can never
     * match one and is not kept - the database's index would refuse it, and with it every request of the user.
     */
    public void recordWamGroups(Long userId, List<String> wamGroups) {
        Set<String> sent = new TreeSet<>();
        for (String wamGroup : wamGroups) {
            if (wamGroup.length() <= MAX_WAM_GROUP) {
                sent.add(wamGroup.toLowerCase(Locale.ROOT));
            }
        }
        if (storedWamGroups(userId).equals(sent)) {
            return;
        }
        transactions.executeWrite(_ -> {
            groups.lockUser(userId);
            if (!storedWamGroups(userId).equals(sent)) {
                groups.removeWamGroupsOfUser(userId);
                sent.forEach(name -> groups.addWamGroupOfUser(userId, name));
            }
            return null;
        });
    }

    /**
     * Without the check the IDM groups are not needed: those of the last run are deleted at the start, so nothing of
     * the directory is kept or shown while the feature is off (docs/privacy.md).
     */
    @EventListener
    public void forgetWamGroupsWhenUnchecked(StartupEvent event) {
        if (!wam.checksGroups()) {
            transactions.executeWrite(_ -> {
                groups.removeAllWamGroups();
                return null;
            });
        }
    }

    private Set<String> storedWamGroups(Long userId) {
        return new TreeSet<>(groups.findWamGroupsOfUser(userId));
    }

    /**
     * The IDM groups the WAM proxy sent on each user's last request (users without any left out) - for the admins to
     * see what the proxy really sends.
     */
    public Map<Long, List<String>> wamGroupsOfUsers(Long tenantId) {
        Map<Long, List<String>> byUser = new HashMap<>();
        for (UserGroupRepository.WamGroupOfUser wamGroup : groups.findWamGroups(tenantId)) {
            byUser.computeIfAbsent(wamGroup.userId(), _ -> new ArrayList<>()).add(wamGroup.name());
        }
        return byUser;
    }

    public List<String> wamGroupsOfUser(Long userId) {
        return groups.findWamGroupsOfUser(userId);
    }

    private void replaceMembers(Long tenantId, Long groupId, List<Long> userIds) {
        Set<Long> known = new LinkedHashSet<>();
        for (TenantUser user : users.findByTenantIdOrderByUsername(tenantId)) {
            known.add(user.id());
        }
        if (!allKnown(userIds, known)) {
            throw ServiceException.badRequest("group.unknownUser", "Unknown user");
        }
        Set<Long> checked = new LinkedHashSet<>(userIds);
        groups.removeMembers(groupId);
        checked.forEach(userId -> groups.addMember(groupId, userId));
    }

    private void replaceAreas(Long tenantId, Long groupId, List<Long> areaIds) {
        Set<Long> known = new LinkedHashSet<>();
        for (KnowledgeArea area : areas.findByTenantIdOrderByName(tenantId)) {
            known.add(area.id());
        }
        if (!allKnown(areaIds, known)) {
            throw ServiceException.badRequest("knowledgeArea.unknown", "Unknown area");
        }
        Set<Long> checked = new LinkedHashSet<>(areaIds);
        groups.removeAreas(groupId);
        checked.forEach(areaId -> groups.addArea(groupId, areaId));
    }

    /** A connection given twice takes its last level. */
    private void replaceLevels(Long tenantId, Long groupId, List<UserGroupLevel> levels) {
        Set<Long> known = new HashSet<>(groups.findConnectionIds(tenantId));
        Map<Long, McpAccessLevel> checked = new LinkedHashMap<>();
        for (UserGroupLevel level : levels) {
            if (level == null || level.connectionId() == null || !known.contains(level.connectionId())) {
                throw ServiceException.badRequest("group.unknownConnection", "Unknown connected software");
            }
            if (level.level() == null) {
                throw ServiceException.badRequest("group.levelRequired", "A level is READ or WRITE");
            }
            checked.put(level.connectionId(), level.level());
        }
        groups.removeLevels(groupId);
        checked.forEach((connectionId, level) -> groups.setLevel(groupId, connectionId, level.name()));
    }

    private Set<Long> requireGroups(Long tenantId, List<Long> groupIds) {
        Set<Long> known = new LinkedHashSet<>();
        for (UserGroup group : groups.findByTenantIdOrderByName(tenantId)) {
            known.add(group.id());
        }
        if (!allKnown(groupIds, known)) {
            throw ServiceException.badRequest("group.unknown", "Unknown group");
        }
        return new LinkedHashSet<>(groupIds);
    }

    /** Whether every id is one of the tenant's - null included as unknown. */
    private static boolean allKnown(List<Long> ids, Set<Long> known) {
        for (Long id : ids) {
            if (id == null || !known.contains(id)) {
                return false;
            }
        }
        return true;
    }

    private String requireAvailableName(Long tenantId, @Nullable String name, @Nullable Long ownId) {
        if (name == null || name.isBlank()) {
            throw ServiceException.badRequest("group.nameRequired", "The name is required");
        }
        String stripped = name.strip();
        if (stripped.length() > MAX_NAME) {
            throw ServiceException.badRequest("group.nameTooLong", "The name may have at most " + MAX_NAME
                    + " characters", Map.of("max", String.valueOf(MAX_NAME)));
        }
        Optional<UserGroup> same = groups.findByTenantIdAndNameIgnoreCase(tenantId, stripped);
        if (same.isPresent() && !same.get().id().equals(ownId)) {
            throw ServiceException.conflict("group.nameTaken", "The tenant already has a group of this name");
        }
        return stripped;
    }

    private static @Nullable String wamGroup(@Nullable String wamGroup) {
        if (wamGroup == null || wamGroup.isBlank()) {
            return null;
        }
        String stripped = wamGroup.strip();
        if (stripped.length() > MAX_WAM_GROUP) {
            throw ServiceException.badRequest("group.wamGroupTooLong", "The WAM group may have at most " + MAX_WAM_GROUP
                    + " characters", Map.of("max", String.valueOf(MAX_WAM_GROUP)));
        }
        return stripped;
    }

    private UserGroupResponse response(UserGroup group) {
        List<UserGroupLevel> levels = new ArrayList<>();
        for (UserGroupRepository.Level level : groups.findLevelsOfGroup(group.id())) {
            levels.add(level(level));
        }
        return UserGroupResponse.from(group, groups.findUserIdsOfGroup(group.id(), wam.checksGroups()), groups.findAreaIdsOfGroup(group.id()),
                levels);
    }

    private static UserGroupLevel level(UserGroupRepository.Level level) {
        return new UserGroupLevel(level.connectionId(), McpAccessLevel.valueOf(level.level()));
    }

    private static Map<Long, List<Long>> byGroup(List<UserGroupRepository.Item> items) {
        Map<Long, List<Long>> byGroup = new HashMap<>();
        for (UserGroupRepository.Item item : items) {
            byGroup.computeIfAbsent(item.groupId(), _ -> new ArrayList<>()).add(item.itemId());
        }
        return byGroup;
    }

    private void requireTenant(Long tenantId) {
        if (tenants.findById(tenantId).isEmpty()) {
            throw ServiceException.notFound("tenant.notFound", "Tenant not found");
        }
    }
}
