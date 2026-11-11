package de.alpdesk.services.mcp

import io.micronaut.context.BeanContext
import io.micronaut.context.BeanProvider
import io.micronaut.context.event.BeanCreatedEvent
import io.micronaut.inject.BeanDefinition
import io.micronaut.inject.BeanIdentifier
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerHandler
import io.modelcontextprotocol.spec.McpSchema
import reactor.core.publisher.Mono
import spock.lang.Specification

/**
 * What the tenant's MCP server answers when its own part of initialize, tools/list or prompts/list fails: our words,
 * never the exception's (the calls and prompts/get answer a tool error or the prompt's text - McpSpec, McpProxySpec).
 */
class BrandedMcpServerSpec extends Specification {

    def "a failure of ours in #method is a JSON-RPC error in our words - never the exception's text"() {
        given: "a database failing with a row's values in its message"
        BeanProvider failing = { throw new IllegalStateException("SECRET-ROW-VALUE") } as BeanProvider
        def sdk = [handleRequest     : { McpTransportContext context, McpSchema.JSONRPCRequest request ->
            Mono.just(McpSchema.JSONRPCResponse.result(request.id(), sdkResult))
        },
                   handleNotification: { McpTransportContext context, McpSchema.JSONRPCNotification notification ->
                       Mono.empty()
                   }] as McpStatelessServerHandler
        def server = new BrandedMcpServer(failing, failing, failing, failing)
                .onCreated(new BeanCreatedEvent(Stub(BeanContext), Stub(BeanDefinition), Stub(BeanIdentifier), sdk))

        when:
        def response = server.handleRequest(McpTransportContext.EMPTY,
                new McpSchema.JSONRPCRequest("2.0", method, 1, null)).block()

        then:
        response.result() == null
        response.error().code() == McpSchema.ErrorCodes.INTERNAL_ERROR
        response.error().message() == "The server could not answer - try again later"

        where:
        method         | sdkResult
        "initialize"   | new McpSchema.InitializeResult("2025-06-18", new McpSchema.ServerCapabilities(null, null, null, null, null, null), new McpSchema.Implementation("ai-assistant", "1"), "", null)
        "tools/list"   | new McpSchema.ListToolsResult([], null, null)
        "prompts/list" | null
    }

    def "what the server does not brand passes as the SDK answered it"() {
        given:
        BeanProvider unused = { throw new IllegalStateException("not needed") } as BeanProvider
        def answer = McpSchema.CallToolResult.builder().addTextContent("done").build()
        def sdk = [handleRequest     : { McpTransportContext context, McpSchema.JSONRPCRequest request ->
            Mono.just(McpSchema.JSONRPCResponse.result(request.id(), answer))
        },
                   handleNotification: { McpTransportContext context, McpSchema.JSONRPCNotification notification ->
                       Mono.empty()
                   }] as McpStatelessServerHandler
        def server = new BrandedMcpServer(unused, unused, unused, unused)
                .onCreated(new BeanCreatedEvent(Stub(BeanContext), Stub(BeanDefinition), Stub(BeanIdentifier), sdk))

        expect:
        server.handleRequest(McpTransportContext.EMPTY, new McpSchema.JSONRPCRequest("2.0", "tools/call", 1,
                [name: "list_templates"])).block().result().is(answer)
    }
}
