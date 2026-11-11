package de.alpdesk.services.document;

import io.micronaut.core.annotation.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Which of a tenant's documents someone finds (docs/architecture/ai-features.md "Knowledge areas"): the company
 * knowledge - documents without an area - and the areas assigned to them, or everything (a tenant admin).
 */
public record KnowledgeScope(boolean findsEverything, Set<Long> areaIds) {

    public KnowledgeScope {
        areaIds = Set.copyOf(areaIds);
    }

    public static KnowledgeScope everything() {
        return new KnowledgeScope(true, Set.of());
    }

    public static KnowledgeScope companyKnowledgeAnd(Collection<Long> areaIds) {
        return new KnowledgeScope(false, Set.copyOf(areaIds));
    }

    /** Whether a document of this area (null: the company knowledge) is found. */
    public boolean includes(@Nullable Long areaId) {
        return findsEverything || areaId == null || areaIds.contains(areaId);
    }

    /** The areas as a list, for an SQL array; empty for {@link #everything()}. */
    public List<Long> areaIdList() {
        return List.copyOf(areaIds);
    }
}
