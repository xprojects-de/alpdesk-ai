# Security

## Reporting a vulnerability

Please **do not open a public issue**. Use the button **"Report a vulnerability"** on the *Security* tab of
[xprojects-de/alpdesk-ai](https://github.com/xprojects-de/alpdesk-ai/security) instead - it opens a private channel
only the maintainer sees. Alternatively by e-mail to `infos@x-projects.de`.

Helpful in a report: the affected version, what you did, what happened and what you expected. A way to reproduce it is
worth gold. Please test against an installation of your own, never against someone else's.

This project is maintained by a single developer. I answer as soon as I can, but cannot promise a fixed response
time. Feel free to ask again if nothing came back after two weeks.

Especially welcome are findings about the boundaries the design depends on: one tenant reaching another tenant's
documents, chats, keys or database; a tenant user reaching the tenant admins' or the admin's API; the client software's
API key or the MCP key reaching more than its endpoints; a provider key leaving the server in plain text; a prompt
injection in a document or a tool result that gets data out or runs a changing tool without the user's decision.

## What the software assumes

Alpdesk AI is a server application run by an **operator** (the admin) for several companies (tenants), reachable over
the internet or an intranet. The design relies on the following - an installation that does not meet them is not
protected by the software:

- **HTTPS only.** The production stack puts Caddy in front of the app (the installation's `Caddyfile`), which serves
  HTTPS and redirects plain HTTP. Never add a plain-HTTP site or publish the app's port 19100 directly: passwords,
  tokens and API keys would travel unencrypted. The app itself answers every request with `X-Frame-Options: DENY`,
  `Content-Security-Policy: frame-ancestors 'none'` and `X-Content-Type-Options: nosniff`: no page of it can be framed
  by another site (clickjacking), and no file it serves is sniffed into a script - whatever proxy is in front.
- **PostgreSQL is never reachable from outside.** It gets no published port in production; every tenant's database
  lives on it.
- **The secrets are the installation's own.** Outside the environments "dev" and "test" the app refuses to start
  without `APP_USERNAME`, `APP_PASSWORD`, `APP_SECRET_KEY` and `JWT_GENERATOR_SIGNATURE_SECRET` (not empty, not
  `CHANGE_ME`, not a value from this repository, the password at least 12 and the JWT secret at least 32 characters).
  The values visible in `application.properties` (`alpdesk` / `alpdesk`, a fixed JWT secret) are for local
  development only - they are not the defaults of an installation.
- **A leaked admin password is changed together with `JWT_GENERATOR_SIGNATURE_SECRET`.** The admin's token is valid
  for a day and nothing on the server ends it: a new `APP_PASSWORD` alone leaves whoever logged in with the old one
  inside until then. A new JWT secret ends every session at once (tenant users log in again too).
- **`APP_SECRET_KEY` is kept safe and backed up.** It encrypts the stored AI provider keys and the keys of connected
  software and of own model servers (AES-256-GCM); whoever has it and a database dump has those keys.
- **Sign-in through a WAM proxy** (`APP_WAM_ENABLED`, off by default) trusts the user header completely. With it on,
  the proxy must strip that header from every request from outside, and the app must be reachable through the proxy
  only - otherwise anyone can sign in as anyone.
- **Code execution runs in a container of the tenant's own, never in the app's and never in another tenant's.** The
  chat's model may run Python and shell commands (`run_python`, `run_shell`) for a tenant whose sandbox the admin
  entered - its addresses and token at the tenant (the image `alpdesk-ai-sandbox`, README.md "Code execution" of the
  installation guide). This holds for every question of the tenant, also those of its client software
  (`/api/v1/chat` and `/api/v1/chat/stream`, API key) and of its MCP key (`ask_assistant`): whoever holds one of its keys can have code run in
  its sandbox - there is no switch per key. Never give two tenants the same
  instance - the app refuses an address another tenant already has, but not another name for the same container: the
  container is what keeps them apart; within it a session's cleanup keeps the tenant's users apart. A
  sandbox holds no secret but its token (`SANDBOX_TOKEN`, at least 32 characters, one per tenant; the app stores it
  encrypted and never sends it out again), and must be reachable by the app only and reach nothing itself - never
  publish its port. On Kubernetes a NetworkPolicy without egress does that. With Compose the `sandbox` network of
  `docker-compose.prod.yml` (`internal: true`) keeps the internet out, but it is one network for the app's unproxied
  port and every tenant's sandbox: there, only the sandbox's seccomp filter (no network socket for a session's
  program) keeps code from reaching the app or another tenant's instance. It needs no root or capabilities: run it as
  shipped (not root, read-only, all capabilities dropped).
- **The daily report goes to the operator only.** It is mailed to `APP_MONITORING_MAIL_TO` over the mail server of
  `APP_MAIL_*` (optional, off without them): the tenants' names, counts, sizes, amounts and the recent warnings and
  errors of the log. `APP_MAIL_PASSWORD` is a secret of the installation like the others; use `STARTTLS` (the default,
  required once chosen) or `SSL` - `NONE` sends the report and the mail password unencrypted, only for a mail server
  in the same internal network.
- **The operator is trusted.** The admin sees and manages every tenant, may set any user's password and so log in as
  that user, and enters the addresses of model servers. A tenant admin may do the same within its own tenant, except
  for model servers: it enters the addresses of its connected software (MCP) instead, and may mark one of its tools
  as not changing - that tool then runs without the user's decision.
- **`APP_AI_PLAIN_HTTP_HOSTS` opens its hosts to every tenant admin.** A host on the list is exempt from both rules -
  HTTPS and "no internal address" - for own model servers (the admin) *and* for the addresses of connected software,
  which every tenant admin enters: any port and path of such a host becomes reachable to the app's requests. List
  only hosts that are meant for it (a model server of the installation), never `localhost` or the app's own pod.
- **The certificate check is never switched off.** With `USE_SYSTEM_CA_CERTS=1` the app also trusts every certificate
  in the volume's `certificates/` - for the requests to model servers, AI providers and connected software alike.
  Whoever can write into that volume can therefore make the app trust any server: put only the company's own root
  authority there.

## What the software does not do

So nobody has to find it out the hard way:

- **No lockout and no rate limiting of logins.** Behind the reverse proxy every request has the proxy's address, so an
  address-based lockout would lock out everyone. Passwords are hashed with PBKDF2-HMAC-SHA256 (600 000 iterations) and
  must have at least 12 characters. If you need rate limiting, add it in the reverse proxy.
- **Some routes are anonymous on purpose**: the frontend's static files, `/login`, `/login/wam`, `/watchdog` (liveness),
  `/branding` (name, colour and logo for the login page) and `/swagger` (the documentation of the client software's API
  - it describes the endpoints, the endpoints themselves require a key).
- **Data goes to the AI providers the admin configures.** Documents, questions, chat files and tool results are sent to
  the provider of the model in use (Anthropic, OpenAI, Google, or a server of your own). What happens there is governed
  by that provider's terms - see [docs/privacy.md](docs/privacy.md).
- **Models can be misled.** A document, an uploaded file or a connected software's answer may contain instructions
  aimed at the model (prompt injection). The design limits the damage - links other than http(s)/mailto and content
  the browser loads by itself are not rendered, changing tools of connected software run only after the user's
  decision in the chat, the client software and the MCP key never get changing tools, addresses of connected software
  must not resolve to internal addresses, a file a connected software returns is offered only as a download with a
  type from a fixed list (anything else as `application/octet-stream`) - also when it goes to an MCP client with an
  answer of the MCP server, images are created only from the conversation's own images and with at
  most three calls of the image model per answer (each one billed, also a refused one), code runs in the tenant's own sandbox only,
  one answer at a time per instance, without network and with nothing of another answer left - but no model is immune. Turn on only the tools a company needs.

## Supported versions

Security fixes go into the current release. Older releases are not maintained - update to the latest
(README.md "Update" of the installation guide in the public repository
[xprojects-de/alpdesk-ai](https://github.com/xprojects-de/alpdesk-ai)).
