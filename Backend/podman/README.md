# Zirmo - Podman

Everything needed to build the backend (with the Angular frontend baked in) as a container image and run it next to
its PostgreSQL database (with pgvector) locally with Podman. Running an installation in production: "Running an
installation".

The Dockerfile packages what is already on disk, it does not build the frontend. Build it once before building the
image:

```shell
cd Frontend && npm run deploy
cd ../Backend && ./gradlew copyFrontend
```

The image is Eclipse Temurin's Java 25 runtime on Ubuntu 26.04 (`eclipse-temurin:25-jre-resolute`, since 11 October
2026 - 24.04 before) and runs as the fixed user 999. It installs **ffmpeg** (Ubuntu's package, 8.0 - it converts
uploaded audio and video for the AI models); with its libraries the image has about 890 MB (865 MB on 24.04). Decided 6 October 2026: Ubuntu's package, not a smaller static build - ffmpeg
reads uploaded, i.e. foreign files, and the package brings Ubuntu's security fixes with every image build (a release),
where a static build would have to be watched and raised by hand.

The image must be built with **`Backend/` as build context** (it `COPY . .`s the Gradle project). `podman compose`
resolves that from the compose file; for manual builds run from `Backend/` with `-f podman/Dockerfile`.

## Running an installation

The guide for operators - installation, update, configuration, backups, own model servers, a reverse proxy under a
sub-path, WAM, code execution, Kubernetes - is [public/README.md](public/README.md), next to the files it describes, all
in [public/](public/): `docker-compose.prod.yml`, `docker-compose.proxy.yml`, `docker-compose.tenant-sandbox.yml`,
`docker-compose.embeddings.yml`, `docker-compose.reranker.yml`, `docker-compose.reranker-gpu.yml`, `Caddyfile`,
`.env.example` and `.gitignore`. An installation works in that folder: `.env`, `sandboxes/` and `./pgdata` lie next to
the compose files and are ignored by git.

## Embeddings with a local Ollama

The company's knowledge needs an embedding model. Where the documents must not go to a cloud provider and the model
server offers none (an OpenAI-compatible model farm often serves chat models only), a local Ollama with an embedding
model does it: `ollama/Dockerfile` bakes the model into the image (default `bge-m3`: multilingual, 1024 dimensions),
`docker-compose.ollama.yml` starts it as a project of its own.

```shell
podman compose -f docker-compose.ollama.yml up -d --build      # builds the image (pulls the model) and starts it
podman compose -f docker-compose.ollama.yml ps                  # "healthy" once the model is there
curl -s http://localhost:11434/api/embed -d '{"model":"bge-m3","input":"Kalibrierung"}' | head -c 200
```

- **Build where the internet is reachable** - the model is pulled while the image is built, the running container
  needs no internet. Behind a proxy podman passes `HTTP_PROXY`/`HTTPS_PROXY` to the build. A machine without internet
  gets the image as a file: `podman save -o ollama-embeddings.tar localhost/alpdesk/ollama-embeddings:latest`, copy it,
  `podman load -i ollama-embeddings.tar`. Another model: `EMBEDDING_MODEL=nomic-embed-text podman compose -f
  docker-compose.ollama.yml build`.
- **The catalog entry** (page "AI models" - "KI-Modelle"): provider "Ollama", kind "Embedding", model name `bge-m3`,
  dimension `1024`, no key, and the address:
  - backend started on the same machine (`./gradlew run`, the jar): `http://localhost:11434`, with
    `APP_AI_PLAIN_HTTP_HOSTS=localhost`;
  - backend in the container stack: connect the Ollama container to the stack's network once,
    `podman network connect alpdesk-ai_default alpdesk-ai-ollama` (`alpdesk-ai-local_default` for the local test
    stack), then `http://alpdesk-ai-ollama:11434` with `APP_AI_PLAIN_HTTP_HOSTS=alpdesk-ai-ollama` in `.env`.
- Plain HTTP is fine here: Ollama is published on 127.0.0.1 only or reached inside the stack's network - never publish
  it on all interfaces, it has no keys.
- Then the tenant (or the admin) chooses it as the tenant's embedding model; changing it later embeds every document
  again.
- It runs as any user but root (10001 by default) on a read-only root: the model lies in
  `/usr/share/ollama/models`, `/tmp` must be writable (Ollama writes a key of its own there at every start). In
  Kubernetes `/tmp` is an emptyDir, the probe HTTP `/api/tags` on port 11434, and the model needs its memory (bge-m3
  well over 1 GB).
- **Published** by the workflow **Build Embeddings Image** (`.github/workflows/build-embeddings.yml`, started by hand,
  not part of a release - the model changes rarely) as `ghcr.io/xprojects-de/alpdesk-ai-embeddings:latest`, checked
  as it runs and replaced by every run; every release and pre-release tags that `latest` with its version (no build,
  a copy on ghcr.io - it fails while there is no `latest`), and the public `docker-compose.embeddings.yml` names that
  tag (public/README.md "Embeddings with a local Ollama"). An installation that cannot build the image (no internet, a proxy in the way)
  pulls it instead. The previous image stays in the package as an untagged version until deleted there.

## Reranking with a local reranker

The knowledge search's reranker (docs: ai-features.md "Reranking") runs on Hugging Face's official TEI image - we
build no image of it (decided 30 October 2026: it is optional, and its GPU images cannot be built or checked on
GitHub's runners). The public `docker-compose.reranker.yml` (CPU) and `docker-compose.reranker-gpu.yml` (NVIDIA)
start it in the app's network; locally, for a backend started on this machine:

```shell
podman run -d --name alpdesk-ai-reranker -p 127.0.0.1:19185:8080 -v alpdesk-ai-reranker-models:/data \
  ghcr.io/huggingface/text-embeddings-inference:cpu-arm64-1.9.4 \
  --model-id BAAI/bge-reranker-v2-m3 --revision 953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e \
  --port 8080 --max-batch-tokens 2048            # cpu-1.9.4 on x86_64; first start downloads 2.3 GB
curl -s http://localhost:19185/rerank -H 'Content-Type: application/json' \
  -d '{"query":"Wie oft wird der Kessel gewartet?","texts":["Die Kantine öffnet um acht.","Der Kessel wird einmal im Jahr gewartet."]}'
```

- **The catalog entry**: provider "TEI server (reranker)" ("TEI-Server (Reranker)"), kind "Reranker", model name
  `bge-reranker-v2-m3`, price 0, no key, the address `http://localhost:19185` with
  `APP_AI_PLAIN_HTTP_HOSTS=localhost`. Assign it to the tenant; switch it on under "Company knowledge"
  ("Firmenwissen").
- The model needs about 3.5 GB of memory (measured 3.3 GB on Apple Silicon) - a Podman machine of 2 GB is too small
  (`podman machine set --memory 8192`). TEI warms up with its largest batch: `--max-batch-tokens 2048` keeps that small
  (at the model's 8192 the container was killed); a longer text is cut to it.
- **Slow on a CPU**: about 1.1 s per candidate on Apple Silicon (45 s for 40) - hence the default of
  `APP_SEARCH_RERANK_CANDIDATES` 10 (2 to 40); with a GPU 20.
- **Measure** with and without it: `RetrievalEvaluationSpec` with `EVAL_RERANK_URL=http://127.0.0.1:19185` (and
  `EVAL_OLLAMA_URL` for a local bge-m3, or `EVAL_OPENAI_KEY`); `APP_SEARCH_RERANK_CANDIDATES` for another number, with
  `--rerun` (Gradle does not see an environment variable change).
- Checked on 30 October 2026 (cpu-arm64): read-only root, every capability dropped, and a start without network once
  the model is in the volume.

## Local testing

```shell
podman machine start                 # macOS only
podman compose up -d --build         # from this folder
podman compose logs -f alpdesk-ai    # wait for "Startup completed"
podman compose down
```

Open **http://localhost:19100** and log in with `alpdesk` / `alpdesk`. `podman compose down -v` also deletes the local
database volume. The stack's project is named `alpdesk-ai-local`. It builds and starts one sandbox for code execution
too (`sandbox/Dockerfile`, a published local token): enter `http://alpdesk-ai-sandbox:8080` and the token of
`docker-compose.yml` at a test tenant on the page "Tenants" ("Mandanten").

For development - the backend started from Gradle or the IDE against a database in Podman - `docker-compose.dev.yml`
starts only PostgreSQL (`./gradlew devDbStart`, 127.0.0.1:15433), see [../../README.md](../../README.md).

## Release

The workflow **Build Release** (`.github/workflows/build.yml`, started by hand) builds everything from one commit,
never from a working tree. Before it: the section `## [<app.version>] - unreleased` of `CHANGELOG.md` gets the
release's date instead of "unreleased" - the workflow's first step (`.github/scripts/check-changelog.sh`) refuses a
version without its dated section, before anything is built or pushed. It runs the tests, checks that the jar holds the frontend and
the AOT configurer, starts it (`.github/scripts/smoke-test-jar.sh`) and builds the images from exactly that jar
(`Dockerfile` target `release`) - for `linux/amd64` and `linux/arm64` (an x86 server, an ARM server, a Raspberry Pi 5),
one job per architecture, both joined under one tag (decided 24 October 2026). An uncommitted change, a red test or a
frontend not copied in cannot reach a server that way. It publishes:

- the GitHub release `v<version>` of this repository with the jar alone (`alpdesk-ai-v<version>.jar`);
- the images `ghcr.io/xprojects-de/alpdesk-ai:<version>`, `ghcr.io/xprojects-de/alpdesk-ai-sandbox:<version>` and
  `...-sandbox-video:<version>`, each a manifest list of `<version>-amd64` and `<version>-arm64`, pushed with the
  workflow's `GITHUB_TOKEN`. No `latest`: an installation names its version. The label
  `org.opencontainers.image.source` names this repository. The arm64 images are built and started on the x86
  runner under QEMU, which carries no seccomp filter: there the sandboxes are checked for starting and `/health` only,
  `sandbox/selftest.py` runs natively on amd64. **The selftest on arm64 is run by hand before a release that changed
  the sandbox** - on Apple Silicon the Podman machine is arm64: `./gradlew sandboxStart` (or `videoSandboxStart`) in
  `Backend/`, then from the repository root
  `python3 Backend/podman/sandbox/selftest.py http://127.0.0.1:19180 local-sandbox-token-change-me-local-sandbox-token`;
- nothing else: the installation's files (`public/`) are part of the repository, their image tags already the
  version (`.github/scripts/check-compose-files.sh` compares them with `app.version` - in the pull-request checks and
  as the workflow's first step - and `docker-compose.proxy.yml`, the prod file without Caddy, with the prod file's app
  settings). GitHub adds the tag's source code to the release by itself. Re-running for an unchanged version replaces
  the jar and the images; an existing tag is never moved.

Set up once:

- after the first release, every package on ghcr.io public - app, sandbox, video sandbox, embeddings
  (github.com/xprojects-de?tab=packages → the package → "Package settings" → "Change visibility"). Should a later push
  be refused, give this repository write access under "Manage Actions access" of the package;
- "Private vulnerability reporting" on (Settings → Security), which `SECURITY.md` points to.

**Release candidates and dev drops** - to try a version on a server before releasing it: start **Build Release** with
a label (`rc.1`, `dev.2`; letters and digits separated by dots). It publishes everything above under
`<app.version>-<label>` (`2.0.0-rc.1`) - the images (the embeddings image tagged too), the jar, the tag
`v2.0.0-rc.1` and its GitHub release, marked pre-release - with three differences: `app.version` stays as it is (only
the jar of the run carries the label, so the running app shows it), `CHANGELOG.md` needs no dated section, and the
compose files keep naming the release's version (`2.0.0`). The tests may be left out for a pre-release (a quick dev
drop), the smoke test runs always. On the test server: `git fetch --tags && git checkout v2.0.0-rc.1`, the tags
`:2.0.0` in the compose files set to `:2.0.0-rc.1` by hand, then update as public/README.md "Update" says. **The test
server's database is a pre-release's database**: the unreleased version's migrations are one file that grows until the
release (`V<n>__release_<version>.sql`), and Flyway refuses to start when an applied one changed -
after a pre-release whose migration changed since, or before the release itself, the test server starts with an empty
database (or one restored from a backup of a release). The pre-releases stay in the repository and on ghcr.io
until deleted there by hand.

### Building the image by hand

Only to try a release's jar outside the workflow: download it (`gh release download v<version> -p '*.jar' -D
podman/release`), then build for the server's architecture - `linux/amd64` or `linux/arm64` (run from `Backend/`):

```shell
podman build --platform linux/amd64 -f podman/Dockerfile --target release \
  --build-arg RELEASE_JAR=alpdesk-ai-v<version>.jar -t localhost/alpdesk/alpdesk-ai:<version> podman/release
```

`podman/release/` is the build context of this image (the jar alone) and gitignored; `.dockerignore` keeps it out of
the other builds. The local test stack (`docker-compose.yml`) builds the target `from-source` instead - from the
working tree, without the tests, for trying things out only.
