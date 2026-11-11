package de.alpdesk.controller.mcp;

import de.alpdesk.dto.mcp.SoftwarePromptRequest;
import de.alpdesk.dto.mcp.SoftwarePromptResponse;
import de.alpdesk.dto.mcp.SoftwarePromptText;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.mcp.SoftwarePrompts;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

/** The prompts of the connected software a tenant user may use in the chat, and their text with the user's values. */
@Secured(Roles.TENANT_USER)
@Tag(name = "Users")
@SecurityRequirement(name = "UserAuth")
@Controller("/tenant/chat/prompts")
@ExecuteOn(TaskExecutors.BLOCKING)
public class SoftwarePromptController {

    private final SoftwarePrompts prompts;

    public SoftwarePromptController(SoftwarePrompts prompts) {
        this.prompts = prompts;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<SoftwarePromptResponse> list(Authentication authentication) {
        return prompts.forUser(TenantContext.tenantId(authentication), TenantContext.userId(authentication));
    }

    /** The prompt's text with the user's values - for the input field, not sent. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post("/{promptId}")
    public SoftwarePromptText fill(Authentication authentication, @PathVariable Long promptId,
                                   @Body SoftwarePromptRequest request) {
        return new SoftwarePromptText(prompts.fill(TenantContext.tenantId(authentication),
                TenantContext.userId(authentication), promptId, request.arguments()));
    }
}
