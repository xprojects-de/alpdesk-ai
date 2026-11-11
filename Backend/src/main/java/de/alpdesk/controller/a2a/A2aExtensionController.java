package de.alpdesk.controller.a2a;

import de.alpdesk.security.RequestOrigin;
import de.alpdesk.services.a2a.A2aCards;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Hidden;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * What the tenants' A2A agent shows anyone (docs/architecture/a2a.md "The agent cards"): the neutral public card with
 * the absolute address of /a2a - built from the host the client asked, under the context path - and the description of
 * our two extensions, served by the installation itself (a company's network may not reach a public website).
 */
@Hidden
@Controller
@Secured(SecurityRule.IS_ANONYMOUS)
public class A2aExtensionController {

    private final A2aCards cards;

    public A2aExtensionController(A2aCards cards) {
        this.cards = cards;
    }

    @Get(uri = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON)
    String card(HttpRequest<?> request) throws JsonProcessingException {
        return JsonUtil.toJson(cards.publicCard(RequestOrigin.of(request)));
    }

    @Get(uri = "/a2a/extensions/assistant/1", produces = "text/markdown")
    HttpResponse<String> assistant() {
        return HttpResponse.ok(text("assistant-1.md")).contentType("text/markdown; charset=utf-8");
    }

    @Get(uri = "/a2a/extensions/asker/1", produces = "text/markdown")
    HttpResponse<String> asker() {
        return HttpResponse.ok(text("asker-1.md")).contentType("text/markdown; charset=utf-8");
    }

    private static String text(String name) {
        try (InputStream in = A2aExtensionController.class.getResourceAsStream("/a2a/extensions/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
