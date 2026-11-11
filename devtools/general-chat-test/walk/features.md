# Where every feature's happy path runs

The inventory of what the software does for an operator, a tenant admin, a user and a machine - and where the happy
path of each runs when the skill `/alpdesk-happy-path` runs: a step of the browser walk (`walk.md`), `check.py` (no
model), `scenarios.py` (a real model), a spec the skill starts, or the smoke test of the jar.

- **specs only** - the Spock specs test it (with fakes), nothing here runs it end to end: the list of what the run
  does not yet prove. A step or check for one of them moves it out of this list.
- **not run** - it needs something this stack cannot bring (an account, a public address, hardware).
- Mode: `both` is walked or checked with and without WAM; `WAM` / `plain` exists in that mode only.

**A new feature gets its line here in the same change** - with the step or check that runs it, or as *specs only*
so the gap is seen.

290 features: 245 run end to end, 36 specs only, 9 not run.

## Sign-in & sessions

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| AUTH-01 | Admin password login, lands on `/dashboard` | both | walk A1 |
| AUTH-02 | Tenant user / tenant admin password login on the neutral page, lands on `/app` | plain | walk B1, C1; `check.py` |
| AUTH-03 | Tenant's own login page with its logo, name, colour; signs in that tenant's users only | both | walk A6, B1; `check.py` |
| AUTH-04 | Username existing in several tenants signs in only on the tenant's own login page; neutral page refuses it and shows the hint | plain | walk D1; `check.py` |
| AUTH-05 | Another tenant's user refused on a tenant's login page; unknown/replaced key or deactivated tenant shows the neutral page (404) | plain | walk A6 |
| AUTH-06 | "Access needs a group": non-admin user in no group is refused with the reason (409 `user.noGroup`) | both | walk D5; `check.py` |
| AUTH-07 | Sign-in through the WAM proxy by header, no password | WAM | walk A9; `check.py` |
| AUTH-08 | Unknown person waits ("Zugang angefragt") and becomes an access request | WAM | walk A9; `check.py` |
| AUTH-09 | Windows account `DOMAIN\name` becomes `name@domain` | WAM | **specs only** - no step yet |
| AUTH-10 | Person with users in several tenants chooses the tenant at sign-in (each with logo); browser remembers the choice; stale choice forgotten | WAM | walk D1; `check.py` |
| AUTH-11 | Access requests: admin lists, assigns to a tenant (active and/or tenant admin, language kept), rejects | WAM | walk A9, E4 |
| AUTH-12 | Tenant admin approves an inactive user ("Wartet auf Freigabe") | WAM | walk B3 |
| AUTH-13 | IDM groups from the proxy's groups header: a user group with a WAM group counts only for people the header lists; right removed in the IDM is gone with the next request; fail closed without header | WAM | walk C1; `check.py` |
| AUTH-14 | Accounts from the IDM: person listed in a group's WAM group gets a user in that tenant at sign-in (active, no password) | WAM | walk B1, C1; `check.py` |
| AUTH-15 | Every tenant-user request checked against the proxy header: another person at the same browser gets 403, forced logout, new person signed in | WAM | walk D3 |
| AUTH-16 | Logout returns to the login page it came from (remembered tenant key) | both | walk C21, E5 |
| AUTH-17 | Tenant switch in the user menu (shown with two or more tenants), new token, all state reset, queued uploads/deletions ended | both | walk D2; `check.py` |
| AUTH-18 | Sessions end at once: user or tenant deactivated/deleted, password set by an admin, username changed, tenant-admin right removed | both | walk D4, E3 |
| AUTH-19 | Another open tab of the same browser reloads when the shared token changes (logout, switch, another person's sign-in) | both | walk D3 |
| AUTH-20 | Wrong role redirected to its own area (admin to `/dashboard`, tenant user to `/app`; non-admin kept off tenant-admin pages) | both | walk C1 |
| AUTH-21 | Unknown username / user without password refused in the time of a wrong password | plain | **specs only** - no step yet |

## Admin: tenants

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| TEN-01 | Tenant list; a row opens the tenant page with tabs in the address (Übersicht, Benutzer, Modelle, Firmenwissen, Handbuch, Assistent, Keys, Software, Dateiablagen, Aufgaben, Branding) | both | walk A2 |
| TEN-02 | Create tenant (own database created, group "Standard" created, login key made, search language chosen) | both | walk A3 |
| TEN-03 | Edit tenant master data; deactivate/activate tenant | both | walk E3 |
| TEN-04 | Switch "may use the admin's global provider keys" (`globalKeysAllowed`) | both | walk A3 |
| TEN-05 | Switch "Darf Aufgaben anlegen" (`tasksAllowed`) | both | walk A3 |
| TEN-06 | Switch "may connect A2A agents" (`a2aAllowed`) | both | walk A3 |
| TEN-07 | Sandbox addresses and token per tenant (address of another tenant refused 409, token never shown again) | both | walk A3 |
| TEN-08 | "Sandbox prüfen": per instance reachable / busy / token refused / unreachable / needs a restart, "Video", "Eigene Programme" and whether instances agree | both | walk A4; `check.py` |
| TEN-09 | Assign catalog models to a tenant (chosen embedding model locked; web search entry and reranker assigned the same way) | both | walk A5 |
| TEN-10 | Tenant overview: usage this month, master data, models | both | walk A2 |
| TEN-11 | Delete tenant (database dropped, files, users, keys, usage gone; confirmation points to deactivating) | both | walk E4 |
| TEN-12 | The admin does everything a tenant admin does, per tenant, on the tenant page's tabs (mirror API `/admin/tenants/{id}/...`) | both | walk A2 |
| TEN-13 | Add a person's existing user from another tenant ("Benutzer aus anderem Mandanten") | both | walk A10 |

## Admin: model catalog, global keys, usage

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| MOD-01 | Model catalog CRUD and activate/deactivate | both | walk A7 |
| MOD-02 | Chat model entry for Anthropic, OpenAI, Gemini with prices per million tokens | both | setup: `setup.py models` makes the entry, every question of the walk uses it |
| MOD-03 | Embedding model entry with dimension (max 2000; Anthropic refused) | both | setup: `setup.py knowledge` makes the entry, every document of the walk uses it |
| MOD-04 | Image model entry (kind IMAGE, Gemini only) | both | setup: `setup.py walk` makes the entry; used by C8 |
| MOD-05 | Own server: `OLLAMA` (context length) and `OPENAI_COMPATIBLE` with address and encrypted key; plain HTTP only to `APP_AI_PLAIN_HTTP_HOSTS`; billed as global | both | walk A7 |
| MOD-06 | Amazon Bedrock chat model (region endpoint, IAM `ACCESS_KEY_ID:SECRET`, EU inference profile, thinking, tools, cache) | both | **not run** - needs an AWS account with Bedrock |
| MOD-07 | GPT through Azure OpenAI v1 endpoint as `OPENAI_COMPATIBLE` | both | **not run** - needs an Azure OpenAI resource |
| MOD-08 | Chat model flags: reads PDF, reads audio, transcribes audio (at most one), "Liest Bilder und Scans für das Firmenwissen" (at most one), "Kann Werkzeuge nutzen", "Nimmt eine Temperatur", steers reasoning, max output tokens | both | walk A7 |
| MOD-09 | Price tier (threshold + tier prices) and cache read/write prices ("Preisstufe und Cache") | both | walk A7 |
| MOD-10 | Web search catalog entry (kind "Websuche", provider Tavily, priced per 1,000 searches) | both | setup: `setup.py full` makes the entry; used by C16 |
| MOD-11 | Reranker catalog entry (kind "Reranker", provider TEI, own server address) | both | **not run** - needs a TEI reranker server - about a second per candidate on a CPU, not in the stack |
| MOD-12 | Embedding model in use cannot be deleted, only deactivated (tenants using it keep it) | both | walk A7 |
| GKEY-01 | Global provider keys per provider (set, replace, delete; only `keyHint` shown), incl. Tavily | both | walk A8 |
| GKEY-02 | Key choice per call: tenant's own key first, else the global one if allowed, else 409 "No API key" | both | walk C2 |
| USE-01 | Admin usage page per month and tenant: the billed (GLOBAL) part, tokens, cache tokens, amounts, web searches as calls | both | walk A12 |
| USE-02 | Every provider call recorded once (chat rounds, titles, embeddings, query embeddings, scans, transcripts, images, web searches, reranking, task steps, drafts) with key source and user | both | walk B19 |
| USE-03 | Prompt caching with Claude / OpenAI cache key; cached tokens priced at cache prices | both | **specs only** - no step yet |

## Tenant users & user groups

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| USR-01 | Users list (name, groups, areas, state, "Aus dem IDM", last IDM groups) | both | walk B3 |
| USR-02 | Create user (username, display name, language, admin flag, groups; goes into "Standard" without `groupIds`) | both | walk A9, B3 |
| USR-03 | Edit user: display name, language, admin flag, groups, active | both | walk B3 |
| USR-04 | Admin / tenant admin sets a user's password (ends the user's sessions) | plain | walk A10 |
| USR-05 | Deactivate / activate user (access ends at once) | both | walk B3, D4 |
| USR-06 | Delete user (its conversations and files deleted) | both | walk E1 |
| USR-07 | Tenant admin cannot lock itself out (deactivate, demote, delete itself: 409) | both | walk B3 |
| USR-08 | Tenant admin copies the tenant's login link (read only) | both | walk B3 |
| GRP-01 | User groups CRUD (name unique per tenant) | both | walk A11, B3 |
| GRP-02 | Group members; a user in several groups gets the union of areas and the highest level | both | walk B3 |
| GRP-03 | Group's knowledge areas | both | walk B4 |
| GRP-04 | Group's level per connected software: "Kein Zugriff" / "Nur lesen" / "Lesen und ändern" | both | walk B9 |
| GRP-05 | Group's web search switch (shown only when the tenant has the catalog entry) | both | walk B9 |
| GRP-06 | Group's WAM group: set by the admin only (tenant admin sees it locked, 409); members then come from the IDM alone, member picker hidden, warning before assigned members are replaced | both | walk A11 |
| GRP-07 | Group "Standard" exists per tenant, may be renamed or deleted | both | walk A11 |
| GRP-08 | User taken out of its last group keeps account and chats but is out with the next request | both | walk D5 |

## Provider keys / API keys / MCP keys

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| KEY-01 | Tenant's own provider keys per provider (set, replace, delete; keyHint; "nicht hinterlegt"), incl. Tavily | both | walk B12 |
| KEY-02 | Client-software API keys: create (plain key shown once), list with rights as tags, change, delete (its conversations and files go) | both | walk B12, E1 |
| KEY-03 | API key rights: knowledge areas | both | walk B12 |
| KEY-04 | API key rights: connected software (reading tools in `/api/v1/chat`) | both | walk B12 |
| KEY-05 | API key rights: scheduled tasks it may list/start/read | both | walk B15 |
| KEY-06 | API key rights: web search | both | walk B12 |
| KEY-07 | API key switch "Verlauf speichern" (off: every question on its own, nothing stored) | both | walk B12 |
| KEY-08 | MCP keys: create (shown once with the Claude Code command), list, change, renew (old secret dead at once, rights kept), delete | both | walk B12 |
| KEY-09 | MCP key name and description (max 500 chars; goes into the server's instructions and tool descriptions) | both | walk B12 |
| KEY-10 | MCP key rights: knowledge areas, connected software ("Name (nur Antworten)" for non-pass-through), tasks, web search | both | walk B12 |
| KEY-11 | MCP key doors: `/mcp`, `/a2a` or both (none refused) | both | walk B12; `check.py` |
| KEY-12 | MCP key card shows the Claude Code command, the claude.ai / Cowork custom-connector fields and Copilot's `.vscode/mcp.json`, named after the brand | both | walk B12 |

## Company knowledge

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| KNOW-01 | Document list with status (waiting, processing, ready, failed with reason), area, folder, source ("Quelle"), waiting count, paging 50/100/all, sticky headers | both | walk B4 |
| KNOW-02 | Upload a single document (up to 50 MB), embedded in the background | both | walk B4 |
| KNOW-03 | Allowed types by content: PDF, DOCX, ODT, RTF, TXT, `.log`, Markdown, HTML, PPTX, XLSX, ODS, CSV; others refused (415); oversized Office part refused | both | walk B4, C4 |
| KNOW-04 | ZIP import with folders (paths as keys, folder column), result dialog per file (CREATED / REPLACED / UNCHANGED / DUPLICATE / SKIPPED); re-import replaces changed, leaves unchanged | both | walk B5, C4 |
| KNOW-05 | Images (PNG, JPEG, GIF, BMP) and scanned PDF pages read by the catalog's scan-reading chat model before embedding; refused without it | both | walk B4, C4 |
| KNOW-06 | "Erneut versuchen" for a failed document (or one stuck > 30 min) | both | **specs only** - no step yet |
| KNOW-07 | "Alle Dokumente neu einbetten" (confirmed, costs like a new upload) | both | walk B6 |
| KNOW-08 | Delete a document | both | walk B6 |
| KNOW-09 | "Alle hochgeladenen in ... löschen" (all frontend uploads of the company knowledge or one area) | both | walk E2 |
| KNOW-10 | Knowledge areas CRUD ("Bereiche verwalten"; area with documents, website or source cannot be deleted) | both | walk B4 |
| KNOW-11 | Area filter "Alle Bereiche / Firmenwissen / <area>"; an upload goes into the area the filter shows | both | walk B4 |
| KNOW-12 | Move a document to another area ("Bereich ändern"), nothing embedded again | both | walk B6 |
| KNOW-13 | An area's documents found only by its groups' members, its keys, and every tenant admin | both | walk B20, C3; `check.py`; `scenarios.py` |
| KNOW-14 | Choose the embedding model (first choice) and change it (drops and re-embeds everything, asks first, needs a key) | both | walk B2 |
| KNOW-15 | Search language of the full-text search (29 languages + SIMPLE), changed without re-embedding | both | walk B6 |
| KNOW-16 | Reranker switched on/off per tenant | both | **not run** - needs a TEI reranker server - not in the stack |
| KNOW-17 | Hybrid search (vector + full text, context header with file name/title/path), sources named by file name | both | walk C2; `scenarios.py` |
| KNOW-18 | Same content under another path stored once per area (DUPLICATE) | both | **specs only** - no step yet |
| KNOW-19 | Documents of several tenants embedded in turn | both | **specs only** - no step yet |

## Knowledge sources

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| SRC-01 | Create a source: file store, folder (typed or chosen in the folder picker), area, pattern, schedule, active; saving only lists and shows what is open | both | walk B14 |
| SRC-02 | "Jetzt synchronisieren" (confirmation with open files and size), list refreshed every 3 s while running | both | walk B14; `check.py` |
| SRC-03 | "Abbrechen" of a running sync | both | **specs only** - no step yet |
| SRC-04 | Schedule: by hand, hourly, every 3/6/12 hours, daily; at most 100 files / 250 MB per scheduled run; pause with `active=false` | both | **specs only** - no step yet |
| SRC-05 | Changed files re-embedded, vanished files deleted (only after a complete listing), unchanged not embedded again | both | `check.py` |
| SRC-06 | Files not read (refused with reason, deleted by hand, type skipped by extension) listed | both | walk B14 |
| SRC-07 | Edit a source: other area (documents move), other store/folder (documents deleted, new listing, warning), other pattern; 409 while running | both | walk B14 |
| SRC-08 | Remove a source: "Dokumente behalten" or "Mit Dokumenten löschen" | both | walk B14 |
| SRC-09 | Overlapping folders of two sources pointed out | both | **specs only** - no step yet |
| SRC-10 | Document of a source shows "Dateiablage: <name>"; deleted or moved by hand stays so | both | walk B14 |
| SRC-11 | A store or area used by a source cannot be deleted / pointed at other files (409) | both | walk B14 |

## File stores

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| FS-01 | File stores list with state, add, change, remove (store used by a task or source refused) | both | walk B13 |
| FS-02 | SFTP store: check shows the fingerprint (UNCONFIRMED), confirm, second check logs in and lists (OK); changed key shown as warning (CHANGED) | both | walk B13; `check.py` |
| FS-03 | FTPS store (explicit TLS): fingerprint flow, or CA-trusted certificate without fingerprint | both | spec `FileStoreServerSpec` with `FILE_STORE_TEST=1` (a real FTPS server) |
| FS-04 | S3 store: Amazon S3 (region) or own endpoint, bucket, folder, access key; check reads the folder; plain HTTP only to internal hosts | both | spec `FileStoreServerSpec` with `FILE_STORE_TEST=1` (SeaweedFS as the S3 server); Amazon S3 itself: not run |
| FS-05 | SharePoint / OneDrive store through Microsoft Graph (directory ID, client ID, secret, site address, library, folder); expired secret named | both | spec `SharePointStoreSpec` against `FakeMicrosoft` - never against Microsoft |
| FS-06 | IMAP mailbox store, read only: messages of the last N days as folders, attachments and `mail.txt` as files; SSL / STARTTLS / plain to internal hosts | both | spec `MailboxStoreSpec` (a mail server of the spec) |
| FS-07 | Failed check shown with its code (unreachable, login failed, folder not found, ...) and kept on the list | both | walk B13 |
| FS-08 | Another server / bucket / app needs the password or secret again; rename or folder change keeps it | both | walk B13 |
| FS-09 | Switch "Aufgaben dürfen hier schreiben" (tagged in the list; refused for a mailbox) | both | walk B13 |
| FS-10 | Internal hosts only when listed in `APP_FILE_STORE_INTERNAL_HOSTS` | both | walk B13 |
| FS-11 | Folder browsing of a ready store (for sources) | both | walk B14 |

## Chat

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| CHAT-01 | Ask a question, answer streamed as server-sent events (thinking, delta, tool, sources, done, error, keep-alive) | both | walk C2; `scenarios.py` |
| CHAT-02 | Model choice per question among the tenant's usable models; user without a usable model told to ask the admin | both | walk C11 |
| CHAT-03 | Answer from the company's documents with sources (file names in brackets, source list) | both | walk C2; `scenarios.py` |
| CHAT-04 | Reading on in a found document (`read_document`) | both | walk C4 |
| CHAT-05 | Reasoning shown collapsed while the model thinks ("Denkt nach ...", "Gedankengang anzeigen"), not stored | both | walk C2 |
| CHAT-06 | Tool steps shown collapsed above the answer | both | walk C2 |
| CHAT-07 | Stop an answer (backend finishes and stores it; shown after reload); stopped answer failing says so; lost connection message | both | walk C17 |
| CHAT-08 | Markdown answers, code blocks highlighted with copy control, links in a new tab, nothing loaded by itself | both | walk C17 |
| CHAT-09 | Upload files to the conversation via paperclip (documents, images, scanned PDF; up to 10 files, 50 MB), strip above the input, read by the model with `read_file` | both | walk C5; `scenarios.py` |
| CHAT-10 | Download and delete a chat file | both | walk C5, C6 |
| CHAT-11 | Audio upload: MP3 copy via ffmpeg and a transcript by the transcribing model; heard directly by a model that reads audio | both | walk C9 |
| CHAT-12 | Model creates files (TXT, MD, CSV, HTML, JSON, DOCX, XLSX, PDF), offered below the answer; at most 5 per answer | both | walk C6; `scenarios.py` |
| CHAT-13 | Model creates or changes images, preview below the answer, large in a dialog, versions (`-v2`) | both | walk C8 |
| CHAT-14 | Code execution: Python (notebook-like) and shell on the question's and conversation's files; busy / unavailable told to the model | both | walk C7; `scenarios.py` |
| CHAT-15 | Code's images looked at and files handed out; several images shown as a sequence with slider/play; clip played | both | walk C7, C10 |
| CHAT-16 | Video upload up to 500 MB, examined with code (frames, pose estimation, stills with skeleton, clips), up to 16 rounds; refused without video sandbox | both | walk C10 |
| CHAT-17 | Own sandbox image's README reaches the model with the code rules | both | **not run** - needs a sandbox image of one's own built on `sandbox-example/` |
| CHAT-18 | Chat templates: choose next to the model ("Standard" = none), kept with the conversation; template without a question starts with Enter | both | walk C11 |
| CHAT-19 | Connected software's reading tools used in the chat by the user's group level; result headed `[Software]` | both | walk C12; `scenarios.py` |
| CHAT-20 | Changing tool call waits for the user: action card with software, tool, arguments, "Ausführen" / "Abbrechen"; expires after a day or with the next question; second click 409 | both | walk C13 |
| CHAT-21 | Topics deselectable per conversation: a chip per connected software with two or more reached (on a phone a menu "Topics n/m"), choice stored with the conversation | both | walk C15; `scenarios.py` |
| CHAT-22 | Files returned by a connected software's tool (embedded resource, image, or `resource_link` fetched at once, up to 50 MB) offered below the answer and readable by the model | both | walk C12; `scenarios.py` |
| CHAT-23 | Prompts of the connected software: bolt button next to the paperclip, form of arguments, text lands in the input (never sent by itself) | both | walk C14 |
| CHAT-24 | Web search after the documents (at most 3 per answer), pages as links under the answer | both | walk C16; `scenarios.py` |
| CHAT-25 | Conversations list, open one (messages with files, pending action, choice), delete one | both | walk C18 |
| CHAT-26 | Conversation title written by the model in the background (max six words) | both | walk C2 |
| CHAT-27 | Chat file retention: files expire after 14 days, videos after 8 hours, shown as expired, hourly cleanup | both | **specs only** - no step yet |
| CHAT-28 | Model without tool calling: application searches and reads files beforehand | both | **specs only** - no step yet |
| CHAT-29 | Tenant answering through its A2A agent: every question goes to the agent, streamed status, question back ("wartet auf Ihre Antwort") answered by the next message, uploads sent along, skills as chips, model and template hidden | both | walk B11; `check.py` (the agent's part, after `setup.py a2a`) |
| CHAT-30 | Large file handling tools `search_file` / `read_file_lines` (for files sent with a question) | both | `scenarios.py` |
| CHAT-31 | Provider failures as readable errors (refused key, unknown model, rate limit, stalled stream, empty answer asked again once, runaway answer stopped) | both | **specs only** - no step yet |

## Assistant settings & templates

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| ASST-01 | Instructions (up to 4000 chars) in the system prompt of every question | both | walk B7, C2 |
| ASST-02 | Answer source: documents only / documents first (general knowledge marked) | both | walk B7 |
| ASST-03 | Creativity low/medium/high and reasoning off/low/medium/high | both | walk B7 |
| ASST-04 | Number of search results (3-20) | both | walk B7 |
| ASST-05 | Default model ("Standardmodell für Claude Code": MCP, A2A, API key without history, task drafts) | both | walk B7 |
| ASST-06 | Image model choice | both | walk B7 |
| ASST-07 | Chat templates CRUD: name, own instructions, any of the four settings | both | walk B7 |

## Connected software

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| SW-01 | MCP connection: add (name, address, optional key), tools and prompts read at once, change, delete | both | walk B8 |
| SW-02 | "Werkzeuge neu laden" (new tools come in off, dropped ones kept as not offered, reading error shown) | both | walk B8 |
| SW-03 | Turn each tool on/off and correct "ändert etwas" | both | walk B8 |
| SW-04 | Turn each software prompt on/off | both | walk B8 |
| SW-05 | Software's own instructions shown (cut to 2,000 chars) and given to the assistant | both | walk B8 |
| SW-06 | Access list by group, read only | both | walk B8 |
| SW-07 | Switch "Direkt an MCP-Clients (Claude Code, Cowork)" (pass-through; refused for A2A) | both | walk B8; `check.py` |
| SW-08 | Address rules: HTTPS, plain HTTP and internal hosts only when listed, key dropped for another origin, another address reads the tools anew | both | **specs only** - no step yet |
| SW-09 | OAuth sign-in to a hosted MCP server: "Anmelden", consent at the server, callback, completion by the one who started it, chip signed in / "Anmeldung nötig", token renews itself, remove sign-in | both | walk B10; `check.py` |
| SW-10 | OAuth client identity: client id metadata document, dynamic registration, or client id and secret entered by hand (dialog shows our redirect address) | both | walk B10; `check.py` |
| SW-11 | A2A agent as connected software (only with the admin's A2A switch): card read, skills shown, model asks through one tool `<connection>__ask` | both | walk B11; `check.py` (the agent's part, after `setup.py a2a`) |
| SW-12 | A2A check button: card read and timed (name, skills, ms) | both | walk B11 |
| SW-13 | A2A agent that signs in with OAuth | both | **specs only** - no step yet |
| SW-14 | Switch "Beantwortet den Chat" (one A2A connection per tenant answers every question: chat, `/api/v1/chat`, `ask_assistant`, `/a2a`) | both | walk B11; `check.py` (the agent's part, after `setup.py a2a`) |
| SW-15 | Who-asks metadata sent to an agent (tenant, language, user or key, groups, IDM groups, skills) | both | `check.py` (the agent's part, after `setup.py a2a`) |
| SW-16 | Our MCP client introduces itself with the tenant's brand | both | **specs only** - no step yet |

## The tenant's MCP server (/mcp)

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| MCP-01 | `initialize` with the tenant's brand as server name, instructions (key description, what the assistant offers, passed-through software's instructions) | both | walk B12; `check.py` |
| MCP-02 | `tools/list` with flat schemas and branded descriptions | both | walk B12; `check.py` |
| MCP-03 | Tool `ask_assistant(question, template?, files?)`: default model, key's areas, "Sources:" line, nothing stored | both | `scenarios.py` |
| MCP-04 | `ask_assistant` with text files sent (`files`), large ones explored by the model | both | **specs only** - no step yet |
| MCP-05 | `ask_assistant` returns files as embedded resources (text as text, others base64; max 8 MB, 5 files; only from pass-through software) | both | **specs only** - no step yet |
| MCP-06 | `ask_assistant` using the key's connected software (reading tools), the tenant's sandbox and the web search where the key allows | both | `scenarios.py` |
| MCP-07 | Tool `list_templates` | both | `check.py` |
| MCP-08 | Tool `search_knowledge(query)` (passages with path, document id, where to read on; no chat model) | both | `check.py` |
| MCP-09 | Tool `read_document(document, offset?)` by id | both | `check.py` |
| MCP-10 | Tools `list_tasks`, `run_task` (waits up to 60 s), `task_run` for the tasks assigned to the key | both | `scenarios.py` |
| MCP-11 | Templates as MCP prompts (`prompts/list`, `prompts/get` with optional `question`; slash commands in the client) | both | `check.py` |
| MCP-12 | Pass-through: reading tools of the key's software listed as `<connection>__<tool>` (max 32 chars) and called by the client; files passed on; every failure a tool error | both | walk B12; `check.py` |
| MCP-13 | Pass-through: software's prompts listed and fetched | both | **specs only** - no step yet |
| MCP-14 | Key boundaries: MCP key reaches only its door (401 at `/a2a` without the door), API keys and user tokens get 403 at `/mcp`, renewed key's old secret refused, `GET /mcp` 405 | both | walk B12; `check.py` |
| MCP-16 | Pass-through of changing tools: "Darf ändern in" per key and software (only software passing through), the changing tool listed as not read-only and called; taken back - gone; `ask_assistant` still reading | both | walk B12; `check.py` |
| MCP-15 | Connecting real clients: Claude Code (`claude mcp add`), GitHub Copilot (`mcp.json`), claude.ai / Cowork custom connector with the `authorization` header | both | **not run** - needs the real clients (Claude Code, Copilot, claude.ai) - by hand |

## The tenant's A2A server (/a2a)

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| A2A-01 | Neutral public agent card (streaming, push notifications, extended card, bearer scheme) | both | `check.py` |
| A2A-02 | Extension descriptions | both | `check.py` |
| A2A-03 | Extended card for the key: brand, key description, skills `answer`, `template:<id>`, `task:<id>` | both | `check.py` |
| A2A-04 | Blocking `SendMessage`: one Message with the answer, "Sources", files as raw parts; text files sent as raw parts; template via metadata | both | `scenarios.py` |
| A2A-05 | `SendStreamingMessage` / `returnImmediately`: a stored Task with the answer as artifact, streamed as SSE | both | **specs only** - no step yet |
| A2A-06 | Start a scheduled task assigned to the key via metadata `task`: Task mirroring the run (events for 30 min), artifacts `report` and `findings` | both | **specs only** - no step yet |
| A2A-07 | `GetTask`, `ListTasks` (own key's only, paged, filtered), cancel, `SubscribeToTask` | both | **specs only** - no step yet |
| A2A-08 | Push notification configs per task (up to 5 webhooks, public HTTPS only) | both | **not run** - needs a webhook reachable over public HTTPS |
| A2A-09 | Task retention: tasks deleted after `APP_A2A_TASK_RETENTION_HOURS` (24) | both | **specs only** - no step yet |
| A2A-10 | Protocol refusals: invalid JSON -32700, 0.3 method names -32601, other version, message to an existing task, `url` parts | both | **specs only** - no step yet |

## Client-software API /api/v1 (Bearer `ai_...`)

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| API-01 | `GET /api/v1/me` | both | `check.py` |
| API-02 | `GET /api/v1/documents` | both | `check.py` |
| API-03 | `POST /api/v1/documents` (multipart `file`) | both | `check.py` |
| API-04 | `POST /api/v1/documents` with `externalId` | both | `check.py` |
| API-05 | `POST /api/v1/documents` with `area` (name) | both | `check.py` |
| API-06 | `DELETE /api/v1/documents/{id}` | both | `check.py` |
| API-07 | `DELETE /api/v1/documents?externalId=` | both | `check.py` |
| API-08 | `GET /api/v1/models` | both | `check.py` |
| API-09 | `GET /api/v1/software` | both | `check.py` |
| API-10 | `GET /api/v1/templates` | both | `check.py` |
| API-11 | `POST /api/v1/chat` with history: `modelId`, `conversationId`, `templateId` (message optional with a template), `excludedConnectionIds` / `excludedSkillIds`; answer with `sources` and files | both | `scenarios.py` |
| API-12 | `POST /api/v1/chat` with a key without history: answered once, nothing stored, `modelId` optional (default model), text `files: [{name, content}]` (duplicate names 400) | both | `scenarios.py` |
| API-13 | `POST /api/v1/chat/stream` | both | `scenarios.py` |
| API-14 | `/api/v1/chat` using the key's connected software (reading tools only) and web search where the key allows | both | **specs only** - no step yet |
| API-15 | `GET /api/v1/chat/conversations` | both | `check.py` |
| API-16 | `GET /api/v1/chat/conversations/{id}/messages` | both | `check.py` |
| API-17 | `DELETE /api/v1/chat/conversations/{id}` | both | `check.py` |
| API-18 | `POST /api/v1/chat/files` (multipart `files`, optional `conversationId`) | both | `check.py` |
| API-19 | `GET /api/v1/chat/conversations/{id}/files` | both | `check.py` |
| API-20 | `GET /api/v1/chat/files/{id}/content` | both | `check.py` |
| API-21 | `DELETE /api/v1/chat/files/{id}` | both | `check.py` |
| API-22 | `GET /api/v1/tasks` | both | `check.py` |
| API-23 | `POST /api/v1/tasks/{taskId}/runs` | both | `scenarios.py` |
| API-24 | `GET /api/v1/tasks/{taskId}/runs/{runId}` | both | `scenarios.py` |
| API-25 | Isolation: one key never sees another key's conversations, a user's chats, or unassigned areas/tasks (404) | both | `check.py` |

## Scheduled tasks

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| TASK-01 | Task list (name, when it runs, last run chip, next run), "Neue Aufgabe" from the template, start now, delete | both | walk B15 |
| TASK-02 | Editor: YAML in CodeMirror with the graph beside it, "Prüfen" lists every mistake with its line, switch "Aktiv", keys that may start it, unsaved-changes guard | both | walk B15; `check.py` |
| TASK-03 | Import (YAML or JSON file), export as `.yaml`, duplicate | both | walk B15 |
| TASK-04 | Format reference ("Format" button, English and German) | both | walk B15 |
| TASK-05 | "Mit KI entwerfen" / "Neue Aufgabe mit KI": describe a task or a change, model writes it, check corrects it (max 3 rounds), never saved by itself | both | walk B17 |
| TASK-06 | Trigger `schedule` with `every` (15m-7d) or `cron`; at most every 15 minutes; downtime made up by one run; running task skipped | both | **specs only** - no step yet |
| TASK-07 | Start by hand ("Jetzt starten"), one run at a time (409) | both | walk B16; `scenarios.py` |
| TASK-08 | Step `read_files`: new lines since the last successful run, `pattern` with dates, `days`, `onlyNew`, `firstRun: end`, `minLines`, `include` / `exclude`, `mask` (`after`, `regex`), rotated files | both | walk B16; `scenarios.py` |
| TASK-09 | Step `agent`: check points with severity `error` / `warning` / `report`, structured findings with evidence lines | both | walk B16; `scenarios.py` |
| TASK-10 | Step `verify`: findings judged again (optionally a cheaper model), edge back with `maxRounds` | both | **specs only** - no step yet |
| TASK-11 | Step `send_mail`: findings / prompt answers / unclear rows / written rows to `to` or `notify`, `if` condition | both | walk B16; `scenarios.py` |
| TASK-12 | Step `prompt`: chain of prompts, `choices` deciding the path (`<step>.choice == word`), sent back to refine | both | **specs only** - no step yet |
| TASK-13 | Step `prompt` with `assistant: true`: `areas`, `software`, `web: true`, `template` (or answered by the tenant's A2A agent) | both | **specs only** - no step yet |
| TASK-14 | Step `read_documents`: whole documents of a folder (patterns ignoring case, `subfolders`), each once and again when changed, 50 per run, scans through the scan-reading model, unreadable ones kept with reason | both | walk B21 |
| TASK-15 | Step `extract`: one typed row per document (`text`, `date`, `amount`, `number`, `percent`, `choice`; `required`), unclear rows with reason, `knowledge: true` | both | walk B21 |
| TASK-16 | Step `write_file`: rows appended to a CSV (`mode`, `separator`, `decimal`, `dateFormat`, placeholders in the file name, nothing twice, added columns, formula guard) | both | walk B21 |
| TASK-17 | Step `call_tool` as a target: rows to a tool of a connected software (`each: row/all`, `with`), arguments checked against the tool's schema at save, unclear rows held back, refused call marks its row | both | **specs only** - no step yet |
| TASK-18 | Step `call_tool` as a source: rows from a reading tool's JSON array (`fields`, `key`) or documents fetched by a file tool (`file: {tool, with}`), `{lastRun:...}` | both | **specs only** - no step yet |
| TASK-19 | Step `call_agent`: rows to an A2A agent (`agent`, `skill`, `each`, `prompt`, `with`) | both | **specs only** - no step yet |
| TASK-20 | Conditions (`if`, `next` with `to` / `maxRounds`) over step counts | both | walk B16 |
| TASK-21 | Runs list (who started, state, tokens, cost), delete one run, delete all ended | both | walk B16, B17 |
| TASK-22 | Run view: graph with each step's state, rounds, tokens and cost, files and lines read, check points with evidence, documents, rows table, written files, calls; refreshed every 2 s | both | walk B16 |
| TASK-23 | Cancel a queued or running run | both | walk B16 |
| TASK-24 | Run ends DONE / UNCLEAR / FAILED / CANCELLED; mails: findings, "run failed" (always to `notify`), "findings unclear", in the tenant's look | both | walk B16 |
| TASK-25 | Start from outside: client software (`/api/v1/tasks`), MCP (`run_task`), A2A (metadata `task`) for keys the task is assigned to | both | `scenarios.py` |
| TASK-26 | Run retention 14 days (`APP_TASK_RUN_RETENTION_DAYS`), last successful run kept; runs resumed from checkpoints after a restart | both | **specs only** - no step yet |
| TASK-27 | Admin's switch off: tenant admin endpoints answer 409 `task.notAllowed`, admin can still edit, no run starts, menu entry hidden | both | walk B21 |
| TASK-28 | A store a task reads or writes cannot be deleted; two tasks never write the same file | both | **specs only** - no step yet |

## Handbook / doc sites

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| DOC-01 | Upload a static website as a ZIP for everyone or per knowledge area (replaces that scope's site; `index.html` required; skipped files shown; ZIP limit shown and refused before sending) | both | walk B18 |
| DOC-02 | Preview a website as admin / tenant admin | both | walk B18 (the frame itself in a normal browser - the Browser pane refuses it) |
| DOC-03 | Delete a website | both | walk B18 |
| DOC-04 | Users read the handbook in a sandboxed iframe; choice when several; each user sees the site for everyone and those of its areas; menu entry only with a site (tenant admin always, with "Websites verwalten") | both | walk C19 (the frame itself in a normal browser) |
| DOC-05 | Files served under a signed one-hour address, renewed five minutes before it ends | both | walk B18 |
| DOC-06 | A knowledge area with a website cannot be deleted | both | walk B18 |

## Branding

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| BRAND-01 | Installation's look (name, colour, logo from `APP_BRANDING_*`) on the admin dashboard, loaded before anything shows | both | walk A1; `check.py` |
| BRAND-02 | Tenant's own name, colour and logo (PNG/JPEG/WebP, max 512 KB, no SVG), set by the admin, fallbacks to the installation's | both | walk A6 |
| BRAND-03 | Tenant area shows the tenant's look (title, favicon, colours) | both | walk B1 |
| BRAND-04 | Neutral login page without any brand | both | walk A1 |
| BRAND-05 | Tenant's login link shown to the admin, "Neuer Link" replaces it (old one shows the neutral page) | both | walk A6 |
| BRAND-06 | Brand reaches the MCP server name, instructions, tool descriptions, A2A extended card and task mails | both | walk B12 |
| BRAND-07 | App on a phone's home screen with the installation's name and logo | both | **not run** - needs a real phone |

## Languages & look

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| UI-01 | English and German at runtime; language switch in the shell's user menu, saved to the account | both | walk C20, D2; `check.py` |
| UI-02 | Language before login and for the admin follows the browser (admin's switch holds until reload) | both | walk A13 |
| UI-03 | First WAM sign-in gets the browser's language; a waiting request keeps it | WAM | walk A9; `check.py` |
| UI-04 | User's language set by an admin in the user dialog | both | walk B3 |
| UI-05 | Backend error codes translated (`errors.backend.<code>`), dates and numbers per locale | both | walk C20 |
| UI-06 | Light / dark / automatic, chosen in the user menu, kept in the browser | both | walk A13, C20 |
| UI-07 | Responsive shell: sidebar slides in below 960px; phone chat bar with "Chats", topics menu and files of the conversation; files fold behind their count; no zoom on iPhone input | both | walk C20 |
| UI-08 | Not-found page inside each area | both | walk A13 |

## Account page

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| ACC-01 | Account overview (user, tenant, roles, tenants of the person, flags `tasksAllowed`, `mcpServer`, `answersThroughAgent`) | both | walk B19 |
| ACC-02 | Change own password with the current one (wrong current password 400) | plain | walk B19 |
| ACC-03 | Card "Claude oder Copilot verbinden": Claude Code command, Copilot `mcp.json`, claude.ai / Cowork connector fields with `<MCP-KEY>`, example question; shown only when the tenant has an MCP key | both | walk B19 |

## Monitoring

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| MON-01 | Daily report by e-mail (needs attention, system, tenants' data, questions and usage, application log), always sent, at the cron | both | walk A12 |
| MON-02 | "Tagesbericht jetzt senden" (snackbar with recipients; 409 without mail settings, 502 when the server refuses) | both | walk A12; `check.py` |
| MON-03 | Application log page: recent warnings and errors, filter errors only, search, row opens thread / logger / stack trace | both | walk A12; `check.py` |
| MON-04 | Report lines for open access requests and for a connection whose OAuth sign-in must be repeated | both | **specs only** - no step yet |

## Operations

| Id | Feature | Mode | Runs in |
|---|---|---|---|
| OPS-01 | Liveness probe polled by the frontend | both | walk A1; `check.py` |
| OPS-02 | Version | both | `check.py` |
| OPS-03 | Swagger UI with "API v1", "Users | both | walk A13; `check.py` |
| OPS-04 | Context path behind a reverse proxy under a sub-path (routes, login, Swagger, `/mcp`, `/a2a`, frontend base href, tenant login link) | both | `.github/scripts/smoke-test-jar.sh` (the jar under a context path) |
| OPS-05 | SPA deep links reload (`/login`, `/login/<key>`, `/dashboard/**`, `/app/**`) | both | walk A2; `check.py` |
| OPS-06 | Refuses to start outside dev/test without its secrets or with repository values | both | `.github/scripts/smoke-test-jar.sh` (the jar without its secrets) |
| OPS-07 | Security headers on every answer; `X-Accel-Buffering: no` on streams | both | `check.py` |
| OPS-08 | Own frontend on another origin via `APP_CORS_ORIGINS` (no credentials) | both | **specs only** - no step yet |
| OPS-09 | Compose variants: prod with Caddy, `docker-compose.proxy.yml`, embeddings image, reranker CPU / GPU, tenant sandbox, `sandbox-example/` | both | **not run** - the published compose files - an installation, not this stack |
| OPS-10 | Tenant databases migrated on every start; unfinished documents, open task runs and A2A question tasks taken up after a restart | both | **specs only** - no step yet |
