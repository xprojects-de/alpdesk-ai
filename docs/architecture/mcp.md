# MCP: the tenant's MCP server, and MCP clients to the tenant's software

Decided 7 October 2026 (roadmap points 10 and 2). Two directions of the Model Context Protocol, both on **Micronaut
MCP** (decided 7 October 2026, https://docs.micronaut.io/5.2.x/mcp/): `micronaut-mcp-server-java-sdk` for the server,
`micronaut-mcp-client-java-sdk` for the clients - both on the official MCP Java SDK (`io.modelcontextprotocol.sdk`):

- **MCP server** (built): an MCP client - Claude Code, Claude Desktop, Cursor, ... - asks the tenant's assistant.
- **MCP client** (built: connections, tools, levels, reading tools in the chat, changing ones after the user's
  decision): the tenant's assistant uses the tools of the tenant's own software (its ERP or
  accounting system) through that software's MCP server.

## The tenant's MCP server

**What it is**: a user of the tenant who sees no list of documents. It asks the assistant and gets its answers with
their sources (file names); an answer may quote what the assistant read, since 10 October 2026 also longer parts of a
document (`read_document`, [ai-features.md](ai-features.md) "Reading on in a document": a how-to's whole example for
Claude Code). Since 24 October 2026 it also searches and reads itself (`search_knowledge`, `read_document` - "Searching
the knowledge" below): passages and parts of the documents it finds, still never a list of documents or a download.
Its answers and searches draw on the company
knowledge and the knowledge areas of the MCP key it is asked with ([ai-features.md](ai-features.md) "Knowledge
areas"), and - where the key may (off by default) - on the web ([ai-features.md](ai-features.md) "Web search"; the
pages' addresses join the "Sources:" line). Deliberately not an OpenAI-compatible
API: Claude Code brings its own model and takes extra knowledge through MCP tools.

- **The doors of a key** (2.0.0): a key opens `/mcp`, `/a2a` (the tenant's assistant as an A2A agent) or both -
  [a2a.md](a2a.md) "The doors of a key".
- **MCP keys** (decided 24 October 2026, roadmap point 39 - before, one key per tenant with the tenant's areas and web
  search): a tenant has **several** (`tenant_mcp_key`, `mcp_...`), each with a **name, its knowledge areas**
  (`tenant_mcp_key_area`), **the connected software it may use** (`tenant_mcp_key_connection`, point 41) **and the web
  search** (`web_search`), composed for a purpose or a circle of people and
  handed on by the admin - like the client software's API keys. Kept by its tenant admins (page "API-Keys", card
  "Claude und Copilot (MCP)", `/tenant/admin/mcp-keys`) and the admin (tenant page, tab "Keys",
  `/admin/tenants/{id}/mcp-keys`): `GET` the list (prefix, areas, software, web search, creation, last use); `POST`
  (`{name, webSearch?, areaIds?, connectionIds?}` - name required, 400 `mcpKey.nameRequired`; web search off, no area
  and no software unless given) creates one and shows it once; `PUT .../{id}` renames it, switches its web search or
  changes its areas or its software (left out: unchanged; another tenant's area 400, another tenant's connection 400
  `mcpKey.unknownConnection`); `POST .../{id}/renew` gives it a new secret - the previous one stops working
  at once, name, areas, web search and its creation (its place in the list) stay; `DELETE .../{id}` ends it with its areas. Another tenant's key answers 404.
  Only the SHA-256 hash is stored (`services.mcp.McpKeyService`). **The scheduled tasks are chosen per key too**
  (decided 25 October 2026 - before, one switch per task for every key, `task.open_to_mcp`, dropped by `V12`, which
  gave every key the tasks opened so far): `taskIds` on `.../mcp-keys`, `tenant_mcp_key_task`, "Aufgaben" in the key's
  dialog (with tasks allowed), `mcpKey.unknownTask` (400) for another tenant's task - like the API keys' tasks.
  The usage stays the tenant's. Deleting a connection or a task takes it off every key; renewing a key keeps its
  software and tasks. The key a tenant had before became "Standard" with the tenant's areas and web search
  (migration `V12`). Limits: a key is a shared secret - whoever leaves keeps it until it is renewed; no rights per
  person (OAuth, point 37 "Later").
- **A description per key** (decided 5 November 2026, roadmap point 51 package 1 - a general chat asking several
  topic tenants could not tell them apart: every tenant's server describes itself alike): `tenant_mcp_key.description`
  (`V17__release_2_0_0.sql`), what the key releases in the tenant admin's words, written in the key's dialog under
  the name. `description` on `.../mcp-keys` - left out on create none, on a change unchanged, empty removes it; at most
  500 characters (400 `mcpKey.descriptionTooLong`). **Per key, not per tenant**: a key may see only some of the
  tenant's areas - a description of the whole tenant would promise what the key cannot reach. It goes to every
  client of the key (see "The brand"); without one everything stays as it was. Our own MCP client keeps a server's
  instructions (2,000 characters) and its tools' descriptions when it reads the tools, so a general chat takes a
  topic's changed description over with "Neu laden" (`POST .../mcp-connections/{id}/reload`) - there is no scheduled
  re-read.
- **The key reaches `/mcp` and - with its A2A door - `/a2a`, nothing else**: `security.McpKeyTokenValidator` yields `ROLE_MCP` only, with the tenant
  id and the key's id (`mcpKeyId` - its areas and web search) - no `/api/v1` (documents), no `/tenant/**`. An inactive tenant's key answers 401. The API keys and the users'
  logins do not reach `/mcp` (403).
- **Transport**: Micronaut MCP's HTTP server (`micronaut.mcp.server.transport=HTTP`, endpoint `/mcp`, server info
  `ai-assistant` with `app.version` - each tenant's client is answered with the tenant's brand, see "The brand"): MCP's "streamable HTTP", stateless - every JSON-RPC message is `POST /mcp` and
  answered with one JSON response, a notification gets 202, no event stream (`GET /mcp`: 405) and no session id. The SDK
  agrees the protocol version and answers `initialize`, `ping`, `tools/list` and `tools/call`; an unknown method is
  JSON-RPC error -32601, an unknown tool -32602 (HTTP 400). Its sync tools run on a worker thread, not the event loop.
- **Security**: the module's controller carries no `@Secured`, so `/mcp` is guarded in `application.properties`
  (`micronaut.security.intercept-url-map`: `/mcp` → `ROLE_MCP`; `TenantApiKeySpec` checks the entry). The tools read
  the tenant from the MCP key's authentication, which reaches them as the principal of the
  `MicronautMcpTransportContext`. No `Origin` check: the server is reached with a bearer key, not by a browser.
- **Tools** (`services.mcp.AssistantMcpTools`, both `readOnlyHint` and not destructive). They are registered as the
  SDK's `SyncToolSpecification` beans (Micronaut MCP takes them next to `@Tool` methods) with their **input schemas
  written out, flat**: plain JSON Schema types, a list's `items` inline, no `$ref` - so every client and model takes them
  (decided 7 October 2026: as much standard as possible). `@Tool` methods were tried: for a list argument Micronaut gives
  only `{"type": "array"}`, and its JSON Schema processor gives the items as a `$ref` to a URL no client can reach. The
  SDK checks the arguments against the schema before the tool runs; a mismatch is a tool error (`isError`). **Every
  failure of ours is a tool error too** (`AssistantMcpTools.guarded` around each of our tools' handlers, review of 25
  October 2026): the database briefly out of reach, a provider's key no longer decryptable - "The tool <name> failed -
  try again later", logged by the exception's class only. Thrown on, the SDK made it a JSON-RPC error with the
  exception's text, sent with HTTP 500, and the MCP SDK's client ended its whole session; a template's prompt likewise
  answers "could not be fetched - try again later" as its text. What we add to `initialize` and `tools/list` (the
  brand, the key's software) and `prompts/list` cannot be a tool error: a failure of ours there is a JSON-RPC error
  "The server could not answer - try again later", logged by the exception's class (`BrandedMcpServer.failed`) -
  never the exception's text, which Micronaut MCP's controller would send on.
  - `ask_assistant(question, template?, files?)`: the assistant answers (`ChatService.answerOnce`) with the tenant's **default
    model** (`tenant_assistant.default_model_id`, "Standardmodell für Claude Code" in the assistant settings - 409 as a
    tool error while none is chosen), the tenant's instructions and settings, and the template of that name (case does
    not matter; an unknown one is a tool error naming the templates). The text ends with "Sources: ..." of the documents
    found. **Nothing is stored** (decided): no conversation, no title - only the usage, as the tenant's (no user). The
    answer's files go with it ("Files for the client" below). A refusal or a failing provider is a tool result with
    `isError`, which the client's model reads. **What it reaches** (written down 5 November 2026 for a general chat
    asking topic tenants, roadmap point 51): the key's areas, the tenant's default model, instructions, settings and
    templates, the key's connected software (none for a key given to another tenant), the web search if the key
    allows, files created and sent - and **the asked tenant's own sandbox**: `run_python` / `run_shell` are offered
    whenever that tenant has a sandbox that answers, whoever asks (`CodeSessions.available`); the session belongs to
    that one answer and ends with it. A general chat asking topic A thus runs code in A's sandbox with A's token, never
    in its own (`CodeExecutionSpec`).
  - **Scheduled tasks** (`TaskMcpTools`, [tasks.md](tasks.md) "MCP"): `list_tasks` (the tasks a tenant admin assigned
    to the key the call is made with - `tenant_mcp_key_task`, set at the key, not in the task's text), `run_task(task)` (by name, case does not
    matter; starts a run and waits up to `app.task.mcp-wait` (60 s) for its end - then the check points not ok with
    their evidence lines, else the run's id and state) and `task_run(task, run)` (read-only). An unknown or closed task,
    a run still open, a task switched off, a tenant without the admin's switch: a tool error the model reads.
  - **Files for the client** (roadmap point 29, decided 14 October 2026 - the server is used for programming, with
    Claude Code and GitHub Copilot): the files the answer creates (`create_file`, `create_image`) and the files a
    connected software's tool returns - an MCP software that passes through to MCP clients only; one that answers only
    keeps its files inside and the model is told so (`McpTools.keep`, security review of 9 November 2026); an A2A
    agent has no such switch, its files are its answer - go with
    `ask_assistant`'s result, each an MCP `EmbeddedResource` after the text
    (`AssistantMcpTools.withFiles`; an answer that only created files has no empty text part before them). A file
    whose content is text - code, Markdown, CSV, JSON, a log - goes as
    `TextResourceContents` (a text file of no known type as `text/plain`): every client shows it, and its model can
    write it into the project. Anything else - PDF, DOCX, an image, a ZIP - goes as `BlobResourceContents` (base64),
    as it is: what the client does with it is the client's matter (decided: no unpacking of archives). The `uri` is
    `file:///<name>`; name and type are the ones the chat keeps (a connected software's types only from
    `ChatFileService.RETURNED_TYPES`). **Kept nowhere**: `ToolContext.Files.DELIVERED` lets the tools create and take
    files like the chat (`Files.KEPT`); they are written to the storage for the moment of the answer, read into its
    result (`ChatService.delivered`) and deleted at once, also when the answer fails. At most 8 MB of files per answer
    (`ChatService.MAX_DELIVERED_BYTES`), 5 files as in the chat; a file that does not fit is named in the text without
    its content, a smaller one after it still goes. A connected software's file is not read for the model here (no
    Tika, no page images - no `read_file` follows without a conversation), only stored for the moment. The model is
    told the file goes to the asker with the answer and not to repeat its content - by the tool and by its rules
    (`DELIVERED_FILE_RULES`, the chat keeps `KEPT_FILE_RULES`). The client software with a key that keeps no history
    still gets no files (`Files.NONE`). Claude Code (tried with 2.1.295) shows a text
    resource to its model and saves a blob in its session folder, giving its
    model the path (a ZIP it can then unpack); GitHub Copilot is not tried yet. For the client software without
    history, its `/api/v1/chat` answer would need them as base64 in its JSON; that comes with a case. Files from the
    client to us stay text only (`files` below): MCP has no upload.
  - **`files`** (decided 7 October 2026): text files the question is about, e.g. source code to check against the
    company's rules - `[{name, content}]`. MCP has no file upload: a tool takes JSON arguments only, so the client reads
    the file and sends its text. They go with the question, each under `=== The file "<name>" (sent with the question)
    ===`, and are kept nowhere; the search for excerpts still goes by the question alone. Larger ones the model searches
    and reads itself (`search_file`, `read_file_lines`) - the same rules and limits as the client software's
    ([ai-features.md](ai-features.md) "Files sent with a question"); a file that cannot come is a tool error. Text only:
    binary files would have to come as Base64, which the client's model writes out character by character; they come
    when a real case asks for them. The default model's context must hold them (an own Ollama server: `num_ctx`).
  - `list_templates()`: the templates' names, as a user sees them.
- **Templates as prompts** (roadmap point 29, decided 14 October 2026): the tenant's chat templates are the server's
  MCP prompts, which clients offer as slash commands - Copilot `/mcp.muster-ai.code-review`, Claude Code
  `/mcp__muster-ai__code-review`. The server's part of the command (`muster-ai`) is the name the client was given
  (the MCP key card's command and `mcp.json`, "The brand"); the prompt's name is the template's, written the same way
  (`AssistantMcpPrompts.promptName`: "Code-Review" → `code-review`, "Prüfung" → `pruefung`, at most 40 characters;
  a name taken twice stays the oldest template's, a later one gets its id, `code-review-17` - not a number by position,
  which a template added later would move to another one), its title the template's name, its description "Asks Muster AI (the company's
  assistant) with its template ...". One optional argument, `question`. `prompts/get` gives the client's model one
  user message: "Ask Muster AI with the tool ask_assistant and the template "Code-Review". The question: ..." - without
  a question, "what I am working on right now", with the full context and the files. The templates' instructions never
  go out. Each tenant has its own templates, so `BrandedMcpServer` answers `prompts/list` and `prompts/get` itself
  and adds the prompts capability to `initialize`; nothing is registered with the SDK. A prompt whose template was
  deleted since the client listed it - or a request without a name - asks without a template - not a JSON-RPC error:
  Micronaut MCP's transport answers errors with HTTP 400, which the Python SDK's client takes for a broken connection
  and ends its session (tried 14 October 2026). A template added later shows at the client's next `prompts/list`
  (Claude Code and Copilot read it when the server is connected or reloaded).
- **The connected software only for the keys given it, and only to read** (decided 7 October 2026; per MCP key since
  24 October 2026, roadmap point 41 - before, a switch per connection for all keys, `mcp_server_reads`, dropped by
  `V12`): a question over MCP has no user. A key gets none of the tenant's connected software unless a tenant admin
  (or the admin) gives it some - "Software" in the MCP key's dialog, `connectionIds` on `.../mcp-keys`,
  `tenant_mcp_key_connection`. Then `ask_assistant` with that key may use the software's turned-on reading tools
  (`McpTools.forMcpKey`, the key's id in the `ToolContext`): Claude Code asks, our assistant looks it up there, weighs
  it and answers. Its changing tools never - there is no conversation to ask in and no user whose level would count.
  Whoever holds the key reads what those tools give, and the answers go to the asking client. A connection that
  passes through gives the same key its tools directly too ("Connected software through to MCP clients" below) -
  there, and only there, also the changing ones where the key may change in that software.
  `McpSpec` holds it: a key without the software gets nothing, another key of the tenant neither.
- **Claude Code** - see "Connecting Claude Code" below.
- **GitHub Copilot (VS Code)**: `.vscode/mcp.json` with the server under the brand's name (`"muster-ai"`), `"type":
  "http"`, the URL and `"headers": {"Authorization": "Bearer ${input:muster-ai-key}"}`, the key as a `promptString` input
  (`"Muster AI MCP-Key"`) with `"password": true` - VS Code asks once and keeps it encrypted.
- Tests: `controller/mcp/McpSpec` (keys, rights, protocol, the flat schemas, tools, files, the brand); the smoke test
  initializes and asks through the AOT jar.

### The brand

Decided 10 October 2026 (roadmap point 22): the server goes by the tenant's brand - the name of the look shown for the
tenant, the tenant's own branding name, else the installation's (`app.branding.name`, [branding.md](branding.md)). A
tenant branded "Muster AI" has a server `muster-ai` in Claude Code, and when its people ask "Frag Muster AI ..." or for
"Muster-Wissen", the model knows that is this server. Four places carry the brand:

- **The server's name in the client** - what Claude Code shows in `claude mcp list` and `/mcp` and puts before every
  tool the model sees (`mcp__muster-ai__ask_assistant`): it comes from the command on the MCP key card, which the
  frontend fills with the brand written as a command takes it (`utils/mcp.util.ts` `mcpServerName`: lower case, ä/ö/ü/ß
  as ae/oe/ue/ss, accents dropped, every other run of characters outside `a-z0-9` one `-`, at most 40 characters,
  nothing left → `assistant`). The tenant admin's card reads the tenant's look, the admin's that tenant's branding
  (its own name, else the default). Copilot's configuration takes the same name.
- **The instructions** in the answer to `initialize`, which clients such as Claude Code give to their model - with
  Claude Code's tool search only the tool names and the instructions load at the start of a session, so they are what
  makes the model look for the server when the brand is named (Claude Code cuts them at 4,096 characters): "<brand> is
  the AI assistant of the company ... When the user names <brand> - or a part of the name - or asks about the company's
  own knowledge, ask <brand> with ask_assistant instead of answering from general knowledge."
- **The tool descriptions** in `tools/list`: "Asks Muster AI (the company's assistant) a question ...".
- **`serverInfo.name`** in the answer to `initialize`: the brand as it is ("Muster AI").

**The key's description** (2.0.0, see "MCP keys") joins the brand: in `initialize` a paragraph "This access knows:
<description>" before the tenant's instructions (first, since clients take the start as the server's summary - Claude
Code reads the first 4,096 characters), and the descriptions of `search_knowledge` and `ask_assistant` end with "It
knows: <description>" - a client's model reads the tool descriptions with every question, Claude Code's tool search
only those and the instructions. Only the key's own clients get it; a key without one is answered as before.

**What the assistant offers beyond the documents** (`McpKeyService.offerOf`, 8 November 2026) is the next paragraph and
ends `ask_assistant`'s description (not `search_knowledge`'s, which cannot): "It can also search the web, run Python
and shell code and read the connected software <names> - hand it those parts of a question that concern the company,
with their full context." - the web search where the key allows it and the tenant has the catalog's entry, code where
the tenant has a sandbox entered (not checked on every list - a call says when it is down), the key's software by
name - only where `ask_assistant` is offered a tool of it (`McpTools.forMcpKey`). Nothing for a tenant answering
through its agent: its tools are the agent's matter. Computed, nothing to enter. "That concern the company": a client
such as Claude Code searches the web and runs code itself and should not hand that over at the tenant's cost. Without
the sentence a general chat kept those parts of a question and told the person it had no such tool. The A2A card says
the same (a2a.md).

The SDK serves one server for every tenant, its name and tools fixed at start, so `services.mcp.BrandedMcpServer` wraps
the handler Micronaut MCP's controller calls and rewrites the answers to `initialize` and `tools/list` with the brand of
the MCP key's tenant (read off the event loop). The configured `serverInfo` stays neutral (`ai-assistant`), never the
operator's brand. A brand changed later shows in the answers at once; in Claude Code the server keeps the name it was
added under until it is added again - it is only a local label, the old one keeps working.

### Connecting Claude Code

Our MCP server is a tool server for Claude Code - independent of the model Claude Code itself runs on. The
`ANTHROPIC_BASE_URL` / `ANTHROPIC_MODEL` settings of an installation that runs Claude Code on an own model (e.g. a
self-hosted vLLM server; Claude Code appends `/v1/messages` itself, so that address goes without `/v1`) have nothing to do
with it: Claude Code reaches the assistant through MCP, and any model that calls tools can use `ask_assistant`.

1. **The key**: a tenant admin creates the MCP key (page "API-Keys", card "Claude und Copilot (MCP)") - shown
   once, with the command below filled in - and chooses the default model in the assistant settings ("Standardmodell
   für Claude Code"); without one `ask_assistant` answers with a tool error. **Every user** finds the rest on the page
   "Konto" (card "Claude oder Copilot verbinden", `components/account/mcp-connect`, decided 14 October 2026 to
   keep the hurdle low): the command with `<MCP-KEY>` in place of the key, which comes from a tenant admin (one of
   the tenant's keys, each with its own rights), Copilot's `mcp.json`, both named after the brand, and the settings of a custom connector for claude.ai
   and Cowork (see "Connecting claude.ai and Cowork" below), with an example question. Shown only when the
   tenant has a key (`GET /tenant/account` `mcpServer`); a tenant admin reads where the key is made. The chat itself
   does not know how to connect - it answers from the company's documents.
2. **Add the server** - under the brand's name (here `muster-ai` for "Muster AI", the card's command has it), the address is
   the installation's with `/mcp` (`http://localhost:19100/mcp` for a backend on the same machine):
   ```bash
   claude mcp add --transport http --scope user muster-ai https://<host>/mcp --header "Authorization: Bearer mcp_..."
   ```
   `--scope user`: in every project of this user; `--scope local` (the default): this project only; `--scope project`
   writes `.mcp.json` into the repository - then without the key in it, Claude Code expands environment variables:
   ```json
   {"mcpServers": {"muster-ai": {"type": "http", "url": "https://<host>/mcp",
     "headers": {"Authorization": "Bearer ${MUSTER_AI_MCP_KEY}"}}}}
   ```
3. **Check it**: `claude mcp list` shows `muster-ai ... ✔ Connected`; in Claude Code `/mcp` lists its tools
   (`ask_assistant`, `search_knowledge`, `read_document`, `list_templates` and the task tools). "Failed to connect" with 401: the key is wrong or was replaced; with a
   connection error: the address (and for HTTPS the certificate) is not reachable from that machine.
4. **Use it**: Claude Code calls the tools when the company's knowledge helps - ask for it to be sure ("Frag Muster AI:
   ..."), name a template ("... mit der Vorlage Nachschlagen"), or let it send files ("Lass Muster AI src/Main.java gegen
   unsere Richtlinien prüfen" - it reads the file and sends its text in `files`). The first call asks for permission in
   Claude Code. Each call stands alone: Claude Code carries the context of the conversation, the server keeps nothing.
   What to put into the knowledge so Claude Code writes code the company's way - how-tos, API overviews, a sync
   script for the CI: [docs/code-knowledge.md](../code-knowledge.md).
5. **Timeouts**: an answer with reasoning and documents can take a minute or more; raise Claude Code's tool timeout if
   it gives up earlier: `MCP_TOOL_TIMEOUT=300000 claude` (milliseconds).
6. **Remove**: `claude mcp remove muster-ai --scope user`. A renewed MCP key stops its old secret at once - every
   installation of that key then needs the new one.

### Searching the knowledge

Decided 24 October 2026 (roadmap point 37, step 1): the client's model - Claude in Cowork, claude.ai or Claude Code -
searches the company knowledge and reads on itself, and answers from what it read. A strong client model then does the
thinking, and no second model answers behind it (no second cost, less waiting); `ask_assistant` stays for a template's
answer, files to check against the company's rules, and what needs the connected software. Both tools are always
offered with the MCP key (decided: no switch) and find exactly what `ask_assistant` finds - the company knowledge and
the areas of the MCP key the call was made with (`KnowledgeAreaService.scopeOfMcpKey`). `services.mcp.KnowledgeMcpTools`:

- **`search_knowledge(query)`**: `KnowledgeSearch` with the tenant's number of results (its assistant settings, like
  the chat - decided), each passage under `--- From "<path>" (document <id>[, one passage of <n>, read on from offset
  <o> of <length>]) ---`: the path with its folder like the chat's `similarity_search` (decided), the document's id,
  and for a longer document where to read on (the passage found less 2,000 characters, `ReadDocumentTool.locate`). The
  query (at most 1,000 characters) is embedded and recorded as the tenant's usage like `ask_assistant`'s; no chat model
  is called. Without an embedding model, a missing key or a failing provider: a tool error the model reads.
- **`read_document(document, offset?)`**: by the document's id (decided - unique where two files share a name), not
  by name. `/mcp` keeps nothing between calls, so there is no "found earlier in this answer" and no reading limit per
  answer like the chat's: every call checks again that the MCP server finds the document
  (`DocumentRepository.findFoundText` with its areas - READY only). A short document (up to 12,000 characters) comes
  whole, a longer one 5,000 characters from the offset (from its start without one), with where to read on - the
  chat's `ReadDocumentTool.part`. Unknown, deleted, moved out of the key's areas, waiting to be embedded again,
  another tenant's: all the same "not found" - the server tells no document it does not find from one that does not
  exist. A negative offset is a tool error, one beyond the end is answered with the document's length.
- **The descriptions** (with the tenant's brand) and the server's instructions point the client to these two for facts
  and to `ask_assistant` for templates, files and the connected software (decided: search first).
- Tests: `controller/mcp/McpKnowledgeSpec`; the smoke test searches the uploaded DOCX and reads it by its id.

### Connecting claude.ai and Cowork

Tried on 24 October 2026 (roadmap point 37, step 0): claude.ai, Claude Desktop and Cowork take the server as a **custom
connector** with the MCP key as a fixed request header - no OAuth needed. The connection comes from Anthropic's network
(`160.79.104.0/21`), so the server must be reachable from the internet over HTTPS. In Claude: Settings → Connectors →
Add custom connector - name (the brand), the URL (`https://<host>/mcp`), authentication **"No sign-in"** (with an
OAuth choice the header `authorization` is greyed out: OAuth takes it), request header **`authorization`**, value
**`Bearer mcp_...`**. Claude sends the value exactly as typed: without "Bearer " and its space `/mcp` answers 401. A request header cannot be changed afterwards: a new key means removing the connector and
adding it again. On a Team or Enterprise plan the owner adds it, and everyone in the organisation then asks with the
same key (the tenant's MCP server, not a person). Request headers are a beta of Anthropic's, not offered to every
organisation yet.

The MCP key card shows these fields under the new key (value copyable) and, like the page "Konto", with `<MCP-KEY>`
in place of the key (`components/shared/claude-connector`, the values from `utils/mcp.util.ts` `claudeConnector`).

### Connected software through to MCP clients

Decided and built 24 October 2026 (roadmap point 41): the connected
software an MCP key may use reaches MCP clients - Claude Code, Cowork, claude.ai - **directly**: the client's model calls
its tools itself, without a second model behind `ask_assistant`. The case: a software that only Zirmo reaches (an internal
service, a module in a pod of its own, its key held by Zirmo alone - a key per tenant as the connection's key), and a
tenant's own internal software. Zirmo is the one gate: the module's address and token never leave it, the key decides
who gets what. `services.mcp.SoftwareMcpProxy` (and `SoftwareResources`), answered by
`BrandedMcpServer` - the SDK never sees these tools, prompts and resources.

**Zirmo passes on unchanged what the software says and decides rights only** (decided 11 November 2026, after the
user counted the earlier behaviour - text cut at 100,000 characters, images turned into files, types rewritten, links
fetched by Zirmo, a tool's extra statements and structured results lost - as errors). The software is responsible for
what it sends. The architecture stays: one MCP server at `/mcp`, the connection found by the name in front of the tool,
a new session to the software per call, the MCP Java SDK on both sides - so "unchanged" means what the SDK's types
carry (a field a later revision adds reaches the client once the SDK knows it). Rejected the same day: an address per
software, a relay without the SDK, sessions, a stored registry of resources, a switch for links, forcing the client's
permission question. What Zirmo changes: a tool's name (the connection in front), the start of its description
(`[<connection>] `), its `readOnlyHint` (the tenant admin's mark), a resource's address and name (the connection in
front, below), and the `_meta` keys MCP reserves for itself (a prefix whose second label is `modelcontextprotocol` or
`mcp`, e.g. `io.modelcontextprotocol/...` - they belong to the hop that sends them; in 2026-07-28 the protocol version,
capabilities and subscription ids live there). Every other `_meta` key goes on, `anthropic/requiresUserInteraction` too
(an agent without a person then fails - the software's wish). The chat, `ask_assistant`, `/a2a`, `/api/v1/chat` and the
tasks are unchanged: there Zirmo is the client and keeps its own rules (text cut, links fetched, HTML as text, files
stored - "Files in tool results" below).

- **Two settings, one place each**: the **MCP key** chooses the software it may use (`tenant_mcp_key_connection`,
  "Software" on the key card - for `ask_assistant` and passing through alike); the **connection** says whether it may
  go to clients directly - `mcp_connection.passes_through`, "Direkt an MCP-Clients (Claude Code, Cowork)" on the page
  "Software", `PUT .../mcp-connections/{id}/pass-through` `{passesThrough}` (400 `mcpConnection.passThroughRequired`
  without a value), **off by default**: a software whose raw results must stay inside is answer-only - a key
  with it uses it through `ask_assistant` only, the client never sees the raw results. On, the client's model sees
  them and can keep them. The key card names a software that does not pass through "Name (nur Antworten)".
- **Tools**: `tools/list` of a key adds the turned-on **reading** tools of its connections that pass through - read
  from `mcp_tool`, no call to the software -, named `<connection>__<tool>` within **32 characters** (Claude Code
  shows `mcp__<server>__<tool>`, the model's API takes 64). Names as they are where both are plain (letters, digits,
  `-`, `_` only between them) and fit - `Buchhaltung__find_invoice`; any other is cut, the software's part first, the
  tool keeping at least half, and ends in `__<id>` of the tool - `Warenwirtscha__get_customer___17` (review of 25
  October 2026: a counter `_2` where two came out alike moved a name to another tool when one before it was turned on
  or off, and a client holding the old list called that one). Each name comes from its own tool alone, so a name never
  moves to another tool; `__` is always in it (it tells a passed-through call from ours - our tools never have it).
  **The entry is the software's**: reading the tools keeps each tool's whole entry as the server listed it
  (`mcp_tool.definition`, JSON, migration `V19`; at most 50,000 characters per entry - a larger one refuses the tools
  like a schema too long, `mcpServer.toolDefinitionTooLong`): title, input and output schema, all annotations,
  `_meta`, icons. `tools/list` builds the entry from it with our name, `[<connection>] ` before the software's whole
  description (the column keeps 4,000 characters for the chat) and `readOnlyHint` = not the admin's mark `changes`
  (otherwise the software's own value, which is the same). A tool not read again since `V19` (`definition` null) is
  listed as before - name, description, input schema, read-only or not - until "Werkzeuge neu laden"; so is a statement
  the software adds later (a forced question): the entry is as of the last reading, no scheduled re-read. Name,
  description, schema and `readOnlyHint` stay columns - rights and the chat use them. The changing tools only where the
  key may change in that software (below).
- **Changing tools passed through** (decided 11 November 2026, roadmap point 68 - for a company's own MCP server that
  creates a ticket or files a document elsewhere, called from Claude Code or Copilot): **a level per key and
  software**, `tenant_mcp_key_connection.level` (`READ`, `WRITE` - the user groups' `McpAccessLevel`; migration
  `V18`, every key that existed `READ`). On `.../mcp-keys` it is `changingConnectionIds`, the software of
  `connectionIds` whose changing tools go to the key's client too - left out on create none, on a change those it had
  that it still may use (a software taken off the key loses its level). 400 `mcpKey.changingNotUsed` for one the key
  may not use, `mcpKey.changingNotPassedThrough` for one that does not pass through or is an A2A agent, and
  `mcpKey.unknownConnection` for another tenant's. In the key's dialog "Darf ändern in" below the software, offering
  those of the chosen software that pass through; the key's row says "Ändert in: ...". **A software that stops
  passing through takes the level off its keys** (`McpConnectionRepository.mcpKeysReadOnly` in `setPassThrough`): a
  level left would be in force again the day the switch is turned back on, and no dialog shows it meanwhile.
  - **Why it is right here and nowhere else**: in the chat a changing call waits for the user's decision; a key has no
    user. Passed through, the decision is the MCP client's: its own permission rules say whether its user is asked
    ("What the client does" below) - the tenant admin giving the key the level is our approval, as naming a tool in
    a task's text is. `ask_assistant`, `/a2a` and
    the client software's API keys stay reading: there the tenant's model would call the tool and nobody sees the call
    before it runs (`McpTools.forMcpKey` is unchanged).
  - **Which tools**: those turned on (a tenant admin turns on each, a delete left off reaches no key) and marked
    changing (`changes`). `tools/list` and `tools/call` take the same list (`SoftwareMcpProxy.passed`,
    `McpTools.usable(tool, level)`): a key with `READ` neither lists nor calls a changing tool - the call is the
    unknown tool of above. Marked `readOnlyHint: false`; the software's other annotations (`destructiveHint`,
    `idempotentHint`, ...) go on as it listed them. The mark matters: Claude Code repeats a tool marked read-only by itself after a dropped connection and
    runs it in parallel with others - a changing tool must never be listed as read-only.
  - **What the client does** (Claude Code's documentation, read 11 November 2026 - corrected the same day: the first
    text said "the client asks before each call"): Claude Code asks at the **first use** of every MCP tool, whatever
    its annotations, and its user may allow the tool for good; in auto mode a classifier decides, with bypassed
    permissions or an allow rule (`mcp__<server>__<tool>`) nothing is asked - an agent without a person changes
    without a question. `readOnlyHint` does not steer the question. An organisation enforces it in the client
    (managed settings: `permissions.ask` / `permissions.deny`). **Not built, on purpose**: forcing the question from
    our side (`_meta["anthropic/requiresUserInteraction"]: true` on the tool - Claude Code then asks at every call in
    every mode and refuses it without a person): decided against 11 November 2026, since agents working without a
    person are the case this was built for; if wanted later, a choice of the tenant admin, not fixed. What claude.ai,
    Cowork and Copilot do is their documentation's matter and not checked.
  - **A changing call that failed after it was sent** - no answer in time, an answer without content or too large
    (`mcpServer.timeout`, `.requestFailed`, `.tooLarge`) - may have run at the software all the same; its tool error
    says "... the change may have been made; check before calling again" (`SoftwareMcpProxy.failure`) - the client's
    model repeats a failed call. Not for a software out of reach, a refused key or an error the software answered.
  - **A line in the log for every changing call** (key id, tenant, tool, software - never the arguments): the chat
    keeps who decided what (`PendingAction`), this is the proxy's trace. The software's names go in without control
    characters - a tool's name is the other server's text, and a line break in it would forge a line.
  - **What it costs**: a key is a shared secret - whoever holds it changes what it may change; every call reaches the
    software as the connection's one account (its key or its OAuth sign-in), not as the person at the client;
    between a prompt injection read at the client and the change stands only what the client asks - nothing where it
    is told not to ask. A tool takes JSON arguments only (a binary file would be base64 written
    out by the client's model), and asks no values back during the call (elicitation, point 30 - `/mcp` is
    stateless).
  - Not built: a question back over A2A (the calling agent confirming, `input-required` - roadmap point 68 "Noted for
    later"); a record of the changing calls in the database.
- **Calls**: `tools/call` of such a name goes through `McpClient.passTool` to the software with its key and the
  client's arguments (the software checks them - the SDK checks only its registered tools), off the event loop
  (`boundedElastic`), within `app.ai.timeout`. **Its result goes to the client as the software sent it**: text in full
  length, images and audio as such, embedded files with their own type and address, structured content, `_meta` (the
  reserved keys excepted), `isError`. Nothing cut, nothing turned into a file, no type rewritten (HTML stays HTML), no
  limit of files per answer. Our client does not check a structured result against the output schema (the SDK's
  `enableCallToolSchemaCaching` is off) - the client may. Only what protects the installation is a limit: 8 MiB per
  message of the software (the SDK reads it whole - "Files in tool results" below), 500 tools, `app.ai.timeout`; beyond
  it the call is a tool error saying so ("... sent an answer larger than 8 MB"), nothing is cut silently.
  - **A link to a web address** (`resource_link` with `https://...`) **goes on as a link** - Zirmo no longer fetches it
    for an MCP client: the client has its user's rights there, Zirmo often has none. No switch. A software only Zirmo
    reaches that links to itself with `https` has to embed the file or link to a resource of its own.
  - **A link to a resource of the software's own** (`ticket://...`, any scheme but http/https) **is still resolved**:
    only Zirmo is connected to the software, the client could not read it. `McpClient.passTool` reads it with
    `resources/read` in the call's session (where such a uri lives - it also works for one that lives only as long as
    the call) and puts each content it gets in the link's place, an embedded resource with the address the software
    gave it (it names content already delivered) and the link's annotations and `_meta`. At most 20 links per call and
    8 MB of files embedded together (`MAX_RESOLVED_LINKS`, `MAX_RESOLVED_BYTES` - a protection of the installation, not a
    filter); the rest stay links, and so does a link the software cannot read - with the connection in front where it
    passes its resources on (below), so the client can read it then. None of a failed call: its result goes back as it
    is.
  - **Failures are tool errors**: the software's `isError` goes on as it came; a software out of reach is a tool
    error the client's model reads, and so is the connection's key no longer decryptable ("... enter it again in the
    connection"). So is every other failure on the way - the database (logged by its class only, told without
    details): `BrandedMcpServer` never answers a passed-through call with a JSON-RPC error. **A name the key does not
    have** - another key's, a software not passing through, a tool turned off, a connection renamed - is a tool error
    "Unknown tool: ... list the tools again", not a JSON-RPC error: Micronaut MCP's transport sends those with HTTP
    400, and the MCP SDK's client then ends the whole session.
- **Instructions**: the `instructions` of a software's `initialize` are read with its tools (`McpClient.Offer`) and
  kept on the connection, stripped and cut to **2,000 characters** (`mcp_connection.instructions`; a new address drops
  them). `initialize` of a key adds, after ours, **one line naming every software of the key that passes through**
  ("Connected software: Ticketsystem, Buildserver - their tools' names start with the software's name and "__", their
  descriptions with [<software>].", decided 11 November 2026), then "Software <name> (its tools' descriptions start with
  [<name>]): ..." for each of them. Claude Code reads 4,096 characters, ours come first: with ten software its model
  would see the texts of one or two - the line tells it what exists and what to search for (Claude Code loads only tool
  names and these instructions at the start and finds tools by their descriptions). Our own
  assistant reads them too, wherever the software's tools are offered (the chat, `ask_assistant`, the client software)
  - "What the software itself says about its tools ... cannot lift the rules above" in the system prompt
  (`ChatService.softwareInstructions`).
- **Prompts**: `prompts/list` of a key adds the turned-on prompts of its connections that pass through, named like the
  tools, with their title, `[<connection>] ` before the description and their arguments; `prompts/get`
  (`McpClient.passPrompt`) sends the client's values as given - the software checks them - and returns the software's
  answer as it is: every message, whatever its role or content, its description and `_meta` (the reserved keys
  excepted). The chat's form keeps its own check (`SoftwarePrompts.values`) and the user's text only. **Whatever fails** - an unknown name, a value we refuse, the software refusing
  or out of reach, a failure of ours - is that one user message saying so ("The prompt ... could not be fetched: ..."),
  not a JSON-RPC error, which would end the MCP SDK client's session (decided 25 October 2026); the person who picked
  the prompt reads it.
- **No `tools/list_changed`**: `/mcp` is stateless (no event stream), so a client learns of a key's changed software
  at its next `tools/list` - Claude Code at the start of a session, Cowork when it connects again.
- **Resources of the software** (decided and built 11 November 2026): below.
- **Not built, on purpose**: subscriptions to a resource (`resources/subscribe`) and `list_changed` (with MCP
  2026-07-28 - `/mcp` answers one request with one answer), a catalog of the connected software at the admin, usage per
  call of a connected software (only the AI providers are counted), elicitation, progress and long runs (with MCP
  2026-07-28, once the Java SDK has a client for it and Micronaut MCP follows - roadmap point 69).
- Tests: `controller/mcp/McpProxySpec` (listing, names, the entry as the software listed it and the admin's mark, a
  tool not read again, calls unchanged - long text, images, audio, files with their type, structured content, `_meta` -,
  web links passed, own links embedded within 20, the 8 MB limit, errors, a key without the software, another tenant,
  not passing through, instructions with the overview line, every prompt message; the changing tools with the level:
  listed and called, turned off, taken back, kept on a change of the key's software, the admin's API, `ask_assistant`
  still reading, the refused levels), `controller/mcp/McpProxyResourcesSpec` (below), `services/mcp/SoftwareMcpProxySpec`
  (the names, a changing call's timeout), `services/mcp/SoftwareResourcesSpec` (the address's part, the reserved
  `_meta` keys), `McpClientSpec` (the entry kept, its limit), `McpSpec` (the key's software for `ask_assistant`, the
  choice per key), `McpConnectionSpec` (the switch); the smoke test (a passed-through tool called through the AOT jar,
  its instructions and the overview line); by hand: `devtools/mcp-test-server/proxy_check.py` - the test server as a
  module, the MCP SDK's client with a key with and one without it (its README). Checked 11 November 2026 with Claude
  Code 2.1.321 (Sonnet) through a test stack: an image shown as an image, an embedded PDF saved as a local file, 150,000
  characters whole, a web link left unfetched, structured content read, a resource read by its prefixed address - and
  after a "not found" with the hint it listed the resources, found the right address and read it.

### Resources of the software

Decided and built 11 November 2026 (package 2 of the same day's decision above). A resource is found the way a tool
is - the connection in front. A client reads a resource by its **address** (uri), not by its name, so the connection
goes into the address, separated by a dot (`SoftwareResources`):

| From the software | To the client |
|---|---|
| `ticket://4711/protokoll.log` | `ticketsystem.ticket://4711/protokoll.log` |
| template `ticket://{number}/attachment` | `ticketsystem.ticket://{number}/attachment` |

- **The connection's part** is computed from all of the tenant's MCP connections (not the key's, not the switches'):
  lower case, letters, digits and `-` only (`Lager Süd` - `lager-sud`), starting with a letter (an address must), no
  dot - so the first dot always splits. A name starting with a digit, or without any such character, is `c` and the
  connection's id; parts that come out alike all get `-` and their connection's id (an id has no `-`, so these never
  meet another part). A dot, not `+` or `-`: the user's choice - of the three signs an address may carry before `://`,
  `-` is in connection names and schemes alike, `+` looked wrong. A renamed connection changes its addresses, as it
  changes its tools' names.
- **The switch**: per connection, "Ressourcen an MCP-Clients" on the page "Software" below "Direkt an MCP-Clients",
  `mcp_connection.passes_resources` (`V19`), `PUT .../mcp-connections/{id}/resources` `{passesResources}` (400
  `mcpConnection.passResourcesRequired` without a value, 409 `mcpConnection.notForA2a` for an A2A agent, 409
  `mcpConnection.resourcesNeedPassThrough` for a connection that does not pass through - the frontend greys the switch
  out then), **off by default**. On, every key that passes the software through reads its resources - reading a
  resource is reading; no switch per resource. **A software that stops passing through loses the switch**
  (`setPassThrough`, decided 12 November 2026 - as it takes the changing level off its keys): left on, it would be in
  force again the day pass-through is turned back on, and nobody chose it then.
- **`resources/list` and `resources/templates/list`**: asked live, every time - resources change with every ticket. A
  page of ours is one page of one software, in the order of the software's names; our cursor (opaque, base64) names the
  software and carries its cursor, so nothing is capped or cut and one software is paged at a time. A software that
  fails, or has nothing on its page and no next one, is left out and the next asked in the same request (a client that
  stops at an empty page would never see the software after it) - all within one `app.ai.timeout` (decided 12 November
  2026): with several software out of reach the waits would add up, so past it the page ends empty and its cursor
  leads on. The name a
  client shows (`name`, `title`) gets `[<connection>] ` in front. **A web address is never prefixed** (2026-07-28 says a
  client may fetch an `https` resource directly - a prefix would break that). `initialize` says the server has
  resources (no subscriptions, no `list_changed`) when a software of the key has the switch on; without one, a
  resources request is "method not found" as before.
- **`resources/read`**: the connection from the prefix, the prefix taken off and exactly that software asked with the
  address as it was - nothing guessed, nothing stored; its answer goes back as it is, its addresses with the prefix
  again. An address without a prefix, or of a connection the key does not read: "Resource not found: <uri> - the
  addresses of connected software start with "<connection>."; list the resources (resources/list) to find them" - a
  model that took an address out of a text finds its way (Claude Code did). A web address: not found, "fetch it
  directly". The software's own error (its "not found") goes back with its code and message. "Not found" is `-32002`
  (2025-11-25; `-32602` in 2026-07-28).
- **An error answering a resources request goes out with HTTP 200** (`ResourceErrorStatus`, a response filter on
  `/mcp`, decided 12 November 2026). Micronaut MCP's controller gives every JSON-RPC error an HTTP status (400 for
  invalid params or an unknown method, 500 for the rest - "not found" among them), and the official SDKs' clients take
  that for a broken server and end their whole session, the tools with it (seen with the Python SDK's; Claude Code
  reads the error and goes on). For tools this is why every failure is a tool error; a resource has no such way -
  "not found" can only be a JSON-RPC error, and an address a model guessed wrong must not cost the session. **The
  specification names no HTTP status here** (read 12 November 2026, 2025-11-25 and 2026-07-28 alike: a request is
  answered with JSON or an event stream; 400, 403, 404 and 405 are prescribed for the transport's own failures only -
  a protocol version, a header, an origin, a session, in 2026-07-28 an unknown method with 404); the Java SDK's own
  stateless server sends 200. Only `resources/*`: every other request keeps the status the controller gives (an
  unknown method 400, as shipped since October). **To be checked again when Zirmo moves to 2026-07-28** (roadmap point 69, which collects what that move asks): the code
  becomes `-32602` there, an unknown method must answer 404, and what Micronaut MCP and the SDKs' clients do then
  decides whether this filter stays, widens or goes.
- **Links in a tool result** stay resolved at once and embedded (above) - also a link that lives only as long as the
  call; one left as a link gets the prefix where the switch is on.
- **Addresses the software writes into a text are not touched and not reliably resolved**: Zirmo cannot tell them
  from any text, and MCP does not make an address in a text a link at all - also at a server connected directly it is
  up to the client's model to notice it. The model may still find the resource (it sees the prefixed address in the
  list, or follows the hint of a failed read), but nothing guarantees it. Claude Code's listing shows its model the
  fixed resources only, not the templates: a resource that exists only at run time reaches the model through a link in
  a tool result.
- **What it changes**: the software's addresses, the same kind of change as the name in front of a tool - several
  servers lie behind one address. A client or a server that compares an address with one from a text finds them
  different.
- Tests: `controller/mcp/McpProxyResourcesSpec` (the switch off by default and its API, the lists merged and prefixed,
  page by page with our cursor, a read reaching the right software among two, the answer's addresses prefixed, not
  found with the hint, a web address, the software's own error, a software out of reach in a list of two, a key without
  the software, another tenant, the switch gone with pass-through and refused without it, an A2A agent, a link left
  in a result prefixed), every error of a resources request with HTTP 200 and another method's as before),
  `services/mcp/SoftwareResourcesSpec`; by hand `proxy_check.py`.

### What a server behind Zirmo must observe

For the authors of a company's MCP server that is passed through to MCP clients:

- **Answer the revision Zirmo speaks** (2025-11-25, the MCP Java SDK's): a server that only takes 2026-07-28 cannot be
  connected until Zirmo has moved (the specification: legacy client and modern-only server "Fails"; a server may serve
  both).
- **A file goes as a file**: embed it (an `EmbeddedResource`, or an image as image content), link to a web address the
  client's user can reach, or link to a resource of its own (`resource_link` - Zirmo embeds it). **Never only name a
  resource in the text**: an address in a text is not reliably resolved, behind Zirmo or anywhere - only a
  `resource_link` is a link in MCP.
- **Everything a tool needs as its arguments** - a tool takes JSON arguments only, and asks no values back during the
  call (elicitation needs a session; `/mcp` is stateless).
- **Results within 8 MB** per message (about 6 MB of files after base64).
- **A structured result comes as text too** (the specification's SHOULD - and our own assistant reads the text).
- **The words searched for first in a description**: Claude Code finds tools by their descriptions, and the client may
  cut them.
- **What a model shall find by itself belongs behind a tool**: Claude Code takes a fitting tool before it looks at
  resources (tested with 300 resources, 11 November 2026).
- **A statement added later** (a forced question, a new annotation) reaches clients after "Werkzeuge neu laden" of the
  connection.

## MCP clients to the tenant's software

A connection may also speak **A2A** instead of MCP (`mcp_connection.protocol`, 2.0.0): an A2A agent whose card's
skills are kept as the connection's tools and which the chat asks through one tool - see [a2a.md](a2a.md).

Agreed and built 7 October 2026: connections, tools, each user's level, the reading tools in the chat, and the
changing ones after the user's decision in the chat.

**A connected software that asks Zirmo back** (decided 24 October 2026, no code): a module - a software that only
Zirmo reaches (an internal service, a module in a pod of its own, its key held by Zirmo alone) - that needs the company's knowledge or an answer during its work asks like
any client: with an MCP key of its own (`search_knowledge`, `read_document`, `ask_assistant`) or an API key of its own
(`/api/v1/chat`), at the installation's internal address. It is a separate request with its own key, scope and
transaction; the usage is the tenant's. **Its key must not reach the module itself**, or a chat calling the module,
the module asking the assistant and the assistant calling the module again would loop: the module's MCP key is given
no software (point 41: a key reaches only the software it was given), and neither is an API key the module asks with
(point 42). MCP's own way - sampling, the server asking the client's model during a
call - is not built: when a module needs it.

- **Connections** (`mcp_connection`, `services.mcp.McpConnectionService`): the tenant admins
  (`/tenant/admin/mcp-connections`) and the admin (`/admin/tenants/{id}/mcp-connections`) enter the software's MCP
  server - name (unique per tenant, at most 80 characters), address, optional key. The key is stored encrypted
  (`SecretCipher`), shown as `keyHint` only, and stays with its host: an address on another origin drops it unless a
  new one comes (like own model servers). **Another address is another software**: its tools are read anew and come in
  off, the old ones are removed - what was chosen for a tool (on, `changes`) must not pass to a tool of the same name
  there. For the same reason its resources stop going to MCP clients (`passes_resources` off - the switch opens all of
  them, there is no choice per resource) and every MCP key reads only in it again (the level `WRITE` back to `READ`),
  as when it stops passing through; which keys and groups use the connection, and that it passes through, stay. Only
  the name or the key changed, everything stays.
- **Addresses** (`services.mcp.McpAddresses`): HTTPS, plain HTTP only to the hosts in `APP_AI_PLAIN_HTTP_HOSTS`; no
  user, query or fragment; a host that resolves to an internal address (loopback, private, link-local, `fc00::/7`,
  `100.64/10`) is refused unless it is one of those hosts - checked when saved and again before every call. An unknown
  host is named when the address is saved; at a call the refusal reaches the model, the user and an MCP client, and
  names the connection only ("the host of its address is not known").
- **The client** (`services.mcp.McpClient`): the SDK's `McpSyncClient` over `HttpClientStreamableHttpTransport`, one
  session per use (initialize, list or call, close), introducing itself with the tenant's brand (`clientInfo`, "The
  brand" above) - the software's logs name the assistant by the tenant's brand. `micronaut-mcp-client-java-sdk` builds its clients from
  configuration only (`micronaut.mcp.client.http.*`), so our client is built per connection the way the module's factory
  builds it, with the module's `McpJsonMapper` and `JsonSchemaValidator`. Redirects are never followed (the key goes to
  the address entered, nowhere else), the key goes as bearer, the timeout is `app.ai.timeout`, a tool result reaches
  the model with at most 100,000 characters. A failure is 502 naming the connection, never the address ("refused our
  key", "is not reachable", "did not answer in time", "did not answer as an MCP server"); so is our stored key no
  longer decryptable after `APP_SECRET_KEY` changed ("our stored key can no longer be read - enter it again in the
  connection", `mcpServer.keyUnreadable`): the chat's model reads it like any refusal, a reload names it. A server that answered
  `initialize` is an MCP server: a JSON-RPC error is shown with its own message ("answered with an error: ..."), also
  when it comes with HTTP 400 or 500 - servers on the MCP Java SDK send it so, e.g. for a prompt's value they refuse,
  and the SDK puts it into a transport exception, whose message we read the error from; only a stateful server's 400
  loses it (the SDK ends the session - and, racing its own dismissal of the request, sometimes hands back no result at
  all, which `McpClient` takes the same way), which says "could not carry out the request". Tools are read page by page -
  not with the SDK's `listTools()`, which follows every next page itself, without end -, at most 100 pages: a server
  that sends the same page again or more answers "keeps sending more pages of tools". The server is the tenant's, the
  memory the installation's, so what it may offer is bounded: one message at most 8 MiB ("sent an answer larger than 8 MB"; "Files in tool results" below says why not more), at most 500 tools, a tool's whole entry of at most 50,000 characters (kept for the MCP clients it is passed through to), a tool name of at most 128 characters and an input schema of at most 20,000
  (refused with 502 "offers more than 500 tools" / "offers a tool with ..."), a description cut at 4,000 characters,
  and the software's own error message cut at 500 in our 502.
- **Tools are read as soon as the connection is created** (`mcp_tool`: name, description, input schema, `changes` =
  not `readOnlyHint`, which the tenant admin may correct). A server that cannot be read keeps the connection; the answer
  says why (`toolsError`). "Neu laden" (`POST .../{id}/reload`) reads them again: new tools come in off, dropped ones are
  kept but `offered=false`, and what the admin chose stays - except `changes` when the server changed its word: a tool
  whose `readOnlyHint` differs from the last read (`mcp_tool.read_only_hint`; a software update made a reading tool a
  changing one) takes the server's, so it is asked before every call again. The server can take minutes to answer: what
  it sent is stored on the connection as it is then (read again under a lock) - a key, an address or an access the
  admin or a tenant admin changed meanwhile stays, and tools read from an address that is no longer the connection's
  are dropped. **Every tool is off until a tenant admin turns it on**
  (`PUT .../{id}/tools/{toolId}`) - nothing appears in the chat unasked.
- **Who may use them** (decided 7 October 2026, variant b; by group since 16 October 2026 - [tenancy.md](tenancy.md)
  "User groups"; the MCP server: see "The tenant's MCP server"): each user group gets a level per connection
  (`group_mcp_access`, set in the group's dialog only - `levels` on `.../groups`; the connection's access list shows
  them, `GET .../{id}/access`): none ("kein Zugriff"), `READ` ("nur lesen": the reading tools
  turned on) or `WRITE` ("lesen und ändern": all tools turned on, changing ones asked before every call). A user gets
  the highest level of its groups (`UserGroupService.accessOfUser`), a user in no group none. No matrix of groups and
  tools: exceptions for single tools come when a real case asks for them. An MCP key and an API key of the client software
  get only the software given that key (below). Who asks is told (`ToolContext.Asker`: user, client
  software, MCP server), never derived - the client software's key without history asks without a conversation just
  like the MCP server.
- **Topics deselectable per question** (decided 5 November 2026, roadmap point 51 package 2 - a general chat's
  connected software are topic tenants; a question for one topic should not cost the search in three): the groups set
  what a user may reach at all (the admin's), the choice in the chat narrows it per question (the user's). `ChatRequest
  .excludedConnectionIds` on `/tenant/chat/stream`, `POST /api/v1/chat` and `/chat/stream` - left out the
  conversation's stored choice (none for a new one), empty every connection on again. It only narrows: `Toolbox` drops
  the excluded connections' tools from what `McpTools` offers the asker. An id the asker does not reach (unknown,
  another tenant's, not in the user's groups, not the API key's) is 400 `chat.unknownConnection` - never ignored, so a
  stale choice shows. **Kept with the conversation** (tenant `conversation.excluded_connection_ids`, `V8__release_2_0_0
  .sql`, written with every question that names the list) and returned with it (`ConversationResponse
  .excludedConnectionIds`), so a frontend - ours or a tenant's own - opens it with its last choice; a connection
  deleted or no longer reached since is dropped where it is read, never an error. A key without history takes it per
  question; a decided changing call goes on with the conversation's choice. **The list to choose from**: `GET
  /tenant/chat/software` (the user's, through its groups - the same path as the tools, so a membership the IDM gave or
  took shows with the next request) and `GET /api/v1/software` (the API key's) - `[{id, name, instructions}]`, the
  instructions as stored on the connection (for the client software; our chat shows only the name - the instructions
  are the software's words for a model, in its language, not the person's - decided 8 November 2026); not the tools (the client software still never calls them
  itself). The user's own knowledge areas are not deselectable (decided 5 November 2026). **The chat** shows a chip per
  connection above the input when the user reaches two or more, all on by default; a click switches one off for the
  conversation; all off, the answer comes from the chat's own knowledge alone and the row says so
  (`utils/software-choice.util.ts`, the chat slice's `excludedConnectionIds`).
- **A tool result names its software**: every result of a connected software's tool in the chat starts with
  `[<connection>]` (`McpTools.execute`) - a general chat asks several topics, and a document's id (`--- From "X.pdf"
  (document 12) ---`) holds only within its topic. `SOFTWARE_RULES` (only for a question with software tools) says
  so, and that passages a software's search tool returns are the company's documents too, cited by their file name with
  the software's name; without documents of its own the prompt says "no documents of its own to search". A tenant
  without connected software reads the prompt word for word as before. Passed through to MCP clients
  (`SoftwareMcpProxy`) results stay unchanged - and links to a web address stay links there: **the general chat over
  topic tenants gets a link where a topic's software links to a web address** (accepted by the user, 11 November
  2026): its own client fetches it only when the address is reachable from the general tenant's Zirmo, allowed by its
  address rules (HTTPS, no internal address) and needs no key of the software (our bearer goes only to the
  connection's own origin - here the topic's Zirmo). A link inside the company's network, or one that needs the
  software's key, is a note "could not be fetched" for the general chat's model. Links to a topic software's own
  resources are embedded by the topic's Zirmo and arrive as files. End to end: `controller/chat/GeneralChatSpec` (a tenant asks another's
  `/mcp` of the same installation with its key).
- **The client software only for the keys given it, and only to read** (decided 7 October 2026, revising the same
  day's "the client software gets no access"; the case: a client software asks about data of its own and the
  assistant looks things up through a connected software's reading tools; **per API key since 25 October 2026**, roadmap point 42 - before, one switch per connection for every key,
  `client_software_reads`, dropped by `V12`, which gave every key the connections opened so far). An API key gets the
  connections chosen for it - "Software" in the API key's dialog, `connectionIds` on `.../api-keys` (none unless
  given, another tenant's connection 400 `apiKey.unknownConnection`), `tenant_api_key_connection`, deleted with the key
  or the connection. Then `/api/v1/chat` with that key - with or without history - may use the software's turned-on
  reading tools (`McpTools.forApiKey`, the key's id in the `ToolContext`); another key of the tenant gets none of
  them. Its changing tools never: there is no user to decide. Whoever holds the API key reads what those tools give.
  `/api/v1/me` does not name the key's software (decided - the client software never calls the tools itself). What the tools can reach at
  all is the connected server's decision (and our key's rights there): a server should offer no tool that reads more
  of its file system or code than it means to share. With a key that keeps history the tools' results go into the conversation like a user's;
  for data from outside (logs, say) a key without history is the better fit - nothing is stored. Such data may carry
  text written by outsiders (prompt injection): the tools only read and the answer goes to the key's holder, so the
  client software must not pass answers on unchecked to the people whose text is in the data.
- **In the chat** (`services.mcp.McpTools`, `Toolbox`): the user's tools of the connections it has a level for, named
  `<connection>__<tool>` (only `A-Za-z0-9_-`, at most 64 characters, unique), described with the connection's name in
  front; `ChatService` adds rules for software tools. A tool's input schema goes to the model with only the top-level
  keys langchain4j takes on an object (`McpTools.objectSchema`): the Python SDK's `title` or the TypeScript SDK's
  `$schema` made it give up on the whole schema, and the model got a tool without parameters. `READ` gets the reading tools, `WRITE` the changing ones too. The
  log names only the tool, never arguments or results; a software's error (`mcpServer.error`, 502) reaches the user
  with its text but the log by its code only - the text may quote what a user typed into one of its prompts.
- **Changing tools** (decided 7 October 2026): a call of a tool marked `changes` is never run at once.
  - The answer stops there (`AgentLoop.continueWithTools`, `Toolbox.askingFirst`; other calls of the same response
    are not run). Its text is stored as usual, and the call as a `pending_action` of the tenant database with the
    answer: software and tool names, the model's call id, name and arguments, the model and the tool's ids, `PENDING`.
    The answer (stream "done", `GET .../messages`) carries it as `action`; the chat shows a card with software, tool
    and arguments and "Ausführen" / "Abbrechen" (`components/chat/chat-action-card`).
  - `POST /tenant/chat/actions/{id}/run` or `.../cancel` (`ChatService.decide`) streams like `/tenant/chat/stream`.
    Only one decision wins: the row is claimed `PENDING` → `RUN`/`CANCELLED` atomically, a second click answers 409.
    Run: the tool must still be turned on for the user with `WRITE` (else 409), the software's call runs before the
    stream starts and its answer is kept as `result`. Cancel: the software is never asked. Then the model goes on from
    the stored history - the stopped answer with the note of what became of its call, as for a later question - and a
    message of its own with the decision: the software's whole answer ("The user decided: the call you stopped at was
    run ...") or "The user declined the call you stopped at - it was not run" (`ChatService.RUN_NOTE`, `DECLINED`). Not
    the call with its tool result (until 11 October 2026): the call's message as the provider sent it is not kept - its
    reasoning, Gemini 3's thought signature - and Gemini 3 refuses a tool result without the signature of its call;
    the change had run, and every continuation failed (`FakeAiModels` refuses such a result too). Its text is stored as
    the next answer, without a question (`done.question` null). It may stop at another changing call. A template the
    conversation used, deleted meanwhile, gives way to the tenant's settings (`AssistantService.forConversation`): the
    call can still be decided. The provider failing after a run leaves the call `RUN` with its result; the next
    question's history says so. Files the software returned with the run go to the answer that stopped at the call
    (`ChatService.keepConfirmedRunFiles`), not away with the failed one: the change cannot run again to bring them.
  - **Expiry**: a call waits at most a day (`ChatService.ACTION_LIFETIME`, shown as `EXPIRED` from then on, written
    down when someone tries it), and a new question in the conversation expires every call still waiting - it no
    longer fits the history. That happens when the question is taken (`ChatService.prepare`), not when its answer is
    stored: also when that answer fails, and a stopped answer stored later never expires a newer question's call. The
    other way round, a call such a late answer asks for is stored `EXPIRED` at once (`ChatService.storePendingCall`):
    every question marks its conversation when it is taken (`ConversationRepository.markAsked`, `updated_at`) and every
    stored answer when it is stored, so an answer that finds the mark changed since it was prepared knows the
    conversation went on - also when it was only a decided call's answer stored meanwhile, which errs to the safe
    side. Both answer 409 "This action expired - ask again".
  - Another user's call answers 404. The client software and `ask_assistant` never get changing tools: they have no
    user. An MCP key gets them only passed through to its client, whose own rules decide whether its user is asked
    (above, roadmap point 68).
  - What a tool may change at all is the other side's decision: its tools and the rights of our key there.
- **Files in tool results** (e.g. a tool returning a generated project as a ZIP archive): a tool may return files the way MCP provides for them - an `EmbeddedResource` in the `CallToolResult`, as
  `BlobResourceContents` (base64) or `TextResourceContents`, and images as `ImageContent`. `McpClient.callTool`
  decodes them into `CallResult.files` (name = the last part of the resource's `uri`, an image `bild`; the type the
  server says); content that is no valid base64 is left out and noted in the text for the model; audio is still left
  out (`[audio content left out]`); a `resource_link` is fetched at once (below, roadmap point 63). **A result's
  structured content reaches the model** (decided 12 November 2026 - before, it was dropped): without any text it is
  the text, as its JSON (the specification asks for a text beside it but does not require one, and the model got
  nothing); beside a text it follows it as `=== Structured result ===` and its JSON (`CallResult.structured`,
  `McpTools.appendStructured`) - the text may be a summary only, and an MCP client gets both through the proxy too.
  Not twice: where the text is that JSON already, as most servers send it, the text alone. Within the result's 100,000
  characters; beyond them the structured part is left out with a note, never cut. A task's `call_tool` step reads the
  text as before (`CallResult.text` - a second JSON after it would break its search for the array). `McpTools.execute` (it gets the answer's
  `ToolContext`, also for a changing call run by the user's decision) stores each file like one of `create_file`
  (`ChatFileService.createReturned`) into `ToolContext.createdFiles`: it hangs at the answer as `GENERATED`, the
  download below it, and the model is told the file was created and not to write a link to it.
  - **Types**: only those of `ChatFileService.RETURNED_TYPES` - archives (ZIP, gzip, tar, 7z), PDF, Office and
    OpenDocument, text, logs, CSV, Markdown, JSON, XML, YAML, and the images PNG, JPEG, GIF and BMP
    (`DocumentImages.IMAGES` - what ImageIO reads, no WebP). Anything else - `text/html`, `image/svg+xml` - is stored
    as `application/octet-stream`: the software is the tenant's, and the download is an attachment anyway. A name
    without an extension gets the type's (`datei.tar`, `bild.png`).
  - **Documents and images are read like an upload** (`ChatFileReader.read`, review of 14 October 2026): a PDF's or
    DOCX's text is extracted, a scan's pages and an image are stored as the JPEG copies the model looks at with
    `read_file` (an image is shown below the answer) - and the file takes the type its content shows when it differs
    from the one the server said (text named `.pdf` is `text/plain`, HTML named `.txt` is `octet-stream`). A file the
    reader refuses (no text, unreadable) is a plain download.
  - **Other text**: the model reads a file's text with `read_file` when its type is a text type (JSON, XML, YAML) or
    its name a text or code file's (`.log`, `.yaml`, `.java`, ... - servers name text types very differently), and the
    content is valid UTF-8. For any other file (an archive) `read_file` says it has no text the model can read - and
    where the tenant has a sandbox, that the model opens it with `run_shell` or `run_python`: every file of the
    conversation is in the session's `data/`, so "what is in the package?" is answered by unpacking it there (without
    the hint the model stopped at "no text"); without a sandbox, that the file is for the user to download. Reading
    archives in `read_file` itself was left out on purpose (14 October 2026): the sandbox does more, and a second way
    to unpack would need its own limits against ZIP bombs - a table of contents only, for tenants without a sandbox,
    is the step to take when such a case comes.
  - **The text of a returned text file is in the tool's result at once** (`McpTools.appendTexts`, decided 14 October
    2026): `=== File "<name>" (returned by the tool) ===` and its text after the tool's own text, within the result's
    100,000 characters (a longer one cut, the rest named) - as Claude Code shows its model a text resource. Kept as a
    file, the model could otherwise read it only with `read_file` from the next question on, and over the MCP server
    never: a software that sends its files as text resources lets our model choose and adapt them in the same answer.
    Also where no file is kept (the client software's key without history) and with a failed call. Not an archive or a
    binary file (roadmap point 31: unpacking, the sandbox, handing a file on unchanged).
  - **A failed call's files** (`isError`) are not stored: the user would get a download instead of an answer, and the
    agent loop would take the files for the answer. The model hears `[file "<name>" returned with the error - not
    offered]`.
  - **Files returned as a link** (`resource_link`, MCP since 2025-06-18; roadmap point 63, 9 November 2026): a
    software that hands out a file as a temporary download (valid once, for minutes) returns a link instead of the content. `McpClient.callTool` fetches it **at once**, before
    it returns - later the download has expired: a uri of the server's own (any scheme but http/https) with
    `resources/read` in the same session (where such a uri lives; the SDK refuses it for a server without the resources
    capability; no answer at all - too slow, the connection lost - is a failure for now like a download's, an error
    the server answers is final), an http(s) address after the session with `LinkDownloads`: the connections' address rules
    (`McpAddresses.requireAllowed` - HTTPS, plain HTTP only to `app.ai.plain-http-hosts`, no internal address) before
    the request and after every redirect, followed by hand (3 at most); at most 50 MB (`ChatFileService.MAX_FILE_BYTES`,
    a chat file's - the JDK's `BodyHandlers.limiting` stops the body there) and 60 s for the whole download. **Our
    bearer goes only to the connection's own origin** (scheme, host, port); a download host elsewhere - the storage a
    link was signed for - never sees it, the link carries its own right. The file is a `ReturnedFile` like an embedded
    one (named by the link's `name`, else its uri's last part; typed by the link, else the server's `Content-Type`), so
    the chat and a task's documents source take it unchanged. **Not for MCP clients** (decided 11 November 2026): the
    proxy passes a web link on as it is and resolves a link to the software's own resource itself ("Connected software
    through to MCP clients" above). Only as many links as one call's result leaves room for among the 5 files are fetched, none of a failed
    call (`[file "x" not fetched - ...]`) - counted per call, not per answer: a later call of the same answer may fetch
    a file the answer then leaves out with a note (a task has no answer to count against; not worth threading
    through). A link that gives no file - expired (404/410), another status, too large, an
    address refused or no address at all (a space in it, another scheme, none), too slow, a uri the server cannot
    read, a token that cannot be renewed then - is a note for the model, `[file "x" could not be fetched: <reason>]`,
    never a failed call (the tool has run by then); a task's document without a file takes it as its reason
    (`McpClient.unfetchedNotes`). Not built: a link only written into the text (nothing tells a file from any link),
    fetching when the model asks (expired by then), A2A's file parts with a uri. Specs: `McpClientSpec` (both kinds, the
    bearer per origin, every refusal, the size, the limits), `McpConnectionSpec`, `TaskDocumentsSpec`.
  - **Limits**: the answer's 5 files (`CreateFileTool.MAX_FILES_PER_ANSWER`, shared with `create_file` and
    `create_image`; one more is left out with a note), and 8 MiB per message of the server (`McpClient.MAX_MESSAGE_BYTES`,
    about 6 MB of files after base64) - a larger result fails as "did not answer as an MCP server". Not more on
    purpose: the SDK reads an answer line by line through the JDK's `LineSubscriberAdapter`, which searches the whole
    line again for every chunk received, and a file's base64 is one line - the time grows with the square of the
    line's length, so the message is capped at 8 MiB.
  - A question over the MCP server sends the files to the MCP client with the answer ("Files for the client" above).
    Where an answer takes no files (the client software's key without history) nothing is stored, the model only
    hears `[file "<name>" returned - files cannot be offered here]`. The log names neither the file nor its content.
- Our key at the software is the tenant's, shared: the software does not tell our users apart - the levels above do.
- **Prompts of the connected software** (decided and built 14 October 2026, version 1.4.0): a software's MCP server
  may offer prompts - ready-made requests with arguments (Copilot shows them as slash commands). Our client reads them
  with the tools, in one session (`McpClient.read`; prompts only when the server declares the prompts capability; the
  same limits as tools, with errors of their own, at most 20 arguments) into `mcp_prompt` (migration V6: name, title, description, the arguments as JSON,
  `enabled`, `offered`) - **each off until a tenant admin turns it on** (`PUT .../mcp-connections/{id}/prompts/{promptId}`,
  like a tool); a reload keeps the switch, a prompt the server no longer lists (or a server that dropped its prompts
  capability) is `offered=false`; another address removes them with the tools. Prompts that cannot be read keep the
  tools read and the stored prompts as they were, and are named in `promptsError` ("Prompts nicht lesbar: ..."). **In the chat** (`services.mcp.SoftwarePrompts`,
  `controller.mcp.SoftwarePromptController`, `/tenant/chat/prompts`, Roles.TENANT_USER): a user with a level for the
  connection (READ or WRITE) sees its turned-on prompts behind the button "Prompts der angebundenen Software" (bolt,
  next to the paperclip) with the software's name; picking one opens a form of its arguments
  (`components/chat/chat-prompt-dialog`, required ones required). `POST /tenant/chat/prompts/{id}` `{arguments}` asks
  the software (`prompts/get`, only the prompt's own arguments, trimmed, at most 10,000 characters each - 400
  otherwise, 404 for a prompt the user may not use or one removed meanwhile, 502 when the software fails or gives no
  text) and returns the
  text of its user messages (an assistant's message, images and resources left out), cut to a question's 20,000
  characters. **The text goes into the input field** - after what is typed there - **never sent by itself**: the user
  reads it and sends it, so a prompt of the tenant's software triggers nothing unseen (decided). The values come from
  the user's form, not from the model - for a tool that needs exact values (a package name), a prompt is the way to
  get them without elicitation (roadmap point 30). Not for the MCP server's and the client software's questions: they
  have no form.
- **Frontend**: the tenant admin's page "Software" (`/app/software`) and the admin's dialog "Angebundene Software" in the
  tenant list share `components/shared/mcp-connections-panel` (slice `mcp-connection`, one tenant at a time): per
  connection its address, key hint and when the tools were read (a reading error shown), "Werkzeuge neu laden", the
  tools with their switch, "ändert etwas" and what they do in the chat now, the prompts with their switch and their
  values (required ones marked), the instructions its server gave (cut to 2,000 characters, said so), the switch
  "Direkt an MCP-Clients (Claude Code, Cowork)", and each user's level ("Kein Zugriff" / "Nur lesen" / "Lesen und ändern"). The API keys and the MCP keys are created and changed in one dialog each key (`components/shared/key-dialog`, through
  `DialogSave`: name, areas, software, tasks, web search and - an API key - its history, one under the other, each with
  what it means; only what the tenant has, and what it does not show is not sent - `utils/key-form.util.ts`); the
  tables show a key's rights as short tags (`key-rights`), an MCP key's actions in a ⋮ menu; the MCP key panel shows the Claude Code command once with the new key, and Copilot's
  `.vscode/mcp.json` whenever there is a key - it holds no key, VS Code asks for it.
- Tests: `services/mcp/McpClientSpec` (the client against `FakeMcpSoftware`: sessions, key, JSON and event stream,
  readOnlyHint, redirects, internal addresses, pages, the limits of a listing, cut results and errors, returned files),
  `services/mcp/McpToolsSpec` (tool names, schemas), `controller/mcp/McpConnectionSpec` (connections, tools, levels,
  the chat, the client software's access, returned files at the answer, the prompts - read, switched, listed only with
  a level, filled with the user's values -, tenant isolation); `FakeAiModels` calls a software tool for `[[call <tool> <json>]]`. The smoke test runs the client in
  the AOT jar: its fake Ollama also answers as a software's MCP server, the connection reads its tools, and a tenant
  user's chat calls `find_invoice` through it, then the client software's `/api/v1/chat` once its API key is given the
  connection, and - switched "Direkt an MCP-Clients" and given to an MCP key - the key's tools/list carries it, the client
  calls it itself, and its instructions follow ours in initialize.

### Signing in to the software's server (OAuth)

Roadmap point 61, built 9 November 2026 (2.0.0). A hosted MCP server or A2A
agent that takes no fixed key signs its clients in with OAuth 2.1 - authorization code with PKCE, a client registered
by metadata document or dynamically, rotating refresh tokens - as the MCP authorization specification (2026-07-28)
says. The connection signs in **once, as the tenant admin who clicks**; from then on it behaves like a connection with
a key, and the token renews itself.

- **Protocol-neutral in `services.oauth`**: `OAuthMetadata` (the authorization server's metadata - RFC 8414 at
  `/.well-known/oauth-authorization-server[<path>]`, then OpenID at `/.well-known/openid-configuration[<path>]`, then
  `<path>/.well-known/openid-configuration`; the document's `issuer` must be the one asked for; RFC 9728's resource
  metadata), `OAuthClient` (the flow and the grant), `OAuthHttp` (every call of the flow), `OAuthAddresses` (our own
  addresses), `OAuthReachability`. The messages are built and parsed by Nimbus (`com.nimbusds:oauth2-oidc-sdk`) as a
  library only: our JDK `HttpClient` sends them - never Nimbus' own `send()`, which follows redirects and knows nothing
  of our address rules. MCP and A2A only say **where the authorization server is** and send the token as bearer.
- **Where the server is**: MCP - a 401 to reading the tools (`McpClient.KeyRefused` carries its `WWW-Authenticate`):
  the header's `resource_metadata` (only on the connection's own origin - a server must not send us elsewhere), else
  `/.well-known/oauth-protected-resource<path>`, else `/.well-known/oauth-protected-resource`; the first authorization
  server listed is taken; the scopes are the header's `scope`, else the resource metadata's `scopes_supported`, else
  none. A 401 without any of them stays "refused our key" (a server that wants a fixed key). A2A - the public card's
  security requirements naming an `OAuth2SecurityScheme`: its `oauth2MetadataUrl` (its issuer must own that address),
  else the authorization code flow's URLs (no metadata, so no registration: the client id comes by hand - and no
  issuer: the grant's is empty, an `iss` such a server sends has nothing to be compared with); a scheme
  without an authorization code flow is `oauth.noAuthorizationCode` in `toolsError`. **A 401 with metadata is a result,
  not an error**: the connection is kept, `toolsError` null, its grant `AVAILABLE`.
- **The grant** (`oauth_grant`, management database, one per connection, deleted with it): the server's endpoints,
  whether it reads a client id metadata document and whether it sends `iss`, our client id there (`client_id_source`
  `METADATA_DOCUMENT`, `REGISTERED` or `ENTERED`, bound to the issuer and the token endpoint - another one starts over:
  the client's secret and the refresh token go to the token endpoint, which an A2A card naming the URLs alone could
  otherwise move to a host of its choosing; such a server has no issuer, so its token endpoint alone decides - a card
  naming another sign-in page with the same token URL keeps the client id and the sign-in), the pending
  sign-in (`state`, the PKCE verifier encrypted, the page to return to, `pending_until` - 10 minutes), the tokens
  (encrypted, `SecretCipher`), `expires_at`, the scopes granted, `last_error` (never a server's own text). Status
  `AVAILABLE` → `PENDING` → `SIGNED_IN`; a refused renewal → `SIGN_IN_NEEDED` (the tokens go). A refused or expired
  sign-in puts the grant back where it was - a signed-in connection stays signed in, keeping its client id (its tokens
  belong to it: a server that cannot read our document then, or a sign-in over it left past its time, changes nothing
  of it; a grant whose client id is gone anyway asks for a new sign-in, never fails the call). A sign-in nobody finished
  in its time is shown as what the grant goes back to, not as running. A key replaces the sign-in (a key entered
  deletes the grant; signing in clears the key; an A2A card naming a key and a sign-in as alternatives offers no
  sign-in while the connection has a key); a new address deletes it (its resource was the old one); a reload that
  reads the tools without a token drops a grant nobody signed in to.
- **Our client identity - the software decides**: 1. the client id held for this issuer (made for this callback
  address); 2. **our client id metadata document** (`/oauth/client-metadata.json`, anonymous, built from the request:
  its own URL as `client_id`, the installation's brand name, the callback, `none`) when the server announces
  `client_id_metadata_document_supported` **and** the host the admin uses resolves to public addresses only
  (`OAuthReachability`) - a server cannot fetch a document of an installation inside a company network; 3. **dynamic
  registration** (RFC 7591, `application_type` web, `none` when the server offers it, else a secret) when the metadata
  has a `registration_endpoint`; 4. **by hand**: 409 `oauth.registrationNeeded` - the dialog shows our redirect address
  and fields for the portal's client id and secret. The specification puts the document before registration without
  the reachability condition; we deviate for the installation in a company network. A server that cannot read our
  document (an error back, or a sign-in left pending past its time) is remembered on the grant: the next sign-in
  registers.
- **The flow**: `POST .../mcp-connections/{id}/sign-in` `{returnUrl}` (tenant admin and the admin's mirror; the page to
  return to must be absolute http(s) on the request's host, any port, and a page of the frontend - `/app/...` or
  `/dashboard/...` below the context path in plain segments, no query or fragment: the callback sends the server's
  answer there, the code included, so never a path serving a tenant's own content (a handbook's website, whose
  scripts could pass it on) - else 400 `oauth.returnUrlForeign`; 409
  `oauth.notAvailable` for a connection whose server never asked) answers `{authorizationUrl}`: `code_challenge` S256,
  `state` (single use), `resource` (RFC 8707, the connection's address), the scopes offered. The browser comes back to
  **`/oauth/connections/callback`** (anonymous; our address from the request's `Host` and `X-Forwarded-Proto` and the
  context path - behind a proxy the header must arrive, as with the published Caddyfile and nginx example). **The
  callback exchanges nothing** (security review of 9 November 2026): it looks the state up and sends the browser 302 on
  to the page the sign-in started on with `connection=<id>&signIn=complete&<the server's answer>`; an unknown state has
  nowhere to go - a plain 400 page. The page - signed in as whoever opens it - completes it with
  `POST .../mcp-connections/{id}/sign-in/complete` `{callback}` (tenant admin and the admin's mirror; 404 for another
  tenant's connection): the grant found by its state under the row's lock **and only for the one who started it** (the
  tenant and connection, `started_by_user_id` - null for the admin), else 409 `oauth.stateUnknown` and the sign-in
  stays pending for its owner - a sign-in link passed on to someone else (a person of another company signing in at
  the same hosted software) signs in no one. Then the state is cleared, `iss` checked (RFC 9207 - required when the
  metadata announced it, compared with the metadata's issuer when it comes; a mismatch is refused and the response's `error` texts are neither
  shown nor stored), the code exchanged with the verifier and `resource`, the tools or the card read with the new token
  - the answer is the connection; a failure is its code (409 `oauth.stateExpired`, 502 `oauth.denied`,
  `oauth.issuerMismatch`, `oauth.exchangeFailed`). The frontend reads the connections after it either way. `DELETE .../sign-in` removes the sign-in (the frontend asks first, then reads
  the software again - it names its sign-in anew). A refused start (409 `oauth.registrationNeeded`) leaves the redirect
  address and the client id's fields to show: the frontend reads the connections again.
- **The token in every call**: `McpConnectionService.credential(connection)` (`services.mcp.Credential`: the bearer and
  how to renew it) - the chat, the proxy, the prompts, the API keys' software and the tasks go through it, nothing has
  a line of its own. A token expiring within 60 s is renewed before the call; a 401 renews it once and the call (MCP:
  the whole session) is repeated once - done in `McpClient`/`A2aClient` themselves, not by the MCP SDK's authorization
  handler, whose retry resends the request built before the renewal. A renewed token refused again means the sign-in
  is gone at the server (the client or the grant revoked there): `SIGN_IN_NEEDED`, as after a refused refresh
  (`Credential.signInLost`). A streamed A2A question (a tenant answering through its agent) hears the 401 as its
  stream's end: `AgentAnswers` renews on the asking thread and sends the message once more - refused before the stream
  began, it never reached the agent. An A2A task is asked for on its own: a token refused while it runs is renewed for the
  asking only - the message is never sent twice. A 403 (more rights, step-up) is not renewed, at either door: the call
  fails readably ("needs a sign-in"), **Anmelden** asks for the server's scopes anew. **Rotation and the race**:
  the refresh runs under the grant's row lock; a refresh that finds the stored token no longer the one that failed
  takes that one instead of refreshing again, and a new refresh token is stored only when the answer has one - two
  calls at once never spend the one valid token. **A token belongs to the address it was signed in for**: a call takes
  the connection's address with it and gets a token (or a renewed one) only while the grant's resource is that address
  - a call still open to an address the connection has left since (changed, signed in anew) gets `oauth.signInNeeded`,
  never the new address's token. The exchange and the registration write only onto the grant they started on: one
  replaced meanwhile (another server) gets neither tokens nor a client. A refused refresh (400/401, `invalid_grant`) marks `SIGN_IN_NEEDED`
  (502 `oauth.signInNeeded`, the chip "Anmeldung nötig", a line in the daily report); a server that cannot be reached
  or answers in a way we cannot read leaves the grant as it is (`oauth.unreachable`, `oauth.timeout`,
  `oauth.refreshFailed`). Tokens no longer decryptable (`APP_SECRET_KEY` changed) mark `SIGN_IN_NEEDED` too.
- **Every address of the flow we call** is an absolute http(s) address and passes `McpAddresses.requireAllowed`
  (HTTPS, plain HTTP only to `APP_AI_PLAIN_HTTP_HOSTS`, no internal address), no redirect, connect 10 s, 30 s for an
  answer's headers and body together (not `app.ai.timeout`: a refresh holds its row lock and a database connection,
  and the database ends a transaction idle for a minute; the deadline is the caller's, a request's own timeout would
  end with the headers), an answer of more than 64 KB is not taken. **The server's sign-in page** - the one address
  we never call, the browser is sent there - is checked when a sign-in starts, before a registration
  (`OAuthHttp.signInPage`): a web address only (a `javascript:` one would run in the installation's own page; the
  frontend checks it again, `isWebAddress`), HTTPS unless the installation names its host. Its name is not resolved:
  a page only the browser reaches is no way into our network, and behind a proxy the server may not know the name.
  Metadata without an authorization or a token endpoint is unreadable (`oauth.metadataUnreadable`). The
  authorization server may be another host than the software; **the token goes to the connection's host only** (A2A:
  `KeyLeak` holds for it). The response DTO (`signIn`) shows the status, the scopes, the expiry, `last_error`, the
  server's host, the client id's source and our redirect address - never a token, a secret or the verifier.
- **Not built** (plan "Not done on purpose"): step-up with a person in the loop, several authorization servers (the
  first is taken), client credentials and device flows, a token per user, a grant shared by connections, a background
  refresh, DCR with an initial access token, OAuth for `/mcp` and `/a2a` as a server (point 37), anything named after a
  vendor.
- Tests: `services/oauth/OAuthClientSpec` (metadata in the specification's order, the client id's four ways, the
  callback once, `iss`, the refresh with rotation, two refreshes at once, the context path, a sign-in page that is
  no web address, addresses the rules refuse never called, an answer stalling after its headers),
  `controller/mcp/McpConnectionSignInSpec` (the same end-to-end sign-in for seven shapes of server - among them an
  identity provider apart, OpenID below the issuer's path, CIMD public and in a company network, a
  portal only, resource metadata at the root -, refusals, renewal in the chat, removal, isolation, the admin's mirror),
  `A2aConnectionSpec` (cards with an OAuth scheme), `McpProxySpec`, `TaskDocumentsSpec`, `DailyReportJobSpec` - all
  against `services/oauth/FakeAuthorizationServer` in the JVM; `FakeReachability` plays both deployments.

### Tools called by scheduled tasks (roadmap point 50)

A task's `call_tool` step calls a tool of a connection with the rows it took out of documents
([tasks.md](tasks.md) "call_tool"): `McpConnectionService.toolForTask` finds the connection by name and a tool turned on
and offered (the check), `toolSchema` gives its input schema for the arguments, `callForTask` calls it - refusing a
tool no longer there or turned off (`task.toolUnavailable`). A changing tool runs without a person: the tenant admin
naming it in the task's text is the approval, no group's level counts. The software is expected to recognise a row sent
again by its `source`.
