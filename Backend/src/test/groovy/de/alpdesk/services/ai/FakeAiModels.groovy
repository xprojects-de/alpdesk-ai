package de.alpdesk.services.ai

import de.alpdesk.entities.ai.AiModel
import de.alpdesk.services.ai.AiModels.CreatedImage
import de.alpdesk.services.common.ServiceException
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.image.Image
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.output.FinishReason
import dev.langchain4j.model.chat.response.PartialThinking
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.output.Response
import dev.langchain4j.model.output.TokenUsage
import io.micronaut.context.annotation.Replaces
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpStatus

import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import java.awt.image.BufferedImage
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stands in for every AI provider in the specs. Embeddings are word hashes (texts sharing words are close, so the
 * retrieval finds the right chunks). Like a model using its tools, it first searches (similarity_search, with the
 * question) and reads every listed file (read_file: TEXT, PAGES for a scan) when it is offered them; with
 * "[[create_file <name> <FORMAT>]]" in the question it then creates that file (content naming the files it read) and
 * answers with the tool's result; otherwise it answers with the question, the excerpts found, the images and the files
 * read. With "[[search <query>]]" it searches every large file sent with the question (search_file) and reads the lines
 * around the first match (read_file_lines), answering with both results; "[[explore-forever]]" searches in every round
 * it may, "[[explore-silently]]" too, without a word next to its calls; "[[shell-forever]]" runs a shell command in
 * every round it may (a model working through a video). With "[[read-document]]" it reads on in the first document the search found (read_document). With
 * "[[create_image <file names>]]" it asks the image model (create_image) for an image of these files of the
 * conversation - a new one without names; the image model's fake is createImage below. "[[create_image_repeatedly]]"
 * asks for a new image in four rounds one after the other. "[[web <query>]]" searches the web (web_search) once per
 * marker, one search a round, after the documents; "[[web-first <query>]]" searches it before the documents. The
 * reranker's fake (rerank) keeps the search's order but puts every text containing {@link #RERANK_FAVOURITE} first.
 */
@jakarta.inject.Singleton
@Replaces(AiModels)
// Off only for the retrieval measurement, which needs a real embedding model (RetrievalEvaluationSpec).
@Requires(property = "eval.real-ai-models", notEquals = "true")
class FakeAiModels extends AiModels {

    static final String THOUGHT_SIGNATURE = "thought_signature"
    /** A text with this word is the reranker's best - wherever the search placed it. */
    static final String RERANK_FAVOURITE = "Favorit"

    /** Every chat call: the model, the key, the messages, the tools it was offered and the tenant's options. */
    final List<Map> chats = new CopyOnWriteArrayList<>()
    /** How many of them were streamed - the answer in one piece streams too. */
    final AtomicInteger streamedCalls = new AtomicInteger()
    /** Every rerank call: the model, the key, the query and the texts in the search's order. */
    final List<Map> reranks = new CopyOnWriteArrayList<>()
    /** Reranking fails like a server out of reach. */
    volatile boolean rerankFails = false
    /** Every embedding call: the model, the key and how many segments. */
    final List<Map> embeddings = new CopyOnWriteArrayList<>()
    /** Whether embedding calls report a token count - some providers do not. */
    volatile boolean reportEmbeddingTokens = true
    /** The next calls fail like a provider refusing the key. */
    volatile int failures = 0
    /** Streams on a thread of its own, like a real provider's HTTP client - not on the thread that asked. */
    volatile boolean streamsOnOwnThread = false
    /** The call after a tool result fails - an answer failing after it created a file. */
    volatile boolean failAfterTool = false
    /** Every title asked for a new conversation (services.chat.ConversationTitles): the question - kept apart from chats. */
    final List<String> titles = new CopyOnWriteArrayList<>()
    /** Asking for a title fails - the conversation keeps the question's first line. */
    volatile boolean titleFails = false
    /** Every image model call: the model, the key, the instruction and the images sent (their bytes). */
    final List<Map> createdImages = new CopyOnWriteArrayList<>()
    /** The image model answers with this text and no image - like Gemini refusing an instruction. */
    volatile String imageRefusal = null
    /** The image model fails like a provider out of reach (502) - the chat model's calls still answer. */
    volatile boolean imageFails = false
    /** Embedding fails like a provider out of reach (502) - the chat model's calls still answer. */
    volatile boolean embeddingFails = false
    /** How long each embedding call takes - a tenant's large import keeps the indexer busy (milliseconds). */
    volatile long embeddingDelay = 0
    /** How many answers of a task's step are no result at all - the step asks once more, then fails. */
    volatile int brokenTaskAnswers = 0
    /** While set, a task's verify step waits for it - a run stays open after its agent step found something. */
    volatile CountDownLatch verifyHold = null
    /** While set, every answer to an extract step waits for it - a run stays between its documents. */
    volatile CountDownLatch extractHold = null
    /** Called while an audio file is transcribed - a spec looks at what is stored at that moment. */
    volatile Closure whileTranscribing = null

    FakeAiModels(BedrockClients bedrock) {
        super(Duration.ofSeconds(1), 100, new ModelServers(""), bedrock)
    }

    void reset() {
        streamedCalls.set(0)
        chats.clear()
        embeddings.clear()
        reranks.clear()
        rerankFails = false
        reportEmbeddingTokens = true
        failures = 0
        streamsOnOwnThread = false
        failAfterTool = false
        titles.clear()
        titleFails = false
        createdImages.clear()
        imageRefusal = null
        imageFails = false
        embeddingFails = false
        embeddingDelay = 0
        brokenTaskAnswers = 0
        verifyHold = null
        extractHold = null
        whileTranscribing = null
    }

    /** The answer in pieces of one word (with its space), then the whole response - like a provider streaming it. */
    @Override
    void streamChat(AiModel model, String apiKey, List<ChatMessage> messages, List<ToolSpecification> tools,
                    boolean toolsCallable, ChatOptions options, StreamingChatResponseHandler handler) {
        if (streamsOnOwnThread) {
            Thread.startVirtualThread { stream(model, apiKey, messages, tools, toolsCallable, options, handler) }
        } else {
            stream(model, apiKey, messages, tools, toolsCallable, options, handler)
        }
    }

    private void stream(AiModel model, String apiKey, List<ChatMessage> messages, List<ToolSpecification> tools,
                        boolean toolsCallable, ChatOptions options, StreamingChatResponseHandler handler) {
        streamedCalls.incrementAndGet()
        ChatResponse response
        try {
            response = chat(model, apiKey, messages, tools, toolsCallable, options)
        } catch (ServiceException e) {
            handler.onError(e)
            return
        }
        // "[[think <text>]]": the model's reasoning, streamed before its answer like a thinking model's.
        def thinking = questionText(messages) =~ /\[\[think ([^\]]+)]]/
        if (thinking.find()) {
            (thinking.group(1) =~ /\S+\s*/).each { handler.onPartialThinking(new PartialThinking(it as String)) }
        }
        if (response.aiMessage().text()) {
            (response.aiMessage().text() =~ /\S+\s*/).each { handler.onPartialResponse(it as String) }
        }
        handler.onCompleteResponse(response)
    }

    @Override
    ChatResponse chatInBackground(AiModel model, String apiKey, List<ChatMessage> messages) {
        chat(model, apiKey, messages, [], true, ChatOptions.NONE)
    }

    @Override
    ChatResponse chat(AiModel model, String apiKey, List<ChatMessage> messages, List<ToolSpecification> listed,
                      boolean toolsCallable, ChatOptions options) {
        // In the last round the tools are listed but none may be called (tool choice NONE).
        List<ToolSpecification> tools = toolsCallable ? listed : []
        if ((messages.find { it instanceof SystemMessage } as SystemMessage)?.text()?.startsWith("You name conversations")) {
            return title(model, (messages.last() as UserMessage).singleText())
        }
        failIfAsked(model)
        refuseInvalidToolNames(model, listed)
        refuseBlankAnswers(model, messages)
        refuseUnsignedToolResults(model, messages)
        chats << [model: model.modelName(), apiKey: apiKey, messages: List.copyOf(messages), tools: tools*.name(),
                  listedTools: listed*.name(), toolSpecifications: List.copyOf(listed), options: options]
        String system = (messages.find { it instanceof SystemMessage } as SystemMessage)?.text()
        if (system?.startsWith("You check files for a scheduled task") || system?.startsWith("You verify the findings")) {
            return taskResult(messages, system.startsWith("You verify"))
        }
        if (system?.startsWith("You are one step of a scheduled task")) {
            return promptAnswer(messages, tools*.name())
        }
        if (system?.startsWith("You take values out of one document")) {
            return extractAnswer(messages, tools*.name())
        }
        if (system?.startsWith("You write scheduled tasks")) {
            return taskDraft(messages, system)
        }
        if (tools.isEmpty() && (messages.find { it instanceof SystemMessage } as SystemMessage)?.text()?.startsWith("Transcribe")) {
            int audio = (messages.last() as UserMessage).contents().count { it instanceof dev.langchain4j.data.message.AudioContent } as int
            whileTranscribing?.call()
            return answer("Transkript der Aufnahme (" + audio + " Audio)")
        }
        if (tools.isEmpty() && (messages.find { it instanceof SystemMessage } as SystemMessage)?.text()?.startsWith("You turn an image")) {
            String label = ((messages.last() as UserMessage).contents().find { it instanceof TextContent } as TextContent).text()
            return answer("Gelesen vom Bildmodell - " + label + " Skizze mit Toren und Abständen")
        }
        List<ToolExecutionResultMessage> results = messages.findAll { it instanceof ToolExecutionResultMessage }
        if (failAfterTool && messages.last() instanceof ToolExecutionResultMessage) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "provider.failed", model.provider().name() + " failed - please try again",
                    [name: model.provider().name()])
        }
        String text = questionText(messages)
        String question = text.contains("=== Question ===\n") ? text.substring(text.indexOf("=== Question ===\n") + 17) : text
        List<String> offered = tools*.name()

        // "[[call <tool> <json>]]": a connected software's tool, called like a model would (once) - the first one
        // offered of those named; with "[[together]]" every one offered, in one response.
        List<List> softwareCalls = (question =~ /\[\[call (\S+) (\{.*?\})]]/).findAll()
                .findAll { List match -> offered.contains(match[1]) && !results.any { it.toolName() == match[1] } } as List<List>
        if (softwareCalls) {
            List<List> called = question.contains("[[together]]") ? softwareCalls : [softwareCalls.first()]
            // Without text of its own, as Gemini and OpenAI call a tool.
            return toolCalls(null, called.collect { List call ->
                toolCall(call[1] as String, io.micronaut.serde.ObjectMapper.getDefault().readValue(call[2] as String, Map))
            })
        }
        List<String> exploredFiles = (text =~ /=== The file "([^"]+)" \(sent with the question, too large to go with it whole/)
                .collect { it[1] as String }
        if (offered.contains("search_file") && (question.contains("[[explore-forever]]") || question.contains("[[explore-silently]]"))) {
            return toolCalls(question.contains("[[explore-silently]]") ? null : "Ich suche weiter.",
                    [toolCall("search_file", [file_name: exploredFiles.first(), query: "ERROR"])])
        }
        if (offered.contains("run_shell") && question.contains("[[shell-forever]]")) {
            return toolCalls("Ich schaue weiter.", [toolCall("run_shell", [command: "ls data"])])
        }
        def search = question =~ /\[\[search ([^\]]+)]]/
        if (offered.contains("search_file") && search.find() && results.any { it.toolName() == "search_file" }
                && !results.any { it.toolName() == "read_file_lines" }) {
            def hit = results.find { it.toolName() == "search_file" }.text() =~ /(?m)^(\d+): /
            if (hit.find()) {
                int line = hit.group(1) as int
                return toolCalls("Ich lese den Zusammenhang.", [toolCall("read_file_lines",
                        [file_name: exploredFiles.first(), from_line: Math.max(1, line - 1), to_line: line + 1])])
            }
        }
        // "[[python <code>]]", "[[shell <command>]]", "[[view <path> <path> ...]]", "[[hand_out <path> [name]]]",
        // "[[hand_out_all <path> <path> ...]]" (several in one call): the
        // sandbox's tools, every one of them in one round, once.
        List<ToolExecutionRequest> codeCalls = []
        if (offered.contains("run_python") && !results.any { it.toolName() == "run_python" }) {
            (question =~ /\[\[python ([^\]]+)]]/).each { codeCalls << toolCall("run_python", [code: (it as List)[1]]) }
        }
        if (offered.contains("run_shell") && !results.any { it.toolName() == "run_shell" }) {
            (question =~ /\[\[shell ([^\]]+)]]/).each { codeCalls << toolCall("run_shell", [command: (it as List)[1]]) }
        }
        if (offered.contains("view_images") && !results.any { it.toolName() == "view_images" }) {
            (question =~ /\[\[view ([^\]]+)]]/).each {
                codeCalls << toolCall("view_images", [paths: ((it as List)[1] as String).split(" ").toList()])
            }
        }
        if (offered.contains("hand_out_file") && !results.any { it.toolName() == "hand_out_file" }) {
            (question =~ /\[\[hand_out (\S+)(?: ([^\]]+))?]]/).each {
                def call = it as List
                codeCalls << toolCall("hand_out_file", call[2] ? [paths: [call[1]], file_name: call[2]] : [paths: [call[1]]])
            }
            (question =~ /\[\[hand_out_all ([^\]]+)]]/).each {
                codeCalls << toolCall("hand_out_file", [paths: ((it as List)[1] as String).split(" ").toList()])
            }
        }
        if (codeCalls) {
            return toolCalls("Ich rechne nach.", codeCalls)
        }
        // "[[web-first <query>]]": the web before the documents - as a model ignoring the rule would (the tool refuses).
        def webFirst = question =~ /\[\[web-first ([^\]]+)]]/
        if (offered.contains("web_search") && webFirst.find() && !results.any { it.toolName() == "web_search" }) {
            return toolCalls("Ich suche im Web.", [toolCall("web_search", [query: webFirst.group(1)])])
        }
        if (results.isEmpty()) {
            List<ToolExecutionRequest> calls = []
            if (offered.contains("search_file") && search.find(0)) {
                exploredFiles.each { calls << toolCall("search_file", [file_name: it, query: search.group(1)]) }
            }
            if (offered.contains("similarity_search")) {
                calls << toolCall("similarity_search", [query: question])
            }
            if (offered.contains("read_file")) {
                (text =~ /- file_id (\d+): "([^"]+)" \(([^)]*)\)/).each { match ->
                    String type = match[3] as String
                    Map arguments = [file_id: match[1] as long]
                    // Media and original PDFs: the mode left out, the application picks the form.
                    if (!type.startsWith("audio/") && !type.startsWith("video/") && !(type.startsWith("application/pdf") && question.contains("[[original]]"))) {
                        arguments.mode = type.contains("scanned") ? "PAGES" : "TEXT"
                    }
                    calls << toolCall("read_file", arguments)
                }
            }
            if (calls) {
                return toolCalls("Ich schaue nach.", calls)
            }
        }
        // "[[read-document]]" / "[[read-document <offset>]]": reads on in the first document the search found, once.
        def readDocument = question =~ /\[\[read-document(?: (\d+))?]]/
        String searched = results.find { it.toolName() == "similarity_search" }?.text()
        if (offered.contains("read_document") && searched && readDocument.find()
                && !results.any { it.toolName() == "read_document" }) {
            def from = searched =~ /--- From "([^"]+)"/
            if (from.find()) {
                Map arguments = [document: from.group(1)]
                if (readDocument.group(1)) {
                    arguments.offset = readDocument.group(1) as int
                }
                return toolCalls("Ich lese weiter.", [toolCall("read_document", arguments)])
            }
        }
        // "[[web <query>]]": one web search a round, once per marker.
        List<String> webQueries = (question =~ /\[\[web ([^\]]+)]]/).collect { it[1] as String }
        int searchedWeb = results.count { it.toolName() == "web_search" } as int
        if (offered.contains("web_search") && searchedWeb < webQueries.size()) {
            return toolCalls("Ich suche im Web.", [toolCall("web_search", [query: webQueries[searchedWeb]])])
        }
        // "[[create_image_repeatedly]]": a new image in every round, four times - each tool result is passed on.
        if (offered.contains("create_image") && question.contains("[[create_image_repeatedly]]")) {
            List<String> imageResults = results.findAll { it.toolName() == "create_image" }*.text()
            if (imageResults.size() < 4) {
                return toolCalls("Ich erstelle das Bild.", [toolCall("create_image", [instruction: "Bild " + (imageResults.size() + 1)])])
            }
            return answer("Fertig: " + imageResults.join(" | "))
        }
        // "[[create_image]]": a new image; "[[create_image a.png b.jpg]]": of the conversation's files of these names,
        // "#<id>" a file_id as it is. The instruction is the question without the marker.
        def imageCall = question =~ /\[\[create_image((?: [^\s\]]+)*)]]/
        boolean imaged = results.any { it.toolName() == "create_image" }
        if (offered.contains("create_image") && imageCall.find() && !imaged) {
            Map<String, Long> ids = (text =~ /- file_id (\d+): "([^"]+)"/).collect { it }.collectEntries { [(it[2] as String): it[1] as long] }
            List<Long> fileIds = imageCall.group(1).trim().split(/\s+/).findAll().collect {
                it.startsWith("#") ? it.substring(1) as long : ids[it]
            }
            Map arguments = [instruction: question.replace(imageCall.group(0), "").trim()]
            if (fileIds) {
                arguments.file_ids = fileIds
            }
            return toolCalls("Ich erstelle das Bild.", [toolCall("create_image", arguments)])
        }
        if (imaged && !results.find { it.toolName() == "create_image" }.text().startsWith("Error: ")) {
            return answer("Fertig: " + results.find { it.toolName() == "create_image" }.text() + repeatedNote(messages, question))
        }
        def fileCall = question =~ /\[\[create_file (\S+) (\w+)]]/
        boolean created = results.any { it.toolName() == "create_file" }
        if (offered.contains("create_file") && fileCall.find() && !created) {
            List<String> read = results*.text().collectMany { (it =~ /=== File "([^"]+)" ===/).collect { m -> m[1] as String } }
            String format = fileCall.group(2)
            String content = format == "XLSX" ? "Datei;Zeichen\n" + read.collect { it + ";1" }.join("\n")
                    : "# Überarbeitet\n\nAus: " + read.join(", ") + "\n\n| A | B |\n|---|---|\n| 1 | 2 |"
            return toolCalls("Ich erstelle die Datei.",
                    [toolCall("create_file", [file_name: fileCall.group(1), format: format, content: content])])
        }
        if (created) {
            return answer("Fertig: " + results.find { it.toolName() == "create_file" }.text())
        }
        // "[[empty]]": no text at all, also when asked again; "[[empty-once]]": no text until the loop asks again;
        // "[[empty-length]]": cut off by the output limit before the first word - a thinking model that spent its limit
        // on reasoning; "[[empty-once-loop]]": the stream cancelled for reasoning in a loop (StreamStop, finish reason
        // OTHER), until the loop asks again. With "[[think <text>]]" the response carries that reasoning.
        boolean emptyOnce = (question.contains("[[empty-once]]") || question.contains("[[empty-once-loop]]")) && !askedAgain(messages)
        if (question.contains("[[empty-length]]") || question.contains("[[empty]]") || emptyOnce) {
            def reasoning = question =~ /\[\[think ([^\]]+)]]/
            def message = AiMessage.builder().text("").thinking(reasoning.find() ? reasoning.group(1) : null).build()
            def finish = question.contains("[[empty-length]]") ? FinishReason.LENGTH
                    : question.contains("[[empty-once-loop]]") ? FinishReason.OTHER : FinishReason.STOP
            // StreamStop completes a cancelled stream without the provider's token count
            def tokens = finish == FinishReason.OTHER ? null : new TokenUsage(1000, 4096)
            return ChatResponse.builder().aiMessage(message).tokenUsage(tokens).finishReason(finish).build()
        }
        int excerpts = results*.text().sum(0) { it.count("--- From ") } as int
        List<String> fileNames = results*.text().collectMany {
            (it =~ /(?:=== File|Transcript of) "([^"]+)"/).collect { m -> m[1] as String }
        }
        int images = messages.findAll { it instanceof UserMessage && isToolImages(it as UserMessage) }
                .sum(0) { (it as UserMessage).contents().count { c -> c instanceof ImageContent } } as int
        String reply = "Antwort auf: " + question + " (Auszüge: " + excerpts + ")"
        if (images > 0) {
            reply += " (Bilder: " + images + ")"
        }
        [PDFs: dev.langchain4j.data.message.PdfFileContent, Audio: dev.langchain4j.data.message.AudioContent].each { label, type ->
            int count = messages.findAll { it instanceof UserMessage && isToolImages(it as UserMessage) }
                    .sum(0) { (it as UserMessage).contents().count { c -> type.isInstance(c) } } as int
            if (count > 0) {
                reply += " (" + label + ": " + count + ")"
            }
        }
        String error = results*.text().find { it.startsWith("Error: ") }
        if (error) {
            reply += " (" + error + ")"
        }
        if (fileNames) {
            reply += " (Dateien: " + fileNames.join(", ") + ")"
        }
        List<String> explored = results.findAll { it.toolName() in ["search_file", "read_file_lines"] }*.text()
        if (explored) {
            reply += " (Gelesen: " + explored.join("; ") + ")"
        }
        List<String> read = results.findAll { it.toolName() == "read_document" }*.text()
        if (read) {
            reply += " (Dokument: " + read.join("; ") + ")"
        }
        List<String> code = results.findAll { it.toolName() in ["run_python", "run_shell", "view_images", "hand_out_file"] }*.text()
        if (code) {
            reply += " (Code: " + code.join("; ") + ")"
        }
        List<String> web = results.findAll { it.toolName() == "web_search" }*.text()
        if (web) {
            reply += " (Web: " + web.join("; ") + ")"
        }
        List<String> software = results.findAll { it.toolName().contains("__") }*.text()
        if (software) {
            reply += " (Software: " + software.join("; ") + ")"
        }
        answer(reply)
    }

    /**
     * "[[repeat-note]]": the answer ends with the note the history put on an earlier answer - as Gemini copied
     * "[Created with a tool: ...]" into its own answer (10 October 2026).
     */
    private static String repeatedNote(List<ChatMessage> messages, String question) {
        if (!question.contains("[[repeat-note]]")) {
            return ""
        }
        String note = messages.findAll { it instanceof AiMessage }.collect { (it as AiMessage).text() }
                .collectMany { it?.readLines() ?: [] }.find { it.startsWith("[Created with") }
        note ? "\n\n" + note : ""
    }

    /** Like a model naming a conversation - with the quotes and full stop models add, which the title loses. */
    private ChatResponse title(AiModel model, String question) {
        titles << question
        if (titleFails) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "provider.failed", model.provider().name() + " failed - please try again",
                    [name: model.provider().name()])
        }
        // No tokens: the usage specs count a title as a call of its own, their amounts stay the answers'.
        ChatResponse.builder().aiMessage(AiMessage.from("\"Thema: " + question.readLines().first().take(40) + "\"."))
                .tokenUsage(new TokenUsage(0, 0)).build()
    }

    /**
     * A task's agent or verify step, answered with its structured result: a check point whose instruction holds
     * "[[find TEXT]]" is a finding with every given line containing TEXT as evidence, else ok. "[[invent]]" names a line
     * that is in no file; "[[unclear-until N]]" keeps a verification unclear up to its round N.
     */
    private ChatResponse taskResult(List<ChatMessage> messages, boolean verify) {
        if (verify) {
            verifyHold?.await(20, TimeUnit.SECONDS)
        }
        if (brokenTaskAnswers > 0) {
            brokenTaskAnswers--
            return answer("Ich habe alles geprüft, es sieht gut aus.")
        }
        String question = (messages.find { it instanceof UserMessage } as UserMessage).singleText()
        int round = (question =~ /round (\d+)\./)[0][1] as int
        List<Map> lines = []
        String file = null
        question.eachLine { String line ->
            def header = line =~ /^=== File "([^"]+)"/
            if (header.find()) {
                file = header.group(1)
            } else if (file && line ==~ /\d+\| .*/) {
                lines << [file: file, line: line.takeWhile { it != '|' } as long, text: line.substring(line.indexOf('|') + 2)]
            } else if (line.startsWith("===")) {
                file = null
            }
        }
        List<Map> checks = []
        (question =~ /(?s)=== Check point "([^"]+)" \((error|warning|report)\) ===\n(.*?)(?=\n\n===|\z)/).each { List match ->
            String instruction = match[3]
            def find = instruction =~ /\[\[find ([^\]]+)]]/
            List<Map> evidence = find.find() ? lines.findAll { (it.text as String).contains(find.group(1)) }
                    .collect { [file: it.file, line: it.line] } : []
            if (instruction.contains("[[invent]]")) {
                evidence = [[file: lines ? lines.first().file : "keine.log", line: 999999]]
            }
            def until = instruction =~ /\[\[unclear-until (\d+)]]/
            String status = evidence ? "finding" : "ok"
            if (verify && until.find() && round <= (until.group(1) as int)) {
                status = "unclear"
            }
            checks << [name: match[1], status: status, note: "Geprüft in Runde " + round + ": " + evidence.size() + " Zeilen",
                       evidence: evidence]
        }
        answer("```json\n" + io.micronaut.serde.ObjectMapper.getDefault().writeValueAsString([checks: checks]) + "\n```")
    }

    /**
     * A task's prompt step: "Antwort von <step> (Runde n)", the names of the files it got and the first line of each
     * ("<name>: <line>"). "[[choose a,b,...]]" in its prompt picks the n-th word in round n (the last one after),
     * "[[choose-late w]]" picks it only when asked again, "[[choose-none]]" never picks.
     */
    private ChatResponse promptAnswer(List<ChatMessage> messages, List<String> offered) {
        String question = (messages.find { it instanceof UserMessage } as UserMessage).singleText()
        // An assistant prompt step (assistant: true): "[[call <tool> <json>]]" calls an offered tool once, its result
        // goes into the answer; "[[tools]]" lists the tools offered.
        List<ToolExecutionResultMessage> results = messages.findAll { it instanceof ToolExecutionResultMessage }
        def call = question =~ /\[\[call (\S+) (\{.*?\})]]/
        if (call.find() && offered.contains(call.group(1)) && !results.any { it.toolName() == call.group(1) }) {
            return toolCalls(null, [toolCall(call.group(1),
                    io.micronaut.serde.ObjectMapper.getDefault().readValue(call.group(2), Map))])
        }
        def header = question =~ /step "([^"]+)", round (\d+)\./
        header.find()
        String step = header.group(1)
        int round = header.group(2) as int
        List<String> files = []
        String file = null
        question.eachLine { String line ->
            def head = line =~ /^=== File "([^"]+)"/
            if (head.find()) {
                file = head.group(1)
            } else if (file && line ==~ /\d+\| .*/) {
                files << file + ": " + line.substring(line.indexOf('|') + 2)
                file = null
            }
        }
        String text = "Antwort von " + step + " (Runde " + round + ")" + (files ? "\n" + files.join("\n") : "")
        if (results) {
            text += "\n(Werkzeug: " + results*.text().join("; ") + ")"
        }
        if (question.contains("[[tools]]")) {
            text += "\n(Angeboten: " + offered.sort().join(", ") + ")"
        }
        if (question.contains("=== Why you are asked again")) {
            text += "\nZurueckgeschickt"
        }
        // The loop may add its note on the last round with tools after the question asked again.
        boolean askedAgain = messages.any {
            it instanceof UserMessage && (it as UserMessage).hasSingleText()
                    && (it as UserMessage).singleText().startsWith("Your answer did not end with a line CHOICE")
        }
        def choose = question =~ /\[\[choose ([a-z0-9_,-]+)]]/
        def late = question =~ /\[\[choose-late ([a-z0-9_-]+)]]/
        if (choose.find()) {
            List<String> words = choose.group(1).split(",") as List<String>
            text += "\nCHOICE: " + words[Math.min(round, words.size()) - 1]
        } else if (late.find() && askedAgain) {
            text += "\nCHOICE: " + late.group(1)
        }
        answer(text)
    }

    /**
     * "Draft with AI": "[[draft <name>]]" in the description writes a valid task of that name from the tenant's first file
     * store and chat model (as the system prompt lists them); "[[draft-wrong]]" makes the first round invalid,
     * "[[draft-never]]" every round; with a task to change, "[[draft-change <name>]]" renames it, its comments kept.
     */
    private ChatResponse taskDraft(List<ChatMessage> messages, String system) {
        String question = (messages.find { it instanceof UserMessage } as UserMessage).singleText()
        boolean corrected = messages.any { it instanceof UserMessage && (it as UserMessage).singleText().startsWith("The check found these mistakes") }
        def change = question =~ /\[\[draft-change ([^\]]+)]]/
        if (change.find()) {
            String source = (question =~ /(?s)```yaml\n(.*?)```/)[0][1] as String
            return answer("```yaml\n" + source.replaceFirst(/(?m)^name: .*$/, "name: " + change.group(1)) + "```")
        }
        String name = (question =~ /\[\[draft ([^\]]+)]]/).with { it.find() ? it.group(1) : "Entwurf" }
        String store = (system =~ /File stores: ([^(;\n]+) \(/).with { it.find() ? it.group(1).trim() : "Keine" }
        String model = (system =~ /Chat models: ([^;\n]+)/).with { it.find() ? it.group(1).trim() : "Keines" }
        boolean wrong = question.contains("[[draft-never]]") || (question.contains("[[draft-wrong]]") && !corrected)
        answer("Hier ist die Aufgabe:\n```yaml\nversion: 1\nname: " + name + "\nnotify: [it@example.com]\n"
                + (wrong ? "colour: blue\n" : "")
                + "steps:\n  - id: logs\n    type: read_files\n    store: " + store + "\n    pattern: \"*.log\"\n"
                + "  - id: summary\n    type: prompt\n    model: " + model + "\n    input: [logs]\n    prompt: Fasse zusammen.\n"
                + "  - id: mail\n    type: send_mail\n    input: [summary]\n```")
    }

    /** The asker's question: the last user message that is neither the tools' files nor the loop asking again. */
    private static String questionText(List<ChatMessage> messages) {
        UserMessage question = messages.findAll {
            it instanceof UserMessage && !isToolImages(it as UserMessage) && !isAskingAgain(it as UserMessage)
                    && !isLastToolRoundNote(it as UserMessage)
        }.last() as UserMessage
        (question.contents().find { it instanceof TextContent } as TextContent).text()
    }

    private static boolean isAskingAgain(UserMessage message) {
        message.hasSingleText() && message.singleText() == de.alpdesk.services.agent.AgentLoop.ASK_AGAIN
    }

    /** The agent loop's note before its last round with tools - no question either. */
    private static boolean isLastToolRoundNote(UserMessage message) {
        message.hasSingleText() && message.singleText() == de.alpdesk.services.agent.AgentLoop.LAST_TOOL_ROUND
    }

    /** Whether the agent loop asked again after an empty answer - its message is the last. */
    static boolean askedAgain(List<ChatMessage> messages) {
        messages.last() instanceof UserMessage && isAskingAgain(messages.last() as UserMessage)
    }

    static boolean isToolImages(UserMessage message) {
        def first = message.contents().first()
        first instanceof TextContent && (first as TextContent).text() == de.alpdesk.services.agent.AgentLoop.TOOL_FILES
    }

    private static ToolExecutionRequest toolCall(String name, Map arguments) {
        ToolExecutionRequest.builder().id(name + "-" + UUID.randomUUID()).name(name)
                .arguments(io.micronaut.serde.ObjectMapper.getDefault().writeValueAsString(arguments)).build()
    }

    /** With a thought signature, as Gemini 3 calls a tool - it wants the signature back with the call's result. */
    private static ChatResponse toolCalls(String text, List<ToolExecutionRequest> calls) {
        AiMessage message = AiMessage.builder().text(text).toolExecutionRequests(calls)
                .attributes([(THOUGHT_SIGNATURE): "fake-signature"]).build()
        ChatResponse.builder().aiMessage(message).tokenUsage(new TokenUsage(500, 100)).build()
    }

    /**
     * An extract step's answer: every field the question lists takes its value from a document line "<field>: <value>"
     * (none: null). In the document, "[[row-broken]]" makes the first answer no result, "[[row-broken-twice]]" both,
     * "[[row-wrong]]" gives every date field "yesterday" in the first answer, "[[unclear <why>]]" sets unclear,
     * "[[knowledge <query>]]" searches the knowledge first (similarity_search) when the step offers it.
     */
    private ChatResponse extractAnswer(List<ChatMessage> messages, List<String> offered) {
        extractHold?.await(20, TimeUnit.SECONDS)
        String question = (messages.find { it instanceof UserMessage } as UserMessage).singleText()
        def knowledge = question =~ /\[\[knowledge ([^\]]+)]]/
        if (knowledge.find() && offered.contains("similarity_search")
                && !messages.any { it instanceof ToolExecutionResultMessage }) {
            return toolCalls(null, [toolCall("similarity_search", [query: knowledge.group(1)])])
        }
        boolean again = messages.count { it instanceof UserMessage } > 1
        if (question.contains("[[row-broken-twice]]") || question.contains("[[row-broken]]") && !again) {
            return answer("Ich konnte das Dokument lesen.")
        }
        List<String> fields = []
        boolean inFields = false
        question.eachLine { String line ->
            if (line.startsWith("=== The fields ===")) {
                inFields = true
            } else if (line.startsWith("===")) {
                inFields = false
            } else if (inFields && line.startsWith("- ")) {
                fields << line.substring(2).takeWhile { it != ' ' }
            }
        }
        Map<String, String> found = [:]
        question.eachLine { String line ->
            def numbered = line =~ /^\d+\| ([a-z][a-z0-9_]*): (.*)$/
            if (numbered.find()) {
                found[numbered.group(1)] = numbered.group(2).strip()
            }
        }
        Map values = fields.collectEntries { [(it): found[it]] }
        if (question.contains("[[row-wrong]]") && !again) {
            values = values.collectEntries { k, v -> [(k): k.contains("date") ? "yesterday" : v] }
        }
        def unclear = question =~ /\[\[unclear ([^\]]+)]]/
        String valuesJson = values.collect { k, v -> quoted(k as String) + ": " + (v == null ? "null" : quoted(v as String)) }.join(", ")
        answer("{\"values\": {" + valuesJson + "}, \"unclear\": " + (unclear.find() ? quoted(unclear.group(1)) : "null") + "}")
    }

    private static String quoted(String text) {
        '"' + text.replace('\\', '\\\\').replace('"', '\\"') + '"'
    }

    private static ChatResponse answer(String text) {
        ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(new TokenUsage(1000, 200)).build()
    }

    /**
     * A PNG of 2 x 1 pixels per call, the instruction and the number of images it got in its text chunk - so a spec
     * reads what reached the image model from the stored file. Usage: 100 tokens per image sent plus 10, 1290 out.
     */
    @Override
    CreatedImage createImage(AiModel model, String apiKey, String instruction, List<Image> images) {
        failIfAsked(model)
        if (imageFails) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "provider.failed", model.provider().name() + " failed - please try again",
                    [name: model.provider().name()])
        }
        createdImages << [model: model.modelName(), apiKey: apiKey, instruction: instruction,
                          images: images.collect { Base64.decoder.decode(it.base64Data()) }]
        def usage = new TokenUsage(10 + 100 * images.size(), 1290)
        if (imageRefusal != null) {
            return new CreatedImage(null, null, imageRefusal, usage)
        }
        new CreatedImage(png("${instruction} | images: ${images.size()}"), "image/png", "", usage)
    }

    /** A 2 x 1 PNG with the text as its "Description". */
    static byte[] png(String description) {
        def image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB)
        def writer = ImageIO.getImageWritersByFormatName("png").next()
        def metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null)
        def text = new IIOMetadataNode("tEXtEntry")
        text.setAttribute("keyword", "Description")
        text.setAttribute("value", description)
        def chunk = new IIOMetadataNode("tEXt")
        chunk.appendChild(text)
        def root = new IIOMetadataNode("javax_imageio_png_1.0")
        root.appendChild(chunk)
        metadata.mergeTree("javax_imageio_png_1.0", root)
        def bytes = new ByteArrayOutputStream()
        def output = ImageIO.createImageOutputStream(bytes)
        writer.output = output
        writer.write(new IIOImage(image, null, metadata))
        output.close()
        bytes.toByteArray()
    }

    /** The "Description" a fake image carries - what reached the image model. */
    static String description(byte[] png) {
        def reader = ImageIO.getImageReadersByFormatName("png").next()
        reader.input = ImageIO.createImageInputStream(new ByteArrayInputStream(png))
        def root = reader.getImageMetadata(0).getAsTree("javax_imageio_png_1.0") as IIOMetadataNode
        def entries = root.getElementsByTagName("tEXtEntry")
        (0..<entries.length).collect { entries.item(it) as IIOMetadataNode }
                .find { it.getAttribute("keyword") == "Description" }?.getAttribute("value")
    }

    @Override
    Response<List<Embedding>> embed(AiModel model, String apiKey, List<TextSegment> segments) {
        failIfAsked(model)
        if (embeddingFails) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "provider.failed", model.provider().name() + " failed - please try again",
                    [name: model.provider().name()])
        }
        if (embeddingDelay > 0) {
            Thread.sleep(embeddingDelay)
        }
        embeddings << [model: model.modelName(), apiKey: apiKey, segments: segments.size(), texts: segments*.text()]
        List<Embedding> vectors = segments.collect { vector(it.text(), model.dimension()) }
        Response.from(vectors, reportEmbeddingTokens ? new TokenUsage(segments.size() * 10) : null)
    }

    @Override
    List<Double> rerank(AiModel model, String apiKey, String query, List<String> texts) {
        if (rerankFails) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "provider.unreachable",
                    model.displayName() + " is not reachable - please try again later", [name: model.displayName()])
        }
        reranks << [model: model.modelName(), apiKey: apiKey, query: query, texts: texts]
        texts.withIndex().collect { String text, int i -> text.contains(RERANK_FAVOURITE) ? 1.0d : 0.5d / (i + 1) }
    }

    /**
     * Like Gemini and Claude: an earlier answer without text and without tool calls is refused ("contents.parts must
     * not be empty", an empty content) - 400 there, 502 for us.
     */
    private static void refuseBlankAnswers(AiModel model, List<ChatMessage> messages) {
        boolean blank = messages.any {
            it instanceof dev.langchain4j.data.message.AiMessage && !it.hasToolExecutionRequests() && !it.text()?.trim()
        }
        if (blank) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "fake.blankAnswerInHistory",
                    model.provider().name() + " refused an empty answer in the history")
        }
    }

    /**
     * Like Gemini 3: a tool's result is refused unless the call that asked for it comes back as the model sent it, with
     * its thought signature (400 there, 502 for us).
     */
    private static void refuseUnsignedToolResults(AiModel model, List<ChatMessage> messages) {
        boolean unsigned = messages.findAll { it instanceof ToolExecutionResultMessage }.any { result ->
            def call = messages.find {
                it instanceof AiMessage && it.hasToolExecutionRequests()
                        && it.toolExecutionRequests().any { request -> request.id() == (result as ToolExecutionResultMessage).id() }
            } as AiMessage
            call == null || !call.attributes().containsKey(THOUGHT_SIGNATURE)
        }
        if (unsigned) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "fake.missingThoughtSignature",
                    model.provider().name() + " refused a tool result without the thought signature of its call")
        }
    }

    /** Like the providers: a tool name of other characters or longer than 64 is refused (400 there, 502 for us). */
    private static void refuseInvalidToolNames(AiModel model, List<ToolSpecification> tools) {
        def invalid = tools*.name().find { !(it ==~ /[a-zA-Z0-9_-]{1,64}/) }
        if (invalid) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "fake.invalidToolName", model.provider().name() + " refused the tool name " + invalid)
        }
    }

    private void failIfAsked(AiModel model) {
        if (failures > 0) {
            failures--
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "provider.keyRefused",
                    model.provider().name() + " refused the API key - check it under API-Keys", [name: model.provider().name()])
        }
    }

    private static Embedding vector(String text, int dimension) {
        float[] values = new float[dimension]
        text.toLowerCase().split(/[^\p{L}\p{N}]+/).findAll { it.length() > 2 }.each { word ->
            values[Math.floorMod(word.hashCode(), dimension)] += 1f
        }
        double length = Math.sqrt(values.collect { it * it }.sum() as double)
        if (length == 0) {
            values[0] = 1f
            length = 1
        }
        Embedding.from(values.collect { (float) (it / length) } as float[])
    }
}
