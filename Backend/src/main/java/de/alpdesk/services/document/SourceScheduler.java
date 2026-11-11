package de.alpdesk.services.document;

import de.alpdesk.entities.document.KnowledgeSource;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.document.KnowledgeSourceRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Starts the knowledge sources whose schedule is due (docs/architecture/knowledge-sources.md "When it runs"), once a
 * minute over every tenant - like the tasks' TaskScheduler, but not tied to the admin's task switch. A source still
 * running is skipped and logged; either way its next time moves on, so a downtime is made up for by one run. One
 * installation, no cluster: no locking.
 */
@Singleton
public class SourceScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(SourceScheduler.class);

    private final KnowledgeSourceRepository sources;
    private final TenantRepository tenants;
    private final SourceSync sync;
    private final ZoneId zone;

    public SourceScheduler(KnowledgeSourceRepository sources, TenantRepository tenants, SourceSync sync,
                           @Value("${app.timezone}") String zone) {
        this.sources = sources;
        this.tenants = tenants;
        this.sync = sync;
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(fixedDelay = "1m", initialDelay = "1m")
    void everyMinute() {
        startDue(Instant.now());
    }

    /** The sources due at this time - each started once, its next time moved on. */
    void startDue(Instant now) {
        for (KnowledgeSource source : sources.findDue(now)) {
            try {
                startOne(source, now);
            } catch (RuntimeException e) {
                LOG.error("The scheduled start of knowledge source {} of tenant {} failed", source.id(),
                        source.tenantId(), e);
            }
        }
    }

    private void startOne(KnowledgeSource source, Instant now) {
        Instant next = source.schedule().nextAfter(now, zone);
        if (sources.moveNextRun(source.id(), source.nextRunAt(), next) == 0) {
            return;
        }
        Optional<Tenant> tenant = tenants.findById(source.tenantId());
        if (tenant.isEmpty() || !tenant.get().active()) {
            return;
        }
        if (!sync.start(source.tenantId(), source.id(), SourceSync.Kind.SCHEDULED)) {
            LOG.info("Knowledge source {} of tenant {} was due but still runs - skipped", source.id(), source.tenantId());
        }
    }
}
