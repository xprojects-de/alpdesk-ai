package de.alpdesk.services.a2a;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.tasks.BasePushNotificationSender;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The official A2A SDK's server core (docs/architecture/a2a.md "Point 52"), built without a CDI container behind our
 * controller: its request handler with the task lifecycle, queues and subscriptions, our agent, our task store in the
 * tenants' databases and the key owning its tasks. The SDK writes and reads the wire format.
 */
@Singleton
public class A2aAgentCore {

    /** What the controller puts into the SDK's call context: the key's tenant and the key. */
    public static final String TENANT_ID = "alpdesk.tenantId";
    public static final String MCP_KEY_ID = "alpdesk.mcpKeyId";
    static final String METHOD = JSONRPCContextKeys.METHOD_NAME_KEY;
    static final String STREAMING = "SendStreamingMessage";

    private final ExecutorService agentThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService consumerThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final JSONRPCHandler handler;
    private final MainEventBusProcessor processor;

    public A2aAgentCore(AssistantAgent agent, JdbcTaskStore tasks, KeyOwnsTask owners, A2aCards cards,
                        JdbcPushConfigStore pushConfigs, PushAddresses pushAddresses, A2aClient a2a) {
        MainEventBus bus = new MainEventBus();
        InMemoryQueueManager queues = new InMemoryQueueManager(tasks, bus);
        // Webhooks through our HTTP adapter (no redirect, the timeout) and our address rules - public HTTPS only.
        PushNotificationSender push = new BasePushNotificationSender(pushConfigs, a2a.pushClient(), pushAddresses);
        processor = new MainEventBusProcessor(bus, tasks, push, queues);
        DefaultRequestHandler requests = DefaultRequestHandler.builder()
                .agentExecutor(agent)
                .taskStore(tasks)
                .queueManager(queues)
                .pushConfigStore(pushConfigs)
                .mainEventBusProcessor(processor)
                .executor(agentThreads)
                .eventConsumerExecutor(consumerThreads)
                .authorizationProvider(owners)
                .build();
        // The card the SDK checks requests against: the public one - its address does not matter there.
        handler = new JSONRPCHandler(cards.publicCard(""), requests, agentThreads);
    }

    public JSONRPCHandler handler() {
        return handler;
    }

    /**
     * The SDK's event thread holds the task store - left running it would keep every application context that ever
     * started (the specs start dozens). Its stop() is package-private (a CDI container calls it): called reflectively.
     */
    @PreDestroy
    void stop() {
        agentThreads.shutdownNow();
        consumerThreads.shutdownNow();
        try {
            java.lang.reflect.Method stop = MainEventBusProcessor.class.getDeclaredMethod("stop");
            stop.setAccessible(true);
            stop.invoke(processor);
        } catch (ReflectiveOperationException | RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(A2aAgentCore.class).warn("The A2A event thread could not be stopped: {}",
                    e.toString());
        }
    }
}
