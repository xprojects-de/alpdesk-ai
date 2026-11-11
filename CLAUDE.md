# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Zirmo - "a product of Alpdesk", called Alpdesk AI up to 1.9.x - is a **generic AI platform** - used by hotels,
craftsmen and industry alike: a Micronaut/Java backend serving a REST API plus the built Angular frontend as static
resources, backed by PostgreSQL with pgvector. It runs as a console
server application in a Podman pod (app + PostgreSQL) behind Caddy - see [Backend/podman/README.md](Backend/podman/README.md)
and, for running an installation, [Backend/podman/public/README.md](Backend/podman/public/README.md).

- **Tenants (Mandanten)** are the customers (a hotel, a craftsman's business, an industrial company, ...). They get a
  **frontend login**: several users per tenant (one per employee) chat (frontend `/app`,
  API `/tenant/**`). Each tenant has **tenant admins** (users with `admin`) who keep its settings - users, provider
  keys, client software keys, embedding model, the company's knowledge (documents every user finds in the chat
  without seeing them), usage - and chat too (`/tenant/admin/**`); plain users only chat. Chats belong to the user.
  The tenant's client software uses the API (`/api/v1`) with the tenant's **API keys** (`Authorization: Bearer
  ai_...`) - see [tenancy.md](docs/architecture/tenancy.md) "Who does what".
- **The admin** (the operator) can do everything: tenants, the model catalog with prices, global keys, which models a
  tenant may use - and, per tenant, all a tenant admin does (frontend `/dashboard`, admin API `/admin/**`).
- **One database per tenant**: the management database holds tenants, users, keys, prices and usage; every tenant's
  embeddings and chat history live in a database of its own on the same PostgreSQL server - see
  [docs/architecture/tenancy.md](docs/architecture/tenancy.md).
- **AI features with langchain4j**: a model catalog with prices (admin), provider keys (the tenant's own, or the
  admin's global ones - then billed), documents embedded into the tenant database (pgvector), a chat drawing on them,
  usage per call - see [docs/architecture/ai-features.md](docs/architecture/ai-features.md).
- **Documents** are uploaded in the frontend (one by one or as a ZIP archive with folders), pushed by the client
  software (`/api/v1/documents`) or synced from a folder of a file store (knowledge sources) and embedded
  automatically; Apache Tika extracts their text internally - it is not offered as an endpoint of its own (decided
  6 October 2026). Only an agreed list of file types is read (PDF, DOCX, ODT, RTF, TXT, Markdown, HTML, PPTX, XLSX,
  ODS, CSV), recognised by content; images and scanned pages are read by the catalog's image model before embedding.

Earlier versions used ChromaDB; pgvector replaces it.

## Architecture reference `docs/architecture/` (read before working on an area)

This file holds the rules for every change. The detailed rules and decisions of each area live in
`docs/architecture/` (English, index in [README.md](docs/architecture/README.md)). **Before changing an area, read its
file** - it is as binding as this one:

| Area | File |
|------|------|
| Management database, one database per tenant, how a tenant database is reached, who logs in | [tenancy.md](docs/architecture/tenancy.md) |
| langchain4j, model catalog and prices, provider keys, documents and embeddings, chat, files in the chat, tools (agent loop), usage | [ai-features.md](docs/architecture/ai-features.md) |
| Name, colour and logo of the installation and of each tenant, applied in Angular | [branding.md](docs/architecture/branding.md) |
| The tenant's MCP server for Claude Code (MCP key, ask_assistant), MCP clients to the tenant's software, connections that sign in with OAuth | [mcp.md](docs/architecture/mcp.md) |
| A2A both ways on the official SDK: A2A agents as connected software (the tool, a tenant answering through its agent, the skills' choice, call_agent, the assistant prompt step), the tenant's assistant at /a2a (the doors of a key, cards, tasks, push notifications) | [a2a.md](docs/architecture/a2a.md) |
| The tenants' file stores (SFTP, FTPS, S3, SharePoint / OneDrive): fingerprint confirmed before the password goes out, internal hosts, FTPS data connections resuming the TLS session, S3 with the store's own access key only, SharePoint through Microsoft Graph | [file-stores.md](docs/architecture/file-stores.md) |
| Knowledge sources: a file store's folder synced into the knowledge (listing, by hand, by schedule), source_file, deleted and moved by hand, the indexer fair between tenants | [knowledge-sources.md](docs/architecture/knowledge-sources.md) |
| Scheduled tasks: the admin's switch per tenant, the format (YAML/JSON) and its check, the steps read_files / agent / verify / send_mail, langgraph4j with checkpoints in the tenant database, runs, read positions, mails | [tasks.md](docs/architecture/tasks.md) |
| Python and shell in the chat: the sandbox container (`Backend/podman/sandbox/`), its isolation without privileges, run_python / run_shell | [code-execution.md](docs/architecture/code-execution.md) |
| The handbook: a static website per scope (ZIP), served sandboxed in an iframe through a short-lived address | [doc-sites.md](docs/architecture/doc-sites.md) |
| The frontend's languages (English, German): Transloco, the language files, the user's language on the account, the backend's error codes | [i18n.md](docs/architecture/i18n.md) |
| The daily report by e-mail (installation, every tenant, log), the SMTP sender, the in-memory log buffer, the admin's page "Application log" | [monitoring.md](docs/architecture/monitoring.md) |

**Keep it up to date like the code**: every change updates the affected file in the same change. A new area gets its
own file, linked from the table above and from `docs/architecture/README.md`.

## Open source (always)

The project is MIT-licensed and this repository is public: `xprojects-de/alpdesk-ai` (decided 11 November 2026, with
2.0.0 - before, a private repository wrote only the installation's files there). Only the brand name is Zirmo
("Zirmo, a product of Alpdesk"); everything technical keeps the old name on purpose - the Java package `de.alpdesk`,
the database `alpdesk_ai`, the images `ghcr.io/xprojects-de/alpdesk-ai*`, the jar, the containers, volumes and
paths, the repository's name. Never rename them along with a text: an installation would lose its containers or data.
Names people read - UI defaults, docs, release titles, image labels - say Zirmo.

Everything committed is read by outsiders - code, specs, CLAUDE.md, `docs/` (the roadmap too), `.claude/`,
`devtools/`, `website/`. Plans, reviews and measurement reports are not committed: they go to `local-data/` (ignored by
git) - plans to `local-data/plans/`, reviews and the review skills' decision register to `local-data/review/`, reports
to `local-data/measurements/`. Public documents never link to them; what a plan decided that stays binding goes into
`docs/architecture/`. **Nothing real goes into the repository** - no company documents,
chat histories, names of people or customers, keys, passwords, domains of an installation or local paths; specs, test
data and measurements use invented data (`example.com`, "Musterfirma"). What is committed once stays in the history.

The installation lives in `Backend/podman/public/`: its README is the installation guide, next to the compose files,
the Caddyfile, `.env.example` and the screenshots in `docs/images/` (made from a test stack with invented data only);
an installation runs in that folder, `.env`, `sandboxes/` and `./pgdata` next to the files. The compose files carry
the release's image tags = `app.version` (`.github/scripts/check-compose-files.sh`, see "CI"). A release is the tag
`v<version>` with its GitHub release (the jar) and the images on ghcr.io - see `Backend/podman/README.md` "Release".

The documents for outsiders are kept up to date like the code, in the same change: a new or updated runtime dependency
(backend jar, frontend bundle, container image) updates [THIRD-PARTY.md](THIRD-PARTY.md) and, if it brings a NOTICE of
its own, [NOTICE](NOTICE). `./gradlew checkLicense` (in `check` and every jar build) fails for a backend library without
a license of `Backend/config/allowed-licenses.json` (permissive for all, weak copyleft only for the named libraries) - a
new license is a decision of the user, never allowed just to make the build pass. Every jar carries the licenses under
`META-INF/third-party/` (`thirdPartyNotices` in `build.gradle`: LICENSE, NOTICE, THIRD-PARTY.md, the libraries' report
and license files, the frontend's `3rdpartylicenses.txt`); junrar (UnRAR licence, not open source) is excluded from
Tika. A change of what the software assumes or deliberately does not do (anonymous routes, secrets, HTTPS, WAM) updates
[SECURITY.md](SECURITY.md); a change of what is stored, sent to a provider or deleted updates
[docs/privacy.md](docs/privacy.md).

## Changelog and version (always)

[CHANGELOG.md](CHANGELOG.md) (introduced with 1.4.0, decided 14 October 2026) is written for the operators and users of
installations (English, no customer, person or customer's software named, invented examples only).
- **Every change an operator or user of an installation notices** adds its bullet to the topmost section in the same
  change: a feature, a fix, a changed setting, limit or default, a change of `/api/v1`, `/mcp` or the compose files, a
  security matter. Short, one or two lines, what it means for them - not how it is built (no class names, no
  `docs/architecture` paths). Grouped under `### Added`, `### Changed`, `### Fixed`, `### Removed`, `### Security`.
  Refactorings, specs, internal docs, notes and local tooling get no entry.
- **The topmost section is the version in `app.version`**, as `## [x.y.z] - unreleased` while it is worked on. Before
  the release the user writes its date instead of "unreleased" (`## [1.4.0] - 2026-10-15`); the release workflow's first
  step (`.github/scripts/check-changelog.sh`) refuses a version without its dated section.
- **Which number** (Semantic Versioning, as CHANGELOG.md's head says): **major** - updating needs a step by hand (a new
  or renamed required setting, a change of the compose files an installation must follow, a change of `/api/v1` or
  `/mcp` that breaks the client software); **minor** - a new feature, updating needs nothing by hand; **patch** - only
  fixes and small improvements. After a release the next change raises `app.version` to the next patch and opens its
  section; a change that needs more (a feature in a patch) raises it to the next minor or major in the same change,
  section heading included. Raising the number is part of the change and is said in the report.

## Git (always)

**Never commit, amend or push on your own** - not after a finished change, not when a plan was agreed, not when
"let's continue" follows. The user reviews every change in the working tree first and commits it themselves. Leave the
changes uncommitted, report what changed, and commit only when the user explicitly asks for that commit.

## The developer's dev database (always)

**Never check, test or try anything against the developer's dev database** - the PostgreSQL of
`Backend/podman/docker-compose.dev.yml` (`alpdesk-ai-dev-db`, `127.0.0.1:15433`, data in `Backend/database/`). It holds
the data the developer set up by hand. Don't read or write it, don't start the backend against it (`./gradlew run`
does, through `application-dev.properties`), and don't run `devDbStart`, `devDbStop` or `devDbDelete` or touch
`Backend/database/` unless the developer asks for exactly that.

To verify a change, always build your own:
- the Spock specs (`./gradlew test`) - they bring their own Testcontainers database;
- to see the running app: an own PostgreSQL container (own name, another port, no bind mount into the project, removed
  afterwards) and the backend started against it on another port, so a backend the developer runs on 19100 is not hit:
  ```bash
  podman run -d --rm --name claude-check-ai-db -p 127.0.0.1:25433:5432 -e POSTGRES_DB=alpdesk_ai -e POSTGRES_USER=check -e POSTGRES_PASSWORD=check docker.io/pgvector/pgvector:pg18
  DATASOURCES_DEFAULT_URL=jdbc:postgresql://localhost:25433/alpdesk_ai DATASOURCES_DEFAULT_USERNAME=check DATASOURCES_DEFAULT_PASSWORD=check MICRONAUT_SERVER_PORT=29100 ./gradlew run
  podman rm -f claude-check-ai-db   # afterwards, together with stopping the backend
  ```
  (environment variables override `application-dev.properties`). Never stop or remove containers you did not start. A
  compose file of your own always gets its own `name:` - compose names a project after its folder, and a `down` in a
  project of the same name (e.g. another folder `podman/`) removes the developer's containers of the same service.

## Language convention (always)

- **Frontend: every user-facing text is a translation key, in every language of the frontend** — template labels,
  buttons, menu entries, dialog titles, tooltips, snackbars, validation and error messages (decided 11 October 2026,
  [i18n.md](docs/architecture/i18n.md)). Never a literal text in a template or an effect: the key goes into
  `Frontend/src/i18n/en.json` (English, the reference) **and** `de.json` (German) in the same change - `ng lint` refuses
  static text in templates, `i18n.spec.ts` a key missing in a language. The German texts address the user formally
  ("Sie"). A new language is a file plus an entry in `utils/language.util.ts`.
- **Everything else is English** — all code (identifiers), all comments (backend and frontend), log messages, backend
  `ErrorResponse` messages, API paths and DTO fields, the database schema (table/column names, migration comments),
  the docs, commit messages and PR descriptions.
- Don't translate one into the other's territory: new identifiers and comments English. Exception: German
  `.gitignore` comments.

## Industry-neutral (always)

Zirmo is not a hotel product (decided 6 October 2026): hotels, craftsmen and industrial companies use it alike.
Code, prompts, tool descriptions, UI texts, API documentation and examples speak of the tenant, the company, its
documents and its people - never of hotels, guests, rooms or a particular trade. The software that calls `/api/v1`
with a tenant's API key is "the client software" ("angebundene Software" in the UI). Whatever a trade needs on top
comes as configuration or as a tool of its own, not as wording in the core.

## Code style (always)

Code is written to be read: someone new to the project must follow a class from top to bottom without jumping around.
Lean and readable beats clever and general.
- **Names say what, comments say why.** A method or variable named after its purpose needs no comment; a comment
  explains a reason, a rule or a provider's quirk the code cannot show. No comments that repeat the code.
- **Small, flat methods**: one level of abstraction per method, early returns instead of nested ifs, a helper method
  with a good name instead of a block with a comment above it.
- **No abstraction in advance**: no interface with one implementation, no generic framework for one use, no
  configuration option nobody asked for. Add it when the second case is there.
- **No optimisation without a real case**: an optimisation is made only where a load exists or is certain to come, or
  where it removes a wrong result. In doubt leave it, say so in the report ("not done on purpose: ...") and let the
  user decide. This limits what gets *built*, never what gets *looked at*: reviews still check concurrency, resource
  and memory leaks (connections, pools, streams), real performance problems and best practice in full depth.
- **A recommendation is held to the same bar as a change**: before recommending a change at the end of a review or
  report, check there is a real case today and what the damage without it is. A recommendation that does not survive
  this is not made; the finding is still reported with its weight.
- **Plain Java where it is clearer**: a loop instead of a stream chain that needs reading twice; records for data;
  a `switch` over an enum instead of a class hierarchy.
- **Follow the neighbours**: a new class looks like the existing ones of its layer (constructor injection, `@Value`
  settings, `ServiceException`, the same package layout).
- **Readable specs**: a feature method reads like the rule it tests (given/when/then with plain helpers), data tables
  for variants.
- **Every public write method of a service runs in a transaction** (`@Transactional`, or `transactions.executeWrite(...)`
  where something must happen after the commit). A method that must not hold one (it calls an AI provider, which can
  take seconds to minutes) says why in its comment.

## Repository and build

The repo has two independent projects and the sandbox:
- `Backend/` — Micronaut 5 (`micronautVersion` in `gradle.properties`, Java 25, Gradle 9) REST API.
- `Frontend/` — Angular 22 SPA, npm/Angular CLI build.
- `Backend/podman/sandbox/` — the container the chat's Python and shell run in (Ubuntu 26.04, Python, `app.py` on Starlette and
  `sessions.py`; built from the
  repository root with `-f Backend/podman/sandbox/Dockerfile`); `Backend/podman/sandbox/selftest.py` checks a running instance -
  [code-execution.md](docs/architecture/code-execution.md). Every tenant that wants code execution gets instances of its
  own (`Backend/podman/public/docker-compose.tenant-sandbox.yml`, addresses and token at the tenant) - never shared between
  tenants. Never run `app.py` outside its container.
- `devtools/mcp-test-server/` — an MCP server with invented data for trying out MCP clients by hand: our own client,
  Claude Code and GitHub Copilot - text, files, images, links, errors, elicitation, prompts, one tool per scenario
  (`start.sh`, `127.0.0.1:19190/mcp`; `check.py` runs every scenario; its README). A local helper, not used by the
  specs (they have `FakeMcpSoftware`). A new MCP scenario to test goes there.
- `devtools/a2a-test-agent/` — an A2A agent on the official Python `a2a-sdk` with invented data for trying out the A2A
  client by hand (one scenario per skill or marker; `start.sh`, `127.0.0.1:19195`; `check.py`; its README). A local
  helper, not used by the specs (they have `FakeA2aAgent`).
- `devtools/tavily-fake/` — answers like Tavily's search with invented pages (marker `WEB-4242`), for a test stack's
  web search through `APP_WEB_SEARCH_ADDRESS` (its README). A local helper.
- `devtools/oauth-fake/` — a hosted software that signs in with OAuth: an authorization server with a consent page
  (registration, PKCE, rotating refresh tokens) and the MCP server "Ticketsystem" behind it, for a test stack's
  connection that signs in (roadmap point 61; its README). A local helper, not used by the specs
  (they have `FakeAuthorizationServer`).
- `devtools/general-chat-test/` — the test stack of the general chat over topic tenants (roadmap point 51): Caddy with
  Authelia (users and IDM groups in a file) in front of the app built from the working tree, a PostgreSQL of its own,
  its own embedding model (bge-m3), an SFTP server and MailHog; `setup.py`, `check.py` (no chat model: sign-in, the
  topics, `/api/v1`, `/mcp` and `/a2a` as machines use them, the file store and its knowledge source, the mails),
  `scenarios.py` and `measure.py` (a real model - the general chat, the machines, the scheduled task; reports in
  `local-data/measurements/general-chat/`, ignored by git); its README. Also where the happy path of every feature runs, with and without WAM
  (skill `/alpdesk-happy-path`): `walk/features.md` lists every feature and where it runs, `walk/walk.md` is the
  browser walk - a new feature gets its line and its step there in the same change. A local helper.
- `website/` — the project's web page (English): `index.html` with styles and logo inline and the screenshots in
  `images/` (nothing loaded from third parties, no cookies, light and dark), published separately, not part of the build.
  Its links point at this repository `xprojects-de/alpdesk-ai`; a feature that changes what the page promises updates
  it in the same change.

`Backend/build.gradle`'s `copyFrontend` task copies `Frontend/dist/alpdesk-ai/browser` into
`Backend/src/main/resources/public`, so a full production build is Frontend-then-Backend. That directory is
**gitignored** — never commit the built frontend; CI builds it.

The only deliverable is the **Micronaut AOT-optimized fat jar** (`./gradlew optimizedJitJarAll` →
`build/libs/alpdesk-ai.jar`), run on a plain Java 25 JRE in the Podman image. The production image copies the jar of a
GitHub release (`Backend/podman/Dockerfile` target `release`, context `podman/release`) - never a jar built from the
working tree; the target `from-source` is only for the local test stack. The release workflow builds that image itself
and pushes it to ghcr.io (`ghcr.io/xprojects-de/alpdesk-ai:<version>`, no `latest`); `docker-compose.prod.yml` in
`Backend/podman/public/` names that tag (the image tags there are `app.version`, raised with it). The prod compose file
and the Caddyfile hold nothing of an installation (secrets and domain come from the server's `.env`, template
`Backend/podman/public/.env.example`), so a release can replace them.
`docker-compose.proxy.yml` is the prod file without Caddy (behind an own reverse proxy - a NAS, an nginx -, the app's
port on `127.0.0.1:19100`): a new app setting goes into both files, `.github/scripts/check-compose-files.sh`
(pr-checks, release workflow) fails otherwise - and when an image tag there is not `app.version`. AOT works on the JVM; there is deliberately no GraalVM
native image — don't reintroduce it. The `aot {}` settings in `build.gradle` only apply to the `optimized*` tasks: the
plain `shadowJar` (`alpdesk-ai-no-aot.jar`) is unoptimized and only for comparison. Container env vars (`APP_*`,
`JWT_*`, `DATASOURCES_*`) are still read at startup despite AOT's precomputed environment - the CI smoke test
(`.github/scripts/smoke-test-jar.sh`) checks it in every pull request. `replaceLogbackXml` bakes `logback.xml` into the jar: it
is the one logging configuration for development and the container (dated lines, no colour codes).

The build's version is `app.version` in `Backend/src/main/resources/application.properties` (read by `build.gradle`
and the release workflow) - raise it there, nowhere else.

Ports, chosen so that it runs next to other local projects: backend 19100, Angular dev server 4300, dev database
15433.

## CI (GitHub Actions)

- `.github/workflows/pr-checks.yml` — every pull request against `main` (work happens on feature branches; no push
  trigger: it would run every commit twice): no migration of the latest release changed (both sets, compared with the
  newest tag `v<x.y.z>`, pre-releases not counted - a new one is added; the migrations of the unreleased version may
  be folded into one until its release), Spock suite, the
  AOT jar (`optimizedJitJarAll -x test`) and its smoke test, `ng lint`, frontend unit tests, `ng build`, the sandbox
  image built and checked with `Backend/podman/sandbox/selftest.py` (started not root, no capabilities, read-only).
- `.github/scripts/smoke-test-jar.sh` — starts the AOT jar like the container (no "dev") against a PostgreSQL of the job:
  it must refuse to start without its secrets, then answer `/watchdog`, take the admin login from `APP_USERNAME` /
  `APP_PASSWORD` (and refuse `alpdesk`/`alpdesk`), log dated lines, create a tenant with its own database (Flyway reads
  `db/tenant/` from the jar), log a tenant user in, upload a DOCX with a tenant's API key (Tika's detectors and parsers
  come through service loading, which AOT touches), embed it and chat through a fake Ollama server of the script
  (Python) - in one piece and as the stream's events -, ask the tenant's MCP server, connect the tenant's software through its MCP server (the same fake answers as
  one: its tools are read, and a tenant user's chat calls one through the MCP Java SDK's client, then the client
  software's `/api/v1/chat` once its API key is given the software), check knowledge areas (a key pushes into its area, only the area's
  user finds it in the chat, the company knowledge's uploads are deleted at once), check through a fake
  WAM proxy of the script (it strips the user header from outside and sends the signed-in person) that the header
  counts for nothing without WAM, serve the frontend if it has one, and start again under a context path
  (`MICRONAUT_SERVER_CONTEXT_PATH=/alpdesk-ai`: routes, login, Swagger, the MCP server and the frontend's base href
  below it) with `APP_WAM_ENABLED`: through the proxy someone unknown waits, the admin assigns them as a tenant's admin, who signs in
  and approves the next user; another person at the proxy ends a session; tenant users have no password login - and
  with the chat files' retention variables set empty the log says they are kept. Locally: own database
  (see "The developer's dev database"), JDK 25 first on the `PATH`,
  `DATASOURCES_DEFAULT_*=... PORT=29100 .github/scripts/smoke-test-jar.sh` from the repository root.
- `.github/workflows/build.yml` — started by hand: builds the frontend, runs `copyFrontend test optimizedJitJarAll`,
  checks the jar contains the frontend, the AOT configurer and the licenses (`META-INF/third-party/`), runs the smoke test, builds the production image from
  that jar and the sandbox images (checked with `Backend/podman/sandbox/selftest.py`) - for `linux/amd64` and
  `linux/arm64`, one matrix job per architecture (arm64 under QEMU on the x86 runner, where only start and `/health` are
  checked; the selftest on arm64 runs by hand on an arm64 machine), joined under one tag by `publish-images` -, pushes them
  to ghcr.io (`ghcr.io/xprojects-de/alpdesk-ai`, `-sandbox` and `-sandbox-video`, tagged with the version), and publishes the GitHub release `v<app.version>`
  ("Zirmo v<version>") with the jar alone (`alpdesk-ai-v<version>.jar`) - its first step refuses a version without its
  dated section in CHANGELOG.md (`.github/scripts/check-changelog.sh`) or with compose files not naming it
  (`.github/scripts/check-compose-files.sh`). Re-running for an unchanged version replaces the jar and the images; an
  existing tag is never moved. **Started with a label** (`rc.1`, `dev.2`) it publishes a pre-release
  `<app.version>-<label>` the same way - images, jar, tag and GitHub release marked pre-release - without changing
  `app.version` and without the CHANGELOG's dated section (the compose files keep naming the release's version); the
  tests may be left out then (`Backend/podman/README.md` "Release"). Every run tags the embeddings image's `latest` with its version.
- `.github/workflows/build-embeddings.yml` — started by hand, not part of a release: builds `Backend/podman/ollama`
  (Ollama with `bge-m3` baked in, about 5 GB), checks that it embeds started restricted, and pushes
  `ghcr.io/xprojects-de/alpdesk-ai-embeddings:latest` - one image for `linux/amd64` and `linux/arm64` (the same matrix as
  `build.yml`; on arm64 under QEMU only the start and the model list are checked), replaced by every run; a release tags it with
  its version, which `Backend/podman/public/docker-compose.embeddings.yml` names.
- `.github/dependabot.yml` — weekly grouped updates for Actions, Gradle, npm and the sandbox (pip, its base image).

## Commands

### Backend (run from `Backend/`, needs a JDK 25 as JAVA_HOME)

```bash
./gradlew devDbStart                 # start the dev database (devDbStop: stop, data stays; devDbDelete: stop and delete the data)
./gradlew sandboxStart               # build and start the sandbox for run_python / run_shell (127.0.0.1:19180; sandboxStop)
./gradlew videoSandboxStart          # the video sandbox instead, same port (ffmpeg, RTMPose; videoSandboxStop) - first build ~1 h, then cached
./gradlew run                        # API on http://localhost:19100 against the dev database, environment "dev"
./gradlew test                       # run the Spock/Groovy test suite
./gradlew test --tests "de.alpdesk.security.TenantApiKeySpec"   # single spec class
./gradlew copyFrontend               # copy the built frontend into src/main/resources/public
./gradlew optimizedJitJarAll         # AOT-optimized fat jar (build/libs/alpdesk-ai.jar) - the deliverable
./gradlew checkLicense               # the libraries' licenses against config/allowed-licenses.json (report: build/reports/dependency-license)
./gradlew optimizedRun               # run with AOT optimizations
```

**Audio and video need ffmpeg** (`app.media.ffmpeg`, default `ffmpeg` from the PATH; the container image installs
it, CI too): without it they are refused (415), and `ChatMediaSpec` is skipped - install ffmpeg locally (e.g. `brew
install ffmpeg`) to run it.

**The daily report goes to a local MailHog in "dev"** (`application-dev.properties`: SMTP on `127.0.0.1:1025`, UI on
http://localhost:8025 - MailHog's own ports, one MailHog serves several projects). Start it by hand: `podman run -d --name
mailhog -p 1025:1025 -p 8025:8025 docker.io/mailhog/mailhog`; then "Tagesbericht jetzt senden" on the admin's page
"Anwendungslog" shows it there at once. Without MailHog the report fails with a warning, nothing else.

**`./gradlew run` and `./gradlew test` need Podman (or Docker) running**: `run` uses the dev database from
`application-dev.properties` (start it first with `./gradlew devDbStart`); `DATASOURCES_DEFAULT_*` environment
variables still override it. **The tests have their own database**: `application-test.properties` sets a
Testcontainers JDBC URL (`jdbc:tc:pgvectortest:pg18:///alpdesk_ai_test`, the same pgvector image as production, with
1 GB of shared memory through `services.tenant.TestDatabaseProvider`), so specs
never write into the dev database. With Podman, the Podman machine's socket is the Docker socket
Testcontainers uses.

Default login is `alpdesk` / `alpdesk` unless overridden via `APP_USERNAME`/`APP_PASSWORD` - these defaults and the JWT
secret fallback in [application.properties](Backend/src/main/resources/application.properties) are for local
development only. **Outside the "dev" and "test" environments the app refuses to start** unless `APP_USERNAME`,
`APP_PASSWORD`, `APP_SECRET_KEY` and `JWT_GENERATOR_SIGNATURE_SECRET` are set (not empty, not `CHANGE_ME`, not a value from the
repository, the password at least 12 and the JWT secret at least 32 characters; a `DATASOURCES_DEFAULT_PASSWORD` that
is `CHANGE_ME` or the local one is refused too) - `security.ProductionSecretsCheck`, whose spec reads the committed
values from `application.properties` and `podman/docker-compose.yml`. `./gradlew run`/`optimizedRun` set "dev" in
build.gradle, a hand-started jar for local tests needs `MICRONAUT_ENVIRONMENTS=dev`. The database has no defaults at
all: the packaged jar refuses to start without `DATASOURCES_DEFAULT_URL`.

### Backend tests (always)

**Every backend change comes with enough Spock specs in the same change** - a feature or fix is not done without them,
and the specs of the changed area (`./gradlew test --tests ...`) must be green before it is reported as done. **The
full suite (`./gradlew test`, over 10 minutes) runs only once, at the very end of the whole task and only after asking
the user** - never after each change, fix or review round in between (decided 14 October 2026); until then the report
says it is still open. "Enough" means, for every new or changed endpoint/service:

- the happy path through the HTTP API - admin API, tenant-user API and tenant API (`/api/v1`) where they exist;
- every validation rule (400), preferably as a data-driven `where:` table;
- not found and tenant isolation: another tenant's ids answer 404, and one tenant never sees another's documents,
  chats or keys;
- rights: role rules (extend `TenantApiKeySpec` for every new controller or role);
- business rules (409) and state transitions.

A bug fix starts with a spec that fails without the fix. AI providers are never called from the specs: they get a fake
(langchain4j's model interfaces) that answers deterministically. Specs stay independent: each creates its own tenant.

Test tooling:
- `@MicronautTest` wraps every feature in a transaction that is rolled back. Specs that write through repositories or
  services next to HTTP calls use `@MicronautTest(transactional = false)`: otherwise the uncommitted rows lock the HTTP
  requests out.
- A helper named `call` in a spec collides with `Closure.call` inside closures - name it differently (`request`).
- **AI providers are never called**: `services.ai.FakeAiModels` replaces `AiModels` in every spec (word-hash
  embeddings, answers repeating the question, `failures` for a refused key; `reset()` in `setup()`).
  `services.ai.AiApi` holds the shared HTTP steps of the AI specs (models, tenant, user token, upload),
  `awaitDocument`, which waits for the background embedding, `ask` / `askEvents`, which read the chat's event
  stream (the fake streams its answer word by word), and `chatUpload` / `download` for the chat's files. The fake
  behaves like a model using its tools: it searches and reads every listed file when offered them, creates a file for
  `[[create_file <name> <FORMAT>]]`, reports the excerpts, images and files it got, and `failAfterTool` fails the round
  after a tool call; `streamsOnOwnThread` streams like a real provider, off the thread that asked (a client going
  away interrupts that one). It records copies of the messages - the loop appends to its list after every call.
- `capacity.CapacitySpec` is the load test (many tenants, one large tenant's search), skipped unless `LOAD_TEST` is
  set - also in the full suite and CI. It runs only by hand, through the skill `/alpdesk-load-test` (report in
  `build/reports/capacity/`).
- The file stores: `controller.filestore.FileStoreSpec` (no server - fields, names, passwords, isolation) runs always;
  `controller.filestore.FileStoreServerSpec` runs against real SFTP and FTPS servers built from
  `src/test/resources/filestore/` and an S3 server (SeaweedFS, pulled) - Testcontainers - and is skipped unless
  `FILE_STORE_TEST` is set - also in the full suite and CI (decided 13 October 2026, for S3 29 October 2026).
  `controller.filestore.SharePointStoreSpec` runs always, against `services.filestore.FakeMicrosoft` (Entra ID's
  login and Microsoft Graph as an HTTP server of the spec, `test.fake-graph=true`) - Microsoft is never called. **Run it by hand with every change of `services.filestore` (or what it uses)
  and before a release**: `FILE_STORE_TEST=1 ./gradlew test --tests "de.alpdesk.controller.filestore.*"`. Shared
  steps: `services.filestore.FileStoreApi`.
- The scheduled tasks: `controller.task.TaskSpec` (keeping tasks) and `services.task.TaskRunSpec` (runs) run with
  `test.fake-store=true` (`services.task.FakeStoreClient`, an SFTP store in memory, replaces the real client) and
  `app.mail.*` pointing at one GreenMail of the test JVM - both through `services.task.TaskApi`. `FakeAiModels` answers a
  task's step with its structured result (`[[find TEXT]]`, `[[invent]]`, `[[unclear-until N]]`, `brokenTaskAnswers`), a
  `prompt` step with "Antwort von <step> (Runde n)" (`[[choose a,b]]`, `[[choose-late w]]`, `[[choose-none]]`) and a draft
  (`[[draft <name>]]`, `[[draft-wrong]]`, `[[draft-never]]`, `[[draft-change <name>]]`).
- `services.ai.ModelServerSpec` runs the real `AiModels` against an HTTP server inside the spec that answers like
  Ollama or an OpenAI-compatible server - the one place the provider clients themselves are exercised.
- A new tool is a `ChatTool` bean (`services.tools`): spec it through the `Toolbox` like `ToolboxSpec`, and teach
  `FakeAiModels` to call it where a chat spec needs it.
- **Tavily is never called**: `services.tools.FakeTavilySearch` replaces `TavilySearch` in every spec (`reset()` in
  `setup()`); `FakeAiModels` searches the web for `[[web <query>]]` after the documents, `[[web-first <query>]]` before
  them - `controller.chat.WebSearchSpec`.
- Stored files go to `build/test-storage` (`app.storage.local.path`), which the `test` task empties before every run.
- Creating a tenant creates a real database on the specs' PostgreSQL (`TenantDatabasesSpec` checks it); a spec that
  creates tenants through a service, not over HTTP, needs `transactional = false` - CREATE DATABASE cannot run inside
  the spec's transaction.
- **No migration of every tenant on a spec context's start** (`app.tenant-databases.migrate-on-start=false` in
  `application-test.properties`): the suite's tenants pile up (~1000) and each of the ~90 contexts would migrate them
  all again. A feature that needs it starts a context of its own with it on (`TenantDatabasesSpec`, once - a walk late
  in the suite takes a minute or more); `CapacitySpec` sets `@Property(name = "app.tenant-databases.migrate-on-start",
  value = "true")`. Whatever else walks every tenant on start costs the suite the same - keep it to the tenants that
  need it (`TaskRunner` reads only tenants with tasks).

### Frontend tests

Logic without a template gets Vitest specs next to its file (`*.spec.ts`, `npx ng test --watch=false`): reducers,
utils (validators, forms, error messages) and custom RxJS operators. `i18n/i18n.spec.ts` checks the language files:
every language has exactly the keys of English, no empty text, the same `{{params}}`. What a dialog sends and refuses goes into a util
with a spec, not into the component. Lint and the unit tests must pass before a frontend change is done.

### Frontend (run from `Frontend/`)

```bash
npm start                # ng serve on http://localhost:4300 (calls the backend directly at :19100)
npm run build            # build
npm run deploy           # production build → dist/alpdesk-ai/browser (what copyFrontend consumes)
npm test                 # Angular's unit-test builder (Vitest under the hood)
npm run lint             # ng lint: ESLint (angular-eslint) + Prettier as a lint rule
npm run lint:fix         # formats with Prettier and auto-fixes what ESLint can
```

Formatting is owned by Prettier (`.prettierrc.json`) and enforced through ESLint via `eslint-plugin-prettier`, so
`ng lint` is the single entry point for both. **`lint:fix` marks static text and static attributes of a template with
`i18n`**, which needs `$localize` - not loaded, so the page fails with "$localize is not defined": after a `--fix` look
for added `i18n` attributes (`git diff | grep i18n`) and make the text a key, the attribute a binding. **After every frontend code change, run the linter before reporting the
change as done** (`npm run lint:fix`, then `npm run lint`), and run `npm run build` after a `--fix` over many files:
`--fix` applies rule fixes, not just formatting.

CORS in `application.properties` only allows `http://localhost:4300`, so the Angular dev server must run on that exact
port when talking to a locally running backend.

## Backend architecture

Micronaut app (`de.alpdesk`), annotation-driven, no Spring.

### Package structure (always follow it)

The code is organised **by layer first, then by feature**: each layer package has one sub-package per feature. A new
domain area (e.g. `chat`, `document`) gets a sub-package in every layer it needs - never loose classes directly in a
layer package.

```
de.alpdesk
├── Application.java
├── controller/
│   ├── common/        GlobalExceptionHandler, SpaFallbackController, HealthController (/watchdog),
│   │                  VersionController, SwaggerController
│   ├── tenant/        TenantController (incl. /admin/tenants/{id}/sandbox: the check of the tenant's sandbox),
│   │                  TenantApiKeyController, TenantUserController, TenantModelController,
│   │                  TenantProviderKeyController (/admin/tenants/**, Roles.ADMIN);
│   │                  UserGroupAdminController (/admin/tenants/{id}/groups, Roles.ADMIN);
│   │                  TenantAdminUserController, TenantAdminApiKeyController, TenantAdminUserGroupController
│   │                  (/tenant/admin/**, Roles.TENANT_ADMIN);
│   │                  AccessRequestController (/admin/access-requests - the WAM proxy's unknown people, Roles.ADMIN)
│   ├── login/         WamLoginController (/login/wam: sign-in through a WAM proxy, anonymous)
│   ├── ai/            AiModelController (/admin/models), GlobalProviderKeyController (/admin/provider-keys),
│   │                  UsageAdminController (/admin/usage)  (Roles.ADMIN);
│   │                  ProviderKeyController (/tenant/admin/provider-keys), TenantAdminUsageController
│   │                  (/tenant/admin/usage) (Roles.TENANT_ADMIN); AiSettingsController (/tenant/ai/**) (Roles.TENANT_USER)
│   ├── document/      DocumentController (/tenant/admin/documents, .../embed-again, .../embedding-model,
│   │                  .../search-language, Roles.TENANT_ADMIN), TenantKnowledgeController (/admin/tenants/{id}/documents,
│   │                  .../embed-again, .../embedding-model, .../search-language, Roles.ADMIN),
│   │                  KnowledgeAreaController (/tenant/admin/knowledge-areas, Roles.TENANT_ADMIN),
│   │                  KnowledgeAreaAdminController (/admin/tenants/{id}/knowledge-areas, Roles.ADMIN),
│   │                  KnowledgeSourceController (/tenant/admin/knowledge-sources, Roles.TENANT_ADMIN),
│   │                  KnowledgeSourceAdminController (/admin/tenants/{id}/knowledge-sources, Roles.ADMIN)
│   ├── chat/          ChatController (/tenant/chat/stream - server-sent events, /tenant/chat/actions/{id}/run|cancel
│   │                  (a changing tool call of the connected software, decided), /tenant/chat/conversations/**,
│   │                  /tenant/chat/files/**, Roles.TENANT_USER), ChatFiles (upload/download helpers shared with api/v1),
│   │                  ChatEvents (the streamed answer's server-sent events, shared with api/v1)
│   ├── account/       AccountController (/tenant/account: the tenant user's own account, its password and language,
│   │                  whether the tenant has an MCP key, Roles.TENANT_USER)
│   ├── assistant/     TenantAdminAssistantController (/tenant/admin/assistant, Roles.TENANT_ADMIN),
│   │                  AssistantAdminController (/admin/tenants/{id}/assistant, Roles.ADMIN)
│   ├── mcp/           TenantAdminMcpKeyController (/tenant/admin/mcp-keys), TenantAdminMcpConnectionController
│   │                  (/tenant/admin/mcp-connections) (Roles.TENANT_ADMIN); McpKeyAdminController, McpConnectionAdminController
│   │                  (/admin/tenants/{id}/mcp-keys, .../mcp-connections, Roles.ADMIN); SoftwarePromptController
│   │                  (/tenant/chat/prompts: the connected software's prompts in the chat, Roles.TENANT_USER). /mcp
│   │                  itself is Micronaut MCP's controller, guarded by intercept-url-map (Roles.MCP)
│   ├── filestore/     TenantAdminFileStoreController (/tenant/admin/file-stores, Roles.TENANT_ADMIN), FileStoreAdminController
│   │                  (/admin/tenants/{id}/file-stores, Roles.ADMIN) - list, create, update, delete, check, fingerprint,
│   │                  folders (to choose a knowledge source's folder)
│   ├── task/          TenantAdminTaskController (/tenant/admin/tasks, Roles.TENANT_ADMIN), TaskAdminController
│   │                  (/admin/tenants/{id}/tasks, Roles.ADMIN) - tasks, validate, runs (start, list, read, delete)
│   ├── monitoring/    DailyReportController (/admin/daily-report/send: the daily report now), ApplicationLogController
│   │                  (/admin/application-log: the recent warnings and errors)  (Roles.ADMIN)
│   ├── docsite/       DocSiteController (/tenant/admin/doc-sites, Roles.TENANT_ADMIN), DocSiteAdminController
│   │                  (/admin/tenants/{id}/doc-sites, Roles.ADMIN), HandbookController (/tenant/doc-sites,
│   │                  Roles.TENANT_USER), DocSiteFilesController (/doc-sites/<token>/**: the files, anonymous, sandboxed)
│   ├── branding/      BrandingController (/branding and a tenant's login page /branding/login/<key>, anonymous),
│   │                  TenantBrandingController (/tenant/branding, Roles.TENANT_USER), TenantBrandingAdminController
│   │                  (/admin/tenants/{id}/branding, Roles.ADMIN), TenantAdminBrandingController
│   │                  (/tenant/admin/branding/login-key, Roles.TENANT_ADMIN)
│   ├── a2a/           A2aController (/a2a: the tenant's assistant as an A2A agent, Roles.MCP - an MCP key with the A2A
│   │                  door), A2aExtensionController (/.well-known/agent-card.json and /a2a/extensions/*, anonymous)
│   ├── oauth/         OAuthCallbackController (/oauth/connections/callback: a connection's OAuth sign-in coming back,
│   │                  /oauth/client-metadata.json: our client id metadata document - both anonymous)
│   └── api/v1/        TenantSelfController (/api/v1/me), DocumentApiController (/api/v1/documents),
│                      ChatApiController (/api/v1/models, /api/v1/chat - also /chat/stream as server-sent events -,
│                      /api/v1/chat/**), TaskApiController (/api/v1/tasks: the tasks assigned to the key)
│                      (Roles.TENANT)
├── services/
│   ├── common/        ServiceException, InternalAddresses (our own network - for every address a tenant admin enters)
│   ├── tenant/        TenantService, ApiKeyService, TenantUserService, AccessRequestService (WAM sign-in),
│   │                  UserGroupService (user groups: what a user may use, the WAM proxy's IDM groups),
│   │                  TenantDatabases (create/migrate/drop a tenant's database, its pool), TenantDatabaseMigration
│   ├── ai/            AiModels (the only class calling a provider; TeiScoringModel the reranker's /rerank; BedrockClients the AWS SDK's
│   │                  clients for Amazon Bedrock; StreamStop ends a streamed answer that runs away,
│   │                  CycleDetector finds reasoning repeating itself), AiModelService, TenantModelService (which models
│   │                  a tenant may use), ProviderKeyService, UsageService, ModelServers (own servers' addresses)
│   ├── document/      DocumentService, DocumentTextExtractor (Tika, allowed types), DocumentIndexer (background
│   │                  embedding), TenantEmbeddings (pgvector store), KnowledgeSearch (the search rules, the
│   │                  reranking of its candidates),
│   │                  DocumentArchive (ZIP import), ScanReader + DocumentImages (images and scans),
│   │                  KnowledgeAreaService + KnowledgeScope (knowledge areas: who finds which documents),
│   │                  KnowledgeSourceService, SourceSync (a run: list, read, delete), SourceScheduler (knowledge
│   │                  sources: a file store's folder synced into the knowledge)
│   ├── agent/         AgentLoop (the agent loop: rounds, tools, usage per round - in one piece or streamed), AgentRun
│   ├── chat/          ChatService (prepares, asks through the AgentLoop, stores), SentFiles (files sent with a question), ChatFileService, ChatFileReader (text, images, scanned PDFs,
│   │                  audio, video), MediaConverter (ffmpeg), AudioTranscriber, FileRenderer + OutputFormat
│   │                  (TXT/MD/CSV/HTML/JSON/DOCX/XLSX/PDF)
│   ├── sandbox/       Sandboxes (the tenant's own sandbox instances, one leased per answer; the admin's check),
│   │                  SandboxSession, SandboxFile
│   ├── tools/         ChatTool, Toolbox, ToolContext, ToolResult; SimilaritySearchTool, ReadDocumentTool (reads on in
│   │                  a document found), ReadFileTool, CreateFileTool, CreateImageTool (the image model),
│   │                  SearchFileTool + ReadFileLinesTool (large files sent with a question, ExploredFile),
│   │                  RunPythonTool + RunShellTool + CodeSessions (code in the sandbox, one session per answer);
│   │                  WebSearchTool (web_search), WebSearchAccess (who may: group, API key, MCP server), TavilySearch
│   │                  (the one class that knows Tavily, langchain4j's TavilyWebSearchEngine)
│   ├── docsite/       DocSiteService (the handbook's websites: upload, replace, who reads which, the files),
│   │                  DocSiteTokens (the signed short-lived address)
│   ├── assistant/     AssistantService (the tenant's instructions, settings and chat templates)
│   ├── filestore/     FileStoreService (the stores, their check and confirmed fingerprint), FileStoreAddresses
│   │                  (app.file-store.internal-hosts), SftpStoreClient (MINA SSHD), FtpsStoreClient (Commons Net,
│   │                  data connections resuming the TLS session), S3StoreClient (AWS SDK, nothing of the server it
│   │                  runs on), GraphStoreClient + GraphAddresses (SharePoint and OneDrive through Microsoft Graph,
│   │                  the token through MSAL, no Graph SDK), ServerSettings, S3Settings, GraphSettings, CheckResult, FileStoreSession
│   │                  (list files and folders, read - for tasks and knowledge sources), RemoteFile, NamePattern
│   ├── task/          TaskService (tasks and runs, the admin's switch), TaskCompiler (YAML/JSON to TaskDefinition or
│   │                  TaskErrors with lines), Condition, TaskGraph (the definition as a langgraph4j graph), TaskRunner
│   │                  (runs on the task executor, checkpoints, read positions, taken up at start, cancel), TaskScheduler
│   │                  (due schedules every minute) + TaskSchedule (the times), TaskMails, TaskResults;
│   │                  TaskDraftService ("Draft with AI"), TaskReference (the format's reference, resources/tasks/reference);
│   │                  step/: ReadFilesStep, AgentStep (agent and verify, through the AgentLoop), PromptStep (prompt, its
│   │                  answer a run file, choices), StepInputs, TaskFindings, TaskPrompts, SendMailStep
│   ├── monitoring/    DailyReportJob (the daily report, scheduled and at the admin's request), DailyReportMail (its
│   │                  texts), DailyReport, ApplicationLogService (the log buffer's recent warnings and errors, for the page and the report)
│   ├── a2a/           A2aClient + A2aHttpClient (the SDK's client on our address rules), AgentAnswers (a tenant
│   │                  answering through its agent), A2aAgentCore (the SDK's server core), AssistantAgent, A2aCards,
│   │                  JdbcTaskStore + KeyOwnsTask, RunTasks, A2aTaskCleanupJob, JdbcPushConfigStore + PushAddresses
│   ├── oauth/         OAuthClient (a connection's OAuth sign-in: client id, authorization code with PKCE, callback,
│   │                  refresh with rotation - protocol-neutral, the messages by Nimbus), OAuthMetadata (RFC 8414 /
│   │                  OpenID, RFC 9728), OAuthHttp (every call of the flow), OAuthAddresses, OAuthReachability,
│   │                  AuthorizationServer
│   ├── mcp/           AssistantMcpTools (the MCP server's tools, flat schemas; the answer's files as resources),
│   │                  KnowledgeMcpTools (search_knowledge, read_document: the client's model searches and reads itself),
│   │                  AssistantMcpPrompts (the tenant's templates as prompts), BrandedMcpServer (the tenant's
│   │                  brand in initialize and tools/list, prompts/list and prompts/get), McpKeyService (the key's
│   │                  areas and software); SoftwareMcpProxy (the key's connected software passed through to MCP
│   │                  clients: tools, prompts, instructions); McpConnectionService (also the switch "Direkt an
│   │                  MCP-Clients", .../pass-through, .../sign-in), Credential (a call's bearer: key or OAuth token),
│   │                  McpClient (MCP Java SDK client), LinkDownloads (a tool's file returned as an https link),
│   │                  McpAddresses, McpTools (in the chat),
│   │                  SoftwarePrompts (the connected software's prompts in the chat); TaskMcpTools (list_tasks, run_task,
│   │                  task_run for the tasks assigned to the MCP key)
├── entities/
│   ├── tenant/        Tenant, TenantApiKey, TenantUser, UserGroup, AccessRequest (+ SearchLanguage) (management database, @MappedEntity)
│   ├── ai/            AiModel, ProviderKey, UsageRecord (+ enums, UsageSummary)  (management database)
│   ├── assistant/     TenantAssistant, ChatTemplate (+ AnswerSource, Creativity, Reasoning)  (management database)
│   ├── mcp/           TenantMcpKey, McpConnection, McpTool, McpPrompt (management database)
│   ├── oauth/         OAuthGrant (+ GrantStatus, ClientIdSource) (management database)
│   ├── filestore/     FileStore, FileStoreType                      (management database)
│   ├── task/          Task (management database); TaskRun, TaskRunStep, RunFile, ReadPosition (+ RunState,
│   │                  StepState, StartedBy)                         (tenant database, plain records)
│   ├── docsite/       DocSite                                       (tenant database, plain record)
│   ├── document/      Document, DocumentOrigin, DocumentStatus, SourceFile (tenant database, plain records);
│   │                  KnowledgeArea, KnowledgeSource (+ SourceSchedule) (management database)
│   └── chat/          Conversation, Message, ConversationFile, PendingAction (tenant database, plain records)
├── repositories/
│   ├── tenant/, ai/, filestore/, oauth/   Micronaut Data repositories (management database)
│   ├── task/          TaskRepository (management database, also task_file_store); TaskRunRepository - plain JDBC on a
│   │                  tenant's DataSource (runs, steps, files read, read positions, langgraph4j's checkpoints)
│   ├── docsite/       DocSiteRepository - plain JDBC on a tenant's DataSource
│   ├── document/, chat/  DocumentRepository, SourceFileRepository, ConversationRepository, ConversationFileRepository,
│   │                     PendingActionRepository - plain JDBC on a tenant's DataSource; KnowledgeAreaRepository,
│   │                     KnowledgeSourceRepository (management database, Micronaut Data)
│   └── monitoring/    DailyReportRepository (the daily report's read-only queries: management and tenant databases)
├── storage/           FileStorage, LocalFileStorage (the chat's files - app.storage.*)
├── mail/              MailSender (SMTP, app.mail.*), MailTemplate (the HTML building blocks under resources/mail/)
├── logging/           RecentLogAppender (the last warnings and errors in memory, appender RECENT in logback.xml)
├── dto/
│   ├── common/        ErrorResponse, VersionResponse
│   ├── tenant/        TenantRequest/-Response, ApiKey*, TenantUserRequest/-Response, UserGroupRequest/-Response
│   ├── account/       AccountResponse, PasswordChangeRequest, LanguageRequest
│   ├── ai/, document/, chat/, monitoring/, filestore/, task/
│   ├── oauth/         GrantView (what a tenant admin sees of a sign-in), SignInRequest/-Response
│   └── api/v1/        TenantSelfResponse
└── security/          Roles, AuthenticationProviderUserPassword (admin and tenant users), PasswordHasher,
                       ActiveTenantUserRule, ApiKeyTokenValidator (ai_ keys), TenantContext, SecretCipher,
                       ProductionSecretsCheck, WamProxy (app.wam.*), TenantUserClaims (a tenant user's JWT),
                       ResponseHeaders (no framing, no sniffing - on every answer), RequestOrigin (the
                       installation's address as the request reached it: Host and X-Forwarded-Proto)
```

### Layers

- **API documentation (Swagger UI `/swagger`)** covers the client software's API (`/api/v1`, tag "API v1", `BearerAuth`)
  and what a frontend of the tenant's own needs for its users (decided 6 November 2026): `/login` (Micronaut Security's,
  through `@OpenAPIInclude` on `Application`), `/login/wam`, `/branding`,
  `/tenant/account`, `/tenant/branding`, `/tenant/chat` (tags "Users - sign-in" and "Users", `UserAuth`) - a contract
  like `/api/v1`, changed only compatibly. Every other controller - admin, tenant admin, `/tenant/ai`, the handbook,
  infrastructure, SPA fallback - carries `@Hidden`; a new one must too. `TenantApiKeySpec` holds the published list.
- **controller/** — REST endpoints, one per resource, `@ExecuteOn(TaskExecutors.BLOCKING)` for anything touching JDBC
  or an AI provider. **Every controller is secured with exactly one role, matching its path** —
  `@Secured(Roles.ADMIN)` for the admin API (`/admin/**`), `@Secured(Roles.TENANT_ADMIN)` for the tenant admins' API
  (`/tenant/admin/**`), `@Secured(Roles.TENANT_USER)` for the rest of the tenant users' API (`/tenant/**`),
  `@Secured(Roles.TENANT)` for the client-software API (`/api/v1/**`), `ROLE_MCP` for the MCP server (`/mcp`: Micronaut MCP's own controller, which carries no `@Secured` - guarded by the `/mcp` entry of `micronaut.security.intercept-url-map`). A tenant-admin controller mirrors the admin's
  for one tenant (same sub-path, same service) and takes the tenant from `TenantContext`. Never `IS_AUTHENTICATED`: it would
  let a tenant into the admin API. Tenant endpoints take the tenant id (and user id) only from
  `TenantContext.tenantId/userId(authentication)`, never from a path, query or body value. Static
  resources, `/login` and swagger are whitelisted in `application.properties` (`micronaut.security.intercept-url-map`).
  `/watchdog` is the anonymous liveness probe the frontend polls, `/branding` the installation's look and
  `/branding/login/<key>` a tenant's for its own login page `/login/<key>` (anonymous too, nothing but name, colour
  and logo; the random key is the check - [branding.md](docs/architecture/branding.md)), `/doc-sites/<token>/**` a handbook's website (anonymous,
  the signed token in the path is the check, every file sandboxed - [doc-sites.md](docs/architecture/doc-sites.md)).
  `GlobalExceptionHandler` turns every error into an
  `ErrorResponse` JSON body (`{message, code, params}` - [i18n.md](docs/architecture/i18n.md) "Error codes") with a
  sensible status; `SpaFallbackController` serves `index.html` for the
  Angular routes (`/login`, `/login/<key>`, `/dashboard/**`, `/app/**`) so a browser reload on a deep link works.
- **Behind a reverse proxy under a sub-path** (e.g. nginx at `/alpdesk-ai/`): `MICRONAUT_SERVER_CONTEXT_PATH` at
  runtime puts every route below it; `SpaFallbackController` writes it into `index.html`'s `<base href>`, and the
  frontend builds its API address from that (`utils/base-path.util.ts`). Never set it while building - the OpenAPI
  processor would bake it into the Swagger page. nginx configuration: `Backend/podman/public/README.md` "Behind a
  reverse proxy under a sub-path".
- **services/** — business logic; controllers stay thin and have no try/catch. Services throw
  `services.common.ServiceException` with a code (`<area>.<rule>`, mandatory, first argument) and an English message
  that end up in `ErrorResponse`: 400 invalid input, 404, 409 business-rule violations, and so on. A new code gets
  `errors.backend.<code>` in every language file of the frontend. **Never answer 403 for a refused action**: the frontend's auth interceptor
  logs out on 401 and 403 (a deactivated tenant user gets 403 on every request), so a 403 must only ever mean "no
  longer allowed" - a refused business rule is 409, a wrong current password 400.
- **entities/** — Java `record`s annotated `@MappedEntity` (Micronaut Data JDBC), one table each, management database
  only (a tenant database is reached with plain JDBC - see tenancy.md).
- **repositories/** — `@JdbcRepository(dialect = Dialect.POSTGRES)` interfaces.
- **dto/** — request/response records exposed over HTTP, kept separate from entities, so a change of the storage
  format never changes the `/api/v1` contract (`TenantApiKeySpec` fails if an entity type is reachable from a tenant
  controller). Switches in requests are `@Nullable Boolean`, never `boolean`: left out on create = the default, on
  update = unchanged.

### Tenant API (`/api/v1`)

What the client software may do is decided per endpoint - a new tenant endpoint needs a conscious decision. Today:
`GET /api/v1/me` (which tenant the key belongs to, and its knowledge areas) and the company's knowledge: `GET
/api/v1/documents`, `POST /api/v1/documents` (multipart field `file`, embedded in the background like an upload in the
frontend; with `externalId` a push replaces the document with that key, with `area` - an area's name - it goes into
that knowledge area - see ai-features.md), `DELETE /api/v1/documents/{id}` and `?externalId=...` - every one of them
only for the company knowledge and the areas assigned to the key (decided 9 October 2026), the chat's files (`POST /api/v1/chat/files`, `GET .../conversations/{id}/files`,
`GET /api/v1/chat/files/{id}/content`, `DELETE /api/v1/chat/files/{id}`), and the chat: `GET /api/v1/models` (the chat models, `usable` = the tenant has a key), `GET /api/v1/software` (the
connected software the key may use, to leave out per question - decided 5 November 2026),
`POST /api/v1/chat` (optionally with a `templateId`; with an API key without history - "Verlauf speichern" off,
decided 7 October 2026 - every question is answered on its own, nothing is stored but the usage, and it may bring text
files, `files: [{name, content}]`, which the assistant searches and reads itself when they are large), `POST
/api/v1/chat/stream` (the same, answered as the chat's server-sent events - decided 13 October 2026), `GET /api/v1/templates`, `GET /api/v1/chat/conversations`,
`GET .../{id}/messages`, `DELETE .../{id}` (all decided 6 October 2026); `/api/v1/chat` uses the reading tools of the
connected software a tenant admin gave the API key (decided 7 October 2026, per key since 25 October 2026 -
`connectionIds`, roadmap point 42). The scheduled tasks
assigned to the key (decided 13 and 21 October 2026): `GET /api/v1/tasks`, `POST /api/v1/tasks/{id}/runs` (202),
`GET /api/v1/tasks/{id}/runs/{runId}` (the findings, not the lines read). The client software's conversations have no user (`user_id` NULL) and belong to the API key that
started them (`api_key_id`, decided 9 October 2026): no user sees them, the API sees no user's, and no key another key's. Not offered: the text extraction itself.

### Security

`Roles` (`ROLE_ADMIN`, `ROLE_TENANT_ADMIN`, `ROLE_TENANT_USER`, `ROLE_TENANT`, `ROLE_MCP` - one of the tenant's MCP keys, `/mcp`
and, with its A2A door, `/a2a` only, `McpKeyTokenValidator`). Micronaut's management endpoints (`/health`, `/beans`, ...)
come in through a dependency and are switched off (`endpoints.all.enabled=false`); `/watchdog` is the liveness probe.
**Sign-in through a WAM proxy** (`app.wam.enabled`, off unless set - [tenancy.md](docs/architecture/tenancy.md)): tenant
users sign in with the user name the proxy sends in a header (`POST /login/wam`) and have no password; someone unknown
waits until the admin assigns the access request to a tenant and the tenant admin approves them; the admin keeps its
password login; every tenant user's request is checked against the header. The app trusts the header completely - the
proxy strips it from outside requests, and the app is reachable through the proxy only.
`AuthenticationProviderUserPassword`: one `/login` for the admin account from config/env and then the tenant users
(PBKDF2 hashes, `PasswordHasher`; the JWT carries `tenantId` and `userId`, a tenant admin's also `ROLE_TENANT_ADMIN`),
no lockout (behind the reverse proxy every
request has the proxy's address). `ActiveTenantUserRule` rejects every request of a tenant user who or whose tenant was
deactivated or deleted, with a token older than a password an admin set, or of a former tenant admin to
`/tenant/admin/**` - although the JWT is still valid - see [tenancy.md](docs/architecture/tenancy.md). `ApiKeyTokenValidator`: accepts `ai_` bearer tokens next to
JWTs, looks the key up by SHA-256 hash (`ApiKeyService`, off the event loop) and yields `ROLE_TENANT` with the tenant id
as attribute; unknown keys and inactive tenants get 401. Only the hash and a display prefix are stored; the plain key
is returned once on creation. `TenantApiKeySpec`, `TenantUserSpec` and `TenantAdminSpec` guard these boundaries - extend them for every new controller or role rule. Database
error messages are logged without the failing row's values (`GlobalExceptionHandler.rootCauseMessage`,
`logServerErrorDetail=false`); a document's content never goes to the log. The AI providers' API keys are stored
encrypted (`SecretCipher`, AES-256-GCM, `APP_SECRET_KEY` - losing it means entering every key again) and never sent out
again (only `keyHint`). PostgreSQL never gets a published port in
production; Caddy serves HTTPS only - never add a plain-HTTP site. Every answer carries `X-Frame-Options: DENY`,
`Content-Security-Policy: frame-ancestors 'none'`, `X-Content-Type-Options: nosniff` and `X-Robots-Tag: noindex,
nofollow`, a streamed one (server-sent events) also `X-Accel-Buffering: no` so an nginx-based proxy passes it on as it
comes (`security.ResponseHeaders`, set by the app so an installation behind its own proxy gets them too). Request size limits (`micronaut.server.*` in
`application.properties`) are 512 MB for a ZIP archive of documents - raise them together; a single document, a chat
file (50 MB) and a logo are checked against their own limits before they are read.

### Database

PostgreSQL 18 with pgvector. The management database's schema is managed by Flyway migrations under
`src/main/resources/db/migration/` — add a new `V<n>__<description>.sql`, never edit an applied one (not even a
comment: Flyway's checksum covers the whole file, and a changed one stops the start of every installation). Applied
means released, in a release tag `v<x.y.z>` (`main` carries the unreleased version too): the migrations of the version
being worked on are one file per set
(`V17__release_2_0_0.sql`, tenant `V8__release_2_0_0.sql`), which grows until the release (decided 5 November 2026). Ids are `BIGINT GENERATED BY DEFAULT AS IDENTITY`, timestamps `TIMESTAMPTZ` mapped to `Instant`, text
`TEXT`, case-insensitive uniqueness via `UNIQUE INDEX ... (LOWER(name))`. Every connection waits at most 10s for a lock
and has idle transactions ended after a minute (`datasources.default.data-source-properties.options`); a lock timeout,
deadlock, serialization failure, no free pool connection or a database briefly out of reach answers 503 "try again"
(`GlobalExceptionHandler`). Constraint violations are told apart by their SQL state: unique 23505 → 409, not null /
foreign key / check → 400.

**Every tenant has a database of its own** (`tenant.database_name`, same server, created with the tenant, dropped with
it - [tenancy.md](docs/architecture/tenancy.md)). Its schema is a second Flyway set under
`src/main/resources/db/tenant/`, applied by `TenantDatabases` to every tenant database on creation and on every start - when the context starts,
before the HTTP server takes requests (`TenantDatabaseMigration` on `StartupEvent`) - the same rules (new
`V<n>__...sql`, never edit an applied one). Nothing in a tenant database refers to another tenant;
whatever the admin manages or bills lives in the management database. Tenant databases are reached with plain JDBC
through `TenantDatabases.dataSource(databaseName)` (Micronaut Data cannot reach databases created at runtime). CREATE
and DROP DATABASE cannot run in a transaction - the tenant service calls them outside of one.

### Blueprint

The tenant users (`entities.tenant.TenantUser`, `repositories.tenant.TenantUserRepository`,
`services.tenant.TenantUserService`, `controller.tenant.TenantUserController`, `dto.tenant.TenantUser*`, spec
`security/TenantUserSpec`) are the reference for a new admin feature end to end, `controller.account` for one of the
tenant users.

## Frontend architecture

Angular 22 standalone app using Angular Material and NgRx.

- **State**: NgRx, one slice per feature under `store/<feature>/` (actions/reducer/selectors/effects), registered in
  `app.config.ts`. Effects call `services/<feature>.service.ts` (thin `HttpClient` wrappers) and dispatch
  success/failure actions with messages from `extractErrorMessage()`; components read state via selectors, never call
  services directly. Every new `load*Failure` action is added to `store/load-failure/load-failure.effects.ts`.
- **components/**: one folder per page. A new admin page gets a lazy child route under `dashboard` in `app.routes.ts`
  and an entry in `navGroups` in `dashboard.component.ts`; a tenant users' page a child route under `app` and an entry
  in `navGroups` in `tenant-app.component.ts`.
- **Look (always)**: light and dark (`utils/color-scheme.util.ts`: system, light or dark, chosen in the shell's user
  menu, kept in the browser). Every colour comes from a token - Material's `--mat-sys-*` and the app's `--app-*` in
  `styles.scss`, all `light-dark()` pairs - never a literal colour or `rgba(0, 0, 0, ...)` in a component: it would be
  unreadable in the dark. The brand colour is `--app-brand` (with `-soft`, `-softer`, `-text`), only as an accent. Both
  areas use one frame, `components/shared/app-shell` (sidebar with brand, menu groups and the user, top bar, below
  960px the sidebar slides in). A page is `<div class="page">` with `components/shared/page-header` (title, a sentence,
  the actions - the main one a `mat-flat-button`) and panels below: the global classes `.app-panel`,
  `.app-panel-head`, `.app-panel-body`, `.app-summary`, `.app-search`, `.app-filter`, `.app-chip` (status with dot:
  ok, warn, err, info), `.app-tag`, `.app-avatar`, `.app-note`, `.cell-main`; no `mat-card` on pages. An empty list
  shows `components/shared/empty-state` with its add button; more than two actions of a table row go into a ⋮ menu.
  A long list pages with `utils/paging.util.ts` (50, 100 or "Alle"; the document list) and has no scroll area of its
  own: the page scrolls, so its sticky column headers stay below the top bar.
  Buttons: main action `mat-flat-button`, others `mat-stroked-button` / `mat-button`, never `mat-raised-button`.
- **Two areas, one login**: `/login` sends the admin (`ROLE_ADMIN`) to `/dashboard` and a tenant user or tenant admin
  (`ROLE_TENANT_USER`, plus `ROLE_TENANT_ADMIN`) to `/app` (`utils/auth.util.ts` `homePathFor`; the roles come from the login response and are
  kept in the auth state). `adminGuard` / `tenantUserGuard` (`guards/auth.guard.ts`) send a user of the other role to
  its own area. Both shells (`components/dashboard/`, `components/tenant-app/`) put their menu into `app-shell`.
- Pages: admin - Mandanten (`tenant-list`: a row opens the tenant's page `tenant-detail`,
  `/dashboard/tenants/:id/:tab` with the tab in the address - Übersicht (usage this month, master data, models),
  Benutzer, Modelle (`tenant-models-dialog`), Firmenwissen, Handbuch, Assistent, Keys (provider, client software, MCP), Software,
  Dateiablagen, Aufgaben (`tasks-panel`; the editor `task-editor` and the run view `task-run` under `.../tasks/:taskId`), Branding (`tenant-branding-dialog`): the shared panels with the tenant's id), Zugangsanfragen (`access-request-list`,
  only with WAM on), KI-Modelle, Globale API-Keys, Verbrauch, Anwendungslog (`application-log-list`, with "Tagesbericht
  jetzt senden"); tenant users - Chat, Handbuch (`handbook-page`: the websites in an iframe, a tenant admin keeps them there), Konto (with the card how to connect Claude Code or Copilot); tenant admins also
  Firmenwissen (`document-list`: the knowledge sources above the documents), Assistent (`assistant-page`: instructions, settings, chat templates), Benutzer (`tenant-users-page`: the users and their groups), API-Keys (`tenant-keys-page`: provider and client
  software keys, the MCP keys with the Claude Code command, the claude.ai/Cowork connector and Copilot's `mcp.json`), Software (`software-page`: the
  connected software's tools and each group's level), Dateiablagen (`file-stores-page`: SFTP and FTPS servers), Aufgaben
  (`tasks-page`, only when `account.tasksAllowed`: the scheduled tasks, their YAML in CodeMirror with the graph beside
  it, their runs - [tasks.md](docs/architecture/tasks.md) "The frontend"), Verbrauch - routes with `tenantAdminGuard`, `navGroups` by role.
- **The admin and the tenant admins share their building blocks**: `components/shared/document-panel`,
  `knowledge-sources-panel`,
  `assistant-panel`, `doc-sites-panel`,
  `tenant-users-panel`, `user-groups-panel`, `api-keys-panel`, `mcp-key-panel`, `mcp-connections-panel`, `file-stores-panel`, `tasks-panel` and `provider-key-list` take a
  `TenantScope` (`"self"` = the tenant admin's own
  tenant via `/tenant/admin`, a tenant's id = the admin via `/admin/tenants/{id}`, `utils/tenant-scope.util.ts`); the
  slices `document`, `docSite`, `knowledgeArea`, `knowledgeSource`, `tenant-user`, `userGroup`, `mcp-key`, `mcp-connection`, `fileStore`, `task`, `assistant` and the tenant's API keys
  hold one tenant at a time and start with `scope: null`, so a result arriving after a logout is ignored, not shown to
  the next login. Route data such as `scope`
  reaches a page as input (`withComponentInputBinding`).
- **Chat stream**: `services/chat.service.ts` reads `POST /tenant/chat/stream` through HttpClient (`observe: 'events'`,
  `reportProgress`, text) so the interceptors still apply, and sends `Accept: text/event-stream, application/json`;
  `utils/sse.util.ts` cuts the progress text into events, `utils/chat-stream.util.ts` types them. Assistant messages are
  Markdown (`utils/markdown.util.ts`, `marked`, links in a new tab) bound with `[innerHTML]` - Angular's sanitizer
  cleans them; never `bypassSecurityTrust*`. Links other than http(s)/mailto stay text (models make up links to files);
  images, media and other content the browser loads by itself are left out (`markdown.util.ts`). Code blocks carry their
  language and a copy control (a span - the sanitizer removes buttons; the chat's host listens for it) and are
  highlighted by highlight.js in the language the model names (`utils/code-highlight.util.ts`: about 60 registered
  languages, no guessing), coloured by the `--app-code-*` tokens.
- **Chat files**: the paperclip uploads to the open conversation (or starts one), the strip above the input lists the
  conversation's files, an answer shows the files it created (`components/chat/chat-file-chip.component.ts`).
  Downloads go through HttpClient as a blob (the auth interceptor applies) and `utils/download.util.ts`. An image the
  model created shows below its answer (`chat-image-preview.component.ts`, large in `chat-image-dialog`): loaded the
  same way, kept as an object URL in the chat slice and released by `ChatEffects` when the conversation is left.
- **Form dialogs stay open until saved** (`components/shared/dialog-save.ts`). The page opens the dialog with
  `DialogSave.open(component, config, {action, success, failure})`, and the dialog saves with
  `readonly saver = dialogSave<R>()` + `saver.submit(value)`, disabling its save button while `saver.saving()` is true.
  The dialog closes on the success action; on the failure action it stays open with everything entered. Never close a
  form dialog with its value and dispatch in `afterClosed()`: a 400/409 from the backend would throw the input away.
- **WAM**: the auth slice holds `wamEnabled` (loaded at start and on the login page, `GET /login/wam`); with it the
  login page signs in through the proxy (waiting and logged-out views, the password form behind "Als Administrator
  anmelden"), and no password field is shown anywhere. A logout by the user marks the tab (`AuthService.markSignedOut`).
- **Languages** ([i18n.md](docs/architecture/i18n.md)): Transloco at runtime, the files in `src/i18n/` (bundled as
  chunks of their own by `i18n/i18n.loader.ts`), English the default. Templates use the `transloco` pipe and
  transloco-locale's `translocoDate` / `translocoDecimal` (not Angular's `date` / `number`); code translates at the
  moment of the event (`TranslocoService.translate`), effects through `i18n/error-messages.service.ts` (`ErrorMessages`:
  the backend's error code translated, its message, or the fallback key). Utils return keys, not sentences. The
  `language` slice speaks the language (`chooseLanguage` from the shell's menu, saved to the tenant user's account;
  `useLanguage` at start and after a logout - the browser's language, never stored - and from the account). Specs
  that need texts import `i18n/i18n.testing.ts`.
- **Logout** resets every slice (`store/logout.meta-reducer.ts`, except version and backend health): nothing of the
  session stays for the next login in the same tab, and every open dialog is closed. An effect whose requests outlive
  a click - a streamed answer, queued uploads, a ZIP import, queued deletions and moves - ends with `endedByLogout` (`store/session.operator.ts`,
  also at the switch to another tenant): a request takes the token of the moment it starts, so a queued one would go
  out with the next user's. Another tab of the browser reloads when the shared token changes (`otherTabSession$`).
