# Zirmo

*A product of Alpdesk.*

**An AI assistant that knows a company's own documents - for companies of every trade: services, crafts, industry.
Several companies on one installation, each with a database of its own, with the AI provider of your choice or models
on your own servers.**

[![PR Checks](https://github.com/xprojects-de/alpdesk-ai/actions/workflows/pr-checks.yml/badge.svg)](https://github.com/xprojects-de/alpdesk-ai/actions/workflows/pr-checks.yml)
[![Website: zirmo.dev](https://img.shields.io/badge/Website-zirmo.dev-49732a.svg)](https://zirmo.dev/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Backend: Micronaut / Java 25](https://img.shields.io/badge/Backend-Micronaut%20%2F%20Java%2025-blue.svg)](Backend)
[![Frontend: Angular 22](https://img.shields.io/badge/Frontend-Angular%2022-red.svg)](Frontend)

The name comes from the Zirbe, the Swiss stone pine: the nutcracker hides its seeds in autumn and finds them again under
the snow - Zirmo keeps a company's knowledge and finds it again when someone asks.

A Micronaut (Java 25) backend with the Angular frontend baked in, backed by PostgreSQL with pgvector, run as a
container behind Caddy. The user interface is English and German (English the default), every text a translation key
in `Frontend/src/i18n/en.json` and `de.json`; code and documentation are English.

## What it does

- **Tenants with their own people.** Every company (tenant) gets its own database for its documents, embeddings and
  chat history ([tenancy](docs/architecture/tenancy.md)). Its employees log in and chat; its tenant admins keep users,
  keys, the company's knowledge, the assistant's instructions and an eye on the usage. The operator (admin) manages
  the tenants, the model catalog and prices.
- **The company's knowledge in the chat.** Documents - one by one, as a ZIP archive with folders, or pushed by the
  company's software - are read (PDF, DOCX, ODT, RTF, TXT, Markdown, HTML, PPTX, XLSX, ODS, CSV; images and scanned
  pages through an image model), embedded and found by every user in the chat without seeing the list
  ([AI features](docs/architecture/ai-features.md)).
- **A chat that works with files.** Files in the conversation (text, images, scans, audio, video), files created as
  answers (DOCX, XLSX, PDF, CSV, ...), chat templates, and an agent loop with tools.
- **Any provider, or your own models.** Anthropic, OpenAI, Gemini - with the tenant's own keys or the operator's
  (then billed) - or models on own hardware through Ollama or any OpenAI-compatible server (vLLM, LM Studio, Mistral).
- **Connected to the company's software.** The client software uses the API at `/api/v1` with the tenant's API key
  (documented at `/swagger`). The chat can call the tools of the company's software over MCP - reading ones directly,
  changing ones only after the user's decision - and Claude Code or Copilot can ask the assistant through the tenant's
  own MCP server ([MCP](docs/architecture/mcp.md)).
- **Your own code's knowledge for Claude Code and Copilot.** How-tos and API overviews of the company's frameworks
  as Markdown, pushed from the CI, so the coding assistants write code the company's way instead of guessing
  ([teaching the assistant your code](docs/code-knowledge.md)).
- **Single sign-on** through a WAM proxy that passes the signed-in person on in a header (optional).
- **Branding** per installation and per tenant: name, colour and logo ([branding](docs/architecture/branding.md)).

## Getting started

### Running it - for operators

An installation runs the released images from ghcr.io (`ghcr.io/xprojects-de/alpdesk-ai:<version>`) with the files in
[Backend/podman/public/](Backend/podman/public/) - compose files, Caddyfile, `.env` template. The guide - first
installation, updates, backups, a reverse proxy under a sub-path, single sign-on, code execution, Kubernetes - is
[Backend/podman/public/README.md](Backend/podman/public/README.md). Every release is a tag `v<version>` with its jar
on the [releases page](https://github.com/xprojects-de/alpdesk-ai/releases); what changed is in
[CHANGELOG.md](CHANGELOG.md).

In short, on a Linux server with Podman and a domain pointing at it:

```shell
git clone --branch v<version> https://github.com/xprojects-de/alpdesk-ai.git
cd alpdesk-ai/Backend/podman/public
cp .env.example .env && chmod 600 .env    # then replace every CHANGE_ME in .env
podman-compose -f docker-compose.prod.yml up -d
```

For Python and shell in the chat, a company gets a sandbox of its own (`ghcr.io/xprojects-de/alpdesk-ai-sandbox`, one
container per tenant, started from `docker-compose.tenant-sandbox.yml`); its address and token are entered at the
tenant on the page "Tenants" - see the guide's "Code execution".

### Developing it - for contributors

Needs a JDK 25 as `JAVA_HOME`, Node.js 24 and Podman (or Docker).

```shell
cd Backend
./gradlew devDbStart     # PostgreSQL + pgvector on 127.0.0.1:15433 (devDbStop / devDbDelete)
./gradlew run            # API on http://localhost:19100, login alpdesk / alpdesk (development only)
./gradlew test           # Spock specs with their own Testcontainers database

cd ../Frontend
npm ci
npm start                # http://localhost:4300, talks to the backend on :19100
```

Then log in as admin, add a model to the catalog ("AI models", "KI-Modelle") with a provider key, create a tenant and a
user, and chat. Without a provider account, a local Ollama works too (`Backend/podman/docker-compose.ollama.yml`).

API documentation of `/api/v1`: http://localhost:19100/swagger

For Python and shell in the chat, start the sandbox next to it (built from the working tree, Podman):

```shell
cd Backend
./gradlew sandboxStart        # on 127.0.0.1:19180 (sandboxStop); videoSandboxStart instead for videos (ffmpeg, pose estimation)
```

and enter `http://localhost:19180` with the local token of `Backend/podman/docker-compose.sandbox.yml` at a test tenant
("Sandbox addresses", "Sandbox token" on the page "Tenants").

Releases are built by hand with the GitHub workflow **Build Release** (`.github/workflows/build.yml`) after raising
`app.version` in `Backend/src/main/resources/application.properties`.

## Security and privacy

Zirmo handles company documents and employees' chats and sends them to the AI providers you configure. Before
running an installation for others, read:

- [SECURITY.md](SECURITY.md) - what the software assumes (HTTPS only, the database never reachable from outside, your
  own secrets), what it deliberately does not do, and how to report a vulnerability;
- [docs/privacy.md](docs/privacy.md) - what is stored where, what leaves the server, who is responsible under the GDPR,
  and deleting.

## Repository

| Folder | Contents |
|---|---|
| `Backend/` | Micronaut REST API, Gradle build, Flyway migrations, container setup (`podman/`) |
| `Frontend/` | Angular SPA (Angular Material, NgRx), copied into the backend's jar |
| `docs/architecture/` | The rules and decisions per area - binding for every change |
| `docs/roadmap.md` | What is to come, discussed point by point |
| `docs/code-knowledge.md` | How a company teaches the assistant its own code - Markdown to write, a sync script, CI |
| `website/` | The project's web page - `index.html` and its screenshots, uploaded to a web space by hand |

[CLAUDE.md](CLAUDE.md) holds the conventions for every change - written for AI assistants, and the most complete
description of the architecture for people too.

## Contributing

Issues and pull requests are welcome - especially from real use: a provider or model that behaves differently, a file
type that should be read, a tool your company's software needs. [CONTRIBUTING.md](CONTRIBUTING.md) explains the
prerequisites, conventions and the way to a pull request; the [code of conduct](CODE_OF_CONDUCT.md) applies.

Please: **no real data** in issues, pull requests or specs - no company documents, chat histories, names or keys.

## Support

Zirmo is free. If it helps your company and you want to give something back, I'm happy about
[a coffee](https://paypal.me/BenjaminHummel560). It is not necessary.

## License

[MIT](LICENSE) - © Benjamin Hummel

The bundled libraries, fonts and icons are under their own licenses; the complete list is in
[THIRD-PARTY.md](THIRD-PARTY.md), the required notices in [NOTICE](NOTICE).
