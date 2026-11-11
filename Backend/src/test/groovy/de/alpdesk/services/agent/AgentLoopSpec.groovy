package de.alpdesk.services.agent

import de.alpdesk.entities.ai.KeySource
import de.alpdesk.logging.RecentLogAppender
import de.alpdesk.repositories.ai.AiModelRepository
import de.alpdesk.repositories.tenant.TenantRepository
import de.alpdesk.services.ai.AiApi
import de.alpdesk.services.ai.ChatOptions
import de.alpdesk.services.ai.FakeAiModels
import de.alpdesk.services.ai.ResolvedKey
import de.alpdesk.services.chat.SentFile
import de.alpdesk.services.chat.SentFiles
import de.alpdesk.services.common.ServiceException
import de.alpdesk.services.document.KnowledgeScope
import de.alpdesk.services.tenant.TenantDatabases
import de.alpdesk.services.tools.ExploredFile
import de.alpdesk.services.tools.ToolContext
import de.alpdesk.services.tools.ToolStep
import de.alpdesk.services.tools.Toolbox
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The rules of the agent loop itself, the same in one piece (/api/v1/chat, the MCP server) and streamed (the chat):
 * rounds, the last one without tools, the usage per round, the answer it returns.
 */
@MicronautTest(transactional = false)
class AgentLoopSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    AgentLoop loop

    @Inject
    FakeAiModels fake

    @Inject
    Toolbox toolbox

    @Inject
    SentFiles sentFiles

    @Inject
    TenantRepository tenants

    @Inject
    TenantDatabases databases

    @Inject
    AiModelRepository models

    @Shared
    AiApi api

    def setup() {
        api = api ?: new AiApi(client)
        fake.reset()
    }

    def "the last round must answer without tools, every round is recorded - #mode"() {
        given: "a model that would search a sent log forever, three rounds"
        def tenant = api.tenant()
        def run = agentRun(tenant, "Suche [[explore-forever]]", 3)

        when:
        def result = answer(run, mode)

        then:
        fake.chats.size() == 3
        fake.chats[0..1].every { it.tools.contains("search_file") }
        fake.chats[2].tools.isEmpty()
        fake.chats[2].listedTools.contains("search_file")
        result.answer.startsWith("Antwort auf: Suche")

        and: "one call per round in the tenant's usage"
        api.call(api.tenantAdminToken(tenant.id), "GET", "/tenant/admin/usage").sum { it.calls } == 3

        where:
        mode << ["run", "stream"]
    }

    def "the answer in one piece is streamed from the provider too - one path, the stream's protections everywhere"() {
        given:
        def run = agentRun(api.tenant(), "Suche [[explore-forever]]", 3)

        when:
        def result = answer(run, "run")

        then:
        result.answer.startsWith("Antwort auf: Suche")
        fake.streamedCalls.get() == 3
        fake.chats.size() == 3
    }

    def "a streamed answer separates the rounds' texts; the answer is the last round's"() {
        given:
        def run = agentRun(api.tenant(), "Suche [[explore-forever]]", 2)

        when:
        def result = answer(run, "stream")

        then:
        result.parts.join("") == "Ich suche weiter.\n\n" + result.answer
        result.answer.startsWith("Antwort auf: Suche")
    }

    def "an answer without text fails - empty also when asked again, or cut off by the output limit: #mode, #marker"() {
        given:
        def run = agentRun(api.tenant(), "Frage " + marker, 3)

        when:
        def result = answer(run, mode)

        then:
        result.error.status().code == 502
        result.error.message.contains(reason)
        fake.chats.size() == calls

        where: "asked again once - cut off by the limit it would be cut off again, so it is not asked"
        mode     | marker             || reason                                       | calls
        "run"    | "[[empty]]"        || "gave no answer"                             | 2
        "stream" | "[[empty]]"        || "gave no answer"                             | 2
        "run"    | "[[empty-length]]" || "used up its output limit on its reasoning"  | 1
        "stream" | "[[empty-length]]" || "used up its output limit on its reasoning"  | 1
    }

    def "a model that ends without a word is asked once more, without tools - its answer stands: #mode, #marker"() {
        given: "a round ended empty - or cut for reasoning in a loop (finish reason OTHER)"
        def tenant = api.tenant()
        def run = agentRun(tenant, "Frage " + marker, 3)

        when:
        def result = answer(run, mode)

        then:
        result.answer.startsWith("Antwort auf: Frage")
        fake.chats.size() == 2
        fake.chats[1].tools.isEmpty()
        (fake.chats[1].messages.last() as UserMessage).singleText() == AgentLoop.ASK_AGAIN

        and: "the round asking again is a provider call like every other"
        api.call(api.tenantAdminToken(tenant.id), "GET", "/tenant/admin/usage").sum { it.calls } == 2

        where:
        [mode, marker] << [["run", "stream"], ["[[empty-once]]", "[[empty-once-loop]]"]].combinations()
    }

    def "a round StreamStop ended has no token count - its input is estimated from the messages sent: #mode"() {
        given: "the first round cut for reasoning in a loop, the second answering with the provider's count (1000 in)"
        def tenant = api.tenant()
        def run = agentRun(tenant, "Frage [[empty-once-loop]]", 3)

        when:
        answer(run, mode)
        def usage = (api.call(api.tenantAdminToken(tenant.id), "GET", "/tenant/admin/usage") as List<Map>)[0]

        then:
        usage.calls == 2
        usage.estimated == true
        usage.inputTokens > 1000

        where:
        mode << ["run", "stream"]
    }

    def "a draft next to earlier tool calls is no answer - an empty last round is still asked again: #mode"() {
        given: "a model writing 'Ich suche weiter.' next to every search, then ending the last round empty"
        def run = agentRun(api.tenant(), "Suche [[explore-forever]] [[empty-once]]", 3)

        when:
        def result = answer(run, mode)

        then:
        result.answer.startsWith("Antwort auf: Suche")
        !result.answer.contains("Ich suche weiter.")
        fake.chats.size() == 4
        (fake.chats[3].messages.last() as UserMessage).singleText() == AgentLoop.ASK_AGAIN

        where:
        mode << ["run", "stream"]
    }

    def "an empty last round is asked again with everything found before: #mode"() {
        given: "a model that searches without a word until the last round, which has no tools, and ends it empty"
        def run = agentRun(api.tenant(), "Suche [[explore-silently]] [[empty-once]]", 3)

        when:
        def result = answer(run, mode)

        then:
        result.answer.startsWith("Antwort auf: Suche")
        fake.chats.size() == 4
        fake.chats[2].tools.isEmpty()
        fake.chats[3].tools.isEmpty()
        fake.chats[3].messages.count { it instanceof ToolExecutionResultMessage } == 2
        (fake.chats[3].messages.last() as UserMessage).singleText() == AgentLoop.ASK_AGAIN

        where:
        mode << ["run", "stream"]
    }

    def "an answer without text leaves a warning that tells its causes apart - lengths and counts, never the content - #mode"() {
        given: "a model that thinks and then answers nothing, in the first of three rounds"
        def tenant = api.tenant()
        def run = agentRun(tenant, "Frage [[empty]] [[think Geheime Gedanken über die Firma]]", 3)

        when:
        def result = answer(run, mode)

        then:
        result.error.status().code == 502
        def warnings = RecentLogAppender.installed().entries().findAll {
            it.logger() == AgentLoop.name && it.message().contains("gave no answer for tenant ${tenant.id}:")
        }
        def model = run.model().displayName()
        warnings*.message() == [
                "${model} gave no answer for tenant ${tenant.id}: round 2, limit 3, tools callable false, finish reason STOP, "
                        + "text 0 characters, reasoning 31 characters, 0 tool calls - empty again after asking",
                "${model} gave no answer for tenant ${tenant.id}: round 1, limit 3, tools callable true, finish reason STOP, "
                        + "text 0 characters, reasoning 31 characters, 0 tool calls - asked again"]
        warnings*.level() == ["WARN", "WARN"]
        warnings.every { !it.message().contains("Geheime") }

        where:
        mode << ["run", "stream"]
    }

    def "a tool failing unexpectedly ends the answer as not completed, not silently - #mode"() {
        given: "a sent log whose lines cannot be read"
        def run = agentRun(api.tenant(), "Suche [[explore-forever]]", 3)
        run.context().exploredFiles([new ExploredFile("machine.log", null)])

        when:
        def result = answer(run, mode)

        then:
        (result.error as ServiceException).code() == "chat.answerNotCompleted"

        where:
        mode << ["run", "stream"]
    }

    /** A question with a log too large to go with it whole - the model gets search_file and read_file_lines. */
    private AgentRun agentRun(Map tenantMap, String question, int maxRounds) {
        def tenant = tenants.findById(tenantMap.id as Long).get()
        def model = models.findById(api.chatModel("GEMINI").id as Long).get()
        def context = new ToolContext(tenant, ToolContext.Asker.CLIENT_SOFTWARE, model, null, KnowledgeScope.everything(),
                databases.dataSource(tenant.databaseName()), null, false, 5, ToolContext.Files.NONE)
        def sent = sentFiles.prepare([new SentFile("machine.log", "ERROR x\n" * 400)], true)
        context.exploredFiles(sent.explored())
        new AgentRun(tenant, null, model, new ResolvedKey("test-key", KeySource.OWN), ChatOptions.NONE,
                [SystemMessage.from("Test"), UserMessage.from(question + sent.text())],
                toolbox.specificationsFor(context), context, maxRounds)
    }

    /** The answer in one piece or streamed: [answer] and the streamed [parts], or the [error]. */
    private Map answer(AgentRun run, String mode) {
        if (mode == "run") {
            try {
                return [answer: loop.run(run)]
            } catch (ServiceException e) {
                return [error: e]
            }
        }
        List<String> parts = []
        def done = new CompletableFuture<Map>()
        loop.stream(run, new AgentLoop.Listener({ String part -> parts << part }, { String thinking -> },
                { List<String> sources -> }, { ToolStep tool -> }, { String answer -> done.complete([answer: answer, parts: parts]) },
                { ServiceException failure -> done.complete([error: failure]) }))
        done.get(10, TimeUnit.SECONDS)
    }
}
