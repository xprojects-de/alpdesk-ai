# Test stack: a general chat over topic tenants

A local stack of its own (roadmap point 51): one installation behind **Caddy and
Authelia** - a real auth proxy with groups, its users and their groups in a file (`authelia/users_database.yml`), no
LDAP -, eight topic tenants with invented knowledge (`documents/`), the general chat asking them on both ways, and the
measurement with a real model. Never the developer's dev database: the stack brings its own PostgreSQL (volume of the
compose project `alpdesk-ai-general-chat-test`). A local helper.

## The topics and how the general chat asks them

| Topic (connection) | Way | The general chat uses | `setup.py full` adds |
|---|---|---|---|
| Wartung | A2A | the skills `answer`, `template:<id>` | sandbox, software, web search, template "Störungsbericht" |
| Qualitaet | MCP | `search_knowledge`, `read_document`, `ask_assistant` | sandbox, software passed through (`Pruefsoftware__list_invoices`, `__pdf_report`), web search, template "8D-Bericht" |
| Einkauf, Arbeitssicherheit, Vertrieb | MCP | `search_knowledge`, `read_document` | - |
| Personal | MCP | `ask_assistant` | - |
| Logistik, IT-Service | A2A | the skill `answer` | - |

Everyone in `AI-Allgemein` sees the eight topics as chips in the general chat - A2A and MCP side by side.

## Two modes: WAM and plain

```bash
./mode.sh            # the mode in use (wam without a .env)
./mode.sh plain      # without WAM: Caddy with TLS, no Authelia, password login, users and groups by hand
./mode.sh wam        # back: Authelia in front, people from the IDM
```

The same app, topics, sandboxes, software and fakes in both; each mode its own database and files (volumes with the
suffix `-plain`) and its own ids (`state.json`, `state-plain.json`) - switching keeps both. The first time in a mode:
`python3 setup.py base`, `full`, `knowledge`, `stores`, `models` (the provider keys entered again: the admin's page
"Globale API-Keys"), then `stores` once more for the task.
`check.py`, `scenarios.py` and `measure.py` run in either; what only one mode has is checked there. `mode.sh` starts
the images as they are - after a code change `podman compose up -d --build`.

**After every switch** (`mode.sh` takes every container down) two fakes have lost what they held in their container:
the OAuth fake its registered clients ("unbekannter Client" at its consent page) and the SFTP server its folders. Run
`python3 setup.py stores` (folders, fingerprint) and `python3 setup.py oauth` (the Ticketsystem connected anew) before
`check.py`. A database of a mode from before a change of the folded migration does not start ("Migration checksum
mismatch for migration version 17") - see "Stop" for setting it up anew.

Without WAM the people of the table below are users kept by hand (`setup.py base`), each tenant has its own login page
(`/login/<key>`, the keys in `state-plain.json`): a name in several tenants signs in there - at the common page it is
refused -, and switches with the password of its user in the other tenant. `clara` is a user of the general chat in no
group: she must not get in. In the browser the admin signs in at the login page directly (no Authelia).

## The people (all invented, password `Testpasswort-2026`)

| Authelia user | IDM groups | What it shows |
|---|---|---|
| `anna` | AI-Thema-Wartung | one group: a user in Thema Wartung, signed in at once |
| `bernd` | AI-Thema-Einkauf, AI-Allgemein | two groups: users in Thema Einkauf and the general chat - the choice, the switch |
| `clara` | - | no group of a user group: waits as an access request |
| `david` | AI-Allgemein, AI-Thema-Qualitaet | to take out of a group by hand during the test (edit the file - restart Authelia, below) |
| `betrieb` | - | the admin's identity at the proxy (the app's own admin `admin` / `Admin-Testpasswort-2026` behind it) and the general chat's first tenant admin |

## Start

```bash
cd Frontend && npm run deploy && cd ../Backend && ./gradlew copyFrontend && cd ../devtools/general-chat-test
podman compose up -d --build        # the app from the working tree (Dockerfile target from-source), a few minutes
python3 setup.py base               # eight topics, MCP keys with descriptions, connections, user groups - no model
python3 setup.py full               # Wartung and Qualitaet in full (below)
python3 setup.py knowledge          # every tenant's documents embedded by bge-m3 in the stack - no provider key
python3 setup.py stores             # Thema Wartung's file store, its knowledge source (the task: after `models`)
python3 check.py                    # sign-in, IDM accounts, choice, switch, languages, forged headers, keyed machines,
                                    # the topics on their ways and what they offer, the client software, an MCP client,
                                    # the file store and its source, the mails - no chat model; runs again and again
```

Besides the app, the topics' sandboxes, the software and the fakes, the stack runs its own **embedding model**
(container `embeddings`: Ollama with bge-m3, built by `podman compose up -d --build` from `Backend/podman/ollama` - the
published image's source - for this machine's architecture; the model is pulled once while building, about 1.2 GB), an **SFTP server** (container `sftp`, built from the file store specs' `Backend/src/test/resources/filestore/sftp`)
and **MailHog** (container `mailhog`, every mail of the app - its page `https://mail.general-chat.localhost:8443`).

In the browser: `https://ai.general-chat.localhost:8443` (Caddy's own certificate - accept it once; `*.localhost`
resolves to this machine). Sign in at Authelia as one of the people. A browser that offers no "accept" (Claude's browser
pane) needs Caddy's local CA trusted - and removed again after the test:

```bash
podman cp general-chat-test-caddy:/data/caddy/pki/authorities/local/root.crt ~/Downloads/caddy-general-chat-root.crt
security add-trusted-cert -r trustRoot -k ~/Library/Keychains/login.keychain-db ~/Downloads/caddy-general-chat-root.crt
security delete-certificate -c "Caddy Local Authority - 2026 ECC Root" ~/Library/Keychains/login.keychain-db  # afterwards
```

Machines with a key pass Caddy without Authelia, as the operator guide asks of a WAM proxy: `/mcp`, `/a2a`, the public
agent card and `/api/v1` - the app checks their key.

## Two topics in full (no model needed to set them up)

```bash
python3 setup.py full                # after base: Wartung (A2A) and Qualitaet (MCP) with everything the assistant offers
python3 check.py                     # their rows: sandbox, software, key, web search, card or passed-through tools
```

Each gets its own sandbox (containers `sandbox-wartung`, `sandbox-qualitaet` - own tokens), connected software (the
MCP test server of `devtools/mcp-test-server`, container `mcp-software`: `list_invoices`, `pdf_report` and the changing
`cancel_order`, level "lesen und ändern" for its group), the web search (the catalog's entry, a global Tavily key of
no value - the app searches at the Tavily fake of `devtools/tavily-fake`, `APP_WEB_SEARCH_ADDRESS`; every page holds
`WEB-4242`) and a template. Its MCP key gives the software and the web search. Wartung, asked over A2A, uses its
knowledge, software (reading tools only - nobody there to decide a changing call), sandbox and web search, and sends
back what it creates as files. Qualitaet passes the software's reading tools through to the general chat, whose model
calls them itself next to `ask_assistant` (which uses all of the topic's) - `cancel_order` never.

With a model (`setup.py models`, in either order): ask in the general chat as `bernd` what needs all of it, e.g.
"Welche Rechnungen sind seit 1. Oktober offen? Rechne die Summe mit Python und such im Web nach der neuen Prüffrist".
What went out to the web: `podman logs general-chat-test-tavily-fake`; the software's calls: `podman logs
general-chat-test-mcp-software`.

`full` turns an agent of `setup.py a2a` into a tool of the chat (no longer answering it) - `a2a` run afterwards makes
the agent answer every question again, and the topics are not asked.

## Machines with a key, the file store and the mails (no chat model needed)

`setup.py knowledge` gives every tenant the stack's embedding model (an entry "bge-m3 (Stack)" of the catalog, the
container `embeddings` as its server) and pushes `documents/` through the client software's API, each file with its
path as `externalId` - run again, it replaces them and embeds nothing twice. It keeps three API keys per tenant in the
state (`machines`): the client software's without an area, one that keeps no history (files with a question), and in
Thema Wartung one with the area "Leitung Instandhaltung". `check.py` then asks as the machines do, through Caddy and
without a person:

- `/api/v1`: the key's tenant and areas, a push with `externalId` new, unchanged, changed and deleted, a push into an
  area the key does not have refused, another tenant's document 404, the models and templates;
- the tenant's MCP server as a client's model uses it: `search_knowledge` finds the maintenance plan, `read_document`
  reads it, the area the key does not release (`INTERN-7731`) is never found, the template is a prompt; a key without
  the A2A door is refused at `/a2a`.

`setup.py stores` gives Thema Wartung the file store "Ablage Wartung" (the SFTP server's folder `/home/check/ablage`,
its fingerprint confirmed - a rebuilt image has a new host key: run `stores` again) and the knowledge source "Ablage
Wissen" on its folder `wissen`, synced by hand. `check.py` writes, changes, removes and restores its file on the server
(`podman exec`): synced in and found, replaced in place, deleted after the complete listing, taken in again. It checks
a task with a mistake (told with its line), sends the daily report now and finds it at MailHog, and reads the
application log.

With a chat model (`setup.py models`, then `stores` again) Thema Wartung gets the task **"Logprüfung Presse"**:
`read_files` (the ERROR lines of `logs/*.log`), `agent`, `send_mail` "Befund Presse P200" to
`instandhaltung@example.com` - assigned to the client software's key and the MCP key (a skill of its A2A card too).
`scenarios.py` runs it (below).

## A software that signs in with OAuth (no model needed)

`setup.py full` also gives Thema Wartung the connection **Ticketsystem** to the OAuth fake (`devtools/oauth-fake`, the
container `oauth-fake`): a hosted software that takes no key - its server answers 401 and names its authorization
server, so the connection is kept with **"Anmeldung nötig"**. The app reaches the fake inside the stack
(`http://oauth-fake:9100`, in `APP_AI_PLAIN_HTTP_HOSTS`), the browser its consent page through Caddy:
`https://signin.general-chat.localhost:8443`.

To see it: as the admin (`betrieb` at Authelia, then `admin`) open Mandanten → Thema Wartung → Software (or as a
tenant admin of Thema Wartung the page Software), **Anmelden** - the fake's page "Ticketsystem Musterfirma" asks for
`tickets:read tickets:write`, **Zulassen** - back on the page the chip says "Angemeldet", the tools are read. The
installation registers itself there (its address is not public - no metadata document). Its people (group
Mitarbeitende, READ) ask e.g. "Welche Tickets sind offen?" in the chat once `list_tickets` is on and a model is set up.

`check.py` clicks the same way (the consent page's button, the callback through Caddy, the page completing the sign-in
as the admin who started it), then revokes the token at the
fake (renewed once, the reload reads the tools), refuses the renewal (the reload says "needs a sign-in", the chip
"Anmeldung nötig"), refuses at the consent page, and signs in again - so the stack ends signed in. The fake's log
(`podman logs general-chat-test-oauth-fake`) shows every step without a token.

**Updating a stack set up before 2.0.0's migrations were folded** (`V17__release_1_10_0.sql` and tenant
`V8__release_1_10_0.sql` in its databases): the app then refuses to start (checksum or description mismatch of version
17, tenant databases not migrated). Either set it up anew (`podman compose down -v`, then `base`, `full`, `models`), or
bring the history to the folded files by hand - only when the database has every block the file had then.

## The general chat answered by an A2A agent (no model needed)

A tenant whose every question goes to a workflow engine behind A2A (roadmap point 54): the general chat. The
demo of `devtools/a2a-test-agent` stands in for the engine, on this machine; the app reaches it at
`host.containers.internal` (in `APP_AI_PLAIN_HTTP_HOSTS`), as a company's own engine at an internal address.

```bash
TEST_AGENT_DEMO=1 TEST_AGENT_PORT=19197 TEST_AGENT_ADDRESS=http://host.containers.internal:19197 \
  ../a2a-test-agent/start.sh &       # the "Auftragsassistent"
python3 setup.py a2a                 # the admin's A2A switch, the connection with "Beantwortet den Chat"
```

Then sign in as `bernd` or `david` (AI-Allgemein): the chat has no model picker, the agent's skills are the topics,
questions like "Wie steht Auftrag 4711?" or "Buche die Rechnung R-2026-118" are answered by the agent, which gets the
person's user name, groups and IDM groups in its metadata (the agent's log shows them). The topic tenants stay as they
are - the agent does not ask them back (the demo has no way back).

`setup.py full` makes the agent a tool of the chat again, `setup.py a2a` lets it answer again. As long as the
connection is there, `check.py` expects it reached - the demo must keep running. Stopping the demo:
`python3 setup.py agent-off` takes the connection away first.

## Conversations with a real model

```bash
python3 scenarios.py [--only a2a-file-in,mcp-pdf-in]   # writes local-data/measurements/general-chat/scenarios-<date>-<time>[-only].md
```

Thirteen conversations of the general chat as `bernd`, each of one or more steps (`SCENARIOS` in `scenarios.py`): a
question building on the one before, a vague one the topic asks back, files of `uploads/` sent with the question (a
CSV, texts, a PDF with a text layer - read by Tika on the MCP way, sent as it is over A2A), files coming back (a DOCX
from Wartung's template over A2A, a PDF from Qualitaet over MCP, changed and sent again), the passed-through software
with Python, two ways in one conversation, a web search handed to the topic. Each step checks the topics asked, a text
the answer must hold, the file that must come back and - for the web - the Tavily fake's log. Whether the model hands
the web part on depends on how it words the question for the topic.

After `setup.py knowledge` it also asks as the machines do (`MACHINES` in `scenarios.py`): `/api/v1/chat` with a file
sent by the key that keeps no history, `/api/v1/chat/stream` and a follow-up in its conversation (another key gets
404), `ask_assistant` at Qualitaet's MCP server, `SendMessage` at Wartung's A2A agent - and, after `stores` with a
model, the task: an ERROR line with a new code written into the store's log, a run started through `/api/v1/tasks`
(finding with the code, the mail at MailHog), a second run reading nothing new (no finding, no mail), and a run through
the MCP server's `run_task`.

## The measurement (with a real model)

```bash
PROVIDER=ANTHROPIC CHAT_MODEL=claude-sonnet-5-5 KEYS_FILE=<your keys file, e.g. ../../local-data/keys> python3 setup.py models
python3 setup.py stores                        # the task, now that a chat model is there
python3 measure.py                             # writes local-data/measurements/general-chat/<date>.md
```

The keys: `<PROVIDER>_KEY` or `<PROVIDER>_API_KEY` from the environment, else the line of that name in `KEYS_FILE`
(`KEY=value` lines, `export` and quotes allowed) - or entered by hand beforehand in the stack (as `betrieb`, then the
app's admin login, page "Globale API-Keys"; then leave `KEYS_FILE` out). They go into the stack only, never printed.

`setup.py models` puts the chat model into the catalog (prices from `CHAT_INPUT_PRICE` / `CHAT_OUTPUT_PRICE`, default
1 and 5 per million tokens), allows it every tenant and makes it their default. The documents are embedded by
`knowledge` with bge-m3 in the stack (the topic Wartung's `intern-personalplanung.md` into an area its MCP key does not
release). Measurements before 9 November 2026 used `text-embedding-3-small`; to compare with them, name it:
`EMBEDDING_PROVIDER=OPENAI EMBEDDING_MODEL=text-embedding-3-small EMBEDDING_DIMENSION=1536` with `models` puts it into
the catalog and runs `knowledge` with it (every tenant's documents embedded again).

`measure.py` asks every question of `questions.yml` in a new conversation of the general chat and reports per question:
the topics asked against the expected ones, whether the answer holds what it should and never `INTERN-7731` (the area
not released), a passage cited under the wrong topic, the time and the cost. `--only id1,id2` asks some, `--person
david` asks as another person in AI-Allgemein.

By hand, with the measurement: take `david` out of `AI-Thema-Qualitaet` or `AI-Allgemein` in
`authelia/users_database.yml` - his next request counts it (the topic's chip goes, the general chat says nothing of
it); switch topics off with the chips; `ask_assistant` turned on at a connection on the page Software of the general
chat (as `betrieb`, its tenant admin) for the comparison search vs. ask.

## The happy path of every feature (the skill `/alpdesk-happy-path`)

The stack is also where the happy path of every feature runs end to end, once in each mode - started by the skill
`/alpdesk-happy-path`, which builds the stack from the working tree, runs `check.py`, walks `walk/walk.md` in a
browser, runs `scenarios.py` and reports per feature:

- `walk/features.md` - every feature of the software and where its happy path runs (a step of the walk, `check.py`,
  `scenarios.py`, a spec) - or that only specs cover it. **A new feature gets its line there in the same change.**
- `walk/walk.md` - the browser walk: the admin, a tenant admin and a user through every page, in a tenant of its own
  ("Musterfirma Rundgang") made at its start and deleted at its end. Its people `walter`, `vera` and `ute` are in
  `authelia/users_database.yml`.
- `walk/files/` - the invented files the walk uploads, made by `walk/make.py` and served at
  `https://files.general-chat.localhost:8443/` (the Browser pane has no upload tool: a script of `walk.md` lays them
  into the page's upload field). `walk/store-documents.sh` puts the sample documents of `devtools/task-documents` into
  the file store for the walk's second task.
- `python3 setup.py walk` (after `models`) - what the walk needs and nobody types: the other providers' keys from
  `KEYS_FILE`, the chat model marked as reading PDFs, images and scans, an image model, a transcriber when
  `TRANSCRIBE_PROVIDER` and `TRANSCRIBE_MODEL` name one. The walk's tenant gets the sandbox `sandbox-rundgang` - the
  video sandbox when started with `WALK_SANDBOX_IMAGE=localhost/alpdesk/alpdesk-ai-sandbox:video`.

## Stop

```bash
podman compose down       # keeps the volumes; `down -v` deletes the stack's database and files
```

A fresh database (a folded migration of the version being worked on changed) without a new certificate authority of
Caddy - the one your keychain may trust stays:

```bash
podman compose down && podman volume rm alpdesk-ai-general-chat-test_pgdata alpdesk-ai-general-chat-test_files
podman compose up -d --build && python3 setup.py base && python3 setup.py full && python3 setup.py knowledge \
  && python3 setup.py stores
```

Authelia reads `authelia/users_database.yml` at its start: after a change on macOS (the file is mounted through the
Podman machine, its watcher sees nothing) `podman restart general-chat-test-authelia`.
