package de.alpdesk.dto.account;

import de.alpdesk.dto.login.TenantChoice;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * The logged-in tenant user and its tenant; admin: the user is a tenant admin; language: its frontend's language
 * (docs/architecture/i18n.md); mcpServer: the tenant has an MCP key - its people can connect Claude Code or Copilot
 * (the page "Konto" shows how; the key itself comes from a tenant admin).
 */
@Serdeable
public record AccountResponse(
        Long userId,
        String username,
        String displayName,
        Long tenantId,
        String tenantName,
        boolean admin,
        String language,
        boolean mcpServer,
        /** The admin let the tenant have scheduled tasks - its tenant admins see the page "Aufgaben". */
        boolean tasksAllowed,
        /**
         * The tenant's chat answers through its A2A agent (docs/architecture/a2a.md): no model or template to choose,
         * the agent's skills are the chips.
         */
        boolean answersThroughAgent,
        /**
         * The tenants where a user of this username may log in, this one included - the user menu offers the switch
         * with two or more. Without the WAM proxy only the users the admin added as the same person's.
         */
        List<TenantChoice> tenants
) {
}
