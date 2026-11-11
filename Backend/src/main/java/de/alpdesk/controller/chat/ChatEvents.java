package de.alpdesk.controller.chat;

import de.alpdesk.dto.chat.ChatResponse;
import de.alpdesk.dto.chat.ChatStreamPart;
import de.alpdesk.dto.chat.ChatStreamSources;
import de.alpdesk.dto.chat.ChatStreamTool;
import de.alpdesk.dto.common.ErrorResponse;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tools.ToolStep;
import io.micronaut.http.sse.Event;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * The events of a streamed answer, the same for the users' chat and the client software (/api/v1/chat/stream):
 * "thinking" and "delta" pieces, the "sources" found so far, each "tool" call before it runs, then "done" with the
 * answer or "error" - which both end the stream. In between, while nothing else is sent, a comment (": keep-alive")
 * every KEEP_ALIVE: a tool may run for minutes (code in the sandbox: up to 300 s), and the server's idle timeout (5
 * minutes) - or a reverse proxy's - closed a quiet stream while the answer went on (seen 18 October 2026). A comment
 * carries no event: browsers and SSE clients skip it.
 */
public final class ChatEvents {

    static final Duration KEEP_ALIVE = Duration.ofSeconds(20);

    private ChatEvents() {
    }

    /** A streamed answer's events with a keep-alive comment every KEEP_ALIVE until they end. */
    public static Flux<Event<Object>> stream(Consumer<FluxSink<Event<Object>>> answer) {
        return withKeepAlive(Flux.create(answer), KEEP_ALIVE);
    }

    static Flux<Event<Object>> withKeepAlive(Flux<Event<Object>> events, Duration every) {
        return events.publish(shared -> Flux.merge(shared,
                Flux.interval(every).map(tick -> keepAlive()).takeUntilOther(shared.then())));
    }

    /** No data, no name: written as the comment line alone. */
    private static Event<Object> keepAlive() {
        return Event.<Object>of("").comment("keep-alive");
    }

    public static void delta(FluxSink<Event<Object>> sink, String part) {
        sink.next(Event.<Object>of(new ChatStreamPart(part)).name("delta"));
    }

    public static void thinking(FluxSink<Event<Object>> sink, String part) {
        sink.next(Event.<Object>of(new ChatStreamPart(part)).name("thinking"));
    }

    public static void sources(FluxSink<Event<Object>> sink, List<String> sources) {
        sink.next(Event.<Object>of(new ChatStreamSources(sources)).name("sources"));
    }

    public static void tool(FluxSink<Event<Object>> sink, ToolStep step) {
        sink.next(Event.<Object>of(ChatStreamTool.from(step)).name("tool"));
    }

    public static void done(FluxSink<Event<Object>> sink, ChatResponse response) {
        sink.next(Event.<Object>of(response).name("done"));
        sink.complete();
    }

    public static void error(FluxSink<Event<Object>> sink, ServiceException failure) {
        sink.next(Event.<Object>of(new ErrorResponse(failure.getMessage(), failure.code(), failure.params())).name("error"));
        sink.complete();
    }
}
