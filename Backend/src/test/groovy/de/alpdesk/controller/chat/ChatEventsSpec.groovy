package de.alpdesk.controller.chat

import io.micronaut.http.sse.Event
import reactor.core.publisher.Flux
import spock.lang.Specification

import java.time.Duration

/**
 * A streamed answer gets a keep-alive comment while nothing else is sent: a tool may run for minutes, and an idle
 * timeout (the server's 5 minutes, a reverse proxy's) closed a quiet stream while the answer went on.
 */
class ChatEventsSpec extends Specification {

    def "a quiet answer gets keep-alive comments until it ends - then none follows"() {
        given: "an answer that sends its result after 300 ms"
        Flux<Event<Object>> answer = Flux.<Event<Object>> create { sink ->
            Thread.start {
                sleep(300)
                sink.next(Event.<Object> of("fertig").name("done"))
                sink.complete()
            }
        }

        when:
        def events = ChatEvents.withKeepAlive(answer, Duration.ofMillis(50)).collectList().block(Duration.ofSeconds(5))

        then: "comments without data or name in between, the answer's event last"
        events.size() > 2
        events.last().name == "done"
        events[0..-2].every { it.comment == "keep-alive" && it.data == "" && it.name == null }
    }

    def "an answer subscribed once - the keep-alive does not start the answer a second time"() {
        given:
        int started = 0
        Flux<Event<Object>> answer = Flux.<Event<Object>> create { sink ->
            started++
            sink.next(Event.<Object> of("fertig").name("done"))
            sink.complete()
        }

        when:
        ChatEvents.withKeepAlive(answer, Duration.ofMillis(50)).collectList().block(Duration.ofSeconds(5))

        then:
        started == 1
    }
}
