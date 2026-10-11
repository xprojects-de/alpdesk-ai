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
  to answer and are **sub-processors** - see "What leaves the server".

A company running Alpdesk AI for itself only is operator and controller at once.

## What is stored where

| Data | Where | Personal data in it |
|------|-------|---------------------|
| Tenants, users (username, display name, password hash, the frontend's language), admin flags, the knowledge areas each user finds, access requests (WAM user name and name) | management database | yes - the employees' accounts |
| API keys of the client software, MCP keys | management database | SHA-256 hash and a display prefix only |
| Provider keys, keys of connected software, the token of a tenant's sandbox | management database | encrypted (`APP_SECRET_KEY`) |
| Usage per call (tenant, user, provider, model, tokens, amount, time) | management database | yes - who used which model when |
| The company's documents: their text, chunks and embeddings | the tenant's own database (`tenant_...`) | whatever the documents contain |
| Conversations and messages of the chat, pending tool calls | the tenant's own database | yes - questions and answers belong to a user |
| Files in the chat (uploaded and created), converted copies of audio and video, page images of scans | file storage (`APP_STORAGE_PATH`, volume `files`) | whatever the files contain |
| The question's files while its code runs (sent files and conversation files, what the code writes) | the tenant's own sandbox, in memory (`/work`, `/tmp` and the other writable places) | whatever the files contain - deleted when the answer ends, after 5 minutes idle at the latest; never in another tenant's sandbox |
| Server log | container log | usernames on sign-in events and errors; never a document's content, a prompt or a provider's answer |
| Backups | wherever the operator puts them | everything above |

Besides the session's token (removed at logout), the browser keeps only the colour scheme (`localStorage`). The
frontend's language before the login is the browser's own; nothing of the last user's is kept.

Not stored: the model's reasoning shown while it thinks, and - with an API key whose "Verlauf speichern" is off -
anything of the client software's questions but the usage. Questions through the MCP server (Claude Code) are not
stored either, only their usage.

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
  to the chat model like any tool result.
- **An image asked for in the chat** sends the instruction the chat model wrote and the conversation's images it names
  to the tenant's image model (Google Gemini for now); the image it creates is stored as a file of the conversation.

Which providers a tenant's data may reach is decided by the admin (models assigned per tenant) - audio never goes to a
provider the tenant is not assigned. For data that must not leave the company, use models on your own servers
(`OLLAMA` or `OPENAI_COMPATIBLE`, see [architecture/ai-features.md](architecture/ai-features.md)).

For the big providers, check before use:

- **Where the data is processed** - a provider outside the EU/EEA is a transfer to a third country (Art. 44 ff. GDPR);
- **the data processing agreement** of the provider and whether its API terms exclude training on your data;
- **how long the provider keeps** requests (abuse monitoring, logs).

## Deleting

- **A tenant**: deleting it drops its database (`DROP DATABASE`) - documents, embeddings, conversations - and removes
  its users, keys and usage. Its files in the file storage are removed too; what cannot be removed stays behind
  unreachable and is logged. A file still being uploaded at that moment (a chat file, a document, a logo) may land
  after the removal and stays behind unreachable - the tenant's id is never given out again. Its sandbox's addresses
  and token go with the tenant row; the operator takes the sandbox container down (it holds nothing between answers).
- **A user**: deleting it deletes its conversations with their files. Likewise **an API key**: revoking it deletes
  the client software's conversations it started. A conversation or file that cannot be removed (the database or the
  file storage briefly out of reach) stays behind unreachable, is logged and goes with the tenant. A question still
  being answered at that moment may store its conversation afterwards - reachable by no one, it goes with the tenant;
  so do the client software's conversations from before they belonged to a key. Its usage records stay (without the
  user) for billing.
- **A document or a conversation** is deleted with its chunks or messages and files. Every document uploaded into the
  company knowledge or a knowledge area can be deleted at once (before its ZIP is imported anew).
- **Taking a knowledge area away** from a user or key: new questions no longer find its documents, but answers already
  given stay in the conversations as they are - with what they quoted. Delete those conversations where that matters.
- **Backups** keep everything until they are rotated - plan a retention period, and delete or let backups expire after
  a deletion request where needed.
- **The providers** keep their own copies according to their terms; the software cannot delete them.

There is no automatic retention period: conversations and documents stay until someone deletes them. Set one as an
organisational rule with your tenants if they need it.

## Information to give the users

Tell the tenants' employees, e.g. in your privacy notice:

- that their questions, files and the company's documents are sent to the AI providers named,
- that their usage (who, when, which model, how much) is recorded,
- that the tenant's admins and the operator can set their password and so see their chats,
- that answers of an AI can be wrong and are not checked by a person.

## Not in the repository

Real company documents, chat histories, names and keys never go into this repository - not in specs, issues,
screenshots or comments. What is committed once stays in the Git history.
