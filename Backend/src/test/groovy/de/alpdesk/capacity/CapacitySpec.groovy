package de.alpdesk.capacity

import de.alpdesk.dto.tenant.TenantRequest
import de.alpdesk.entities.tenant.SearchLanguage
import de.alpdesk.entities.tenant.Tenant
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.document.KnowledgeScope
import de.alpdesk.services.document.KnowledgeSearch
import de.alpdesk.services.document.TenantEmbeddings
import de.alpdesk.services.tenant.TenantDatabaseMigration
import de.alpdesk.services.tenant.TenantDatabases
import de.alpdesk.services.tenant.TenantService
import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import io.micronaut.context.annotation.Property
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise

import javax.sql.DataSource
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntBinaryOperator

/**
 * Measures how far one installation carries: many tenants (creating them, the migration of every tenant database on
 * start, the connections and threads their pools hold) and one large tenant (writing chunks, the size of table and
 * indexes, the search time as it grows). Skipped unless LOAD_TEST is set: it runs for a long time (half an hour and
 * more at the default sizes) and its numbers are a report, not a pass/fail rule. Writes
 * build/reports/capacity/capacity.md - for the large tenant also each side of the search alone, both query plans, and the
 * search of users who find only part of the knowledge (knowledge areas, docs/architecture/ai-features.md).
 * <p>
 * Sizes from the environment: LOAD_TENANTS (500), LOAD_CHUNKS (200000, about 6000 documents of ten pages),
 * LOAD_DIMENSION (1024), LOAD_SEARCHES (200). No provider is called: FakeAiModels embeds the queries.
 * <p>
 * By default it runs on the specs' Testcontainers PostgreSQL with its default settings - the numbers are those of the
 * machine running it. For the numbers of a server, run it there against a throwaway PostgreSQL (never the developer's
 * dev database) by also setting DATASOURCES_DEFAULT_URL, _USERNAME, _PASSWORD and
 * DATASOURCES_DEFAULT_DRIVER_CLASS_NAME=org.postgresql.Driver. The spec deletes every tenant it created.
 */
@Requires({ env.LOAD_TEST })
@MicronautTest(transactional = false)
@Property(name = "app.tenant-databases.migrate-on-start", value = "true")
@Stepwise
class CapacitySpec extends Specification {

    static final int TENANTS = setting("LOAD_TENANTS", 500)
    static final int CHUNKS = setting("LOAD_CHUNKS", 200_000)
    static final int DIMENSION = setting("LOAD_DIMENSION", 1024)
    static final int SEARCHES = setting("LOAD_SEARCHES", 200)
    /** Tenants with three requests each at the same moment - the per-tenant pool's maximum. */
    static final List<Integer> ACTIVE_TENANTS = [10, 20, 30, 40, 50, 75, 100]
    static final List<Integer> CHUNK_STEPS = [10_000, 50_000, 100_000, 200_000, 500_000, 1_000_000]
    static final int CHUNKS_PER_DOCUMENT = 35
    static final int WRITE_BATCH = 500
    static final int PARALLEL_SEARCHES = 8
    /**
     * The large tenant's documents in knowledge areas - deliberately unfavourable: the company knowledge everyone finds
     * is only every 20th document, the rest lies in two large areas. A user of the company knowledge alone then finds 5 %
     * of the chunks - the case where pgvector's iterative scan (at most hnsw.max_scan_tuples chunks) can run short.
     */
    static final int COMPANY_KNOWLEDGE_EVERY = 20
    /** KnowledgeSearch's minimum vector score and candidates per side - its own constants are private. */
    static final double MIN_SCORE = 0.6
    static final int CANDIDATES = 20
    static final String RUN = UUID.randomUUID().toString().substring(0, 8)

    @Inject
    @Client("/")
    HttpClient client

    /** Shared: cleanupSpec deletes the run's tenants with it. */
    @Shared
    @Inject
    TenantService tenantService

    @Inject
    TenantRepository tenantRepository

    @Inject
    TenantDatabases databases

    @Inject
    TenantDatabaseMigration migration

    @Inject
    TenantEmbeddings embeddings

    @Inject
    KnowledgeSearch knowledge

    @Inject
    DataSource managementDataSource

    @Shared
    List<Tenant> created = []

    @Shared
    List<String> report = []

    @Shared
    List<String> vocabulary = words(3000)

    def setupSpec() {
        report << "# Capacity measurement".toString()
        report << ""
        report << ("Run ${RUN}: ${TENANTS} tenants, ${CHUNKS} chunks of ${DIMENSION} dimensions, "
                + "${SEARCHES} searches per step.").toString()
    }

    def cleanupSpec() {
        long start = System.nanoTime()
        created.each { tenantService.delete(it.id()) }
        report << ""
        report << "Deleting the ${created.size()} tenants of the run took ${seconds(start)} s.".toString()
        def file = Path.of("build/reports/capacity/capacity.md")
        Files.createDirectories(file.parent)
        Files.writeString(file, report.join("\n") + "\n")
        println report.join("\n")
    }

    def "the database server and the JVM measured on"() {
        when:
        def runtime = Runtime.runtime
        List<List> rows = [
                ["PostgreSQL", managementValue("SELECT version()")],
                ["max_connections", managementValue("SHOW max_connections")],
                ["shared_buffers", managementValue("SHOW shared_buffers")],
                ["work_mem / maintenance_work_mem",
                 "${managementValue('SHOW work_mem')} / ${managementValue('SHOW maintenance_work_mem')}"],
                ["JVM", "${System.getProperty('java.version')}, ${runtime.availableProcessors()} processors, "
                        + "max heap ${megabytes(runtime.maxMemory())} MB"]]

        then:
        table("Environment", [], ["", ""], rows)
    }

    def "many tenants: created one by one, every database migrated again on start"() {
        when: "the tenants are created"
        List<Long> creationMillis = []
        TENANTS.times { i ->
            long start = System.nanoTime()
            created << tenantService.create(new TenantRequest("Lasttest ${RUN} ${i}".toString(), null, null, null, null, null, null, null, null, null))
            creationMillis << millis(start)
        }

        and: "the application starts again: every tenant database is checked against the tenant schema"
        long start = System.nanoTime()
        migration.onApplicationEvent(null)
        double migrationSeconds = seconds(start)

        and: "the databases' size is read"
        def sizes = managementRow("SELECT count(*) AS databases, sum(pg_database_size(datname)) AS bytes "
                + "FROM pg_database WHERE datname LIKE 'tenant\\_%'")
        long emptySize = managementValue("SELECT pg_database_size('${created.first().databaseName()}')") as long

        then:
        created.size() == TENANTS

        and:
        table("Tenants", [], ["", ""], [
                ["Creating a tenant (with its database)", "median ${percentile(creationMillis, 0.5)} ms, "
                        + "p95 ${percentile(creationMillis, 0.95)} ms, all ${creationMillis.sum().intdiv(1000)} s"],
                ["Migration on start, ${managementValue('SELECT count(*) FROM tenant')} tenants", "${migrationSeconds} s"],
                ["Tenant databases on the server", "${sizes.databases}, together ${megabytes(sizes.bytes as long)} MB, "
                        + "an empty one ${megabytes(emptySize)} MB"]])
    }

    def "tenants used within half a minute keep their connections open"() {
        given:
        databases.closePools()
        int threadsBefore = threads()
        int maxConnections = managementValue("SHOW max_connections") as int

        when: "one short request per tenant, one tenant after another - until the server refuses one"
        List<List> rows = []
        int used = 0
        String refusal = null
        for (Tenant tenant : created) {
            try {
                query(tenant.databaseName(), "SELECT 1")
            } catch (Exception e) {
                refusal = "tenant ${used + 1}: ${rootMessage(e)}"
                break
            }
            used++
            if (used % 25 == 0 || used == created.size()) {
                rows << [used, tenantConnections(), threads() - threadsBefore]
            }
        }

        and: "the pools stay unused for 70 seconds - longer than their idle timeout and housekeeping period"
        Thread.sleep(70_000)
        int connectionsAfterIdle = tenantConnections()

        then:
        used > 0

        and:
        table("Connections of tenants used one after another",
                ["max_connections ${maxConnections}; every tenant's pool keeps at most 3 connections, idle ones for 30 s.",
                 refusal ? "Refused at ${refusal}." : "No tenant was refused.",
                 "After 70 s without requests: ${connectionsAfterIdle} connections to tenant databases, "
                         + "${threads() - threadsBefore} JVM threads added."],
                ["Tenants used", "Connections to tenant databases", "JVM threads added"], rows)
    }

    def "tenants active at the same moment, each with three requests at once"() {
        given:
        def steps = ACTIVE_TENANTS.findAll { it <= created.size() }

        when: "every request holds its connection for half a second, like a search or a short write"
        List<List> rows = []
        for (int active : steps) {
            databases.closePools()
            def result = simultaneousRequests(created.take(active))
            rows << [active, active * 3, result.failed, result.slowestMillis, result.peakConnections]
            if (result.failed > 0) {
                break
            }
        }

        then:
        !rows.isEmpty()

        and:
        table("Tenants active at the same moment",
                ["Each tenant sends three requests at once (its pool's maximum), each holds its connection 0.5 s. A refused "
                         + "connection fails a request only after the pool's 30 s connection timeout."],
                ["Active tenants", "Requests", "Failed", "Slowest ms", "Peak connections to tenant databases"], rows)
    }

    def "one large tenant: writing chunks and searching them as they grow"() {
        given: "a tenant with an embedding model of the measured length"
        databases.closePools()
        def api = new AiApi(client)
        def model = api.embeddingModel(DIMENSION)
        def large = api.tenant(false, "GERMAN")
        api.allow(large.id, model)
        api.admin("PUT", "/admin/tenants/${large.id}/provider-keys/${model.provider}", [apiKey: "sk-lasttest-key"])
        api.admin("PUT", "/admin/tenants/${large.id}/embedding-model", [modelId: model.id])
        Tenant tenant = tenantRepository.findById(large.id as Long).get()
        created << tenant
        Long areaA = api.admin("POST", "/admin/tenants/${large.id}/knowledge-areas", [name: "Bereich A"]).id as Long
        Long areaB = api.admin("POST", "/admin/tenants/${large.id}/knowledge-areas", [name: "Bereich B"]).id as Long
        Map<String, KnowledgeScope> scopes = [
                "Admin (alles)"           : KnowledgeScope.everything(),
                "Firmenwissen (5 %)"      : KnowledgeScope.companyKnowledgeAnd([]),
                "Firmenwissen + Bereich A": KnowledgeScope.companyKnowledgeAnd([areaA])]
        def store = embeddings.storeForIndexing(tenant.databaseName(), DIMENSION, SearchLanguage.GERMAN)
        List<String> queries = (0..<SEARCHES).collect { query(new Random(1_000_000 + it)) }

        when: "the chunks are written in steps, each step measured by searching like the chat and by each side alone"
        List<List> writing = []
        List<List> searching = []
        List<String> plans = []
        List<List> scoped = []
        int written = 0
        for (int step : CHUNK_STEPS.findAll { it < CHUNKS } + [CHUNKS]) {
            long start = System.nanoTime()
            while (written < step) {
                int size = Math.min(WRITE_BATCH, step - written)
                List<TextSegment> segments = (0..<size).collect { segment(written + it) }
                writeDocuments(tenant.databaseName(), written, written + size, areaA, areaB)
                store.addAll(segments.collect { vectorOf(it.text(), new Random(it.text().hashCode())) }, segments)
                written += size
            }
            double writeSeconds = seconds(start)
            def sizes = tenantRow(tenant.databaseName(), "SELECT pg_total_relation_size('embedding') AS total, "
                    + "pg_relation_size('embedding_vector_idx') AS vector, pg_relation_size('" + TenantEmbeddings.textIndexName(SearchLanguage.GERMAN) + "') AS text")
            queries.take(10).each { search(tenant, it) }
            List<Long> sequential = queries.collect { q -> timed { search(tenant, q) } }
            List<Long> vectorSide = queries.collect { q -> timed { searchVectors(tenant, q) } }
            List<Long> textSide = queries.collect { q -> timed { searchText(tenant, q) } }
            def parallel = parallelSearches(tenant, queries)
            writing << [step, Math.round(step / writeSeconds), megabytes(sizes.total as long),
                        megabytes(sizes.vector as long), megabytes(sizes.text as long)]
            searching << [step, percentile(sequential, 0.5), percentile(sequential, 0.95), sequential.max(),
                          percentile(vectorSide, 0.5), percentile(vectorSide, 0.95),
                          percentile(textSide, 0.5), percentile(textSide, 0.95), parallel.perSecond, parallel.p95]
            plans.addAll(plansOf(tenant.databaseName(), queries.first(), step))
            scoped.addAll(scopes.collect { name, scope -> scopedRow(tenant, queries, step, name, scope) })
        }

        then:
        written == CHUNKS

        and:
        table("One large tenant: writing",
                ["Chunks of about 1000 characters, ${CHUNKS_PER_DOCUMENT} per document, each with its document's row; "
                         + "dense vectors of ${DIMENSION} dimensions. The write rate includes making the vectors in the spec."],
                ["Chunks", "Written per s", "Table total MB", "HNSW index MB", "Full-text index MB"], writing)
        table("One large tenant: searching",
                ["Chat: KnowledgeSearch as the chat calls it (query embedded by the fake - no provider time - then both "
                         + "sides, merged). Vector and full text: TenantEmbeddings' two searches alone, ${CANDIDATES} "
                         + "candidates each. Parallel: ${PARALLEL_SEARCHES} chat searches at once in this one tenant "
                         + "(its pool has 3 connections). All in ms but the searches per s."],
                ["Chunks", "Chat median", "Chat p95", "Chat max", "Vector median", "Vector p95", "Full text median",
                 "Full text p95", "Parallel per s", "Parallel p95"], searching)
        table("One large tenant: knowledge areas",
                [("Every ${COMPANY_KNOWLEDGE_EVERY}th document is company knowledge, the others lie in two areas of about "
                         + "half each. Per scope the vector side alone (${CANDIDATES} candidates, min score ${MIN_SCORE}) "
                         + "and the chat's whole search. Hits: chunks the vector side returned on average, and in how many "
                         + "searches fewer than the admin's for the same query - pgvector's iterative scan stops after "
                         + "hnsw.max_scan_tuples chunks, so a scope that finds only a small share can run short. Times in ms.")
                         .toString()],
                ["Chunks", "Scope", "Vector median", "Vector p95", "Hits avg", "Fewer hits than admin", "Chat median",
                 "Chat p95"], scoped)
        report << ""
        report << "## One large tenant: query plans"
        report.addAll(plans)
    }

    /** One scope's searches at this step - its hits compared with the admin's (everything) for the same queries. */
    private List scopedRow(Tenant tenant, List<String> queries, int step, String name, KnowledgeScope scope) {
        List<Long> vectorTimes = []
        List<Integer> hits = []
        int fewer = 0
        for (String q : queries) {
            float[] vector = vectorOf(q, new Random(q.hashCode())).vector()
            List found = null
            vectorTimes << timed { found = embeddings.searchVectors(tenant.databaseName(), vector, MIN_SCORE, CANDIDATES, scope) }
            hits << found.size()
            if (!scope.findsEverything()) {
                int admin = embeddings.searchVectors(tenant.databaseName(), vector, MIN_SCORE, CANDIDATES,
                        KnowledgeScope.everything()).size()
                fewer += found.size() < admin ? 1 : 0
            }
        }
        List<Long> chatTimes = queries.collect { q -> timed { knowledge.search(tenant, null, scope, q, 5) } }
        [step, name, percentile(vectorTimes, 0.5), percentile(vectorTimes, 0.95),
         String.format(Locale.ROOT, "%.1f", (hits.sum() as double) / hits.size()),
         scope.findsEverything() ? "-" : "${fewer} of ${queries.size()}".toString(),
         percentile(chatTimes, 0.5), percentile(chatTimes, 0.95)]
    }

    /** k tenants with three requests each, all started at once; samples the connections while they run. */
    private Map simultaneousRequests(List<Tenant> tenants) {
        def executor = Executors.newFixedThreadPool(tenants.size() * 3)
        def running = new AtomicBoolean(true)
        def peak = new AtomicInteger()
        def sampler = Thread.start {
            while (running.get()) {
                peak.accumulateAndGet(tenantConnections(), { a, b -> Math.max(a, b) } as IntBinaryOperator)
                Thread.sleep(100)
            }
        }
        try {
            List<Callable<Long>> requests = []
            tenants.each { tenant ->
                3.times {
                    requests << ({ ->
                        long start = System.nanoTime()
                        try {
                            query(tenant.databaseName(), "SELECT pg_sleep(0.5)")
                            return millis(start)
                        } catch (Exception ignored) {
                            return -1L
                        }
                    } as Callable<Long>)
                }
            }
            List<Long> results = executor.invokeAll(requests)*.get()
            [failed: results.count { it < 0 }, slowestMillis: results.max(), peakConnections: peak.get()]
        } finally {
            running.set(false)
            sampler.join()
            executor.shutdownNow()
        }
    }

    /** All queries, PARALLEL_SEARCHES at a time: searches per second and the 95th percentile of one search. */
    private Map parallelSearches(Tenant tenant, List<String> queries) {
        def executor = Executors.newFixedThreadPool(PARALLEL_SEARCHES)
        try {
            long start = System.nanoTime()
            List<Long> times = executor.invokeAll(queries.collect { q ->
                ({ -> timed { search(tenant, q) } } as Callable<Long>)
            })*.get()
            [perSecond: Math.round(queries.size() / seconds(start)), p95: percentile(times, 0.95)]
        } finally {
            executor.shutdownNow()
        }
    }

    /** The vector side alone, with a dense query vector like a real model's. */
    private void searchVectors(Tenant tenant, String query) {
        embeddings.searchVectors(tenant.databaseName(), vectorOf(query, new Random(query.hashCode())).vector(), MIN_SCORE,
                CANDIDATES, KnowledgeScope.everything())
    }

    private void searchText(Tenant tenant, String query) {
        embeddings.searchText(tenant.databaseName(), SearchLanguage.GERMAN, query, CANDIDATES, KnowledgeScope.everything())
    }

    /**
     * EXPLAIN ANALYZE of both searches for one query, and when PostgreSQL last analyzed the tables: whether the HNSW and
     * the full-text index are used, and how many rows each side reads - and of the vector side for a user of the company
     * knowledge alone (TenantEmbeddings' scope condition with no areas). The SQL is TenantEmbeddings' with the values
     * written in - keep it in step with searchVectors and searchText.
     */
    private List<String> plansOf(String databaseName, String query, int chunks) {
        String vector = vectorOf(query, new Random(query.hashCode())).vector().toList().join(",")
        String join = ("JOIN document d ON d.id = (e.metadata->>'${TenantEmbeddings.DOCUMENT_ID}')::bigint "
                + "AND d.status = 'READY'")
        String textVector = TenantEmbeddings.textVector(SearchLanguage.GERMAN, "e.text")
        String vectorSql = ("SELECT e.embedding_id::text AS id, e.text, (2 - (e.embedding <=> '[${vector}]'::vector)) / 2 "
                + "AS score FROM embedding e ${join} ORDER BY e.embedding <=> '[${vector}]'::vector LIMIT ${CANDIDATES}")
        String scopedVectorSql = ("SELECT e.embedding_id::text AS id, e.text, (2 - (e.embedding <=> '[${vector}]'::vector)) / 2 "
                + "AS score FROM embedding e ${join} WHERE (d.area_id IS NULL OR d.area_id = ANY('{}'::bigint[])) "
                + "ORDER BY e.embedding <=> '[${vector}]'::vector LIMIT ${CANDIDATES}")
        String textSql = ("SELECT e.embedding_id::text AS id, e.text, ts_rank_cd(${textVector}, query) AS rank "
                + "FROM embedding e ${join}, CAST(replace(plainto_tsquery('german', '${query}')::text, ' & ', ' | ') "
                + "AS tsquery) query WHERE ${textVector} @@ query ORDER BY rank DESC LIMIT ${CANDIDATES}")
        databases.dataSource(databaseName).connection.withCloseable { connection ->
            ["", "### ${chunks} chunks".toString(), "",
             "Last analyzed: " + rows(connection, "SELECT relname || ' ' || coalesce(greatest(last_analyze, "
                     + "last_autoanalyze)::text, 'never') FROM pg_stat_user_tables WHERE relname IN ('embedding', 'document') "
                     + "ORDER BY relname").join(", "),
             "", "Vector search:", "", "```",
             *vectorPlan(connection, vectorSql),
             "```", "", "Vector search of a user of the company knowledge alone:", "", "```",
             *vectorPlan(connection, scopedVectorSql),
             "```", "", "Full-text search (query \"${query}\"):".toString(), "", "```",
             *rows(connection, "EXPLAIN (ANALYZE, BUFFERS) " + textSql),
             "```"]
        }
    }

    /** As searchVectors runs it: in a transaction with pgvector's iterative scan. */
    private static List<String> vectorPlan(java.sql.Connection connection, String vectorSql) {
        connection.autoCommit = false
        try {
            connection.createStatement().execute("SET LOCAL hnsw.iterative_scan = strict_order")
            return rows(connection, "EXPLAIN (ANALYZE, BUFFERS) " + vectorSql)
        } finally {
            connection.rollback()
            connection.autoCommit = true
        }
    }

    /** The first column of every row. */
    private static List<String> rows(java.sql.Connection connection, String sql) {
        def result = connection.createStatement().executeQuery(sql)
        List<String> values = []
        while (result.next()) {
            values << result.getString(1)
        }
        values
    }

    /** The chat's search over all of the tenant's knowledge, five results. */
    private void search(Tenant tenant, String query) {
        knowledge.search(tenant, null, KnowledgeScope.everything(), query, 5)
    }

    /**
     * The rows of the documents the chunks from..to belong to: the search finds a chunk only through its document's row.
     * Ready, without text - the measurement reads chunks only. Every COMPANY_KNOWLEDGE_EVERY-th is company knowledge, the
     * others alternate between the two areas.
     */
    private void writeDocuments(String databaseName, int from, int to, Long areaA, Long areaB) {
        databases.dataSource(databaseName).connection.withCloseable { connection ->
            def statement = connection.prepareStatement(("INSERT INTO document (id, file_name, size_bytes, text, status, "
                    + "chunk_count, origin, area_id, created_at, updated_at) VALUES (?, ?, 0, '', 'READY', ?, 'UPLOAD', ?, "
                    + "now(), now()) ON CONFLICT (id) DO NOTHING"))
            for (long document = documentId(from); document <= documentId(to - 1); document++) {
                statement.setLong(1, document)
                statement.setString(2, "Dokument-${document}.pdf".toString())
                statement.setInt(3, CHUNKS_PER_DOCUMENT)
                Long area = document % COMPANY_KNOWLEDGE_EVERY == 0 ? null : (document % 2 == 1 ? areaA : areaB)
                statement.setObject(4, area, java.sql.Types.BIGINT)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private static long documentId(int chunk) {
        chunk.intdiv(CHUNKS_PER_DOCUMENT) + 1
    }

    /** The i-th chunk: its document's path as context line, then about 1000 characters of words. */
    private TextSegment segment(int i) {
        long document = documentId(i)
        String path = "Ordner ${document % 50}/Dokument-${document}.pdf"
        def random = new Random(i)
        def text = new StringBuilder(path).append('\n')
        while (text.length() < 1000) {
            text.append(word(random)).append(' ')
        }
        TextSegment.from(text.toString(), Metadata.from([(TenantEmbeddings.DOCUMENT_ID): document,
                                                        (TenantEmbeddings.FILE_NAME)  : path]))
    }

    private String query(Random random) {
        (0..<(3 + random.nextInt(4))).collect { word(random) }.join(' ')
    }

    /** Common words come up more often than rare ones, as in real texts - the full text then finds many chunks. */
    private String word(Random random) {
        vocabulary[(int) (vocabulary.size() * Math.pow(random.nextDouble(), 2))]
    }

    /**
     * Dense like a real embedding: the fake's word hashes (so a query finds chunks sharing its words) plus noise in
     * every dimension. The fake's own vectors are mostly zeros, which an HNSW index walks differently.
     */
    private static Embedding vectorOf(String text, Random random) {
        float[] values = new float[DIMENSION]
        for (String word : text.toLowerCase().split(/[^\p{L}\p{N}]+/)) {
            if (word.length() > 2) {
                values[Math.floorMod(word.hashCode(), DIMENSION)] += 1f
            }
        }
        double length = 0
        for (int i = 0; i < DIMENSION; i++) {
            values[i] += (float) (random.nextGaussian() * 0.3)
            length += values[i] * values[i]
        }
        length = Math.sqrt(length)
        for (int i = 0; i < DIMENSION; i++) {
            values[i] = (float) (values[i] / length)
        }
        Embedding.from(values)
    }

    /** Made-up German-sounding words of two to four syllables, the same on every run. */
    private static List<String> words(int count) {
        def syllables = ["ka", "ber", "lin", "to", "ma", "sch", "ein", "gel", "rat", "zu", "mo", "ter", "vin", "del",
                         "pra", "fur", "hel", "dor", "stu", "nek", "wal", "ung", "keit", "bau"]
        def random = new Random(42)
        Set<String> words = new LinkedHashSet<>()
        while (words.size() < count) {
            words << (0..<(2 + random.nextInt(3))).collect { syllables[random.nextInt(syllables.size())] }.join()
        }
        words as List
    }

    private void query(String databaseName, String sql) {
        databases.dataSource(databaseName).connection.withCloseable { it.createStatement().execute(sql) }
    }

    private Map tenantRow(String databaseName, String sql) {
        databases.dataSource(databaseName).connection.withCloseable { row(it, sql) }
    }

    private int tenantConnections() {
        managementValue("SELECT count(*) FROM pg_stat_activity WHERE datname LIKE 'tenant\\_%'") as int
    }

    private Object managementValue(String sql) {
        managementRow(sql).values().first()
    }

    /** Through the pool itself: outside a transaction the injected DataSource hands out no connection. */
    private Map managementRow(String sql) {
        DelegatingDataSource.unwrapDataSource(managementDataSource).connection.withCloseable { row(it, sql) }
    }

    private static Map row(java.sql.Connection connection, String sql) {
        def result = connection.createStatement().executeQuery(sql)
        result.next()
        def row = [:]
        for (int i = 1; i <= result.metaData.columnCount; i++) {
            row[result.metaData.getColumnLabel(i)] = result.getObject(i)
        }
        row
    }

    /** A section of the report: its title, sentences above the table, the table. */
    private void table(String title, List sentences, List header, List<List> rows) {
        report << ""
        report << "## ${title}".toString()
        sentences.each {
            report << ""
            report << it.toString()
        }
        report << ""
        report << "| ${header.join(' | ')} |".toString()
        report << "|${'---|' * header.size()}".toString()
        rows.each { report << "| ${it.join(' | ')} |".toString() }
    }

    private static long timed(Closure work) {
        long start = System.nanoTime()
        work()
        millis(start)
    }

    private static long millis(long start) {
        (System.nanoTime() - start).intdiv(1_000_000)
    }

    private static double seconds(long start) {
        Math.round((System.nanoTime() - start) / 1e8) / 10
    }

    private static long percentile(List<Long> values, double share) {
        def sorted = values.sort(false)
        sorted[Math.max(0, (int) Math.ceil(share * sorted.size()) - 1)]
    }

    private static long megabytes(long bytes) {
        bytes.intdiv(1024 * 1024)
    }

    private static int threads() {
        ManagementFactory.threadMXBean.threadCount
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e
        while (cause.cause != null) {
            cause = cause.cause
        }
        cause.message
    }

    private static int setting(String name, int fallback) {
        System.getenv(name) ? System.getenv(name) as int : fallback
    }
}
