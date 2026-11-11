package de.alpdesk.services.document

import de.alpdesk.entities.document.SourceSchedule
import de.alpdesk.repositories.document.KnowledgeSourceRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.task.FakeStoreClient
import io.micronaut.context.annotation.Property
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A knowledge source's scheduled runs (docs/architecture/knowledge-sources.md "Two kinds of run", "When it runs"): at
 * most 100 files and 250 MB a run, the rest with the next; started by the scheduler when due - once, not while one
 * runs, not for a source switched off or a tenant deactivated; each schedule's times.
 */
@MicronautTest(transactional = false)
@Property(name = "test.fake-store", value = "true")
class SourceSyncSpec extends Specification {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin")

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels models

    @Inject
    FakeStoreClient fake

    @Inject
    SourceSync sync

    @Inject
    SourceScheduler scheduler

    @Inject
    KnowledgeSourceRepository repository

    @Shared
    AiApi api

    SourceApi sources

    def setup() {
        api = api ?: new AiApi(client)
        sources = new SourceApi(api, fake)
        models.reset()
        fake.reset()
    }

    def "a scheduled run reads at most 100 files, the next run the rest; a run by hand reads everything"() {
        given:
        def setup = sources.tenant()
        (1..101).each { sources.write("${setup.base}/datei-${String.format('%03d', it)}.txt", "Datei ${it}") }
        def source = sources.source(setup)

        when:
        sync.run(setup.tenant.id as Long, source.id as Long, SourceSync.Kind.SCHEDULED)
        def first = sources.await(setup.token, source.id)

        then:
        first.lastAdded == 100
        first.openFiles == 1

        when:
        sync.run(setup.tenant.id as Long, source.id as Long, SourceSync.Kind.SCHEDULED)

        then:
        sources.await(setup.token, source.id).lastAdded == 1
        sources.await(setup.token, source.id).openFiles == 0

        when: "every file changes - by hand, all of them at once"
        (1..101).each { sources.write("${setup.base}/datei-${String.format('%03d', it)}.txt", "Neu ${it}") }
        def byHand = sources.run(setup.token, source.id)

        then:
        byHand.lastChanged == 101
        byHand.openFiles == 0
    }

    def "a scheduled run stops before 250 MB"() {
        given: "six files of 45 MB - the sixth would pass 250 MB; images to the content, refused at once after reading"
        def setup = sources.tenant()
        byte[] large = new byte[45 * 1024 * 1024]
        [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A].eachWithIndex { int value, int i -> large[i] = (byte) value }
        (1..6).each {
            fake.files["${setup.base}/gross-${it}.txt".toString()] = large
            fake.modified["${setup.base}/gross-${it}.txt".toString()] = Instant.now()
        }
        def source = sources.source(setup)

        when:
        sync.run(setup.tenant.id as Long, source.id as Long, SourceSync.Kind.SCHEDULED)
        def done = sources.await(setup.token, source.id)

        then:
        done.lastRefused == 5
        done.openFiles == 1
    }

    def "files the store cannot read do not take a scheduled run's budget from new ones for good"() {
        given: "100 files the store refuses, before a readable one by path"
        def setup = sources.tenant()
        (1..100).each {
            String path = "${setup.base}/a-gesperrt-${String.format('%03d', it)}.txt"
            sources.write(path, "Gesperrt ${it}")
            fake.unreadable.add(path)
        }
        sources.write("${setup.base}/z-neu.txt", "Neu")
        def source = sources.source(setup)

        when: "the first scheduled run spends its budget on them"
        sync.run(setup.tenant.id as Long, source.id as Long, SourceSync.Kind.SCHEDULED)
        def first = sources.await(setup.token, source.id)

        then:
        first.lastAdded == 0
        first.lastError == "fileStore.readFailed"

        when: "the next one tries them after the others"
        sync.run(setup.tenant.id as Long, source.id as Long, SourceSync.Kind.SCHEDULED)
        def second = sources.await(setup.token, source.id)

        then: "the new file is read, the locked ones stay open and are listed with their reason"
        second.lastAdded == 1
        sources.documents(setup.token, source.id).keySet() == ["z-neu.txt"] as Set
        def files = api.call(setup.token, "GET", "${SourceApi.SOURCES}/${source.id}/files") as List<Map>
        files.size() == 100
        files.every { it.reason == "fileStore.readFailed" }

        when: "the store lets them be read"
        fake.unreadable.clear()
        def byHand = sources.run(setup.token, source.id)

        then:
        byHand.lastAdded == 100
        byHand.lastError == null
    }

    def "a cancel that comes after a run ended does not stop the next one"() {
        given:
        def setup = sources.tenant()
        sources.write("${setup.base}/a.txt", "A")
        def source = sources.source(setup)

        when: "the flag of a cancel that saw the last run still going is left"
        sync.cancelled.add(source.id as Long)
        def run = sources.run(setup.token, source.id)

        then:
        run.lastError == null
        run.lastAdded == 1
    }

    def "the scheduler starts a due source once and moves its next time on; a source switched off is not started"() {
        given:
        def setup = sources.tenant()
        sources.write("${setup.base}/a.txt", "A")
        def due = sources.source(setup, [schedule: "HOURLY"])
        def paused = sources.source(setup, [schedule: "HOURLY", active: false])
        Instant now = Instant.parse(due.nextRunAt as String).plusSeconds(1)

        when:
        scheduler.startDue(now)
        def run = sources.await(setup.token, due.id)

        then:
        run.lastAdded == 1
        Instant.parse(run.nextRunAt as String).isAfter(now)
        sources.await(setup.token, paused.id).lastAdded == 0

        when: "the same moment again"
        scheduler.startDue(now)

        then: "not due any more"
        sources.await(setup.token, due.id).lastFinishedAt == run.lastFinishedAt
    }

    def "a deactivated tenant's sources are not started"() {
        given:
        def setup = sources.tenant()
        sources.write("${setup.base}/a.txt", "A")
        def source = sources.source(setup, [schedule: "HOURLY"])
        api.admin("PUT", "/admin/tenants/${setup.tenant.id}", [name: setup.tenant.name, active: false])
        Instant now = Instant.parse(source.nextRunAt as String).plusSeconds(1)

        when:
        scheduler.startDue(now)
        Thread.sleep(300)

        then:
        repository.findById(source.id as Long).get().lastAdded() == 0
    }

    def "each schedule's next time, in the installation's time zone"() {
        expect:
        schedule.nextAfter(ZonedDateTime.of(2026, 10, 27, 13, 20, 0, 0, BERLIN).toInstant(), BERLIN) ==
                (next == null ? null : ZonedDateTime.parse(next).toInstant())

        where:
        schedule                       || next
        SourceSchedule.MANUAL          || null
        SourceSchedule.HOURLY          || "2026-10-27T14:00+01:00[Europe/Berlin]"
        SourceSchedule.EVERY_3_HOURS   || "2026-10-27T15:00+01:00[Europe/Berlin]"
        SourceSchedule.EVERY_6_HOURS   || "2026-10-27T18:00+01:00[Europe/Berlin]"
        SourceSchedule.EVERY_12_HOURS  || "2026-10-28T00:00+01:00[Europe/Berlin]"
        SourceSchedule.DAILY           || "2026-10-28T02:00+01:00[Europe/Berlin]"
    }
}
