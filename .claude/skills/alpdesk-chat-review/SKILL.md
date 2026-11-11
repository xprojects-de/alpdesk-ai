---
name: alpdesk-chat-review
description: "Focused non-diff, multi-agent review of the path of a question in Zirmo: from the chat input (frontend, /tenant/chat/stream, /api/v1/chat, the MCP server's ask_assistant) through ChatService's agent loop (rounds, the last round without tools, system prompt rules, history window, the asker's knowledge areas), the tools (Toolbox, search, files sent with the question, created files, the connected software's MCP tools, changing tool calls waiting for the user's decision, the connected software as topics deselectable per question, the web search through Tavily - only for an asker whose group, API key or MCP server may, and only after the documents) and the streamed answer (server-sent events, stop, logout, switch to another tenant) to what is stored (conversation, messages, files, pending actions - each owned by a user or by one API key) - Python and shell in the tenant's sandbox (run_python / run_shell: one session per answer with the question's and the conversation's files, busy or out of reach told to the model) - and a video in the chat (upload up to 500 MB only with a sandbox, examined with code in the tenant's video sandbox: more rounds and its rules in the prompt, view_images and hand_out_file, stills as a sequence and clips in the frontend, retention of chat files). Answers its key questions, walks the path step by step and checks every failure case (provider fails midway, tool fails, user stops or logs out). Asks which parts to review. Follows the shared review base (no hallucination, no over-optimisation, decision register, at most 10 verified findings, fix packages). Use when the user invokes /alpdesk-chat-review or asks whether the chat and its tools behave correctly - NOT a diff/branch/PR comparison."
---

## What this does

A read-only review of **one question's way to an answer**: asked in the frontend, by the client software or through the
MCP server, prepared with the tenant's instructions, template and history and with **what the asker may find** (the
company knowledge plus the knowledge areas of their groups - a `KnowledgeScope` set once per answer), answered by the model in rounds
that may call tools (search the company knowledge, read and search files sent with the question, create a file, call a tool of the
connected software - a changing one only after the user decided, run code in the tenant's sandbox, search the web
where the documents do not answer - [ai-features.md](../../../docs/architecture/ai-features.md) "Web search"), streamed back word
by word, and stored. **Code** (`run_python`, `run_shell`) runs in a sandbox of the tenant's own, one session per
answer with the question's and the conversation's files in it -
[code-execution.md](../../../docs/architecture/code-execution.md) "The parts", "What the model gets". A **video** is not read by a model: the model examines it with code in the tenant's video sandbox
(ffmpeg, rtmlib's pose estimation), looks at the frames it cut (`view_images`) and hands out stills or a clip
(`hand_out_file`) - [code-execution.md](../../../docs/architecture/code-execution.md) "Videos". It follows the
path step by step, goes through every failure case on it, and checks what is left behind when it breaks off. The
conversation, its messages and files belong to their owner (`ConversationOwner`): a user, or the one API key of the
client software that started it.

**It follows [.claude/review-base.md](../../review-base.md)**: rules, the decision register, no hallucination, no
over-optimisation, what counts as a finding, the verifier, the budget, fix packages and the report. Read it first; this
skill adds parts, paths, key questions and its own report sections.

Relation to the other reviews: how documents get into the search, and which area a document lands in, is
`alpdesk-knowledge-review`; whether a changing tool call can be forced by prompt injection or reach another tenant, and
SSRF of the MCP client, is `alpdesk-security-review` parts 6 and 12, whether an attacker can reach another area's
documents its part 7, and whether the code in the sandbox stays confined (also with a video) its part 8 - here the same scope is checked for correctness: every entry point and every round carries the
asker's areas, and a conversation reaches no other owner; whether every round is counted and billed is `alpdesk-usage-review`.
The MCP keys, the `/mcp` server itself, the proxy and the connections to the tenant's software (addresses, `McpClient`,
reading tools and prompts) are `alpdesk-mcp-a2a-review` - here only the software's tools as the agent loop calls them.
A scheduled task's steps go through the same `AgentLoop` (`Asker.TASK`, `onlyTools`, `stopWhen`) - that path is
`alpdesk-task-review`; here only that the chat is untouched by it. Here the question is: is the answer built,
streamed and stored correctly, and does the chat stay usable after every failure. Answer quality (wording, prompt
tuning) is a finding only with a concrete wrong behaviour that breaks a rule of `ai-features.md`.

## Step A - choose the parts

- If the arguments name parts (`teil=1,3`, `alle` ...), take them.
- Otherwise **ask** with `AskUserQuestion` - one question, `multiSelect: true`, header "Teile", question "Welche Teile
  des Chats soll das Review prüfen?", options:
  - "1: Agent-Loop & Speichern" - ChatService, Runden, Systemprompt, Verlauf, Vorlagen, Titel, Wissensbereiche des Fragenden, wem die Unterhaltung gehört (Benutzer oder API-Key), was gespeichert wird, /api/v1/chat, ask_assistant; der Mandant, dessen Chat ein A2A-Agent beantwortet (kein Modell, Kontext je Unterhaltung, Rückfrage, Stopp, Löschen)
  - "2: Werkzeuge" - Toolbox, Suche, mitgesendete Dateien, erzeugte Dateien, MCP-Werkzeuge, ein A2A-Agent als ein Werkzeug (<connection>__ask), Themen pro Frage abwählbar (bei einem antwortenden Agenten dessen Skills), ändernde Aufrufe (run/cancel)
  - "3: Dateien im Chat" - Upload, Lesen (Text, Bilder, Scans, Audio), Ausgabeformate, Download, Löschen, Aufbewahrung (abgelaufene Dateien)
  - "4: Stream & Frontend" - Server-sent Events, Stopp, Abmelden, Mandantenwechsel, Chat-Store, Themen- und Skill-Chips (A2A-Agent), Markdown-Anzeige, Aktionskarte
  - "5: Code & Video im Chat" - Python und Shell in der Sandbox des Mandanten (eine Sitzung je Antwort, Dateien hinein und heraus, belegt / nicht erreichbar, eigenes Sandbox-Image); Video: Upload bis 500 MB nur mit Sandbox, Video-Sandbox, mehr Runden und Video-Regeln im Prompt, view_images / hand_out_file, Bildfolge und Clip im Frontend, Aufbewahrung des Videos
  Four options per question at most: ask parts 1-4 in one question and part 5 in a second (header "Teil 5", options
  "Ja" / "Nein"). Nothing chosen (or "Other: alle") means all five.

## Scope

Base paths `Backend/src/main/java/de/alpdesk/` (below `…/`), tests `Backend/src/test/groovy/de/alpdesk/`, frontend
`Frontend/src/app/`, tenant schema `Backend/src/main/resources/db/tenant/` (`V1__init.sql`, `V2__document_area.sql`:
`conversation.api_key_id`).

| Part | Where |
|---|---|
| 1 Agent-Loop & Speichern | `…/services/chat/{ChatService,PreparedChat,ChatResult,ConversationTitles}.java`, `…/services/assistant/AssistantService.java` (instructions, templates as they reach the prompt), `…/repositories/chat/ConversationRepository.java`, `…/entities/chat/{Conversation,ConversationOwner,Message,MessageRole}.java`, `…/services/document/{KnowledgeAreaService,KnowledgeScope}.java` (`scopeOfUser`/`scopeOfApiKey`/`scopeOfMcpKey` as the loop uses them), `…/services/tenant/ApiKeyService.java` (`revoke` deletes the key's conversations), `…/security/TenantContext.java` (`apiKeyId`), `…/controller/chat/ChatController.java` (conversations), `…/controller/api/v1/ChatApiController.java`, `…/services/mcp/AssistantMcpTools.java` (`ask_assistant`), `…/dto/chat/**`; the tenant answering through its A2A agent (a2a.md "A tenant answering through its agent"): `…/services/chat/ChatService.java` (`prepareForAgent`, `filesForAgent`, `excludedSkills`, `newUploads`, `streamAgent`, `finishAgent`, the agent branches of `ask` / `answerOnce` / `stream` / `streamOnce`, `deleteConversation`'s cancel), `…/services/chat/PreparedChat.java` (`agent`, `stop`), `…/services/a2a/AgentAnswers.java` (the events mapped onto the chat's, the latch, `Call.stop`, `cancelQuietly`, `warnIfLoop`), `…/repositories/chat/ConversationRepository.java` (`AgentState`, `markWaiting`, `excluded_skill_ids`), `…/controller/chat/ChatController.java` and `…/controller/api/v1/ChatApiController.java` (`sink.onCancel`), `…/controller/account/AccountController.java` and `…/controller/api/v1/TenantSelfController.java` (`answersThroughAgent`), `db/tenant/V8__release_2_0_0.sql` (`a2a_context_id`, `a2a_task_id`, `a2a_waiting`) - the connection, the client and the agent's rights are `alpdesk-mcp-a2a-review` |
| 2 Werkzeuge | `…/services/tools/**` (`ToolContext.knowledge`, `SimilaritySearchTool`; `onlyTools`/`allows` and `stopWhen` stay neutral for the chat - null and false), `…/services/agent/AgentLoop.java` (the stop check before a round), `…/services/document/KnowledgeSearch.java` (only that the scope reaches the search), `…/services/mcp/McpTools.java`, `…/services/mcp/McpClient.java` (only results and errors as the loop sees them), `…/services/chat/ChatService.java` (tool rounds, `decide`), `…/repositories/chat/PendingActionRepository.java`, `…/entities/chat/{PendingAction,PendingActionStatus}.java`, `…/controller/chat/ChatController.java` (`actions/{id}/run|cancel`); the topics per question: `…/controller/chat/SoftwareChoiceController.java` (`/tenant/chat/software`), `…/controller/api/v1/ChatApiController.java` (`/api/v1/software`, `excludedConnectionIds`), `…/dto/chat/{ChatRequest,SoftwareChoiceResponse,ConversationResponse}.java`, `…/services/chat/ChatService.java` (the stored choice, `SOFTWARE_RULES`, `NO_OWN_DOCUMENTS`), `…/services/mcp/McpTools.java` (the `[<connection>]` prefix, `withoutSoftwareName`), `…/services/tools/{Toolbox,ToolContext}.java`, `…/repositories/chat/ConversationRepository.java` (`excluded_connection_ids`), `db/tenant/V8__release_2_0_0.sql`; the web search: `…/services/tools/{WebSearchTool,WebSearchAccess,TavilySearch}.java` (`ToolContext.searchesWeb`/`documentsSearched`/`webSearches`), `…/services/chat/ChatService.java` (`WEB_RULES`, `DOCUMENTS_AND_WEB_ONLY_RULES`, `answerSourceRules`, where `searchesWeb` is set per asker), `…/services/tenant/UserGroupService.java` (`searchesWeb`), frontend `components/shared/tenant-web-search.ts`, `utils/{web-search,chat-tools}.util.ts`, `answerSource` in `utils/chat.util.ts` (a web page's address as a link) |
| 3 Dateien im Chat | `…/services/chat/{ChatFileService,ChatFileReader,SentFiles,SentFile,FileRenderer,OutputFormat,MediaConverter,AudioTranscriber,ChatFileCleanupJob}.java`, `db/tenant/V3__conversation_file_expiry.sql` (`expired_at`), `app.chat.*` in `application.properties`, `…/controller/chat/ChatFiles.java`, `…/repositories/chat/ConversationFileRepository.java` (files by owner), `…/entities/chat/{ConversationFile,FileOrigin}.java`, `…/storage/**` |
| 4 Stream & Frontend | `…/controller/chat/ChatController.java` (`/tenant/chat/stream`), `…/services/chat/ChatService.java` (`stream`, callbacks), `…/services/ai/AiModels.java` (`streamChat`); frontend `services/chat.service.ts`, `store/chat/**`, `store/session.operator.ts`, `utils/{sse,chat-stream,chat,chat-thinking,chat-action,chat-file,markdown,download,software-choice}.util.ts`, `components/chat/**` (the topic chips in `chat.component.ts`), `interceptors/timeout.interceptor.ts`; the switch to another tenant as far as the chat is concerned: `store/logout.meta-reducer.ts` (`SESSION_ENDS`), `store/session.operator.ts`, `store/auth/auth.{actions,effects}.ts` (`switchTenant`), `…/controller/account/AccountController.java` (the switch) |
| 5 Code & Video im Chat | `…/services/chat/ChatService.java` (`CODE_RULES`, when the code tools are offered, the session closed in every path - `ToolContext.closeSandbox`), `…/services/tools/CodeSessions.java` (the first call opening the session with the files, `MAX_OUTPUT`), `…/services/chat/ChatFileService.java` (a video above 50 MB recognised by its first 64 KB and streamed, `chatFile.videoNeedsSandbox` / `videoNeedsVideoSandbox`, `app.chat.video-max-size`), `…/controller/chat/ChatFiles.java` (the upload's parts), `…/services/chat/ChatService.java` (`VIDEO_RULES`, `app.chat.video-rounds` - only with a video not expired, the code tools and the video sandbox), `…/services/sandbox/{Sandboxes,SandboxSession,SandboxFile}.java` (`Sandboxes.video`, `Sandboxes.extras` - a tenant's own image's README, the video sent into the session), `…/services/tools/{ViewImagesTool,HandOutFileTool,ReadFileTool,RunPythonTool,RunShellTool,CodeSessions,ToolContext}.java` (`MAX_IMAGES_PER_ANSWER`, the hint for a tenant without video tools), `…/services/chat/ChatFileCleanupJob.java` (`app.chat.video-retention-hours`); the sandbox only as the app uses it: `Backend/podman/sandbox/{sessions.py,posture.py}` (`VIDEO`, `/health`); frontend `components/chat/{chat-video-preview,chat-image-sequence,chat-image-sequence-dialog,chat-image-preview,chat-file-chip}.component.ts`, `utils/{chat-image,chat-file}.util.ts`, `store/chat/**` (object URLs released with the conversation) |

Specs: `controller.chat.ChatSpec`, `ChatFileSpec`, `ChatMediaSpec` and `services.chat.MediaConverterSpec` (both skipped without ffmpeg - say so),
`ChatFileRetentionSpec`, `ChatImageSpec`, `ChatEventsSpec`, `services.chat.ChatUploadLimitsSpec`, `DeliveredFilesSpec`;
part 5: `controller.chat.ChatVideoSpec`, `CodeExecutionSpec`, `services.sandbox.SandboxesSpec`, frontend
`utils/chat-image.util.spec.ts` (`Backend/podman/sandbox/selftest.py` needs the video image - its video cases are read
and named, not run);
`controller.api.v1.ChatApiSpec`, `controller.mcp.McpSpec`, `controller.assistant.AssistantSpec`,
`controller.document.KnowledgeAreaSpec` (what each asker finds, conversations per API key, revoking a key),
`services.chat.BlankRunsSpec`, `ConversationTitlesSpec`, `FileRendererSpec`, `SentFilesSpec`, `services.agent.AgentLoopSpec` (rounds, the last one without tools, usage per round),
`HistoryNotesSpec`, `services.ai.StreamStopSpec`, `CycleDetectorSpec` (a stream that runs away or goes silent),
`ImageModelSpec` (what `create_image` sends and reads),
`SentSpec`, `services.tools.ToolboxSpec`, `services.mcp.McpToolsSpec`; the topics per question: `controller.chat.SoftwareChoiceSpec`, `GeneralChatSpec`, frontend `utils/software-choice.util.spec.ts`; the tenant switch: `security.TenantUserSpec`, `LoginModesSpec`, `WamLoginSpec` (only the cases of a switch), frontend `store/auth/auth.effects.spec.ts`, `auth.reducer.spec.ts`; the web search: `controller.chat.WebSearchSpec`
(Tavily is `FakeTavilySearch`; `[[web ...]]` / `[[web-first ...]]` in `FakeAiModels`), `services.tools.TavilySearchSpec`;
frontend specs under `utils/` (`sse`, `web-search`,
`chat-stream`, `chat`, `chat-thinking`, `chat-action`, `chat-file`, `markdown`), `store/chat/chat.reducer.spec.ts`,
`store/session.operator.spec.ts`.

## Key questions (every agent keeps them in mind; the report answers each one explicitly)

1. **Does every question end?** A bounded number of rounds, a last round without tools, a stop for a model that repeats
   itself or answers blank - no loop that runs until the timeout and no answer that is silently empty.
2. **What is stored when it breaks off?** Provider error in round 1 or round 5, a tool error, the user stops, the user
   logs out, the connection drops: is the conversation consistent (question without answer marked as such, no half
   message counted as a whole, no pending action left open for good)? Does the next question in that conversation work?
3. **Does a changing tool call wait for the user?** Stopped before it runs, decided once (two clicks, two tabs), run with
   the arguments shown, expired after a day or a new question, the user's level re-checked when it runs.
4. **Does the model get what it should, and only that?** History window, the tenant's and template's instructions never
   lifting our rules, tool results and sent files within their limits, the right tools for the asker (user, client
   software, MCP server; a task's `onlyTools` and `stopWhen` never set for a chat answer) - and **the right knowledge scope**: set once per answer from the server's data (a tenant
   admin by the database's `admin` flag, a key by its id, the MCP server by its tenant), carried into every round and
   into the search a model without tools gets from the application; the `sources` event names only what that scope
   found.
5. **Does a conversation stay with its owner?** A user's or one key's - listing, messages, files, continuing and
   deleting answer 404 for every other owner; a decided pending action continues only the user's own; a revoked key's
   conversations are deleted.
6. **Are the three entry points consistent?** `/tenant/chat/stream`, `/api/v1/chat` (with and without history) and
   `ask_assistant` follow the same rules where `ai-features.md` says so, and differ only where it says so.
   `ask_assistant` takes the knowledge areas, the connected software (`tenant_mcp_key_connection`, its reading tools
   only, with the software's own instructions after our rules) and the web search of the MCP key it was called with (`prepareOnce` with
   the key's id, `scopeOfMcpKey`, `WebSearchAccess`) - a tenant has several keys, each with its own; `/api/v1/chat`
   likewise the software of its API key (`tenant_api_key_connection`, `McpTools.forApiKey`, `ToolContext.keyId`); the server's
   instructions and the tools' descriptions send a client to `search_knowledge` / `read_document` for facts and to
   `ask_assistant` for a template, files or the connected software (mcp.md "Searching the knowledge"). **Topics per
   question** (2.0.0, mcp.md "Topics deselectable per question"): `excludedConnectionIds` on `/tenant/chat/stream` and
   `/api/v1/chat` only narrows what the asker reaches (an id not reached 400 `chat.unknownConnection`, never ignored),
   left out the conversation's stored choice (`conversation.excluded_connection_ids`, dropped on reading when no longer
   reached), empty every one again; `GET /tenant/chat/software` and `/api/v1/software` list the same connections the
   tools come from; `ask_assistant` and tasks take no list. Every result of a connected software's tool in the chat
   starts with `[<connection>]` (a decided call's stored result without it), `SOFTWARE_RULES` says a document's id
   holds only within its software, `NO_OWN_DOCUMENTS` replaces `NO_DOCUMENTS` only with software tools - a tenant
   without software reads the prompt as before. Specs: `controller.chat.SoftwareChoiceSpec`, `GeneralChatSpec` (a
   tenant asking another tenant's own `/mcp`). **A tenant answering through its A2A agent** (2.0.0, a2a.md): all
   entry points - the chat, `/api/v1/chat` with and without history, `ask_assistant`, `/a2a` - go to the agent through
   `prepareForAgent` instead of an agent run: no model, no tool and no usage of ours, a `templateId` 400
   `chat.noTemplateWithAgent`, the admin's switch off 409 `a2a.notAllowed` before the stream; the conversation keeps
   the agent's context and sends it with every question, a question back is stored as the answer with `a2a_waiting`
   and the next question carries the `taskId`; the files uploaded since the last question go once, within 8 MB (a
   larger one or a video named instead); the title is the question's first line; the agent's answer and files are
   stored like a model's (`finishAgent`, on the asking thread after the latch - never on the SDK's); a closed stream
   (stop, logout) stops the answer and cancels the agent's task on a thread of its own, nothing stored; deleting a
   waiting conversation cancels its task best effort; `GET .../software` lists the agent's skills (`skill:<id>`) and
   `excludedSkillIds` narrows them (400 `chat.unknownSkill`, the stored choice filtered to the card); the frontend hides
   model and template pickers (`answersThroughAgent`), shows the skill chips and the chip "wartet auf Ihre Antwort" on
   the last answer. The scheduled tasks are untouched by the switch (their `agent` / `verify` steps keep the tenant's
   model; only a `prompt` step with `assistant: true` goes to the agent - `alpdesk-task-review`). Specs:
   `controller.chat.AgentChatSpec`.
7. **Does the frontend show what happened?** Stream events parsed completely (split chunks, last event without newline),
   stop and error visible, a decided action card updated, nothing of the stream left after logout - **or after a switch
   to another tenant** (user menu, 2.0.0): the switch resets the store (`SESSION_ENDS`) and reloads the page at `/app`
   (`switchTenantSuccess$`), while `endedByLogout` listens to the logout only - nothing queued or streaming of the
   tenant before may go out with the new token between the new token's arrival and the reload. The topic
   chips show what `/tenant/chat/software` lists, the choice of the open conversation (a new one starts with all), and
   send exactly the chosen ones left out. **With a tenant answering through its A2A agent the same chips are the
   agent's skills** (`skill:<id>`, `utils/software-choice.util.ts`): sent as `excludedSkillIds` without the prefix,
   never mixed with `excludedConnectionIds`; the stored choice (`excludedSkillIds` of the conversation) shown for the
   open conversation, a skill the card no longer lists dropped without a 400; model and template pickers hidden
   (`answersThroughAgent`), the chip "wartet auf Ihre Antwort" on the last answer only.
8. **Does a video get its own way, and only then?** Refused at upload without a sandbox (409), streamed to the storage
   up to its own limit and never whole in memory; the video rules and the larger number of rounds only with a video of
   the conversation not expired, the code tools offered and the video sandbox (`Sandboxes.video`) - otherwise
   `read_file` says why; frames shown to the model bounded (12 per call, 20 MB each), files handed out bounded (30
   images, 5 other files per answer); a still or a clip handed out is a file of the answer of its own (shown as a
   sequence or played), expiring after the days, not with its video; an expired video named as such everywhere (list,
   `read_file`, download 404 `chatFile.expired`, not sent into the sandbox); the client software without history gets
   no `hand_out_file`.
   **Python and shell without a video** (code-execution.md "The parts", "What the model gets"): `run_python` /
   `run_shell` offered only when the tenant has a sandbox of its own and one of its instances answers (`CODE_RULES`
   only then; an `unusable` instance is none); the answer's first call opens one session on a free instance of that
   tenant with that tenant's token and streams in every file sent with the question and the conversation's files not
   expired (a name keeps its folders, a name given twice gets a number, one leaving `data/` is refused, nothing
   whole in the app's memory); the session ends with the answer however it ends - finished, failed, stopped, logged
   out, a decided action - and its lease with it; every instance busy for `app.sandbox.wait`, a refused token, a
   file the instance refuses, a session the instance ended itself (404 - the next call opens a new one) and an
   instance going down mid-answer are texts for the model, never a failed answer; at most `CodeSessions.MAX_OUTPUT`
   characters of a call's output reach the model; what the code wrote leaves the session only through
   `view_images` / `hand_out_file`, within the answer's file limits; all three entry points get the tools alike
   (`ask_assistant` and `/api/v1/chat` with their sent files in `data/`).
   **A tenant's own sandbox image** (code-execution.md "Your own image", roadmap 59): its README (`/opt/extras/README.md`,
   `/health` `extras`, cut at 16 KB in `sessions.read_extras`) reaches the prompt verbatim under
   `ChatService.SANDBOX_README_HEADING` only when the code tools are offered (`sandboxReadme`) and every answering
   instance of the tenant reports the same text (`Sandboxes.extras(tenant)`: a session lands on any free one - one
   without it, or a different one, gives none; the admin's check says `extrasAgree`); never a sentence of ours, never
   a round to read it, nothing else of the image in the prompt; the video image stays described by `VIDEO_RULES`
   (`CodeExecutionSpec` "the README of the tenant's own sandbox image", `SandboxesSpec`, `TenantSpec`).
9. **Does the web search keep to its rules?** Offered only where the tenant has the catalog's web search entry and a
   key, the model calls tools and the asker may (a user through a group under the WAM rule - a tenant admin too only
   through one -, the client software through its key, each MCP key through its own switch, a task never); refused until
   the answer has called `similarity_search` (unless the tenant has no embedding model), at most three searches, a
   failed search told to the model and not billed; only the model's query goes out - never a document, file or the
   conversation; the pages marked as unchecked; the rules per answer source ("documents only" with the web: no general
   knowledge); the pages' addresses in the sources alike at all three entry points.
10. **Is it tested?** Each failure case below has a spec with `FakeAiModels` (`failures`, `failAfterTool`) or
   `FakeMcpSoftware` - or the missing case and the spec it belongs in.

## Failure cases (every agent walks through them for its part)

Provider refuses the key · provider fails after a tool call · provider streams nothing · the tenant's reranker slow
(up to 30 s per search, every search of an answer) or out of reach - the answer goes on in the hybrid order · model calls an unknown tool or
sends broken arguments · a tool throws · an MCP server is down or slow · a tool returns a huge result · the user stops
mid-stream · the user logs out mid-stream · the same pending action decided twice at once · a pending action decided
after a new question · a conversation deleted while it is being answered · a chat file deleted while a question reads it ·
ffmpeg missing or slow · a sent file far over the inline limit · the tenant's default model removed from the tenant ·
a user's area taken away or the admin flag removed between two questions of one conversation (old answers stay -
decided; the next question must use the new scope) · an API key revoked while its question is answered · another key
continuing a key's conversation · a document of the asker's area replaced while the answer searches it (only READY
documents are found) · a user's group or its software level changed while an answer runs (decided: the running answer
keeps its tools) · a video upload broken off midway · a video above the limit or a non-video above 50 MB · the
tenant's sandbox removed or only a plain sandbox after the upload · the video sandbox down or busy for the whole wait ·
an ffmpeg call that hangs until the sandbox's timeout · the rounds used up before anything is handed out · a file the
sandbox refuses (413) · the video expiring between two questions or during an answer · more stills than
`MAX_IMAGES_PER_ANSWER` · code without a video: every instance of the tenant busy for the whole wait · the sandbox's
token refused · the sandbox going down between two calls of one answer · the instance ending the session itself (idle
or lifetime) before the next call · a call stopped at the sandbox's timeout (the session's Python gone) · a program
printing without end · two files of the same name sent with one question · the user stopping or logging out while a
call runs (the session closed, the lease free for the next answer) · the provider failing after a code call · the user logging out while the stills of a sequence load · Tavily refusing the key (401),
too many requests (429), a plan's limit (432/433), out of reach or finding nothing · the model calling `web_search`
before the documents or a fourth time · the web search entry taken away or a group's switch turned off while an
answer runs (decided like the levels: the running answer keeps its tools) · a topic left out in a conversation whose
software is then deleted, renamed or taken from the user's group (the stored choice dropped on reading, the next
question not refused) · an `excludedConnectionIds` with another tenant's or an unreached id (400) · every topic left out
(no software tools, the prompt as without software) · the user switching to another tenant mid-stream or with
uploads queued · with an answering A2A agent: the agent down, slow past `app.a2a.timeout`, failing its task, answering
a question back, keeping its stream open after it, answering over `max-answer-chars` or with files over 8 MB · the user
stopping, logging out or deleting the conversation while the agent answers or waits · the admin's switch taken away
between two questions · the answering connection deleted or unmarked while a conversation waits for an answer · a
`templateId` or a `modelId` sent all the same · a skill left out that the card no longer has · the agent answering a
Message without a context · the agent answering at `/mcp` or `/a2a` of its own tenant while a call is open (the loop).

## Step 1 - Spawn the agents

**Every agent runs on Opus** - pass `model: "opus"` in each Agent call, whatever model the session runs on (decided
9 November 2026: a session on a larger model would hand it to every agent and use up the token budget at once). The
session's own model only verifies and joins the findings (Step 2). Never leave `model` out, and never start an agent as
a fork - a fork takes the session's model.

One agent per chosen part, in a single turn, plus the ground-truth run (review base section 1) of the specs above. Give
every agent "What this does", its part and paths, the key questions, the failure cases, sections 2 to 5 of the review
base verbatim, and:

> You are reviewing the CURRENT STATE of a real codebase: not a diff. Read `CLAUDE.md`
> (only "Project overview", the sections marked "(always)" except "Open source" and "Changelog and version", "Backend tests (always)" and "Backend architecture" - "Frontend architecture" when your part has frontend paths; not "Repository and build", "CI" or the commands),
> `local-data/review/decisions.md` and - the sections named here, not the whole file - read another one when a finding depends on it - for part 1 `ai-features.md` "Chat", "Assistant settings and templates",
> "Knowledge areas" and of `mcp.md` only "Searching the knowledge" and "Topics deselectable per question" (`grep -n`
> for both - a heading and a list entry); part 2 `ai-features.md` "Tools", "Reading on in a document", "Files sent with a question", "Web
> search" and `mcp.md` "MCP clients to the tenant's software" without its OAuth and scheduled-task parts; part 3
> `ai-features.md` "Files in the chat", "Files sent with a question"; part 4 `ai-features.md` "Chat"; part 5
> `code-execution.md` "The parts", "What the model gets", "Videos", "Your own image" - first, then every file of
> your part in full. Context: Micronaut 5 / Java 25,
> langchain4j (streaming chat models, tool specifications), the MCP Java SDK as client, the A2A Java SDK's client behind
> `services.a2a` for a tenant answering through its agent (`docs/architecture/a2a.md` - read it for part 1), server-sent events to an Angular
> 22 / NgRx frontend, one database per tenant reached with plain JDBC; a call to an AI provider can take minutes and
> must not hold a transaction. Specs use `FakeAiModels`, `FakeMcpSoftware` and `FakeA2aAgent`. Read-only: do not edit
> files, do not run the application, never touch the developer's dev database, never call an AI provider, MCP server
> or A2A agent.
> Deliver first a **walkthrough** of your part: one row per step - step · `Class.method` (path:line) · transaction yes/no
> · what is stored · what happens on failure · verdict ✅ / ⚠️ / ❗. Every ⚠️/❗ row is also a finding.
> Severity for this review: **Kritisch** = a changing tool call runs without the user's decision or with other arguments
> than shown; one user's conversation, file or answer reaches another user or tenant - or one API key's another key; an
> answer drawing on a knowledge area the asker is not assigned to; a stored conversation lost; a document's or file's
> content sent out with a web search query by the application (not only the model's own words);
> **Hoch** = a question that never ends or ends silently empty, a conversation that cannot be continued after a failure,
> a pending action that stays open for good, a provider call inside a transaction, a stream that leaks into the next
> session, a sandbox session or its lease left open after the answer ended, a sandbox failure failing the whole
> answer, a video read whole into memory, the video rules or rounds given without a video sandbox (or withheld with
> one), an expired file still sent to a model or the sandbox, the web search offered to an asker that may not or run
> before the documents were searched.

## Step 2 - Synthesize

Follow **section 6 of the review base**, then the report as in **section 7**. The skill's own sections:

- **Der Weg einer Frage**: the walkthroughs as one table from input to stored answer, only ⚠️/❗ rows in full.
- **Abbruch-Matrix**: one row per failure case · what the user sees · what is stored · next question works ✅/❌ · spec.
- **Einstiege**: built from the code, not from a list - the agent of part 1 finds every caller of `ChatService`'s
  public methods and of `AgentLoop` outside `services/chat` (`grep -rlE "chatService\.|agentLoop\."
  Backend/src/main/java`, then the methods called). Today: `/tenant/chat/stream`, `/api/v1/chat` (with and without
  history), `ask_assistant`, `/a2a` (`AssistantAgent`), a decided action, the scheduled tasks' steps (`AgentStep`,
  `ExtractStep`, `PromptStep` - their run is `alpdesk-task-review`; here one row: what they share with a chat answer,
  and that nothing of a task reaches one) and the callers that only delete (`ApiKeyService`, `TenantUserService`). A
  caller this list does not name goes into "Not described" too. The differences between them - per entry the owner of the conversation, where the knowledge scope and the
  web search switch come from (for `ask_assistant`: the MCP key the call was made with) and which tools it gets - intended (with the line of `ai-features.md`) or not;
  for a tenant answering through its A2A agent one more column: what each entry sends to the agent (context, files,
  the asker's metadata) and what it stores.
- **Code** (when part 5 ran): one row per step of a code call's way - tools offered, session opened on a free
  instance, files in, call, output cut, files out, session and lease ended - and per failure (busy, token refused,
  instance down, session gone, timeout): `Schritt` · `geprüft in` (path:line) · `was das Modell erfährt` · `Spec` ·
  Urteil ✅ / ⚠️ / ❗.
- **Video** (when part 5 ran): one row per step of a video's way - upload, stored, sent into the session, rules and
  rounds, frames looked at, stills and clip handed out, shown in the frontend, expired: `Schritt` · `Bedingung`
  (sandbox, video sandbox, not expired) · `Grenze` · `geprüft in` (path:line) · `Spec` · Urteil ✅ / ⚠️ / ❗.
- **Kernfragen**: each key question answered in two or three sentences with the evidence (path:line, spec).
