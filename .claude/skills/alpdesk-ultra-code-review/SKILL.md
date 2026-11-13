---
name: alpdesk-ultra-code-review
description: "Full non-diff, multi-agent deep review of the current Backend+Frontend codebase of Zirmo against seven criteria (Bugs, Stabilität, Funktionalität, Best Practice, Performance, Testabdeckung, Struktur), grouped by severity, with a test-coverage matrix and a production-readiness punch list. Use when the user invokes /alpdesk-ultra-code-review, or explicitly asks for a full/ultra codebase review that is NOT a diff/branch/PR comparison."
---

## What this does

This skill never looks at a diff, branch comparison or PR. It reads the **entire current state** of
Backend and Frontend area by area. Several parallel research agents each read the full files of
their area, not excerpts and not `git diff`, and evaluate them against the same seven criteria. The
findings are merged and presented by **Härtegrad** (severity). The report adds a test-coverage
matrix and closes with what must change before production use.

[CLAUDE.md](../../../CLAUDE.md) is the yardstick for structure and conventions: layer-first
package structure, one role per controller matching its path, tenant (and user) id only from `TenantContext`, the
narrow tenant API (`/api/v1`), never 403 for a refused action, one database per tenant, knowledge areas (every search
through the asker's scope, conversations per owner), every user-facing frontend text a translation key in every
language and everything else English (CLAUDE.md "Language convention", i18n.md), industry-neutral wording, the code style rules, NgRx with `selectSignal`/`takeUntilDestroyed`, form dialogs through
`DialogSave`, and "Backend tests (always)". The rules of each area are in
[docs/architecture/](../../../docs/architecture/README.md) (one file per area - the table in CLAUDE.md), which is just
as binding. A deviation from them is a finding. A rule the code has outgrown is also a finding, as a suggestion to update
the file that states it.

**The decision register** `local-data/review/decisions.md` (local, ignored by git) lists what the user
has decided to accept (E-numbers). If the file is missing, stop and tell the user (review-base §2). What an entry covers is not reported, only "Entscheidung prüfen (E..)" when its
premise no longer holds (the code changed so the reason is gone, or the consequence is worse than the entry says).
Decisions written in CLAUDE.md and `docs/architecture/` ("decided", "deliberately", "not done on purpose") count the
same as register entries. Unlike a scoped review, this one has no finding budget - it is meant to find everything.
"Everything" means everything that holds: the rules against hallucination and over-optimisation of
[.claude/review-base.md](../../review-base.md) (sections 3 and 4) apply here too and go verbatim into every agent's
prompt, together with the side-effect pass of its section 5 ("Side effects", "Not described") - only what was read and cited, missing tests searched for, library behaviour marked as Annahme, unsure points
under "Offen/unklar", never a finding to fill a list. The scoped reviews (security, knowledge, chat, usage, tenancy, task,
mcp-a2a) go deeper on one path each.

Ignore any `<PR#>`, branch, `--comment`, `--fix` or `--post` arguments and say that this skill
always reviews the whole working tree (uncommitted changes included). The review is **read-only**: no code changes,
no commits, no `./gradlew run`, **never the developer's dev database** (CLAUDE.md "The developer's dev database"),
never a call to a real AI provider, model server or MCP server of a tenant.

## Judging with proportion

The review finds what really hurts, not what could be more perfect. Lean and readable code is the goal (CLAUDE.md
"Code style"); a review that recommends caches, pools, interfaces, configuration options or generic frameworks for a
load or a second case nobody has makes the code worse.

- **Damage today, not in theory.** A finding needs a concrete scenario that happens with the code and the use it has
  now (or a use certain to come). "Could become slow with millions of tenants" is not a finding; "every chat question
  reads all documents of the tenant into memory" is.
- **This limits what gets recommended, never what gets looked at.** Concurrency, transactions, resource and memory
  leaks (connections, pools, streams, temporary files), security and real performance problems are still examined in
  full depth.
- **Weigh value against cost.** Every finding carries the size of its fix: **S** (a few lines, one spec), **M** (one
  class and its specs), **L** (several classes or a design change). A fix that adds more complexity than the damage
  justifies is not recommended - the finding is reported with its weight and the honest verdict "Fix lohnt sich
  derzeit nicht", or not at all when the damage is negligible.
- **The smallest fix that removes the damage.** Prefer deleting or simplifying code over adding layers; one guard or
  one spec over a new mechanism. No abstraction in advance, no optimisation without a measured or certain load, no
  configuration option nobody asked for.
- **Severities are not inflated.** A papercut is Niedrig, even when there are many of them; Kritisch and Hoch only with
  the consequences named below. Style without a broken rule is no finding.
- **"Nothing to find" is a valid result** for an area. The report also says briefly what is solid, so that well-built
  parts are not touched.

## Step 0 - Ground truth (parallel to step 1)

**Ask the user first** whether the full suite may run (CLAUDE.md "Backend tests": it takes over 10 minutes and runs
only after asking - invoking this skill is not that consent). Without a yes, leave out the `./gradlew test` line below
and say in the report that the backend suite was not run. Then, in the same turn as the agents, start in the background:

- `cd Backend && ./gradlew test` (JAVA_HOME = `$(/usr/libexec/java_home -v 25)`; needs Podman;
  the tests bring their own Testcontainers database, so neither the dev database nor a running dev backend is
  touched),
- `cd Frontend && npm run lint && npx ng test --watch=false`.

Afterwards count the tests and failures from `Backend/build/test-results/test/*.xml` and the Vitest summary. A red
test or lint run is itself a finding (Hoch).

## Step 1 - Spawn parallel deep-read agents

**Every agent runs on Opus** - pass `model: "opus"` in each Agent call, whatever model the session runs on (decided
9 November 2026: a session on a larger model would hand it to every agent and use up the token budget at once). The
session's own model only verifies and joins the findings (Step 2). Never leave `model` out, and never start an agent as
a fork - a fork takes the session's model.

In a **single assistant turn**, launch the following agents via the `Agent` tool, each with
`subagent_type: general-purpose` and `run_in_background: false`, so that they all return before
the synthesis. Each agent gets its own area, so no agent has to skim because its context is full.
**An area stays below about 12,000 lines** (CLAUDE.md and the area's architecture files come on top): before spawning,
count each area (`find <paths> -type f | xargs cat | wc -l`) and split one that has grown past it by its sub-packages
or folders into two agents with the same focus - say so in the report's Überblick.
Base paths: `Backend/src/main/java/de/alpdesk/` (below `…/`), tests in
`Backend/src/test/groovy/de/alpdesk/`, and `Frontend/src/app/`.

1. **Backend API & Security**:
   - `…/controller/**`, `…/dto/**`, `…/security/**`
   - the security/CORS/swagger/MCP parts of `Backend/src/main/resources/application.properties`
     (`micronaut.security.intercept-url-map`, `micronaut.mcp.server.*`)
   - Focus:
     - exactly one role per controller matching its path, never `IS_AUTHENTICATED`; `/mcp` (Micronaut MCP's own
       controller) guarded by the intercept-url-map entry for `ROLE_MCP`
     - tenant and user id only from `TenantContext`, never from a path, query or body value
     - tenant API scope as decided in CLAUDE.md "Tenant API"; DTOs only, no entity reachable from `/api/v1`
     - `@Hidden` on everything outside `/api/v1` and the users' routes published next to it (`/login` through
       `@OpenAPIInclude`, `/login/wam`, `/branding`, `/tenant/account`, `/tenant/branding`, `/tenant/chat` - the list
       in `TenantApiKeySpec`, tags "Users - sign-in" / "Users", `UserAuth`); nothing of `/admin`, `/tenant/admin`,
       `/tenant/ai`
     - one person with users in several tenants (2.0.0): a token one user's of one tenant, the switch
       (`/tenant/account/switch`: with WAM the header, without the target's password - 400, never 401 - and only
       between users the admin marked as one person's, `tenant_user.person` set by `users/from-tenant`), `X-Login-Key`
       at `POST /login` (a wrong key like a wrong password, ignored with WAM), `POST /login/wam` with the choice and
       `{tenantId}`, `users/from-tenant` the admin's only; CORS `APP_CORS_ORIGINS` exact origins and
       `allow-credentials` false
     - `@ExecuteOn(BLOCKING)` for JDBC and AI calls; thin controllers without try/catch
     - the token validators (JWT, `ai_` keys, `mcp_` keys): hash lookup off the event loop, inactive tenants 401,
       `ActiveTenantUserRule` (deactivated users, old tokens after a password change, former tenant admins)
     - no secrets in responses (provider keys only as `keyHint`, API/MCP keys shown once)
     - `ServiceException`/`GlobalExceptionHandler` mapping; never 403 for a refused business rule
     - knowledge areas: the area controllers and `/areas` endpoints (one role each), the API key's id from its
       authentication (`TenantContext.apiKeyId`), every conversation endpoint passing its `ConversationOwner` (a user,
       or one API key), `/api/v1/documents` listing, pushing and deleting only within the key's areas
2. **Backend tenants, users, assistant & branding**:
   - `…/services/{tenant,assistant,branding,common,filestore}/**`, `…/storage/**`, their entities and repositories,
     `…/controller/filestore/**`, `…/dto/filestore/**`
   - `TenantDatabases`, `TenantDatabaseMigration`, `Backend/src/main/resources/db/tenant/**`
   - Focus:
     - tenant scoping of every query (another tenant's id answers 404)
     - creating and dropping a tenant database (outside a transaction, pool handling, failure halfway)
     - users: password hashing, sessions after a password change, the last tenant admin, deleting a user with its
       chats; a username unique per tenant (`tenant_user_tenant_username_ci_uq`, the admin's taken everywhere),
       `loginAccounts` (active user, active tenant, without WAM a password, a tenant admin or a user in a group),
       `switchableAccounts`, `switchTenant`, `addFromOtherTenant` (the person's mark); wrong passwords lock the account
       for a while (`countLoginAttempt` before the password check, `app.login.*`, `LoginLockoutSpec`)
     - user groups (`UserGroupService`, tenancy.md "User groups"): a group with a WAM group takes its members from the
       IDM alone (`GROUPS_OF_USER`, every right through it), members sent 400, assigned rows dropped when the WAM group
       is set, only the admin sets a WAM group under WAM (409); `AccessRequestService.signIn` gives a person listed in
       such an IDM group an active user in every active tenant with such a group (`accountsFromIdm`), the choice with
       several, fail closed without the groups header; **access needs a group** (2.0.0): a user who is no tenant admin
       gets in only while one of its groups counts for it (`TenantUserService.inAGroup` - the password login, `POST
       /login/wam` with 409 `user.noGroup`, the choice and switch of tenants, every request in `ActiveTenantUserRule`,
       which records the request's IDM groups first); every tenant has the group "Standard" without rights (created
       with the tenant, `V17` for the tenants of before), into which a user created by hand without `groupIds`, an
       assigned access request and a user added from another tenant go (`joinStandard`, never an IDM-created user);
       specs `security.GroupAccessSpec`, `WamGroupsSpec`, `StandardGroupMigrationSpec`
     - knowledge areas (`services.document.KnowledgeAreaService`) as part of the tenant's settings: assigning areas to
       users, API keys and the MCP server (only the tenant's own areas, locked against concurrent saves), revoking an
       API key deleting its conversations after the commit
     - assistant settings and chat templates (default model must be a chat model of the tenant)
     - branding (logo size/type checked before reading)
     - transactions on every public write method (or the reason why not)
     - file stores (file-stores.md): the server's fingerprint confirmed before the password goes out, another type,
       host or port needs the password again, internal hosts only on the operator's list and resolved again before
       every connection, FTPS data connections to the control connection's address, a store a task or a knowledge
       source reads not deleted (409) - and while a source reads it, not pointed at another server, folder or library -,
       the password encrypted and never in a response; **S3** (`S3StoreClient`): credentials only from the store, never
       the server's environment, `~/.aws` or an instance role, the endpoint checked on save and before every session,
       plain http only to an internal host, a range read cut after its limit, one `S3Client` per session and the HTTP
       client shared (closed only with the bean); **SharePoint / OneDrive** (`GraphStoreClient`): the secret only to
       Microsoft's login, the token only to Graph, no redirect followed, a next page only from Graph, a download
       address without the token and only `*.sharepoint.com`, throttling waited for once, an expired token fetched
       again; `readable` per type (S3 and SharePoint: a check that read the folder); the folder listing for the picker
       (`folders`: below the store's folder, at most 500, only a ready store); **writing** (file-stores.md "Writing",
       2.0.0): only a store with the switch `task_write` (V17, never a mailbox), `FileStoreSession.write` per type -
       SFTP a temporary file beside the target renamed over it (`posix-rename`, SFTP 5 overwrite, else the old file
       removed first and the temporary kept when the rename then fails), FTPS STOR then RNFR/RNTO with the connection
       dropped after a write, S3 `PutObject`, Graph `PUT :/content` with 423 → `fileStore.fileLocked`; `readWhole` the
       one whole-file read `SourceSync` and the task steps share; **IMAP mailbox** (`MailboxStoreClient`, V17,
       file-stores.md "Mailbox"): read only (`Folder.READ_ONLY`, nothing marked, moved or deleted), messages listed as
       folders `<date>_<UID>` with their attachments and `mail.txt`, a forwarded `message/rfc822` unpacked one level
       (such a mail fetched whole), parts of `multipart/related` after the first skipped, `SEARCH SINCE` by `days`
       (default 365), at most `MAX_ENTRIES`, security SSL / STARTTLS / NONE with NONE only to an internal host, the
       failures mapped by code, one attachment in memory at a time, no fingerprint (409 `noFingerprint`);
       `FileStoreServerSpec` (SFTP, FTPS, S3 - reading and writing) runs only with `FILE_STORE_TEST`,
       `SharePointStoreSpec` (against `FakeMicrosoft`, also an upload and a locked file) and `MailboxStoreSpec` (the
       real client against GreenMail IMAP) always
3. **Backend AI providers & usage**:
   - `…/services/ai/**`, `…/entities/ai/**`, `…/repositories/ai/**`
   - Focus:
     - `AiModels` is the only class calling a provider; per provider the options actually sent (temperature,
       reasoning, thinking, context length)
     - own model servers: `ModelServers` address rules (HTTPS, plain-HTTP hosts, no user/query/fragment), no
       redirects, the key bound to its origin, embedding dimension check; Bedrock (`BedrockClients`): the region from
       the address, requests signed with the access key, nothing of the server's environment taken
     - provider keys: encryption (`SecretCipher`), own key before global key, `globalKeysAllowed`
     - usage records per call (tokens, the cached ones apart - `CallTokens` per provider -, source of the key, user or
       none), priced by `UsageService.amount` (the tier by the prompt's whole length, cache prices falling back to the
       tier's input price); prompt caching only for the agent loop's rounds (`ChatOptions.cacheKey`,
       `AiModels.request`); the reranker's call (`TeiScoringModel`, 30 s, no retry) recorded once, a failed one not
     - timeouts and error mapping (502 with the provider's name, never its key or the company's data)
4. **Backend documents & company knowledge**:
   - `…/services/document/**`, `…/repositories/document/**`, `…/entities/document/**`, the handbook:
     `…/services/docsite/**`, `…/repositories/docsite/**`, `…/entities/docsite/**`
   - Focus:
     - Tika: only the agreed file types, recognised by content; size limits checked before reading
     - ZIP import (`DocumentArchive`): entry/size limits, path traversal (`..`), hidden files, name encodings,
       replace-by-path and dedup by content hash
     - background embedding (`DocumentIndexer`): states PENDING → PROCESSING → READY/FAILED, retry, restart while
       embedding, changing the embedding model re-embeds everything; the tenants taking turns (`submit` / `next`: a
       queue per tenant, at most one job per tenant in the executor, the queue entry removed only when it is empty,
       the next job queued in `finally`)
     - knowledge sources (knowledge-sources.md: `KnowledgeSourceService`, `SourceSync`, `SourceScheduler`,
       `DocumentService.fromSource`, `SourceFileRepository`): a save lists only; reading by hand everything open, by
       schedule at most 100 files and 250 MB; a known document replaced in place (same id, its area as it is now),
       unchanged text not embedded again; deleting what vanished only after a complete listing (never after a store
       failure, a limit or a cancel); deleted and moved by hand kept; another area / store / folder / pattern; one run
       per source, 409 `source.running`; removing with or without documents, the tenant database first; the executor
       `sources`; the file types by `DocumentTextExtractor.TYPES` (the extensions only leave files out)
     - pgvector store (`TenantEmbeddings`), `KnowledgeSearch` rules (the tenant's reranker: candidates by
       `app.search.rerank-candidates`, scores back by index, the hybrid order kept when it fails or takes 30 s),
       external ids of the client software
     - knowledge areas: every search through the asker's `KnowledgeScope` (company knowledge plus assigned areas,
       everything for a tenant admin), both searches joined to the document and only READY documents found,
       `markReady` only for the version embedded; the area of an upload, a ZIP import (path unique per area,
       duplicates per area) and a push (`area` missing = keep, `""` = company knowledge), checked in the writing
       statement; moving, deleting an area's uploads, deleting an area only when empty
     - images and scans (`ScanReader`, `DocumentImages`): pages read by the image model, cost
     - a document's content never goes to the log
     - the handbook (doc-sites.md: `DocSiteService`, `DocSiteTokens`): a website per scope uploaded as a ZIP (the
       installation's limit, entries and paths checked like a ZIP of documents), replaced and deleted with its files,
       who reads which, the signed short-lived address, every file served sandboxed, nothing of it reaching the
       assistant's search
5. **Backend chat, files & tools**:
   - `…/services/chat/**`, `…/services/tools/**`, `…/services/agent/**`, `…/services/sandbox/**`,
     `…/repositories/chat/**`, `…/entities/chat/**`
   - Focus:
     - the agent loop (`AgentLoop`, prepared and stored by `ChatService`): rounds, the last round without tools,
       streaming, a stream that runs away ended (`AiModels.StreamStop`, `CycleDetector`) and `withoutBlankRuns`, what
       is stored when the provider fails midway
     - Python and shell (code-execution.md: `Sandboxes`, `SandboxSession`, `RunPythonTool`, `RunShellTool`,
       `CodeSessions`): offered only with an answering sandbox of the tenant's own, one session leased per answer and
       ended with it in every path, the question's and the conversation's files streamed into it (never whole in
       memory, no name leaving `data/`), every instance busy / a refused token / an instance going down told to the
       model as a text - never a failed answer -, the output cut, the tenant's token only to its own instances
     - the system prompt rules (answer source, citations, "Allgemeinwissen:", the tenant's and template's
       instructions never lifting our rules)
     - chat files: upload limits, `ChatFileReader` (text, images, scanned PDFs, audio via ffmpeg; a video is taken as
       it is and examined in the tenant's sandbox), created files (`FileRenderer`, formats), file cleanup on delete and
       after the retention (`ChatFileCleanupJob`)
     - conversation titles, history window, answers once without a conversation (the MCP server, sent files)
     - changing tool calls (`pending_action`): stop, decide once (atomic claim), run/cancel, expiry after a day or a
       new question, the user's level checked again
     - `Toolbox`/`ToolContext`: which tools a question gets (user, client software, MCP server) - and which knowledge
       scope every entry point sets (`ToolContext.knowledge`); the connections a question leaves out
       (`ToolContext.excludedConnections`, from `excludedConnectionIds` or the conversation's stored choice, 400 for one
       not reached, kept with the conversation by `finish`), `[<connection>]` before every software tool's result
       (`McpTools.execute`, stripped again for a decided call's stored result), `SOFTWARE_RULES` / `NO_OWN_DOCUMENTS`
       only with software tools
     - conversations, messages and files owned by a `ConversationOwner`: a user's or one API key's, never reachable by
       another owner
     - the web search (`WebSearchTool`, `WebSearchAccess`, `TavilySearch`): offered only to an asker that may, only
       after the documents, at most three searches, only the model's query going out, a failed search told to the model
     - the loop as a scheduled task's step uses it (`Asker.TASK`, `onlyTools`, `stopWhen`): neutral for a chat answer
6. **Backend scheduled tasks**:
   - `…/services/task/**`, `…/repositories/task/**`, `…/entities/task/**`, `…/controller/task/**`, `…/dto/task/**`,
     `Backend/src/main/resources/tasks/reference/**`, `Backend/src/main/resources/mail/{answer,footer-task}.html`;
     `…/services/agent/AgentLoop.java` and `…/services/filestore/FileStoreSession.java` only as the steps use them
   - Focus:
     - scheduled tasks (tasks.md): the text checked as data with its lines and never run as code, names resolved to
       ids and a run keeping the definition it started with; the graph bounded (`maxRounds` per edge back, the
       recursion limit), a step's record per round, read positions moved only after a successful run and never past
       a complete line, checkpoints deleted at every end, open runs taken up after a restart, a cancel between steps
       and rounds; a step's model with `search_file` and `read_file_lines` only, the structured result checked
       (evidence lines really read, a finding without evidence unclear), one more round for a broken answer; every
       failed or unclear run mailed, the admin's switch checked at every start, one run of a task at a time;
       **documents and tables** (tasks.md "read_documents", "extract", "write_file", "call_tool", 2.0.0 -
       `…/services/task/step/{ReadDocumentsStep,DocumentReader,ExtractStep,ExtractedRow,WriteFileStep,CsvRows,CallToolStep,ToolArguments,StepRows}`):
       every document of a folder read once (a position of size and modification time to the millisecond, at most 50
       a run, the oldest first), what the document is to blame for reported once and never again, no image model or
       key read again later, a provider failing fails the run; `extract` one document per question, every value checked
       by type, one more round, then the row unclear with why, rows in the tenant table `task_run_row` (V8) and over
       `/api/v1` and `task_run` only once the run ended; `write_file` only into a store with the switch checked at the
       save and at the run, the file from `FILE_PLACEHOLDER` only (never a path), rows skipped by `source`, fields
       added at the end taken and another header refused, `replace` this run's rows only, 20 files and 20 MB, one
       task's file never another's (`task.fileTaken`), the old file never lost on a replace; `call_tool` as a target
       sends clear rows only with their `source`, a failed call marks its row and ends the run `UNCLEAR` with a mail,
       a tool turned off since the save fails the run (`task.toolUnavailable`); as a source reading tools only, at most
       200 objects, `{lastRun}` the start of the last `DONE`/`UNCLEAR` run, a file the tool fails at or sends too
       large kept with its reason while the software out of reach fails the step; `TaskResults.counts(Step)` by what a
       step gives; `send_mail` taking an extract's or a rows source's unclear rows
7. **Backend MCP & A2A**:
   - `…/services/mcp/**`, `…/controller/mcp/**`, `…/entities/mcp/**`, `…/repositories/mcp/**`, `…/dto/mcp/**`,
     a connection's OAuth sign-in: `…/services/oauth/**`, `…/controller/oauth/**`, `…/entities/oauth/**`,
     `…/repositories/oauth/**`, `…/dto/oauth/**`
   - Focus:
     - the MCP server (`AssistantMcpTools`): flat input schemas every client takes, tool errors vs. protocol
       errors, the tenant and the MCP key's id from the key's authentication only; `KnowledgeMcpTools`
       (`search_knowledge`, `read_document`): stateless, every read re-checks the key's areas by the document's id,
       "not found" the same for every reason
     - MCP keys: several per tenant (name, areas, web search each - `V12`; a description of 500 characters - `V17` of
       2.0.0 -, into `initialize` and the descriptions of `search_knowledge` and `ask_assistant` of that key only), hash
       only, renew keeps the settings and ends only the old secret, delete, last use; `ask_assistant` and the knowledge tools take the areas, software and
       web search of the key the call was made with
     - connected software passed through (`SoftwareMcpProxy`, `BrandedMcpServer`'s "__" branches): reading tools, and
       changing ones only for a key with the level `WRITE` for that software (`changingConnectionIds`, roadmap point
       68: marked not read-only, never through `ask_assistant` / `/a2a`, the level back to `READ` when pass-through
       is switched off, a log line per changing call, "the change may have been made" after a call that failed once
       sent); names within 32
       characters keeping "__", list and call agree, an unknown name a tool error (the transport's HTTP 400 ends an SDK
       client's session), calls off the event loop, the result passed on unchanged (mcp.md "Connected software through
       to MCP clients": a web link as a link, an own resource's link embedded within 20 links and 8 MB, the tool's
       whole entry from `mcp_tool.definition`, the reserved `_meta` keys kept back, resources through
       `SoftwareResources` with the connection in front of the address), instructions cut to 2,000
     - the MCP client (`McpClient`, MCP Java SDK): no redirects, the key only to its origin, timeouts, result size,
       failures naming the connection, never the address; a file a tool returns as a link (`LinkDownloads`): the
       address rules before the request and after every redirect (followed by hand, at most 3), the bearer only on
       the connection's own origin, 50 MB and 60 s for the whole download, every failure a reason for the model -
       never a failed call
     - a connection that signs in with OAuth (mcp.md, `OAuthClient`, `OAuthMetadata`, `OAuthHttp`, `OAuthAddresses`,
       `Credential`): every address of the flow checked like a connection's, no redirect followed, the state single use
       and bound to one grant, completed only by the one who started the sign-in, the anonymous callback forwarding
       only, tokens, client secret and verifier encrypted, the access token only to the connection's host, the
       refresh under the grant's row lock (a rotated refresh token never spent twice), a refused renewal dropping the
       tokens and asking for a new sign-in
     - `McpAddresses`: SSRF protection (internal addresses, DNS resolving to internal ones, plain HTTP only to
       listed hosts) - checked on save and before every call
     - connections and tools: sync on reload (new ones off, dropped ones not offered, admin choices kept), each
       user's level, the MCP server's opt-in (its model reading only; changing tools only passed through to a key
       with the level for that software)
     - everything in `docs/architecture/mcp.md` implemented as written
     - the task tools (`TaskMcpTools`): only the tasks assigned to the MCP key (`tenant_mcp_key_task`), `run_task` waiting bounded
       (`app.task.mcp-wait`), the findings and the answers handed on given back - never the lines read
   - **A2A** (`docs/architecture/a2a.md`): `…/services/a2a/**`, `…/controller/a2a/**`, `…/repositories/a2a/**`, `…/entities/a2a/**`,
     `…/services/task/step/CallAgentStep.java`, the A2A branches of `McpConnectionService`, `McpTools`, `ChatService`, `PromptStep`
     - the client on the official SDK behind our adapter (`A2aHttpClient`: `McpAddresses` before every request, the key only to
       the address entered - `KeyLeak` -, 12 MB, the timeouts, streams cancellable; `A2aClient`: a client per call, the card
       pinned to the connection's address, a Task polled, the tool way `<connection>__ask`); `AgentAnswers` (the events mapped
       onto the chat's, the latch and the threads - no JDBC on the SDK's thread -, the asker's metadata, stop and cancel, the
       loop warning); `McpConnectionService` (protocol fixed, the admin's switch, the card into instructions and skills, one
       answering agent per tenant, the check); `ChatService.prepareForAgent` / `finishAgent` (context, question back, files,
       skills, nothing in the usage)
     - the server on the SDK's core (`A2aAgentCore` without CDI, `A2aController` parsing and writing through the SDK, errors as
       JSON-RPC errors with HTTP 200, the extended card and its version check answered by us, the card's origin from
       `X-Forwarded-Proto`), `AssistantAgent` (a Message, a stored Task with the answer appended in pieces, the key's scheduled
       tasks mirrored, cancel reaching the run), `JdbcTaskStore` + `a2a_task_index` (a task owned by its key - `KeyOwnsTask` -,
       the restart rule, `ListTasks` paged), `JdbcPushConfigStore` + `PushAddresses` (public HTTPS only, ≤ 5 per task,
       encrypted), `A2aTaskCleanupJob` (the tenants with tasks only)
     - the doors of a key (`McpKeyTokenValidator.opens` below the context path, `mcpKey.noDoor`, existing keys MCP only)
     - the scheduled tasks: `call_agent` (a target only, rows as `DataPart`s, a question back fails the row), a `prompt`
       step with `assistant: true` (reading tools only, areas, web, template; through the agent for a tenant in agent mode)
     - everything in `docs/architecture/a2a.md` implemented as written, the "Decisions (6 November 2026)" and the "Decisions (lean)" lists of `local-data/plans/a2a.md` kept
8. **Backend data model, configuration & build**:
   - all of `…/entities/**` and `…/repositories/**` (cross-feature view)
   - `Backend/src/main/resources/db/migration/**` and `db/tenant/**`, the rest of `application.properties`
   - `Backend/build.gradle`, `Backend/podman/**` (incl. `ollama/`), `.github/workflows/**`, `.github/scripts/**`,
     `.github/dependabot.yml`
   - monitoring (monitoring.md): `…/services/monitoring/**`, `…/mail/**`, `…/logging/**`,
     `Backend/src/main/resources/mail/**`, `logback.xml`
   - Focus:
     - constraints and indexes matching the queries; `ON DELETE` behaviour; ids across databases without foreign
       keys (`document.area_id`, `document.source_id`, `conversation.api_key_id`, `task_run.task_id`; the area assignments with
       `ON DELETE CASCADE`; `task_file_store`'s deferred foreign key and why - the tenant's cascade), the partial
       unique index of `task_run` (one open run), langgraph4j's tables from our migration (`V5__task_runs.sql`);
       `knowledge_source`'s references to store and area without `ON DELETE` (checked at the statement's end, so the
       tenant's cascade passes), `source_file.document_id ON DELETE SET NULL` (a document deleted by hand recognised),
       `tenant.reranking_model_id ON DELETE SET NULL`, the check constraints of V15 (`S3`, `SHAREPOINT`) and V16
       (`RERANK` only with `TEI`), `V17__release_2_0_0.sql` (`file_store.task_write` default false; `IMAP` added to
       the type constraint the way V15 did, `security`, `mailbox`, `days`; the username unique per tenant with an index
       for the lookup by name; `tenant_mcp_key.description`), tenant `V8__release_2_0_0.sql` (`task_run_row`
       `ON DELETE CASCADE` with the run, `"values"` quoted; `task_read_position.modified_at` carrying a document's time;
       `conversation.excluded_connection_ids BIGINT[]`)
     - the task executor (`micronaut.executors.task`), the sources' (`micronaut.executors.sources`), `app.task.*`,
       `app.file-store.internal-hosts`, `app.search.rerank-candidates` (2 to 40, in both compose files)
     - the new libraries: the AWS SDK's `s3` through the Micronaut platform with only `url-connection-client` (Apache,
       Apache 5 and Netty excluded), `msal4j` pinned, Angus Mail (`jakarta.mail`) for IMAP; their licenses in THIRD-PARTY.md / NOTICE; the reranker - no image of
       our own: `Backend/podman/public/docker-compose.reranker{,-gpu}.yml` start TEI's official image with
       `bge-reranker-v2-m3` of one fixed revision, downloaded into a volume at the first start; not root, read-only,
       no published port, in the app's network
     - Flyway rules (a new `V<n>` per change, no released migration of either set edited - applied means in a release tag `v<x.y.z>`; the
       migrations of the unreleased version are one per set, `V17__release_2_0_0.sql` and tenant `V8__release_2_0_0.sql`,
       which grow until the release; `pr-checks.yml` compares with the newest release tag)
     - CORS: `app.cors.origins` from `APP_CORS_ORIGINS` (default `http://localhost:4300`, in both compose files and
       `.env.example`), `allow-credentials` false, the same list frames the handbook
     - connection and lock timeouts, the 503 "try again" mapping, SQL state mapping (23505 → 409)
     - AOT/jar packaging, container env vars read despite AOT, the production secrets check
     - CI covering tests, lint, frontend tests, build and the smoke test; the release workflow
     - the daily report and the application log: a tenant database read over a connection of its own, closed at once
       (never the tenant's pool), one that cannot be read reported and the others all the same, the report without
       the management database, nothing of a tenant's content in the mail (counts, sizes, amounts, tenants' names),
       every value HTML-escaped (`MailTemplate`), a wrong recipient or sender stopping the start, `sendNow` outside a
       transaction with its 409 / 502; `RecentLogAppender` keeping flat entries only (never the event with its
       arguments), bounded, found by its type in the AOT jar; both routes the admin's only
     - what the public repository shows (CLAUDE.md "Open source"): every committed file is public - list them
       (`git ls-files`, and `git status --short` for what is about to be added) and look at what is new outside
       `Backend/src` and `Frontend/src`; no real data anywhere (names of people or customers, domains of an installation, keys, local paths), also not in `docs/`,
       `.claude/` or `devtools/`; the image tags in `Backend/podman/public/` equal to `app.version`
       (`.github/scripts/check-compose-files.sh`), the topmost CHANGELOG section the version in `app.version`, a
       runtime dependency without its entry in THIRD-PARTY.md; the brand name Zirmo where people read it, the technical
       names (`alpdesk-ai`, `de.alpdesk`, `alpdesk_ai`) untouched
9. **Backend test suite** (the suite is about 40,000 lines - too much to read in full, so this agent works from the
   outside in; whether one behaviour of an area is tested is each area agent's own question, searched for in the specs
   of its feature, `controller.<feature>` and `services.<feature>`):
   - read in full: the shared tooling - `FakeAiModels`, `AiApi`, `FakeMcpSoftware`, `FakeA2aAgent`, `ModelServerSpec`,
     `FakeStoreClient`, `TaskApi`, `FileStoreApi`, `SourceApi`, `FakeMicrosoft`, `FakeTavilySearch`,
     `FakeAuthorizationServer`, `TestDatabaseProvider`, the test config (`application-test.properties`), and the three
     boundary specs `security.TenantApiKeySpec`, `TenantUserSpec`, `TenantAdminSpec`
   - every other spec by its feature names first (`grep -rnE '^\s+(def|void) "' Backend/src/test/groovy` - a feature
     method reads like the rule it tests), then in full wherever a matrix cell would be ⚠️/❌, a name does not say what
     is asserted, or one of the greps below hits; say in the result which specs were read in full and which by name
   - `FileStoreServerSpec` runs only with `FILE_STORE_TEST` and `CapacitySpec` only with `LOAD_TEST` - say so instead
     of counting them red
   - compared against the main code (list the controllers' endpoints and the services' public methods by `grep`)
   - Focus:
     - the checklist in CLAUDE.md "Backend tests (always)", for every feature: happy path admin, tenant admin,
       tenant user and tenant API (`/api/v1`), every validation rule, 404/tenant isolation, roles (`TenantApiKeySpec`,
       `TenantAdminSpec`, `TenantUserSpec`), 409, state transitions
     - do the fakes behave like the real providers, MCP servers and A2A agents (tool calls, streaming, errors; `FakeA2aAgent` runs the SDK's own server core)
     - spec independence (own tenant, `fake.reset()`), `transactional = false` where needed
     - flaky patterns (sleeps, fixed ports, order dependence, time/date dependence, clock precision -
       PostgreSQL keeps microseconds, Linux clocks give nanoseconds), found by `grep` over all specs (`sleep`,
       `Thread.`, a literal port, `Instant.now`, `LocalDate`, `@Stepwise`, `@Shared`) and read where it hits
   - Deliver the coverage matrix (see below) in addition to the findings.
10. **Frontend state & services**:
   - `store/**` (actions/reducer/selectors/effects, with their `*.spec.ts`), `services/**`, `models/**`,
     `app.config.ts`, `app.routes.ts`
   - Focus:
     - NgRx conventions from CLAUDE.md (one slice per feature, slices holding one tenant at a time via
       `TenantScope` - also `mcpKey` with its keys' areas, software and tasks, `task`, `fileStore` (with the folder
       listing for the picker, `browseFolders*`) and `knowledgeSource` (polling while a source runs, ended on leaving
       and logout) -, late results for another tenant ignored,
       `load*Failure` in `load-failure.effects.ts`, logout resets every slice)
     - effects: `switchMap`/`concatMap`/`exhaustMap`/`mergeMap` choice, reload after writes and refusals
     - the chat stream (`chat.service.ts`, `sse.util.ts`): events, connection loss, stop, decided tool calls
     - models matching the backend DTOs
     - error messages via `extractErrorMessage()`
11. to 13. **Frontend UI, infrastructure & tests** - three agents, each on its own files (a `*.spec.ts` belongs to the
    agent of the file it stands next to):
    - 11 **shared building blocks**: `components/shared/**` (the panels and dialogs the admin and the tenant admins
      share, `app-shell`, `page-header`, `dialog-save.ts`)
    - 12 **pages & chat**: every other folder of `components/**` (the chat, the admin's and the tenant users' pages,
      login, account, the task editor and run view), `guards/**`, `interceptors/**`
    - 13 **utils, languages & tests**: `utils/**`, `i18n/**`, `Frontend/src/i18n/*.json`,
      `Frontend/src/environments/**` - also the look's rules where a util or a global style decides them
      (`styles.scss`: every colour a token, light and dark)
    - Focus (the same list for all three; each takes the points its files can show and says which it could not judge):
      - components never call services directly
      - `selectSignal` instead of `| async`; `takeUntilDestroyed`; signals/effects without loops
      - form dialogs through `DialogSave` (stay open on 400/409)
      - knowledge areas in the UI: the document panel's area choice and the upload target (never the company
        knowledge unasked), area selects showing the stored areas until saved, nothing enforced only in the frontend
      - Markdown bound with `[innerHTML]` through Angular's sanitizer, never `bypassSecurityTrust*`; links other
        than http(s)/mailto stay text
      - keys shown once and never kept in the store longer than needed; the key dialog (`components/shared/key-dialog`,
        `utils/key-form.util.ts`) for API and MCP keys showing only what the tenant has and sending only what it shows
        (left out: unchanged), a deleted area, connection or task dropped from every key in the lists
      - languages (i18n.md): every user-facing text a key in `en.json` and `de.json` - also what lint cannot see: a
        text put together in code, a snackbar or dialog text translated in a field initializer instead of at the
        moment of the event, a util returning a sentence instead of a key, Angular's `date` / `number` pipes instead
        of `translocoDate` / `translocoDecimal`, an added `i18n` attribute; values from users as params, never part
        of a key; German addressing the user with "Sie"; nothing of the language stored in the browser; English
        code, industry-neutral wording in both languages
      - form validation matching backend validation
      - memory leaks (object URLs, subscriptions, the CodeMirror view destroyed with the editor, the run view's
        polling ended on leaving and logout)
      - the task editor (`task-source-editor`, `task-graph`, `task-editor`, `guards/task-editor.guard.ts`): an edit
        kept while marks change (the view rebuilt only for a new text from outside), the graph from the last text that
        compiled, the guard asking only while signed in; the file store dialog (one form, a fields component per type -
        SFTP/FTPS, S3, SharePoint - `utils/file-store.util.ts` `requiredFields`, `passwordNeeded`) asking for the
        password or secret on create and for another server, bucket or app only, and sending only the fields of its
        type
      - knowledge sources (`components/shared/knowledge-sources-panel/**`): the folder typed or chosen
        (`folder-picker-dialog`), overlapping folders pointed out, the run's confirmation naming what is open and that
        scans cost, removing asking "Dokumente behalten" or not, every backend code of a source translated with its
        params (store, bucket, site, library); the reranker panel and the model dialog's tier and cache prices
        (`utils/ai-model.util.ts`, the group validator mirroring `model.tierIncomplete`); the run's cost in the task
        views
      - which frontend logic has no unit test but should have one (utils, reducers, selectors, what a dialog sends)

Give every agent the same self-contained prompt skeleton (the agent has no conversation context),
with its own area, file list and focus filled in:

> You are reviewing the CURRENT STATE of a real codebase: not a diff, not a PR, not recent
> commits. Read every file listed below in full, and read the repository's `CLAUDE.md` first: it
> is the binding convention for structure, security, code style and tests; the rules of your area are in its file
> under `docs/architecture/`. Read `local-data/review/decisions.md` too.
>
> Area: <name>. Files/directories: <paths>. Focus: <focus points>.
>
> Context:
> - Zirmo is a generic AI platform for companies of any trade (Micronaut 5 / Java 25 backend with Micronaut
>   Data JDBC, PostgreSQL with pgvector, Flyway, Spock tests, langchain4j, Micronaut MCP, the official A2A Java SDK
>   behind controllers and adapters of ours; Angular 22 frontend with Material and NgRx).
> - Tenants are the customers. Each has a database of its own (embeddings, conversations); the management database
>   holds tenants, users, keys, the model catalog, usage and the knowledge areas with who is assigned to them. A
>   document is company knowledge (everyone finds it) or belongs to an area (only its users and keys, and tenant
>   admins, find it). Tenant users chat, tenant admins keep the tenant's
>   settings, the admin keeps everything. The client software uses `/api/v1` with an `ai_` key, MCP clients such as
>   Claude Code use `/mcp` with the tenant's `mcp_` key, A2A clients `/a2a` with a key that opens that door, and the
>   chat may use the tools of the tenant's own software through its MCP server or ask a connected A2A agent - one
>   such agent may answer the tenant's whole chat (`docs/architecture/a2a.md`).
> - AI provider keys and connection keys are stored encrypted; documents and chats are the company's data.
> - Backend code, comments and messages are English; every frontend UI text is a translation key with its text in
>   `Frontend/src/i18n/en.json` (the reference) and `de.json` (formal German, "Sie").
>
> Rules:
> - **This is read-only**: do not edit files, do not run the application, never touch the developer's dev database,
>   and never call an external API, AI provider, MCP server or A2A agent.
> - You may use `grep`/`find` to find callers or tests of what you read.
> - Judge with proportion (CLAUDE.md "Code style"): the goal is lean, readable code, not perfection.
>   - Report only damage that happens with today's code and use (or a use certain to come), never theoretical scale.
>   - No abstraction in advance, no optimisation without a measured or certain load, no configuration option nobody
>     asked for: a missing cache, pool, interface or framework is a finding only with the load or the wrong result it
>     causes today.
>   - Suggest the smallest fix that removes the damage (deleting or simplifying beats adding), and give its size:
>     S (a few lines, one spec), M (one class and its specs), L (several classes or a design change). When the fix
>     costs more complexity than the damage is worth, say so ("Fix lohnt sich derzeit nicht").
>   - Do not inflate severities; many papercuts stay Niedrig. "Nothing to report" is a valid answer for a focus point.
>   - This limits what you recommend, never what you examine: concurrency, transactions, resource and memory leaks,
>     security and real performance problems are examined in full depth.
>
> Evaluate everything you read against exactly these seven criteria. An issue may fit more than
> one; list it under its primary one:
> - **Bugs**: incorrect logic, wrong results, crashes, unhandled exceptions or edge cases.
> - **Stabilität**: race conditions, transactions, error handling, retries, resource handling (connections, pools,
>   streams, temporary files), anything that could wedge the embedding, leave a conversation or a pending action
>   inconsistent or exhaust a tenant's pool.
> - **Funktionalität**: behaviour that doesn't match what CLAUDE.md / `docs/architecture/` or the domain implies,
>   incomplete features, silently wrong output (e.g. an answer drawing on another tenant's documents, a document never
>   embedded, a change run without the user's decision).
> - **Best Practice**: convention violations (CLAUDE.md, Micronaut, Angular/NgRx), missed reuse,
>   dead code, language and industry-neutral convention violations.
> - **Performance**: N+1 queries, missing indexes, loading too much (whole documents or files into memory),
>   unnecessary provider calls, blocking work on the event loop, anything that degrades with many tenants, documents,
>   conversations or files.
> - **Testabdeckung**: behaviour of your area without a test that would catch its breakage,
>   measured against the CLAUDE.md checklist; weak assertions; fakes that deviate from the real
>   provider or MCP server. Name the concrete missing case and the spec it belongs in.
> - **Struktur**: violations of the layer/feature package structure and the dependency direction
>   (controller → service → repository; features only use other features' services; `api/v1`
>   without its own repositories/entities; test packages mirroring the main packages), and in the
>   frontend of the component/store/service split. Also misplaced classes, and classes that are
>   too big or have mixed responsibilities.
>
> For every finding report:
> - file:line
> - the criterion
> - one sentence with the concrete failure scenario (which input or state causes which wrong
>   behaviour)
> - a suggested fix in one sentence, and its size S/M/L
> - a severity:
>   - **Kritisch**: a tenant's documents, chats, files or keys visible to or changeable by another tenant or user,
>     an area's document found by a user, key or MCP client not assigned to it, one API key's conversation reachable
>     by another key,
>     a leaked secret (provider key, API key, MCP key, connection key) or a document's content in the log, a change
>     in a tenant's software run without the user's decision, a request to an internal address an attacker can steer
>     (SSRF), data loss
>   - **Hoch**: the embedding or chat wedges or fails permanently, a feature is broken, an important path is
>     untested, a tenant's pool or the server's memory can be exhausted by normal use
>   - **Mittel**
>   - **Niedrig**: a papercut with little consequence
> - `produktionsrelevant: true/false`, i.e. whether you would insist on the fix before
>   productive use
>
> Do not report what the decision register or a decision in CLAUDE.md / `docs/architecture/` covers (count it as
> "vom Register abgedeckt: E.." or "entschieden in <file>"). Skip purely stylistic nitpicks without consequence.
> Verify every finding against the code (quote the relevant line to yourself) and do not report guesses. Return a
> structured list, not prose.
>
> After the focus points, go through the methods of your area that delete, deactivate, revoke, change a right or an
> assignment, or start work that outlives the request, with the four questions of "Side effects" below (callers,
> other readers, what goes on afterwards, twice at once) - they find what the focus list does not name. Where the place it hurts lies in another agent's area, report it all the same
> and name that area. End with the list "Not described".
>
> <sections 3 and 4 and the subsections "Side effects" and "Not described" of section 5 of `.claude/review-base.md`,
> verbatim>

The test-suite agent additionally gets:

> Also deliver a **coverage matrix**: one row per backend feature (tenants/tenant databases, users/roles/sessions,
> client software API keys, provider keys, model catalog/own model servers (tiers, cache prices), documents/ZIP import/scans, embedding and
> search (reranker), knowledge areas (assignments, scope per asker, push/move/delete), knowledge sources (listing,
> runs, replace/vanish, change, remove), file stores (SFTP/FTPS, S3, SharePoint, folders), scheduled tasks (runs, cost),
> chat/stream/history, chat files/created files, code execution (the tenant's sandbox, a video), web search,
> assistant settings/templates, usage, branding, the handbook's websites, user groups/WAM/access requests, the
> daily report/application log, a connection's OAuth sign-in, MCP server
> (`ask_assistant`, `search_knowledge`, `read_document`)/MCP keys (several, areas and web search per key, the doors), MCP
> connections/tools/levels, changing tool calls, A2A connections (card, skills, the tool way, the answering agent),
> the A2A server (cards, Message, stored tasks, tasks over A2A, webhooks), `call_agent` and the assistant `prompt` step). Use one column per checklist item (Happy Path Admin,
> Happy Path Mandanten-Admin/-Benutzer, Happy Path Tenant-API, Validierung 400, 404/Isolation, Rollen, 409,
> Zustandsübergänge/Fakes). Each cell is ✅ covered / ⚠️ partial / ❌ missing / – not applicable, with the spec name.

## Step 2 - Synthesize

Once all agents and the step-0 runs have returned:

1. Pool every finding and drop what the decision register or a documented decision covers (say how many, by entry).
   Dedupe near-duplicates, such as a service bug that is also visible from its controller or the test agent's view of
   it, and keep the more precise file:line.
2. Spot-check every **Kritisch** and **Hoch** finding yourself by reading the cited lines. Drop
   what does not hold, and downgrade what is overstated.
3. **Proportion pass** over every remaining finding ("Judging with proportion"): drop theoretical scale and
   optimisation or abstraction without today's load; replace an oversized fix with the smallest one that removes the
   damage; downgrade inflated severities; mark findings whose fix costs more than the damage is worth as "Fix lohnt
   sich derzeit nicht". Say how many findings this pass dropped or changed.
4. Structure the report:
   - **Überblick**:
     - test run (number of specs/tests, failures, duration), frontend tests
     - lint result
     - numbers of findings per severity, and how many the proportion pass dropped or changed
     - two or three sentences on the overall state, including what is solid and should stay as it is
   - **Befunde by Härtegrad**, in the order Kritisch, Hoch, Mittel, Niedrig; drop empty buckets.
     Tag each finding with its criterion, Backend/Frontend and the fix size S/M/L, then give the description and the
     suggested fix.
   - **Testabdeckung**: the coverage matrix from the test-suite agent (checked against the other agents'
     Testabdeckung findings; which specs it read in full and which by their feature names), plus the frontend test
     situation in two or three sentences.
   - **Nebenwirkungen**: which methods the agents went through and the rows of their side-effect pass that
     held - each also a finding in its bucket above, here with the caller, reader or job it was found at.
   - **Nicht beschrieben**: what the code does that neither this skill, CLAUDE.md nor `docs/architecture/` mentions,
     one line each - to bring the skill or the file up to date.
   - **Struktur**: a short verdict on the backend and frontend structure. Where does the code match
     CLAUDE.md and `docs/architecture/`, and where does it drift? Include suggestions for updates if the code
     outgrew a rule.
   - **Für den produktiven Betrieb**: a concrete punch list of actions, phrased as actions and not
     as a restatement of the findings, ordered by priority. Build it from every
     `produktionsrelevant: true` finding and every Kritisch/Hoch finding - only what is needed for productive use, no
     nice-to-haves.
   - **Bewusst nicht empfohlen**: the findings marked "Fix lohnt sich derzeit nicht" and the improvements the proportion
     pass dropped, one line each with the reason - so the user sees what was weighed and why it stays.
   - **Kandidaten fürs Register**: findings the user will likely accept as intended (a deliberate trade-off, a
     documented limit), each as a ready-to-paste entry for `local-data/review/decisions.md` (English, in its format).
5. Present the report in German (headings, severity labels, descriptions). Keep file, class and
   method names and identifiers as they are, and use clickable `path:line` references relative to
   the repository root.
6. Do not fix anything unasked. At the end, offer to work through the punch list, e.g. starting
   with Kritisch/Hoch. When the user answers that a finding is intended, offer to add it to the register.
