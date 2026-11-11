package de.alpdesk.services.document;

import de.alpdesk.entities.document.KnowledgeArea;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.entities.tenant.TenantUser;
import de.alpdesk.repositories.docsite.DocSiteRepository;
import de.alpdesk.repositories.document.DocumentRepository;
import de.alpdesk.repositories.document.KnowledgeAreaRepository;
import de.alpdesk.repositories.document.KnowledgeSourceRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.repositories.tenant.TenantUserRepository;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tenant.TenantDatabases;
import de.alpdesk.services.tenant.UserGroupService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A tenant's knowledge areas (docs/architecture/ai-features.md "Knowledge areas"), kept by its tenant admins and the
 * admin, and who finds which: a user the areas of its groups besides the company knowledge (UserGroupService), a tenant
 * admin every area, a client software's API key and the MCP server the areas assigned to them. The rule of what a search finds is
 * {@link KnowledgeScope}; it is decided here and nowhere else.
 */
@Singleton
public class KnowledgeAreaService {

    public static final int MAX_NAME = 100;

    private final KnowledgeAreaRepository areas;
    private final TenantRepository tenants;
    private final TenantUserRepository users;
    private final TenantDatabases databases;
    private final DocumentRepository documents;
    private final UserGroupService groups;
    private final DocSiteRepository docSites;
    private final KnowledgeSourceRepository sources;

    public KnowledgeAreaService(KnowledgeAreaRepository areas, TenantRepository tenants, TenantUserRepository users,
                                TenantDatabases databases, DocumentRepository documents, UserGroupService groups,
                                DocSiteRepository docSites, KnowledgeSourceRepository sources) {
        this.areas = areas;
        this.tenants = tenants;
        this.users = users;
        this.databases = databases;
        this.documents = documents;
        this.groups = groups;
        this.docSites = docSites;
        this.sources = sources;
    }

    /** The tenant's areas by name; 404 for an unknown tenant. */
    public List<KnowledgeArea> findAll(Long tenantId) {
        tenant(tenantId);
        return areas.findByTenantIdOrderByName(tenantId);
    }

    /** 400 for an empty or too long name, 409 when the tenant has an area of this name. */
    @Transactional
    public KnowledgeArea create(Long tenantId, @Nullable String name) {
        tenant(tenantId);
        String areaName = requireAvailableName(tenantId, name, null);
        return areas.save(new KnowledgeArea(null, tenantId, areaName, Instant.now()));
    }

    /** Empty for another tenant's or an unknown area; else as {@link #create}. */
    @Transactional
    public Optional<KnowledgeArea> rename(Long tenantId, Long areaId, @Nullable String name) {
        Optional<KnowledgeArea> existing = areas.findByIdAndTenantId(areaId, tenantId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        String areaName = requireAvailableName(tenantId, name, areaId);
        return Optional.of(areas.update(new KnowledgeArea(areaId, tenantId, areaName, existing.get().createdAt())));
    }

    /**
     * Deletes the area and its assignments; false for another tenant's or an unknown area. 409 while documents belong
     * to it: moving them to the company knowledge on the quiet would show them to everyone. 409 as well while it has a
     * handbook's website, for the same reason, and while a knowledge source writes into it.
     */
    @Transactional
    public boolean delete(Long tenantId, Long areaId) {
        if (areas.findByIdAndTenantId(areaId, tenantId).isEmpty()) {
            return false;
        }
        Tenant tenant = tenant(tenantId);
        DataSource dataSource = databases.dataSource(tenant.databaseName());
        if (documents.countInArea(dataSource, areaId) > 0) {
            throw ServiceException.conflict("knowledgeArea.notEmpty",
                    "The area still holds documents - delete them or move them first");
        }
        if (docSites.findByArea(dataSource, areaId).isPresent()) {
            throw ServiceException.conflict("knowledgeArea.hasDocSite",
                    "The area still has a handbook website - remove it first");
        }
        List<String> writtenBy = sources.findNamesWritingArea(areaId, tenantId);
        if (!writtenBy.isEmpty()) {
            throw ServiceException.conflict("knowledgeArea.usedBySource", "The knowledge sources "
                    + String.join(", ", writtenBy) + " write into the area - change them first",
                    Map.of("sources", String.join(", ", writtenBy)));
        }
        return areas.deleteByIdAndTenantId(areaId, tenantId) > 0;
    }

    /** The tenant's areas' names by id - for showing a document's area. */
    public Map<Long, String> namesById(Long tenantId) {
        Map<Long, String> names = new HashMap<>();
        for (KnowledgeArea area : areas.findByTenantIdOrderByName(tenantId)) {
            names.put(area.id(), area.name());
        }
        return names;
    }

    /** The names of these areas of the tenant, by name. */
    public List<String> names(Long tenantId, Set<Long> areaIds) {
        List<String> names = new ArrayList<>();
        for (KnowledgeArea area : areas.findByTenantIdOrderByName(tenantId)) {
            if (areaIds.contains(area.id())) {
                names.add(area.name());
            }
        }
        return names;
    }

    /** The tenant's area with this id; null for none (the company knowledge). 400 for another tenant's or an unknown one. */
    public @Nullable Long requireArea(Long tenantId, @Nullable Long areaId) {
        if (areaId == null) {
            return null;
        }
        return areas.findByIdAndTenantId(areaId, tenantId).map(KnowledgeArea::id)
                .orElseThrow(() -> ServiceException.badRequest("knowledgeArea.unknown", "Unknown area"));
    }

    /** The tenant's area of this name (case-insensitive); null for none. 400 for an unknown name. */
    public @Nullable Long requireAreaNamed(Long tenantId, @Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return areas.findByTenantIdAndNameIgnoreCase(tenantId, name.strip()).map(KnowledgeArea::id)
                .orElseThrow(() -> ServiceException.badRequest("knowledgeArea.unknownName",
                        "Unknown area: " + name.strip(), Map.of("name", name.strip())));
    }

    /** What a tenant user finds: every area as a tenant admin, else the company knowledge and its groups' areas. */
    public KnowledgeScope scopeOfUser(Long tenantId, Long userId) {
        Optional<TenantUser> user = users.findByIdAndTenantId(userId, tenantId);
        if (user.isPresent() && user.get().admin()) {
            return KnowledgeScope.everything();
        }
        return KnowledgeScope.companyKnowledgeAnd(user.isEmpty() ? List.of() : groups.areasOfUser(userId));
    }

    /** What a client software's API key finds. */
    public KnowledgeScope scopeOfApiKey(Long apiKeyId) {
        return KnowledgeScope.companyKnowledgeAnd(areas.findAreaIdsOfApiKey(apiKeyId));
    }

    /** What one of the tenant's MCP keys finds. */
    public KnowledgeScope scopeOfMcpKey(Long mcpKeyId) {
        return KnowledgeScope.companyKnowledgeAnd(areas.findAreaIdsOfMcpKey(mcpKeyId));
    }

    /** The areas assigned to each API key of the tenant (keys without one are left out). */
    public Map<Long, List<Long>> areasOfApiKeys(Long tenantId) {
        return byOwner(areas.findApiKeyAssignments(tenantId));
    }

    public List<Long> areasOfApiKey(Long apiKeyId) {
        return areas.findAreaIdsOfApiKey(apiKeyId);
    }

    /** The areas assigned to each MCP key of the tenant (keys without one are left out). */
    public Map<Long, List<Long>> areasOfMcpKeys(Long tenantId) {
        return byOwner(areas.findMcpKeyAssignments(tenantId));
    }

    public List<Long> areasOfMcpKey(Long mcpKeyId) {
        return areas.findAreaIdsOfMcpKey(mcpKeyId);
    }

    /**
     * Replaces the key's areas - in the caller's transaction (creating or changing the key). null: unchanged. 400 for
     * an area that is not the tenant's.
     */
    public void assignApiKey(Long tenantId, Long apiKeyId, @Nullable List<Long> areaIds) {
        if (areaIds == null) {
            return;
        }
        Set<Long> checked = requireAreas(tenantId, areaIds);
        areas.unassignApiKey(apiKeyId);
        checked.forEach(areaId -> areas.assignApiKey(apiKeyId, areaId));
    }

    /** As {@link #assignApiKey} for an MCP key - in the caller's transaction, which holds the key locked. */
    public void assignMcpKey(Long tenantId, Long mcpKeyId, @Nullable List<Long> areaIds) {
        if (areaIds == null) {
            return;
        }
        Set<Long> checked = requireAreas(tenantId, areaIds);
        areas.unassignMcpKey(mcpKeyId);
        checked.forEach(areaId -> areas.assignMcpKey(mcpKeyId, areaId));
    }

    private Set<Long> requireAreas(Long tenantId, List<Long> areaIds) {
        Set<Long> known = new LinkedHashSet<>();
        for (KnowledgeArea area : areas.findByTenantIdOrderByName(tenantId)) {
            known.add(area.id());
        }
        Set<Long> checked = new LinkedHashSet<>();
        for (Long areaId : areaIds) {
            if (areaId == null || !known.contains(areaId)) {
                throw ServiceException.badRequest("knowledgeArea.unknown", "Unknown area");
            }
            checked.add(areaId);
        }
        return checked;
    }

    private String requireAvailableName(Long tenantId, @Nullable String name, @Nullable Long ownId) {
        if (name == null || name.isBlank()) {
            throw ServiceException.badRequest("knowledgeArea.nameRequired", "The name is required");
        }
        String stripped = name.strip();
        if (stripped.length() > MAX_NAME) {
            throw ServiceException.badRequest("knowledgeArea.nameTooLong", "The name may have at most " + MAX_NAME
                    + " characters",
                    Map.of("max", String.valueOf(MAX_NAME)));
        }
        Optional<KnowledgeArea> same = areas.findByTenantIdAndNameIgnoreCase(tenantId, stripped);
        if (same.isPresent() && !same.get().id().equals(ownId)) {
            throw ServiceException.conflict("knowledgeArea.nameTaken", "The tenant already has an area of this name");
        }
        return stripped;
    }

    private static Map<Long, List<Long>> byOwner(List<KnowledgeAreaRepository.Assignment> assignments) {
        Map<Long, List<Long>> byOwner = new HashMap<>();
        for (KnowledgeAreaRepository.Assignment assignment : assignments) {
            byOwner.computeIfAbsent(assignment.ownerId(), _ -> new ArrayList<>()).add(assignment.areaId());
        }
        return byOwner;
    }

    private Tenant tenant(Long tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> ServiceException.notFound("tenant.notFound",
                "Tenant not found"));
    }
}
