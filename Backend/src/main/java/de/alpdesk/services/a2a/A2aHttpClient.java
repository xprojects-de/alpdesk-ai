package de.alpdesk.services.a2a;

import de.alpdesk.services.mcp.McpAddresses;
import io.micronaut.core.annotation.Nullable;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpHeaders;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.BoundedLineAccumulator;
import org.a2aproject.sdk.client.http.SSEParserConfig;
import org.a2aproject.sdk.client.http.ServerSentEvent;
import org.a2aproject.sdk.client.http.ServerSentEventParser;
import org.a2aproject.sdk.common.A2AErrorMessages;
import org.a2aproject.sdk.spec.A2AClientHTTPError;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The HTTP layer of the A2A SDK's client for one call to one connection (docs/architecture/a2a.md "The client"): the
 * SDK builds and reads the JSON-RPC, this adapter carries it - under the rules of a connected software's address
 * ({@link McpAddresses}: checked before every request, no redirect ever), with {@code app.ai.timeout} (a blocking
 * call: its caller's wait) as request timeout, an answer of at most {@value #MAX_ANSWER_BYTES} bytes, and the
 * connection's key only to the connection's own host. A request elsewhere with a key is refused before it leaves ({@link KeyLeak}) -
 * a card naming another host must not collect the key. The SDK's own JDK client has no timeouts and no address rules.
 * <p>
 * One instance per call: {@link #cancel()} ends every stream it opened (a stop of the user, a logout).
 */
public class A2aHttpClient implements A2AHttpClient {

    /** An answer (or one event of a stream) at most: the files an agent returns may have 8 MB together, base64. */
    public static final int MAX_ANSWER_BYTES = 12 * 1024 * 1024;
    private static final SSEParserConfig SSE = new SSEParserConfig(MAX_ANSWER_BYTES, 10_000, MAX_ANSWER_BYTES);

    private final HttpClient http;
    private final McpAddresses addresses;
    private final String connectionName;
    private final String connectionHost;
    private final @Nullable String key;
    private final Duration timeout;
    private final Set<CompletableFuture<?>> streams = ConcurrentHashMap.newKeySet();

    A2aHttpClient(HttpClient http, McpAddresses addresses, String connectionName, String connectionUrl,
                  @Nullable String key, Duration timeout) {
        this.http = http;
        this.addresses = addresses;
        this.connectionName = connectionName;
        this.connectionHost = URI.create(connectionUrl).getHost();
        this.key = key;
        this.timeout = timeout;
    }

    /** A request with the key to another host than the connection's - refused before it leaves. */
    public static final class KeyLeak extends IllegalStateException {
        KeyLeak(String connectionName) {
            super("The key of " + connectionName + " stays with its host");
        }
    }

    /** Ends every stream this call opened; their consumers hear nothing more. */
    public void cancel() {
        streams.forEach(stream -> stream.cancel(true));
    }

    @Override
    public GetBuilder createGet() {
        return new Get();
    }

    @Override
    public PostBuilder createPost() {
        return new Post();
    }

    @Override
    public DeleteBuilder createDelete() {
        return new Delete();
    }

    private abstract class Request<T extends Builder<T>> implements Builder<T> {

        private String url = "";
        private final Map<String, String> headers = new HashMap<>();

        @Override
        public T url(String url) {
            this.url = url;
            return self();
        }

        @Override
        public T addHeaders(Map<String, String> headers) {
            if (headers != null) {
                headers.forEach(this::addHeader);
            }
            return self();
        }

        @Override
        public T addHeader(String name, String value) {
            headers.put(name, value);
            return self();
        }

        @SuppressWarnings("unchecked")
        private T self() {
            return (T) this;
        }

        /** The request as it may leave: an allowed address, the key only to the connection's host. */
        HttpRequest.Builder request() {
            URI uri = URI.create(url);
            if (key != null && !connectionHost.equalsIgnoreCase(uri.getHost())) {
                throw new KeyLeak(connectionName);
            }
            addresses.requireAllowed(url, connectionName);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
            headers.forEach((name, value) -> {
                if (!"authorization".equalsIgnoreCase(name)) {
                    builder.header(name, value);
                }
            });
            if (key != null) {
                builder.header("Authorization", "Bearer " + key);
            }
            return builder;
        }

        /**
         * The deadline covers the body too: a request's own timeout ends with the headers, and an agent sending its
         * answer byte by byte would hold the thread - a task's call holds one of the task executor's two.
         */
        A2AHttpResponse send(HttpRequest request) throws IOException, InterruptedException {
            CompletableFuture<HttpResponse<byte[]>> answer = http.sendAsync(request,
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_ANSWER_BYTES));
            HttpResponse<byte[]> response;
            try {
                response = answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                answer.cancel(true);
                throw new HttpTimeoutException(connectionName + " did not answer in time");
            } catch (InterruptedException e) {
                answer.cancel(true);
                throw e;
            } catch (ExecutionException e) {
                throw failure(e.getCause());
            }
            refuseUnauthorized(response.statusCode(), response.headers().map());
            return new Answer(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8),
                    A2AHttpHeaders.of(response.headers().map()));
        }

        private IOException failure(Throwable cause) {
            if (cause instanceof IOException io && String.valueOf(io.getMessage()).contains("body exceeds capacity")) {
                return new IOException(connectionName + " sent an answer larger than "
                        + MAX_ANSWER_BYTES / (1024 * 1024) + " MB");
            }
            return cause instanceof IOException io ? io : new IOException(cause);
        }

        CompletableFuture<Void> stream(HttpRequest request, Consumer<ServerSentEvent> events,
                                       Consumer<Throwable> errors, Runnable complete) {
            Lines lines = new Lines(events, errors, complete);
            CompletableFuture<HttpResponse<Void>> exchange = http.sendAsync(request, info -> {
                lines.start(info);
                return HttpResponse.BodySubscribers.fromSubscriber(lines);
            });
            CompletableFuture<Void> stream = new CompletableFuture<>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    lines.cancel();
                    exchange.cancel(true);
                    return super.cancel(mayInterruptIfRunning);
                }
            };
            streams.add(stream);
            exchange.whenComplete((response, failure) -> {
                streams.remove(stream);
                if (failure != null) {
                    lines.fail(failure);
                }
                stream.complete(null);
            });
            return stream;
        }
    }

    private class Get extends Request<GetBuilder> implements GetBuilder {

        @Override
        public A2AHttpResponse get() throws IOException, InterruptedException {
            return send(request().GET().build());
        }

        @Override
        public CompletableFuture<Void> getAsyncSSE(Consumer<ServerSentEvent> events, Consumer<Throwable> errors,
                                                   Runnable complete) {
            return stream(request().header(ACCEPT, EVENT_STREAM).GET().build(), events, errors, complete);
        }
    }

    private class Post extends Request<PostBuilder> implements PostBuilder {

        private String body = "";

        @Override
        public PostBuilder body(String body) {
            this.body = body;
            return this;
        }

        @Override
        public A2AHttpResponse post() throws IOException, InterruptedException {
            return send(request().POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
        }

        @Override
        public CompletableFuture<Void> postAsyncSSE(Consumer<ServerSentEvent> events, Consumer<Throwable> errors,
                                                    Runnable complete) {
            return stream(request().header(ACCEPT, EVENT_STREAM)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), events, errors, complete);
        }
    }

    private class Delete extends Request<DeleteBuilder> implements DeleteBuilder {

        @Override
        public A2AHttpResponse delete() throws IOException, InterruptedException {
            return send(request().DELETE().build());
        }
    }

    /** The SDK's transports expect a refused key as this exception (as its JDK client throws it). */
    private static void refuseUnauthorized(int status, Map<String, List<String>> headers) throws IOException {
        if (status == 401 || status == 403) {
            String message = status == 401 ? A2AErrorMessages.AUTHENTICATION_FAILED : A2AErrorMessages.AUTHORIZATION_FAILED;
            throw new IOException(message, new A2AClientHTTPError(status, message, null, headers));
        }
    }

    private record Answer(int status, String body, A2AHttpHeaders headers) implements A2AHttpResponse {

        @Override
        public boolean success() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * A streamed answer cut into lines - each at most {@value #MAX_ANSWER_BYTES} bytes, checked while it arrives - for
     * the SDK's event parser. An answer that is no event stream (a JSON-RPC error sent at once) is handed on as one
     * event, as the SDK's own client does.
     */
    private static final class Lines implements Flow.Subscriber<List<ByteBuffer>> {

        private final Consumer<ServerSentEvent> events;
        private final Consumer<Throwable> errors;
        private final Runnable complete;
        private final ServerSentEventParser parser;
        private final BoundedLineAccumulator line = new BoundedLineAccumulator(MAX_ANSWER_BYTES);
        private volatile boolean eventStream;
        private volatile boolean ended;
        private volatile @Nullable Throwable refusal;
        private volatile Flow.@Nullable Subscription subscription;

        Lines(Consumer<ServerSentEvent> events, Consumer<Throwable> errors, Runnable complete) {
            this.events = events;
            this.errors = errors;
            this.complete = complete;
            this.parser = new ServerSentEventParser(events, errors, SSE);
        }

        void start(HttpResponse.ResponseInfo info) {
            try {
                refuseUnauthorized(info.statusCode(), info.headers().map());
            } catch (IOException e) {
                refusal = e;
            }
            eventStream = info.statusCode() / 100 == 2
                    && info.headers().firstValue("Content-Type").orElse("").contains(EVENT_STREAM);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (refusal != null) {
                subscription.cancel();
                fail(refusal);
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                while (buffer.hasRemaining() && !ended) {
                    byte b = buffer.get();
                    if (b == '\n' && eventStream) {
                        endLine();
                    } else if (b != '\r' || !eventStream) {
                        line.addByte(b);
                    }
                }
            }
            if (line.isTooLong() && !ended) {
                cancel();
                fail(new IOException("An event of the stream is larger than " + MAX_ANSWER_BYTES / (1024 * 1024) + " MB"));
            }
        }

        private void endLine() {
            if (line.isTooLong()) {
                return;
            }
            parser.processLine(line.toLine());
            line.reset();
        }

        @Override
        public void onError(Throwable failure) {
            fail(failure);
        }

        @Override
        public void onComplete() {
            if (ended) {
                return;
            }
            ended = true;
            if (eventStream) {
                if (line.size() > 0) {
                    parser.processLine(line.toLine());
                }
                parser.flush();
            } else if (line.size() > 0) {
                events.accept(new ServerSentEvent(line.toLine()));
            }
            complete.run();
        }

        void fail(Throwable failure) {
            if (!ended) {
                ended = true;
                errors.accept(failure);
            }
        }

        void cancel() {
            ended = true;
            Flow.Subscription current = subscription;
            if (current != null) {
                current.cancel();
            }
        }
    }
}
