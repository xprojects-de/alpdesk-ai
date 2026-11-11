package de.alpdesk.services.document

import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.chat.FileRenderer
import de.alpdesk.services.chat.OutputFormat
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.multipart.MultipartBody
import io.micronaut.serde.ObjectMapper
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Requires
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/**
 * Measures how well the knowledge search finds the passage that answers a question - with a real embedding model, on the
 * fictional company in src/test/resources/retrieval (see its README). Skipped unless EVAL_OPENAI_KEY or EVAL_GEMINI_KEY is
 * set (it calls the provider, a few cents) or EVAL_OLLAMA_URL (an own Ollama with bge-m3, e.g. http://127.0.0.1:21434);
 * its numbers are a report, not a pass/fail rule. With EVAL_RERANK_URL (a TEI server with a reranker, e.g.
 * http://127.0.0.1:28080) the hybrid search is measured with the reranker too. Writes build/reports/retrieval/<model>.md.
 */
@Requires({ env.EVAL_OPENAI_KEY || env.EVAL_GEMINI_KEY || env.EVAL_OLLAMA_URL })
@MicronautTest(transactional = false)
@Property(name = "eval.real-ai-models", value = "true")
// The local servers of the measurement are reached over plain HTTP.
@Property(name = "app.ai.plain-http-hosts", value = "127.0.0.1")
class RetrievalEvaluationSpec extends Specification {

    static final String RESOURCES = "/retrieval"
    static final List<String> TYPES = ["EXACT", "TABLE", "SPLIT", "PARAPHRASE", "DISTRACTOR", "CODE"]
    /** What is compared: the vector search alone, the full text alone, ours hybrid at three weights, langchain4j's. */
    static final List<Map> VARIANTS = [
            [name: "VECTOR", mode: KnowledgeSearch.Mode.VECTOR, textWeight: 1.0d],
            [name: "FULL_TEXT", mode: KnowledgeSearch.Mode.FULL_TEXT, textWeight: 1.0d],
            [name: "HYBRID 1.0", mode: KnowledgeSearch.Mode.HYBRID, textWeight: 1.0d],
            [name: "HYBRID 0.5", mode: KnowledgeSearch.Mode.HYBRID, textWeight: 0.5d],
            [name: "HYBRID 0.3", mode: KnowledgeSearch.Mode.HYBRID, textWeight: 0.3d],
            [name: "LANGCHAIN4J", mode: KnowledgeSearch.Mode.LANGCHAIN4J_HYBRID, textWeight: 1.0d]]
    /** Measured only with EVAL_RERANK_URL: the hybrid search of the chat, its candidates reordered by the reranker. */
    static final Map RERANKED = [name: "HYBRID 0.3 + RERANK", mode: KnowledgeSearch.Mode.HYBRID, textWeight: 0.3d, rerank: true]

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    KnowledgeSearch knowledge

    @Inject
    TenantRepository tenants

    @Inject
    FileRenderer renderer

    def "the knowledge search finds the answering passages of the test company"() {
        given:
        def api = new AiApi(client)
        def embedding = embeddingModel(api)
        def tenant = api.tenant(false, "GERMAN")
        api.allow(tenant.id, embedding)
        if (providerKey()) {
            api.admin("PUT", "/admin/tenants/${tenant.id}/provider-keys/${embedding.provider}", [apiKey: providerKey()])
        }
        api.admin("PUT", "/admin/tenants/${tenant.id}/embedding-model", [modelId: embedding.id])

        when: "the company's documents are uploaded and embedded"
        def corpus = json("corpus.json")
        corpus.each { upload(api, tenant.id, it) }
        def documents = awaitAll(api, tenant.id, corpus.size())

        then:
        documents.every { it.status == "READY" }

        when: "every question is searched in every mode"
        def tenantEntity = tenants.findById(tenant.id as Long).get()
        def rerankedEntity = reranked(api, tenant.id)
        def questions = json("questions.json") as List<Map>
        Map<String, Long> millis = [:]
        Map<String, List<Map>> results = (VARIANTS + (rerankedEntity ? [RERANKED] : [])).collectEntries { variant ->
            long started = System.nanoTime()
            def rows = questions.collect { question ->
                def hits = knowledge.search(variant.rerank ? rerankedEntity : tenantEntity, null, KnowledgeScope.everything(),
                        question.question as String, variant.mode, variant.textWeight)
                [question: question, hits: hits, rank: rankOfAnswer(question.expected as List<Map>, hits)]
            }
            millis[variant.name] = Math.round((System.nanoTime() - started) / 1_000_000.0 / questions.size())
            [(variant.name): rows]
        }

        then:
        def report = report(embedding.modelName as String, documents, results, millis)
        println(report)
        def file = Path.of("build/reports/retrieval/${embedding.modelName}.md")
        Files.createDirectories(file.parent)
        Files.writeString(file, report)
    }

    /** The rank (1 = first) of the first hit that answers the question; 0 when none does. */
    private static int rankOfAnswer(List<Map> expected, List<KnowledgeSearch.Hit> hits) {
        for (int i = 0; i < hits.size(); i++) {
            def hit = hits[i]
            if (expected.any { it.file == hit.fileName() && normalize(hit.text()).contains(normalize(it.contains as String)) }) {
                return i + 1
            }
        }
        0
    }

    private static String report(String model, List<Map> documents, Map<String, List<Map>> results, Map<String, Long> millis) {
        def questions = results.values().first().size()
        def lines = ["# Retrieval measurement - ${model}".toString(), "",
                     "${documents.size()} documents, ${documents.sum { it.chunkCount }} chunks, ${questions} questions".toString()]
        results.each { mode, rows ->
            lines += ["", "## ${mode}".toString(), "", "${millis[mode]} ms per search".toString(), "",
                      "| Type | Questions | First hit | Among first 5 | MRR |", "|---|---|---|---|---|"]
            def answerable = rows.findAll { it.question.type != "NONE" }
            (TYPES.collect { type -> [type, answerable.findAll { it.question.type == type }] } + [["ALL", answerable]]).each { type, typed ->
                lines << "| ${type} | ${typed.size()} | ${percent(typed.count { it.rank == 1 }, typed.size())} | ${percent(typed.count { it.rank > 0 }, typed.size())} | ${String.format(Locale.ROOT, '%.2f', typed.sum(0.0) { it.rank > 0 ? 1.0 / it.rank : 0.0 } / Math.max(typed.size(), 1))} |".toString()
            }
            def none = rows.findAll { it.question.type == "NONE" }
            lines << "".toString()
            lines << "Questions without an answer: something found for ${none.count { !it.hits.isEmpty() }} of ${none.size()}.".toString()
        }
        def modes = results.keySet() as List
        lines += ["", "## Per question - rank of the answering passage", "",
                  "| Question | Type | ${modes.join(' | ')} |".toString(),
                  "|---|---|${modes.collect { '---' }.join('|')}|".toString()]
        (0..<questions).each { i ->
            def row = results[modes.first()][i]
            def ranks = modes.collect { results[it][i].rank ?: '-' }
            lines << "| ${row.question.id} ${row.question.question} | ${row.question.type} | ${ranks.join(' | ')} |".toString()
        }
        lines.join("\n") + "\n"
    }

    private static String percent(int count, int of) {
        of == 0 ? "-" : "${Math.round(100.0 * count / of)} %"
    }

    private static String normalize(String text) {
        text.replace(' ', ' ').replaceAll(/\s+/, " ").toLowerCase(Locale.ROOT)
    }

    private Map embeddingModel(AiApi api) {
        def ollama = System.getenv("EVAL_OLLAMA_URL")
        if (ollama) {
            return api.admin("POST", "/admin/models", [provider: "OLLAMA", kind: "EMBEDDING", modelName: "bge-m3",
                    dimension: 1024, baseUrl: ollama, displayName: "Messung " + AiApi.unique(), inputPrice: 0, outputPrice: 0]) as Map
        }
        def openai = System.getenv("EVAL_OPENAI_KEY")
        def model = openai
                ? [provider: "OPENAI", modelName: "text-embedding-3-small", dimension: 1536]
                : [provider: "GEMINI", modelName: "gemini-embedding-001", dimension: 1536]
        api.admin("POST", "/admin/models", model + [kind: "EMBEDDING", displayName: "Messung " + AiApi.unique(),
                                                   inputPrice: 0.02, outputPrice: 0]) as Map
    }

    /** The tenant with the TEI server of EVAL_RERANK_URL as its reranker - null without it. */
    private Object reranked(AiApi api, Object tenantId) {
        def address = System.getenv("EVAL_RERANK_URL")
        if (!address) {
            return null
        }
        def reranker = api.admin("POST", "/admin/models", [provider: "TEI", kind: "RERANK", modelName: "bge-reranker-v2-m3",
                baseUrl: address, displayName: "Messung " + AiApi.unique(), inputPrice: 0, outputPrice: 0]) as Map
        api.allow(tenantId, reranker)
        api.admin("PUT", "/admin/tenants/${tenantId}/reranking-model", [modelId: reranker.id])
        tenants.findById(tenantId as Long).get()
    }

    private static String providerKey() {
        System.getenv("EVAL_OPENAI_KEY") ?: System.getenv("EVAL_GEMINI_KEY")
    }

    /** Renders the source in its format - so the real text extraction runs - and uploads it as the admin. */
    private void upload(AiApi api, Object tenantId, Map document) {
        String source = getClass().getResource("${RESOURCES}/corpus/${document.source}").getText("UTF-8")
        byte[] content = renderer.render(OutputFormat.valueOf(document.format as String), source).content()
        def body = MultipartBody.builder()
                .addPart("file", document.file as String, MediaType.APPLICATION_OCTET_STREAM_TYPE, content).build()
        client.toBlocking().retrieve(HttpRequest.POST("/admin/tenants/${tenantId}/documents", body)
                .contentType(MediaType.MULTIPART_FORM_DATA_TYPE).bearerAuth(api.adminToken), Map)
    }

    private static List<Map> awaitAll(AiApi api, Object tenantId, int count) {
        long deadline = System.currentTimeMillis() + 300_000
        while (true) {
            def documents = api.admin("GET", "/admin/tenants/${tenantId}/documents") as List<Map>
            if (documents.size() == count && documents.every { it.status in ["READY", "FAILED"] } || System.currentTimeMillis() > deadline) {
                return documents
            }
            Thread.sleep(500)
        }
    }

    private Object json(String name) {
        ObjectMapper.getDefault().readValue(getClass().getResource("${RESOURCES}/${name}").getText("UTF-8"), Object)
    }
}
