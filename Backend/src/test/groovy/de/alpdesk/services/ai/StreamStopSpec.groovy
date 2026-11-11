package de.alpdesk.services.ai

import de.alpdesk.entities.ai.AiModel
import de.alpdesk.entities.ai.AiProvider
import de.alpdesk.entities.ai.ModelKind
import de.alpdesk.services.common.ServiceException
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.PartialResponse
import dev.langchain4j.model.chat.response.PartialResponseContext
import dev.langchain4j.model.chat.response.PartialThinking
import dev.langchain4j.model.chat.response.PartialThinkingContext
import dev.langchain4j.model.chat.response.PartialToolCall
import dev.langchain4j.model.chat.response.PartialToolCallContext
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.chat.response.StreamingHandle
import dev.langchain4j.model.output.FinishReason
import spock.lang.Specification

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledExecutorService

/**
 * A streamed answer is ended once, whatever the provider does afterwards: one that runs away in blanks (Gemini padding
 * a table) completes with the text so far, one the provider stops sending fails after the limit, a normal one passes
 * untouched - however long it takes, as long as it keeps sending. Reasoning repeating itself word for word ends the
 * round without text; an answer repeating a block is left as it is.
 */
class StreamStopSpec extends Specification {

    /** Generous against a GC pause between two parts: the parts come every tenth of it. */
    static final Duration LIMIT = Duration.ofSeconds(1)

    ScheduledExecutorService watchdog = AiModels.watchdog()
    List<String> parts = new CopyOnWriteArrayList<>()
    List<String> thoughts = new CopyOnWriteArrayList<>()
    List<ChatResponse> completed = new CopyOnWriteArrayList<>()
    List<Throwable> errors = new CopyOnWriteArrayList<>()
    volatile int cancels = 0

    StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
        void onPartialResponse(String part) { parts << part }

        void onPartialThinking(PartialThinking thinking) { thoughts << thinking.text() }

        void onCompleteResponse(ChatResponse response) { completed << response }

        void onError(Throwable error) { errors << error }
    }

    StreamingHandle stream = new StreamingHandle() {
        void cancel() { cancels++ }

        boolean isCancelled() { cancels > 0 }
    }

    def cleanup() {
        watchdog.shutdownNow()
    }

    def "an answer with blanks in a row past the limit is cancelled at the provider and completes without them"() {
        given:
        def stop = stop()

        when:
        send(stop, "| Abschnitt ", " " * 150, " " * 100, " " * 500, "| weiter")
        stop.onCompleteResponse(answer("spät"))

        then:
        cancels == 1
        completed.size() == 1
        completed[0].aiMessage().text() == "| Abschnitt"
        completed[0].finishReason() == FinishReason.LENGTH
        parts == ["| Abschnitt ", " " * 150]
        errors.isEmpty()
    }

    def "reasoning repeating a block word for word is cancelled at the provider - the round ends without text"() {
        given: "a model planning its answer and never writing it: two paragraphs, again and again"
        def stop = stop()
        def cycle = "Okay.\nLet's write.\nThe answer will be in German, with the class name `KM4`.\n\n" +
                "Okay.\nLet's go.\nAnswer: the KM-4 for up to four boilers, the leading boiler first.\n" +
                "One detail: the manual names busAddress as required.\nI'll focus on it.\n\n"

        when:
        think(stop, "Ich suche nach dem Kaskadenmodul.\n")
        think(stop, (cycle * 8).split("(?<=\n)"))
        think(stop, "Okay.\n")
        stop.onCompleteResponse(answer("spät"))

        then:
        cancels == 1
        completed.size() == 1
        completed[0].aiMessage().text() == ""
        completed[0].finishReason() == FinishReason.OTHER
        errors.isEmpty()

        and: "the reasoning was passed on up to the fifth copy, not further"
        thoughts.join("") == "Ich suche nach dem Kaskadenmodul.\n" + cycle * 5
    }

    def "reasoning that varies passes untouched, however long"() {
        given:
        def stop = stop()

        when:
        40.times { think(stop, "Versuch ${it}: Ich suche nach dem Kaskadenmodul KM-4 und seiner Busadresse in den Handbüchern.\n") }
        send(stop, "Antwort")
        stop.onCompleteResponse(answer("Antwort"))

        then:
        cancels == 0
        thoughts.size() == 40
        completed[0].aiMessage().text() == "Antwort"
    }

    def "an answer repeating a block is never cut - it may do so on purpose (the same configuration for several devices)"() {
        given:
        def stop = stop()
        def channel = """<object name="kessel" className="BoilerChannel">
  <param name="deviceAddress" value="1" />
  <param name="maxFlowTemperature" value="75.0" />
</object>
"""

        when:
        send(stop, (channel * 10).split("(?<=\n)"))
        stop.onCompleteResponse(answer(channel * 10))

        then:
        cancels == 0
        parts.join("") == channel * 10
        completed[0].aiMessage().text() == channel * 10
    }

    def "a stream silent past the limit is cancelled and fails - what comes late is dropped"() {
        given:
        def stop = stop()
        send(stop, "Hallo ")

        when:
        sleep(LIMIT.toMillis() * 2)
        send(stop, "Welt")
        stop.onCompleteResponse(answer("Hallo Welt"))

        then:
        cancels == 1
        parts == ["Hallo "]
        completed.isEmpty()
        errors.size() == 1
        (errors[0] as ServiceException).code() == "provider.stalled"
    }

    def "a stream that keeps sending passes untouched, however long it takes - a tool call streaming counts too"() {
        given:
        def stop = stop()
        def done = answer("Hallo Welt")

        when: "parts and a long tool call over twice the limit, never silent for long"
        12.times {
            sleep(LIMIT.toMillis() / 10 as long)
            send(stop, "Teil ")
        }
        10.times {
            sleep(LIMIT.toMillis() / 10 as long)
            stop.onPartialToolCall(PartialToolCall.builder().index(0).id("call").name("create_file")
                    .partialArguments("{").build(), new PartialToolCallContext(stream))
        }
        stop.onCompleteResponse(done)
        sleep(LIMIT.toMillis() * 2)

        then:
        cancels == 0
        parts.size() == 12
        completed == [done]
        errors.isEmpty()
    }

    def "an answer ends once - a late error or completion changes nothing"() {
        given:
        def stop = stop()

        when:
        stop.onCompleteResponse(answer("Hallo"))
        stop.onError(new RuntimeException("late"))
        stop.onCompleteResponse(answer("later"))
        sleep(LIMIT.toMillis() * 2)

        then:
        completed.size() == 1
        errors.isEmpty()
        cancels == 0
    }

    def "an ended answer leaves nothing in the watchdog - a cancelled check kept until due would hold the answer"() {
        given:
        def stop = stop()

        when:
        stop.onCompleteResponse(answer("Antwort"))

        then:
        (watchdog as ScheduledThreadPoolExecutor).queue.isEmpty()
    }

    def "a provider's failure becomes a ServiceException naming the model"() {
        given:
        def stop = stop()

        when:
        stop.onError(new RuntimeException("boom"))

        then:
        errors.size() == 1
        (errors[0] as ServiceException).code() == "provider.failed"
        errors[0].message.contains("M")
    }

    private AiModels.StreamStop stop() {
        new AiModels.StreamStop(model(), handler, LIMIT, watchdog)
    }

    private void send(AiModels.StreamStop stop, String... texts) {
        texts.each { stop.onPartialResponse(new PartialResponse(it), new PartialResponseContext(stream)) }
    }

    private void think(AiModels.StreamStop stop, String... texts) {
        texts.each { stop.onPartialThinking(new PartialThinking(it), new PartialThinkingContext(stream)) }
    }

    private static ChatResponse answer(String text) {
        ChatResponse.builder().aiMessage(AiMessage.from(text)).build()
    }

    private static AiModel model() {
        new AiModel(1L, AiProvider.GEMINI, ModelKind.CHAT, "m", "M", null, BigDecimal.ONE, BigDecimal.ONE, true, false,
                false, false, false, true, true, true, null, null, null, null, null, null, null, null, null, null, null, null, Instant.now(),
                Instant.now())
    }
}
