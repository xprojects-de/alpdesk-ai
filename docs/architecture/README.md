# Architecture reference

The binding rules and decisions of each area of Zirmo. The rules for every change (conventions, commands, tests,
package structure, layers, tenant API, security, database, frontend) are in [CLAUDE.md](../../CLAUDE.md); this folder
holds the rest, one file per area. English, dense.

| File | Contents |
|------|----------|
| [tenancy.md](tenancy.md) | Management database, one database per tenant, how a tenant database is reached, who logs in |
| [ai-features.md](ai-features.md) | langchain4j, model catalog and prices, provider keys, Amazon Bedrock, documents and embeddings, chat, files in the chat, tools (agent loop), usage |
| [branding.md](branding.md) | The installation's and each tenant's look: name, colour, logo - and how it reaches Angular |
| [a2a.md](a2a.md) | A2A both ways: A2A agents as connected software (and a tenant answering through its agent), the tenant's assistant as an A2A agent at /a2a |
| [mcp.md](mcp.md) | The tenant's MCP server (Claude Code and Cowork ask the assistant with one of the tenant's MCP keys), MCP clients to the tenant's software, connections that sign in with OAuth |
| [code-execution.md](code-execution.md) | Python and shell in the chat: the sandbox container, its isolation without privileges, the tools, one session per answer |
| [monitoring.md](monitoring.md) | The daily report by e-mail (installation, every tenant, log), the mail, the in-memory log buffer and the admin's page "Application log" |
| [doc-sites.md](doc-sites.md) | The handbook: a static website per scope uploaded as a ZIP, read beside the chat in a sandboxed iframe through a short-lived address |
| [file-stores.md](file-stores.md) | The tenants' file stores (SFTP, FTPS, S3, SharePoint / OneDrive): the server proving itself before the password goes out, internal hosts, TLS session reuse of FTPS data connections, S3 with the store's own access key only, SharePoint through Microsoft Graph |
| [knowledge-sources.md](knowledge-sources.md) | Knowledge sources: a file store's folder synced into the company knowledge or an area - listing, runs by hand and by schedule, refused and deleted files, the indexer fair between tenants |
| [tasks.md](tasks.md) | Scheduled tasks: the admin's switch per tenant, the format and its check, the steps, langgraph4j with checkpoints, runs, read positions, mails |
| [i18n.md](i18n.md) | The frontend's languages (English, German): Transloco at runtime, the language files, the user's language on the account, the backend's error codes |

Every change updates the affected file in the same change, like the specs. A new area gets its own file (linked here
and in CLAUDE.md).
