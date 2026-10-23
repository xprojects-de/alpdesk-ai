# Changelog

What changed in each release of Alpdesk AI, newest first. Versions follow [Semantic Versioning](https://semver.org):
**major** - updating needs a step by hand (a setting, the compose file, the client software's API); **minor** - new
features, updating needs nothing by hand; **patch** - fixes and small improvements. Every release is a tag `v<version>`
with its images `ghcr.io/xprojects-de/alpdesk-ai:<version>` and `-sandbox:<version>`; updating: see the README.

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
