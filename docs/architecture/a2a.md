# A2A (Agent2Agent)

Zirmo speaks A2A 1.0 (a2a-protocol.org, the JSON-RPC binding) in both directions, on the official Java SDK
(`org.a2aproject.sdk` 1.5.0.Final): as a **client** - an A2A agent is connected software of a tenant (roadmap point 54)
- and as a **server** - the tenant's assistant is an A2A agent at `/a2a` (roadmap point 52). The plan with every decision is kept outside the repository.

**Every byte of A2A JSON goes through the SDK** (`JSONRPCUtils`, `JsonUtil`, `ProtoUtils` - generated from the
specification's protobuf); Micronaut Serde stays out of the A2A path. The SDK's client and server run on our HTTP layer:
the client's on `services.a2a.A2aHttpClient`, the server's behind `controller.a2a.A2aController`.

## The client - A2A agents as connected software

### The connection

- An `mcp_connection` with `protocol` `A2A` (MCP the default; fixed once made - 400 `mcpConnection.protocolFixed`).
  Only for a tenant the admin allowed it (`tenant.a2a_allowed`, the tenant dialog - 409 `mcpConnection.a2aNotAllowed`
  on create, reload and a new address; an existing agent is then neither offered nor answering).
- The address is the agent's JSON-RPC address; its key the bearer, encrypted like a software's. The address rules are
  `McpAddresses`' (HTTPS, plain HTTP and internal hosts only when listed), checked before every request.
- **The card** is read on create and "Neu laden" (`A2ACardResolver` at the address's origin, the extended card with
  the key when the card offers one): name and description become the connection's `instructions`, the skills its
  `mcp_tool` rows (on at once, `input_schema` `{}`, replaced on reload, at most 500, each id once). A card that cannot
  be read keeps the connection with `toolsError` (`mcpConnection.unreachable`, `.cardInvalid`,
  `.unsupportedBinding` without a JSON-RPC interface) - as an MCP server whose tools cannot be read.
- **The agent is always called at the address entered**: the card's first JSON-RPC interface lends its `tenant` and
  version only. So the key never follows a card to another host; `A2aHttpClient` refuses a request with the key to
  another host (`KeyLeak` → 400 `mcpConnection.keyLeaks`) as a last net. No redirect, connect timeout 10 s, the
  request timeout `app.ai.timeout` (a task's blocking `call_agent`: `app.a2a.timeout` - the agent sends its headers
  only with the answer) over a blocking answer's headers **and body** (an agent sending its answer byte by byte would
  otherwise hold a thread - a task's call one of the task executor's two), an answer (or one event) at most 12 MB.
  The log names an agent's failure by its code (`a2a.agentFailed`), never its words, which may quote the question;
  the A2A SDK's warning with a failed webhook's address is held to errors (`logback.xml`).
- **An agent that signs in with OAuth** (roadmap point 61): a public card whose security requirements name an
  `OAuth2SecurityScheme` keeps the connection with "sign-in available" and its skills from the public card; the
  sign-in, the grant and the renewal are the MCP connections' - [mcp.md](mcp.md) "Signing in to the software's server
  (OAuth)". The scheme's `oauth2MetadataUrl` wins over the authorization code flow's URLs (then the client id comes by
  hand); no such flow is `oauth.noAuthorizationCode`. The public card goes without the token, the extended card, every
  question, stream, task asked for and cancel with it - to the connection's host only; a 401 renews it once (while a
  task is asked for: the asking, never the message again; a streamed question hears it as the stream's end and is sent
  once more by `AgentAnswers`, on the asking thread), a 403 is never renewed. A `HTTPAuthSecurityScheme` bearer or an
  API key scheme is the key field, as before - a card naming a key and a sign-in as alternatives keeps a connection
  with a key on its key (no sign-in offered until the key is removed).
- **The check button** (`POST .../mcp-connections/{id}/check`, tenant admin and admin): the card read and timed -
  `{ok, name, skills, millis, error}`.
- An A2A connection is never passed through to MCP clients (`setPassThrough` 409 `mcpConnection.notForA2a`) and never
  reached by `call_tool` (the scheduled tasks' `call_agent` is its way).

### The tool way (package A)

- The chat's model gets **one tool** per agent: `<connection>__ask` with `question` and an optional `skill` (an enum of
  the card's skills), its description the card's with the skills listed. Offered by any level of the user's groups -
  asking is reading -, and through `forApiKey` / `forMcpKey` as a software's reading tools.
- `A2aClient.ask` sends one blocking `SendMessage` (our extension's metadata: who asks as below - `AgentAnswers.asker`,
  the user, the key or the task's tenant -, `skills` the one the model named): a Message is the answer;
  a Task is asked for every 2 s up to `app.ai.timeout` (`a2a.taskTimeout`), its artifacts the answer; input-required
  is "The agent asks back: ..." (the model passes it on - the tool way is stateless). Texts joined (an artifact's
  pieces without a separator), data parts as their JSON, files with bytes returned within 8 MB like a software's
  files; the result starts with `[<connection>]`.

### A tenant answering through its agent (package B)

- One A2A connection per tenant may be marked **"Beantwortet den Chat"** (`answers_chat`, a unique partial index; 400
  `mcpConnection.answersChatOnlyA2a`, 409 `.answersChatTaken`). Then every question of the tenant - the chat,
  `/api/v1/chat` and `/chat/stream`, the MCP server's `ask_assistant`, an A2A question at `/a2a` - goes to the agent:
  `ChatService.prepare` / `prepareOnce` build `PreparedChat.agent` (`AgentAnswers.Question`) instead of an agent run;
  no model, template (400 `chat.noTemplateWithAgent`), tool or usage of ours. The admin's switch off: 409
  `a2a.notAllowed`.
- `services.a2a.AgentAnswers` is the one class mapping A2A onto the chat: `SendStreamingMessage` through the SDK's
  client; a Message - the answer; a Task's working status - a `tool` event (its text cut at 500 characters, the agent
  as software); its
  artifact text - `delta` events; files - the answer's files; input-required - the answer, stored with
  `message.a2a_waiting` (the chat shows "wartet auf Ihre Antwort"), the task kept on the conversation
  (`conversation.a2a_task_id`) and answered by the next question (`taskId` and `contextId`). The context id is kept
  (`a2a_context_id`) and sent with every question. Failed, rejected, canceled - `error` `a2a.agentFailed`; slower than
  `app.a2a.timeout` (10 min) - `a2a.timeout`; an answer is cut at `app.a2a.max-answer-chars` (200,000). **A card that
  streams nothing** is asked with a blocking `SendMessage`: the whole answer comes in one piece on the asking thread,
  the request waiting `app.a2a.timeout` like the latch (not `app.ai.timeout`); a stop meanwhile cannot end that
  request - the answer is dropped when it comes (`answer.stopped`, nothing stored), and nothing is cancelled at the
  agent.
- **Threads**: the SDK's client hands its events over on the JDK HTTP client's thread; they only collect and pass
  pieces to the chat's sink. The asking thread (for the chat a virtual thread of its own) waits on a latch and stores
  the answer after it - never JDBC on the SDK's thread. A final or interrupted state ends the wait even when the agent
  keeps its stream open; we do not close that stream ourselves (the SDK's server cancels a task whose client goes
  away). A closed chat stream (stop, logout) stops the answer and cancels the agent's task on a thread of its own;
  deleting a waiting conversation cancels its task (best effort; not once the admin took A2A away from the tenant). A question that answered the agent's question back
  and failed - stopped, timed out, the task failed, the agent no longer knows it - forgets that task on the
  conversation (`ChatService.forgetAnsweredTask`, only if it is still the stored one): an agent refuses a message to a
  task that ended, so every later question would fail. The context stays; the next question starts a new task in it.
  A question the agent never took (`AgentAnswers.Call.taken`: no event of its answer, no error of its own - out of
  reach, the key refused) leaves the task waiting: the next try answers it.
- The agent's answer is stored like a model's: an answer of files alone has no text of its own, one with neither text
  nor files nor a question back is `502 chat.noAnswer`.
- The files uploaded to the conversation since its last question go with it as `raw` parts within 8 MB (a larger one
  or a video named in the text); the files sent with an API key's question likewise. The title is the question's first
  line. One log line per call (INFO ended / WARN failed: connection, duration, state, task id, error code - never the
  question or the answer). A question at `/mcp` or `/a2a` of a tenant with an agent call open logs "Possible loop".
- `AccountResponse.answersThroughAgent` and `/api/v1/me`'s `answersThroughAgent`: the chat hides model and template.

### Who asks - our extension `urn:alpdesk-ai:a2a:asker:1`

Every message to an agent carries `metadata["urn:alpdesk-ai:a2a:asker:1"]` (the URI also in `message.extensions` and
the header `A2A-Extensions`): `tenant` (its name), `language` (the user's; a key's or a task's: the tenant's search
language in lower case), `user` (the username) or `apiKey` / `mcpKey` (the key's name), `groups` (the user's groups'
names), `idmGroups` (the WAM proxy's), `skills` (the skills left on); a task's call `task` and `run` instead of the
user. Names, never ids, nothing secret. The agent trusts it as it trusts the installation: it comes with the
connection's key. Described at `/a2a/extensions/asker/1`.

### The skills' choice (package C)

For a tenant answering through its agent, `GET /tenant/chat/software` and `GET /api/v1/software` list the agent's skills
(`{id: "skill:<id>", name, instructions}`) instead of the connections; `excludedSkillIds` (the ids without `skill:`)
leaves some out - stored with the conversation like the connections' choice, 400 `chat.unknownSkill`; the metadata's
`skills` are the ones left on.

### The scheduled tasks (packages D and E)

- `call_agent` sends the clear rows of an extract step to an agent ([tasks.md](tasks.md) "The step `call_agent`").
- A `prompt` step with `assistant: true` is answered by the assistant with the knowledge, software (A2A agents as
  their tool), web search and template it names - for a tenant answering through its agent by the agent, only with
  the admin's switch (the step fails with `a2a.notAllowed` otherwise) ([tasks.md](tasks.md) "The step `prompt`").

## The server - the tenant's assistant at `/a2a`

### The doors of a key

An MCP key opens `/mcp`, `/a2a` or both (`tenant_mcp_key.mcp` / `.a2a`; existing keys MCP only; 400 `mcpKey.noDoor`
for none). `McpKeyTokenValidator` authenticates a key only at the door the path knocks at (below the context path) -
elsewhere it is unknown (401). What a key may do is the same at both doors: areas, software (reading tools), web
search, its tasks. At `/mcp` only: the software passed through (with its changing tools where the key may change in
it - mcp.md "Changing tools passed through"; never at `/a2a`, whose questions the tenant's model answers) and the
templates as prompts.

### Routes and cards

- `POST /a2a` - `A2aController` (`@Secured(Roles.MCP)`, `intercept-url-map`): JSON-RPC parsed and answered by the SDK,
  the SDK's `DefaultRequestHandler` (built without CDI in `A2aAgentCore`) runs it; a stream answers as SSE; a body
  that is no JSON is -32700 "Request body is not valid JSON"; 0.3 method names are -32601; a version other than 1.0 is
  `VersionNotSupportedError`. The call context carries the key's tenant and the key.
- `GET /.well-known/agent-card.json`, `GET /a2a/extensions/assistant/1`, `/a2a/extensions/asker/1` - anonymous
  (`A2aExtensionController`). The public card is **neutral**: "AI assistant", provider Zirmo, no skills, the
  absolute `/a2a` address under the context path (its scheme from the proxy's `X-Forwarded-Proto` - behind Caddy or
  nginx the app itself is spoken to in plain HTTP), `streaming`, `pushNotifications`, `extendedAgentCard`, a bearer
  scheme `key`, both extensions (never required).
- `GetExtendedAgentCard` (answered by our controller - the SDK resolves one card for everyone): the tenant's brand, the
  key's description (or "<brand> is the AI assistant of the company ...") followed by what the assistant offers beyond
  the documents - web search, code, the key's software (mcp.md "The brand", `McpKeyService.offerOf`) -, the skills
  `answer`, `template:<id>` per chat template (none for a tenant answering through its agent - it takes no template)
  and `task:<id>` per task assigned to the key - each one's description (the template's, the task's `description`)
  ahead of how to ask for it, so a client's model can tell what to delegate to which.

### Questions and tasks - `services.a2a.AssistantAgent`

- A **blocking `SendMessage`** is answered like `ask_assistant` (`ChatService.answerOnce`, asker `MCP_SERVER`, the
  key's areas, software and web search): one Message - the answer with "Sources: ...", the files as `raw` parts within
  8 MB. Nothing stored but the usage - and, until the retention passed, the task id the answer names with its key
  (`a2a_task_index`: the SDK records an answer's owner, a task or not; no question, no answer).
- **`SendStreamingMessage`** (or `returnImmediately`) answers a **Task**: submitted, working, the answer appended to the
  artifact `answer` in pieces of at least 500 characters (the SDK stores the whole task with every piece), files as
  artifacts, completed - or failed with our message. Stored for the key. A cancel or the timeout ends the model's
  answer before its next round. The timeout is twice `app.ai.timeout` for our model, and `app.a2a.timeout` and half a
  minute for a tenant answering through its agent - as long as the chat waits for it.
- Text parts joined are the question; `raw` text files are sent files (`url` parts: `UnsupportedOperationError`, a
  file that is no text `ContentTypeNotSupportedError`, naming the file - not its type, which is not the reason); `metadata["urn:alpdesk-ai:a2a:assistant:1"].template` picks a
  template. What a text file is: valid UTF-8 under a type that says text (`text/*`, JSON, XML, YAML, CSV, or none) -
  and under any other type (clients send source code as `application/x-python`, `application/octet-stream`) when it
  also has no control characters but tab and line ends, which a binary file has (decided 13 November 2026). The text
  goes the way of `ask_assistant`'s and `/api/v1/chat`'s `files`, which name no type at all - same limits
  (`SentFiles`). The cards say so as far as A2A can: `defaultInputModes` `text/plain`, `text/*`, `application/json`
  (media types, no wildcard defined - `text/*` for a client that reads one; no check against them is asked of anyone),
  and the extension's page spells the rule out.
- `metadata[...].task` starts the **scheduled task** of that name assigned to the key (`TaskService.startByA2a`,
  `StartedBy.A2A`): a Task mirroring the run (polled every 2 s up to `app.task.a2a-subscribe`, 30 min), at its end the
  artifacts `report` (TaskMcpTools' text) and `findings` (data: findings, rows, handedOn). **After those 30 minutes
  nothing is mirrored any more**: `GetTask` still reads the run as it is (also its end, report and findings), but no
  further event is made - a stream, a `SubscribeToTask` and the webhooks hear nothing of a run that takes or waits
  longer, so a client polls `GetTask` then. Not assigned, running, not allowed: `InvalidParamsError` with the code.
  The artifacts mirrored at the end stay in the stored task until its retention, also when a tenant admin deletes the
  run or the task meanwhile (said in docs/privacy.md "Deleting" - only the key that started the run reads them).
- A message to an existing task: `UnsupportedOperationError` (no input-required at our server).

### The task store - `JdbcTaskStore`

The SDK's `TaskStore` on the tenants' databases: `a2a_task` (the SDK's JSON via `JsonUtil`, its state, context, key
and run) in the tenant database, and `a2a_task_index` in the management database (task id → tenant, key) - the SDK
names tasks by random ids and asks for them without a caller. A task belongs to its key (`KeyOwnsTask`); another key
gets `TaskNotFoundError`. `ListTasks` lists the caller's key's tasks, the last changed first, pages by offset tokens,
filtered by state, time and context.
A task stored before the SDK recorded its owner waits in memory until then. A question task still working from before
the JVM's start is answered failed "interrupted by a restart"; a run's task nobody mirrors any more is read from the run.
`A2aTaskCleanupJob` deletes every task unchanged for `app.a2a.task-retention-hours` (24; empty keeps), hourly, only in
the tenants with tasks - ended or not: a question lost to a restart and a run nobody mirrors keep their stored state
until someone reads them and would otherwise stay for good.

### Push notifications

`CreateTaskPushNotificationConfig` and its siblings: up to 5 webhooks per task (`JdbcPushConfigStore`, tenant table
`a2a_push_config`, the config encrypted with `SecretCipher`, deleted with the task); a webhook must be public HTTPS
(`PushAddresses` - no exemption for listed hosts), checked on registration and before every delivery. The SDK's
`BasePushNotificationSender` posts every event on our HTTP adapter (no redirect, 10 s) with the client's token - of a
run started over A2A those of its first 30 minutes (`app.task.a2a-subscribe`, "Questions and tasks"). A webhook
sent inside a plain `SendMessage` that starts something new is refused (`InvalidParamsError`): the SDK stores it before the task has its key, so
the work would start while the client heard "task not found" - with `SendStreamingMessage`, or registered once the task
exists, it is taken.

## Deliberately not built

gRPC and the HTTP+JSON binding; a card signature; 0.3 compatibility; push notifications to us (our client streams or
polls); input-required at our server; `thinking` from an agent; a token per asker (roadmap 55); skills filtered by
group; `call_agent` as a source; the sandbox and created files in an assistant prompt step; runs started elsewhere
listed by `ListTasks`.

## Specs and tools

`A2aConnectionSpec` (package A), `AgentChatSpec` (B, C), `TaskAgentSpec` (D), `TaskAssistantSpec` (E), `A2aServerSpec`
(52, push - the SDK's own client against our server), on `services.a2a.FakeA2aAgent` (the SDK's server core behind an
embedded HTTP server). `devtools/a2a-test-agent/` is an agent on the official Python SDK for the hand test.
