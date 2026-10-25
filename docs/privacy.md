# Privacy and data protection

For whoever runs an installation of Alpdesk AI. This is an overview of what the software stores and sends where - not
legal advice. Under the GDPR, the author of the software is neither controller nor processor of your data: the software
runs on your server, and the author never receives any of it.

## Who is responsible

- **The operator** (whoever installs and runs Alpdesk AI, the admin) usually acts as a **processor** for the
  companies it serves (Art. 28 GDPR) - a data processing agreement with every tenant is needed.
- **Each tenant** (a company using the installation) is the **controller** for the data its people and its client
  software put in: its documents, its users' chats and files.
- **The AI providers** (Anthropic, OpenAI, Google, or the operator's or tenant's own model servers) receive data
  to answer and are **sub-processors** - see "What leaves the server". So is **Tavily**, where a tenant uses the
  chat's web search.

A company running Alpdesk AI for itself only is operator and controller at once.

## What is stored where

| Data | Where | Personal data in it |
|------|-------|---------------------|
| Tenants, users (username, display name, password hash, the frontend's language, the last login, since when its sessions are valid), admin flags, user groups and their members (each group's knowledge areas and levels for connected software), access requests (WAM user name and name) | management database | yes - the employees' accounts |
| With the WAM proxy's IDM groups checked (`APP_WAM_GROUPS_HEADER`): the IDM groups the proxy sent on each user's last request | management database, replaced on every change, deleted with the user - and all of them when the app starts without the check | yes - which groups of the company's directory a person is in |
| API keys of the client software, MCP keys | management database | SHA-256 hash and a display prefix only |
| Provider keys, keys of connected software, the token of a tenant's sandbox, the passwords of file stores | management database | encrypted (`APP_SECRET_KEY`) |
| File stores (name, server, user name, folder, the confirmed fingerprint, the last check's outcome) | management database | the user name on the tenant's server |
| Scheduled tasks (their text, check points, recipients) | management database | the e-mail addresses of the recipients |
| A task's runs: what each step did, the lines read from the store's files (after the task's filter, with what it masks replaced by `***`), the findings with their evidence lines | the tenant's own database | whatever the files contain and the task does not mask (log files often hold names, user names, addresses) - 14 days after the run ended (`APP_TASK_RUN_RETENTION_DAYS`; set empty: until a tenant admin deletes the run, or its task) |
| How far a task has read each file: its path, the position and when the file last changed | the tenant's own database | the files' paths - kept with the task until it is deleted, also for a store it no longer reads |
| Usage per call (tenant, user, provider, model, tokens, amount, time) | management database | yes - who used which model when |
| The company's documents: their text, chunks and embeddings | the tenant's own database (`tenant_...`) | whatever the documents contain |
| Conversations and messages of the chat, pending tool calls | the tenant's own database | yes - questions and answers belong to a user |
| Files in the chat (uploaded, created, and returned by a tool of connected software), converted copies of audio, page images of scans | file storage (`APP_STORAGE_PATH`, volume `files`) | whatever the files contain - until `APP_CHAT_FILE_RETENTION_DAYS` (14) after they were stored, an uploaded video `APP_CHAT_VIDEO_RETENTION_HOURS` (8) after its upload (set empty: until they or their conversation are deleted); then deleted with their text, their name, type and size stay listed in the conversation |
| The handbook's websites (static websites uploaded as a ZIP for everyone or a knowledge area) | file storage, their list in the tenant's own database | whatever the tenant puts on them - read by people only, never sent to an AI provider |
| The question's files while its code runs (sent files and conversation files, what the code writes) | the tenant's own sandbox, in memory (`/work`, `/tmp` and the other writable places) | whatever the files contain - deleted when the answer ends, after 5 minutes idle at the latest; never in another tenant's sandbox |
| Server log | container log | ids of tenants and users in errors and warnings; never a document's content, a prompt or a provider's answer |
| The last 1000 warnings and errors of the log, for the daily report and the admin's page "Application log" | the app's memory - a restart empties it | as the server log |
| Backups | wherever the operator puts them | everything above |

Besides the session's token, user name and roles (removed at logout), the browser keeps the colour scheme, the chat
model chosen last, the width of the chat's conversation list and the key of the tenant's own login page, if it was
used (`localStorage`) - these four stay after a logout, so the next user of the same browser starts with them; the
last one tells which company's login page was used. With WAM a logout marks the browser tab (`sessionStorage`, gone
with the tab). The frontend's language before the login is the browser's own.

Not stored: the model's reasoning shown while it thinks, and - with an API key whose "Verlauf speichern" is off -
anything of the client software's questions but the usage. Questions through the MCP server (Claude Code, GitHub
Copilot) are not stored either, only their usage: the files their answer creates or a connected software returns are
written to the file storage for the moment of the answer, go to the MCP client with it and are deleted at once.

## What leaves the server

Every model call sends data to the provider of the model in use:

- **Embedding a document** sends its text in chunks to the tenant's embedding model; images and scanned pages go to
  the model the admin marked to read them.
- **A chat question** sends the tenant's instructions, the conversation's history, the excerpts the search found
  (and, when the model reads on, larger parts of those documents - at most 30,000 characters per answer),
  the files read in that conversation and the results of tools (including answers of connected software) to the chat
  model.
- **Audio** is transcribed by the catalog's transcription model.
- **Code execution** (when the tenant has a sandbox of its own): the question's files go to that sandbox, a container
  of the installation itself - not to a provider, not to another tenant's -, and are deleted there when the answer
  ends; what the code prints goes
  to the chat model like any tool result, and so do the images the model asks to see of what the code wrote (e.g.
  frames cut out of a video) - only those, not the file they came from. A file of the session the model hands to the
  user (a marked still, a clip) is stored as a file of the conversation like one it created.
- **Checking a file store** (SFTP, FTPS) connects to the tenant's own server: the user name and - once the server's
  fingerprint is confirmed - the password go there, and the names of the folder's first entries come back; they are
  shown, not stored.
- **A scheduled task's run** reads the matching files of the tenant's own store; the lines its filter keeps go to the
  tenant's chat model (the provider of that model, with the tenant's or the admin's key) together with the task's
  check points - and the findings with their evidence lines to the verifying model. Without new lines nothing goes to
  a provider. Its mails (the findings with up to 20 evidence lines each, a failure, unclear findings) go to the task's
  recipients through the installation's mail server. **What a step masks** (`mask`: a password, a token, an e-mail
  address in the files) is replaced with `***` before a line is kept - it is neither stored with the run nor sent to a
  provider or in a mail; what a task does not mask goes as it is in the file. A `prompt` step sends its prompt and the answers, lines or
  findings of its inputs to the tenant's chat model; its answers are kept with the run like the findings, until the run
  or the task is deleted.
- **"Draft with AI"** sends the tenant admin's description, the names of the tenant's file stores (with their folders
  and the names of up to 20 of the newest files in each folder) and chat models, and - when a task is changed - its
  current text to the tenant's chat model; no file's content. The draft is not stored. The client software with a key
  the task is assigned to, and an MCP client with an MCP key the task is assigned to, get a run's findings with their
  evidence lines and the answers it hands on (the last answers of the prompt steps it mails) - not the other lines
  read.
- **An image asked for in the chat** sends the instruction the chat model wrote and the conversation's images it names
  to the tenant's image model (Google Gemini for now); the image it creates is stored as a file of the conversation.
- **A web search in the chat** (only where the admin assigned the web search to the tenant and a tenant admin turned
  it on for the asker's group, API key or the MCP server) sends the search query the chat model wrote to Tavily, with
  the tenant's or the admin's Tavily key - never a document, a file or the conversation. The model is told to put only
  the public topic into a query, never names, numbers or details from the company's documents or files; that it never
  does cannot be guaranteed - a model may still carry a word of a document (a product name) into a query. The pages
  found go to the chat model like a document's excerpts, and their addresses are shown as the answer's sources; the
  usage records the number of searches.
- **A prompt of connected software** sends the values a user typed into its form to that software (the tenant's
  own), which makes the text that lands in the user's input field.
- **An answer through the MCP server** goes to the MCP client that holds the tenant's MCP key (Claude Code, GitHub
  Copilot) - with the files the answer created and the files a connected software's tool returned in it.
- **A search through the MCP server** (`search_knowledge`, `read_document`) sends the passages found and the parts of
  documents read - with the documents' paths and ids - to that MCP client, from the company knowledge and the areas
  of the MCP key it was asked with. The client's own model reads them: with claude.ai, Claude Desktop or Cowork they reach
  Anthropic under the plan of whoever added the connector. Only the search query is embedded (by the tenant's
  embedding model); nothing of it is stored but the usage.
- **Connected software passed through to an MCP client** (a connection switched "Direkt an MCP-Clients", given to
  the MCP key): the client's arguments go to that software, and its results - text, the files it returns, a prompt's
  text - go unchanged to the MCP client, whose own model reads them (with claude.ai, Claude Desktop or Cowork under the
  plan of whoever added the connector). Nothing of the call is stored. The instructions the software gives about its
  tools are kept on the connection (at most 2,000 characters, until its address changes or it is deleted) and go to
  every MCP client of a key given it and into the assistant's prompt when the software's tools are offered.

Besides the model calls, **the daily report** (when the operator set a mail server and recipients) goes by e-mail to
the operator's addresses over the configured mail server, every morning: the active tenants' names with counts (users,
documents, questions, the users who asked), database sizes and usage amounts, and the warnings and errors of the log of
the last 24 hours. No document or file names, no questions or answers, no names of people other than what a log line
holds.

Which providers a tenant's data may reach is decided by the admin (models assigned per tenant) - audio never goes to a
provider the tenant is not assigned. For data that must not leave the company, use models on your own servers
(`OLLAMA` or `OPENAI_COMPATIBLE`, see [architecture/ai-features.md](architecture/ai-features.md)).

For the big providers, check before use:

- **Where the data is processed** - a provider outside the EU/EEA is a transfer to a third country (Art. 44 ff. GDPR);
- **the data processing agreement** of the provider and whether its API terms exclude training on your data;
- **how long the provider keeps** requests (abuse monitoring, logs).

## Deleting

- **A tenant**: deleting it drops its database (`DROP DATABASE`) - documents, embeddings, conversations - and removes
  its users, keys and usage. A database that cannot be dropped stays behind unreachable; its name is logged, and the
  operator drops it by hand. Its files in the file storage are removed too; what cannot be removed stays behind
  unreachable and is logged. A file still being uploaded at that moment (a chat file, a document, a logo) may land
  after the removal and stays behind unreachable - the tenant's id is never given out again. Its sandbox's addresses
  and token go with the tenant row; the operator takes the sandbox container down (it holds nothing between answers).
- **A file store**: deleting it removes its server, user name and password at once; nothing of the server's files is
  kept by the app - except the lines a task's runs read, which stay with those runs, and how far a task read each of
  its files (path and position), which stays with the task. A store a task reads cannot be deleted before the task.
  They go with the tenant too.
- **A scheduled task**: deleting it deletes its runs - with the lines read, the findings and the state kept between
  steps - and its read positions; a tenant admin deletes one run, or every ended run of a task, while the admin allows
  the tenant tasks (otherwise the admin does). They go with the tenant too.
- **A user**: deleting it deletes its conversations with their files. Likewise **an API key**: revoking it deletes
  the client software's conversations it started. A conversation or file that cannot be removed (the database or the
  file storage briefly out of reach) stays behind unreachable, is logged and goes with the tenant. A question still
  being answered at that moment may store its conversation afterwards - reachable by no one, it goes with the tenant;
  so do the client software's conversations from before they belonged to a key. Its usage records stay for billing,
  with nothing of the user but its former id, which names no one any more.
- **A file of the chat** (uploaded, created, handed out or returned by a connected software) is deleted from the file
  storage `APP_CHAT_FILE_RETENTION_DAYS` (14) after it was stored, an uploaded video `APP_CHAT_VIDEO_RETENTION_HOURS`
  (8) after its upload - by an hourly job, together with its text in the tenant database (extracted on upload, a
  transcript). The conversation keeps its name, type and size, marked expired; its messages stay. A file that cannot be
  deleted at that moment is tried again at the next run. Set empty, a retention is off.
- **A document or a conversation** is deleted with its chunks or messages and files. What of a document cannot be
  removed at that moment stays behind unreachable and is logged: its chunks go with the next change of the embedding
  model or with the tenant, its file with the tenant. Every document uploaded into the company knowledge or a
  knowledge area can be deleted at once (before its ZIP is imported anew).
- **A handbook's website** is deleted with its files when it is removed or replaced by a new ZIP; a knowledge area
  with a website cannot be deleted before it. What cannot be removed at that moment stays behind unreachable and is
  logged; it goes with the tenant.
- **Taking a knowledge area away** from a group or key (or a user out of the group): new questions no longer find its documents, but answers already
  given stay in the conversations as they are - with what they quoted. Delete those conversations where that matters.
- **Backups** keep everything until they are rotated - plan a retention period, and delete or let backups expire after
  a deletion request where needed.
- **The providers** keep their own copies according to their terms; the software cannot delete them.

There is no automatic retention period: conversations and documents stay until someone deletes them. Set one as an
organisational rule with your tenants if they need it.

## Information to give the users

Tell the tenants' employees, e.g. in your privacy notice:

- that their questions, files and the company's documents are sent to the AI providers named - and, with the web
  search on for them, the search queries the assistant writes to Tavily,
- that their usage (who, when, which model, how much) is recorded,
- that the tenant's admins and the operator can set their password and so see their chats,
- that answers of an AI can be wrong and are not checked by a person.

## Not in the repository

Real company documents, chat histories, names and keys never go into this repository - not in specs, issues,
screenshots or comments. What is committed once stays in the Git history.
