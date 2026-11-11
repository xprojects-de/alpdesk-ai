# Contributing

Good to have you here. Zirmo is made for companies of every trade, and contributions that come
from their practice are especially welcome: a provider or model that behaves differently than expected, a file
type that should be read, a tool your company's software needs, or simply a bug you ran into.

## What you need

| | |
|---|---|
| **JDK 25** | Required, as `JAVA_HOME`. The backend is built for Java 25; `/usr/libexec/java_home -V` (macOS) lists the installed JDKs. |
| **Node.js 24** | For the frontend. |
| **Podman** (or Docker) | The Spock specs start their own PostgreSQL with pgvector through Testcontainers; the local development database runs in it too. With Podman, the Podman machine's socket must be the Docker socket Testcontainers uses. |
| **ffmpeg** | Optional: audio and video in the chat need it (e.g. `brew install ffmpeg`). Without it `ChatMediaSpec` is skipped. |

No AI provider account is needed for the specs: every model call goes to a fake (`services.ai.FakeAiModels`). To try
the running app you need a key of a provider (Anthropic, OpenAI, Gemini) or a model server of your own, e.g. Ollama
(`Backend/podman/docker-compose.ollama.yml`).

## Build and run

```bash
# Backend (folder Backend/)
./gradlew devDbStart     # PostgreSQL + pgvector on 127.0.0.1:15433 (devDbStop / devDbDelete)
./gradlew run            # API on http://localhost:19100, login alpdesk / alpdesk

# Frontend (folder Frontend/)
npm ci
npm start                # http://localhost:4300, talks to the backend on :19100
```

The dev server must run on **exactly port 4300** - the backend's CORS configuration allows only that origin.

A full production build is frontend first, then backend: `npm run deploy` writes `Frontend/dist/alpdesk-ai/browser`,
`./gradlew copyFrontend` copies it into `Backend/src/main/resources/public`, and `./gradlew optimizedJitJarAll` builds
the deliverable, the AOT-optimised fat jar `Backend/build/libs/alpdesk-ai.jar`. The built frontend is **not checked
in** (`.gitignore`); the release workflow builds it itself.

Container images and the local stack: [Backend/podman/README.md](Backend/podman/README.md); configuration and production
setup: [Backend/podman/public/README.md](Backend/podman/public/README.md).

## Tests

```bash
cd Backend && ./gradlew test                                   # Spock suite, own Testcontainers database
cd Backend && ./gradlew test --tests "de.alpdesk.security.TenantApiKeySpec"
cd Frontend && npm test                                        # Vitest
cd Frontend && npm run lint                                    # ESLint + Prettier
```

**Every backend change comes with Spock specs** - a feature or fix is not done without them:

- the happy path through the HTTP API (admin, tenant user, client software where they exist);
- every validation rule (400), preferably as a `where:` table;
- not found and tenant isolation: another tenant's ids answer 404, one tenant never sees another's documents, chats or
  keys;
- roles: extend `TenantApiKeySpec` for every new controller;
- business rules (409) and state transitions.

A bug fix starts with a spec that fails without the fix. AI providers are never called from the specs. Each spec
creates its own tenant.

On the frontend, logic without a template (reducers, utils, validators, RxJS operators) gets a Vitest spec next to its
file.

> **No real data in the repository** - not in specs, fixtures, screenshots or comments. Company documents, chat
> histories, user names and keys are personal or confidential data and stay in the Git history even when the file is
> deleted later. Specs use synthetic data; keep real test data under `local-data/` (ignored) or outside the
> repository. See [docs/privacy.md](docs/privacy.md).

## Conventions

The full set is in [CLAUDE.md](CLAUDE.md) - written for AI assistants, but simply the most complete description of the
architecture and the rules, and just as readable for people. The detailed rules per area are in
[docs/architecture/](docs/architecture/README.md); a change updates the affected file there in the same pull request.
The ones that surprise most:

**Language.**

- **The frontend is English and German, English the default.** Everything the user sees - labels, buttons, dialogs,
  tooltips, validation and error messages - is a translation key in `Frontend/src/i18n/en.json` and `de.json`, in
  both files in the same change (`ng lint` refuses static text in templates, `i18n.spec.ts` a key missing in a
  language). The German texts address the user formally ("Sie").
- **Everything else is English** - identifiers, comments, log messages, the backend's error texts, API paths and DTO
  fields, the database schema, the docs, commit messages.

New UI texts as keys in both languages, new identifiers English - and never one translated into the other's territory.

**Industry-neutral.** Zirmo is not a hotel product. Code, prompts, tool descriptions, UI texts and examples speak
of the tenant, the company, its documents and its people - never of hotels, guests, rooms or a particular trade. What
a trade needs on top comes as configuration or as a tool of its own.

**Security boundaries.** Every controller is secured with exactly one role matching its path (`/admin/**`,
`/tenant/admin/**`, `/tenant/**`, `/api/v1/**`). Tenant endpoints take the tenant only from the authentication
(`TenantContext`), never from a path, query or body value. A refused business rule answers 409, never 403 - the
frontend logs out on 403.

**Code style.** Code is written to be read: names say what, comments say why; small, flat methods; no abstraction in
advance and no optimisation without a real case; plain Java where it is clearer. A new class looks like its neighbours
in the same layer.

**Frontend formatting** belongs to Prettier, enforced through ESLint - `ng lint` is the one entry point:

```bash
cd Frontend
npm run lint:fix     # formats and fixes what ESLint can
npm run lint         # must pass
npm run build        # after a --fix over many files: --fix changes code, not only formatting
```

## Pull requests

Fork the repository (or, with write access, push a branch), branch off `main`, and open the pull request against
`main` - the maintainer works the same way. The branch is deleted after the merge.

- One thing per pull request. Two small ones are better than one that formats on the side.
- Describe **why** the change is needed - with the provider, model or workflow it came from.
- `./gradlew test` green, `npm run lint` and `npm test` clean. The checks of `.github/workflows/pr-checks.yml` run on
  every pull request.
- A new or updated dependency updates [THIRD-PARTY.md](THIRD-PARTY.md). `./gradlew checkLicense` fails when a backend
  library brings a license not yet allowed in `Backend/config/allowed-licenses.json` - say why it is fine in the pull
  request.

By contributing you agree that your contribution is licensed under the project's [MIT license](LICENSE).

## Questions

Open an issue, also for "is this meant to be like this?". Not for security issues - see [SECURITY.md](SECURITY.md).
