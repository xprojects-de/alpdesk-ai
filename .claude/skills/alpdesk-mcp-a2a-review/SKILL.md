---
name: alpdesk-mcp-a2a-review
description: "Focused non-diff, multi-agent review of everything MCP and A2A in Zirmo, both directions: the MCP keys (several per tenant, each with its knowledge areas, connected software, tasks and web search; the two doors mcp and a2a; created, renewed, deleted by tenant admins and the admin; McpKeyTokenValidator, ROLE_MCP for /mcp and /a2a only), the tenant's MCP server at /mcp (stateless streamable HTTP, BrandedMcpServer with the tenant's brand in initialize, tools/list and prompts, ask_assistant with files for the client, search_knowledge / read_document, list_templates, the templates as prompts, list_tasks / run_task / task_run), the tenant's A2A server at /a2a on the official A2A SDK (the neutral public card and the key's extended card, SendMessage as a Message, streamed questions and the key's scheduled tasks as stored tasks owned by the key, webhooks to public HTTPS only, the retention), the proxy (the connected software passed through to MCP clients: reading tools named <connection>__<tool> within 32 characters, changing tools only for a key that may change in that software - a level per key and software, marked as not read-only, whether a person is asked being the client's matter, never through ask_assistant or /a2a -, list and call agreeing, what the software sends passed on unchanged - a tool's whole entry, a call's result (a web link as a link, a link to its own resource embedded), every prompt message -, its resources with the connection in front of the address where the connection's switch is on, the instructions with the line naming the key's software, every failure a tool error so the client's session lives on (a resource's not found a JSON-RPC error) - the software's address and key (or token) never leave; an A2A agent never passed through), and the clients to the tenant's software (MCP connections: addresses and internal hosts, McpClient's limits, reading tools and prompts, reload, levels per user group, the software's prompts in the chat, the API keys' software for /api/v1/chat; A2A connections: the card read into instructions and skills, one tool <connection>__ask, the key only to the address entered, the agent answering the whole chat - context, question back, stop, cancel, the asker's metadata, the loop warning, the admin's switch per tenant; a connection that signs in with OAuth - finding the server, client identity, PKCE, the callback completed only by the one who started it, the token only to the connection's host, refresh with rotation under the row lock) - plus the frontend (key dialog with the doors, key card with Claude Code command, Copilot's mcp.json, the claude.ai connector and the A2A addresses, the page Software with protocol, skills, answers-chat and the check button). Answers its key questions, builds a matrix of what each key reaches on which way and at which door, and checks every failure case (software or agent down or slow, too large, question back, key no longer decryptable, connection renamed or deleted, key renewed mid-session, a card naming another host, a webhook into the internal network). Asks which parts to review. Follows the shared review base (no hallucination, no over-optimisation, decision register, at most 10 verified findings, fix packages). Use when the user invokes /alpdesk-mcp-a2a-review or asks whether MCP keys, the MCP or A2A server, the proxy, the connected software or the A2A agents behave correctly - NOT a diff/branch/PR comparison."
---

## What this does

A read-only review of **both directions of MCP and A2A** ([mcp.md](../../../docs/architecture/mcp.md),
[a2a.md](../../../docs/architecture/a2a.md)):

- **In**: an MCP client - Claude Code, Copilot, Cowork, claude.ai - reaches the tenant's MCP server at `/mcp`, an A2A
  client - an agent platform, a workflow engine - the tenant's assistant as an A2A agent at `/a2a`, both with
  one of the tenant's **MCP keys**. Each key carries its own rights: knowledge areas, connected software, scheduled
  tasks, web search - and its **doors** (`mcp`, `a2a`): at a door it does not open the key is unknown (401). The MCP
  server answers with the tenant's brand, offers our tools (`ask_assistant`, `search_knowledge`, `read_document`,
  `list_templates`, the task tools), the tenant's templates as prompts and - for a connection that **passes through** -
  the software's reading tools, prompts and instructions directly (the proxy, roadmap point 41) - and its **changing
  tools** where the key may change in that software (`tenant_mcp_key_connection.level` `WRITE`, "Darf ändern in",
  roadmap point 68): marked as not read-only; whether a person is asked before a call is the MCP client's matter (Claude Code: at a
  tool's first use, not in a mode or with a rule that allows it - mcp.md "What the client does"). The A2A server answers
  a question like `ask_assistant` (a Message; streamed: a Task stored for the key), lists the key's templates and tasks
  as skills of its extended card, starts the key's scheduled tasks as A2A tasks and posts their events to webhooks.
- **Out**: Zirmo is the client of the tenant's own software's MCP server or of an **A2A agent** (both a
  **connection**, `mcp_connection.protocol`): it reads the software's tools and prompts or the agent's card, the tenant
  admin turns tools on (an agent's skills are on at once), and the chat (by group level), `ask_assistant` (by MCP key),
  `/api/v1/chat` (by API key), the proxy (MCP software only) and the scheduled tasks (`call_tool`, `call_agent`, an
  assistant `prompt` step) use them. **One A2A connection may answer the tenant's whole chat** ("Beantwortet den
  Chat"): then no model of ours answers, the agent gets the question, the files and who asks, and keeps a context per
  conversation.

Zirmo is the one gate: the software's address and key (or token) never leave it, and the key decides
who gets what. The review follows each way end to end, checks that every key reaches exactly what it was given and
nothing else - at both doors -, that list and call agree, that every failure keeps the client's session alive or is a
proper A2A error, that a task belongs to its key, and that what goes out is bounded.

**It follows [.claude/review-base.md](../../review-base.md)**: rules, the decision register, no hallucination, no
over-optimisation, what counts as a finding, the verifier, the budget, fix packages and the report. Read it first; this
skill adds parts, paths, key questions and its own report sections.

Relation to the other reviews: how `ask_assistant`'s answer is built inside the agent loop (rounds, rules, stored
nothing) and the **changing tool calls** waiting for the user's decision in the chat are `alpdesk-chat-review` part 2 -
here only that the MCP key's rights reach the loop and that changing tools reach a key on one way only - passed
through to its MCP client, with the level `WRITE` for that software - and never the tenant's model behind
`ask_assistant` or `/a2a`; how the chat behaves when
an agent answers it (`ChatService`'s agent branch, stop, logout, the stored conversation) is `alpdesk-chat-review` too -
here the connection, the client and the agent's rights; whether an attacker gets past a key, SSRF through a connection's,
a card's or a webhook's address and prompt injection through a software's or agent's text are `alpdesk-security-review`
parts 3, 6, 11 and 12 - here the same rules are checked for correctness and consistency; what disappears with a key, a
connection, an A2A task or a tenant is `alpdesk-tenancy-review`; whether `search_knowledge`'s, `ask_assistant`'s and
`/a2a`'s provider calls are recorded - and an agent's answer is not - is `alpdesk-usage-review`; a task's run itself,
`call_agent` and the assistant `prompt` step are `alpdesk-task-review` - here only `TaskMcpTools`, `RunTasks` and which
key may start what; how documents get into the search is `alpdesk-knowledge-review`.

## Step A - choose the parts

- If the arguments name parts (`teil=1,3`, `alle` ...), take them.
- Otherwise **ask** with `AskUserQuestion` - four options per question at most, so two questions in one call, both
  `multiSelect: true`, headers "Teile 1-4" and "Teile 5-7", question "Welche Teile von MCP und A2A soll das Review
  prüfen?", options:
  - "1: MCP-Keys & Zugang" - mehrere Keys je Mandant, Bereiche/Software/Aufgaben/Websuche je Key, die zwei Türen (mcp, a2a), anlegen, erneuern, löschen, McpKeyTokenValidator (auch unter Context-Path), /mcp und /a2a nur mit ROLE_MCP, Key-Dialog, Key-Karte, Claude-Code-Befehl, mcp.json, claude.ai-Connector, A2A-Adresse und Card-Adresse
  - "2: MCP-Server (/mcp)" - BrandedMcpServer, Marke, Protokoll, ask_assistant mit Dateien, search_knowledge, read_document, Vorlagen als Prompts, Aufgaben-Werkzeuge, Fehler als Werkzeugfehler
  - "3: A2A-Server (/a2a)" - A2aController auf dem SDK-Kern, neutrale Card und Extended Card des Keys (Schema hinter dem Proxy, Context-Path), SendMessage als Message, gestreamt als gespeicherter Task des Keys, Aufgaben des Keys als A2A-Tasks, GetTask/ListTasks/CancelTask/SubscribeToTask, Task gehört seinem Key, Webhooks nur an öffentliches HTTPS, Retention
  - "4: Proxy (Durchreichen)" - Software direkt an MCP-Clients: Namen ≤ 32 Zeichen, Liste und Aufruf passen zusammen, Prompts, Anweisungen, Dateien, Fehler halten die Sitzung, Adresse und Token bleiben drin, ein A2A-Agent wird nie durchgereicht
  - "5: Angebundene Software (MCP-Client)" - MCP-Verbindungen: Adressen, McpClient und seine Grenzen, Werkzeuge und Prompts lesen und neu laden, Stufen je Gruppe, Prompts im Chat, Software der API-Keys, Aufrufe aus Aufgaben ohne Person, Dateien als Link
  - "6: A2A-Agenten (Client)" - A2A-Verbindungen: Card lesen, Skills als Werkzeuge, A2aHttpClient/A2aClient (Key nur an die eingetragene Adresse, Grenzen), <connection>__ask, der antwortende Agent (Kontext, Rückfrage, Stop, Cancel, Metadaten des Fragenden, Loop-Warnung), Schalter des Admins je Mandant, Check-Button, Seite „Software“
  - "7: Anmeldung mit OAuth" - Punkt 61, beide Protokolle: Server finden, Client-Identität (Metadaten-Dokument, Registrierung, von Hand), Anmelden mit PKCE, Callback und Abschluss nur für den, der begann, Token nur an den Host der Verbindung, Erneuern mit Rotation unter Sperre, 401/403, „Anmeldung nötig“
  Nothing chosen in either question (or "Other: alle") means all seven. Parts 6 and 7 were part 5 until November
  2026 (one agent could no longer read it all).

## Scope

Base paths `Backend/src/main/java/de/alpdesk/` (below `…/`), tests `Backend/src/test/groovy/de/alpdesk/`, frontend
`Frontend/src/app/`, management schema `Backend/src/main/resources/db/migration/` (`V6__mcp_prompts.sql`,
`V12__several_mcp_keys.sql`, `V1__init.sql` for `mcp_connection` / `mcp_tool`, `V17__release_2_0_0.sql` for
`protocol`, `answers_chat`, the doors, `a2a_task_index`), tenant schema `Backend/src/main/resources/db/tenant/`
(`V8__release_2_0_0.sql` for `a2a_task`, `a2a_push_config`, the conversation's agent columns).

| Part | Where |
|---|---|
| 1 MCP-Keys & Zugang | `…/services/mcp/McpKeyService.java` (`Caller` with both doors, `requireDoor`), `…/security/McpKeyTokenValidator.java` (`opens`, `a2aPath` below the context path), `…/security/{Roles,TenantContext}.java` (`mcpKeyId`), `micronaut.security.intercept-url-map` in `application.properties` (the `/mcp` and `/a2a` entries), `…/controller/mcp/{TenantAdminMcpKeyController,McpKeyAdminController}.java`, `…/entities/mcp/TenantMcpKey.java`, `…/repositories/mcp/TenantMcpKeyRepository.java`, `…/dto/mcp/McpKey*.java`, `…/services/document/KnowledgeAreaService.java` (`scopeOfMcpKey`), `…/services/tools/WebSearchAccess.java` (the MCP key's switch), `…/controller/account/AccountController.java` (`mcpServer`), `db/migration/V12__several_mcp_keys.sql` (`tenant_mcp_key_area`, `_connection`, `_task`), `V17__release_2_0_0.sql` (`mcp`, `a2a`); frontend `services/mcp-key.service.ts`, `store/mcp-key/**`, `components/shared/{mcp-key-panel,key-dialog,key-rights,claude-connector}/**`, `components/account/mcp-connect.component.ts`, `utils/{mcp,key-form}.util.ts` (`a2aAddresses`, `noDoor`), `models/mcp.model.ts` |
| 2 MCP-Server (/mcp) | `…/services/mcp/{BrandedMcpServer,AssistantMcpTools,KnowledgeMcpTools,AssistantMcpPrompts,TaskMcpTools}.java`, `micronaut.mcp.server.*` and `app.task.mcp-wait` in `application.properties`, `…/services/chat/ChatService.java` (only `answerOnce` / `prepareOnce` with the MCP key, `delivered`, `MAX_DELIVERED_BYTES`, `DELIVERED_FILE_RULES`), `…/services/tools/{ReadDocumentTool,ToolContext}.java` (`locate`, `part`, `Files.DELIVERED`, `Asker`), `…/repositories/document/DocumentRepository.java` (`findFoundText`), `…/services/document/KnowledgeSearch.java` (only that the key's scope reaches it), `…/services/branding/**` (the brand as the server reads it) |
| 3 A2A-Server (/a2a) | `…/controller/a2a/{A2aController,A2aExtensionController,A2aJsonRpcTransport}.java` (`origin` from `X-Forwarded-Proto`, the SDK parses and writes, the extended card and its version check answered here, errors as JSON-RPC errors with HTTP 200), `…/services/a2a/{A2aAgentCore,AssistantAgent,A2aCards,RunTasks,JdbcTaskStore,KeyOwnsTask,JdbcPushConfigStore,PushAddresses,A2aTaskCleanupJob}.java`, `…/repositories/a2a/{A2aTaskRepository,A2aTaskIndexRepository}.java`, `…/entities/a2a/A2aTaskIndex.java`, `…/services/task/TaskService.java` (`startByA2a`, `taskOfTenant`, `mcpTask`, `cancel` with `StartedBy.A2A`), `resources/a2a/extensions/*.md`, `resources/META-INF/services/org.a2aproject.sdk.server.TransportMetadata`, `app.a2a.task-retention-hours` in `application.properties` and both compose files, `app.task.a2a-subscribe` only as the default in `AssistantAgent` (`@Value("${app.task.a2a-subscribe:30m}")` - in neither file), `db/tenant/V8__release_2_0_0.sql` (`a2a_task`, `a2a_push_config`), `db/migration/V17__release_2_0_0.sql` (`a2a_task_index`); `…/services/chat/ChatService.java` (only `answerOnce` / `prepareOnce` / `streamOnce` with the MCP key as `/a2a` uses them - `delivered` files, `stopWhen`) |
| 4 Proxy (Durchreichen) | `…/services/mcp/SoftwareMcpProxy.java` (MCP connections only), `…/services/mcp/SoftwareResources.java` (resources passed through), `…/services/mcp/BrandedMcpServer.java` (where it answers passed-through `tools/list`, `tools/call`, `prompts/*`, `resources/*` and adds the instructions), `…/services/mcp/{McpConnectionService,SoftwarePrompts}.java` (`passes_through`, `notForA2a`, `instructions`, `values`), `…/services/chat/ChatFileService.java` (`passedOn`, `RETURNED_TYPES`), `…/services/mcp/McpClient.java` (call and prompt as the proxy uses them), `…/controller/mcp/{TenantAdminMcpConnectionController,McpConnectionAdminController}.java` (`.../pass-through`), `…/dto/mcp/McpPassThroughRequest.java`, `…/services/chat/ChatService.java` (`softwareInstructions` - our rules first); decision notes `local-data/review/2026-10-25-punkt-41-mcp-proxy.md`; local check `devtools/mcp-test-server/proxy_check.py` (read, not run) |
| 5 Angebundene Software (MCP-Client) | `…/services/mcp/{McpConnectionService,McpClient,McpAddresses,McpTools,SoftwarePrompts,LinkDownloads}.java` (a `resource_link` fetched at once: `resources/read` in the session, an http(s) download under the address rules, the bearer only to the connection's origin) (`toolForTask`, `toolSchema`, `callForTask` - a task's way to a tool, by connection name inside the tenant, without a user's level, MCP connections only; `CallResult.files` for a source's file tool), `…/services/task/step/{CallToolStep,ToolArguments}.java` (only what reaches the client), `…/controller/mcp/{TenantAdminMcpConnectionController,McpConnectionAdminController,SoftwarePromptController}.java` (`.../check` too), `…/entities/mcp/{McpConnection,McpConnectionProtocol,McpTool,McpPrompt,McpAccessLevel}.java`, `…/repositories/mcp/{McpConnectionRepository,McpToolRepository,McpPromptRepository}.java`, `…/dto/mcp/{McpConnection*,McpTool*,McpPrompt*,McpAccessResponse,SoftwarePrompt*}.java`, `…/services/tenant/UserGroupService.java` (`accessOfUser`, `levels`), `…/services/tenant/ApiKeyService.java` (`connectionIds`, `tenant_api_key_connection`), `…/services/tools/Toolbox.java` (only which software tools an asker gets), `…/services/common/InternalAddresses.java`, `…/security/SecretCipher.java` (the connection's key); frontend: the files listed at part 7's end, for the MCP side (tools, levels, pass-through, prompts, reload) |
| 6 A2A-Agenten (Client) | `…/services/a2a/{A2aHttpClient,A2aClient,AgentAnswers}.java` (the SDK's client on our adapter: `McpAddresses` before every request, `KeyLeak`, 12 MB, `app.ai.timeout` for the tool way and `app.a2a.timeout` / `max-answer-chars` for the answering agent, the card at the address's origin, `callCard` pinning the address entered, `asker` metadata, the open calls per tenant and `warnIfLoop`, `Call.stop`, `cancelQuietly`), `…/services/mcp/McpConnectionService.java` (`protocol`, `requireA2aAllowed`, `loadCard` / `syncSkills`, `answeringAgent`, `answersChat`, `check`, `skillsOf*`, `agentForTask` / `agentForRun`), `…/services/mcp/McpTools.java` (`agentTool`, `askAgent`, `a2aAllowed`, `forTask`), `…/services/chat/ChatService.java` (only `prepareForAgent`'s rights: the admin's switch, the template refused, the files' budget, the skills), `…/entities/tenant/Tenant.java` (`a2aAllowed`), `…/services/task/step/CallAgentStep.java` and `PromptStep.answeredByAgent` (only what reaches the client), `app.a2a.*` in `application.properties`, `…/controller/mcp/{TenantAdminMcpConnectionController,McpConnectionAdminController}.java` (`.../check`, the protocol and `answersChat` of a connection), `…/services/common/InternalAddresses.java`; frontend: the files listed at part 7's end, for the A2A side (protocol, skills, answers-chat, check, the tenant dialog's `a2aAllowed`) |
| 7 Anmeldung mit OAuth | roadmap point 61, mcp.md "Signing in to the software's server (OAuth)", for MCP and A2A connections alike: `…/services/oauth/{OAuthClient,OAuthMetadata,OAuthHttp,OAuthAddresses,OAuthReachability,AuthorizationServer}.java` (where the server is - `WWW-Authenticate`, resource metadata, the card's `OAuth2SecurityScheme`; the client identity - metadata document, registration, by hand, bound to issuer and token endpoint; PKCE, `state`, `resource`; `iss`; refresh with rotation under the row lock; 64 KB, 30 s, no redirect, `McpAddresses` for every address, `signInPage` checked), `…/services/mcp/Credential.java` and `McpConnectionService` (`credential`, `startSignIn`, `completeSignIn`, `removeSignIn`, `oauth.forget` on a key or a new address), the 401-renew-once and 403-never in `McpClient`, `A2aClient` and `AgentAnswers`, `…/controller/oauth/OAuthCallbackController.java` (callback exchanges nothing, `/oauth/client-metadata.json`), the `.../sign-in`, `.../sign-in/complete` routes of `…/controller/mcp/{TenantAdminMcpConnectionController,McpConnectionAdminController}.java` (`returnUrl` on the request's host and a frontend page only, only the one who started it), `…/entities/oauth/{OAuthGrant,GrantStatus,ClientIdSource}.java`, `…/repositories/oauth/OAuthGrantRepository.java`, `…/dto/oauth/*.java` (`GrantView`: never a token, secret or verifier), `oauth_grant` in `db/migration/V17__release_2_0_0.sql` (the callback controller is `@Secured(IS_ANONYMOUS)`: it may do nothing but forward); plan `local-data/plans/oauth-connections.md` ("Binding decisions" and "Not done on purpose" count like register entries); frontend `services/mcp-connection.service.ts`, `store/mcp-connection/**`, `components/shared/mcp-connections-panel/**` (protocol, skills, answers-chat, check, sign-in, the client id's fields, `isWebAddress`), `components/software-page/**`, `components/chat/chat-prompt-dialog*`, `components/tenant-list/tenant-dialog.component.ts` (`a2aAllowed`), `utils/mcp-connection.util.ts` |

Specs: `controller.mcp.McpSpec` (keys, rights, protocol, flat schemas, tools, files, brand, the key's software for
`ask_assistant`), `McpKnowledgeSpec`, `McpProxySpec`, `McpConnectionSpec`, `A2aConnectionSpec` (part 6: the A2A
connection, its card and skills, the tool way, the key only to the address entered, isolation), `McpConnectionSignInSpec`
(part 7: the OAuth sign-in through the HTTP API, both protocols, the callback, the one who started it, isolation),
`services.oauth.OAuthClientSpec` (metadata, client identity, exchange, refresh and its race, address rules - against
`FakeAuthorizationServer` and `FakeReachability`), `services.mcp.McpClientSpec`
(against `FakeMcpSoftware`; a file returned as a link: both kinds, the bearer per origin, every refusal, the size),
`McpClientTimeoutSpec` (a software not answering in time), `SoftwareMcpProxySpec` (the passed-through names within 32
characters, each from its own tool alone), `McpToolsSpec`, `AssistantMcpFilesSpec`, `controller.a2a.A2aServerSpec` (part 3, with the
SDK's own client: cards, Message, stored task, doors, tasks over A2A, webhooks, the retention),
`controller.chat.AgentChatSpec` (the answering agent: events, context, question back, metadata, skills, stop, delete,
the admin's switch, the check, isolation, the loop warning), `security.TenantApiKeySpec` (the `/mcp` and `/a2a` entries,
the role rule, the `mcp-keys` routes, the anonymous card), `controller.common.ContextPathSpec` (the card's address and
the doors under a sub-path), `TenantAdminSpec`, `controller.document.KnowledgeAreaSpec` (what each MCP key finds),
`controller.chat.WebSearchSpec` (the MCP key's switch), `services.task.TaskStartSpec` (tasks per MCP key),
`services.task.{TaskAgentSpec,TaskAssistantSpec}` (only what reaches the agent), `services.tools.ToolboxSpec`;
frontend `utils/{mcp,mcp-connection,key-form,software-choice}.util.spec.ts`,
`store/{mcp-key,mcp-connection}/*.reducer.spec.ts`. The fakes: `services.mcp.FakeMcpSoftware`, `services.a2a.FakeA2aAgent`
(the SDK's server core behind an embedded HTTP server, with its markers). The smoke test
(`.github/scripts/smoke-test-jar.sh`, its MCP steps and step 11) is read, not run; `devtools/a2a-test-agent/` (the
official Python SDK) and `devtools/mcp-test-server/` are read, not run.

## Key questions (every agent keeps them in mind; the report answers each one explicitly)

1. **Does a key reach exactly what it was given - on every way, at both doors?** Its areas (`ask_assistant`,
   `search_knowledge`, `read_document`, `/a2a`'s answers), its software (`ask_assistant`, `tools/list`, `tools/call`,
   `prompts/*`, the instructions in `initialize`, `/a2a`'s answers), its tasks (`list_tasks`, `run_task`, `task_run`;
   the task skills of the extended card, `metadata.task`, `GetTask`, `ListTasks`, `CancelTask`), its web search - each
   read from the key's id in the authentication, never from the request (`params.tenant` ignored); another key of the
   tenant and another tenant get none of it; an inactive tenant's key 401, an API key or a user's login at `/mcp` and
   `/a2a` 403, an MCP key anywhere else refused. **The doors**: a key without `a2a` is unknown at `/a2a`, one without
   `mcp` at `/mcp` - also below a context path; at least one door always (`mcpKey.noDoor`); existing keys open `/mcp`
   only. What a key may do is the same at both doors; at `/mcp` only the proxy and the templates as prompts.
2. **Does a change of the key count at once?** Renewed: the old secret stops with the next request; deleted: the same;
   areas, software, tasks, web search or doors changed: the next `tools/list`, the next extended card and every call
   after it follow - nothing cached past the request that could serve the old rights.
3. **Does the client's session live through every failure?** MCP: a software down, slow, refusing, too large, a key no
   longer decryptable, the database, an unknown or stale passed-through name, a prompt value refused: a tool result with
   `isError` or the prompt's text saying so - never a JSON-RPC error over HTTP 400/500 from a passed-through call or
   prompt (decided 25 October 2026). A2A (`/a2a`): every refusal is a JSON-RPC error of the spec's code with HTTP 200
   (`InvalidParamsError` for the request's fault, `InternalError` for ours - our message, never the provider's text;
   `-32700` a fixed sentence, `-32601` for 0.3 names, `VersionNotSupportedError`), a failure during a streamed answer
   fails the task with our message; a `TaskNotFoundError` the same for another key's, another tenant's and an unknown
   task.
4. **Do list and call agree?** Passed-through names (`<connection>__<tool>`, ≤ 32 characters, `__` always kept, a
   name cut to fit ends in `__<tool id>`) computed the same way for list and call from the same data and order; a renamed connection or a tool
   turned off answers "Unknown tool ... list the tools again", never calls another tool than the one listed. An A2A
   agent's `<connection>__ask` offers as `skill` exactly the card's skills as last read (`syncSkills`: on at once, at
   most 500, each id once, dropped ones deleted); the extended card's `template:<id>` and `task:<id>` skills are
   exactly the tenant's templates and the key's tasks, and `metadata.template` / `metadata.task` resolve by the same
   names.
5. **What leaves Zirmo, and is it bounded?** Never the software's address, never its key (or token),
   never a connection that does not pass through (answer-only: only through `ask_assistant`),
   **a changing tool only by the key's level** (mcp.md "Changing tools passed through", roadmap point 68): listed and
   callable only for a key with `WRITE` for that software (`changingConnectionIds`), only a tool turned on, only where
   the software passes through and is no A2A agent (400 `mcpKey.changingNotUsed` / `.changingNotPassedThrough`), list
   and call from the same list (`SoftwareMcpProxy.passed`), marked `readOnlyHint: false` (a reading one `true`) - a
   changing tool listed as read-only would be repeated and run in parallel by Claude Code; no text of ours promises
   that the client asks before each call (it does not - SECURITY.md says what it does); `ask_assistant`, `/a2a` and the API keys
   offer the tenant's model reading tools only, whatever the key's level; the level falls back to `READ` when the
   software stops passing through or is taken off the key (never in force again unseen); a changing call that failed
   after it was sent (no answer in time, without content, too large) tells the model the change may have been made;
   every changing call leaves a log line (key id, tenant, tool, software - no arguments, no control characters of the
   software's names),
   never an A2A agent through the proxy (`notForA2a`); the result passed on unchanged (decided 11 November 2026, mcp.md "Connected software through to MCP clients"): text
   whole, images, audio, files with their own type, structured content, `_meta` but the keys MCP reserves; a web link
   as a link (never fetched for a client), a link to the software's own resource embedded within 20 links and 8 MB
   (`McpClient.passTool`), one left with the connection in front where resources pass; the tool's whole entry from
   `mcp_tool.definition` with only name, description start and `readOnlyHint` ours; every prompt message; resources
   (switch `passes_resources`, `SoftwareResources`: the connection's part unique and found again, a web address never
   prefixed, our cursor, not found with the hint, the software's own error passed); 8 MB per message refused, never cut; through `ask_assistant` files by its rules (types,
   text as text, 8 MB together, 5 files) - at `/a2a` as `raw` parts within the same 8 MB; a software's instructions trimmed to 2,000
   characters and after ours; the log names neither arguments, results, questions, answers nor an exception's message
   that may carry a row. **To an A2A agent**: the question, the files within 8 MB, and the asker's metadata
   (`urn:alpdesk-ai:a2a:asker:1`: tenant name, language, username or key name, group names, IDM groups, the skills
   left on - names, never ids, nothing secret; a task's call `task` and `run` instead of a user) - exactly that, nothing
   more; the agent's answer cut at `app.a2a.max-answer-chars`, its files within 8 MB. **A webhook** gets what
   `GetTask` would return - and only to a public HTTPS host, no exemption for the listed hosts.
6. **Is the client to the software or agent bounded and safe?** The address checked when saved and before every call
   (HTTPS, plain HTTP and internal hosts only by `APP_AI_PLAIN_HTTP_HOSTS`), no redirects, the key only to its origin,
   the timeout, 8 MiB per message, at most 100 pages, 500 tools, name and schema limits, cut descriptions and errors;
   one session per use, always closed; off the event loop. **A file a tool returns as a link** (`resource_link`,
   mcp.md "Files returned as a link", `LinkDownloads`): fetched at once, before `callTool` returns - a uri of the
   server's own with `resources/read` in the same session, an http(s) address after the session under the
   connections' address rules, again after every redirect (followed by hand, at most 3); the bearer - key or OAuth
   token - only while the address is on the connection's own origin; at most 50 MB and 60 s for the whole download;
   only as many links as the answer's 5 files allow, none of a failed call; a link that gives no file (expired,
   another status, too large, too slow, an address refused or none at all, a token that cannot be renewed) is a
   note for the model, never a failed call; the file is a `ReturnedFile` like an embedded one - the chat, a task's
   documents source take it unchanged; the proxy no longer fetches a web link for a client (it passes it on) and embeds
   only a link to the software's own resource. **An A2A agent** is always called at the address entered -
   the card's first JSON-RPC interface lends only its `tenant` and version (`callCard`), the card itself is read at the
   address's origin, `KeyLeak` refuses a request with the key to another host as the last net; connect 10 s, request
   `app.ai.timeout` (tool way) or `app.a2a.timeout` (answering agent), 12 MB per answer or event, a Task polled every
   2 s up to the timeout; a client per call (the key may change); the stream's events touch no database (the asking
   thread stores after the latch); a stop cancels the agent's task on a thread of its own, never on the event loop;
   `CancelTask` best effort on delete. **The admin's switch** `tenant.a2a_allowed`: off, an A2A connection cannot be
   created, reloaded or moved to a new address (409), an existing one is neither offered as a tool nor answers the chat
   (409 `a2a.notAllowed` before the stream), the check says so. **A scheduled task** (`call_tool`, mcp.md "Tools called
   by scheduled tasks") reaches a tool by the connection's name inside its tenant only (`findByIdAndTenantId`), a target
   any tool the tenant admin named in the text, a source and a file tool reading ones only (`readOnlyHint`,
   `task.toolChanges`); a tool turned off or gone since the save fails the run (`task.toolUnavailable`), a tool
   answering `isError` or the software out of reach marks the row (target) or fails the step (source); a file the
   tool sends is one per answer, at most 6 MB of file in the 8 MB message - `mcpServer.tooLarge` kept as that file's
   reason, never the whole run's. `call_agent` reaches an A2A connection of the tenant by name only (`agentForTask`,
   `agentForRun` - gone, no longer A2A or the switch off fail the run with `task.agentUnavailable`); `call_tool` never
   reaches an A2A connection, `call_agent` never an MCP one.
7. **Are the users of a connection consistent?** MCP: the chat (group level, READ / WRITE), `ask_assistant` (MCP key,
   reading only), `/api/v1/chat` (API key, reading only), the proxy (MCP key and `passes_through`, reading only), a
   task's `call_tool` and assistant `prompt` step (the connections named in the text, reading only) - only tools turned
   on and offered; reload keeps the admin's choice, a server's changed `readOnlyHint` wins; another address is another
   software (tools, prompts, instructions dropped, key dropped unless given again); deleting a connection takes it off
   every key and group. A2A: the same callers get the one tool `<connection>__ask` with any level (asking is reading),
   only with the admin's switch; the protocol cannot change once made (`protocolFixed`); the chat, `/api/v1/chat` and
   the tasks may leave connections - or, for a tenant answering through its agent, the agent's skills - out per
   question (`excludedConnectionIds` / `excludedSkillIds`, only narrowing, 400 for one not reached or unknown); their
   results start with `[<connection>]` in the chat only. **One answering agent per tenant** (`answers_chat`, the unique
   partial index, 409 `answersChatTaken`, 400 for an MCP connection); then every door of the tenant - the chat,
   `/api/v1/chat`, `ask_assistant`, `/a2a` - is answered by it, with the context per conversation, a question back
   stored as `a2a_waiting` and answered by the next question with `taskId`, a template refused, the files uploaded since
   the last question sent once, the title from the question, nothing in the usage; a question at `/mcp` or `/a2a`
   while a call of the tenant is open logs "Possible loop", never refuses.
8. **Does the server speak with the tenant's brand - and the public card without it?** `initialize` (name,
   instructions within Claude Code's 4,096 characters, ours first), the tool descriptions, the prompts' names and
   descriptions; read off the event loop; the configured `serverInfo` stays neutral; `McpClient`'s `clientInfo` the
   tenant's brand. **The key's description** (2.0.0, mcp.md "MCP keys": `tenant_mcp_key.description`, 500 characters,
   empty removes it) follows the brand - "This access knows: ..." after the instructions, "It knows: ..." at the end of
   `search_knowledge` and `ask_assistant` of that key only, another key of the tenant unchanged; our client keeps it
   with the connection's instructions and tool descriptions until "Neu laden". **The cards**: the public one at
   `/.well-known/agent-card.json` is anonymous and neutral (no tenant, no brand, no skills, provider "Zirmo"), its
   `/a2a` address absolute under the context path with the scheme the proxy forwards (`X-Forwarded-Proto` - behind
   Caddy the app is spoken to in plain HTTP), both extensions declared and never required, described at
   `/a2a/extensions/*/1` by the installation itself; the extended card is the key's: the brand, the key's description or
   the fixed sentence, `answer` + the templates + the key's tasks - answered by our controller with the version check.
9. **Does a task belong to its key, and does it survive what it must?** A streamed or `returnImmediately` question and
   a run started over A2A are rows of `a2a_task` in the tenant's database, found through `a2a_task_index`
   (`KeyOwnsTask`: another key, even of the same tenant, `TaskNotFoundError`; `ListTasks` the caller's key's only,
   paged by offset, `includeArtifacts`); a task stored before the SDK knew its owner waits in memory until then; a
   question task still working from before the JVM's start is answered failed "interrupted by a restart"; a run's task
   nobody mirrors is read from the run; `CancelTask` cancels the run; webhooks (≤ 5 per task, the config encrypted) go
   with their task; the cleanup deletes every task unchanged for `app.a2a.task-retention-hours` (ended or not) in the tenants with tasks
   only, never walking every tenant; the question itself is never stored. The streamed answer is appended in pieces of
   at least 500 characters (the SDK stores the whole task with every piece).
10. **Does the frontend show and send what is true?** A key's secret shown once; the key dialog sends only what the
   tenant has (`key-form.util.ts`), the doors with at least one on (`noDoor`); the Claude Code command, `mcp.json` and
   the claude.ai connector built from `mcpServerName` with `Bearer ` in the header value - only for a key with `mcp`;
   the A2A address and the card's address under the context path (`a2aAddresses`) only for a key with `a2a`; "Name
   (nur Antworten)" for a software that does not pass through; the pass-through switch and the instructions on the
   page "Software" - for an A2A agent instead the protocol tag, the skills, "beantwortet den Chat", the check button and
   its result, no pass-through row; the protocol fixed after creation; the tenant dialog's `a2aAllowed`; the slices
   start with `scope: null` and are reset on logout.
10a. **Does a connection that signs in with OAuth stay the tenant's?** (`services.oauth`, roadmap point 61) The grant
   one per connection, deleted with it, a key or a new address; the callback finds it by its single-use state under the
   row's lock, checks `iss`, never shows a server's error text; the refresh under the row's lock takes a token another
   call renewed meanwhile instead of spending the rotated one; the token goes to the connection's host only (A2A:
   `KeyLeak`), every address of the flow through `McpAddresses`; a 401 renewed once and repeated once, a 403 never;
   `credential(connection)` the one way every caller gets its bearer.
11. **Is it tested?** Each failure case below has a spec with `FakeMcpSoftware` / `FakeA2aAgent` / `FakeAiModels` - or
   the missing case and the spec it belongs in.

## Failure cases (every agent walks through them for its part)

The software or agent out of reach, slow past `app.ai.timeout` (or `app.a2a.timeout` for an answering agent), answering
with HTTP 400/500 and a JSON-RPC error, answering something that is no MCP or no A2A · a message over 8 MiB, an A2A
answer or event over 12 MB, an agent's answer over `max-answer-chars` · more than 100 pages or 500 tools or skills · a
tool's schema over 20,000 characters · a redirect to another host · a tool returning a file as a link: expired
(404/410), larger than 50 MB or never ending, slower than 60 s, redirected to an internal address or more than three
times, on another host than the connection's (no bearer) and redirected back to it, no address at all, six links in
one answer, a link beside `isError`, a `resources/read` uri from a server without the resources capability · a card whose interface names another host, or no
JSON-RPC interface, or that is no card · a request the adapter would send with the key to another host (`KeyLeak`) · the
address resolving to an internal host after it was saved · the connection's key no longer decryptable (`APP_SECRET_KEY`
changed) · the database briefly out of reach during a passed-through call · a connection renamed between `tools/list` and
`tools/call` · a tool turned off, a connection's pass-through switched off or taken from the key between list and call ·
two connections whose names cut to the same 32 characters, or whose parts of a resource's address come out alike ·
a tool not read again since its entry is kept (`definition` null) · an entry over 50,000 characters · a result with
structured content, `_meta` (a key under `io.modelcontextprotocol/`), an image, audio, 150,000 characters of text · a
web link in a result (never fetched for a client) · 22 links to own resources, three of 3 MB each, one the software
cannot read, a software that hangs on `resources/read` (one `app.ai.timeout` for all of a call's links) · a resource's
address without the prefix, with another key's or tenant's prefix, a web address, `<connection>.https://...` · a
cursor that is not ours or names a software taken from the key · a software out of reach, or without resources, in a
list of two · the resources switch on while pass-through is off · the software's own error at `resources/read` (its
code and message, sent with HTTP 500 by the transport - an SDK client's session ends, Claude Code's goes on) · a connection deleted while a key still lists it · a reload
while the admin changes the key or the address (read again under a lock) · a software that turns a reading tool into a
changing one (gone for a key that only reads, asked about for one that may change) · a changing call the software
answers too late, without content or too large (the model told the change may have been made, not to repeat it
blindly) · a key's level taken back, or its software switched to answer-only, between list and call (an unknown tool) ·
pass-through switched off and on again (the key reads only) · a tool's name with a line break in the log line · a software's or agent's instructions over 2,000 characters or with text that tries to lift our rules · a
returned file of a type not on the list, over the limit, or with invalid base64 · a prompt whose template was deleted
since `prompts/list` · a prompt value over 10,000 characters or missing · a key renewed or deleted while its client is
mid-session, or its `a2a` door closed while a task of it is open · a key's areas changed between two `search_knowledge`
calls · `read_document` for a document moved out of the key's areas, waiting to be embedded again or another tenant's ·
`ask_assistant` or `/a2a` without a default model · `run_task` or `metadata.task` for a task taken from the key or the
tenant's switch off, or while a run is open · the tenant deactivated · two tenants with the same brand name · the agent
answering `input-required` (tool way: text; answering agent: the waiting answer; a task's call: the row fails) · the
agent answering a Task that never ends · the agent keeping its stream open after `input-required` · the user stopping,
logging out or deleting the conversation while the agent answers or waits · the admin taking A2A away while an agent
answers the chat · a second connection marked "answers the chat" · a `templateId` with an answering agent · a skill the
card does not have in `excludedSkillIds`, a skill dropped from the card since the conversation stored it · the
conversation's new upload over 8 MB or a video · the agent asking its own tenant back at `/mcp` or `/a2a` (the loop) · a
streamed `/a2a` question whose client goes away, then `GetTask` and `SubscribeToTask` · a restart with a question task
working · another key's task in `GetTask`, `CancelTask`, `SubscribeToTask`, a webhook registration · a webhook on plain
HTTP, an internal host, a sixth per task · `A2A-Version` other than 1.0, a 0.3 method name, broken JSON, a `url` file
part, a non-text file · the card asked through a proxy that ends TLS and under a context path. **OAuth** (part 7): a 401
without metadata (stays "refused our key") · `resource_metadata` on another origin · metadata whose `issuer` is not the
one asked for, or without an authorization or token endpoint · an A2A card naming only the flow's URLs, or a token
endpoint on another host than before (client id starts over) · a sign-in page with `javascript:` or plain HTTP · a
server that cannot read our metadata document, the installation inside a company network · no registration endpoint
(409 `oauth.registrationNeeded`) · a `returnUrl` on another host, outside `/app` / `/dashboard`, with a query, or a
handbook's address · an unknown, reused or expired `state` · the callback completed by another tenant admin, another
tenant or the admin of another sign-in · an `iss` missing where announced or different · the server's `error` text with
markup · an answer over 64 KB or slower than 30 s · a redirect from the token endpoint · two calls refreshing at once
with a rotating refresh token · a refresh refused (`invalid_grant`) against out of reach · a renewed token refused again
· a 403 · a streamed A2A answer ending with a 401 · an A2A task whose token expires while it runs · a key entered or the
address changed on a signed-in connection · the connection deleted with a sign-in pending · `APP_SECRET_KEY` changed
(tokens and verifier no longer decryptable) · `GrantView` or a log line carrying a token, the secret or the verifier.

## Step 1 - Spawn the agents

**Every agent runs on Opus** - pass `model: "opus"` in each Agent call, whatever model the session runs on (decided
9 November 2026: a session on a larger model would hand it to every agent and use up the token budget at once). The
session's own model only verifies and joins the findings (Step 2). Never leave `model` out, and never start an agent as
a fork - a fork takes the session's model.

One agent per chosen part, in a single turn, plus the ground-truth run (review base section 1) of the specs above. Give
every agent "What this does", its part and paths, the key questions, the failure cases, sections 2 to 5 of the review
base verbatim, and the prompt below.

**Documents by part** (what each agent reads before its files - section names are the files' headings; `mcp.md` has
two halves of about 35 and 42 KB, `local-data/plans/a2a.md` 141 KB - never a whole plan; fill the row into the prompt):

| Part | Sections |
|---|---|
| 1 | `mcp.md` "The tenant's MCP server" (the parts on MCP keys); `a2a.md` "The server" (the doors of a key); `tenancy.md` "Who does what" |
| 2 | `mcp.md` "The tenant's MCP server"; `ai-features.md` "Knowledge areas", "Web search", "Files sent with a question", "Reading on in a document" |
| 3 | `a2a.md` "The server", "Deliberately not built"; `local-data/plans/a2a.md` only "Decisions (6 November 2026)" and, in "Built", the "Decisions (lean)" lists (`grep -n "Decisions ("` finds both - they count like register entries); `tasks.md` "Starting without a person" |
| 4 | `mcp.md` "The tenant's MCP server" (the part on connected software passed through); `local-data/review/2026-10-25-punkt-41-mcp-proxy.md` in full if it is there (its decisions count like register entries) |
| 5 | `mcp.md` "MCP clients to the tenant's software" without its OAuth part; `tenancy.md` "User groups"; `tasks.md` "The step `call_tool`" |
| 6 | `a2a.md` "The client", "Deliberately not built"; `local-data/plans/a2a.md` as for part 3; `tasks.md` "The step `call_agent`", "The step `prompt`" |
| 7 | `mcp.md` "Signing in to the software's server (OAuth)" (a `###` inside the clients' half - `grep -n` for it); `local-data/plans/oauth-connections.md` only "Binding decisions" and "Not done on purpose" (they count like register entries) |

The prompt:

> You are reviewing the CURRENT STATE of a real codebase: not a diff. Read first, and only these sections (the files
> are long - read another section when a finding depends on it, `grep` for its heading): of `CLAUDE.md` "Project
> overview", the sections marked "(always)" except "Open source" and "Changelog and version", "Backend tests
> (always)" and "Backend architecture" ("Frontend architecture" when your part has frontend paths) - not "Repository
> and build", "CI" or the commands; `local-data/review/decisions.md` in full; and <the row of your part from "Documents by
> part" below>. Then every file of your part in full. Context: Micronaut 5 / Java 25, Micronaut MCP on the official MCP Java SDK (server: stateless
> streamable HTTP, `SyncToolSpecification` beans with flat schemas, `BrandedMcpServer` wrapping the SDK's handler;
> client: `McpSyncClient` over `HttpClientStreamableHttpTransport`, one session per use), the official A2A Java SDK
> 1.5.0.Final (server: `DefaultRequestHandler` built without CDI behind our Micronaut controller, our `TaskStore` and
> `TaskAuthorizationProvider`, the SDK writes every byte of A2A JSON through `JSONRPCUtils` / `JsonUtil` / `ProtoUtils`;
> client: the SDK's `Client` over our `A2AHttpClient` adapter), a management database with Micronaut Data and one
> database per tenant with plain JDBC, an Angular 22 / NgRx frontend. A JSON-RPC error from `/mcp` goes out with HTTP
> 400 and ends the MCP SDK client's session - that is why `mcp.md` answers failures as tool errors; a JSON-RPC error
> from `/a2a` goes out with HTTP 200 in the body, as the A2A SDK's client expects. Specs use `FakeMcpSoftware`,
> `FakeA2aAgent` and `FakeAiModels`. Read-only: do not edit files, do not run the application, `devtools/mcp-test-server`
> or `devtools/a2a-test-agent`, never touch the developer's dev database, never call an AI provider, a real MCP server
> or a real A2A agent.
> Deliver first a **walkthrough** of your part: one row per step - step · `Class.method` (path:line) · which key or
> user decides it (and where that is read) · what goes out · what happens on failure · verdict ✅ / ⚠️ / ❗. Every ⚠️/❗
> row is also a finding.
> Severity for this review: **Kritisch** = a key reaches an area, software, task or web search it was not given, or
> another tenant's - at either door; a key opens a door it was not given; a key that was renewed or deleted still
> works; an A2A task, its artifacts or its webhooks reachable by another key; the software's address or its key (or token)
> reaches an MCP client, an A2A agent, a webhook or the log; the connection's key sent to another host
> than the one entered; a changing tool reachable through `ask_assistant`, `/a2a`, an API key or an agent, or passed
> through to a key without the level `WRITE` for that software, or listed as read-only; a call or prompt going to
> another tool or software than the one listed; a connection, a card or a webhook reaching an internal host it may not;
> a tenant without the admin's switch answered by or asking an agent; the public card naming a tenant; an OAuth token,
> refresh token, client secret or PKCE verifier reaching another host than the connection's (or the token endpoint it
> is bound to), the frontend, the log or `GrantView`; a sign-in completed for another tenant, another connection or by
> someone who did not start it; the code sent to a page that is no frontend page; the callback exchanging a code;
> **Hoch** = a refresh race spending the only valid refresh token; a refused renewal not marking `SIGN_IN_NEEDED`, or a
> server out of reach dropping a grant; a 401 renewed more than once or a 403 renewed; an A2A message sent twice after
> a renewal; a call of the OAuth flow without the address rules, a size or time limit, or following a redirect; a failure that ends the client's MCP session where `mcp.md` says it must not, or answers `/a2a` with an
> HTTP error or the provider's text instead of the spec's JSON-RPC error; list and call disagreeing so a listed tool or
> skill cannot be called; a connection that does not pass through, or an A2A agent, reaching an MCP client directly; a
> limit of `McpClient` or `A2aHttpClient` missing so one software or agent can exhaust the server's memory or a thread;
> an MCP session or an A2A stream to the software left open beyond what `a2a.md` allows; a call or a cancel on the
> event loop; a database write on the SDK's event thread; a reload that loses the admin's choices or keeps a changing
> tool as reading; a card advertising an address a client cannot reach (scheme, host, context path); a stored task or
> its webhooks surviving the retention or the tenant; the frontend sending a right or a door the tenant admin did not
> choose or showing a secret again.

## Step 2 - Synthesize

Follow **section 6 of the review base**, then the report as in **section 7**. The skill's own sections:

- **Was ein Key erreicht**: a matrix - rows the rights of an MCP key (areas, software, software passing through, tasks,
  web search, the doors) and an API key's software, columns the ways (`ask_assistant`, `search_knowledge`,
  `read_document`, `tools/list`, `tools/call`, `prompts/list`/`get`, `initialize`, `list_tasks`/`run_task`/`task_run`,
  `/api/v1/chat`, `/a2a` `SendMessage`, the extended card, `metadata.task`, `GetTask`/`ListTasks`/`CancelTask`,
  webhooks); each cell: where the right is read (path:line) · spec · verdict ✅ / ⚠️ / ❗ / "–" (not on this way).
- **Fehler und Sitzung**: one row per failure case · what the client gets (tool error, prompt text, JSON-RPC error with
  its code, HTTP status, A2A task state, chat `error` event) · session alive ✅/❌ · what is logged · spec.
- **Durchreichen** (when part 4 ran): how a name is built and resolved, list against call, step by step with path:line;
  what goes out and what never does.
- **A2A-Server** (when part 3 ran): one row per method (`SendMessage` plain and streamed, `GetExtendedAgentCard`,
  `GetTask`, `ListTasks`, `CancelTask`, `SubscribeToTask`, `metadata.task`, the push configs) · who may (path:line) ·
  what is stored where and until when · what goes out · spec; and the two cards field by field against `a2a.md`.
- **Software-Grenzen** (when part 5 or 6 ran): every limit of `McpClient`, `McpConnectionService`, `A2aHttpClient`,
  `A2aClient` and `AgentAnswers` - `Grenze` · `Wert` · `geprüft in` (path:line) · `Spec` · Urteil - and one row per way
  a task calls a tool or an agent (target row, target `all`, source rows, source file tool, `call_agent` row and `all`,
  the assistant `prompt` step): what goes out · what a refusal, an error answer, a question back, too large and out of
  reach each do to the row, the step and the run · `Spec`.
- **OAuth-Anmeldung** (when part 7 ran): the walk of one sign-in - server found, client identity, start, the server's
  page, callback, complete, first call, renewal, renewal refused - one row per step · `Class.method` (path:line) · who
  may (tenant, connection, the one who started it) · what goes out and to which host · what is stored (encrypted?) ·
  what a failure does to the grant's status · `Spec`; and every limit of `OAuthHttp` (64 KB, 30 s, no redirect, address
  rules) in the table above.
- **Der antwortende Agent** (when part 6 ran): the walk of one question of a tenant answering through its agent - from
  each door to the stored conversation - with the events mapped, the metadata as sent, and every stop and failure.
- **Kernfragen**: each key question answered in two or three sentences with the evidence (path:line, spec).
