package de.alpdesk.controller.chat;

import de.alpdesk.dto.chat.ChoiceResponse;
import de.alpdesk.dto.chat.SkillChoiceResponse;
import de.alpdesk.services.mcp.McpConnectionService;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import de.alpdesk.dto.chat.SoftwareChoiceResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.mcp.McpTools;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

/**
 * The connected software the tenant user reaches through its groups - what a question may leave out (the chips above
 * the chat's input, roadmap point 51). Nothing but what the user may use.
 */
@Secured(Roles.TENANT_USER)
@Tag(name = "Users")
@SecurityRequirement(name = "UserAuth")
@Controller("/tenant/chat/software")
@ExecuteOn(TaskExecutors.BLOCKING)
public class SoftwareChoiceController {

    private final McpTools software;
    private final McpConnectionService connections;

    public SoftwareChoiceController(McpTools software, McpConnectionService connections) {
        this.software = software;
        this.connections = connections;
    }

    /** The connected software - or, for a tenant answering through its A2A agent, the agent's skills. */
    @Produces(MediaType.APPLICATION_JSON)
    @Get
    @ApiResponse(responseCode = "200", description = "The connected software, or the agent's skills", content = @Content(
            array = @ArraySchema(schema = @Schema(oneOf = {SoftwareChoiceResponse.class, SkillChoiceResponse.class}))))
    public List<ChoiceResponse> list(Authentication authentication) {
        Long tenantId = TenantContext.tenantId(authentication);
        if (connections.answeringAgent(tenantId).isPresent()) {
            return connections.skillsOfAnsweringAgent(tenantId).stream()
                    .<ChoiceResponse>map(SkillChoiceResponse::from).toList();
        }
        return software.connectionsOfUser(tenantId, TenantContext.userId(authentication)).stream()
                .<ChoiceResponse>map(SoftwareChoiceResponse::from).toList();
    }
}
