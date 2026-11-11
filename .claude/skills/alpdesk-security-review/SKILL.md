---
name: alpdesk-security-review
description: "Focused non-diff, multi-agent security review of the whole current state of Zirmo (Backend+Frontend): admin login and tenant-user login, JWT and sessions, WAM sign-in, roles per controller and tenant isolation, knowledge areas and user groups (no user, key or MCP client finds an area's documents it is not assigned to; a group's rights - areas, levels per connected software, its WAM group - reach its members only), the client software's API keys and the /api/v1 API, MCP keys and /mcp, secrets (SecretCipher, provider keys, logs, error messages), uploads and files (ZIP, Tika, ffmpeg, videos up to 500 MB, storage paths, logos, XSS in the chat's Markdown, the handbook's websites served sandboxed under a signed address), SSRF through model servers, MCP connections (and their OAuth sign-in: the callback forwarding only, completed only by the one who started it, every address of the flow checked, the token only to the connection's host) and file stores (SFTP/FTPS by fingerprint, an S3 store with its own access key only, SharePoint's secret only to Microsoft's login and the token only to Graph), prompt injection that triggers changing tool calls, the web search (only the model's query goes out to Tavily, web pages as prompt injection, who may search), the sandbox the chat's Python and shell run in (one per tenant, its token, isolation without privileges, the session's files). Builds an attack-surface table of every route and checks SECURITY.md and docs/privacy.md against the code. Asks which parts to review. Follows the shared review base (no hallucination, no over-optimisation, decision register, at most 10 verified findings, fix packages). Use when the user invokes /alpdesk-security-review or asks for a security review of the codebase - NOT a diff/branch/PR comparison (that is /security-review)."
---

## What this does

A read-only review of **everything an attacker can reach**: someone on the internet without a login, a tenant user
trying to see another user's or tenant's data or the documents of a knowledge area they are not assigned to, or to use a right of a user group they are no member of, a tenant admin trying to reach the admin's, the client software with an
API key, an MCP client (Claude Code, Cowork, claude.ai, Copilot) with one of the tenant's MCP keys - each with its own
areas and web search -, and content an attacker controls (an uploaded document, a ZIP, a chat
file, a video, a logo, the HTML and scripts of a handbook's website, a tool result of the connected software, a model's answer, the Python or shell code the model runs in
the tenant's sandbox). Unlike `/security-review` it never looks at
a diff: it reads the whole current state, part by part.

**It follows [.claude/review-base.md](../../review-base.md)**: rules (read-only, never the developer's dev database,
never a real provider or MCP server), the decision register
`local-data/review/decisions.md`, no hallucination, no over-optimisation, what
counts as a finding, the verifier, the budget, fix packages and the report. Read it first; this skill adds parts,
paths, the checklist and its own report sections.

Security is in scope in full depth (review base section 4) - but a finding still needs a concrete attack: who, with
which access, sends what, and gets which data or effect. "Should use a stronger hash" without a weakness of the one in
use is no finding. Defense in depth for a threat SECURITY.md declares out of scope (e.g. the WAM proxy being bypassed,
the decided missing lockout behind the reverse proxy) is a decision, not a finding - unless its premise no longer holds.

## Step A - choose the parts

- If the arguments name parts (`teil=1,3`, `alle` ...), take them.
- Otherwise **ask** with `AskUserQuestion` - one question, `multiSelect: true`, header "Teile", question "Welche Teile
  soll das Security-Review prüfen?", options (four per question at most - split into three questions with the same
  header style, "Teile 1-4", "Teile 5-8" and "Teile 9-12"):
  - "1: Anmeldung & Sitzung" - Admin-Login, Login der Mandanten-Benutzer, JWT, Sitzungsende, WAM, Produktions-Geheimnisse, Frontend-Token
  - "2: Rechte & Mandantentrennung" - eine Rolle je Controller, Mandant nur aus TenantContext, fremde Ids, Admin vs. Mandanten-Admin
  - "3: API-Keys & Client-Software-API" - ai_-Keys, /api/v1 (Dokumente, Chat, Dateien, Vorlagen, Aufgaben eines Keys), Swagger, MCP-Keys (mehrere je Mandant, Bereiche und Websuche je Key, die zwei Türen mcp und a2a), /mcp (ask_assistant, search_knowledge, read_document, run_task / task_run) und /a2a (die anonyme Card, die Extended Card des Keys, Tasks gehören ihrem Key, Webhooks)
  - "4: Geheimnisse & Logs" - SecretCipher, Provider-Keys, keyHint, Logs, Fehlermeldungen, was an Provider geht, Anwendungslog und Tagesbericht (nichts vom Inhalt eines Mandanten)
  - "5: Eingaben & Dateien" - Uploads, ZIP, Tika, ffmpeg, Videos, Speicherpfade, Logos, Größenlimits, XSS im Chat, Handbuch-Websites (Sandbox, signierte Adresse), Dateien einer Wissensquelle
  - "6: Modell-Server & angebundene Software (MCP)" - SSRF über Modell-Server und MCP-Verbindungen (interne Adressen, Weiterleitungen, der Key nur an seinen Host), Dateien als Link, der Reranker; baut die Tabelle der ausgehenden Verbindungen
  - "7: Wissensbereiche & Gruppen" - wer welche Dokumente findet: Benutzer (über seine Gruppen), Mandanten-Admin, API-Key, jeder MCP-Key (auch search_knowledge / read_document); Suche, Push, Verschieben, Löschen, Wissensquellen; Rechte der Benutzergruppen (Bereiche, Stufe je Software), WAM-Gruppen
  - "8: Sandbox" - Python/Shell im Chat: eine Sandbox je Mandant, Token, Isolation ohne Privilegien (seccomp, rlimits), Dateien der Sitzung, Aufräumen
  - "9: Dateiablagen" - SFTP/FTPS: Fingerprint vor dem Passwort; S3: eigener Access-Key, Endpoint; SharePoint: Secret nur an den Login, Token nur an Graph; IMAP: nur verschlüsselt außer zu internen Hosts, nur lesend; interne Hosts, Ordner wählen, Schreiben nur mit Schalter
  - "10: Aufgaben" - der Text einer Aufgabe als Daten, Logzeilen und Dokumente als Prompt Injection am Modell eines Schritts, Start nur mit Schalter und zugewiesenem Key, Pfade nur aus Platzhaltern, Formelschutz in der CSV, call_tool ohne Person nur lesend als Quelle, was an Werkzeug, Agent und Mail geht
  - "11: A2A-Agenten & OAuth" - A2A-Verbindungen (Card eines fremden Hosts, der Key nur an die eingetragene Adresse, was an den Agenten geht, Schalter des Admins); Anmeldung mit OAuth (jede Adresse des Ablaufs geprüft, keine Weiterleitung, Callback leitet nur weiter, Abschluss nur für den, der begann, Token nur an den Host der Verbindung, Secret und Refresh-Token nur an den gebundenen Token-Endpoint, Anmeldeseite kein javascript:)
  - "12: Prompt Injection & Websuche" - fremder Text am Modell (Dokument, Chat-Datei, Werkzeug-Ergebnis, Antwort eines Agenten, Webseite), ändernde Tool-Aufrufe nur nach Entscheidung des Benutzers, MCP-Clients, die auf dem PC handeln, was die Websuche nach außen schickt und wer suchen darf
  Nothing chosen (or "Other: alle") means all twelve. **Part 2 always runs** - it builds the attack-surface table.
  Parts 9 to 12 were part 6 until November 2026 (one agent could no longer read it all).
- Name the chosen parts in the Überblick.

## Parts and paths

Base paths: `Backend/src/main/java/de/alpdesk/` (below `…/`), tests `Backend/src/test/groovy/de/alpdesk/`, frontend
`Frontend/src/app/`. Configuration: `Backend/src/main/resources/application.properties` (security, CORS, swagger,
MCP, size limits), `Backend/podman/**` (Caddyfile, compose files, `.env.example`).

1. **Anmeldung & Sitzung**
   - `…/security/{AuthenticationProviderUserPassword,PasswordHasher,ActiveTenantUserRule,TenantUserClaims,WamProxy,ProductionSecretsCheck,Roles}.java`,
     `…/controller/login/WamLoginController.java`, `…/services/tenant/{AccessRequestService,TenantUserService}.java`
     (password, deactivation, admin flag), `…/controller/account/AccountController.java`, `…/controller/tenant/AccessRequestController.java`
   - `micronaut.security.*` (JWT signature, token lifetime, intercept-url-map) in `application.properties`,
     `…/security/ResponseHeaders.java`, `Backend/podman/public/{docker-compose.prod.yml,docker-compose.proxy.yml,Caddyfile,.env.example}`,
     `.github/scripts/check-compose-files.sh`
   - frontend: `services/auth.service.ts`, `interceptors/auth.interceptor.ts`, `guards/auth.guard.ts`,
     `store/auth/**`, `store/logout.meta-reducer.ts`, `store/session.operator.ts`, `components/login/**`,
     `components/tenant-app/switch-tenant-dialog.component.ts`, `utils/last-tenant.util.ts`
   - Special care (2.0.0, roadmap point 51 - tenancy.md "Who does what", "Sign-in through a WAM proxy"): **one person,
     users in several tenants** - a username unique per tenant only, every token one user's of one tenant
     (`TenantUserClaims`), the switch `POST /tenant/account/switch` a new token for the same username's user who may
     log in (with WAM the header proves the person - `ActiveTenantUserRule` holds after the switch -, without WAM the
     target user's password, 400 never 401), the account's `tenants` list only users with a password without WAM and
     their login keys with WAM only; `POST /login` with `X-Login-Key` picks the tenant of a name in several (a wrong key,
     a deactivated tenant, another tenant's page refused like a wrong password, the same timing), ignored with WAM;
     `POST /login/wam` with the choice (`tenants`) and `{tenantId}` (404 for a tenant where the person has no user);
     the admin's `POST /admin/tenants/{id}/users/from-tenant` (admin only - a tenant admin must not see another tenant's
     users); CORS: `APP_CORS_ORIGINS` exact origins, never `*`, `allow-credentials` false (a page of an allowed origin
     must never have the browser send a WAM proxy's cookie along), the same origins frame the handbook.
     Specs: `security.LoginModesSpec` (both modes), `WamLoginSpec`, `TenantUserSpec`, `TenantAdminSpec`,
     `GroupAccessSpec` (no group, no access - at every door; a tenant admin gets in without one), `WamGroupsSpec` (out
     of the IDM group, out of the tenant), `StandardGroupMigrationSpec`, `controller.common.CorsSpec`; the smoke test's
     steps 7-9.
   - Special care: the **admin login** (account from `APP_USERNAME`/`APP_PASSWORD`, the refused defaults, comparison of
     the password, the admin with WAM on); tenant-user login and the claims in the JWT (`tenantId`, `userId`,
     `ROLE_TENANT_ADMIN`) - can a token claim more than the database says, and does `ActiveTenantUserRule` catch
     deactivated users/tenants, a password set by the admin, a former tenant admin; **access needs a group** (2.0.0,
     tenancy.md "User groups"): a user who is no tenant admin gets in only while one of its groups counts for it - is
     that held at every door (`POST /login`, `POST /login/wam` and its choice - 409 `user.noGroup`, never "wait" -, the
     account's `tenants` list, `POST /tenant/account/switch`, every request through `TenantUserService.inAGroup`), are
     the request's IDM groups recorded before the decision (at the sign-in and in `ActiveTenantUserRule`, so a person
     the IDM drops is out with the next request), and does `joinStandard` put a user into "Standard" only when it has
     no WAM group; WAM: the header only counts with WAM
     on, every tenant user's request checked against it, unknown people wait, the admin keeps its password login;
     `ProductionSecretsCheck` covers every secret it should; where the frontend keeps the token and that logout clears
     everything. **Transport** (SECURITY.md "HTTPS only"): Caddy serves HTTPS only; behind an own reverse proxy
     (`Backend/podman/public/docker-compose.proxy.yml`) the app's port is published on `127.0.0.1` only unless
     `APP_LISTEN` in `.env` says otherwise - what README and `.env.example` say about it, and that plain HTTP between
     proxy and app stays on the machine; `check-compose-files.sh` keeps the app's settings of both files alike.
     `security.ResponseHeaders` on every answer, errors included: no framing, no sniffing, `X-Robots-Tag: noindex,
     nofollow`, `X-Accel-Buffering: no` only on a streamed answer.
   - Specs: `security.AuthenticationProviderSpec`, `LoginModesSpec`, `LoginWithWamSpec`, `LoginWithoutWamSpec`,
     `WamLoginSpec`, `WamProxySpec`, `ProductionSecretsCheckSpec`, `TenantUserSpec`, `TenantAdminSpec`,
     `ResponseHeadersSpec`, `LoginTimingSpec` (an unknown name, a wrong password and a user without a password take
     the same time).
2. **Rechte & Mandantentrennung** (always)
   - read in full: every controller under `…/controller/**` (excluding the details of `api/v1` and `mcp`, which parts
     3 and 6 read), `…/security/TenantContext.java`, `…/services/tenant/TenantDatabases.java`
     (`dataSource(databaseName)`), the intercept-url-map
   - **followed, not read in full**: the services and repositories behind the endpoints (`…/services/**`,
     `…/repositories/**` are far too much for one agent). For every endpoint that takes an id from the path, query or
     body, follow that id from the controller's call into the service method and on to the statement that reads or
     writes (open the method, `grep` for the repository method it calls) until you see where it is checked against
     the caller's tenant and user - that line goes into the attack-surface table. A service method several
     controllers share is followed once. Say in the result how many endpoints you followed and which you could not
     settle ("Offen/unklar").
   - Special care: exactly one `@Secured` role per controller matching its path, never `IS_AUTHENTICATED`; the tenant
     and user id only from `TenantContext`; every id from a path (document, conversation, file, key, template, user,
     connection, pending action) checked against the caller's tenant **and user** before reading or writing - another
     tenant's or another user's id answers 404; a tenant admin reaches only its own tenant through `/tenant/admin/**`; a
     plain user never `/tenant/admin/**`; the admin's `/admin/tenants/{id}/**` takes the tenant from the path and the
     tenant must exist; anonymous routes (`/branding`, `/watchdog`, `/login/wam`, static, swagger) give nothing away.
   - The OAuth sign-in of connected software (`/oauth/connections/callback`, `/oauth/client-metadata.json`, the
     `.../sign-in` routes) is part 11; here its routes only as rows of the attack-surface table (the callback
     anonymous and forwarding only, the others the tenant admin's and the admin's).
   - Deliver the **attack-surface table** (see "Own report sections").
   - Specs: `security.TenantApiKeySpec`, `TenantUserSpec`, `TenantAdminSpec`, and the isolation cases of the feature
     specs (`grep -rn "404\|another tenant\|other tenant" Backend/src/test/groovy`), `controller.mcp.McpConnectionSignInSpec`
     (the sign-in's routes, the callback, the one who started it, isolation).
3. **API-Keys & Client-Software-API**
   - `…/security/{ApiKeyTokenValidator,McpKeyTokenValidator}.java`, `…/services/tenant/ApiKeyService.java`,
     `…/services/mcp/McpKeyService.java`, `…/controller/tenant/{TenantApiKeyController,TenantAdminApiKeyController}.java`,
     `…/controller/mcp/{TenantAdminMcpKeyController,McpKeyAdminController}.java` (`…/mcp-keys`, `…/{id}`,
     `…/{id}/renew`), `…/repositories/mcp/TenantMcpKeyRepository.java`, `…/security/McpKeyTokenValidator.java`,
     `…/controller/api/v1/**`,
     `…/dto/api/v1/**`, `…/controller/chat/ChatFiles.java`, `…/controller/document/DocumentUploads.java`,
     `…/controller/common/SwaggerController.java`, `…/services/mcp/AssistantMcpTools.java` (the tenant and the MCP key's id only
     from the key's authentication - `TenantContext.mcpKeyId`), `…/services/mcp/KnowledgeMcpTools.java`, `…/services/mcp/SoftwareMcpProxy.java` and the "__" branches of
     `…/services/mcp/BrandedMcpServer.java` (connected software passed through), the `/mcp` entry of the intercept-url-map; the scheduled tasks on a key and on the MCP server:
     `…/controller/api/v1/TaskApiController.java`, `…/services/task/TaskService.java` (`assignApiKey`, `keyTask`,
     `tasksOfApiKey`, `tasksOfMcpKey`, `assignMcpKey`, `findings`, `handedOn`), `…/services/mcp/TaskMcpTools.java`;
     the A2A door (a2a.md "The server"): `…/controller/a2a/{A2aController,A2aExtensionController}.java` (the key's
     tenant and id into the SDK's call context, `params.tenant` ignored, the extended card answered here, `origin` from
     `X-Forwarded-Proto`), `…/services/a2a/{AssistantAgent,A2aCards,JdbcTaskStore,KeyOwnsTask,JdbcPushConfigStore,PushAddresses,A2aTaskCleanupJob}.java`,
     `…/repositories/a2a/**`, the `/a2a` entry of the intercept-url-map, `db/migration/V17__release_2_0_0.sql`
     (`mcp`, `a2a`, `a2a_task_index`), `db/tenant/V8__release_2_0_0.sql` (`a2a_task`, `a2a_push_config`)
   - frontend: `components/shared/api-keys-panel/**`, `components/shared/mcp-key-panel/**`, `store/mcp-key/**`
   - Special care: key generation (randomness, length, prefix), only hash and display prefix stored, the plain key
     returned once and not kept in the store; lookup off the event loop; unknown key and inactive tenant 401; a deleted
     key stops at once; an `ai_` key never reaches `/tenant/**` or `/admin/**`, an MCP key only `/mcp`, a JWT not
     `/mcp`; **several MCP keys per tenant** (mcp.md "MCP keys"): each answers only with its own areas and web search -
     a key never reaches another key's areas, another tenant's key id answers 404 on change, renew and delete, renewing
     ends only that key's old secret, deleting ends it at once with its areas; the plain key of a new or renewed key
     shown once and dropped when the page is left; **the key's software** (`tenant_mcp_key_connection`, mcp.md
     "Connected software through to MCP clients"): a key reaches only the connections given it - through
     `ask_assistant` (`McpTools.forMcpKey`) and passed through -, passed through only where the
     connection is switched on; **changing tools only passed through and only by the key's level** (roadmap point 68,
     mcp.md "Changing tools passed through"): `tenant_mcp_key_connection.level` `WRITE` per key and software
     ("Darf ändern in", off for every key, refused for a software the key may not use, that does not pass through or
     is an A2A agent), the tool marked `readOnlyHint: false`; whether a person is asked is the MCP client's matter
     (Claude Code: at a tool's first use, nothing in auto mode, with bypassed permissions or an allow rule) - nothing
     else stands between a prompt injection the client read and the change, and SECURITY.md must say exactly that; `ask_assistant`, `/a2a` and the API keys
     give the tenant's model reading tools only; the level falls back when the software stops passing through; every
     changing call logged (key, tool, software - no arguments, the names without control characters); a name the key
     does not have (another key's, another tenant's, a changing tool without the level, a tool
     turned off) is unknown, list and call computed alike; the software's address and key never in an answer; another
     tenant's connection id on a key 400; its instructions, descriptions and prompts are foreign text to both models;
     the client software's API key likewise only the connections given it (`tenant_api_key_connection`,
     `McpTools.forApiKey`, `apiKey.unknownConnection`), another key of the tenant none, reading tools only; `search_knowledge` / `read_document` stateless: every read checks
     the key's areas again by the document's id - another area's, a deleted, moved or not yet embedded document and an
     id of another tenant's database all answer the same "not found" (no oracle); what goes to the client (passages,
     paths with their folders, document ids) is no more than the key finds; the claude.ai / Cowork connector sends
     the key as a fixed `authorization` header - the same `/mcp` rule, nothing else reached; `/api/v1` offers exactly what CLAUDE.md "Tenant API" decided - nothing more; no entity reachable from a
     tenant controller; the client software's conversations (`user_id` NULL, `api_key_id` its key) and a user's never mix, nor one key's with another's (they may quote areas only that key finds); the API's chat
     without history stores nothing but usage; the API's chat only uses the reading tools a tenant admin opened to the
     client software; `@Hidden` on everything outside `/api/v1` and the users' routes published next to it since 2.0.0
     (`/login`, `/login/wam`, `/branding`, `/tenant/account`, `/tenant/branding`, `/tenant/chat` - the list in
     `TenantApiKeySpec`; nothing of `/admin`, `/tenant/admin`, `/tenant/ai`); `/api/v1/tasks` lists and starts only the tasks a
     tenant admin assigned to the key (404 for any other; `apiKey.taskUnknown` for another tenant's task put on a key),
     `run_task` / `task_run` only the tasks assigned to the MCP key (another key of the tenant none; `mcpKey.unknownTask`
     for another tenant's task put on a key), and both give back the findings and the answers
     handed on - never the lines read (tasks.md "Starting without a person"). **The A2A door** (a2a.md "The doors of a
     key", "The server"): a key opens `/a2a` only with its `a2a` switch and `/mcp` only with `mcp` (401 elsewhere, also
     below a context path; at least one door, `mcpKey.noDoor`; existing keys MCP only); at `/a2a` the key reaches
     exactly what it reaches at `/mcp` (areas, software, web search, its tasks) - never more, `params.tenant` and a
     `taskId` of another key ignored or `TaskNotFoundError`; the public card names no tenant and no brand, the extended
     card only with the key; a stored task (a streamed or `returnImmediately` question, a run started over A2A), its
     artifacts and its webhooks belong to the key that started them (`KeyOwnsTask`: another key of the same tenant
     `TaskNotFoundError`, the same for another tenant's and an unknown id - no oracle), deleted after the retention and
     with the tenant; the question itself is never stored; a refusal is a JSON-RPC error with our message, never the
     provider's text or an internals-naming parser message; a webhook only to a public HTTPS host (`PushAddresses`, no
     exemption for listed hosts, resolved again before every delivery), its config encrypted, at most 5 per task; an
     `ai_` key or a JWT 403 at `/a2a`.
   - Specs: `security.TenantApiKeySpec`, `controller.api.v1.ChatApiSpec`, `DocumentApiSpec`, `controller.mcp.McpSpec`,
     `controller.mcp.McpKnowledgeSpec`, `controller.a2a.A2aServerSpec` (the doors, another key's task, webhooks),
     `controller.common.ContextPathSpec` (the doors under a sub-path), `services.task.TaskStartSpec`,
     `security.SignInBesideBusyMcpSpec` (long-running MCP tools never hold up the sign-in checks of other requests).
4. **Geheimnisse & Logs**
   - `…/security/SecretCipher.java`, `…/services/ai/{ProviderKeyService,ResolvedKey,AiModels}.java` (only where keys and
     errors are handled), `…/controller/ai/{ProviderKeyController,GlobalProviderKeyController}.java`,
     `…/controller/tenant/TenantProviderKeyController.java`, `…/dto/ai/ProviderKey*.java`, `…/dto/mcp/McpConnection*.java`
     (the connection key), `…/services/filestore/FileStoreService.java` and `…/dto/filestore/**` (the store's password),
     `…/services/task/TaskMails.java` (what a task's mail carries), `…/entities/oauth/OAuthGrant.java`,
     `…/services/oauth/OAuthClient.java` and `…/dto/oauth/GrantView.java` (a connection's OAuth sign-in),
     `…/controller/common/GlobalExceptionHandler.java`, `Backend/src/main/resources/logback.xml`,
     the application log and the daily report (monitoring.md): `…/logging/RecentLogAppender.java`,
     `…/services/monitoring/{ApplicationLogService,DailyReportJob,DailyReportMail,DailyReport}.java`,
     `…/repositories/monitoring/DailyReportRepository.java`, `…/controller/monitoring/**`,
     `…/mail/{MailSender,MailTemplate}.java`, `app.mail.*` and `app.monitoring.*` in `application.properties`,
     every log call: `grep -rn "LOG\.\|log\.\(info\|warn\|error\|debug\)" Backend/src/main/java`
   - Deliver the **inventory of decrypted secrets**: every caller of `SecretCipher`'s decryption
     (`grep -rnE "\.decrypt\(" Backend/src/main/java`), one row each - `Class.method` (path:line) · which secret ·
     where the plain value goes from there (which client, header, host) · how long it is held (a field, a cache, a
     local variable) · whether it can reach a response, a log line or an exception message · verdict ✅ / ⚠️ / ❗. A
     caller this skill names nowhere goes into "Not described" too.
   - Special care: AES-GCM use (fresh nonce per encryption, key derivation from `APP_SECRET_KEY`, failure on a wrong
     key); no secret and no document or chat content in a response, a log line, an exception message that reaches
     `ErrorResponse`, or a provider error passed through; `keyHint` cannot reveal the key; what goes to a provider
     matches [docs/privacy.md](../../../docs/privacy.md); a file store's password encrypted like a provider key and never
     in `FileStoreResponse`, a check's answer, a `fileStore.*` message or a log line; the lines a task's run read never
     in the log, and in a mail only to the recipients the task names; **a connection's OAuth sign-in**: access token,
     refresh token, a registered or entered client secret and the PKCE verifier encrypted with `SecretCipher`, never in
     `GrantView` (status, scopes, expiry, `last_error`, the server's host, the client id's source, our redirect address
     only), a log line, an `oauth.*` message or the daily report; `last_error` never a server's own text; a refused
     renewal drops the tokens; tokens no longer decryptable (`APP_SECRET_KEY` changed) ask for a new sign-in, never leak
     or fail open; **the application log and the daily report** (monitoring.md): the log buffer is every WARN and
     ERROR line again - on the admin's page and, the twenty newest with the exception's first line, in a mail that
     leaves the installation - so what must not be in a log line must not be there either; `RecentLogAppender` keeps
     flat entries (formatted message, stack trace capped), never the event with its arguments and MDC, bounded
     (`maxSize`); the report carries counts, sizes, amounts and the tenants' names only - no document or file name, no
     question or answer, no person's name (what docs/privacy.md says is mailed) - and goes only to
     `app.monitoring.mail-to`, checked at startup; every value in the mail HTML-escaped (`MailTemplate`: only a filled
     block inserted as it is - a tenant's name or a log line is text an outsider can shape); both routes
     (`/admin/application-log`, `/admin/daily-report/send`) the admin's only, never a tenant admin's; the SMTP
     password from the environment, in no response or log line. E08 is decided.
   - Specs: `controller.ai.ProviderKeySpec`, `controller.common.GlobalExceptionHandlerSpec`, `controller.mcp.McpConnectionSpec`,
     `McpConnectionSignInSpec`, `services.oauth.OAuthClientSpec`, `security.SecretCipherSpec` (a fresh IV per value,
     another `APP_SECRET_KEY` or a changed value refused), `controller.common.ErrorCodeSpec` (what an error answer
     carries), `controller.monitoring.ApplicationLogSpec`, `DailyReportControllerSpec`,
     `services.monitoring.DailyReportJobSpec` (escaping, the log block), `DailyReportSpec`,
     `logging.RecentLogAppenderSpec`.
5. **Eingaben & Dateien**
   - `…/services/document/{DocumentArchive,DocumentTextExtractor,DocumentService}.java`, `…/controller/document/DocumentUploads.java`,
     `…/services/chat/{ChatFileService,ChatFileReader,MediaConverter,AudioTranscriber,SentFiles,FileRenderer}.java`,
     `…/controller/chat/ChatFiles.java`, `…/storage/{FileStorage,LocalFileStorage}.java`,
     `…/controller/branding/Logos.java`, `…/services/branding/BrandingService.java`, `…/controller/common/{SpaFallbackController,GlobalExceptionHandler}.java`,
     the handbook: `…/services/docsite/{DocSiteService,DocSiteTokens}.java`, `…/controller/docsite/**`,
     `…/repositories/docsite/DocSiteRepository.java`, `…/security/ResponseHeaders.java` (the websites' own CSP),
     the size limits `micronaut.server.*` and `app.*` limits in `application.properties`,
     the knowledge sources' reading: `…/services/document/{SourceSync,KnowledgeSourceService}.java`
   - frontend: `utils/markdown.util.ts`, `components/chat/**` (every `[innerHTML]`), `utils/download.util.ts`,
     `utils/branding.util.ts`, `utils/doc-site.util.ts` (`isDocSiteUrl`), `components/shared/doc-sites-panel/doc-site-frame.component.ts`,
     `grep -rn "bypassSecurityTrust\|innerHTML" Frontend/src/app`
   - Special care: ZIP (path traversal `..`, absolute paths, entry and total size, entry count, nested archives),
     limits checked **before** reading into memory; type recognised by content, not by name; the ffmpeg call (argument
     list vs. shell, file names in arguments, timeouts, temporary files removed); storage keys never built from a user's
     file name (traversal); a downloaded file's name and content type in the response headers (header injection,
     HTML served inline from our origin); the logo (SVG with script, size, type); the context path written into
     `index.html`; Markdown from the model and from documents rendered only through Angular's sanitizer, links other than
     http(s)/mailto stay text, no images or media the browser loads by itself.
     - **videos** (code-execution.md "Videos"): a part above 50 MB only as a video recognised by its first 64 KB, streamed
       to the storage and never whole in memory, `app.chat.video-max-size` checked while it streams (not by the declared
       size alone), a broken-off upload leaves no file behind; only for a tenant with a sandbox entered.
     - **the handbook's websites** ([doc-sites.md](../../../docs/architecture/doc-sites.md) "Security"): the uploaded HTML
       and JavaScript never run as the app's origin - every file answered with `Content-Security-Policy: sandbox ...`
       without `allow-same-origin` (also a file opened in a tab of its own, also an error answer of the route), the iframe
       sandboxed with `no-referrer`; `ResponseHeaders` keeps `frame-ancestors 'none'` for every other answer; the token
       (HMAC under a key derived from the JWT secret, compared in constant time, tenant, site and expiry bound) - a forged,
       expired or another site's token, a deactivated tenant, a deleted site 404; the requested path (`..`, hidden
       segments, backslash, leading slash, percent-encoded forms of them, the redirect of a folder staying relative); the
       ZIP's limits (100 MB, 2000 entries, 500 MB unpacked) checked before unpacking; the `null` origin's CORS only for
       these files, GET, no credentials; another area's website 404 like an unknown one.
     - Deliberately so (doc-sites.md, not findings): an address handed out stays valid up to an hour after the user was
       taken out of the area or deactivated; the website's scripts run (in the sandbox); a tab it opens leaves the sandbox.
     - **a knowledge source's files** ([knowledge-sources.md](../../../docs/architecture/knowledge-sources.md)): a file's
       content is written by whoever writes to the store - read like an upload (type by content, the same limits): a path
       over 500 characters or a file over 50 MB refused from the listing, unread; a file grown since it was listed read
       at most a byte beyond the limit, never without end; the walk bounded (20 levels, 20 000 files), hidden names and
       `.`/`..` never walked into; the source's folder relative and below the store's (`KnowledgeSourceService.folder`,
       `FileStoreService.folderBelow` - `..`, `.`, control characters, doubled slashes), the pattern a file name with
       `*` and `?` only, matched without a regular expression (`NamePattern` - no backtracking that holds a thread); a
       file's path is stored as the document's folder and name, never a storage key.
   - Specs: `controller.document.DocumentArchiveSpec`, `services.document.DocumentArchiveFilesSpec`,
     `DocumentTextExtractorSpec`, `controller.chat.ChatFileSpec`, `ChatMediaSpec`,
     `services.chat.MediaConverterSpec` (a recording too long refused before it fills `/tmp` and the heap; needs
     ffmpeg), `controller.branding.BrandingSpec`, `BrandingDefaultsSpec`, `controller.common.InvalidRequestUriSpec`, `ContextPathSpec`,
     `controller.chat.ChatVideoSpec`, `services.chat.ChatUploadLimitsSpec`, `controller.docsite.DocSiteSpec`, `DocSiteLimitSpec`; frontend
     `utils/markdown.util.spec.ts`, `utils/doc-site.util.spec.ts`.
6. **Modell-Server & angebundene Software (MCP)**
   - `…/services/ai/{ModelServers,BedrockClients,TeiScoringModel}.java` (Bedrock: the SDK takes nothing of the
     server's environment, signs with the entry's access key for the region of the entered address - checked like
     S3's client), `…/services/ai/AiModels.java` (only where a client is built for an address and `rerank`),
     `…/services/mcp/{McpAddresses,McpClient,McpConnectionService,LinkDownloads}.java`,
     `…/services/common/InternalAddresses.java`,
     `…/controller/mcp/{TenantAdminMcpConnectionController,McpConnectionAdminController}.java`, `app.ai.*` in
     `application.properties`
   - Special care: SSRF - a tenant admin's model-server, MCP or A2A address (internal addresses, DNS resolving to
     internal ones, redirects, plain HTTP, user info/query/fragment) checked on save **and** before every call, the key
     sent only to its origin; an A2A agent's address and a connection's OAuth sign-in are part 11, what a
     software's or a web page's text can make the model do part 12; - **a file a tool returns as a link** (mcp.md "Files returned as
     a link", `LinkDownloads`, called from `McpClient.callTool`): the address is the software's choice, not the tenant
     admin's - a request our server makes to wherever a tool result points (SSRF): http(s) only, no user info,
     `McpAddresses.requireAllowed` before the request **and after every redirect** (followed by hand, at most 3 - the
     JDK client itself follows none), resolved when it is checked (E06 for the connection's own address - does its
     reason hold for an address a tool result names?); **our bearer - the connection's key or OAuth token - only while
     the address is on the connection's own origin** (scheme, host and port, also after a redirect away and back),
     never to a download host elsewhere; the body cut at 50 MB while it streams and the whole download at 60 s, so a
     server answering without end holds neither memory nor a thread; only as many links as the answer's files allow
     are fetched, none of a failed call; a refusal's reason reaches the model as a note and names no internal address
     or resolved host beyond what `McpAddresses` says for a connection; a uri of another scheme only through
     `resources/read` in the same session; the fetched file handled like an embedded one (the returned-file rules -
     a type from the fixed list, offered as a download only); the same way in the chat, a task's documents source
     and the proxy, which hands a client the content, never the link (SECURITY.md names the rule); - the reranker's TEI
     server too (`TeiScoringModel` through `AiModels`, a catalog entry like Ollama:
     no redirect followed, plain HTTP only to `app.ai.plain-http-hosts`; it gets the question and only the candidates
     of the asker's scope, answers scores only - no text of it reaches the model).
   - Deliver the **inventory of outgoing connections** (see "Own report sections"): every place the server opens a
     connection of its own. Start from
     `grep -rlE "HttpClient\.newBuilder|HttpClient\.newHttpClient|new Socket|SshClient|FTPSClient|S3Client\.builder|Session\.getInstance|\.getStore\(|Transport\.send|BedrockRuntime|ConfidentialClientApplication" Backend/src/main/java`
     - it is a start, not the list: libraries open connections of their own (langchain4j's model clients built in
     `AiModels`, Tavily's engine in `TavilySearch`, the A2A SDK's client and whatever sends a task's webhook, MSAL,
     the MCP SDK's transport), so add every one the reading shows. The agents of parts 4 (mail), 8 (sandbox), 9
     (file stores), 11 (A2A agents, OAuth) and 12 (the web search) fill in the rows of their classes; without those parts their rows say "nicht geprüft". A place
     this skill names nowhere goes into "Not described" too.
   - Specs: `services.mcp.McpClientSpec` (a linked file: the bearer per origin, every refusal, the size),
     `McpClientTimeoutSpec`, `controller.mcp.McpConnectionSpec`, `McpProxySpec`, `services.ai.ModelServerSpec`.

7. **Wissensbereiche & Gruppen** (knowledge areas - ai-features.md "Knowledge areas"; user groups - tenancy.md "User
   groups" and "Sign-in through a WAM proxy" (IDM groups))
   - `…/services/document/{KnowledgeScope,KnowledgeAreaService,KnowledgeSearch,TenantEmbeddings,DocumentService}.java`,
     `…/repositories/document/{KnowledgeAreaRepository,DocumentRepository}.java`, `…/services/tools/{ToolContext,SimilaritySearchTool,Toolbox}.java`,
     `…/services/chat/ChatService.java` (where every `ToolContext` is built: `prepare`, `ask`, `answerOnce`, `decide`),
     `…/services/mcp/AssistantMcpTools.java`, `…/controller/api/v1/{DocumentApiController,ChatApiController,TenantSelfController}.java`,
     `…/controller/document/**`, `…/controller/mcp/{TenantAdminMcpKeyController,McpKeyAdminController}.java` (`/areas`),
     `…/security/{ApiKeyTokenValidator,TenantContext,Roles}.java` (`API_KEY_ID_ATTRIBUTE`),
     `…/services/tenant/{TenantUserService,ApiKeyService}.java` (assigning areas and groups), the migrations
     `db/migration/V2__knowledge_areas.sql`, `V7__user_groups.sql` and `db/tenant/V2__document_area.sql`
   - user groups: `…/services/tenant/UserGroupService.java` (the only place that reads a user's rights),
     `…/entities/tenant/UserGroup.java`, `…/repositories/tenant/UserGroupRepository.java`, `…/dto/tenant/UserGroup*.java`,
     `…/controller/tenant/{UserGroupAdminController,TenantAdminUserGroupController}.java`, the access list by group of
     `…/controller/mcp/{TenantAdminMcpConnectionController,McpConnectionAdminController}.java`,
     `…/services/mcp/{McpTools,SoftwarePrompts}.java` (the level per software), `…/security/{ActiveTenantUserRule,WamProxy}.java`
     (the IDM groups header, `tenant_user_wam_group`)
   - frontend: `components/shared/document-panel/**`, `store/knowledge-area/**`, `utils/knowledge-area.util.ts`,
     `components/shared/user-groups-panel/**`, `store/user-group/**`, `utils/user-group.util.ts` - only to confirm the
     frontend enforces nothing: every rule must hold in the backend.
   - Special care - **no one gets an answer from an area they are not assigned to**:
     - **every way to a document's text goes through the asker's `KnowledgeScope`**: list every caller of
       `TenantEmbeddings.searchVectors`/`searchText`/`langchain4jHybridStore`, `KnowledgeSearch.search`,
       `DocumentRepository.findText`/`findAll`/`findById` and of anything else that returns chunk or document text
       (`grep -rn "searchVectors\|searchText\|langchain4jHybridStore\|knowledge.search\|findText(" Backend/src/main/java`) -
       a path that reaches text without a scope, or with `KnowledgeScope.everything()` for someone who is no tenant
       admin, is **Kritisch**;
     - **the scope comes from the server, never from the request**: a user's from the database (`tenant_user.admin` and
       the areas of its groups through `UserGroupService` - `group_area` of the groups in `user_group_member`; not the
       JWT's role - a former tenant admin would keep "everything" otherwise; `tenant_user_area` is gone since V7), a key's from
       the key id its authentication carries - an API key's and an MCP key's alike (`scopeOfMcpKey`, never the
       tenant's); every `new ToolContext(...)` passes the
       asker's scope - the chat stream, a decided pending action, `/api/v1/chat` with and without history,
       `ask_assistant`, `search_knowledge` / `read_document` (`KnowledgeMcpTools`, no `ToolContext`), a model without tools (the application searches for it), later sub-agents and workflows;
     - **the SQL filter**: both searches join the chunk to its `document` row and filter on `area_id IS NULL OR area_id =
       ANY(?)`; the parameter order with the array; a chunk without a document row is found by no one; langchain4j's
       hybrid mode is filtered after it searched; the iterative scan does not widen the result beyond the scope;
     - **areas are the tenant's**: an area id of another tenant is refused when assigning it to a user, an API key or an MCP key
       and when uploading, importing or moving into it, or as a knowledge source's area (and a source's store another
       tenant's: 400; its source, files and runs another tenant's: 404); renaming or deleting another tenant's area is 404; a
       name at `/api/v1/documents` is resolved within the key's tenant only;
     - **the client software writes only where it reads**: a push into an area the key is not assigned to (400), a
       known `externalId` in such an area (409 - no replacing or moving it out), list and deletes limited to what the
       key finds (404 otherwise), `GET /api/v1/me` names only its own areas;
     - **what leaves with an answer**: the sources (file names) and the streamed `sources` event come only from the
       scoped search; a created file or a tool result cannot carry another area's text into the answer;
     - **deleting**: `DELETE .../documents/uploads` deletes only uploads of the given area of the caller's tenant;
       deleting an area that holds documents is refused - a race that leaves a document with a deleted area must not
       make it company knowledge (it stays for tenant admins only); an area a knowledge source writes into is not
       deleted either (409 `knowledgeArea.usedBySource`), and a source writing into another area moves only its own
       documents still in the old one.
     - **a group's rights reach its members only** - the union of its areas and the highest level per connected software
       of the user's groups, nothing else: no right per user beside the groups; a group, member, area or connection of
       another tenant refused (400 for a user or area, 404 for a group, also `groupIds` on a user); a group renamed
       keeps its WAM group; a deleted group, user, area or connection leaves every group (`ON DELETE CASCADE`);
     - **a group with a WAM group takes its members from the IDM alone** (2.0.0, roadmap point 51; before: assigned
       and listed): it counts for every user of its tenant whose stored IDM groups name it and for nobody else
       (`UserGroupRepository.GROUPS_OF_USER` / `MEMBERS_OF_TENANT`, every right read through them); members sent for
       it 400 `group.membersFromIdm`, a WAM group set drops the assigned rows, a user's `groupIds` pass over it; **only
       the admin sets, changes or removes a WAM group while WAM is on** (409 `group.wamGroupByAdmin` for a tenant admin
       - it gives access now); **accounts from the IDM** (`AccessRequestService.accountsFromIdm`): a person the header
       lists in such an IDM group gets an active user without a password in every active tenant with such a group, an
       existing user untouched, only with WAM on and a groups header configured, never in a deactivated tenant;
     - **the IDM check fails closed**: a request without the header lists none, no groups header configured means a
       group with a WAM group counts for nobody and creates no account, the header counts for nothing without WAM; the stored list (`tenant_user_wam_group`) written under the user's lock
       (never a mix of two requests) and deleted when the app starts without the check; split, trimmed and compared
       whatever the case exactly as tenancy.md says (a DN with commas, an empty element);
     - **every reader of a right asks `UserGroupService`**: the search scope, `McpTools` and `SoftwarePrompts` (the
       software's tools and prompts), a decided pending action re-checked against the level at that moment -
       `grep -rn "group_area\|group_mcp_access\|user_group_member" Backend/src/main` finds nothing outside
       `UserGroupRepository` and `V7__user_groups.sql`.
     - Deliberately so (ai-features.md "Knowledge areas", tenancy.md "User groups", not findings): tenant admins find
       every area, their software comes from their groups like anyone's; answers given before an area was taken away
       stay in the conversation; an answer already running keeps its tools after a change of the groups or the IDM;
       every tenant's group "Standard" without rights, and that a user who is no tenant admin gets in only through a
       group (2.0.0, `security.GroupAccessSpec`, `WamGroupsSpec`; tenant admins need none); the client software's
       documents survive "delete uploads".
   - Specs: `controller.document.KnowledgeAreaSpec` (search per user, tenant admin, API key with and without history,
     MCP server; moving; deleting; the API's rules), `security.TenantApiKeySpec` and `TenantAdminSpec` (roles of the new
     routes), `controller.document.KnowledgeSourceSpec` (isolation, a store and an area in use), `services.document.KnowledgeSearchSpec`, `controller.tenant.UserGroupSpec` (API, isolation, highest
     level and union, cascades, members of a group with a WAM group refused), `security.WamGroupsSpec` (membership by the
     header alone, accounts from the IDM, the WAM group the admin's); the smoke test's step 4b (`.github/scripts/smoke-test-jar.sh`,
     the area filter in the AOT jar).

8. **Sandbox** (code execution - [code-execution.md](../../../docs/architecture/code-execution.md))
   - backend: `…/services/sandbox/{Sandboxes,SandboxSession,SandboxFile}.java`,
     `…/services/tools/{RunPythonTool,RunShellTool,CodeSessions,ToolContext,Toolbox}.java` (offering the tools, the
     session's files, `closeSandbox`), `…/services/chat/ChatService.java` (only where the tools are offered and
     `closeSandbox` is called on every path), `…/services/tenant/TenantService.java` (the sandbox addresses and token:
     validation, 409 for an address of another tenant, encryption), `…/controller/tenant/TenantController.java`
     (`/admin/tenants/{id}/sandbox`), `…/dto/tenant/{TenantRequest,TenantResponse,SandboxStatusResponse}.java`,
     `app.sandbox.*` in `application.properties`
     - **a tenant's own image** (code-execution.md "Your own image"): the text of `/opt/extras/README.md` goes from
       `/health` (`extras`, cut at 16 KB) verbatim into the system prompt (`ChatService.SANDBOX_README_HEADING`) - a
       prompt-injection surface that belongs to the operator who builds the image (the same trust as the sandbox token
       and `SANDBOX_IMAGE`); check that nothing else of the container reaches the prompt, that the limit holds on
       both sides (`sessions.read_extras`, `Sandboxes.fetchHealth`), that `/health` stays token-free and session-free,
       and that the text is given only when every instance of the tenant agrees (`Sandboxes.extras`)
   - the sandbox itself: `Backend/podman/sandbox/{Dockerfile,app.py,sessions.py,confine.py,kernel.py,selftest.py}`,
     `Backend/podman/public/{docker-compose.tenant-sandbox.yml,docker-compose.prod.yml}` (the internal network, read-only
     root, user, capabilities), `Backend/podman/docker-compose.sandbox.yml` (local only), the `sandbox` job of
     `.github/workflows/pr-checks.yml`
   - frontend: `components/tenant-list/tenant-dialog.component.ts`, `utils/tenant.util.ts` - only to confirm the token
     is never shown again and every rule holds in the backend.
   - Special care - **the code the model runs is the attacker** (a content author steers it through a document, a chat
     file or a tool result; a tenant user writes it outright):
     - **tenants apart**: a tenant's session goes only to its own instances with its own token (the tenant from the
       asker's `ToolContext`, never from the request); an address can never belong to two tenants (also changed case,
       trailing slash, another spelling of the same host); a lease is never handed to another tenant's answer;
     - **users of one tenant apart**: one session per instance; at its end - `DELETE`, idle, lifetime, a failed or
       stopped answer, a logout - every process of it killed and every writable place emptied before the next session
       starts; nothing a session leaves (a file, a process, an IPC object, an environment variable, the kernel's
       namespace) reaches the next one;
     - **the token**: 32+ characters, compared in constant time, stored encrypted and never in a response, a log line or
       an error message; the code cannot read it (server not dumpable, not in an environment the code inherits, not in
       a file the code can open);
     - **confinement** (`confine.py`): what the seccomp filter denies and what it lets through (sockets, io_uring,
       `ptrace`, signals to PID 1, `setsid`, `unshare`, IPC), the rlimits, `no_new_privs`; the server killing a call
       after its timeout including its children;
     - **the server's input**: the request size refused before it is read, the session id and file names (path
       traversal out of `data/`, `..`, absolute paths, links), base64 decoding, output cut before it fills memory;
     - **the app's side**: the files sent (only the asker's: the question's and that conversation's files, never
       another user's), the output given to the model bounded (`CodeSessions.MAX_OUTPUT`), a sandbox's answer never
       trusted as anything but text (no redirect followed with the token, no status turned into a role or id);
     - **the admin's check** `GET /admin/tenants/{id}/sandbox`: admin only, the tenant must exist, it reaches only
       addresses the admin entered (validated on save: http(s), host, no credentials, query or fragment).
     - Deliberately so (code-execution.md "Known weaknesses" and "Isolation without privileges", decided 11 October
       2026 - not findings unless their premise no longer holds): the seccomp filter is a deny list, the code sees the
       container's file system, no Landlock, no gVisor, plain HTTP inside the internal network, one answer per
       instance, the memory limit per program, nothing records what ran, the published local token of
       `docker-compose.sandbox.yml`.
   - Specs: `controller.chat.CodeExecutionSpec`, `controller.tenant.TenantSpec` (sandbox cases),
     `services.sandbox.SandboxesSpec`, `services.tools.ToolboxSpec`; `Backend/podman/sandbox/selftest.py` is not run
     by this review (it needs the image) - its cases are read and named.

9. **Dateiablagen** (file stores - [file-stores.md](../../../docs/architecture/file-stores.md); part 6 until November 2026)
   - `…/services/filestore/{FileStoreAddresses,FileStoreService,FileStoreClient,SftpStoreClient,FtpsStoreClient,ServerSettings,S3StoreClient,S3Settings,GraphStoreClient,GraphAddresses,GraphSettings,MailboxStoreClient,MailboxSettings,FileStoreSession,StoreFailures,NamePattern}.java`
     (`write` per type, `plainAllowed`), `…/services/common/InternalAddresses.java`, `…/controller/filestore/**`,
     `…/dto/filestore/**`, `…/repositories/filestore/**`, `…/entities/filestore/**`,
     `Backend/src/main/resources/logback.xml` (MSAL's own log held to errors), `app.file-store.internal-hosts` in
     `application.properties` and both compose files
   - frontend: `components/shared/file-stores-panel/**`, `utils/file-store.util.ts` - only to confirm a password or
     secret is never shown again and every rule holds in the backend.
   - Special care - **the store's server, and whoever writes to the store, is the attacker**; a tenant admin enters
     the address:
     - **file stores** ([file-stores.md](../../../docs/architecture/file-stores.md)): the host checked on save and resolved
       again before every connection (`FileStoreAddresses`), an internal address only on the operator's list
       `APP_FILE_STORE_INTERNAL_HOSTS` - never `APP_AI_PLAIN_HTTP_HOSTS`; the password goes out only after the server's
       key (SFTP) or certificate (FTPS: or one an authority the installation trusts vouches for, host name checked)
       matched the confirmed fingerprint; another type, host or port drops the fingerprint and needs the password
       again; FTPS never plain FTP, its data connections only to the control connection's address; a task's folder
       stays below the store's (`folderBelow`).
       - **S3** (file-stores.md "The clients" - `S3StoreClient` -, "Addresses of S3 stores"): credentials only from the store
         (`StaticCredentialsProvider`, an empty profile file, region and endpoint always set) - never the default chain
         with the server's environment, `~/.aws` or an instance role, which would read with the operator's rights; the
         endpoint a server address only (no user, path, query, fragment), checked against the internal networks on save
         and before every session, plain `http://` only to a host in `APP_FILE_STORE_INTERNAL_HOSTS`; another endpoint,
         region, bucket or access key needs the secret again; no retry. Deliberately so (SECURITY.md, not a finding):
         the SDK's client resolves the name once more when it connects (rebinding) - the secret never goes over the wire.
       - **SharePoint / OneDrive** (file-stores.md "The clients" - `GraphStoreClient`): the client secret goes to Microsoft's
         login only and the token to Graph only - both addresses fixed in `GraphAddresses`, not a setting; no redirect
         followed (it would take the token along); a listing's next page followed only on Graph itself; a file's
         download address fetched without the token and only over HTTPS from a `*.sharepoint.com` host; the site's
         address only `https://<name>.sharepoint.com/...` (no other host or port, no user, query, fragment or `..`);
         another directory or app needs the secret again; Entra's refusals named by their code, never the secret or the
         token in a message or log line (MSAL logs at ERROR only); JSON answers bounded (16 MB).
       - **choosing a folder** (`GET .../file-stores/{id}/folders`): only a ready store is connected to; the path stays
         below the store's folder; at most 500 names, shown and never stored; another tenant's store 404.
       - **a store a knowledge source reads** is neither deleted nor pointed at another server, folder or library
         (409 `fileStore.usedBySource`) - a source would otherwise read other files and delete its documents.
       - **IMAP mailbox** (file-stores.md "Mailbox"): read only - `Folder.READ_ONLY`, nothing marked, moved or
         deleted, a task never writes into it (400 `fileStore.mailboxReadOnly` on the switch); SSL or STARTTLS with the
         server's identity checked, plain IMAP (`NONE`) only to a host in `APP_FILE_STORE_INTERNAL_HOSTS` - checked on
         save and again at every open; the password encrypted like the others, another host, port, user or security
         needs it again; an attachment's name sanitised (`/`, `\`, dot entries, `mail.txt` reserved) so no name of a
         sender's choosing reaches a path; HTML of a mail stripped of script and style before it becomes `mail.txt`.
       - **writing into a store** (file-stores.md "Writing", tasks.md "write_file"): only with the store's switch, which
         a tenant admin or the admin sets - a task's text alone never makes a store writable; the file's path from
         `FILE_PLACEHOLDER` only (`{yyyy}`, a date field's parts, a choice's word `[a-z0-9_-]`) below the store's
         folder - never a value the model extracted; cells beginning with `=`, `+`, `-`, `@` guarded with `'` so a
         value the model took out of a document never runs as a formula in a spreadsheet program; the password
         never in the failure a tenant admin sees.
   - Specs: `controller.filestore.FileStoreSpec`, `FileStoreFoldersSpec`, `SharePointStoreSpec` (against
     `FakeMicrosoft`), `MailboxStoreSpec` (against GreenMail IMAP: plain IMAP refused, read only, a task cannot write),
     `services.filestore.S3StoreClientSpec`, `FtpsCertificateCheckSpec`, `FtpsListingBoundSpec`
     (`FileStoreServerSpec` - SFTP, FTPS and S3 - runs only with `FILE_STORE_TEST` - named, not run).

10. **Aufgaben** (scheduled tasks - [tasks.md](../../../docs/architecture/tasks.md); part 6 until November 2026)
   - `…/services/task/{TaskCompiler,Condition,TaskService,TaskRunner,TaskDraftService,TaskMails}.java`
     (`FILE_PLACEHOLDER`, `withWritableStore`, `withTool`), `…/services/task/step/{ReadFilesStep,LineMasks,AgentStep,PromptStep,TaskFindings,WriteFileStep,CsvRows,CallToolStep,CallAgentStep,ToolArguments,DocumentReader,ExtractStep,ExtractedRow,SendMailStep}.java`,
     `…/services/tools/{Toolbox,ToolContext}.java` (`onlyTools`, `allows`, `Asker.TASK`),
     `…/services/mcp/McpConnectionService.java` (`toolForTask`, `callForTask`, `agentForTask` - a task's way to a
     tool or an agent, without a person), `…/controller/task/**`, `…/controller/api/v1/TaskApiController.java` and
     `…/services/mcp/TaskMcpTools.java` (only who may start what - the keys themselves are part 3), `app.task.*` in
     `application.properties`
   - frontend: `components/task-run/**`, `utils/task.util.ts` - only that what a run read or a model answered is
     shown as text (no `[innerHTML]`, no link built from it).
   - Special care - **a task runs without a person**: nobody is there to refuse a changing call, and what it reads
     is written by others (log lines, documents, a software's answer, a mail):
     - **scheduled tasks** ([tasks.md](../../../docs/architecture/tasks.md)): the text is data - unknown fields refused,
       conditions only counts and choices, no anchors, nothing of it ever an expression, a shell argument or a path;
       a step's model reads the lines of a server a content author writes to (log lines are prompt injection) and has
       only `search_file` and `read_file_lines` - no software tool, no company knowledge, no file created; the
       structured result's evidence lines checked against the lines really read; a run starts only with the admin's
       switch, from outside only with a key the task is assigned to or through the MCP server when opened to it; a
       draft is checked like a text written by hand and never saved by itself; **documents and tables** (2.0.0): a
       document of a store is read with Tika and the image model like an upload - the same type list, the same size
       limit, its content never in a log line -, the model's extract answer is data checked by type (`ExtractedRow`),
       never a path, a formula or an argument; `call_tool` without a person: a source calls reading tools only
       (`task.toolChanges` - no approval can be given on a schedule), a target's rows go to the tool a tenant admin
       named with the arguments the tool's schema takes (`ToolArguments` - a property the schema does not name is
       left out), the connection's key stays inside `McpClient`; the rows a software sends back are data checked by
       type, a file it sends read like a document (at most 6 MB a message, `MAX_DOCUMENT_BYTES`); `{lastRun}` only a
       date format checked at the save.
     - **the file a task writes** (the rules are stated under part 9 "writing into a store"; part 9 checks the
       store's switch and `FileStoreSession.write`, this part the task's side): the path built in `WriteFileStep`
       from `FILE_PLACEHOLDER` alone, the formula guard in `CsvRows` for every cell of every row (also a header a
       model named, also a row read back from the old file), the switch checked again at the run.
     - **what leaves with a run**: the lines read and the findings go only to the recipients the task names
       (`SendMailStep`, `TaskMails` - every value HTML-escaped), to the key that started it only the findings and the
       answers handed on; to a tool or an agent only the rows and the `with` values of the text, never the
       connection's key, a token or a tenant id.
   - Specs: `controller.task.TaskSpec` (the placeholder and switch rules), `services.task.TaskRunSpec`, `TaskStartSpec`,
     `TaskDocumentsSpec` (the switch off at run time, a refused call, a source's changing tool refused),
     `TaskAgentSpec`, `TaskAssistantSpec`, `services.task.step.CsvRowsSpec` (the formula guard), `ExtractedRowSpec`,
     `LineMasksSpec`, `services.tools.ToolboxSpec`.

11. **A2A-Agenten & Anmeldung mit OAuth** (a2a.md "The client", mcp.md "Signing in to the software's server
    (OAuth)"; part 6 and part 2 until November 2026; `local-data/plans/oauth-connections.md` "Binding decisions" and "Not
    done on purpose" count like register entries)
   - the A2A client: `…/services/a2a/{A2aHttpClient,A2aClient,AgentAnswers}.java`,
     `…/services/task/step/CallAgentStep.java`, `…/services/task/step/PromptStep.java` (`answeredByAgent`),
     `…/services/chat/ChatService.java` (`prepareForAgent`: the admin's switch, what goes to the agent),
     `…/services/mcp/McpConnectionService.java` (only `protocol`, `requireA2aAllowed`, `loadCard`, `answeringAgent`,
     `credential`, `startSignIn`, `completeSignIn`, `removeSignIn`), `…/services/mcp/McpAddresses.java` (as both
     use it), `…/entities/tenant/Tenant.java` (`a2aAllowed`), `app.a2a.*` in `application.properties`
   - a connection's OAuth sign-in: `…/services/oauth/{OAuthHttp,OAuthMetadata,OAuthClient,OAuthAddresses,OAuthReachability,AuthorizationServer}.java`,
     `…/services/mcp/Credential.java`, the renewal in `McpClient`, `A2aClient` and `AgentAnswers`,
     `…/controller/oauth/OAuthCallbackController.java`, the `.../sign-in` and `.../sign-in/complete` routes of
     `…/controller/mcp/{TenantAdminMcpConnectionController,McpConnectionAdminController}.java`,
     `…/entities/oauth/**`, `…/repositories/oauth/**`, `…/dto/oauth/**`, `oauth_grant` in
     `db/migration/V17__release_2_0_0.sql`; frontend `components/shared/mcp-connections-panel/**` (`isWebAddress`
     before the browser goes to the server's sign-in page)
   - Special care:
     - **an A2A agent** (a2a.md): called at the address entered only, the card's interface
     lending `tenant` and version alone, the card read at the address's origin, `A2aHttpClient.KeyLeak` refusing a
     request with the key to another host, `McpAddresses.requireAllowed` before every request of the SDK's client (card,
     message, poll, cancel), 12 MB per answer or event, no redirect; only for a tenant with the admin's switch
     `a2a_allowed` (create, reload, new address, the tool offered, the chat answered, a task's `call_agent` - a tenant
     without it is never asked); **what goes to an agent** is bounded and named (a2a.md "Who asks"): the question, the
     files within 8 MB, and the asker's metadata - tenant name, language, username or key name, the user's group names
     and IDM groups, the skills left on; a task's call `task` and `run` - names only, never ids, never a token, never a
     key; the agent's answer is foreign text to the user and the model (an answering agent's whole answer, the tool way's
     result) and cut at `app.a2a.max-answer-chars`, its files by the returned-file rules; a question back
     (`input-required`) is stored as the agent's text, a task's call fails on it (no person to answer); the agent asking
     its own tenant back at `/mcp` or `/a2a` is warned as a possible loop, never refused (decided).
     - **a connection's
     OAuth sign-in** (mcp.md "Signing in to the software's server (OAuth)"): every address of the flow we call
     (resource and authorization server metadata, registration, token endpoint) absolute http(s) and through
     `McpAddresses.requireAllowed` - internal hosts only by `APP_AI_PLAIN_HTTP_HOSTS` -, no redirect followed, connect
     10 s, 30 s for headers and body together, an answer over 64 KB not taken; Nimbus only builds and parses, never its
     own `send()`; `resource_metadata` from a `WWW-Authenticate` only on the connection's own origin; the metadata's
     `issuer` the one asked for, an A2A card's `oauth2MetadataUrl` owned by its issuer; the client id bound to the issuer
     **and** the token endpoint (another one starts over - the client secret and the refresh token go to the token
     endpoint, which a card naming the URLs alone could otherwise move to a host of its choosing); **the access token
     only to the connection's host** (A2A: `KeyLeak` holds for it), never to the authorization server or a link's
     download host; the server's sign-in page - the one address never called - a web address only (no `javascript:`,
     checked again by the frontend's `isWebAddress`), HTTPS unless the installation names its host; our client
     metadata document offered only when the admin's host resolves to public addresses (`OAuthReachability`); the
     refresh under the grant's row lock, a rotated refresh token never spent twice; a 401 renewed once and repeated
     once, a 403 never renewed; a streamed A2A message never sent twice after a renewal. Deliberately so (plan "Not done
     on purpose", not findings): one grant per connection signed in as the tenant admin who clicks (no token per user),
     the first authorization server only, no step-up.
     - **the sign-in's routes**: `/oauth/connections/callback` (anonymous, `@Secured(IS_ANONYMOUS)`) - **exchanges
     nothing** (security review 9 November 2026): it finds the state and forwards the browser 302 to the page the sign-in
     started on; an unknown state a plain 400 page, never 401/403; the state random, single use, 10 minutes, bound to
     one grant; `/oauth/client-metadata.json` - no database, nothing but name, address, callback, `none`.
     `POST .../mcp-connections/{id}/sign-in` (tenant admin and the admin's mirror): the `returnUrl` absolute http(s) on
     the request's host and a frontend page only (`/app/...` or `/dashboard/...` below the context path, plain segments,
     no query or fragment) - never a path serving a tenant's own content (a handbook's website, whose scripts could pass
     the code on) - else 400 `oauth.returnUrlForeign` (no open redirect). `POST .../sign-in/complete`: another tenant's
     connection 404; the grant found by its state under the row's lock **and only for the one who started it**
     (`started_by_user_id`, null for the admin) - anyone else 409 `oauth.stateUnknown`, the sign-in stays pending for
     its owner (a link passed on signs in no one); `iss` checked (RFC 9207: required when announced, compared when it
     comes); the server's `error` texts neither shown nor stored. `DELETE .../sign-in` the tenant's own connection only. Tokens, secret and verifier encrypted and never in a response or a log -
       part 4.
   - Specs: `controller.mcp.A2aConnectionSpec`, `McpConnectionSignInSpec` (the sign-in's routes, the callback, the one
     who started it, isolation), `controller.chat.AgentChatSpec`, `services.oauth.OAuthClientSpec` (against
     `FakeAuthorizationServer`, `FakeReachability`), `services.task.TaskAgentSpec`.
12. **Prompt Injection, ändernde Aufrufe & Websuche** (part 6 until November 2026)
   - `…/services/chat/ChatService.java` (only the pending actions, which tools a question gets, the rules of the
     system prompt for foreign text), `…/services/tools/{Toolbox,ToolContext}.java`, `…/services/mcp/McpTools.java`,
     `…/repositories/chat/PendingActionRepository.java`, `…/entities/chat/{PendingAction,PendingActionStatus}.java`,
     `…/controller/chat/ChatController.java` (`actions/{id}/run|cancel`),
     `…/services/mcp/{AssistantMcpTools,KnowledgeMcpTools}.java` (only what an MCP client's own model gets, and
     that no tool is marked as writing)
   - the web search: `…/services/tools/{WebSearchTool,WebSearchAccess,TavilySearch}.java`, `…/services/chat/ChatService.java`
     (`WEB_RULES`, where `searchesWeb` is set), `…/services/mcp/McpKeyService.java` (`searchesWeb`, `switchWebSearch`),
     `…/controller/mcp/{TenantAdminMcpKeyController,McpKeyAdminController}.java` (`/web-search`),
     `…/repositories/tenant/UserGroupRepository.java` (`searchesWeb` under the WAM rule)
   - frontend: `components/chat/chat-action-card.component.ts`, `utils/chat-action.util.ts`
   - Special care - **text someone else wrote reaches the model** (a document, a chat file, a tool result, an
     agent's answer, a web page): prompt injection - text in a document, a chat file or a tool result that makes the model call a
     changing tool: such a call must wait for the user's decision (decided once, atomically, by that user, re-checked
     against the user's level - the highest of its groups, part 7 -, expired after a day or a new question); a tool result or a document never reaches
     another tenant's or user's chat; the MCP server's own model (`ask_assistant`, `/a2a`) gets only the reading tools
     of its tenant - a changing one reaches only the MCP client of a key with the level for it, by whose own rules its user is asked or not;
     tool results and
     model answers are bounded in size. **MCP clients that act on the PC** (Cowork, Claude Code): passages from
     `search_knowledge` / `read_document` and `ask_assistant`'s answers reach an agent that may write local files - a
     prepared document is prompt injection there too; the server never marks its own tools as writing and offers no
     writing tool (point 40 "Writing back" is not built - should it come, its switch per key, off by default, belongs
     here); SECURITY.md says what the operator must know.
     - **web search** ([ai-features.md](../../../docs/architecture/ai-features.md) "Web search"): only the query the
       model wrote goes to Tavily, cut to 400 characters - the application adds no document, file or conversation to it
       (that the model leaks a word into its query is a known limit, SECURITY.md - not a finding); the pages come back
       headed as unchecked material, their text bounded (Tavily's extract, at most `PAGE_LENGTH`), at most three searches
       per answer; a page's instructions reach no changing tool without the user's decision; Tavily's address is fixed
       (no tenant-entered URL, no SSRF question); the key goes only to Tavily and never into a log line or an error the
       model reads (`e.toString()` of a refused call); who may search is decided on the server - a group under the WAM
       rule, an API key, the MCP server, never a task - and the switches' routes have their roles; a page's address
       becomes a link in the chat only for http(s), opened with `noopener noreferrer`.
   - Specs: `services.mcp.McpToolsSpec`, `controller.mcp.McpSpec`, `services.tools.ToolboxSpec`,
     `controller.chat.ChatSpec`, `WebSearchSpec`, `services.tools.TavilySearchSpec`; frontend
     `utils/chat.util.spec.ts` (`answerSource`), `utils/chat-action.util.spec.ts`.

## The checklist (every agent, for every entry point of its part)

1. **Who can reach it?** Route, role, anonymous or not - from the annotation and the intercept-url-map, not from the
   name.
2. **Whose data does it touch?** Where the tenant id and user id come from; whether every id from outside is checked
   against them before the first read or write; for anything that returns a document's text or chunks, which
   knowledge areas the caller may find and where that scope comes from.
3. **What does an attacker control?** Path, query, body, headers, file name, file content, archive structure, a URL, a
   model's or tool's answer - and where it ends up (SQL, file path, shell argument, HTTP request, HTML, log, prompt).
4. **Limits before work**: size, count and type checked before reading into memory or calling a provider.
5. **What leaves the system?** Response bodies, error messages, logs, headers, what goes to a provider or MCP server.
6. **Is the rule tested?** The spec that would fail if the check were removed - named, or the missing case and the spec
   it belongs in (searched for, review base section 3).

## Step 1 - Spawn the agents

One agent per chosen part (part 2 always), in a single turn, plus the ground-truth run (review base section 1) of the
specs listed under the chosen parts.

**Every agent runs on Opus** - pass `model: "opus"` in each Agent call, whatever model the session runs on (decided
9 November 2026: a session on a larger model would hand it to every agent and use up the token budget at once). The
session's own model only verifies and joins the findings (Step 2). Never leave `model` out, and never start an agent as
a fork - a fork takes the session's model.

**Documents by part** (what each agent reads of `docs/architecture/` before its files - the section names are the
files' `##` headings; fill the row into the agent's prompt):

| Part | Sections |
|---|---|
| 1 | `tenancy.md` "Who does what", "User groups", "Sign-in through a WAM proxy" |
| 2 | `tenancy.md` in full |
| 3 | `tenancy.md` "Who does what"; `mcp.md` "The tenant's MCP server"; `a2a.md` in full; `tasks.md` "Starting without a person" |
| 4 | `tenancy.md` "Who does what"; `monitoring.md` in full; `ai-features.md` "Provider keys" |
| 5 | `ai-features.md` "Documents and embeddings", "Importing a ZIP archive", "Images and scans", "Files in the chat"; `doc-sites.md` and `branding.md` in full; `knowledge-sources.md` in full; `code-execution.md` "Videos" |
| 6 | `mcp.md` "MCP clients to the tenant's software" without its OAuth and scheduled-task parts; `ai-features.md` "Own model servers", "Amazon Bedrock", "Reranking" |
| 7 | `ai-features.md` "Knowledge areas", "Search", "Documents pushed by the client software"; `tenancy.md` "User groups", "Sign-in through a WAM proxy"; `knowledge-sources.md` in full |
| 8 | `code-execution.md` in full |
| 9 | `file-stores.md` in full |
| 10 | `tasks.md` in full; `file-stores.md` "Writing"; `mcp.md` only "Tools called by scheduled tasks" (`grep -n` for it) |
| 11 | `a2a.md` "The client", "Deliberately not built"; `mcp.md` only "Signing in to the software's server (OAuth)" (`grep -n` for it); `local-data/plans/oauth-connections.md` only "Binding decisions" and "Not done on purpose" |
| 12 | `ai-features.md` "Chat", "Tools", "Web search"; `mcp.md` "The tenant's MCP server" only what it says of the tools an MCP client gets |

Give every agent "What this does", its part, "The checklist", sections 2 to 5 of the review base verbatim, and:

> You are reviewing the CURRENT STATE of a real codebase for security: not a diff. Read first, and only these
> sections (the files are long - read another section when a finding depends on it, `grep` for its heading):
> of `CLAUDE.md` "Project overview", the sections marked "(always)" except "Open source" and "Changelog and version",
> "Backend tests (always)" and "Backend architecture" ("Frontend architecture" when your part has frontend paths) -
> not "Repository and build", "CI" or the commands; `SECURITY.md`, `docs/privacy.md` and `local-data/review/decisions.md`
> in full; and of `docs/architecture/` <the row of your part from "Documents by part" below>. Then every file of
> your part in full (part 2: as its paths say). Context: Micronaut
> 5 / Java 25 backend (Micronaut Security with JWT and own token validators, Micronaut Data JDBC, PostgreSQL with one
> database per tenant, langchain4j, Micronaut MCP, the MCP Java SDK), Angular 22 frontend with NgRx. Tenants are
> companies; tenant users chat, tenant admins keep their tenant's settings, the admin keeps everything; the client
> software calls `/api/v1` with an `ai_` key, MCP clients (Claude Code, Cowork, claude.ai, Copilot)
> call `/mcp` with one of the tenant's MCP keys, each with its own knowledge areas and web search, A2A clients
> (an agent platform or a workflow engine) call `/a2a` with a key that opens that door; a tenant may connect
> A2A agents as software (one may answer its whole chat) when the admin allows it. The app runs behind
> Caddy (HTTPS) or a WAM proxy that strips its user header from outside requests. Read-only: do not edit files, do not
> run the application, never touch the developer's dev database, never call an external API, AI provider, MCP server
> or A2A agent.
> If a class of your part opens a connection of the server's own (a model server, a software, an agent, a webhook,
> a download, a file store, the sandbox, the mail server), deliver one row for it: `Class.method` (path:line) ·
> where to and who chose the address · where the address is checked (path:line) · redirects followed or not · which
> key or token goes along and whether it is bound to the host · limits of time and size · spec · verdict.
> For every finding name the attacker (anonymous / tenant user / tenant admin / client software / MCP client / A2A
> client / a connected agent / content author / the model's code in the sandbox), what they send and what they get.
> Severity for this review: **Kritisch** = login or role bypass, a key opening a door it was not given, one
> tenant's or user's data readable or changeable by another, an A2A task or its webhooks reachable by another key, a
> document of a knowledge area found by a user, key, MCP or A2A client not assigned to it, the connection's key sent to
> another host than the one entered, an OAuth token, refresh token, client secret or verifier reaching another host
> than the connection's (or the token endpoint it is bound to), a response or a log, a connection's sign-in completed
> by someone who did not start it or for another tenant, the authorization code sent to a page that is no frontend
> page, a tenant without the admin's switch asking or answered by an A2A agent, a group's right used by a non-member or a group with a WAM group counted without the
> IDM listing it, a tenant admin pulling an IDM group's people into its tenant, a token or a switch into a tenant where
> the person has no user or (no tenant admin) no group that counts, a person the IDM dropped from every group of a
> tenant still served there, uploaded HTML running as the app's origin, a secret leaked, SSRF, a file store's password sent to a
> server whose key or certificate was not confirmed, a task's text run as code or a run started without the admin's
> switch, writing outside the storage
> folder, script running in another user's browser, a change in the tenant's software without the user's decision,
> code in the sandbox reaching another tenant's or user's files, the sandbox token or a network;
> **Hoch** = a check that only holds by luck (one caller forgets it), a limit missing so that one request can exhaust
> memory or disk, a security rule without a spec that would catch its removal.

## Step 2 - Synthesize

Follow **section 6 of the review base** (pool, dedupe, register, verifier, budget, at most three fix packages), then the
report as in **section 7**. The skill's own sections (section 7, point 2):

- **Angriffsfläche**: one table from agent 2, completed by the others - one row per route group:
  `Pfad` · `Rolle` (or anonym) · `Mandant/Benutzer aus` · `fremde Ids geprüft` · `Spec` · Urteil ✅ / ⚠️ / ❗. Only ⚠️/❗
  rows in full, the ✅ rows counted per controller package.
- **Bereiche** (when part 7 ran): one table, one row per way someone reaches a document's text or name - chat stream,
  decided action, `/api/v1/chat` with history, without history, `ask_assistant`, `search_knowledge`, `read_document` (per MCP key),
  `/a2a` `SendMessage` plain and streamed (per MCP key with the A2A door), `/api/v1/documents` list / push /
  delete, the frontend's document list, a handbook's website: `Wer fragt` · `Scope aus` · `Filter in` · `Spec` · Urteil
  ✅ / ⚠️ / ❗.
- **Gruppen** (when part 7 ran): one row per right a group gives (knowledge area, level per connected software) and per
  way its membership changes (group API, `groupIds` on a user, deleting a user/group/area/connection, the WAM group with
  and without the header): `Recht/Weg` · `gelesen in` (path:line) · `WAM-Gruppe beachtet` · `Spec` · Urteil ✅ / ⚠️ / ❗.
- **Schlüssel**: per kind (admin password, JWT secret, `APP_SECRET_KEY`, provider keys, `ai_` keys, MCP keys (several per tenant, with their doors), connection
  keys (MCP software and A2A agents), a connection's OAuth sign-in (access token, refresh token, client secret, PKCE
  verifier, the `state`), a webhook's token and credentials (`a2a_push_config`, encrypted), sandbox token, the handbook's address token, the file stores' passwords - S3's secret access key, SharePoint's
  client secret and the Graph token it gets, a download address of Graph, a mailbox's password): where it lives, how it is stored, where it is checked, whether it can leave the system - one line each.
- **Ausgehende Verbindungen** (when part 6 ran; otherwise say in the Überblick that it was not built): one row per
  place the server opens a connection of its own - `Class.method` (path:line) · wohin (a fixed address, an address
  the admin, a tenant admin, a connected software, an A2A client or a tool result chose) · `Adresse geprüft in`
  (path:line - on save, before every call, after a redirect; internal hosts by which list) · `Weiterleitung` (not
  followed / followed by hand with the check again) · `was mitgeht` (which key or token, and whether it is bound to
  the host) · `Grenzen` (time, size) · `Spec` · Urteil ✅ / ⚠️ / ❗ / "nicht geprüft" (its part did not run). Only
  ⚠️/❗ rows in full, the ✅ rows counted.
- **Entschlüsselte Geheimnisse** (when part 4 ran): the inventory of every caller of the decryption, only ⚠️/❗ rows
  in full, the ✅ rows counted.
- **Sandbox-Schichten** (when part 8 ran): one row per layer of code-execution.md "Isolation without privileges" and
  per way between sessions (tenant to tenant, user to user of one tenant, code to server/token, code to network):
  `Schicht` · `wo umgesetzt` (`path:line`) · `geprüft durch` (spec or `selftest.py` case) · Urteil ✅ / ⚠️ / ❗.
- **SECURITY.md / privacy.md**: what the documents promise and the code does not keep (or the other way round), each as
  a suggestion to fix the code or update the document.
