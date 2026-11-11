package de.alpdesk.controller.a2a;

import com.google.protobuf.MessageOrBuilder;
import de.alpdesk.security.RequestOrigin;
import de.alpdesk.security.Roles;
import de.alpdesk.services.a2a.A2aAgentCore;
import de.alpdesk.services.a2a.A2aCards;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;
import org.a2aproject.sdk.common.A2AHeaders;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.jsonrpc.common.json.IdJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.InvalidParamsJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.json.MethodNotFoundJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2ARequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.NonStreamingJSONRPCRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.AuthenticatedUser;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.server.extensions.A2AExtensions;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.a2aproject.sdk.spec.VersionNotSupportedError;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

import static org.a2aproject.sdk.server.ServerCallContext.TRANSPORT_KEY;
import static org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys.HEADERS_KEY;
import static org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys.METHOD_NAME_KEY;
import static org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys.TENANT_KEY;

/**
 * The tenant's assistant as an A2A agent at /a2a (docs/architecture/a2a.md "Point 52"): the JSON-RPC binding of A2A 1.0
 * for Micronaut - what the SDK's Quarkus reference (A2AServerRoutes) does: the SDK parses the request and writes the
 * answer (its protobuf mapping), the SDK's handler runs it. An MCP key that opens /a2a is the caller; the key's tenant
 * and the key go into the SDK's call context. The extended card is the key's, answered here.
 */
@Hidden
@Controller("/a2a")
@Secured(Roles.MCP)
@ExecuteOn(TaskExecutors.BLOCKING)
public class A2aController {

    private static final Logger LOG = LoggerFactory.getLogger(A2aController.class);

    private final A2aAgentCore core;
    private final A2aCards cards;

    public A2aController(A2aAgentCore core, A2aCards cards) {
        this.core = core;
        this.cards = cards;
    }

    @Post(consumes = MediaType.APPLICATION_JSON, produces = {MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM})
    HttpResponse<?> call(@Body String body, HttpRequest<?> request, Authentication authentication) {
        JSONRPCHandler handler = core.handler();
        ServerCallContext context = callContext(request, authentication);
        try {
            A2ARequest<?> parsed = JSONRPCUtils.parseRequestBody(body, "");
            context.getState().put(METHOD_NAME_KEY, parsed.getMethod());
            if (parsed instanceof GetExtendedAgentCardRequest cardRequest) {
                // Answered here, not by the SDK's handler - its version check too.
                String version = request.getHeaders().get(A2AHeaders.A2A_VERSION);
                if (version != null && !version.isBlank() && !"1.0".equals(version.strip())) {
                    return json(serialize(new A2AErrorResponse(cardRequest.getId(), new VersionNotSupportedError(
                            null, "Only A2A 1.0 is served", null))));
                }
                return json(serialize(new GetExtendedAgentCardResponse(cardRequest.getId(), cards.extendedCard(
                        tenantId(authentication), mcpKeyId(authentication), RequestOrigin.of(request)))));
            }
            if (parsed instanceof SendMessageRequest send && embedsWebhook(send)) {
                // The SDK stores a new task's webhook before it knows the task's key - refused, not half done.
                return json(serialize(new A2AErrorResponse(send.getId(), new InvalidParamsError(null,
                        "A webhook in SendMessage needs SendStreamingMessage - or register it with "
                                + "CreateTaskPushNotificationConfig once the task exists", null))));
            }
            if (parsed instanceof NonStreamingJSONRPCRequest<?> plain) {
                return json(serialize(answer(handler, plain, context)));
            }
            return HttpResponse.ok(stream(handler, parsed, context)).contentType(MediaType.TEXT_EVENT_STREAM_TYPE);
        } catch (A2AError e) {
            return json(serialize(new A2AErrorResponse(e)));
        } catch (InvalidParamsJsonMappingException e) {
            return json(serialize(new A2AErrorResponse(e.getId(), new InvalidParamsError(null, e.getMessage(), null))));
        } catch (MethodNotFoundJsonMappingException e) {
            return json(serialize(new A2AErrorResponse(e.getId(), new MethodNotFoundError(null, e.getMessage(), null))));
        } catch (IdJsonMappingException e) {
            return json(serialize(new A2AErrorResponse(e.getId(), new InvalidRequestError(null, e.getMessage(), null))));
        } catch (JsonMappingException e) {
            return json(serialize(new A2AErrorResponse(new InvalidRequestError(null, e.getMessage(), null))));
        } catch (JsonProcessingException | com.google.gson.JsonSyntaxException e) {
            // The parser's own text names its internals - a fixed sentence instead.
            return json(serialize(new A2AErrorResponse(new JSONParseError("Request body is not valid JSON"))));
        } catch (RuntimeException e) {
            LOG.error("A2A request failed", e);
            return json(serialize(new A2AErrorResponse(new InternalError("Internal error"))));
        }
    }

    /** A webhook sent with a message that starts something new - one for a task that exists is the SDK's to store. */
    private static boolean embedsWebhook(SendMessageRequest request) {
        return request.getParams().message().taskId() == null
                && request.getParams().configuration() != null
                && request.getParams().configuration().taskPushNotificationConfig() != null;
    }

    private static A2AResponse<?> answer(JSONRPCHandler handler, NonStreamingJSONRPCRequest<?> request,
                                         ServerCallContext context) {
        return switch (request) {
            case SendMessageRequest r -> handler.onMessageSend(r, context);
            case GetTaskRequest r -> handler.onGetTask(r, context);
            case CancelTaskRequest r -> handler.onCancelTask(r, context);
            case ListTasksRequest r -> handler.onListTasks(r, context);
            case GetExtendedAgentCardRequest r -> handler.onGetExtendedCardRequest(r, context);
            case CreateTaskPushNotificationConfigRequest r -> handler.setPushNotificationConfig(r, context);
            case GetTaskPushNotificationConfigRequest r -> handler.getPushNotificationConfig(r, context);
            case ListTaskPushNotificationConfigsRequest r -> handler.listPushNotificationConfigs(r, context);
            case DeleteTaskPushNotificationConfigRequest r -> handler.deletePushNotificationConfig(r, context);
            default -> new A2AErrorResponse(request.getId(), new UnsupportedOperationError());
        };
    }

    private static Publisher<Event<String>> stream(JSONRPCHandler handler, A2ARequest<?> request,
                                                   ServerCallContext context) {
        Flow.Publisher<SendStreamingMessageResponse> events;
        if (request instanceof SendStreamingMessageRequest r) {
            handler.authorizeTaskAccess(r.getParams().message().taskId(), context, TaskOperation.MESSAGE_SEND_STREAM);
            events = handler.onMessageSendStream(r, context);
        } else if (request instanceof SubscribeToTaskRequest r) {
            handler.authorizeTaskAccess(r.getParams().id(), context, TaskOperation.SUBSCRIBE_TO_TASK);
            events = handler.onSubscribeToTask(r, context);
        } else {
            return Flux.just(Event.of(serialize(new A2AErrorResponse(request.getId(), new UnsupportedOperationError()))));
        }
        AtomicLong id = new AtomicLong();
        return JdkFlowAdapter.flowPublisherToFlux(events)
                .map(event -> Event.of(serialize(event)).id(String.valueOf(id.getAndIncrement())));
    }

    private static Long tenantId(Authentication authentication) {
        return ((Number) authentication.getAttributes().get(Roles.TENANT_ID_ATTRIBUTE)).longValue();
    }

    private static Long mcpKeyId(Authentication authentication) {
        return ((Number) authentication.getAttributes().get(Roles.MCP_KEY_ID_ATTRIBUTE)).longValue();
    }

    private static ServerCallContext callContext(HttpRequest<?> request, Authentication authentication) {
        Map<String, Object> state = new HashMap<>();
        state.put(A2aAgentCore.TENANT_ID, tenantId(authentication));
        state.put(A2aAgentCore.MCP_KEY_ID, mcpKeyId(authentication));
        Map<String, String> headers = new HashMap<>();
        request.getHeaders().forEach((name, values) -> headers.put(name, String.join(",", values)));
        state.put(HEADERS_KEY, headers);
        state.put(TENANT_KEY, "");
        state.put(TRANSPORT_KEY, TransportProtocol.JSONRPC);
        return new ServerCallContext(new AuthenticatedUser(authentication.getName()), state,
                A2AExtensions.getRequestedExtensions(request.getHeaders().getAll(A2AHeaders.A2A_EXTENSIONS)),
                request.getHeaders().get(A2AHeaders.A2A_VERSION));
    }

    private static HttpResponse<String> json(String body) {
        return HttpResponse.ok(body).contentType(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    /** The SDK writes the wire format: domain object → its protobuf → JSON, as every transport of the SDK does. */
    private static String serialize(A2AResponse<?> response) {
        if (response.getError() != null) {
            return JSONRPCUtils.toJsonRPCErrorResponse(response.getId(), response.getError());
        }
        return JSONRPCUtils.toJsonRPCResultResponse(response.getId(), proto(response));
    }

    private static MessageOrBuilder proto(A2AResponse<?> response) {
        return switch (response) {
            case SendMessageResponse r -> ProtoUtils.ToProto.taskOrMessage(r.getResult());
            case GetTaskResponse r -> ProtoUtils.ToProto.task(r.getResult());
            case CancelTaskResponse r -> ProtoUtils.ToProto.task(r.getResult());
            case ListTasksResponse r -> ProtoUtils.ToProto.listTasksResult(r.getResult());
            case GetExtendedAgentCardResponse r -> ProtoUtils.ToProto.getExtendedCardResponse(r.getResult());
            case SendStreamingMessageResponse r -> ProtoUtils.ToProto.taskOrMessageStream(r.getResult());
            case CreateTaskPushNotificationConfigResponse r -> ProtoUtils.ToProto.createTaskPushNotificationConfigResponse(r.getResult());
            case GetTaskPushNotificationConfigResponse r -> ProtoUtils.ToProto.getTaskPushNotificationConfigResponse(r.getResult());
            case ListTaskPushNotificationConfigsResponse r -> ProtoUtils.ToProto.listTaskPushNotificationConfigsResponse(r.getResult());
            case DeleteTaskPushNotificationConfigResponse _ -> com.google.protobuf.Empty.getDefaultInstance();
            default -> throw new IllegalArgumentException("Unknown A2A response " + response.getClass().getName());
        };
    }
}
