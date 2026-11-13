# Changelog

What changed in each release of Zirmo (up to 1.9.x called Alpdesk AI), newest first. Versions follow
[Semantic Versioning](https://semver.org): **major** - updating needs a step by hand (a setting, the compose file,
the client software's API); **minor** - new features, updating needs nothing by hand; **patch** - fixes and small
improvements. Every release is a tag `v<version>` with its images `ghcr.io/xprojects-de/alpdesk-ai:<version>` and
`-sandbox:<version>`; updating: see `Backend/podman/public/README.md`.

## [2.0.0] - unreleased

A major version, and a new name: **Alpdesk AI is now Zirmo** - a product of Alpdesk. Only the name changes: the
images, containers, volumes, the database and `/api/v1` keep theirs. This repository now holds the source code as well;
the installation's files moved into `Backend/podman/public`. Four changes need a look by hand (the details under
"Changed"):

- **An installation cloned from this repository moves once** into `Backend/podman/public` - stop, check out
  `v2.0.0`, move `.env`, `pgdata/` and `sandboxes/` there, start (installation guide, "From 1.x to 2.0.0").
- **Access needs a group**: a user who is no tenant admin signs in only through a user group. Put every user who is
  in no group yet into the new group **Standard** (page **Benutzer**) - until then they cannot sign in.
- **A user group with a WAM group takes its members from the IDM alone**: members assigned in Zirmo count no
  longer. Check your groups with a WAM group, and who set them.
- **A username is unique per tenant**, no longer per installation: a name that exists in several tenants signs in on
  the tenant's own login page only.

Nothing in `.env` or `/api/v1` needs a hand; the new settings have defaults.

### Added

- **Resources of your own software for MCP clients**: switched "Ressourcen an MCP-Clients" on the page "Software"
  (off for every software), an MCP key that gets the software directly also lists and reads its resources - files,
  logs, attachments. Their addresses start with the software's name and a dot, e.g.
  `ticketsystem.ticket://4711/log`.

- **Changing tools of your own software for MCP clients**: an MCP key may now also call the changing tools of a
  connected software that goes directly to MCP clients - e.g. create a ticket or file a document - where a tenant
  admin chose it under "Darf ändern in" in the key's dialog. Off for every key. Whether a person is asked before
  such a call is the MCP client's matter: Claude Code asks at a tool's first use and not at all in a mode or with a
  rule that allows it - see SECURITY.md before giving a key this right. The assistant behind `ask_assistant`, `/a2a` and the API keys still only read.
- **Pre-releases**: a version can now be tried before its release - tags such as `v2.0.0-rc.1` here, with their
  images, marked pre-release. A pre-release is not meant for an installation you depend on.
- **An app on the phone's home screen**: added to the home screen, Zirmo now opens without the browser's bars,
  using the whole screen, with the installation's name and logo (`APP_BRANDING_NAME`, `APP_BRANDING_LOGO`) as its
  name and icon.
- **Files a connected software hands out as a link**: when a tool of an MCP server returns a file as a link (a
  temporary download, e.g. a document from a document management system), Zirmo fetches it at once - in the
  chat and in a scheduled task's documents. For MCP clients a link to a web address goes on as a link, and a link to a
  resource of the software's own is read and embedded (see "Changed"). Up to 50 MB; an expired or refused link is named
  to the assistant instead of failing the call. Your software's key goes only to its own host, never to another
  download address.
- **Claude through Amazon Bedrock**: the model catalog takes Bedrock's chat models with an AWS account of your own -
  Claude in an EU region (e.g. Frankfurt with an `eu.` inference profile), with thinking, tools and prompt caching.
  Address of the region and an IAM user's access key per catalog entry; the README's "Own model servers" says how.
  GPT through Azure OpenAI's v1 endpoint works as an OpenAI-compatible server. `GET /api/v1/models` may name the
  provider `BEDROCK`.
- **Connected software that signs in with OAuth**: a hosted MCP server or A2A agent that accepts no fixed key is
  connected by signing in once in the browser (page "Software", button "Anmelden"); the sign-in renews itself, and
  "Anmeldung nötig" (and the daily report) says when it has to be repeated. Two new anonymous routes for it:
  `/oauth/connections/callback` and `/oauth/client-metadata.json`.
- **A2A agents as connected software**: a company's admins can connect an A2A agent (A2A 1.0) on the page "Software"
  next to MCP software - the admin allows it per company. The chat's model asks the agent through one tool; its card's
  skills show on the page, a check button reads and times it.
- **A company answering through its A2A agent**: one agent per company can answer every question - the chat, the
  client software and the MCP server -, streamed, with its context kept per conversation, a question back answered by
  the next message, new uploads sent along and who asks (user, groups, language) in the message. Its skills are the
  chips to choose from in the chat (`excludedSkillIds`, also in `/api/v1/chat`).
- **Scheduled tasks and A2A**: the step `call_agent` sends a task's rows to an agent; a `prompt` step with
  `assistant: true` is answered by the assistant with the knowledge areas, connected software, agents, web search and
  template it names.
- **The company's assistant as an A2A agent** at `/a2a`: questions answered as a message or, streamed, as a task kept
  for the key, the scheduled tasks assigned to the key started as A2A tasks, webhooks for tasks (public HTTPS only);
  a neutral public agent card and the key's extended card. A scheduled task's run sends its events for its first
  30 minutes - a client waiting for a longer run asks with `GetTask`.

- **One person in several tenants**: the same person may now have a user in several tenants of one installation (e.g.
  a topic's chat and a general chat) and switches between them in the user menu. With a WAM proxy the sign-in asks
  once which tenant (the browser remembers the choice) and the switch needs nothing more; without WAM the switch asks
  for the password of the user in the other tenant. The admin adds a person's user to another tenant from the
  tenant's users ("Benutzer aus anderem Mandanten") - without WAM it gets no password, so set one there (the dialog
  says so): until then the person cannot switch to it.
- **A description per MCP key**: what the key releases, in the tenant admin's words (at most 500 characters). Every
  client of the key gets it with the server's instructions and in the descriptions of `search_knowledge` and
  `ask_assistant` - so a general chat asking several tenants tells them apart.
- **A tenant says what it can do for its asker**: with the key's description, the server's instructions, the
  description of `ask_assistant` and the A2A card name what the assistant offers beyond the documents - the web
  search, Python and shell, the connected software - as the key allows. A general chat now hands those parts of a
  question to the topic instead of saying it has no such tool. Nothing to enter. Software is named only where a
  reading tool of it is on; a company answering through its agent offers none of these.
- **Topics to choose per conversation**: with two or more connected software reached, the chat shows a chip for each
  above the input - a click leaves one out of the conversation's questions. The choice is kept with the conversation.
  The client software does the same with `excludedConnectionIds` on `POST /api/v1/chat` and `/chat/stream`; the new
  `GET /api/v1/software` lists the key's connected software to choose from.
- **A frontend of your own for the users**: signing in, the account and the chat are now documented in `/swagger`
  next to `/api/v1` (groups "Users - sign-in" and "Users") and kept compatible like it; the README says how. A frontend
  on another origin is listed in the new setting `APP_CORS_ORIGINS` (default as before: `http://localhost:4300`).
- **A guide "A general chat over several tenants"** in the README: topic tenants and a general chat in one
  installation, the topics' MCP keys, the connections at the installation's internal address, access from the
  directory's groups.

- **Documents in scheduled tasks**: the new step `read_documents` reads whole documents of a file store's folder -
  PDF, Office files, text, and images and scanned pages through the tenant's image model -, each once and again only
  when it changed. One or several name patterns (upper and lower case alike), subfolders on request, at most 50
  documents a run (the rest with the next). A document that cannot be read is kept with its reason; the run goes on.
- **Rows out of documents**: the new task step `extract` takes the same fields out of every document a `read_documents`
  step read - text, date, amount, number, percent or a choice of words, required or not -, one question per document,
  every value checked by its type. A row missing a required field, with a value of the wrong type or from a document
  that could not be read is marked unclear with the reason; `knowledge: true` lets the model search the company
  knowledge (e.g. accounting rules). The run view shows the rows; a `send_mail` step mails the unclear ones and how many
  documents and rows the run had.
- **File stores may be written to by tasks**: a new switch per store, "Aufgaben dürfen hier schreiben", off for every
  store. SFTP, FTPS, S3 and SharePoint / OneDrive write a file whole (a temporary file renamed over it, an object or
  item replaced at once); a file open in Office is reported as locked and tried again by the next run. The store's
  account needs write rights on the folder (the README shows them).
- **Rows into a CSV**: the new task step `write_file` appends an extract step's rows to a CSV in a file store that lets
  tasks write - a row of a document already in the file is skipped, so nothing comes twice. The file's name may hold
  the run's date or a row's own date or choice (`Export/{date:yyyy}/invoices.csv` - each row in the file of its year);
  separator, decimal separator and date format are set per task; fields added later become new columns. A text that a
  spreadsheet would take for a formula is written with a leading `'`.
- **A mailbox as a file store**: a new store type IMAP, read only. Every message of the last days (365 by default) is a
  folder, its attachments and a `mail.txt` with sender, subject and text the files - so documents mailed to an address
  of the company's own are read by scheduled tasks and synced by knowledge sources. A mail forwarded as an attachment is
  unpacked, a signature's embedded logo is no file, nothing in the mailbox is changed or marked as read.
- **Rows to a connected software**: the new task step `call_tool` sends an extract step's rows to a tool of a connected
  software (MCP) - one call per row or one with all of them, the row's fields by name plus its `source` and fixed
  arguments. The arguments are checked against the tool's schema when the task is saved - a fixed argument the tool
  does not have (a typo) is named there, not sent along. Unclear rows are not sent; a
  refused call marks its row and the run ends unclear with a mail. The software is expected to recognise a row sent
  again by its `source`.
- **A connected software as a source**: a `call_tool` step without input calls a reading tool - the company's own
  software, e.g. through a small MCP server in front of it - and takes its answer, a JSON array, as rows of
  typed fields (no model needed), or fetches each listed document's file with a second tool and reads it like a
  document of a store. `{lastRun:yyyy-MM-dd}` in its arguments asks only for what came since the last successful run.
- `/api/v1/tasks/{id}/runs/{runId}` and the MCP tool `task_run` give the rows an extract step took out of documents
  (`rows`: source, values, unclear).
- **Accounts from the IDM**: with the WAM proxy's groups header configured, a person whom it lists in the IDM group of a
  user group gets a user in that tenant at the sign-in - active at once, no password, nothing to click in Zirmo.
  Someone listed in no such group waits for approval as before. With the WAM proxy on, only the admin sets a group's
  WAM group.
- **Your own sandbox image**: build a company's sandbox on ours with its own programs (a test framework, a simulator, a
  command-line tool) and write a README into it (`/opt/extras/README.md`, at most 16 KB) - the assistant gets it with
  its rules for code and uses the programs. "Sandbox prüfen" shows them per instance. An example to copy is in
  `sandbox-example/`; nothing to do when updating if you have none.

### Changed

- **Structured results of a connected software reach the assistant**: the structured data a tool returns was
  dropped; the assistant now reads it - beside the tool's text, or in its place where the tool sends no text.
- **MCP clients get what your connected software sends, unchanged**: a software passed "Direkt an MCP-Clients" now
  reaches Claude Code and the other MCP clients as it answers - long text whole (no longer cut at 100,000 characters),
  an image as an image, files with the type the software names (an HTML page stays HTML), structured results, and
  every message of a prompt. A link to a web address goes to the client as a link - Zirmo no longer fetches it for the
  client; a link to the software's own resource is still read and embedded. Beyond 8 MB an answer is refused and says
  so. The general chat over topic companies gets such web links as links too: it fetches them only where they are
  reachable from its own installation without a key of the software.
- **A tool's own statements reach MCP clients**: its title, all its annotations, its `_meta` and its output schema -
  after "Werkzeuge neu laden" of the software on the page "Software"; until then a tool is listed as before.
- **The MCP server names a key's connected software first**: its instructions now list every software passed to the
  key in one line before the software's own texts, so a client that reads only the beginning still knows them.

- **The name: Zirmo**, a product of Alpdesk. The installation's default name (`APP_BRANDING_NAME`) is now "Zirmo" -
  an installation that set its own name keeps it. The default logo is new - a Zirbe cone instead of "AD" (an
  installation's own logo stays); the colour stays. The public A2A card names Zirmo as its
  provider, the MCP server and the OAuth sign-in introduce themselves with the installation's name as before.
- **The source code is in this repository**, the installation's files in `Backend/podman/public` with their image tags
  pinned to the release. Install and update from a tag `v<version>`, not from `main`. The separate source code archive
  of a release is gone - GitHub's "Source code" download is the whole of it now.

- **The embeddings image carries the version**: `docker-compose.embeddings.yml` names
  `ghcr.io/xprojects-de/alpdesk-ai-embeddings:<version>` instead of `latest`, like the other compose files - after an
  update, `podman-compose -f docker-compose.embeddings.yml up -d` starts the release's one.
- **More room for the answers on a phone**: the chat's topics and the files of a conversation moved into the bar with
  "Chats" - the topics are switched in a menu ("Topics 6/8"), the rows above the input are gone; the list of chats
  closes with its own button, back to the open conversation.
- **Access needs a group**: a user who is no tenant admin now gets in only through a user group - with a WAM proxy and
  the directory's groups, a person taken out of every group of a company no longer gets in there. Every company has
  the group **Standard** without rights (the chat and the company knowledge, as before); users created by hand,
  access requests assigned and persons added from another company go into it. **After updating**: put every user who
  should keep chatting and is in no group yet into **Standard** (page **Benutzer**) - until then they cannot sign in
  (they are told why - after the right password at the login page, through a WAM proxy at once). Tenant admins and
  users the directory's groups give access need nothing.

- **The language follows the person**: behind a WAM proxy a user's choice of language in the menu now holds in every
  tenant the person has a user in (without WAM it stays with the user, since the same name in another tenant may be
  someone else), and someone signing in through a WAM proxy for the first time gets the browser's language (a waiting
  person's request keeps it for the admin's assignment) instead of English.
- **MCP keys open doors**: a key opens `/mcp`, `/a2a` or both (existing keys: `/mcp`, as before).
- **New settings** `APP_A2A_TIMEOUT`, `APP_A2A_MAX_ANSWER_CHARS`, `APP_A2A_TASK_RETENTION_HOURS` - in both compose files;
  nothing to do when updating.

- **Sizing PostgreSQL**: the compose files carry the database's limits commented out at the `postgres` service
  (`max_connections`, `shared_buffers`, `maintenance_work_mem`), and the README's new section "Sizing PostgreSQL" says
  when to raise them - from about 20 tenants or large knowledge bases. The database may also run on a server of its
  own: `DATASOURCES_DEFAULT_URL` and `DATASOURCES_DEFAULT_USERNAME` in `.env` override the pod's (the README says how,
  and how to move there later).
- **A user group with a WAM group takes its members from the IDM alone**: everyone the WAM proxy lists in that IDM
  group is a member - also without being assigned in Zirmo -, nobody else. Before, a member had to be assigned
  here and listed in the IDM; members assigned to such a group count no longer. Check your groups with a WAM group
  after the update - also who set their WAM group: a tenant admin could set one before, and with the groups header
  configured it now gives every person in that IDM group a user in the tenant at the sign-in.
- **A username is unique per tenant**, no longer per installation. A name that exists in several tenants signs in with
  its password on the tenant's own login page only; the neutral login page refuses it like a wrong password and says
  so. A tenant's own login page now signs in that tenant's users only - a user of another tenant is refused there
  (the neutral page takes everyone, as before).
- **A connected software's tool result starts with its name** (`[Software] ...`), and the assistant is told that a
  document's id holds only within the software that gave it - for a chat asking several tenants' MCP servers.
- **Documents are read with Apache Tika 4.2** (before 3.3): the same file types are read, but the text a document gives
  may differ slightly (Markdown is still read as written, headings and paragraphs kept). Documents already in the
  knowledge stay as they are - "Embed again" reads them anew. Libraries updated besides: Micronaut 5.2.6, Angular 22.2.

### Fixed

- **A scheduled task's assistant prompt finds the knowledge again.** A `prompt` step with `assistant: true` searched
  the company knowledge and its areas but never got a passage back - it answered without the documents.
- **A connected software moved to another address** no longer keeps what was chosen for the old one: its resources
  stop going to MCP clients and its MCP keys read only, until a tenant admin chooses again.
- **An A2A agent whose address answers with something that is no agent card** is now refused with that reason when
  it is created, reloaded or checked, instead of an unexpected error.
- **Scheduled tasks**: a prompt of the assistant that uses an A2A agent now fails its run once the admin no longer
  allows the company A2A agents, instead of answering without the agent. And a document a software hands out as one
  of its own resources is asked for again by the next run when the software did not answer while reading it,
  instead of being filed as unreadable.
- SharePoint / OneDrive stores sign in with Microsoft's sign-in library 1.26.1, which fixes how it picks a token from
  its cache.
- **A document uploaded right after a restart no longer waits behind every company's index check**: on an
  installation with many companies the first uploads after a start stayed "pending" until all of them were checked.
- **The embeddings image for ARM**: announced with 1.8.0, `ghcr.io/xprojects-de/alpdesk-ai-embeddings:latest` was
  still published for `linux/amd64` only - on an ARM server it ran emulated, minutes per document. It now carries
  `linux/arm64` as well; pull it again.
- **A scheduled task no longer loses documents silently**: scans waiting for an image model no longer hold back newer
  documents of a `read_documents` step; a connected software's document whose download link fails for now, or a scan
  without an image model, fails the run instead of being skipped for good.
- **A task's assistant prompt step fails when a knowledge area, connected software or chat template it names was
  deleted** instead of answering without it; `input: []` is refused when the task is checked.
- **A knowledge source no longer deletes documents after a listing it got only in part**: an FTPS folder that may be
  entered but not read, an S3 listing announcing more without a way to ask for it, or a SharePoint page answered by a
  proxy now fail the run (nothing deleted) instead of counting as empty. A task's `read_files` step on such an FTPS
  folder now reports it instead of finding no files.
- **Files a knowledge source cannot read no longer hold up its scheduled runs**: they are tried again after the new
  and changed files, so 100 locked files no longer keep every other new file out of the knowledge until a run by hand.
  They are listed among the source's files with the reason.
- A cancel that came just after a source's run ended no longer stopped its next run.
- **A scheduled task's mask `after: 'password:'` now masks `password: s3cret`**: a space between the text and the
  value left the value as it was, so it reached the model and the mail. Check the masks of your tasks reading logs.
- **`{lastRun}` of a task running less often than the run retention** (14 days by default) is kept: the retention no
  longer deletes a task's last successful run, so a monthly task's source is no longer asked for everything again.
- **A broken image in a folder a task reads** is reported once instead of failing every run of the task.
- **A scan read once the admin assigns an image model now reaches the CSV**: its row was skipped as already written,
  because the unclear row from before had the same source.
- **A CSV a spreadsheet saved again as Windows-1252** is refused with its reason instead of having its umlauts
  replaced for good; save it as CSV UTF-8.
- An extremely large number in a document no longer exhausts the memory.
- **A scheduled task no longer waits for an unreachable software row by row**: when the connected software as a whole
  fails (out of reach, no answer in time, the key refused), the rows still to go are marked with that reason at once
  instead of each waiting for the timeout - one such run could hold the installation's task runs for hours.
- **A source tool's answer that is too long** for a call now says so, instead of "no JSON array of objects".
- **A connected software listing a tool or prompt name twice** is refused with a clear reason when its tools are read,
  instead of a conflict error.
- **Changing a connected software at the same moment as its tools are reloaded** no longer writes back what was read
  before the reload - e.g. a tool the software marked as changing stays marked.
- Many questions through the MCP server at once no longer hold up the sign-in of every user, API key and MCP key.
- A knowledge source's listing counts folders and files its pattern leaves out towards its limit of 20 000 entries,
  and "Abbrechen" stops it at once - a folder with very many other files kept the sources of all tenants waiting.
- Switching to another tenant ends uploads and deletions still waiting, and another open tab of the same browser
  starts anew when the session changes - neither goes on with the other tenant's sign-in.
- A video answer looks at no more than 60 images: with more, a provider refused the whole answer.
- A chat upload that runs into a timeout (several long recordings to transcribe) shows the files stored after all, so
  a second try does not upload them twice.
- A topic switched while an answer comes stays switched; a connected software's error on a decided call is shown
  without the software's name in front.
- Two templates whose names make the same slash command (e.g. "Code-Review" and "code review"): the older one keeps
  the command, the newer one gets its number - a template added later no longer takes over an existing command.
- **An image a connected software returns that cannot be read** (a broken PNG) is offered as a download instead of
  an image without preview, and no longer ends an answer that asks the image model to change it.
- The assistant is no longer told to hand out the files of its code where it cannot (the client software without
  history), which cost it a round.
- A changing call of the connected software that an answer asks for after you already asked the next question
  in that conversation no longer waits for a decision: it is shown as expired, like every call a new question
  finds waiting.
- A company answering through its A2A agent: an agent that returns nothing is now an error ("gave no answer")
  for the client software without history, the MCP server and A2A clients too - no longer an empty answer.

### Security

- **Wrong passwords lock a user's account for a while.** After 5 wrong passwords in a row a user's account takes no
  password for 15 minutes - at the login and at the switch to another company - and the login page shows its usual
  message. A password an admin sets lifts it; the admin's own account is never locked. Optional settings
  `APP_LOGIN_MAX_ATTEMPTS` and `APP_LOGIN_LOCK_MINUTES` (in both compose files).
- **Without a WAM proxy, the tenant switch goes by the person, no longer by the username.** A user of the same name in
  another tenant - one another company's admin may create under any name - was listed in the account's tenants with
  its company's name, and the switch asked for its password. Now only the users the admin added with "Benutzer aus
  anderem Mandanten" count as one person's. Installations of the release candidate without WAM: a person's users in
  several tenants from before this release - created by name or added that way - are no longer offered for the
  switch; delete all but one and add them again from it.
- A spreadsheet that unpacks into an excessive size is refused: a part of an Office file that unpacks into more than
  200 MB is refused before it is read, so is an Office file whose parts unpack into more than 500 MB together or that
  has more than 10,000 parts - a file made of many parts just below the limit kept the server busy for minutes.
- Reading certain HTML mails no longer slows down the knowledge sources.
- **A connected software's address reaches its own host only.** An address whose path began with `//` followed by
  another host (`https://software.example.com//other.example.com/mcp`) sent the requests - and the connection's key -
  to that other host, past the check for internal addresses. Such an address is refused now when it is saved (a model
  server's too), and a connection saved with one before no longer reaches the other host: check its address.
- **A sign-in to connected software belongs to its address.** After a connection's address was changed and signed in
  anew, a call still open to the old address could be given the new sign-in's token. A call now gets the token of
  the address it was started for only.
- A scheduled task with many large documents reads their text one document at a time, no longer all at once.
- The sandbox's health check can no longer be read or changed by a session's code. Update every company's sandbox to
  this release's image.
- A connected A2A agent's status lines are cut at 500 characters, and an agent of a company the admin took A2A away
  from is no longer told when a waiting conversation is deleted.
- SECURITY.md and the privacy notes now name the web search of a scheduled task (`web: true`), the start of a task
  through the A2A server, a refused further message that stays in an A2A task until it is deleted, what a link named
  by a connected software may reach, what a handbook's website may show, and a document a task cannot file.
- `.env.example` suggests a fixed heap for the app and a clean restart when it runs out (`JAVA_TOOL_OPTIONS`).
- **The sandbox's health check could run code a chat session had left behind**, outside the session's confinement.
  Update every tenant's sandbox to this release's image.
- **A connected MCP software that answers only** (not switched "Direkt an MCP-Clients") no longer hands the files its
  tools return to an MCP client through `ask_assistant` or to an A2A client - its raw results stay in the
  installation, as the switch says. Switch it on where a client needs the files.
- **Only the admin sets a user group's WAM group, also while WAM is off** - one a tenant admin set before would give
  everyone in that IDM group a user in the tenant the day WAM is turned on. Before turning WAM on, check the WAM groups
  of your tenants' groups.
- A scheduled task's source no longer calls a tool of the connected software that became a changing one after the
  task was saved (a tenant admin's correction or a software update) - the run fails and says so.
- A user without a password (signed in through WAM before, added from another tenant) is refused at the login in the
  time a wrong password takes - the answer no longer tells that the name exists.
- A failed task run's log line names its error code only, no longer what the connected software answered.
- The sandbox refuses a call or a file that arrives while its session ends, instead of running it after the cleanup.
- CORS no longer allows credentials - the app authenticates with a bearer token only, so no page of an allowed origin
  can have the browser send a WAM proxy's session cookie along.
- An update whose change of a company's database fails on that company's data is logged without the values of the
  row it failed on - like every other database error.
- The privacy notes now say that a scheduled task's last successful run stays beyond the 14 days (the next run
  reads on from it), until a later run takes its place or a tenant admin deletes it.
- **Micronaut 5.2.6** brings the framework's security fixes (access rules checked against the path as routed, also
  below a context path; upload limits; server-sent events). Behind a sub-path, a request outside it now answers 401
  instead of 404 - nothing is served there, as before.

## [1.9.0] - 2026-11-02

### Added

- **Knowledge sources**: a folder of a file store (SFTP, FTPS, S3, SharePoint / OneDrive) is read into the company knowledge
  or an area, subfolders included, and kept up to date: changed files are embedded again, deleted ones removed. Saving
  a source only lists its folder and shows what is open; "Jetzt synchronisieren" reads everything, a schedule (hourly to
  daily) at most 100 files or 250 MB per run. Files of a type that is not read are skipped by their extension, refused
  files are listed with the reason. Removing a source deletes its documents or keeps them as uploaded ones; a file
  store a source reads keeps its server and folder until the source is changed. On the page
  "Firmenwissen" and the admin's tab of a tenant. The folder is typed or chosen by clicking through the store's folders;
  a source on the same folder as another one, above or below it, is pointed out.
- Log files (`.log`) are read as text - as documents and as files in the chat.
- **S3 as a file store**: a bucket of Amazon S3 or of an S3-compatible store (an own endpoint) - optionally a folder
  in it - read with an access key, for scheduled tasks and knowledge sources alike. Its check reads the folder at once;
  plain `http://` only to a host in `APP_FILE_STORE_INTERNAL_HOSTS`.
- **SharePoint and OneDrive as a file store**: a document library of a SharePoint site or a OneDrive - optionally a
  folder in it - read through Microsoft Graph by an app registration of the company's Entra ID (`Sites.Selected`,
  client secret), for scheduled tasks and knowledge sources alike. An expired client secret is named as such.
- **Reranking, switchable per tenant**: a reranker reorders what the knowledge search finds by how well each passage
  answers the question - in the chat, `/api/v1/chat`, the MCP server and scheduled tasks. New model kind "Reranker" in
  the catalog on a server speaking the `/rerank` API of Text Embeddings Inference (TEI); assigned to a tenant, switched
  on and off on the page "Firmenwissen" - off unless chosen, nothing is embedded again. If the reranker fails or takes
  longer than 30 seconds, the search keeps its own order. On a CPU it takes about a second per passage, so it reorders
  a search's best 10 candidates - the new optional setting `APP_SEARCH_RERANK_CANDIDATES` (2 to 40, default 10; 20 with
  a GPU).
- **A local reranker**: `docker-compose.reranker.yml` (CPU) and `docker-compose.reranker-gpu.yml` (NVIDIA GPU) start
  Hugging Face's Text Embeddings Inference with `bge-reranker-v2-m3` (multilingual) in the app's network - one reranker
  serves every tenant; the GPU file is not tried on a GPU yet. See the README, "Reranking with a local reranker".

- **What a scheduled task's run cost**: the run list and the run view show the cost of every run and of each of its
  steps next to the tokens, as the usage recorded it.
- **Price tiers and cache prices in the model catalog**: a model may bill a request with a long prompt at higher prices
  (Claude Haiku 5.5 above 100 000 tokens) and a cached token at its own price - both optional per model, entered in the
  model dialog under "Preisstufe und Cache".
- **Prompt caching with Claude**: the rounds of an answer - in the chat, `/api/v1/chat`, the MCP server's
  `ask_assistant` and scheduled tasks - let Anthropic cache what they share; the model sees exactly the same, an
  answer with several rounds costs about 40% less. OpenAI requests carry a cache key so their rounds reach the same cache.

### Changed

- `GET /api/v1/documents` lists the documents of knowledge sources too: origin `SOURCE`, with the new field `sourceId`
  (null for the others).
- Documents of several tenants are embedded in turn: one tenant's large import no longer holds up another tenant's
  uploads. Within a tenant the order stays; the page "Firmenwissen" says how many documents wait.
- The task format's reference says that a `verify` step's `model` may be a cheaper one than the checking step's.
- A task naming a file store that is not ready yet now says so in general terms ("check it on the page File stores
  first") instead of speaking of a fingerprint.
- The usage pages show the tokens read from and written to a provider's cache; they count into the tokens and are
  priced at the model's cache prices.

### Fixed

- Tokens that OpenAI and Gemini serve from their cache were counted at the full input price - now at the cache price
  the catalog gives (or still the input price where none is entered).
- Gemini's thinking tokens, reported apart from the answer, were missing from the usage - they count as output now.
- A scheduled task's step that failed after calling its model showed no tokens - it now keeps the tokens and cost of
  its calls, which were in the usage all along.
- A rare failure while connecting to an SFTP store (a race in the key exchange) is fixed by the update of the SSH
  library; an answer of an OpenAI-compatible server that fails midway is now reported as a failure instead of ending
  the answer silently.
- A scheduled task with `firstRun: end` whose first run found no file yet started at the end again on its next run -
  the lines written in between were never read. The file that appears later is now read from its start.
- The run view of a task no longer stays at "running" on a slow connection: it waits for an answer instead of asking
  again every two seconds and cancelling the request still on its way.
- An answer stopped because the model ran away in blanks or repeated its reasoning was recorded with no tokens and no
  cost - its input is now estimated from the text sent and billed.
- A restart while an uploaded audio file was being transcribed left the file in the storage for good, beyond its
  retention time - the transcript is now written before anything is stored.

### Security

- A folder of an SFTP, FTPS or S3 store with more than 50 000 entries is refused while it is listed (FTPS: a listing
  is cut off after 32 MB): a server that answers without end can no longer fill the memory of the installation.
- Pointing a knowledge source at another area, folder, store or pattern now holds the source: a run can no longer
  start in the moment of the change and file new documents under the old area.

## [1.8.0] - 2026-10-25

### Added

- **Images for ARM**: the app, the sandboxes and the embeddings image are published for `linux/amd64` and `linux/arm64`
  under the same tags, so an installation on an ARM server, a Raspberry Pi 5 (64-bit OS, 8 GB) or an Apple Silicon Mac
  pulls the right image without a change to the compose files.
- **Behind an own reverse proxy**: `docker-compose.proxy.yml` runs the stack without Caddy, the app's port published on
  `127.0.0.1:19100` for a reverse proxy on the same machine - a NAS's reverse proxy, an nginx or Traefik
  serving other applications. The app now sends `X-Robots-Tag: noindex, nofollow` itself (the Caddyfile did) and tells
  nginx-based proxies not to buffer the chat's streamed answers (`X-Accel-Buffering: no`).
- **claude.ai, Claude Desktop and Cowork**: the MCP key card (and the card on the page "Account") shows what to enter
  for a custom connector - the address, "No sign-in", the request header `authorization` and its value
  `Bearer <MCP-KEY>` to copy. The company's assistant then answers in Claude's apps as it does in Claude Code.
- **The MCP server searches and reads**: two new tools, `search_knowledge` (the passages that answer a query, with
  their document's name and id) and `read_document` (a document found, whole or in parts), let Claude or another MCP
  client read the company knowledge itself and answer with its own model - only the query's embedding is used, no
  second chat model. They find what `ask_assistant` finds: the company knowledge and the MCP key's areas.
- **Several MCP keys**: a tenant has as many MCP keys as it needs, each with a name, its own knowledge areas and its own
  web search - e.g. one for development, one for the management - created, renewed and removed on the MCP key card.
  The key a tenant has keeps working after the update, named "Standard" with the areas and web search set so far.
- **Connected software straight to Claude Code and Cowork**: a connected software (e.g. a module running in its own
  container) switched "Direkt an MCP-Clients" on the page "Software" offers its reading tools and prompts to the MCP keys
  given it, named `<software>__<tool>` (a name too long is cut and ends in the tool's number, which it keeps) - the
  client's model calls them itself, its address and token stay with Alpdesk AI. The software's own instructions go to the client after the assistant's and into the assistant's prompt. Off by
  default: a software not switched on is used through `ask_assistant` only, its raw results never reach the client.

### Changed

- **The MCP key chooses its software**: which connected software an MCP key may use (for `ask_assistant` and
  directly) is chosen per key on the MCP key card ("Software"), no longer per connection for all keys - the switch "für
  den MCP-Server" on the page "Software" is gone. After the update every key has the software opened to the MCP server
  so far. Admin API: `PUT .../mcp-connections/{id}/mcp-server` is replaced by `.../pass-through`, `connectionIds` on
  the MCP keys.
- **The MCP key chooses its tasks**: which scheduled tasks Claude Code or Cowork may list, start and read through an
  MCP key is chosen per key on the MCP key card ("Aufgaben"), like for the API keys - no longer with the switch "Für den
  MCP-Server freigegeben" in the task editor for all keys; the editor shows which keys may start the task. After the
  update every key has the tasks opened to the MCP server so far. Admin API: `openToMcp` is gone from the tasks,
  `taskIds` on the MCP keys.
- **API keys choose their software too**: which connected software the client software's API key may use through the
  chat is chosen per key on the API key card ("Software"), no longer per connection for all keys - the row "API-Keys"
  on the page "Software" is gone. After the update every key has the software opened to it so far. Admin API:
  `PUT .../mcp-connections/{id}/client-software` is removed, `connectionIds` on the API keys.
- **Keys in a dialog**: API keys and MCP keys are created and changed in a dialog with all their rights - name, areas,
  software, tasks, web search and, for an API key, the history - each with a sentence on what it means; the tables show
  a key's rights in short. Tasks can now be given to a key when it is created.
- **A group's software in its dialog**: the user group dialog sets the level for each connected software ("Kein
  Zugriff", "Nur lesen", "Lesen und ändern") together with members, areas and web search, saved in one step; the groups'
  table shows each group's software. The software's access list on the page "Software" now only shows the groups'
  levels - they are changed in the group's dialog. Admin API: `levels` on the groups; `PUT
  .../mcp-connections/{id}/access/groups/{groupId}` is removed.

### Fixed

- **A scanned page the PDF library cannot draw** (a broken colour space or image) no longer fails the whole document:
  the page keeps its own text, the other pages are read and the document becomes ready - before, every retry paid for
  the pages read before it and failed again. A chat file with such a page is refused as unreadable instead of an
  internal error.
- **Web search after a failed document search**: when searching the company's documents failed (e.g. the embedding
  provider refusing its key), the web search was allowed all the same and could become the answer's only source - it
  now waits for a document search that came back.
- **Chat, connection lost**: an answer whose connection a proxy or the network tore off showed "could not be
  answered" although the backend still stored it, so asking again doubled the exchange - it now says the answer may
  still be stored and to open the chat again first.
- **Scheduled tasks**: the run view showed a failed run's message without its values in German ("Der Schritt  hat
  neue Zeilen gelesen, mindestens  erwartet") - a failed step now keeps the values of its error, and the message is
  shown whole.
- **Sandbox**: code that left a named pipe where a file was expected (and had it shown or handed out) blocked the
  sandbox until its restart - it is now refused like any other non-file.
- **MCP server**: an unexpected failure in one of its tools (the database briefly out of reach, a provider's key no
  longer readable) ended the MCP client's session with an error and left nothing in the log - it is now an answer the
  client's model reads ("try again later"), and the log names it.
- **Connected software after a new `APP_SECRET_KEY`**: a connection's stored key that can no longer be read broke off
  the chat's answer and made "Werkzeuge neu laden" fail with an unexpected error - both now say to enter the key again
  in the connection.
- **User groups**: two messages of the group dialog (an unknown connected software, a missing level) were shown in
  English to German users.
- **Scheduled tasks, rotated logs**: a log rotated and grown past where the last run stopped before the next run was
  read from the old position - its beginning was never checked, and the line numbers in the mail were wrong. It is now
  recognised as rotated and read from its start.
- **Scheduled tasks, a run broken off**: a run that broke off outside its steps (the tenant's database briefly out of
  reach at its end) blocked every start of its task until the server's next start, and cancelling it did nothing -
  cancelling now ends it at once.
- **Scheduled tasks, files of one name**: a task reading two folders or file stores with a file of the same name (two
  servers' `app.log`) mixed their lines - the run's files of such a task are now named with their store and folder
  (`Webserver:logs/app.log`).
- **Scheduled tasks, editor**: what was typed while a save went out was replaced by the saved text - it now stays as an
  unsaved change.
- **File stores**: FTPS read file names with umlauts wrong (a pattern like `Störung-*.log` never matched); names are
  now read in UTF-8. An SFTP server hanging with its connection open held a task for 10 minutes per request - now
  60 seconds.

### Security

- **Server log**: a model server's error answer to a call asked again (HTTP 5xx, 429) no longer reaches the log, the
  page "Application log" or the daily report - such an answer may repeat the request, a document's text.
- **FTPS file stores**: a host whose name server answers with another address while connecting is refused before its
  certificate is shown or the password is sent - the address checked against the internal networks is the one used.
- **Connected software**: when the host of a connection's address cannot be resolved at a call, the message to the
  chat, its user and an MCP client names the connection only, no longer the host.

## [1.7.1] - 2026-10-24

### Added

- **Scheduled tasks: `firstRun: end`** for a `read_files` step - the task's first run reads nothing and only what is
  written afterwards counts, instead of the files' whole past. For logs with a long history: their first run no longer
  runs into the size limit and reports on a cut file. Files that appear later (tomorrow's log) are read from their
  start as before; without the setting nothing changes.
- **Scheduled tasks: `minLines`** for a `read_files` step - a file that must keep growing (a cron's heartbeat) and
  read fewer new lines fails the run, and the notify list gets a mail why. Without it a stopped cron left every file
  quiet, and a quiet run reported nothing.
- **Scheduled tasks: the model is told the time it asks** - so it can tell "nothing since 03:00" from "nothing in the
  last minutes".
- **Scheduled tasks: `mask`** for a `read_files` step - passwords, tokens or personal data in the files are replaced
  with `***` before a line is kept, so they reach neither the model's provider nor the mail nor the stored run. Simply
  `- after: 'password="'` (the value after a text) or `- regex: ...` (every match); before, `exclude` could only leave
  the whole line out.
- **Scheduled tasks: runs are deleted 14 days after they ended** (`APP_TASK_RUN_RETENTION_DAYS`, set empty to keep
  them as before) - with the lines their steps read. A task reading files twice a day kept every line it ever read.
  The read positions stay, so the next run goes on where it stopped. Older runs go with the first cleanup after the
  update.
- **Scheduled tasks: `severity: report`** for a check point that is no question but something to hand on - "list
  every other error", a daily overview. Its note goes into the mail whenever there is anything to report, marked as a
  report; the verification leaves it as it is. `verify.reports` counts them, so `if: verify.errors > 0 ||
  verify.reports > 0` mails a report on its own. The client software's API and the MCP server give it as `REPORT`.

### Changed

- **A template needs no question**: with a template chosen, Enter (or Send) with an empty input starts it - the
  template's name becomes the question in the history. The client software may leave `message` out of
  `POST /api/v1/chat` and `/api/v1/chat/stream` when it sends a `templateId`.
- **The chat's files above the input fold behind their count** ("Files of this chat (n)") on a phone and from five
  files on, and scroll within themselves when opened - the stills of a video analysis no longer fill the screen.
- **On an iPhone the page no longer zooms in** when a text field is tapped (the chat's input above all) - it had to be
  scrolled sideways afterwards.
- **Video analysis**: the model is told to hand out the stills of a movement also when its measurement is uncertain,
  with the reservation written into them - before, a model that doubted its values left the images out altogether.

### Fixed

- **Code execution on Ubuntu 24.04 with Podman**: the host's AppArmor profiles denied every signal inside the sandbox
  container, so after the first answer with code the sandbox could not end the session and took no further one - the
  chat said "the code execution is busy" and "Sandbox prüfen" showed "busy" for ever. The sandbox now runs without an
  AppArmor profile (`apparmor=unconfined` in `docker-compose.tenant-sandbox.yml` - start every instance again with
  it, as after any update; its isolation rests on no root, no capabilities, read-only, seccomp and the internal
  network, see SECURITY.md). A sandbox that cannot end its sessions says so at its start in its log, "Sandbox prüfen"
  shows "needs a restart", and the chat's model is told the tools are not available instead of "busy".
- **Scheduled tasks reading one file in two steps** (e.g. split by `include`): a line written between the two steps
  could be skipped by one of them. Now the step that read more reads it again in the next run; nothing is lost.
- **Scheduled tasks**: the model now sees which step each file comes from, and a step without new lines is named as
  such. Before, instructions naming a step could not be followed, and a step without new lines made its check points
  "unclear" - an unclear mail on almost every run of a task whose step usually reads nothing.
- **Scheduled tasks**: a step cut at its size limit now tells the model so - before, what came after the cut could be
  reported as missing (e.g. "no later success") although it was only not read.
- **FTPS file stores**: reading a file only partly - larger than the step's limit, or still growing like an active
  log - closed the session on some servers (ProFTPD), and the next file failed with "Connection closed without
  indication". Such a read now opens a new connection for the next file.
- **Scheduled tasks: a file pattern with many `*`** (e.g. `***x.log`) could take minutes per file name it did not
  match - the run hung in its `read_files` step, could not be cancelled, and two such tasks held up every other run of
  the installation. Patterns are now matched without a regular expression and answer at once.
- **Scheduled tasks**: a run whose verification failed (or that broke off before it) no longer hands the client
  software and the MCP server the checking step's findings, which were never verified - the run's error says what
  happened.
- **Scheduled tasks with a model that cannot call tools**: a file too large for the question was cut and the model
  only asked to mention it - it could still call everything ok, and the cut lines were never judged. The model is now
  told nobody judges the rest, so what depends on it is to be called unclear.
- **Chat: a file the connected software returns for a confirmed change is kept** when the assistant's answer after it
  fails (e.g. the provider's rate limit). The change had been made and cannot run again; its file was deleted with the
  failed answer. It now shows below the answer that asked for the decision.
- **Chat: a stopped answer that fails afterwards now says so** - before, its question simply disappeared. A stopped
  decision that fails shows its call as it is now.
- **Chat: a lost connection** now says the answer may still be stored and to open the chat again before asking again -
  asking at once answered the question twice.
- **Chat: searching a large file sent with the question** no longer skips a long matching line that did not fit the
  result while showing shorter ones after it; "go on with from_line" now leads back to it.

## [1.7.0] - 2026-10-23

### Added

- **File stores**: tenant admins (and the admin) enter the tenant's SFTP and FTPS servers on the new page
  "Dateiablagen" and check them; the password goes out only after they confirmed the server's fingerprint. A file
  store in the internal network needs its host in `APP_FILE_STORE_INTERNAL_HOSTS`; FTPS servers of the company's own
  certificate authorities are trusted with `USE_SYSTEM_CA_CERTS`.
- **Scheduled tasks** (switched on per tenant by the admin in the tenant's master data): a task reads the new lines of
  files on a file store, lets the tenant's chat model check them against check points, verifies the findings and
  mails them - only when something is wrong, or when a run failed or stayed unclear. It runs on a schedule
  (`every: 5h` or a cron expression, at most every 15 minutes), by hand, from the client software or through the MCP
  server. Tenant admins keep them on the new page "Aufgaben": the task written in YAML with its steps drawn beside it,
  the format's reference, the runs with what each step found. **Chains of prompts**: a `prompt` step answers freely
  and hands its answer to the next one; with `choices` the model picks a word that decides where the run goes, e.g.
  back to refine the answer. **Draft with AI**: describe a task (or a change) in your own words, the tenant's chat
  model writes it and the check corrects it - you read and save it.
- `/api/v1/tasks`: the client software lists the tasks assigned to its API key, starts a run (`202` with the run's id)
  and reads what it found and the answers it hands on. A tenant admin assigns the tasks on the key (`taskIds`).
- MCP server: `list_tasks`, `run_task` (waits up to a minute for the result) and `task_run` for the tasks opened to it.
- `APP_DOC_SITE_MAX_ZIP_SIZE`: the largest ZIP of a handbook's website (default `100MB`, at most `512MB`).
- **Web search in the chat** (Tavily): for what the company's documents do not answer - current facts, a law, a
  price. The admin enters it in the model catalog (kind "Websuche", priced per 1,000 searches) and assigns it to a
  tenant like a model; the Tavily key goes under API keys. Tenant admins turn it on per user group, per API key and
  for the MCP server - off by default. The documents always come first, at most three searches per answer, only the
  search query goes out; the pages found are shown as links under the answer and in the `sources` of `/api/v1/chat`.
  With "documents only" the assistant then answers from the documents and the web pages, not from general knowledge.

### Changed

- The videos of one chat conversation may have at most the video size (`APP_CHAT_VIDEO_MAX_SIZE`, default 500 MB)
  together; a second large video goes into a new conversation. Before, a second one filled the code execution's space
  and stopped every analysis in that conversation until the first expired.
- The handbook's upload dialog shows the ZIP's limit and refuses a larger ZIP at once, instead of after uploading it.
- The chat on a phone leaves more room for the answers: the input starts with one line and a short placeholder, the
  model (its name only) and template choice sit in one row, and the note below the input shows only on an empty chat.

### Fixed

- The tenant databases are reached with the same parameters as the management database: `sslmode`, a certificate
  or any other parameter in `DATASOURCES_DEFAULT_URL` now applies to every tenant's connection too.
- "Software": a switch or level whose change the backend refuses (the server out of reach, a tool no longer
  offered) shows the stored value again instead of the clicked one.
- The run view of a scheduled task keeps refreshing after a single failed request (a timeout, "try again"); before,
  it stayed at "running" until the page was reloaded.
- "New task" always starts from the template - a text imported or duplicated earlier and left unsaved no longer
  reappears; an imported text counts as unsaved, so leaving the editor asks first.
- A large video in the chat no longer fails over a slow connection after 6 minutes: the timeout counts from the
  upload's last progress, no longer from its start.
- A scanned PDF with one page holding an image too large to render no longer fails as a whole after its other pages
  were read (and paid): that page keeps its own text, the document becomes ready.
- A PDF that breaks while its pages are checked for scans is refused (or skipped in a ZIP archive) instead of ending
  the whole archive's import with an error.
- A connected software that refuses a prompt's values or a tool call with HTTP 400 now always gets the answer "could
  not carry out the request"; before, the request could end with an internal error instead.
- On a phone the page fits the visible screen: before, the browser's toolbars pushed the top bar out of sight.
- On the page "Firmenwissen" the upload buttons wait until the knowledge areas are loaded: before, a file uploaded in
  that moment (or after the areas failed to load) went into the company knowledge without asking which area.
- A document whose embedding broke off without a state written is now an error in the application log and the daily
  report.

### Security

- Sandbox (Python and shell in the chat): under Podman a file a session wrote into `/run/lock` was left for the next
  session of the same instance - another user of the tenant. Every session's end now empties it too; update the
  sandbox image.
- The address of a handbook's website without its trailing slash no longer redirects a made-up token to another site
  (an open redirect on the installation's domain).
- A failed web search logs only the kind of failure and Tavily's HTTP status - no longer Tavily's answer, which could
  repeat the search query.
- Micronaut 5.2.3: an upload answered before all of its files arrived no longer leaks memory, and neither does a
  broken or cancelled request body; a request with a malformed chunk is no longer taken as an empty one.
- The PDF files the chat creates are rendered with PDFBox 3.0.8, the same version that reads uploaded PDFs.

## [1.6.0] - 2026-10-21

**Before updating**: chat files are now deleted 14 days after they were stored - also the existing ones, within an
hour of the update. To keep them, set `APP_CHAT_FILE_RETENTION_DAYS=` (empty) in `.env` before the update.

### Added

- Code execution: the assistant can look at images its code made (for example frames it cut out of a video with
  ffmpeg) and hand files from the code execution to the user - a still with marks drawn in, a video clip, a table.
  They are stored with the answer; an image is shown below it, several images (e.g. the stills of a movement, up to 30
  per answer) as one sequence with a slider that steps through them or plays them like a short clip, a video clip
  with a player.
- Videos in the chat, examined in the new **video sandbox** (`ghcr.io/xprojects-de/alpdesk-ai-sandbox-video`, the
  sandbox with ffmpeg and pose estimation): the assistant cuts the frames it needs out of the video, looks at them,
  measures a person's posture (joints and angles) and shows a movement in its phases as a strip of stills with the
  skeleton drawn in. Give the video sandbox to the tenants that need it: `SANDBOX_IMAGE` in the tenant's file for
  `docker-compose.tenant-sandbox.yml`; "Sandbox prüfen" shows "Video". A video may have up to 500 MB
  (`APP_CHAT_VIDEO_MAX_SIZE`) and is deleted 8 hours after its upload (`APP_CHAT_VIDEO_RETENTION_HOURS`); an answer
  about a video may take up to 16 rounds (`APP_CHAT_VIDEO_ROUNDS`).
- **Handbook**: tenant admins (and the admin) upload a static website as a ZIP - one for everyone and one per
  knowledge area -, which the users read on the new page "Handbuch" beside the chat; each user sees the website for
  everyone and those of their knowledge areas. The assistant does not know these pages. A ZIP may have up to 100 MB and
  2000 files with an `index.html` at its top; the website is shown sandboxed, its scripts cannot reach the app. A
  knowledge area with a website can only be deleted after the website.
- The chat's files are deleted after a retention time: 14 days after they were stored (`APP_CHAT_FILE_RETENTION_DAYS`),
  a video after 8 hours; the cleanup runs every hour (`APP_CHAT_CLEANUP_CRON`). The conversation keeps a deleted file
  listed as expired; its text is deleted with it. Set a retention empty to keep those files.
- **A login page per tenant**: every tenant gets its own address `/login/<key>` (a random key) that shows its logo,
  name and colour before the login; `/login` stays neutral. The admin copies it on the tenant's tab "Branding" and can
  replace it with a new one (the old one then shows the neutral page); tenant admins copy it on their page "Benutzer".
  After a logout the browser returns to the login page it came from.

### Changed

- A code execution call may run up to 5 minutes instead of 30 seconds (`SANDBOX_TIMEOUT_SECONDS` of the sandbox), so
  it can work through a video; files reach the sandbox streamed, so large ones no longer have to fit in memory.
- A video can only be uploaded for a tenant whose code execution is the video sandbox; it is no longer sent to an AI
  model as it is.
- `/api/v1/chat` and the MCP server's `ask_assistant` refuse two files sent with a question under the same name
  (400 `sentFile.duplicateName`): the assistant reads a large file by its name and could not reach the second.

### Fixed

- A streamed answer no longer breaks off when a tool runs for minutes without anything to show (code working through
  a video): the stream sends a keep-alive comment every 20 seconds, which browsers and SSE clients skip.
- Connected software: "Werkzeuge neu laden" no longer undoes a change made while it waits for the software - an access
  for the MCP server or the client software closed meanwhile, a new key or address stays.
- Connected software: when the software refuses a request, its error text is no longer written to the server log, the
  application log page or the daily report - it may quote what a user typed into one of its prompts. The user still
  sees it.
- Connected software: a call waiting for the user's decision expires as soon as a new question is asked in its
  conversation - before, only once that answer was stored, and a stopped answer finishing later could expire the
  call of a newer question.
- Connected software: a tool called with arguments that are no JSON object is told to the assistant as an error
  instead of ending the answer.
- After running or declining a call of a connected software, the assistant knows the conversation's files again.
- Chat files: a file whose content cannot be deleted no longer holds up the retention of the tenant's other files.
- A BMP image handed out by the code execution is shown below the answer (and in a sequence) like the other images.
- Scans and images in the company knowledge: a page the image model cannot read at once (rate limit, a timeout, a
  server error) is asked again, like the embedding - one refused page no longer fails a whole scan of many pages.
- Company knowledge: a scan replaced twice at the same moment (two pushes of the same `externalId`) no longer stays
  "in progress" for good - it fails with a reason, and pushing it again embeds it; a document replaced while its
  previous version is embedded is always found with its current text.
- Sign-in through WAM: an access request whose user the admin created directly goes at that user's next sign-in.

### Removed

- The model catalog's "reads video directly": no model is sent a video any more.

### Security

- Sandbox: code run in a session could stop the cleanup at the session's end where the sandbox's `/work` and `/tmp`
  belong to its user (Docker with `uid=10001`), so files of later sessions could stay for another user of the tenant.
  The cleanup now gives the rights back and empties every place; if anything is still left, the sandbox takes no
  session until it is restarted ("Sandbox prüfen" shows it busy). Update the sandbox image.

## [1.5.0] - 2026-10-16

**Updating needs a step by hand**: the rights per user are not carried over. After the update every user has the
chat and the company knowledge only - a tenant admin creates the user groups (page "Benutzer", panel "Gruppen"), gives
them their knowledge areas and, on the page "Software", their level per connected software, and assigns the users.

### Added
- User groups: what a user may use - the knowledge areas besides the company knowledge, and the level for each
  connected software - comes from their groups. A user may be in several groups: the highest level counts, and the
  areas of all of them. A user in no group has the chat and the company knowledge. Kept by the tenant admins and the
  administrator, below the users.
- With a WAM proxy that sends the person's directory groups (`APP_WAM_GROUPS_HEADER`, optional): a user group can name
  a WAM group and then counts for a member only while the proxy lists it - a right taken away in the directory is gone
  with the next request; a request without the header counts none of them, and neither does a missing
  `APP_WAM_GROUPS_HEADER`. The user dialog shows the groups the proxy sent last.

### Changed
- A user's knowledge areas and levels per software are no longer set on the user: the user dialog chooses groups, and
  the access list of a connected software lists the groups instead of the users.

### Fixed
- When connected software (MCP) refuses a request - e.g. a prompt's value it does not accept - its own reason is shown,
  no longer "did not answer as an MCP server"; servers that send the error with HTTP status 400 or 500 included.
- An answer of connected software larger than the limit (8 MB) says so instead of "did not answer as an MCP server".

## [1.4.0] - 2026-10-14

### Added
- Files returned by a tool of connected software (MCP): a file a tool sends back as an embedded resource or an image
  is offered for download below the answer, like a file the assistant created - e.g. a generated project as a ZIP
  archive.
- The assistant reads such a file like an uploaded one: the text of documents (PDF, DOCX, XLSX, ...), images, the text
  of logs, code and configuration files. An archive it opens with the code tools when the tenant has a sandbox.
- A text file a tool returns (code, a log, Markdown) is in the tool's result at once: the assistant reads it in the
  same answer - also through the MCP server - and can choose and adapt what it hands on.
- Archives get an icon of their own in the chat's file list.
- The MCP server (Claude Code, GitHub Copilot) hands over the files of its answer: files the assistant creates and
  files a connected software returns go with `ask_assistant`'s result - text files (code, Markdown, CSV, logs) as text
  the client's model can write into the project, other files as base64. Nothing is kept; at most 8 MB per answer.
- The page "Konto" shows every user how to connect Claude Code or GitHub Copilot to the company's assistant: the
  command and the VS Code configuration, ready to copy - only the MCP key comes from an administrator.
- Prompts of a connected software in the chat: a software's MCP server may offer ready-made requests. A tenant admin
  turns each on; users with access pick one next to the paperclip, fill in its values in a form, and its text lands
  in the input field - they read it and send it themselves.
- The assistant's templates are the MCP server's prompts: slash commands in the client, named after the tenant's
  server and template (e.g. `/mcp.muster-ai.code-review` in Copilot, `/mcp__muster-ai__code-review` in Claude Code).

### Changed
- A tool result of connected software may now have up to 8 MB (was 4 MB) - about 6 MB of files.

### Fixed
- A PDF that begins with a print job ticket is recognised as a PDF again instead of being refused as an unknown type
  (Apache Tika 3.3.3, which also hardens the reading of PDF forms, bookmarks and OneNote files).

### Security
- A returned file keeps its type only if it is on a fixed list (archives, documents, text, images); anything else -
  HTML, SVG - is offered as `application/octet-stream`, always as a download. Its name is cleaned of paths.
- A file returned together with a tool error is not offered.
- At most 5 files per answer, counted together with the files the assistant creates.
- No files where nothing is kept: questions through the MCP server and from the client software with an API key
  without history.
