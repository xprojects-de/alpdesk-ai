package de.alpdesk.services.document

import de.alpdesk.entities.tenant.SearchLanguage
import de.alpdesk.logging.RecentLogAppender
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.tenant.TenantDatabases
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.Duration

import static de.alpdesk.services.document.KnowledgeSearch.Mode.FULL_TEXT
import static de.alpdesk.services.document.KnowledgeSearch.Mode.HYBRID

/**
 * The search in the company knowledge: the full-text side finds any word of the query (German stems, without stop
 * words), the hybrid search merges it with the vector side - each chunk once, at most five. How well it finds is measured
 * with a real model in RetrievalEvaluationSpec.
 */
@MicronautTest(transactional = false)
class KnowledgeSearchSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    FakeAiModels fake

    @Inject
    KnowledgeSearch knowledge

    @Inject
    TenantRepository tenants

    @Inject
    TenantDatabases databases

    @Inject
    TenantEmbeddings embeddings

    @Inject
    DocumentIndexer indexer

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "the full text finds a chunk by an exact code in a question, and by the stem of a word"() {
        given:
        def tenant = tenantWith([
                "fehler.txt" : "Der Fehlercode E23 bedeutet Abgastemperatur zu hoch.",
                "wartung.txt": "Die Wartungen erfolgen jährlich durch einen Fachbetrieb."])

        expect:
        files(knowledge.search(tenant, null, KnowledgeScope.everything(), "Was bedeutet E23?", FULL_TEXT)) == ["fehler.txt"]
        files(knowledge.search(tenant, null, KnowledgeScope.everything(), "Wartung", FULL_TEXT)) == ["wartung.txt"]
        knowledge.search(tenant, null, KnowledgeScope.everything(), "Was ist das?", FULL_TEXT) == []
    }

    def "the full text finds a code written in XML - PostgreSQL's parser took it for a tag and dropped it: #query"() {
        given: "a configuration that is nothing but XML, as a manual's example shows it"
        def tenant = tenantWith([
                "konfiguration.md": """```xml
<object name="nk2400" className="NK2400">
  <param name="moduleAddress" value="1009" />
</object>
```""",
                "andere.txt"      : "Die Wartung erfolgt jährlich."])

        expect:
        files(knowledge.search(tenant, null, KnowledgeScope.everything(), query, FULL_TEXT)) == ["konfiguration.md"]

        where:
        query << ["NK2400", "Wie konfiguriere ich die NK2400?", "moduleAddress"]
    }

    def "a chunk matching more words of the query comes first in the full text"() {
        given:
        def tenant = tenantWith([
                "a.txt": "Der Kessel braucht eine Wartung.",
                "b.txt": "Der Kessel NK 24 braucht jährlich eine Wartung durch den Kundendienst."])

        expect:
        files(knowledge.search(tenant, null, KnowledgeScope.everything(), "jährliche Wartung Kundendienst Kessel", FULL_TEXT)).first() == "b.txt"
    }

    def "the hybrid search returns each chunk once and at most five - and what only the full text finds"() {
        given:
        def texts = (1..8).collectEntries { ["doc-${it}.txt".toString(), "Kessel Wartung Hinweis Nummer ${it}.".toString()] }
        def tenant = tenantWith(texts + ["code.txt": "Ersatzteil 4711-B"])

        when: "chunks both sides find"
        def both = knowledge.search(tenant, null, KnowledgeScope.everything(), "Kessel Wartung", HYBRID)

        then:
        both.size() == 5
        both*.id().unique().size() == 5

        expect: "a code no vector is close to"
        files(knowledge.search(tenant, null, KnowledgeScope.everything(), "4711-B", HYBRID)).contains("code.txt")
    }

    def "langchain4j's own hybrid search runs on the same table - compared with ours in the measurement"() {
        given:
        def tenant = tenantWith(["code.txt": "Ersatzteil 4711-B", "other.txt": "Die Wartung erfolgt jährlich."])

        expect:
        files(knowledge.search(tenant, null, KnowledgeScope.everything(), "Ersatzteil 4711-B", KnowledgeSearch.Mode.LANGCHAIN4J_HYBRID)).first() == "code.txt"
    }

    def "every chunk starts with the document's file name and title - so a chunk deep in it is found by its subject"() {
        given:
        def filler = "Allgemeine Hinweise zur Montage und zum Betrieb der Anlage. " * 40
        def tenant = tenantWith(["anleitung.txt": "Betriebsanleitung Kessel NK 24\n\n${filler}\n\nGewicht leer: 38 kg".toString(),
                                 "andere.txt"   : "Gewicht leer: 47 kg"])

        when:
        def hits = knowledge.search(tenant, null, KnowledgeScope.everything(), "NK 24 Gewicht", FULL_TEXT)

        then: "the weight of the NK 24 comes before the other document's weight"
        def nk24 = hits.findIndexOf { it.text().contains("38 kg") }
        def other = hits.findIndexOf { it.fileName() == "andere.txt" }
        nk24 >= 0
        other < 0 || nk24 < other
        hits.findAll { it.fileName() == "anleitung.txt" }.every { it.text().startsWith("anleitung.txt – Betriebsanleitung Kessel NK 24\n\n") }
    }

    def "the context header: file name and the first line as title, shortened when long"() {
        expect:
        DocumentIndexer.contextHeader("a.pdf", text) == header

        where:
        text                         | header
        "  Titel der Anleitung\nText" | "a.pdf – Titel der Anleitung\n\n"
        "x" * 130 + "\nText"          | "a.pdf – " + "x" * 120 + "…\n\n"
        "   "                        | "a.pdf\n\n"
    }

    def "the title is the first line with a letter or a digit, without its markup: #text"() {
        expect:
        DocumentIndexer.contextHeader("a.md", text) == "a.md – " + title + "\n\n"

        where:
        text                                              | title
        '---\ntitle: "Serie NK24xx"\n---\nText'            | 'title: "Serie NK24xx"'
        "***\n* * *\n===\n___\nHandbuch Wartung"            | "Handbuch Wartung"
        "```\nKonfiguration"                              | "Konfiguration"
        "|---|---|\nPreisliste"                           | "Preisliste"
        "📊\n⚙️\n•\n→\nPreisliste"                          | "Preisliste"
        "# Handbuch Wartung"                              | "Handbuch Wartung"
        "### 📊 Allgemeine Spezifikationen"                | "Allgemeine Spezifikationen"
        "**Betriebsanleitung**"                           | "Betriebsanleitung"
        "> Hinweis zur Sicherheit"                        | "Hinweis zur Sicherheit"
        "- Punkt eins"                                    | "Punkt eins"
        "| Artikel | Preis |"                             | "Artikel | Preis"
        "\uFEFFPreisliste 2026"                           | "Preisliste 2026"
        "Was ist neu?"                                    | "Was ist neu?"
        "Preisliste (Stand 2026)"                         | "Preisliste (Stand 2026)"
        "„Leitbild“"                                      | "„Leitbild“"
    }

    def "a document of nothing but markup has its path alone as header"() {
        expect:
        DocumentIndexer.contextHeader("a.md", "---\n|---|\n📊") == "a.md\n\n"
    }

    def "the chat's search is hybrid by default (app.search.mode): it finds a word by its stem"() {
        given:
        def tenant = tenantWith(["wartung.txt": "Die Wartung erfolgt jährlich."])

        expect:
        files(knowledge.search(tenant, null, KnowledgeScope.everything(), "Wartungen", 5)) == ["wartung.txt"]
    }

    def "the table gets its full-text index for the tenant's language and its HNSW index with the first embedded document"() {
        given:
        def tenant = tenantWith(["a.txt": "Text"])

        expect:
        textIndexes(tenant) == ["embedding_text_german_v2_idx"]
        query(tenant, "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'embedding_text_german_v2_idx' "
                + "AND indexdef LIKE '%to_tsvector(''german''::regconfig, translate(%'") == 1
        query(tenant, "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'embedding' AND indexname = 'embedding_vector_idx' "
                + "AND indexdef LIKE '%hnsw%vector_cosine_ops%'") == 1
    }

    def "the vector search uses the HNSW index once the table is large enough for it"() {
        given:
        def tenant = tenantWith(["a.txt": "Text"])
        def vector = "[" + (["0.1"] * 64).join(",") + "]"

        expect: "with sequential scans off - on a table this small the planner would rightly scan it"
        explain(tenant, "SELECT embedding_id FROM embedding ORDER BY embedding <=> '${vector}' LIMIT 20")
                .contains("embedding_vector_idx")
    }

    def "the full-text search uses its index - the search's own statement, its expression exactly the index's"() {
        given:
        def tenant = tenantWith(["a.txt": "Text"])
        def sql = TenantEmbeddings.textSearchSql(SearchLanguage.GERMAN, KnowledgeScope.everything())
                .replace("?", "'text'").replaceFirst(/LIMIT 'text'$/, "LIMIT 20")

        expect:
        explain(tenant, sql).contains("Bitmap Index Scan on embedding_text_german_v2_idx")
    }

    def "langchain4j's own full-text index of the measurement is left alone"() {
        given: "the index its hybrid store builds at every search in that mode - dropped, it would be built again on the pool"
        def tenant = tenantWith(["a.txt": "Text"])
        execute(tenant, "CREATE INDEX embedding_text_fts_gin_index ON embedding USING gin (to_tsvector('german', coalesce(text, '')))")

        when:
        embeddings.ensureTextIndex(tenant.databaseName(), SearchLanguage.GERMAN)

        then:
        textIndexes(tenant) == ["embedding_text_fts_gin_index", "embedding_text_german_v2_idx"]
    }

    def "a tenant's table gets the full-text index of its language - the one of before goes (as on the server's start)"() {
        given: "a tenant's table with the index of before: German, without reading XML"
        def tenant = tenantWith(["a.txt": "Text"])
        execute(tenant, "DROP INDEX embedding_text_german_v2_idx")
        execute(tenant, "CREATE INDEX embedding_text_idx ON embedding USING gin (to_tsvector('german', coalesce(text, '')))")

        when: "in the background, as the start queues it for every tenant (TenantDatabasesSpec walks the start itself)"
        indexer.ensureTextIndex(tenant.id())

        then:
        new PollingConditions(timeout: 10).eventually {
            assert textIndexes(tenant) == ["embedding_text_german_v2_idx"]
        }
    }

    def "a build that fails is tried again - and given up with an error after the last attempt"() {
        given: "a tenant whose database is gone - every build fails"
        def tenant = tenantWith(["a.txt": "Text"])
        databases.drop(tenant.databaseName())
        indexer.@indexRetryDelay = Duration.ofMillis(50)

        when:
        indexer.ensureTextIndex(tenant.id())

        then:
        new PollingConditions(timeout: 10).eventually {
            def entries = RecentLogAppender.installed().entries().findAll {
                it.logger() == DocumentIndexer.name && it.message().contains("index of tenant ${tenant.id()} ")
            }
            assert entries.count { it.level() == "WARN" } == DocumentIndexer.INDEX_ATTEMPTS - 1
            assert entries.count { it.level() == "ERROR" } == 1
            assert entries.find { it.level() == "ERROR" }.message().contains("could not be built in ${DocumentIndexer.INDEX_ATTEMPTS} attempts")
        }

        cleanup: "the database back, so a later start in this context finds nothing missing"
        indexer.@indexRetryDelay = DocumentIndexer.INDEX_RETRY_DELAY
        databases.ensure(tenant.databaseName())
    }

    def "an index build waits longer for a lock than the tenant's other connections"() {
        given:
        def tenant = tenantWith([:])

        expect:
        lockTimeout(databases.openConnection(tenant.databaseName())) == "10s"
        lockTimeout(databases.openConnection(tenant.databaseName(), TenantEmbeddings.INDEX_LOCK_TIMEOUT)) == "2min"
    }

    def "an index whose build broke off is built again"() {
        given: "the index marked invalid, as PostgreSQL leaves a CREATE INDEX CONCURRENTLY broken off"
        def tenant = tenantWith(["a.txt": "Die Wartungen erfolgen jährlich."])
        execute(tenant, "UPDATE pg_index SET indisvalid = false WHERE indexrelid = 'embedding_text_german_v2_idx'::regclass")

        when:
        embeddings.ensureTextIndex(tenant.databaseName(), SearchLanguage.GERMAN)

        then:
        query(tenant, "SELECT COUNT(*) FROM pg_index WHERE indexrelid = 'embedding_text_german_v2_idx'::regclass AND indisvalid") == 1
        files(knowledge.search(tenant, null, KnowledgeScope.everything(), "Wartung", FULL_TEXT)) == ["a.txt"]
    }

    def "the full text reads the tenant's language - its word forms; SIMPLE finds a word only as written: #language #query"() {
        given:
        def tenant = tenantWith(["a.txt": text], language)

        expect:
        (files(knowledge.search(tenant, null, KnowledgeScope.everything(), query, FULL_TEXT)) == ["a.txt"]) == found
        textIndexes(tenant) == ["embedding_text_${language.toLowerCase()}_v2_idx".toString()]

        where:
        language  | text                                    | query      || found
        "GERMAN"  | "Die Wartungen erfolgen jährlich."      | "Wartung"  || true
        "ENGLISH" | "The boilers are serviced every year."  | "boiler"   || true
        "RUSSIAN" | "Котлы обслуживаются ежегодно."          | "котлов"   || true
        "SIMPLE"  | "The boilers are serviced every year."  | "boiler"   || false
        "SIMPLE"  | "The boilers are serviced every year."  | "boilers"  || true
        "SIMPLE"  | "Die Wartungen erfolgen jährlich."      | "Wartung"  || false
        "ENGLISH" | "Die Wartungen erfolgen jährlich."      | "Wartung"  || false
    }

    def "before the first document is embedded the search finds nothing - and leaves the table to the indexer"() {
        given: "a tenant with an embedding model, no document yet"
        def tenant = tenantWith([:])

        expect: "a search holding the previous model would otherwise create the table with that model's vector length"
        knowledge.search(tenant, null, KnowledgeScope.everything(), "Kessel", HYBRID) == []
        knowledge.search(tenant, null, KnowledgeScope.everything(), "Kessel", FULL_TEXT) == []
        !tableExists(tenant)
    }

    def "removing a document's chunks before the first is embedded does nothing - and leaves the table to the indexer"() {
        given:
        def tenant = tenantWith([:])

        when: "a document is deleted while the indexer creates the table - two creators failed on PostgreSQL's catalog"
        embeddings.removeDocument(tenant.databaseName(), 64, 1L)

        then:
        noExceptionThrown()
        !tableExists(tenant)
    }

    def "without an embedding model there is nothing to search"() {
        expect:
        knowledge.search(tenants.findById(api.tenant().id as Long).get(), null, KnowledgeScope.everything(), "Kessel", 5) == []
    }

    /**
     * A tenant with an embedding model and these documents (file name -> text), embedded - searched in German unless
     * another language is given.
     */
    static de.alpdesk.entities.tenant.Tenant tenantWithDocuments(AiApi api, TenantRepository tenants, Map<String, String> documents,
                                                                 String language = "GERMAN") {
        def tenant = api.tenant(false, language)
        def token = api.tenantAdminToken(tenant.id)
        api.call(token, "PUT", "/tenant/admin/provider-keys/OPENAI", [apiKey: "sk-own-openai-key-1"])
        def embedding = api.embeddingModel()
        api.allow(tenant.id, embedding)
        api.call(token, "PUT", "/tenant/admin/embedding-model", [modelId: embedding.id])
        documents.each { name, text -> api.awaitDocument(token, api.upload(token, name, text).id) }
        tenants.findById(tenant.id as Long).get()
    }

    private de.alpdesk.entities.tenant.Tenant tenantWith(Map<String, String> documents, String language = "GERMAN") {
        tenantWithDocuments(api, tenants, documents, language)
    }

    /** The names of the table's full-text indexes. */
    private List<String> textIndexes(de.alpdesk.entities.tenant.Tenant tenant) {
        databases.dataSource(tenant.databaseName()).connection.withCloseable { connection ->
            def result = connection.createStatement().executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'embedding' AND indexname LIKE 'embedding_text%' ORDER BY 1")
            def names = []
            while (result.next()) {
                names << result.getString(1)
            }
            names
        }
    }

    private static List<String> files(List<KnowledgeSearch.Hit> hits) {
        hits*.fileName()
    }

    /** The plan of a query with sequential scans switched off. */
    private String explain(de.alpdesk.entities.tenant.Tenant tenant, String sql) {
        databases.dataSource(tenant.databaseName()).connection.withCloseable { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().execute("SET LOCAL enable_seqscan = off")
                def result = connection.createStatement().executeQuery("EXPLAIN " + sql)
                def plan = new StringBuilder()
                while (result.next()) {
                    plan.append(result.getString(1)).append("\n")
                }
                plan.toString()
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    private boolean tableExists(de.alpdesk.entities.tenant.Tenant tenant) {
        query(tenant, "SELECT COUNT(*) FROM pg_tables WHERE tablename = 'embedding'") == 1
    }

    private static String lockTimeout(java.sql.Connection connection) {
        connection.withCloseable {
            def result = it.createStatement().executeQuery("SHOW lock_timeout")
            result.next()
            result.getString(1)
        }
    }

    private void execute(de.alpdesk.entities.tenant.Tenant tenant, String sql) {
        databases.dataSource(tenant.databaseName()).connection.withCloseable { connection ->
            connection.createStatement().execute(sql)
        }
    }

    private int query(de.alpdesk.entities.tenant.Tenant tenant, String sql) {
        databases.dataSource(tenant.databaseName()).connection.withCloseable { connection ->
            def result = connection.createStatement().executeQuery(sql)
            result.next()
            result.getInt(1)
        }
    }
}
