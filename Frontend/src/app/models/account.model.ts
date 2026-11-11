/** The logged-in tenant user and the tenant they belong to. */
import {TenantChoice} from "./auth.model";

export interface Account {
    userId: number;
    username: string;
    displayName: string;
    tenantId: number;
    tenantName: string;
    /** The user is a tenant admin: keeps the tenant's settings and chats. */
    admin: boolean;
    /** The frontend's language, set by an admin and changed by the user (docs/architecture/i18n.md). */
    language: string;
    /** The tenant has an MCP key: its people can connect Claude Code or Copilot (the page "Konto" shows how). */
    mcpServer: boolean;
    /** The admin let the tenant have scheduled tasks: its tenant admins see the page "Aufgaben". */
    tasksAllowed: boolean;
    /** The tenant's chat answers through its A2A agent: no model or template to choose. */
    answersThroughAgent: boolean;
    /** The tenants where the person has a user who may log in, this one included - the switch shows with two or more. */
    tenants: TenantChoice[];
}

export interface PasswordChangeRequest {
    currentPassword: string;
    newPassword: string;
}
