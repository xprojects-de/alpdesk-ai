# AI features: models, keys, documents, chat, usage

Decided 6 October 2026. The AI features are implemented with **langchain4j** (BOM in
`Backend/build.gradle`, one module per provider plus `langchain4j-pgvector`).

The platform is industry-neutral (CLAUDE.md "Industry-neutral"): the instructions and tools speak of "the company"
and its documents - hotels, craftsmen and industrial companies use the same chat.

## What of langchain4j is used

Compared with langchain4j's agentic tutorial on 9 October 2026. We use its **lower layer** and build the agent on top
ourselves:

- **Used**: the provider clients (chat and streaming chat, embeddings - `AiModels`; Bedrock's Converse API through
  `langchain4j-bedrock`, see "Amazon Bedrock"), the message and content types
  (text, image, audio, video, PDF, tool results), tool calling at protocol level (`ToolSpecification`,
  `ToolExecutionRequest`, `JsonObjectSchema`, `ToolChoice`), streaming with thinking and `StreamingHandle` (stop),
  `TokenUsage`, its exception types, `PgVectorEmbeddingStore`, `DocumentSplitters`, the web search's
  `TavilyWebSearchEngine` (`langchain4j-web-search-engine-tavily`, a "beta" module like pgvector - see "Web search")
  and the `ScoringModel` interface for the reranker (with its HTTP client - langchain4j has no module for TEI, see
  "Reranking").
- **Not used, on purpose**: `AiServices` (`@Tool`, `ChatMemory`, `RetrievalAugmentor`) - the agent loop is our own
  (`services.agent.AgentLoop`), the history lives in the tenant database, the search in `KnowledgeSearch`. `AiServices` hides the
  loop, and we need every step of it: usage recorded per call with its key source and user, server-sent events per
  tool call and thinking, a stop mid-answer, model and key chosen per request and tenant, our round rules (more rounds
  while a sent file is explored, the last round without tools) and changing tool calls that wait across requests for
  the user's decision (`PendingAction` - the agentic module's human-in-the-loop blocks in the same process). Checked
  again against 1.22 on 10 October 2026 (roadmap 14): each of these still needs a workaround there.
- **Not used**: `langchain4j-agentic` (sequential, loop, parallel, conditional workflows, supervisor, `AgenticScope`) -
  it solves fixed multi-agent pipelines, not a chat with tools. Compared again on 13 October 2026 for scheduled tasks
  (roadmap 28): it now has error handling, a monitor and a scope store to resume after a restart, but the whole
  module is still experimental and a waiting human blocks a thread. Scheduled tasks are planned on langgraph4j
  instead, with the `AgentLoop` as a node (roadmap 28, prototype passed) - this file gets its rules when they are
  built.
- **Not used**: `langchain4j-mcp` - MCP server and clients come from Micronaut MCP with the MCP Java SDK (mcp.md).

## Models and prices (admin)

- The admin keeps a **catalog of models** (`ai_model`, page "KI-Modelle", `/admin/models`): provider (`ANTHROPIC`,
  `OPENAI`, `GEMINI`, or an own server: `OLLAMA`, `OPENAI_COMPATIBLE`, `BEDROCK` - see "Own model servers" and
  "Amazon Bedrock"), kind (`CHAT`,
  `EMBEDDING` or `IMAGE`), the provider's model name, a display name, for an embedding model its **dimension**, and the **prices
  per million input/output tokens** in the currency the admin bills (incl. any markup). No model is preset: the
  providers' model names change too often.
- Anthropic offers no embedding API: an Anthropic embedding model is refused.
- **Price tiers and cache prices** (V13 of the management schema, roadmap point 15, all optional per model, per million
  tokens): `tier_threshold` with `tier_input_price` and `tier_output_price` - a request whose prompt (input plus cache
  read plus cache write) is longer pays these for all of its tokens, as Anthropic bills Claude Haiku 5.5 above 100 000
  tokens and Gemini its models above 200 000; `cache_read_price` and `cache_write_price` (Anthropic's 5-minute write),
  `tier_cache_read_price` and `tier_cache_write_price` above the threshold. A cache price left out is the input price of
  the call's tier. A tier is whole or none (400 `model.tierIncomplete`, `model.tierThresholdInvalid`); nothing is filled
  in for the admin - the values are the provider's price list's. Only the whole-request way of counting is built: a
  provider counting only the tokens above the threshold is decided when a model needs it.
- **The web search** is a catalog entry too (kind `WEB_SEARCH`, provider `TAVILY`, V11 of the management schema - each
  only with the other): no model, but entered, priced and assigned to a tenant like one - see "Web search".
- An **image model** (`IMAGE`, V3 of the management schema) creates images in the chat - new ones, or from the images
  sent there (docs/roadmap.md point 25, tool `create_image` - "Files in the chat"). `AiModels.createImage` sends the
  instruction and the images (`imagesPerCall`: Gemini 5) to Gemini's chat API through `GoogleAiGeminiChatModel` -
  langchain4j's `GoogleAiGeminiImageModel` takes one image only - and reads the first image of the answer; an answer
  without one (the model refused) keeps its text and its usage, billed all the same. Gemini's only for now (`gemini-2.5-flash-image`
  and its successors, a check constraint): another provider needs its own call in `AiModels` first. It has no
  dimension, reads no files and has no output limit; it never turns up where a chat or embedding model is chosen - the
  chat, the assistant's default model, `/api/v1/models` and the embedding model take only their own kind.
- For chat models the catalog says what they **read as it is** (`reads_pdf`, `reads_audio`; images every chat model
  reads - V2 of the management schema) and which one **transcribes** uploaded audio (at most one, it must read audio;
  choosing another one takes the role from the previous). As of October 2026: Gemini reads both, GPT-4.1 mini and
  Claude PDFs only (OpenAI's audio input needs its own audio models). `reads_video` went with V8 (17 October 2026): no
  model is sent a video any more - it is examined with code ([code-execution.md](code-execution.md) "Videos").
- Tenant users see only the active models assigned to their tenant (`/tenant/ai/models`, see "Model access"), without
  prices, each with whose key a call would use (`keySource`, null = no key: not usable yet).
- An embedding model a tenant uses keeps provider, name and dimension (its embedding table has that vector length) and
  cannot be deleted - it can be deactivated (the tenants using it keep it, see "Model access"), and its prices change
  for future calls.

## Model access

- The admin decides **which catalog models a tenant may use** (`tenant_model`, management schema V1; tenant
  list, dialog "KI-Modelle", `GET`/`PUT /admin/tenants/{id}/models` with the full list of ids). `services.ai.
  TenantModelService` is the one place that answers "may this tenant use this model": the model lists of
  `/tenant/ai/models` and `/api/v1/models`, the chat's and the embedding's model choice (400 for a model not assigned
  or not active) and the transcriber all go through it.
- **A new tenant has no models** - the admin assigns them.
- An assignment survives deactivating the model (the tenant just cannot use it meanwhile); deleting a model removes it.
  **Except the embedding model a tenant has chosen** (decided 12 October 2026): it keeps embedding and searching while
  deactivated, and is billed - stopping it would leave the tenant's knowledge unsearchable. Deactivating it only keeps
  other tenants from choosing it; to stop it for a tenant, its tenant admin or the admin chooses another one.
- **The transcriber needs the assignment too**: a tenant's audio never goes to a provider it is not allowed. Without
  it an uploaded audio file is kept without a transcript.
- The model the tenant's documents are embedded with **cannot be left out** (409): changing it embeds every document
  again, which the tenant decides on its documents page. The dialog shows it checked and locked.
- Not done on purpose: the provider-key page still lists every provider - a key for a provider without assigned models
  does no harm.

## Provider keys

- `services.ai.ProviderKeyService`: a tenant's **own keys** (kept by its tenant admins, page "API-Keys",
  `/tenant/admin/provider-keys`, or by the admin, tenant list "Anbieter-Keys", `/admin/tenants/{id}/provider-keys`)
  and the admin's **global keys** (`/admin/provider-keys`), one per provider each, encrypted with AES-GCM
  (`security.SecretCipher`, key `APP_SECRET_KEY`). Only the last four characters (`keyHint`) ever go out again.
- Which key a call uses, per provider: the tenant's own key; without one, the admin's global key - **only if the admin
  allowed it for the tenant** (`tenant.global_keys_allowed`, tenant dialog); otherwise the call is refused (409 "No API
  key for ..."). A tenant can thus mix: own key for one provider, the admin's for another.
- Tavily's key for the web search is a provider key like these (`TAVILY`): the tenant's own before the admin's global
  one, under the same rule.
- Who manages a tenant's keys and its embedding model: **its tenant admins and the admin** - a user only uses them
  (decided 6 October 2026, docs/architecture/tenancy.md "Who does what"). A user without a usable model is told to
  ask the company's admin. The keys are the tenant's, not a user's: all its users and its client software use them.

## Own model servers

Decided 6 October 2026 (roadmap point 3): models on own hardware or at other providers - a company's model farm,
Ollama with Qwen or Devstral, Mistral - next to the three big providers.

- **Two providers.** `OLLAMA` speaks Ollama's own API through `langchain4j-ollama` (`/api/chat`, `/api/embed`): only
  there the **context length** can be sent with every request (`num_ctx`, catalog field "Kontextlänge", default 32768
  - `AiModels.DEFAULT_CONTEXT_LENGTH`). Through Ollama's OpenAI layer it could not, and Ollama cuts off silently what
  does not fit - search results and tools would vanish without an error. `OPENAI_COMPATIBLE` is any server with the
  OpenAI API through `langchain4j-open-ai` with another `baseUrl`: vLLM, LM Studio, a company's gateway, Mistral
  (`https://api.mistral.ai/v1`, chat models such as `mistral-large-latest`, `mistral-embed` with 1024 dimensions). Its
  context length is set by the server; a question too long is refused there ("context_length_exceeded"), and the user
  is told to start a new conversation - no field for it on purpose.
- **Address and key belong to the catalog entry**, entered by the admin only (no tenant-entered URLs, so no SSRF
  question): `base_url`, `api_key` (encrypted with `SecretCipher`, never sent out - only `key_hint`; a key shorter than 12
  characters shows nothing of itself), both in V1 of the management schema. The same model name may be in the catalog
  once per server. A tenant's own server is an entry assigned to that tenant alone ("Model access"). Their keys are not
  kept under API-Keys (400 there).
- **Security** (`services.ai.ModelServers`, `AiModels`):
  - HTTPS with the JVM's full certificate check; **plain HTTP only to the hosts in `app.ai.plain-http-hosts`**
    (`APP_AI_PLAIN_HTTP_HOSTS` - a server in the same pod or the internal network), checked when the entry is saved
    and again before every call. A company's own certificate authority is added to the JVM's truststore at start
    (`USE_SYSTEM_CA_CERTS=1` and `*.crt` in the volume's `certificates/`, through the Temurin image's entrypoint -
    Backend/podman/public/README.md "Own model servers"), never a switch that skips the check (decided 11 October
    2026).
  - An address with user info, a query or a fragment is refused - credentials would show in the catalog and the logs.
    One with an empty part in its path (`//`) too: such a path can name another host than the one checked.
  - **The key stays with its host**: an address with another scheme, host or port drops the stored key unless a new
    one comes with it - a typo or a taken-over admin account cannot quietly send a company's key elsewhere.
  - **No redirect is followed** (`JdkHttpClientBuilder` with `Redirect.NEVER`): key and data go to the entered address
    only. langchain4j's request and response logging is off, and so is its warning before asking again after a
    failure (`RetryUtils` at ERROR in `logback.xml`): it carries the server's answer, which may repeat the request.
  - Ollama itself has neither TLS nor keys: a reverse proxy in front does both; the entry's key goes as
    `Authorization: Bearer`. Without a key no Authorization header is sent.
  - Failures name the model's display name, never the address ("Firmen-KI is not reachable", "... refused the API key -
    ask the admin", a certificate not trusted); the address is in the server log for the admin.
- **Embeddings**: a server is not asked for a dimension (many refuse OpenAI's `dimensions`); the vectors' length is
  checked against the catalog instead - another one fails with 502 instead of breaking the tenant's embedding table.
- **Billing**: a call with a model on an own server counts as the admin's (`KeySource.GLOBAL`), billed at the model's
  prices - 0 for a server the tenant runs itself. It is usable for every tenant it is assigned to, whether or not the
  tenant may use the admin's provider keys.
- **Tools**: many small local models cannot call tools reliably. A chat model has `calls_tools` (dialog "Kann Werkzeuge
  nutzen", default on); without it see "Tools" - the application runs the search and reads the files beforehand.
- Not built on purpose: Google Vertex AI directly (it wants short-lived OAuth tokens from a service account, not a
  fixed key) - a gateway in front of it with a fixed key works as `OPENAI_COMPATIBLE` today; a service-account login
  comes when a case needs it. Claude in the EU comes through Amazon Bedrock ("Amazon Bedrock"). Ollama's `think` and `keep_alive` are left at the model's defaults.

## Amazon Bedrock

Decided 8 November 2026 (roadmap point 60): Claude in an EU region for a company that wants no prompt at a US
endpoint - Anthropic's own API has no EU region. The installation holds the AWS account; nothing of ours stands
between. Researched that day on the providers' pages: Claude in the EU exists through Amazon Bedrock (Frankfurt
`eu-central-1`, the current models through the EU routing) and Google Vertex AI; GPT through Azure OpenAI.

- **Provider `BEDROCK`** through `langchain4j-bedrock` (the Converse API, `BedrockChatModel` and
  `BedrockStreamingChatModel`) - the standard way to Claude on Bedrock. Chat models only (400 `model.bedrockOnlyChat`
  otherwise): the company's knowledge keeps its own embedding model.
- **Kept like a model on an own server** (`hasOwnServer`): the catalog entry's address is the region's runtime
  endpoint, `https://bedrock-runtime.<region>.amazonaws.com` (also its FIPS or VPC endpoint) - the region is read from
  it and every request is signed for it (400 `model.bedrockAddressInvalid` when it names none); the key is an IAM
  user's access key, `ACCESS_KEY_ID:SECRET_ACCESS_KEY`, required (400 `model.bedrockKeyInvalid`,
  `model.bedrockKeyRequired` - also after another region dropped it), encrypted like every server key, the hint from
  the secret's end. The model id is Bedrock's: an inference profile such as `eu.anthropic.<model>` keeps the
  processing in the EU. Billed like an own server's model (`KeySource.GLOBAL`, the catalog's prices).
- **The AWS SDK takes nothing of the server** (`services.ai.BedrockClients`, like `S3StoreClient`): no credentials from
  the environment, `~/.aws` or an instance role, no profile file, the endpoint always the entered address, the HTTP
  clients set explicitly - the JDK's URL connection for a whole answer, the SDK's Netty client for the stream (AWS's
  event stream). The SDK does not retry (`AiModels` decides, as for every provider). One client pair per address and
  key is kept for the next call (an SDK client holds threads); both HTTP clients are built at the first Bedrock call.
- **Claude's rules apply** (`AiModels`): thinking as `reasoning_config` with the budget (`enableReasoning`), no
  temperature while it thinks; the agent loop's rounds cached up to the question (`AFTER_LAST_USER_MESSAGE`: tools,
  instructions, history and question); the tools listed in the last round - Claude on Bedrock refuses tool calls in
  the history without them. langchain4j's Bedrock client sends no tool choice NONE, so the model could still call one
  there; the agent loop does not run it. The cache tokens come as `BedrockTokenUsage` (input without them, as
  Anthropic's).
- **Failures** as for every provider (`BedrockExceptionMapper`: 403 a refused key, 404 an unknown model, 429 a limit);
  the log line carries the HTTP status and Bedrock's error code (`AccessDeniedException`: the model not enabled for
  the account, `UnrecognizedClientException`: an unknown key) - never AWS's message.
- **Specs**: `ModelServerSpec` against a server of the spec that answers Converse and ConverseStream (the event stream
  encoded by the spec) - the signature's key id and region, the body, the stream, the cache tokens, the failures.
- **Checked against AWS** on 8 November 2026 with Claude Haiku 4.5 through the EU inference profile in Frankfurt and
  an IAM key as described: the answer streamed, a tool called (`create_file`, two rounds), thinking with a budget
  (`reasoning_config`) streamed to the chat, the cache written in the first round and read in the second (9,701 tokens
  each), the usage recorded. Not checked: the newer models (Sonnet and Opus 5.x - they need the provider's approval
  first), which may want langchain4j's adaptive reasoning instead of a
  budget (Opus 4.7 and later, says its javadoc).
- **Not built on purpose**: Bedrock's API keys (bearer tokens - AWS calls the long-term ones "for exploration only")
  and its Anthropic-compatible endpoint (`bedrock-mantle`, newer, its key header documented two ways); Bedrock's
  embedding models (Titan in Frankfurt); Claude on Google Vertex AI (OAuth tokens of a service account;
  `langchain4j-vertex-ai-anthropic` 1.23.0-beta33 runs over gRPC, fakes streaming from the whole answer and knows no
  thinking; the current models in Europe only in the multi-region `eu`, 10 % above the global price).
- **GPT in the EU needs nothing new**: Azure OpenAI's v1 endpoint is `OPENAI_COMPATIBLE` - address
  `https://<resource>.openai.azure.com/openai/v1` (no `api-version`), the deployment's name as the model id, the
  deployment of type "Data Zone EU" ("Global" processes anywhere). Not checked by us: Microsoft documents the key as
  `api-key` header and shows OpenAI's client with it, which sends it as a bearer token.

## Documents and embeddings

- **A document is the company's knowledge, or belongs to a knowledge area** (decided 6 October 2026 - no documents of
  single users; areas 9 October 2026, see "Knowledge areas"): a tenant admin uploads them (page "Firmenwissen", `POST /tenant/admin/documents`, multipart field `file`, up to 50 MB), the
  admin too (tenant list, dialog "Firmenwissen", `POST /admin/tenants/{id}/documents`), and the client software with
  the tenant's API key (`POST /api/v1/documents`, same field; `GET` lists, `DELETE` removes) - e.g. third-party
  software pushing its manuals -, and a knowledge source syncs a file store's folder into it (origin `SOURCE`,
  [knowledge-sources.md](knowledge-sources.md)). Every user finds the company knowledge in the chat, an area's documents only the users
  and keys assigned to it; **no user sees their list** (only the passages and file names as sources), and no user
  creates embeddings. Tika
  extracts the plain text (`services.document.DocumentTextExtractor`) - internal only, there is no endpoint for the
  extraction alone (decided 6 October 2026). The text is stored in the tenant database's `document` table
  (so a change of model can embed it again without a new upload), and the document waits (`PENDING`). No text (e.g. a
  scan) is refused (400) - unless the tenant has the image model, see "Images and scans". A document may have 50 MB
  (`DocumentService.MAX_DOCUMENT_BYTES`), checked before it is read: the server takes requests up to 512 MB for ZIP
  archives. Its text may have **20 million characters** (`DocumentTextExtractor.MAX_TEXT_CHARACTERS`, some 8,000
  pages; Tika stops there, 400 "too much text"): a spreadsheet export of a few megabytes unpacks into hundreds of
  megabytes of text, held several times in memory. An Office file is a ZIP archive, and a spreadsheet's shared strings
  are held in memory whole before any text comes out - a crafted file of a few megabytes can take gigabytes. So every
  part is unpacked once and only counted before the file is parsed: 400
  `document.partTooLarge` for a part of more than 200 MB (`MAX_PART_BYTES`; DOCX and PPTX are read as a stream and
  were never affected).
- `services.document.DocumentIndexer` embeds in the background (executor `indexing`, 2 threads): splits the text into
  chunks (recursive, 1000 characters, 100 overlap), embeds them in batches of 64 and stores them in the table
  `embedding` (langchain4j `PgVectorEmbeddingStore`, created with the model's dimension, metadata `document_id` and
  `file_name`). Status `PROCESSING` → `READY` (with the number of chunks) or `FAILED` with the reason (the provider's
  failure, a missing key); "Erneut versuchen" queues it again. Documents still waiting when the process stopped are
  taken up on start - looked for through a connection of its own per tenant, not its pool (tenancy.md). "Erneut versuchen" is also offered for a document waiting or being embedded for more than 30
  minutes (`utils/document.util.ts` `canRetry`): its run may have broken off (the tenant database out of reach for a
  moment), which otherwise only a restart takes up again; not earlier, so a document in progress is not embedded twice.
  Such a broken-off run is an error in the application log ("Document n of tenant m could not be embedded and keeps
  its state"), so the daily report shows it.
  After a failed ZIP import (a long one answers with a timeout while the server goes on) the list is loaded anew.
- **Every chunk starts with a context header**: the document's file name and its title (its first line with a letter
  or a digit, without the markup around it, at most 120 characters) - "Betriebsanleitung_NK-24.pdf – Betriebsanleitung
  Gas-Brennwertkessel NK 24". A line of markup alone is no title (a Markdown file's front matter gave every chunk the
  title "---", October 2026; so would a rule, a table's separator, a code fence or a line of emojis), and the markup
  framing a title goes: headings, bold, quotes, list and table marks, emojis at the start ("### 📊 Specifications" -
  "Specifications"); punctuation inside the text stays, a sign or a `+` at an end does not ("-20 °C" - "20 °C",
  "C++" - "C"). Generic on purpose: no format's own syntax is read - front matter gives its first key's line, `title:`
  included ("a.md – title: "Serie NK24xx""). The document's text itself keeps all its markup: it carries
  tables and code for the chat model, and reading on finds a chunk's passage by its words (`DocumentIndexer.contextHeader`,
  specs in `KnowledgeSearchSpec`). A chunk deep in a document
  (a row of a technical data table) otherwise does not say what it is about; with it both searches find it by the
  document's subject, and the chat model reads where a passage comes from. Stored with the chunk, so the full text
  searches it too. A document embedded before gets it when it is embedded again (another model, a retry, or
  "Alle Dokumente neu einbetten" - below). Headings
  inside a document are not used: Tika's plain text does not mark them (in PDFs not at all). Measured (hybrid 0.3):
  among the first five 94 % to 97 %, table rows 60 % to 100 %, similar documents (NK 24 / NK 35) first 80 % to 100 %.
- **"Alle Dokumente neu einbetten"** (menu of the document panel, the admin's tab and the tenant admin's page,
  `POST .../documents/embed-again`, decided 13 October 2026): every document embedded again with the tenant's model -
  for a change of how chunks are made (their context header), which reaches documents embedded before only this way.
  The same as a change of the model without dropping the table (`DocumentService.embedAllAgain`): every document
  waits for its run and is not found meanwhile, one that fails can be retried, a restart takes up what is left; each
  document's chunks are replaced when its run comes. It costs as much as uploading them anew (confirmed in a dialog);
  409 without a model or without a key for it - every document would end failed.
- **A tenant has exactly one embedding model** (`tenant.embedding_model_id`, chosen by a tenant admin on the page
  "Firmenwissen" or by the admin in its dialog, among the models assigned to the tenant): vectors of different models cannot be compared. Changing it drops the table `embedding` and embeds every
  document again - it costs as much as uploading them anew (the frontend asks first). The new model is saved last
  (after the table is dropped and every document set waiting): if dropping fails (a search holds the table past
  lock_timeout - 503 "try again"), the tenant keeps the previous model with its chunks, and the next attempt does it
  all again.
  A change away from a model needs a key for the new one (409 `providerKey.missing`, nothing dropped): without it
  every document would end failed; the first choice needs none, so the admin may choose it before the tenant admin
  enters the key. A document queued twice (a change while it still waited) is embedded once: a run finding it ready
  stops.
- A tenant's documents are embedded one after the other under a lock per tenant, which the change of the model takes
  as well: no document is ever stored with the previous model's vectors. The change waits while a document is being
  embedded. **The tenants take turns** (since 27 October 2026): each has a queue of its own, the executor holds at most
  one job per tenant, which embeds one document and queues itself again at the end - one tenant's large import holds
  up another tenant by one document, and no thread waits on a tenant's lock; within a tenant the order stays as queued
  ([knowledge-sources.md](knowledge-sources.md) "The indexer, fair between tenants").
- Deleting a document deletes its chunks; a document deleted while it was being embedded has its chunks removed at
  the end of that run. Chunks that cannot be removed at that moment are logged and the deletion still answers success
  (the row is gone, the search joins it); they go with the next change of the embedding model or with the tenant.

- **Allowed file types** (decided 6 October 2026), recognised by Tika from the content - not the file name, which
  only tells CSV and Markdown apart from plain text, and settles a text the content mistakes for another kind of text:
  a CSV opening with "Article" looks like a Usenet message (message/news), a text opening with "From:" like a mail -
  a text type not on the list gives way to the allowed text type the name says (`DocumentTextExtractor.textByName`,
  11 October 2026), and the document is parsed as that type; content that is not text keeps its type, whatever the
  name. A type not on the list is never parsed (415) - nor is what an allowed file carries inside (an attachment of a
  PDF, an object embedded in a DOCX): only the file itself is read (`DocumentTextExtractor.outerFileOnly`):

  | Allowed | Refused, and why |
  |---------|------------------|
  | PDF, DOCX, ODT, RTF, TXT (also `.log`, which Tika names `text/x-log` - read since 27 October 2026), Markdown, HTML, PPTX, XLSX, ODS, CSV; with the image model also PNG, JPEG, GIF, BMP and scanned PDFs | archives as a single document (a ZIP is imported through its own endpoint, see below), audio/video/executables, WebP/HEIC (Java reads them only with a plugin), the old binary Office formats DOC/XLS/PPT (weaker text - added when tenants need them) |

  **The one place the types are named** is `DocumentTextExtractor.TYPES`: Tika's name, the name in the refusal
  ("allowed: PDF, DOCX, ..." is built from it) and the file name extensions a knowledge source reads - it leaves other
  files out before reading them (`readableByName`; the images' extensions in `DocumentImages.EXTENSIONS`, only with the
  image model). Upload and ZIP import decide by content alone, as before.

## Importing a ZIP archive

Decided 6 October 2026: a whole folder of documents - a manual's chapters, a department's share - imported at once,
its folders kept, and imported again when it changed.

- `POST /tenant/admin/documents/archive` and `POST /admin/tenants/{id}/documents/archive` (multipart field `file`),
  page "Firmenwissen", button "ZIP importieren". **The server unpacks** (decided against unpacking in the browser):
  the upload goes to disk (`micronaut.server.multipart.mixed`, threshold 1 MB) and is copied into a temporary file of
  its own, deleted after the import (`controller.document.DocumentUploads`) - up to 512 MB.
- `services.document.DocumentArchive` reads it entry by entry, never trusting the archive's own size claims (zip
  bombs): at most 5000 entries, 50 MB per file and 2 GB in all, counted while reading. Names are UTF-8, else the
  Windows code page (CP437, what the Explorer writes without saying so - the JDK refuses such a name already when the
  archive is opened as UTF-8, so it is opened again with CP437). Paths with `..`, hidden files (`.DS_Store`, `._*`) and
  `__MACOSX/` are skipped; nothing is ever written to disk under an entry's name. An entry that cannot be unpacked
  (damaged, encrypted) is skipped as "could not be unpacked" - the others are read.
- Every file becomes a document of origin `UPLOAD` with its **path as key** (`external_id`, the unique key of "Documents
  pushed by the client software") and its folder (`document.folder`). Imported again:
  - a path already there with **changed content** is replaced and embedded again (same id); **unchanged** it is left as
    it is - no cost;
  - a **new** path is added;
  - a document **missing** from the archive **stays** - deleting is done in the list, or every upload of the area at
    once before the new import ("Knowledge areas").
- **An archive goes into one area** (multipart field `areaId`, left out: the company knowledge). The path is the key
  **within its area** (tenant schema V2, `document_upload_path_uq` on the area and the path): the same folder tree
  imported into two areas is two sets of documents, each replaced by its own import.
- **The same content under another path is stored once per area** (`content_hash`, of any uploaded document of that
  area): the file is reported as `DUPLICATE` with the path of the document that has it. In another area it is no
  duplicate - other people find it there. Only a new path is checked: a path already there whose new content equals
  another file's is replaced, or its old version would stay found. Duplicate chunks would take several of the five
  search results with the same passage. Only for uploads in the frontend - the client software's pushes are not
  compared (it keeps its own keys).
- A file that cannot be stored (a type not allowed, no text, too large, an image without the image model) is
  `SKIPPED` with the reason; the import goes on. The answer lists every file in the archive's order with its outcome
  (`CREATED`, `REPLACED`, `UNCHANGED`, `DUPLICATE`, `SKIPPED`); the frontend shows it in a dialog.
- The chunks' context header and the sources carry the path ("Handbuch/Kapitel/technik.pdf – Grundlagen ..."): the
  search finds a passage by its folder too, and the chat names where it comes from. The list shows the folder as a
  column, sorted like a file tree.

## Images and scans

Decided 6 October 2026: hand-written notes, scanned handouts, photos of a whiteboard and slides that are mostly a
drawing are often the most valuable part of a company's knowledge - Tika finds no text in them.

- The admin marks **one chat model** in the catalog as the one that reads them (`ai_model.reads_scans`, dialog "Liest
  Bilder und Scans für das Firmenwissen", like the transcriber: at most one; every chat model sees images); a tenant
  uses it only when it is assigned to it and it has a key (`TenantModelService.findScanReader`).
- What it reads: an image (PNG, JPEG, GIF, BMP) as a whole, and every page of a PDF with fewer than 200 characters of
  text (`ScanReader.MIN_PAGE_TEXT` - a slide's title alone says nothing), at most 200 pages per document. The other
  pages keep their text. One model call per image or page, recorded as usage (no user).
- Its instructions: transcribe all text exactly (headings, lists, tables as Markdown), then describe every drawing,
  sketch, diagram and photo in detail - positions, directions, movements, labels - in the language of the image.
- When: the upload stores Tika's text and keeps the **original file** (file storage, `<tenant>/documents/<id>`); the
  indexer lets the model read it before embedding (`DocumentIndexer.readImages`), replaces the text with the reading
  ("--- Page n ---" per page of a PDF) and removes the original. **Read once**: embedding the document again (another
  embedding model, a retry after a failed embedding) does not read it again. The reading is stored only on the version
  that was read (`DocumentRepository.saveReading` by its `content_hash`): a scan takes minutes, and a version pushed
  meanwhile keeps its own original and is read by its own run - else it would keep the old version's text under its
  new hash, never embedded again. A run that starts between the new version's row and its file (stored right after) finds the
  previous file under the new hash: it compares the file's hash first and fails ("replaced while it was read") - the
  new version's run comes next and reads it anyway (only READY is skipped). Left waiting instead, two replaces at once
  whose files were stored in the other order would wait for good; failed, a push of the file heals it.
- **Images are never held at their full size**: an image is decoded at about the size a model gets (every n-th pixel,
  `DocumentImages.MAX_EDGE`), one that declares more than 200 megapixels is refused before it is decoded (a few
  kilobytes of PNG may declare gigabytes), and a PDF page is rendered at 120 dpi or less, at most `MAX_EDGE` pixels
  long, whatever size it declares. PDFBox decodes an image inside a PDF at its full declared size, so a PDF holding one
  (or its mask) of more than 40 megapixels is refused before it is decoded (an A4 page scanned at 600 dpi has 35). The
  same for the chat's files. In a document such a page - or one PDFBox cannot draw at all (a broken colour space or
  image, a runtime exception in PDFBox) - is not read and keeps its own text (a warning in the log): the other pages
  are read and the document becomes ready - failing it would pay the pages read before it again on every retry, and
  fail again.
- An unchanged file is recognised by the SHA-256 of **the file itself** for such a document (its text is not known at
  upload time) - of the extracted text for every other.
- Without the image model an image is refused (409, in a ZIP skipped) and a scan without any text too (400, "contains
  no text"); a PDF with text is then embedded with its text only.

## Documents pushed by the client software

Decided 6 October 2026 (roadmap point 13): software that pushes its documents again and again replaces them instead
of piling up versions.

- `POST /api/v1/documents` takes an optional multipart field `externalId` - the software's own key (an article or file
  number, a path; trimmed, 1 to 200 characters, else 400). `DocumentService.push`:
  - an unknown key: a new document (201);
  - a known key: the document is **replaced in place** - same `id`, new file name, text and size, embedded again
    (200). Until its new chunks are stored the chat does not find it (seconds);
  - a known key with **the same text** (`content_hash`, SHA-256 of the extracted text - of the file for a document the
    image model reads): left as it is, **not embedded
    again** - no cost (200). A document whose embedding failed is embedded again all the same.
  - without a key every push is a new document, as before.
- One statement does it (`DocumentRepository.upsert`, `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE` the text
  changed): two pushes of the same key at once never make two documents.
- `DELETE /api/v1/documents?externalId=...` deletes by the key (a query value, since keys may contain `/`); the delete by
  `id` stays.
- An optional multipart field `area` (a knowledge area's **name**, any case - the client software knows no ids) puts it
  into that area; empty (`area=""`) into the company knowledge. **Without the field** a new document is company
  knowledge and a known one **keeps its area** - also one a tenant admin moved it to (decided 9 October 2026: client
  software written before areas must not take a document out of its area on its next sync). A known document pushed
  with another area **moves** there - unchanged text is not embedded again, only its area changes. A key writes only
  where it reads: an unknown area or one the key is not assigned to is 400, a known `externalId` whose document lies
  in an area the key does not find 409 - checked in the statement that writes (`DocumentRepository.upsertPushed`,
  `updateArea` and `delete` with the allowed areas), so a document moved meanwhile is neither replaced, moved out nor
  deleted.
  `GET /api/v1/documents` lists, and the deletes reach, only the documents the key finds (others: 404);
  `GET /api/v1/me` names the key's areas.
- Every document records its `origin` (`UPLOAD` in the frontend, `API`); the client software's key is unique per
  origin, a ZIP archive's path per area (tenant schema V2). The lists of the admin and the tenant admins show it as "Quelle", the key as tooltip. An admin may delete a
  pushed document - the next push brings it back.
- Built for roadmap point 8 as well: a source (SFTP, SharePoint, ...) will use the same `push` with the file's path as
  key, a new origin `SOURCE` and the source's id in the unique key.

## Knowledge areas

Decided 9 October 2026 (roadmap point 16): some knowledge is for everyone, some only for a part of
the company - "Vertrieb", "Geschäftsleitung", "Werkstatt".

- **A document without an area is company knowledge**, found by everyone - the default of every upload, ZIP import
  and push. A document of an area is found only by the members of the groups and the keys assigned to it, and by every **tenant admin**
  (they keep all documents anyway). Within one tenant - nothing is shared across tenants.
- **Data**: the areas and who is assigned to them are in the management database, next to the users and keys
  (`knowledge_area`, unique name per tenant whatever the case; `group_area` - a user finds the areas of its groups,
  [tenancy.md](tenancy.md) "User groups" -, `tenant_api_key_area`,
  `tenant_mcp_key_area` - each MCP key's own, kept when the key is renewed). A document's area is
  `document.area_id` in the tenant database (no foreign key across databases).
- **The rule exists once**: `services.document.KnowledgeScope` is what someone finds - the company knowledge and these
  areas, or everything. `KnowledgeAreaService` decides it: `scopeOfUser` (a tenant admin by the `admin` flag in the
  database, not the token), `scopeOfApiKey` (the key's id comes with its authentication, `Roles.API_KEY_ID_ATTRIBUTE`),
  `scopeOfMcpKey`. `ChatService` puts it into the `ToolContext` of every answer - the chat, `/api/v1/chat` with and
  without history, `ask_assistant` - and `KnowledgeSearch` takes it for every search. Sub-agents and workflows get the
  scope of whoever asks.
- **The filter is the document's area, not a copy in the chunks**: both searches are SQL of our own
  (`TenantEmbeddings.searchVectors` / `searchText`) and join every chunk to its `document` row by `document_id`,
  **only while the document is `READY`**. Moving a document is one row, nothing is embedded again; a chunk left behind
  by a document deleted meanwhile is found by no one, and neither are the previous version's chunks of a document
  replaced and waiting to be embedded again (they may belong to the area it was in). The indexer sets READY only
  while the document is still the version it embedded (`DocumentRepository.markReady` by its `content_hash`): a run of
  the previous version ending after a push does not make the new one READY with the old chunks. It reads the text by
  the same hash (`findText`): a version pushed between looking the document up and reading its text is left to its own
  run, else its text would be embedded and marked READY as the previous version. The HNSW index filters after the
  index scan - with its usual 40 candidates a user of a small area could get fewer hits than asked for, so the vector
  search sets `hnsw.iterative_scan = strict_order` (pgvector 0.8) for its transaction: the index is read on until
  enough chunks pass, in exact order - but **at most `hnsw.max_scan_tuples` chunks** (20,000 by default). In a tenant
  with far more chunks, a small area whose chunks are all further from the question can still get fewer hits; not
  raised before a measurement shows it (the full-text side of the hybrid search still finds them). langchain4j's own
  hybrid search (a measurement mode only) is filtered after it searched.
- **API** (tenant admins under `/tenant/admin`, the admin under `/admin/tenants/{id}`):
  - `GET/POST/PUT/DELETE .../knowledge-areas` - a name of 1 to 100 characters (400), taken (409). Deleting an area
    that still holds documents is 409: moving them to the company knowledge on the quiet would show them to everyone.
    Not locked against an upload into it at the same moment (the area lives in the management database, its documents
    in the tenant's): such a document keeps the deleted area's id - only tenant admins find it ("Unbekannter
    Bereich"), and it is moved or deleted one by one. Deliberately so: it needs two admins at once and fails closed.
  - User groups and API keys: `areaIds` in request and response (left out on a change: unchanged; another tenant's
    area 400); a user finds the areas of its groups ([tenancy.md](tenancy.md) "User groups"). MCP keys the same (`.../mcp-keys`, [mcp.md](mcp.md) "MCP keys").
  - Documents: `areaId`/`area` in the response, multipart field `areaId` on upload and ZIP import,
    `PUT .../documents/{id}/area` (`{areaId}`, null = company knowledge; 409 when a ZIP file of the same path is in
    that area already).
  - `DELETE .../documents/uploads?area=company|<id>` deletes **every document uploaded in the frontend** into the
    company knowledge or one area - before its ZIP is imported anew, so files no longer in it do not stay. The client
    software's documents stay - it keeps them itself. Answers `{deleted}`. The chunks go in one
    statement; the search would skip a forgotten one anyway (the join).
- **Frontend**: page "Firmenwissen" (shared `document-panel`) - a choice "Alle Bereiche / Firmenwissen / <area>" filters
  the list and is where uploads and ZIP imports go (with "Alle" and areas to choose from, the upload asks where it
  goes - never the company knowledge unasked: the upload buttons wait until the areas are loaded, "Aktualisieren"
  loads them again after a failure); a column "Bereich"; "Bereich
  ändern" in a row's ⋮ menu; in the panel's ⋮ menu "Bereiche verwalten" (dialog) and "Alle hochgeladenen in „…“
  löschen". Page "Benutzer": the areas in the user dialog and as a column; page "API-Keys": the areas of every key and
  of the MCP server.
- A user or key losing an area finds its documents no longer in new questions; answers already given stay in the
  conversations as they are (docs/privacy.md).

## Assistant settings and templates

Decided 6 October 2026: a company tunes how its assistant answers - "how much it may invent, how much it thinks" -
and keeps templates for kinds of questions ("Angebotsprüfung", "Nachschlagen").

- **The tenant's settings** (`tenant_assistant`, management database; `GET`/`PUT /tenant/admin/assistant`, the admin
  `/admin/tenants/{id}/assistant`; page "Assistent" / dialog of the same name in the tenant list), kept by its tenant
  admins and the admin, without a row the defaults (`AssistantService.DEFAULTS`):

  | Setting | Values (default) | Reaches the model as |
  |---|---|---|
  | instructions | text up to 4000 characters ("") | the system prompt of **every** question |
  | answer source | `DOCUMENTS_ONLY` (default) / `DOCUMENTS_FIRST` | a rule of the system prompt: only from the documents, or the documents first and general knowledge marked as such |
  | creativity | `LOW` / `MEDIUM` (default) / `HIGH` | `temperature` 0.2 / 0.7 / 1.0 - within every provider's range |
  | reasoning | `OFF` / `LOW` (default) / `MEDIUM` / `HIGH` | each provider's own parameter, see below |
  | search results | 3-20 (5) | the passages one `similarity_search` returns (`KnowledgeSearch`, the candidates per side at least 20) |
  | default model | a chat model of the tenant, or none | the model of questions that choose none - those of the MCP server ([mcp.md](mcp.md)); one the tenant can no longer use is refused there (400) and left out of the settings shown, so saving them never fails on it |
  | image model | an image model of the tenant, or none (`.../assistant/image-models` lists them) | the model the chat creates images with (tool `create_image`, "Files in the chat") - none chosen: the only image model the tenant may use; with several and none chosen, none (`AssistantService.imageModel`); a chosen one the tenant can no longer use (taken away, deactivated) is ignored and left out of the settings shown, so saving them never fails on it |

- **Templates** (`chat_template`, unique name per tenant, `.../assistant/templates`): a name, instructions of their own
  (next to the tenant's) and any of the four settings - null takes the tenant's. A user chooses one in the chat next to
  the model ("Standard" = none, `GET /tenant/chat/templates` lists the names only); the client software with
  `GET /api/v1/templates` and `templateId` at `POST /api/v1/chat`. The conversation keeps the template of its last
  question (`conversation.template_id`, tenant database) and the chat shows it chosen again; a deleted template leaves
  "Standard". An unknown or another tenant's template answers 400. **A template needs no question** (decided 24
  October 2026): a template can be the task itself ("Videoanalyse" - its instructions say what to do with the
  conversation's video), and typing "bewerte" to start it was pointless. Sent empty with a template, the question is
  the template's name (`ChatService.questionText`) - stored, shown in the history, the title's source; the chat's
  send button is on with a template chosen, its placeholder says so (`hasQuestion` in `utils/chat.util.ts`), and it
  sends the name itself so the pending exchange shows it. Without a template an empty question is 400 as before.
- **The system prompt** (`ChatService.instructions`): our rules first - the tools offered, where each statement comes
  from (the document's file name in brackets right after it - a list of sources at the end does not tell which
  statement rests on which document), the answer source (with `DOCUMENTS_FIRST` every part from general knowledge is a
  paragraph or list item of its own beginning with "General knowledge:" in the answer's language, and the documents win
  where general practice differs - tuned on a test knowledge base, October 2026), what a search did not find
  (said as not found in the documents searched, never as not existing - a model may claim there is no documentation
  for something the documents do cover) and what is not in the passages (names, values, codes and settings never
  written as if documented; a conclusion from a similar case named as one, with its document - a model may borrow a
  similar product's values as "a safe bet"; a rule to mark it, not a ban, since the conclusion helps) - then
  "=== The company's instructions ===" (always) and "=== The instructions of the template ... ===", each with the note
  that they shape tone, form and content while the rules above still apply. A company cannot write the rules away.
  The rules and their examples ("(Source: Manual.pdf)", "General knowledge:") are English, and the language rule says
  it holds whatever language the documents, the files or the instructions have: German examples made GPT-5 answer an
  English question in German (integration test of the release 1.2.0, October 2026).
- **What a model is sent** - only what the catalog says it takes (`ai_model.takes_temperature`, default on: reasoning
  models such as GPT-5 refuse a temperature, and so do Claude Opus 5.5, Sonnet 5.5 and Haiku 5.5 - HTTP 400
  "`temperature` is deprecated for this model", seen 17 October 2026, Sonnet 5.5 on 19 October: switch it off in the
  catalog; `steers_reasoning`, default off),
  `AiModels.Sent`:
  - Anthropic: thinking `enabled` with a budget (LOW 2048, MEDIUM 8192, HIGH 16384 tokens) and no temperature
    meanwhile (Claude takes none while thinking); the thinking goes back with the tool results (`sendThinking`).
  - OpenAI: `reasoning_effort` minimal/low/medium/high (it cannot think not at all) and `max_completion_tokens`.
  - Gemini: `thinkingBudget` 0 / 2048 / 8192 / 16384 - 0 switches it off (2.5 Flash; 2.5 Pro cannot: choose "niedrig").
    Its thought signatures always go back within the agent loop (`returnThinking`/`sendThinking`): Gemini 3 refuses a
    tool's result without the signature of the call that asked for it.
  - Ollama: `think` false/true; OpenAI-compatible servers: `reasoning_effort` low/medium/high, nothing for off.
  - **The output limit** counts reasoning and answer together at every provider (vLLM's `max_tokens`, OpenAI's
    `max_completion_tokens`, Anthropic's `max_tokens` above the thinking budget, Gemini's `maxOutputTokens`). It is the
    catalog entry's `max_output_tokens` ("Max. Ausgabe-Tokens", chat models, 256..1 000 000), else
    `app.ai.max-output-tokens` (16384); a thinking budget the tenant chose is added on top. A model that thinks on its
    own without being steered (Qwen on vLLM) spends the limit on reasoning first - the former 4096 left it without a
    word of answer (seen October 2026): give such a model room in the catalog (e.g. 32768).
  - Transcripts and the scan reader send nothing (`ChatOptions.NONE`): the model's defaults.

## Chat

- `services.chat.ChatService`: the user picks an active chat model per question. The model gets the instructions
  (with the tenant's name, to answer in the language of the question and never to invent company facts), the last 20
  messages of the conversation and the question - and **decides itself what else it needs through its tools** (see
  "Tools"): searching the documents, reading the conversation's files, creating files. There is no automatic search
  any more (decided 6 October 2026). The answer names the documents its searches found (`sources`).
- Without an embedding model there is no search tool, and the instructions say there are no documents.
- **The client software asks too** (`/api/v1`, decided 6 October 2026): `GET /api/v1/models` lists the active chat
  models with `usable` (the tenant has a key for the provider), `POST /api/v1/chat` asks - `POST /api/v1/chat/stream`
  the same, read as it comes: the chat's server-sent events (`thinking`, `delta`, `sources`, `tool`, then `done` with
  the answer as `/api/v1/chat` returns it, or `error`; between them a comment `: keep-alive` every 20 s - `ChatEvents`,
  added 18 October 2026: a quiet stream while code ran for minutes was closed by the server's idle timeout of 5
  minutes, the answer going on unseen), with a key that keeps no history stored nowhere
  (`ChatService.streamOnce`), with one that keeps it stored in its conversation (`stream`, as a user's); what is
  refused before the answer starts is a plain JSON error, so the client accepts both (decided 13 October 2026) -, and
  its conversations are listed, read and deleted under `/api/v1/chat/conversations`. They belong to **the API key that started them**
  (`conversation.api_key_id`, `user_id` NULL, tenant migration V2; `entities.chat.ConversationOwner`) - decided 9
  October 2026: each key finds its own knowledge areas, so another key must not read, continue or download from its
  answers. No user sees them, and the API sees no user's. Revoking a key deletes its conversations with their files
  (after the commit, like deleting a user); conversations from before the change have no key and are reached by none.
  Their usage has no user. The connected software's tools reach it only where a tenant admin gave its key the
  connection, and only the reading ones ([mcp.md](mcp.md) "The client software only for the keys given it") - e.g. a
  file sent with the question checked through a connected software's tools.
- **An API key without history** (decided 7 October 2026, `tenant_api_key.keeps_history`, switch "Verlauf speichern"
  per key for the admin and the tenant admins, default on): every question of the key is answered on its own
  (`ChatService.answerOnce`, like the MCP server's) and **nothing is stored** but the usage - no conversation, no title,
  no created file. `modelId` may be left out (the tenant's default model answers, 409 without one), a `conversationId`
  is 400; the response has no `conversation` and no `question`, and the answer no id. Such a question may bring text
  files - see "Files sent with a question". A key that keeps history gets 400 for `files` (it uploads them to the
  conversation, `POST /api/v1/chat/files`). The key's switch rides in its authentication (`Roles.KEEPS_HISTORY_ATTRIBUTE`,
  `TenantContext.keepsHistory`), read at every request - a change applies to the next question.
- **Conversations belong to their user** (tenant database `conversation` / `message`, keyed by the user's id): other
  users of the tenant do not see them (404).
- No transaction is held while the provider answers (it can take up to `app.ai.timeout`, 300 s - an answer that is not
  streamed arrives only at its end, after the model's reasoning; the frontend waits as long); question and answer are
  stored together afterwards - a failed call leaves nothing behind.
- **An answer without text is a failure** (`AgentLoop.requireAnswer`): nothing is stored, and the chat shows an error -
  "used up its output limit on its reasoning before it answered" when the model was cut off before its first word
  (finish reason LENGTH: raise its output limit in the catalog), else "gave no answer". `/api/v1/chat` answers 502, the
  MCP server a tool error. An answer that stopped at a changing tool call or created files may have no text.
  **Before "gave no answer" the model is asked once more** (`AgentLoop.askAgainIfEmpty`, decided 13 October 2026): a
  round ended without a word and not cut off (any round, also the last - judged on that round, so an earlier round's
  draft next to its tool calls does not stand as the answer) gets the conversation so far - the question,
  the tool calls and their results, not the empty response (providers refuse an empty assistant message, and its tool
  calls would stand without results) - plus one user message (`AgentLoop.ASK_AGAIN`): answer now, in text, from what
  was found, and say when that does not answer it. That round has no tools, is recorded like every other and is never
  stored: the conversation keeps the question and the answer only. Empty again, the asker gets the 502; cut off by the
  output limit, the model is not asked again - it would be cut off the same way. A self-hosted model ended
  rounds empty now and then (October 2026) - its answer written into its reasoning, or a search wanted in the last
  round, which has no tools - and the passages it had found were lost with the 502. Every empty round leaves a warning
  (in the admin's application log and the daily report) with what tells its causes apart: the round of how many,
  whether tools were callable in it, the finish reason, the lengths of text and reasoning, the number of tool calls
  and "asked again", "empty again after asking" or "cut off by the output limit" - lengths and counts only, never
  the content.
- **The model's reasoning is shown while it thinks** (decided 7 October 2026): where the provider hands it over - vLLM's
  `reasoning` (Qwen), Ollama's `thinking`, Claude's thinking, Gemini's thought summaries (`includeThoughts` while it
  thinks); OpenAI's GPT models give none through this API - the stream sends it as `thinking` events before the answer
  (`AiModels` passes `onPartialThinking` on, `StreamStop` too). The chat shows it collapsed above the answer, with its
  latest line while the model thinks ("Denkt nach …", `components/chat/chat-thinking.component.ts`), and keeps it for
  the session ("Gedankengang anzeigen"). **It is never stored**: long, often in English, not part of the history -
  after a reload only the answer is there. The MCP server and `POST /api/v1/chat` get the answer alone, in one piece;
  `POST /api/v1/chat/stream` streams it like the chat.
- A provider's failure answers **502** (in the stream: an `error` event) with a short reason ("refused the API key", "does not know the model", "rate
  limit or quota reached") - never 401/403, which the frontend takes for its own session ending. A chat call is asked
  **once** (`maxRetries` 0): langchain4j's two retries, on a timeout too, made the 502 come after three times
  `app.ai.timeout`, each attempt possibly billed; the embedding and the image model reading a scan's pages
  (`AiModels.chatInBackground`) keep them (background, rate limits of a large import - one refused page would fail a
  scan of 200, and its retry read every page again). A refused key is not asked again.
  The log names provider, model, the failure's kind and HTTP status - never the provider's answer, which may repeat
  the request (the company's data).
- **An answer that stopped at a changing call** carries a note in the history: "[Stopped at the call <software> <tool>
  <arguments> - run by the user's decision, result: ... / declined by the user, not run / expired, not run]". Such an
  answer often has no text of its own (Gemini and OpenAI call without one) - Gemini and Claude refuse an empty answer
  in the history, every later question of the conversation failed -, and without it the model would not know the
  change was made and might ask for it again. An answer without any text goes as "[No text]".
- **Runaway answers**: a model can get caught in a loop of blanks - seen with Gemini 2.5 padding a Markdown table:
  366 000 spaces after 764 characters, until the output limit; the browser's Markdown renderer hung on it (marked: "too
  much recursion"). `AiModels.StreamStop` cancels a streamed answer at the provider after 200 blanks in a row and
  completes it with the text so far (its tokens are then unknown - recorded as estimated); an answer not streamed
  (/api/v1/chat) loses such runs before it is stored (`AgentLoop.withoutBlankRuns`), and the frontend collapses them
  before rendering (answers stored before). Gemini's default temperature is 1.0; lower ones make such loops more
  likely (Google says so explicitly for Gemini 3) - for Gemini models "Nimmt eine Temperatur" may better be off.
- **Reasoning in a loop** (decided 13 October 2026): a self-hosted model (OpenAI-compatible, no
  temperature and no reasoning effort sent - the server's own sampling) repeated a cycle of two paragraphs, 575
  characters, word for word in its reasoning for minutes - its answer already planned ("Answer: ..."), never written -
  until the output limit. Neither the blank check nor the silence check saw it: tokens kept coming. `AiModels.StreamStop`
  watches the streamed reasoning with a `CycleDetector`: the text cut into lines (and every 400 characters), each kept as
  a 64-bit hash with its length (the last 512, no text), and a loop is the same block of lines - however many different
  paragraphs it holds - **five times in a row, at least 200 characters** (a tandem repeat; the real loop is found after
  about 2,900 characters instead of the output limit). Then the stream is cancelled and the round completes without
  text (finish reason OTHER, tokens estimated), so the agent loop asks the model again for its answer, without tools,
  from what it found ("An answer without text is a failure"); looping there too, the asker gets the 502 after seconds,
  not minutes. The warning names copies, lines and characters of the block and the reasoning read - never its content.
  Word for word on purpose: a model replanning in other words is never stopped. Code
  does not count - lines in a fence (```) and lines starting with '<' or '|' are left out: a model drafting the same
  configuration for five devices in its reasoning repeats a block on purpose, the loop seen is prose. Only the
  reasoning is watched: an answer may repeat a block on purpose too, and the asker sees it and can stop it. It holds for every asker: the answer in one piece (/api/v1/chat, the MCP server) is streamed
  from the provider too and collected (`AgentLoop.run`, decided 13 October 2026) - one path, the stream's protections
  everywhere. Not done on purpose: penalties against repetition (`frequency_penalty`,
  `presence_penalty`) sent by the application - they would push a model to vary repeated lines of an XML
  configuration; a penalty belongs to the model on its server (vLLM `--override-generation-config`), where its maker
  recommends one.
- **Silent streams**: langchain4j's timeout (`app.ai.timeout`) only bounds the wait for a response's headers - a stream
  that falls silent after them never ends (measured 11 October 2026 against an Ollama and an OpenAI-compatible server
  in `ModelServerSpec`): the answer waited for good and the sandbox leased for it stayed taken. The same `StreamStop`
  watches every streamed answer: each sign of it - text, reasoning, a tool call streaming - counts, and after
  `app.ai.timeout` without one the stream is cancelled and the answer fails with 502 `provider.stalled` ("stopped
  answering - please try again"); nothing is stored. One wrapper ends an answer exactly once, whichever comes first -
  the provider's end, its failure, a runaway, the silence; one watchdog thread, one check per answer at a time (not
  langchain4j's own OkHttp client with its socket read timeout: a beta module and three new runtime dependencies for
  the one case, decided 11 October 2026). An answer not
  streamed is not watched (a server usually sends its headers only with the whole answer - not measured).
- **Conversation titles** (`services.chat.ConversationTitles`, decided 6 October 2026, like the big chat apps): the
  first question of a conversation - a new one, or one started with files ("Dateien: ...") - is sent in the background
  to the same chat model with "at most six words, in the language of the message" (creativity low, reasoning off),
  while the answer is written. Storing the answer waits for it up to 3 seconds and renames the conversation; a title
  coming later is stored when it comes (the list shows it at its next reload). Quotes, Markdown marks, a "Titel:" and a
  final full stop are taken off, at most 80 characters. Without a title the conversation keeps the question's first
  line - the answer never depends on it. The title is a call of its own in the usage, also when the answer fails.


## Files sent with a question

Decided 7 October 2026: client software may send large text files (logs, exports, source code) to have them checked
against the company's documents - and the same for the MCP server's
`ask_assistant` (source code, [mcp.md](mcp.md)). `[{name, content}]` in the JSON body: text only, read for this answer,
kept nowhere (`services.chat.SentFiles`).

- **Checked first** (400, over MCP a tool error): at most 20 files, a name of 1 to 200 characters and no name twice
  (case-insensitive - the model reads a large file by its name), a content, at most
  `app.chat.sent-files.max-text` characters together (10,000,000; the request buffer, 50 MB, holds them escaped).
- **Small files go with the question whole**, each under `=== The file "<name>" (sent with the question) ===`, as long
  as they fit into `app.chat.sent-files.inline-text` together (500,000 characters - what a model with a large context
  reads in one go); otherwise each file in turn while that budget lasts.
- **A larger file is not cut, filtered or condensed** - decided: the model decides what matters. The
  question carries an overview of it - its characters and lines, nothing that assumes a kind of text (decided 10
  October 2026: logs are a tiny share of the files sent; the time stamps and log levels it showed before are gone) -,
  and the model reads it with two tools offered only then (`ToolContext.exploredFiles`):
  `search_file(file_name, query, from_line?)` - case-insensitive plain text, alternatives separated by `|`, the
  matching lines with their numbers; no regular expressions, one written by a model could run for minutes on 4 MB -
  and `read_file_lines(file_name, from_line, to_line)`. Per call at most `search-results` matches (200), `read-lines`
  lines (500) and `tool-text` characters (40,000); a line is cut after 1000 (search) or 2000 (read) characters; the
  result says how to go on - a search ends at the first match that does not fit, so from_line leads back to it. The instructions tell it to explore until it can answer, to check against the documents
  and to name file and line numbers.
- **Up to `app.chat.sent-files.rounds` rounds** (12) when a file is explored, else 6 - every round is a provider call
  in the usage. Whoever wants one round sends less: what fits into `inline-text` goes in one call.
- A model that cannot call tools gets the rest cut at the budget, with a note to say so.
- All limits are `app.chat.sent-files.*` properties (`APP_CHAT_SENT_FILES_*`).

## Files in the chat

Decided 6 October 2026: files belong to a conversation, are **not embedded** and live as long as the
conversation; the client software has the same as the users.

- **Upload** (`POST /tenant/chat/files`, `POST /api/v1/chat/files`; multipart: one or more `files`, optional
  `conversationId` - without one a new conversation "Dateien: <name>" is created). `services.chat.ChatFileReader`
  reads each file, all before anything is stored (one bad file refuses the upload):
  - the knowledge base's document types (`DocumentTextExtractor.ALLOWED`) as text (Tika);
  - images (PNG, JPEG, GIF, BMP - what Java reads without a plugin; WebP/HEIC are refused) as one JPEG of at most
    2048 pixels on the longest edge (larger only costs tokens);
  - a **scanned PDF** - less than 50 characters of text per page - as one such image per page (PDFBox renders them,
    120 dpi, at most 20 pages);
  - **audio** (any `audio/*` ffmpeg reads - MP3, M4A, WAV, OGG, ...) as a small copy for the models
    (`MediaConverter`, ffmpeg): MP3 mono 16 kHz 32 kbit/s (what Gemini and OpenAI read). A copy above 20 MB (what the
    providers take inline - about 80 minutes) is refused;
    ffmpeg stops writing one byte past that (`-fs`), so hours of speech at a low bitrate fill neither /tmp nor the heap
    before the refusal (`MediaConverterSpec`).
    ffmpeg reads only the uploaded file (`-protocol_whitelist file`); playlists (M3U/HLS, PLS), which would point it at
    the network or at the server's own files, are refused (415) before ffmpeg sees them - also when Tika calls them
    audio (an M3U with CRLF line ends is `audio/x-mpegurl`; `ChatFileReader.PLAYLISTS`).
    **Audio also gets a transcript** right away (`AudioTranscriber`, decided 6 October 2026): the catalog's
    transcribing model (one chat model reading audio, chosen by the admin) writes it with the tenant's key; without
    such a model or key, or when it fails, the file is kept without one. It is written before anything of the file
    is stored: a restart during the minutes it can take leaves no content without its row, which no retention would
    delete.
  - **video** (`video/*` - MP4, MOV, WEBM, ...) as it is, no copy: the model examines it with code in the tenant's
    sandbox, so a tenant without one gets it refused (409); up to `app.chat.video-max-size` (500 MB) - one above the
    50 MB of every other file is recognised by its first 64 KB and streamed to the storage, never held in memory; the
    videos of one conversation together as well (409 `chatFile.videosTooLarge`) - [code-execution.md](code-execution.md)
    "Videos".
  - **every file expires**: deleted from the storage after its retention time (a video 8 hours after its upload, any
    other file 14 days after it was stored - `ChatFileCleanupJob`), its row kept without its text, marked
    `expired_at` - [code-execution.md](code-execution.md) "Videos", "Retention".
- **The question lists the conversation's files** (file_id, name, type, size, "scanned: n page images" for a scan,
  "transcript available" / "no transcript" for audio, "created by you" for a generated file, "expired" for a file
  deleted after its retention time); their content is
  **not** sent along. The model reads what it needs with the tool `read_file` (decided 6 October 2026). **Without a
  mode the application picks the form** from the file's type, what the answering model reads (the catalog's
  `reads_pdf`, `reads_audio`; images every chat model reads) and the size:

  | Type | The original when ... | otherwise |
  |---|---|---|
  | image | always (the stored JPEG) | - |
  | PDF | the model reads PDFs and it has at most 20 MB (`PdfFileContent`) | a scan's page images, else its text |
  | audio | the model reads audio (the MP3 copy, `AudioContent`) | the transcript - or a hint to choose a model that listens |
  | video | never | a hint to examine it with the code tools in `data/` (ffprobe, ffmpeg, `view_images`) - or that they are not available |
  | anything else | - | the text (Tika) |

  The model may ask for a form itself: TEXT (100 000 characters per call, `offset` for the next part - e.g. a long
  text PDF, much cheaper than the original, whose pages the providers bill as images too), PAGES (a PDF's pages as
  images, rendered on demand or a scan's stored ones, at most 10 per call), ORIGINAL (the same as no mode). The user
  never names a mode. In the tests GPT-4.1 mini chose TEXT for a short PDF and asked for the transcript of an audio
  file it cannot hear; Gemini 2.5 Flash heard an audio file as it is.
  Reading on demand saves the tokens of resending every file with every question, so the limits are per file:
  2 000 000 characters of text, 20 page images of a scan, 20 MB for the model's copy of audio; at most 10 files per
  upload, 50 MB per file and together - a video on its own up to its size - checked by the sizes the parts declare
  before any is read (`ChatFileService.requireWithinLimits`: the server takes requests up to a ZIP archive's 512 MB and
  keeps parts above 1 MB on disk). `read_file` and the question's file list
  read only the text they need (one file's, resp. which files have one) - never all texts of the conversation.
- **The model creates files** with the tool `create_file` (`CreateFileTool`, langchain4j tool specification: file
  name, format, content). Formats (`OutputFormat`): TXT, MD, CSV (with a byte order mark for Excel), HTML, JSON as
  written; **DOCX** and **PDF** from Markdown (`FileRenderer`: flexmark's tree written with POI, resp. HTML rendered by
  openhtmltopdf on PDFBox 3 - Helvetica, so characters beyond Latin-1 such as emoji become "#"; nothing is loaded from
  outside the text: an image or style sheet the Markdown references - by a prompt injection an internal address or a
  file on the server - is left out, and openhtmltopdf's own log, which would name it, is off); **XLSX** from CSV
  (`;`, `,` or tab, quoted fields, numbers as numbers - not with a leading zero, a postcode or an article number stays
  text, and at most 15 digits before the point, what Excel keeps exactly -, first row bold). The layout of an uploaded original is not kept:
  the model only knows its text.
- **A connected software's tool returns files** (an MCP embedded resource, e.g. a ZIP archive): stored like a created
  file and offered below the answer - docs/architecture/mcp.md "Files in tool results".
- **The model creates images** with the tool `create_image` (`CreateImageTool`, docs/roadmap.md point 25): offered
  where the conversation keeps its files and the tenant has an image model (`AssistantService.imageModel`). Arguments:
  the instruction (complete - the image model does not see the conversation), optionally `file_ids` of the
  conversation's images to change or combine (at most `AiModels.imagesPerCall`; a document, another conversation's
  file or more are an error for the model) and a file name. Without images it creates a new one. An image goes as it is
  when it is a PNG or JPEG of at most 2.5 MB, else as the model's JPEG copy (Gemini takes about 20 MB per request) - an
  image the model created too: Gemini's PNG may be larger (2.7 MB seen at 1536 x 672 pixels), then the next round gets
  the copy (JPEG quality 0.85, no visible loss in the test of 10 October 2026); the stored file stays Gemini's PNG. The result is stored like a created
  file, with its JPEG copy as `image-1.jpg` (the chat model reads it with `read_file`), named after the first image's
  next version (`haus.png` → `haus-v2.png` → `haus-v3.png`) or `bild.png`. The next question lists it, so the user
  improves it round by round. At most 3 calls of the image model per answer (`ToolContext.imageCalls`) -
  counted are calls, not images, as a refused one is billed too. Every call is recorded as usage (kind IMAGE, the asker as
  user) - also one that created no image: the model's refusal goes back to the chat model as an error. The
  instructions tell the model to create an image when asked, whatever the answer source says (an image is a piece of
  work, not a fact), to write any text in it word for word but short and large (Gemini garbled small text - longer
  wording goes into the answer), and, when changing an image, to say exactly what to change or remove (Gemini kept
  both cards of an invitation asked for "only one card": the image model keeps whatever is not mentioned).
- Created files are stored at once and attached to the answer when it is stored (`conversation_file.message_id`); an
  answer that fails afterwards removes them again - only those not attached yet: a file whose row is stored keeps its
  content, whatever fails after it (`ChatFileService.attach`). A file whose content is missing all the same is a tool
  error for the model ("The file's content is missing"), not a failed answer; an uploaded file whose row cannot be
  stored loses its stored content again. A CSV Excel cannot hold (a cell over 32,767 characters, more than 16,384
  columns) is refused to the model with that reason, which can split its table. In the stream the files come with `done` (`answer.files`). The
  frontend shows an image the model created below its answer (`chat-image-preview`, a click shows it large): loaded
  through HttpClient like a download, as an object URL in the chat slice (`imagePreviews`) - the content stays out of
  the store, and the URLs are released when the conversation is left or the user logs out.
- The models like to write made-up links to created files ("[...](sandbox:/name.docx)", seen with GPT-4.1 mini and
  Gemini 2.5 Flash): the instructions and the tool's result forbid it, the frontend renders links other than
  http(s)/mailto as text. An answer loads nothing by itself: images (Markdown or HTML), video and audio elements and
  attributes such as `background` are left out before binding, an image keeps its alternative text - an image address
  written on a prompt injection's behalf would carry the company's data to a third party the moment it is shown. Earlier answers in the history carry "[Created with a tool: name]" - a model
  seeing answers that merely announce a file tends to announce the next one without calling the tool (seen with Gemini). A model sometimes copies such a note into its own answer (Gemini 2.5 Flash, 10 October 2026): a line that is
  nothing but a note of the history is left out of the stored answer (`AgentLoop.withoutHistoryNotes`).
- **Storage**: `storage.FileStorage` (`LocalFileStorage` under `app.storage.local.path`, the
  container volume `/home/app/files`) under `tenants/<tenant>/chat/<uuid>/` - `file` (the original or the created
  file), `image-<n>.jpg` (what the model is sent). The rows are the tenant database's `conversation_file` (tenant
  migration V4; the text the model reads is kept there). Deleting a file, its conversation or the tenant deletes the
  content (the tenant's whole `tenants/<id>/`).
- Download: `GET /tenant/chat/files/{id}/content` / `/api/v1/chat/files/{id}/content` with `Content-Disposition:
  attachment`. A file is reached only through its conversation's owner (user, or the client software).

## Search

- `services.document.KnowledgeSearch` is the one place the search rules live: the chat's tool `similarity_search`
  uses it, later the MCP server and workflows. It finds only the documents of the asker's `KnowledgeScope` (company
  knowledge and assigned areas - "Knowledge areas"). It searches **hybrid**:
  - the **vector search** finds what means the same in other words ("Gewähr" - "Garantiezeit"): the query embedded with
    the tenant's embedding model (recorded as usage), the 20 closest chunks with a score of at least 0.6;
  - the **full-text search** finds what is written exactly (codes, article numbers: "E23", "4711-B"): PostgreSQL's
    text search in the **tenant's search language** (stems, no stop words) over the chunk texts, **any** word of the
    query, the chunks matching more words first - 20 candidates (`TenantEmbeddings.searchText`, a GIN index on
    `to_tsvector('<language>', translate(text, '<>', '  '))`). '<' and '>' become blanks first: PostgreSQL's parser
    takes what stands between them for an HTML tag and drops it with its attributes - a chunk of XML configuration
    (`<object className="NK2400">`) had no word left, not even its code (October 2026). Not langchain4j's own hybrid
    mode: it asks for **all** words, so a question in a sentence ("Was bedeutet E23?") finds nothing by full text;
  - both lists are merged by **weighted reciprocal rank fusion** (a rank r counts 1 / (60 + r), the full-text list only
    with weight 0.3): a chunk high in either list, or in both, comes first; five are returned (the tenant's number of
    results). At full weight the many chunks sharing common words ("Kessel", "NK") pushed good vector hits down;
  - a tenant with a **reranker** has the merged candidates reordered by it before the best are returned - "Reranking".
  Measured (text-embedding-3-small; answering passage first / among the first five / MRR): vector only 56 % / 91 % /
  0.71; hybrid at weight 1.0 62 % / 88 % / 0.71, 0.5 65 % / 94 % / 0.75, **0.3 68 % / 94 % / 0.77** (exact codes first
  63 % to 88 %, other words 40 % to 60 %); langchain4j's own hybrid search exactly like vector only - with questions in
  sentences its "all words" full text never matched. How the chat searches is a setting:
  `app.search.mode` (`HYBRID`, `VECTOR`, `FULL_TEXT`, `LANGCHAIN4J_HYBRID`; env `APP_SEARCH_MODE`) and
  `app.search.text-weight` (default 0.3; env `APP_SEARCH_TEXT_WEIGHT`) - to be changed only after measuring again; the
  measurement compares all modes regardless.
- Scores do not tell an answering chunk from an unrelated one (measured: the best hit of a question without an answer
  scores like a real one), so there is no cut-off beyond the 0.6 - the chat model must notice that the passages do not
  answer the question.
- Performance: the table has an **HNSW index** for the vector search (`embedding_vector_idx`, cosine, created with the
  table like the full-text index): without it every chunk of the tenant is compared with the query - fine for a few
  thousand chunks, too slow for tens of thousands. It searches approximately, at five to twenty results practically
  exactly. pgvector's HNSW takes **at most 2000 dimensions**: the catalog allows no more (`AiModelService.MAX_DIMENSION`)
  and the dimension is asked of the provider (OpenAI `dimensions`, Gemini `outputDimensionality`) - a model offering
  more, like `gemini-embedding-001` with 3072, is entered with 1536. A model entered with more before that limit is
  searched without the index (logged). On a small table PostgreSQL rightly scans instead of using the index.
- **Only the indexer creates the table** (`TenantEmbeddings.storeForIndexing`), with the first document it embeds,
  under the tenant's lock - which a change of the embedding model takes too. The search and the removal of a document's
  chunks never create it: before the first document there is no table, the search finds nothing and there is nothing to
  remove. Two `CREATE TABLE IF NOT EXISTS` at the same moment failed on PostgreSQL's catalog (23505 - a document deleted
  while the first was being embedded answered 500), and a search still holding the previous model would have created
  the table with that model's vector length, failing every later document.
- **Measured, not guessed** (roadmap point 4): `src/test/resources/retrieval` holds a fictional company (16 documents
  in PDF, DOCX, XLSX, CSV, TXT, Markdown, HTML; 43 questions with the passages that answer them, by type: exact codes
  and article numbers, table rows, deep in long step lists, other words, similar documents, configuration in XML,
  no answer). The XML configuration (type CODE, an overview with front matter and emojis and a separate file of XML
  blocks and property tables, added October 2026) covers the case: such a chunk had no word for the full text
  (PostgreSQL dropped the XML as tags) and lost to the prose of a manual in the vector search. The numbers above were
  measured on the 14 documents before it.
  `RetrievalEvaluationSpec` uploads them and measures how often the answering passage is the first hit and among the
  first five - with a real embedding model, so only when `EVAL_OPENAI_KEY` or `EVAL_GEMINI_KEY` is set (a few cents;
  report in `build/reports/retrieval/`) or `EVAL_OLLAMA_URL` (an own Ollama with `bge-m3`); with `EVAL_RERANK_URL` (a
  TEI server) also the hybrid search with the reranker, and every variant's time per search. `RetrievalCorpusSpec` checks without a provider that every expected passage
  survives the text extraction. A change to the search is measured before and after.

- **The search language** (`tenant.search_language`, `entities.tenant.SearchLanguage`, decided 13 October 2026):
  Zirmo is used in any language, so the full-text search's language is a setting of the tenant, not German for
  all. The 29 languages PostgreSQL 18 brings (`pg_ts_config`: English, German, Russian, French, ...) and SIMPLE - no
  language, words found as written: never wrong, only weaker ("Wartung" does not find "Wartungen"). The vector search
  does not depend on it (multilingual embedding models). The admin chooses it when creating a tenant (default SIMPLE -
  a generic product assumes no language); tenants from before the setting keep German (management migration V5).
  The admin (tab "Firmenwissen") and a tenant admin (page "Firmenwissen") change it (`PUT .../search-language`) -
  **nothing is embedded again**, the chunks' text is stored: only the full-text index is built for the new language.
  - The index's name says its language and expression: `embedding_text_<language>_v2_idx` (v2: '<' and '>'
    replaced). `TenantEmbeddings.ensureTextIndex` compares the name with the catalog and, if it does not fit, drops
    every other index of ours (the one of before, `embedding_text_idx`, and the ones named for another language -
    not langchain4j's `embedding_text_fts_gin_index` of the measurement mode) and builds the right one -
    when the table is created, on every start for every tenant and after a change of the language
    (`DocumentIndexer.ensureTextIndex`: in the background on the indexing executor, under the tenant's lock, which the
    indexer creating the table takes too; on start after the server's start, so no index build delays it - the
    search runs without the index meanwhile, right and slower; afterwards a look at the catalog; **on start one tenant
    after the other**, the job queueing itself again for the next (`ensureTextIndexes`, 12 November 2026): a job per
    tenant at once filled the executor's queue, and a document uploaded right after a start waited behind all of them
    - found by the full suite, where a start meets a thousand tenants). It is application
    code, not a tenant migration: the index depends on a setting, and a name is checked more simply than an index
    definition.
  - `CREATE INDEX CONCURRENTLY`: a change comes while the tenant's people chat; nothing waits for a lock. It waits
    for other transactions on the table to end - on a connection of its own with a lock timeout of 2 minutes
    (`TenantEmbeddings.INDEX_LOCK_TIMEOUT`; the usual 10 s are for a row, and the application's own transactions end
    after a minute idle), not the tenant's pool. A build that fails (the wait over, the server out of reach) leaves an
    invalid index, and the indexer tries again after a minute, five times in all (`DocumentIndexer.INDEX_ATTEMPTS`),
    dropping the invalid index first - then an error in the admin's log, and the next start tries anew; a tenant
    deleted meanwhile ends it quietly. Measured without CONCURRENTLY (which reads the table twice): 8 s for 200,000
    chunks, 44 s for a million - the first start after the release builds it once per tenant.
  - Not done on purpose: a language per document (Tika could detect it - but one index has one expression; with a real
    tenant of mixed languages `german || english` in one index would be the simpler answer), a language per area.

## Reranking

Roadmap point 46, built 29 October 2026 (1.9.0): a reranker reorders the search's candidates - switched per tenant,
off unless chosen, so answers can be compared with and without it.

- **What it is**: the embedding model (a bi-encoder) turns question and chunk into vectors separately - fast over every
  chunk, but it never reads them together, so a chunk on the same subject scores like the one that answers. A reranker
  (a cross-encoder) reads question and chunk as one pair and scores how well the chunk answers - more exact, but one
  pass per pair and nothing computed in advance, so it runs over the candidates of the first search only. It finds
  nothing the candidates lack; it only reorders them.
- **The catalog**: model kind `RERANK`, provider `TEI` - a server speaking the `/rerank` API of Hugging Face Text
  Embeddings Inference, an own server like Ollama (address, optional key, `app.ai.plain-http-hosts`). The two belong
  together (`model.rerankOnlyTei`, a check in management migration V16): Cohere, Jina or Voyage in the cloud would be
  one more provider the company's passages go to - not built until someone asks (decided 29 October 2026). Priced per
  million input tokens, estimated from the text (TEI counts none; for an own server the price is 0).
- **Chosen like the embedding model** (`tenant.reranking_model_id`): the admin assigns it to a tenant, the tenant admin
  (page "Firmenwissen") or the admin (tab "Firmenwissen") switches it on (`PUT .../reranking-model` with the model's id)
  or off (`modelId: null`) - nothing is embedded again. Only an active reranker assigned to the tenant can be chosen
  (400 `model.unknownOrUnavailable`). Taken from the tenant's models it is switched off (`TenantModelService.assign`),
  deleted from the catalog too (`ON DELETE SET NULL`); deactivated it is not asked, and the choice stays.
- **In the search** (`KnowledgeSearch`): the hybrid search merges its candidates as before - 20 per side, up to 40
  chunks -, the reranker scores the best `app.search.rerank-candidates` of them (2 to 40, default 10, env
  `APP_SEARCH_RERANK_CANDIDATES`; at least the results asked for) against the question in one call, and the best
  `maxResults` in its order are returned. The setting is the installation's, not a tenant's: it follows the server's
  speed (decided 30 October 2026). Default 10 for a reranker on a CPU: measured 82 % first hits instead of 85 % with
  all 40, in 13 s instead of 45 s and without a timeout; with a GPU 20 (the whole gain). The same everywhere the search runs: the chat's `similarity_search`,
  `/api/v1/chat`, the MCP server's `search_knowledge`, the tasks. More than 40 not before a measurement on a large
  tenant shows the answering passage beyond rank 20 - every further chunk costs the reranker time.
- **A failing reranker never fails the answer**: `AiModels.rerank` asks once, without retries and with a timeout of 30
  seconds (`RERANK_TIMEOUT`, not app.ai.timeout - the question waits); any failure (server down, key refused, an answer
  that does not fit) keeps the hybrid order, logs a warning ("Reranking for tenant ... failed, the search keeps its own
  order: ...") and records no usage.
- **One reranker for every tenant** is enough: it is stateless (question and candidates in, scores out, nothing kept),
  and the tenants are kept apart before it - the candidates come from the asker's tenant database and areas only.
  Searches at the same time wait for each other (TEI batches them); a second instance only when a load shows.
- **No image of our own** (decided 30 October 2026; built and dropped the same day): the public
  `docker-compose.reranker.yml` (CPU) and `docker-compose.reranker-gpu.yml` (NVIDIA, CDI) start Hugging Face's official
  TEI 1.9.4 image - one per architecture or GPU generation (`RERANKER_IMAGE`) - with `BAAI/bge-reranker-v2-m3` of a
  fixed revision (Apache-2.0, about 570 M parameters, trained from `bge-m3`, multilingual), downloaded at the first
  start into a volume, then started without internet. Read-only root, every capability dropped, port 8080, at most
  2048 tokens per batch on the CPU (TEI warms up with its largest batch - at the model's 8192 it took more than 8 GB; a
  chunk is 1000 characters), about 3.5 GB of memory. Why not our own like the embeddings image: the reranker is
  optional, and its GPU images can neither be built nor checked on GitHub's runners. The embeddings image stays - the
  company's knowledge cannot do without an embedding model. The GPU file is not tried yet. Which reranker: decided by a
  measurement, not by benchmarks; `Qwen3-Reranker-0.6B` does not run in TEI (vLLM), Jina's open weights are
  non-commercial, `gte-multilingual-reranker-base` (306 M, Apache-2.0, runs in TEI) not measured yet.
- **Measured** (29/30 October 2026) with `RetrievalEvaluationSpec` (`EVAL_RERANK_URL`,
  variant "HYBRID 0.3 + RERANK"; `EVAL_OLLAMA_URL` for a local bge-m3): answering passage first / among the first five /
  MRR - hybrid 0.3 56 % / 90 % / 0.68, with the reranker **85 % / 100 % / 0.91** (timeout raised for the measurement).
  But on a CPU (TEI cpu-arm64 in a Podman machine, on a laptop CPU) it takes about **1.1 s per candidate** - 45 s for the
  40 of a search -, so with the 30 s timeout 14 of 43 searches kept the hybrid order (74 % / 95 % / 0.82). On a CPU it
  is an experiment at 40. With fewer candidates (30 October 2026): 20 - 85 % / 100 % / 0.91 in 25.6 s, 10 - 82 % / 97 % /
  0.89 in 13.4 s, both without a timeout; hence the default 10. For the chat a GPU (TEI's CUDA image) is the way.

## Tools

Decided 6 October 2026, modelled on Symfony AI's agent toolbox (`src/agent/src/Bridge`): the model
gets tools and decides when to use them; more tools (an API, an MCP server, ...) are more implementations.

- `services.tools.ChatTool`: `specification()` (name, description, JSON parameters - langchain4j's
  `ToolSpecification`), `availableFor(ToolContext)`, `execute(ToolContext, request)` → `ToolResult` (text for the
  model, optionally images). `Toolbox` collects every `ChatTool` bean, offers the ones available for the question and
  runs the calls: a wrong call (unknown tool or file, missing argument - `ServiceException` from the tool) goes back to
  the model as "Error: ..." so it can correct itself - so does any other refusal inside a tool (`ServiceException`): a
  missing API key (409, e.g. no Gemini key for `create_image`) or a failing provider (502, `FakeAiModels.imageFails`)
  is an error text the model passes on, and the answer goes on (`ChatImageSpec`). `ToolContext` carries tenant, user, tenant database and conversation, and collects the
  answer's created files and sources. A scheduled task's agent step asks as `Asker.TASK` with `onlyTools` set
  (search_file, read_file_lines): no other tool is offered or run, no tool of connected software - and the context
  counts the answer's tokens, which the run keeps per step ([tasks.md](tasks.md)).
- `ToolResult` = text plus `ToolAttachment`s (label, MIME type, bytes): image/jpeg, application/pdf or audio/mpeg - the
  loop sends them as `ImageContent`, `PdfFileContent`, `AudioContent`.
- The tools: `similarity_search` (query → the 5 closest chunks of the tenant's documents with a score of at least 0.6
  - the pgvector store's score is (cosine + 1) / 2, so a cosine of 0.2; only with an embedding model; usage recorded),
  `read_document` (with the search: reads on in a document the search found - "Reading on in a document"),
  `read_file` (only when the conversation has files), `create_file` (always), `create_image` (with an image model -
  "Files in the chat"), `web_search` (where the asker may - "Web search"), `search_file` and `read_file_lines`
  (only with a large file sent with the question - "Files sent with a question"), `run_python` and `run_shell` (when the
  tenant has a sandbox of its own and it answers - [code-execution.md](code-execution.md)) with `view_images` (the
  images the code wrote) and `hand_out_file` (a file of the session for the user), and the
  connected software's tools by who asks, less those the question leaves out ([mcp.md](mcp.md) "MCP clients", "Topics
  deselectable per question"; each result starts with `[<software>]`; after our rules, what each such software
  says about its tools in its own `instructions`, cut to 2,000 characters and marked as unable to lift our rules -
  mcp.md "Connected software through to MCP clients"). The instructions carry the rules of the
  tools offered: for any question about the company call similarity_search first; read on with read_document when a
  passage is cut off or the answer needs what is around it; read files as TEXT or PAGES; create
  files instead of pasting them, never write links to them; count, group and compare large files with code.
- **The agent loop** (`services.agent.AgentLoop`, moved out of `ChatService` on 10 October 2026 - it ran there three
  times: in one piece for `/api/v1/chat` with history, for a question answered once, and streamed for the chat): the
  caller gives it model, key, options, messages, tools with their `ToolContext` and the rounds (`AgentRun`) and stores
  what it answers - the answer in one piece is the stream collected (`run`, 13 October 2026, see "Reasoning in a
  loop"). Up to 6 rounds (12 while a sent file is explored) - the model calls tools (several per round), gets their results
  and goes on; the last round must answer in text: Anthropic, OpenAI and Gemini get the tools listed with tool choice
  `NONE` (Claude refuses a history with tool calls without the tools' definitions - "must define tools"), a model on an
  own server gets none (langchain4j's Ollama client refuses a tool choice); a call made anyway is not run
  (`AiModels.request`). Every round is recorded as usage. A tool result can only be text with every provider, so the **files the tools return follow the results as a
  user message of their own** ("The files the tools returned:", each labelled; images as `ImageContent`, an original
  PDF as `PdfFileContent`) - tested with GPT-4.1 mini and Gemini 2.5 Flash (a photo, a scan's page, the pages of a PDF
  with text, an original PDF with text and a scanned one); Anthropic was not tested here.
- **A model that cannot call tools** (`calls_tools` false, "Own model servers"): it is offered none. The application
  runs them itself before asking - `similarity_search` with the question alone (not the files sent with it, a log would
  be searched for instead), `read_file` for every file of the conversation - and sends their results with the question (attachments as above); the instructions say so, and that
  it cannot create files. The documents found are the answer's sources, sent at the start of the stream.
- The stored answer is **the last round's text**: models often write a draft next to their tool calls and repeat it
  once they have the results (seen with Gemini). The stream shows every round's text; `done` brings the stored one.
- **The stream names each tool before it runs** (a `tool` event, `ToolStep`: `name` and `software` - a connected
  software's tool by its own and the software's name, not the one the model sees; null for our own tools): a round of
  tools - a connected software's calls up to `app.ai.timeout` each, an image, code - can be silent for minutes, and the
  frontend gives up on a stream silent for 6 minutes (`timeout.interceptor.ts`, above `app.ai.timeout`). **The chat
  shows the steps** (decided 12 October 2026: the reasoning names them only when the model says so, and OpenAI's GPT
  models give none): collapsed above the answer like the reasoning, while a tool is at work with that tool, afterwards
  the number of steps and their kinds, opened each step (`components/chat/chat-tools.component.ts`, the texts in
  `utils/chat-tools.util.ts`). No arguments - neither a search query nor the company's data sent to its software (not
  done on purpose: a detail per tool needed every tool to describe its call; add it when it is missed). **Kept for the
  session, never stored** - like the reasoning; a changing call the user decided shows in its action card.
- **Stop in the chat only stops listening**: the browser ends the stream, the backend finishes the answer - its
  remaining rounds and tools -, stores it under the asking user and records its usage. The chat reads the rest of the
  stream silently and shows the stored answer once it is there (the conversation reloaded, a new chat opened if the
  user is still on it); a question asked meanwhile is answered without the stopped exchange in its history. A
  stopped answer that fails afterwards says so (`stoppedAnswerFailed`) and the conversation is read again - nothing of
  it was stored. A lost connection is told the same way, the answer possibly still being stored
  (`errors.chat.connectionLost`) - a stream that ends without "done" as well as one torn off with status 0 (a proxy or
  the network cutting it).
- The Toolbox logs every call at DEBUG (`logger.levels.de.alpdesk.services.tools=DEBUG`, e.g. as environment variable
  `LOGGER_LEVELS_DE_ALPDESK_SERVICES_TOOLS=DEBUG`) with its arguments - except a created file's content.

## Reading on in a document

Decided 10 October 2026 (roadmap point 23). A passage of 1000 characters may give a rule but not its example, or step 4
of 12 - e.g. how-tos in Markdown read through `ask_assistant`.
`services.tools.ReadDocumentTool` (`read_document`, offered with `similarity_search`) lets the model read on - **the
model decides** whether and how far, within limits of ours:

- **Only documents its search found in this answer**: `similarity_search` notes each document under the name it shows
  ("From ...") with its best passage (`ToolContext.documentFound`); another name is an error for the model. Two
  documents of the same name (two pushes of "angebot.pdf", one path in two areas) are shown as "name" and "name (2)",
  so each is read as itself. So the
  asker's knowledge areas and the tenant hold by construction - and are checked again when read
  (`DocumentRepository.findFoundText`: READY and in the asker's scope); a document deleted, moved out of reach or
  waiting to be embedded again meanwhile is an error ("can no longer be read").
- **The search result says when to read on**: a passage of a document with more than one chunk is headed `--- From
  "<name>" (one passage of <n>) ---` (`document.chunk_count`, read with the search), and the result ends with a hint:
  cut off, or the question asks for more than the passage holds (all steps, all rules, a whole example) - call
  `read_document` before answering (`SimilaritySearchTool.READ_ON_HINT`). The rule in the system prompt alone was not
  enough: Gemini 2.5 Flash answered "all 12 steps" with the 5 of its passage (10 October 2026). Not for a model that
  cannot call tools - the application searches for it, and it cannot read on.
- **A short document whole** (up to 12,000 characters, `WHOLE_DOCUMENT` - a how-to of three or four pages).
- **A long one around the passage found**: 5,000 characters (`PART`), starting 2,000 before the passage - the passage
  with about two of its size around it. The result names where it stands ("characters a to b of n") and the offsets of
  the text before and after; the model reads further parts with `offset`. Characters, not pages: the same for every
  type, the stored text (`document.text`) is read - nothing is embedded again.
- **At most 30,000 characters per answer** (`ANSWER_LIMIT`, `ToolContext.documentCharactersRead`): the part that
  reaches it is cut and says so, the next call is refused - a question never reads a whole manual.
- **Finding the passage**: a chunk is the context header and a piece of the text, joined by the splitter with
  whitespace of its own - its words are looked for with any whitespace between them, all of them (its first words are
  the overlap and may stand in many places). Not found: the part starts at the beginning.
- No provider call, so no usage of its own - only the chat model's tokens of the next round. The search is unchanged.
- Markdown is not cut by its headings for this (decided 10 October 2026): only if a measurement shows parts that cut
  off what belongs to a hit (an example's code block).
- Tests: `services/tools/ReadDocumentSpec` (whole, around the passage, offset, limit, not found, deleted or moved,
  areas, the chat, `/api/v1/chat` and the MCP server, another tenant); `FakeAiModels` reads on for `[[read-document]]`.

## Web search

Decided 23 October 2026 (roadmap point 24): the chat answers current and outside facts - a minimum
wage, a new law, who holds an office - from the web instead of the model's outdated training. The options weighed (the
providers' own search, SearXNG, Brave, ...) and why they were dropped are in docs/roadmap.md point 24.

- **Tavily, through langchain4j**: `services.tools.TavilySearch` is the one class that knows Tavily - it builds
  `TavilyWebSearchEngine` (basic depth, one credit; 5 hits, each with Tavily's extract of its page) and
  returns langchain4j's `WebSearchOrganicResult`s. Another search engine would replace this class only. What it sends
  is langchain4j's (checked against Tavily's API on 23 October 2026): `POST /search` with the key as `api_key` in the
  body - Tavily's documentation now names only the `Authorization: Bearer` header, the body key still works (the
  measurement); should Tavily drop it, a langchain4j update or a header of our own fixes it. Tavily's limits answer
  401 (key), 429 (rate), 432/433 (plan or pay-as-you-go limit) - each a failed search the model is told of.
  `app.web-search.address` (`APP_WEB_SEARCH_ADDRESS`, empty: Tavily's own) sends the searches to another address
  answering like Tavily - only for a test stack's fake (`devtools/tavily-fake`, decided 8 November 2026), the tenant's
  Tavily key goes there too; not in the compose files of an installation. Not used:
  langchain4j's `WebSearchTool` (an `@Tool` for `AiServices`, which the agent loop does not use - "What of
  langchain4j is used"), the pages' whole text (`includeRawContent`) and Tavily's Extract (not in the module). **Why
  the extracts** (decided 23 October 2026): Tavily's extract is the part of the page matching the query, about 1,000
  to 1,500 characters, the fact in its first sentences; a page's whole text (6,000 to 37,000 characters in the sample
  "Mindestlohn 2026") starts with menus, logos and "enable JavaScript", the fact 80 to 1,500 characters in. Measured
  with Sonnet 5.5 on the extracts: the answers as good as with the first 3,000 characters of each page, the details of
  the e-invoicing stages included; an answer with web search takes 7,500 to 9,800 input tokens against 4,300 to 5,200
  without. An extract is cut at 3,000 characters (`WebSearchTool.PAGE_LENGTH`) should one be unusually long.
- **Whether a tenant has it** - the admin: the catalog entry of kind `WEB_SEARCH` assigned to the tenant ("Model
  access"; the first one assigned counts), and a Tavily key - the tenant's own or, where allowed, the admin's global
  one (billed).
- **Who searches** - its tenant admins (and the admin), off by default (`services.tools.WebSearchAccess`): a user
  through one of its groups with `web_search` (`user_group.web_search`, under the WAM rule of the groups - a tenant
  admin too only through a group), the client software through its API key (`tenant_api_key.web_search`), the MCP
  server through the MCP key it is asked with (`tenant_mcp_key.web_search`, kept when the key is renewed;
  `.../mcp-keys`). A scheduled task is not asked here: its text is its switch - `web: true` on a prompt step with
  `assistant: true`, which `PromptStep` sets itself (tasks.md), never otherwise. A model that cannot call tools gets no
  web search. The
  switches show only where the tenant has the catalog entry (`components/shared/tenant-web-search.ts`, as the image
  model's setting): without it nothing of the web search is seen; switches set before stay stored and work again once
  it is assigned. A missing Tavily key shows on the provider keys ("nicht hinterlegt"), not at the switches.
- **The documents first - enforced**: `web_search` refuses to run until a `similarity_search` of the answer came back
  (`ToolContext.documentsSearched`, set after the search - one that failed, e.g. the embedding provider refusing its
  key, does not count) and tells the model so; a tenant without an embedding model has nothing to search
  first. Whether the passages found answer the question the model decides, by the rule in the system prompt
  (`ChatService.WEB_RULES`): the web only where the documents do not answer or the question asks for current or outside
  facts; the documents first, web pages before general knowledge; each statement from a web page with its address in
  brackets; where a document and a web page differ, the document wins and the answer says so.
- **Not an answer source but a switch next to it** (changed the same day): with "documents only" the rule becomes
  "only from what the documents and the web pages found say" (`DOCUMENTS_AND_WEB_ONLY_RULES`) - every statement has a
  source, only the model's own knowledge is left out; with "documents first" general knowledge comes after the web.
  No switch in the assistant's settings or templates - a template that turns it off comes when a case needs it.
- **What goes out**: only the query the model wrote (cut to 400 characters - a query is a few words, a longer one is a
  pasted passage) - never a document, file or
  the conversation; the rule and the tool's description tell the model to put only the public topic into it
  (docs/privacy.md, SECURITY.md). The pages come back headed as unchecked material whose instructions are never
  followed (prompt injection).
- **At most 3 searches per answer** (`MAX_SEARCHES_PER_ANSWER`); a search that answered is billed (nothing found
  included, as Tavily bills it), a failing one is not and is told to the model as an error.
- **Sources**: the pages' addresses join the answer's sources as they are - the stream's `sources` event,
  `/api/v1/chat`'s `sources`, `ask_assistant`'s "Sources:" line; the chat shows an address as a link by its host
  (`utils/chat.util.ts` `answerSource`). Kept for the session like the documents' sources, not stored.
- **Measured** on 23 October 2026 with Claude Haiku 5.5 (two runs) and Sonnet 5.5 (one run), with Tavily -
  both alike, Sonnet never searched the web before the documents. Haiku's figures:
  questions the documents answer never searched the web (12 of 12), current facts were right with their sources (12
  of 12), a document differing from today's law won (8 of 8) - without naming the difference, since the documents
  answered and so the web was not searched (as decided). In 2 answers Haiku called `web_search` first: the tool's
  refusal made it search the documents, then the web - the enforcement is needed. The sources list every page found,
  not only the cited ones, like the documents' sources.
- Tests: `controller/chat/WebSearchSpec` (switches, documents first, no entry or key, global key billed, the answer
  sources' rules, API key and MCP server, the catalog's rule, at most three, cut pages, failures),
  `services/tools/TavilySearchSpec`.

## Usage and prices

- Every model call - each embedding batch run of a document, the embedding of a question, each answer - is recorded in
  `usage_record` (management database): tenant, user (null for background work), provider, model name, kind, key source
  (`OWN` / `GLOBAL`), input/output tokens, the tokens **read from and written to the provider's cache**
  (`cache_read_tokens`, `cache_write_tokens`, V13 - not in the input tokens), and the **amount at the model's prices of
  that moment** (`UsageService.amount`: the tier by the prompt's length, the cache prices; a later price change does
  not change recorded amounts).
- **The providers count cached tokens differently** - `services.ai.CallTokens` brings them into one form: Anthropic's
  input tokens leave the cached ones out (`AnthropicTokenUsage`: `cacheReadInputTokens`, `cacheCreationInputTokens`,
  in a streamed answer too), OpenAI's and Gemini's include them (`OpenAiTokenUsage.inputTokensDetails().cachedTokens()`,
  `cacheWriteTokens()`; `GoogleAiGeminiTokenUsage.cachedContentTokenCount()`) and are taken off, so input is never
  billed twice. An own server reports a cache the same way as OpenAI (vLLM's prefix caching: `prompt_tokens_details`,
  checked in `ModelServerSpec` for a whole and a streamed answer). The usage pages show the cached tokens in the
  tokens' column and below them.
- Where the provider reports no token count (some embedding APIs, a chat round the stream stopped), the input tokens
  are estimated as text length / 4 - for a chat round the text of the messages sent - and the row is marked
  `estimated`; its output stays unknown (0).
- **A web search** is a row of its own (kind `WEB_SEARCH`) per search that answered, its count as the input tokens and
  the amount at the entry's price per 1,000 searches (`UsageService.recordSearches`); the usage pages show it as calls,
  not as tokens.
- Usage is recorded with the tenant's own keys too (the tenant's overview for its tenant admins, page "Verbrauch" in
  `/app`, `/tenant/admin/usage` - plain users do not see it). What the admin
  bills is the `GLOBAL` part, per tenant and month (`/admin/usage?month=2026-10`, page "Verbrauch"; months in
  `app.timezone`, Europe/Berlin). An invoice document is not built (decided 6 October 2026: prices and usage only).
- **Deleting a tenant deletes its usage** (`usage_record.tenant_id ... ON DELETE CASCADE`, decided 7 October 2026):
  the admin bills before deleting - the confirmation says so and points to deactivating the tenant instead.

## Prompt caching

Roadmap point 15, decided 27 October 2026.
The agent loop sends its whole conversation again every round - instructions, tools, history, the tools' results.
**The rounds of one answer share a cache key** (`ChatOptions.cacheKey`, set by `AgentLoop` per answer, `answer-<uuid>`):
- **Anthropic**: `AiModels.request` sets `AnthropicChatRequestParameters.cacheAutomatically` - top-level
  `cache_control`, the breakpoint moving with the conversation by itself, 5 minutes. Every round reads what the round
  before wrote; the model sees exactly the same. Measured on twelve runs of a log check with Claude Haiku 5.5: about
  56 % cheaper with it.
- **OpenAI**: caches by itself; the key goes as `prompt_cache_key`, so the rounds reach the same cache. Its explicit
  cache options in langchain4j are not used.
- **Gemini, Ollama, OpenAI-compatible servers**: cache by themselves (Gemini implicitly, Ollama its last prompt, vLLM
  with prefix caching) - nothing is sent. Gemini's explicit cached content (stored, billed per hour) does not fit a
  growing conversation.
- **Only the agent loop**: the chat in the frontend, `/api/v1/chat`, the MCP server's `ask_assistant`, a task's agent,
  verify and prompt steps. Single calls with no next round - titles, transcripts, scans, images, task drafts - are not
  cached: a write costs 1.25 times an input token and would never be read. No switch for users or tenants - caching
  changes no answer, only the price.
- A cached prompt is kept by Anthropic up to 5 minutes ([../privacy.md](../privacy.md)).

## Testing

`services.ai.AiModels` is the one class that calls a provider. The specs replace it with `FakeAiModels`, which behaves
like a model using its tools: word-hash embeddings (so the search finds the right chunks), it searches and reads every
listed file when offered the tools, reads on in a document found for `[[read-document]]`, creates a file for
`[[create_file <name> <FORMAT>]]`, asks for an image for `[[create_image <file names>]]` (its image model's fake
returns a small PNG naming the instruction and the number of images it got; `imageRefusal` answers without one,
`imageFails` fails like a provider out of reach), asks for a new image in four rounds for `[[create_image_repeatedly]]`
(the cap of image model calls), searches the web for `[[web <query>]]` after the documents (`[[web-first <query>]]`
before them), and
answers with the
question, the excerpts, images and files it got; `failures` makes the next calls fail like a refused key. No spec calls a real provider; the smoke test does not
either (it embeds and chats through a fake Ollama server of its own) - except the retrieval measurement, which only runs with a key set (see "Search") and switches the fake off
with `eval.real-ai-models=true`. `ModelServerSpec` runs the real `AiModels` against a small HTTP server in the spec that
answers like Ollama and an OpenAI-compatible server: address, key, context length, no `dimensions`, streaming, no
redirect followed, the failures' wording - and like Gemini for its image models (`generateContent`: the instruction
before the images, the key header, the image, text and usage of the answer, failures not retried), asked there through
`AiModels`' package-private constructor with a Gemini address; the bean always asks Google. Tavily is replaced by
`services.tools.FakeTavilySearch` (two pages on example.com, `failures`, `nothingFound`, `longPages`);
`TavilySearchSpec` runs the real `TavilySearch` against an HTTP server of the spec that answers like Tavily.

`capacity.CapacitySpec` measures how far one installation carries and only runs with `LOAD_TEST` set - by hand,
through the skill `/alpdesk-load-test` (long, a report rather than a rule, `build/reports/capacity/capacity.md`): creating many tenants and migrating them all on start, the
connections and threads of their pools (one after another and at the same moment, against `max_connections`), and one
large tenant growing in steps - write rate, size of table and indexes, the chat's search time alone and in parallel,
and the search of users who find only part of it (its documents lie mostly in two knowledge areas): their time, query
plan and hits against the admin's - where pgvector's `hnsw.max_scan_tuples` starts to cut them short.
Sizes come from `LOAD_TENANTS`, `LOAD_CHUNKS`, `LOAD_DIMENSION` and `LOAD_SEARCHES`; it embeds with the fake, and it
deletes every tenant it created.

## Not built yet

- An invoice document.
