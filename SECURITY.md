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
API key or the MCP key reaching more than its endpoints or an MCP key's search reading a document outside the company
knowledge and that key's areas; a provider key leaving the server in plain text; a prompt
injection in a document or a tool result that gets data out or runs a changing tool without the user's decision.

## What the software assumes

Zirmo is a server application run by an **operator** (the admin) for several companies (tenants), reachable over
the internet or an intranet. The design relies on the following - an installation that does not meet them is not
protected by the software:

- **HTTPS only.** The production stack puts Caddy in front of the app (the installation's `Caddyfile`), which serves
  HTTPS and redirects plain HTTP. Never add a plain-HTTP site or publish the app's port 19100 directly: passwords,
  tokens and API keys would travel unencrypted. Behind an own reverse proxy (`docker-compose.proxy.yml`: a NAS, an
  nginx serving other applications) that proxy terminates HTTPS, and the app's port is published on the loopback
  interface only - plain HTTP between the two stays on the machine. The app itself answers every request with `X-Frame-Options: DENY`,
  `Content-Security-Policy: frame-ancestors 'none'` and `X-Content-Type-Options: nosniff`: no page of it can be framed
  by another site (clickjacking), and no file it serves is sniffed into a script - whatever proxy is in front. The one
  exception are a handbook's website files, which the app's own page frames (see below).
- **PostgreSQL is never reachable from outside.** It gets no published port in production; every tenant's database
  lives on it.
- **The secrets are the installation's own.** Outside the environments "dev" and "test" the app refuses to start
  without `APP_USERNAME`, `APP_PASSWORD`, `APP_SECRET_KEY` and `JWT_GENERATOR_SIGNATURE_SECRET` (not empty, not
  `CHANGE_ME`, not a value from this repository, the password at least 12 and the JWT secret at least 32 characters).
  The values visible in `application.properties` (`alpdesk` / `alpdesk`, a fixed JWT secret) are for local
  development only - they are not the defaults of an installation.
- **A leaked admin password is changed together with `JWT_GENERATOR_SIGNATURE_SECRET`.** The admin's token is valid
  for a day and nothing on the server ends it: a new `APP_PASSWORD` alone leaves whoever logged in with the old one
  inside until then. A new JWT secret ends every session at once (tenant users log in again too). A tenant user's
  logout, too, happens in the browser only: a copied token stays valid for its day unless an admin sets a new
  password; deactivating the user or the tenant refuses it only while they stay deactivated.
- **`APP_SECRET_KEY` is kept safe and backed up.** It encrypts the stored AI provider keys, the keys of connected
  software and of own model servers, the tokens and client secrets of connected software that signs in with OAuth, the
  file stores' passwords, the sandboxes' tokens and A2A clients' webhook settings (AES-256-GCM); whoever has it and a
  database dump has those secrets.
- **Sign-in through a WAM proxy** (`APP_WAM_ENABLED`, off by default) trusts the user header completely. With it on,
  the proxy must strip that header from every request from outside, and the app must be reachable through the proxy
  only - otherwise anyone can sign in as anyone. With Compose the tenants' sandboxes share a network with the app's
  unproxied port (see "Code execution"): there the sandbox's confinement is what keeps a session's code from it. The same holds for the IDM groups header (`APP_WAM_GROUPS_HEADER`,
  optional): whoever can send it chooses which user groups with a WAM group count - and, since **access needs a group**
  (2.0.0: a user who is no tenant admin gets in only through one), who gets in at all. Since 2.0.0 it **gives** access,
  not only limits it: a group with a WAM group takes its members from that header alone, and a person it lists in such
  a group gets a user in that tenant at the sign-in. The proxy must strip it from every request from outside, set it on
  every request inside, and send only the groups meant for Zirmo. Only the admin sets a group's WAM group while
  WAM is on - a tenant admin cannot pull the people of an IDM group into its tenant. A request without it counts none of them (fail
  closed), and so does an installation without `APP_WAM_GROUPS_HEADER` set. A tenant user's session ends when another
  person signs in at the proxy in the same browser; **the admin's password session does not** - it does not depend on
  the proxy (the admin may be someone outside the WAM) and lasts until the admin logs out. On a shared computer the
  admin logs out when done.
- **One person, users in several tenants.** A username is unique per tenant: the same person may have a user in
  several tenants of one installation and switches between them in the user menu. Each token belongs to one user of
  one tenant - a switch issues a new one. With WAM the proxy's header proves the person, at the sign-in (where it
  chooses among its tenants) and at the switch; without WAM a username proves no person (the admin may give the same
  name to two people in two tenants), so the switch asks for the password of the user in the other tenant, like a
  login. A username in several tenants signs in on the tenant's own login page only (`/login/<key>`, which sends the
  tenant's key with the login); on the neutral `/login` it is refused like a wrong password.
- **Code execution runs in a container of the tenant's own, never in the app's and never in another tenant's.** The
  chat's model may run Python and shell commands (`run_python`, `run_shell`) for a tenant whose sandbox the admin
  entered - its addresses and token at the tenant (the image `alpdesk-ai-sandbox`,
  ["Code execution" of the installation guide](Backend/podman/public/README.md#code-execution)). This holds for every
  question of the tenant, also those of its client software
  (`/api/v1/chat` and `/api/v1/chat/stream`, API key) and of its MCP key (`ask_assistant`): whoever holds one of its keys can have code run in
  its sandbox - there is no switch per key. Never give two tenants the same
  instance - the app refuses an address another tenant already has, but not another name for the same container: the
  container is what keeps them apart; within it a session's cleanup keeps the tenant's users apart. A
  sandbox holds no secret but its token (`SANDBOX_TOKEN`, at least 32 characters, one per tenant; the app stores it
  encrypted and never sends it out again), and must be reachable by the app only and reach nothing itself - never
  publish its port. On Kubernetes a NetworkPolicy without egress does that. With Compose the `sandbox` network of
  `docker-compose.prod.yml` (`internal: true`) keeps the internet out, but it is one network for the app's unproxied
  port and every tenant's sandbox: there, only the sandbox's seccomp filter (no network socket for a session's
  program) keeps code from reaching the app or another tenant's instance - so nothing in the container may run code a
  session left behind outside that filter: the image's health check starts Python isolated (`-I`), which ignores
  what a session writes into the user's site directory under `/work` (before 2.0.0 it did not - update the sandbox
  images). It needs no root or capabilities: run it as
  shipped (not root, read-only, all capabilities dropped). It runs without an AppArmor profile (`apparmor=unconfined`
  in its compose file): on Ubuntu 24.04 with Podman the host's profile for crun stacks its label under the
  container's, which allows signals to its own label only, and the sandbox could not end a session's processes any
  more. What that profile forbids - mounts, writes to `/proc/sys` and `/sys` - is already impossible without root and
  capabilities; the isolation rests on those, the seccomp filter and the network (["Code execution" of the
  installation guide](Backend/podman/public/README.md#code-execution)). The app reads back only files the code wrote
  below its session's directory (images the model looks at, files it hands to the user) - checked on the opened file,
  so a link the code made reaches nothing outside it, not the sandbox's own token.
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
  which every tenant admin enters: any port and path of such a host becomes reachable to the app's requests - also
  through a link to a file that a connected software names in a tool's result, whose content then reaches the chat. List
  only hosts that are meant for it (a model server of the installation), never `localhost` or the app's own pod.
- **`APP_FILE_STORE_INTERNAL_HOSTS` opens its hosts to every tenant admin's file stores.** A file store (SFTP, FTPS,
  an S3 endpoint) that a tenant admin enters must not lie in an internal network - unless its host is on this list:
  then any port of it can be connected to by the app on behalf of every tenant. List only the file servers meant for
  it, never `localhost`, the database or the app's own pod. A listed host may also be an S3 endpoint over plain
  `http://` (an own S3 server without a certificate): the access key's secret never goes over the wire, but the files
  do, unencrypted inside that network.
- **A file store's server proves itself before the password goes out.** For SFTP a tenant admin confirms the
  fingerprint of the server's host key, which a check shows first; for FTPS the certificate must come from an
  authority the installation trusts, or its fingerprint is confirmed the same way. Until then, and whenever the
  server later presents another key or certificate, the app does not log in. Plain FTP is never used, and an FTPS
  data connection always goes to the server of the control connection, whatever address the server names - and is
  refused when it presents a certificate the server did not prove itself with.
- **Tasks write into a store only where a tenant admin allowed it.** Every store reads only, until its switch "Aufgaben
  dürfen hier schreiben" is turned on. A task then writes the files named in its text, below the store's folder - a
  temporary file renamed over its target -, and never deletes, moves or overwrites another file. Give the store's
  account only the rights it needs: read, and write on the folder a task writes to. **A CSV a task writes holds what a
  model took out of documents from outside**: a text that a spreadsheet would run as a formula (`=`, `+`, `-`, `@`
  first) is written with a leading `'`, and a file's name never comes from a document's text. A document written as a
  prompt injection can still put wrong values into its own row - look at the rows before importing them.
- **A task may call a changing tool of the connected software without a person.** Only the tool its tenant admin named
  in the task's text, turned on at the connection, with values a model took out of documents from outside: a document
  written as a prompt injection can change the values of its own row only. Unclear rows are never sent. The software
  should check what it receives like any input and recognise a row sent twice.
- **A document a task cannot file fails its run every time.** A task that writes rows into a file fails when a row's
  file cannot be written (its folder is missing, or the rows would go into more than 20 files) - nothing is written
  half. The same documents are read and extracted again by every run until someone removes or corrects the one that
  does not fit; the mail about the failed run names the reason.
- **A mailbox is read only.** A mailbox store opens its folder read only - nothing is deleted, moved or marked - and
  never takes a task's write. It speaks IMAPS or STARTTLS (required, the certificate checked against the JVM's trust);
  unencrypted IMAP goes only to a host the operator names in `APP_FILE_STORE_INTERNAL_HOSTS`.
- **An S3 store reads with its own access key only.** The app never takes credentials, a profile or an endpoint from
  the server it runs on (environment variables, `~/.aws`, an instance role) - a tenant admin's store cannot read with
  the operator's rights. Its requests are signed with the secret, which is not sent. The endpoint's host is checked
  against the internal networks when the store is saved and before every session; the HTTP client resolves the name
  once more when it connects, so a name server that answers differently in between (rebinding on purpose) could lead
  a request elsewhere - a known limit, shared with the connections to the tenants' software. Give the key read rights
  on the bucket only (`s3:ListBucket`, `s3:GetObject`) - and `s3:PutObject` on the folder a task writes to, where the
  store's switch allows it.
- **An Amazon Bedrock model is asked with its own access key only** (the admin's catalog entry). As for S3 the app
  takes no credentials, profile or endpoint from the server it runs on; the requests go to the region's address the
  admin entered and are signed with the secret, which is not sent and is stored encrypted like a provider key. Give
  the IAM user nothing but `bedrock:InvokeModel` and `bedrock:InvokeModelWithResponseStream` on the models and
  inference profiles it is meant for.
- **A SharePoint store reads with the company's own app registration.** Its client secret goes to Microsoft's login only
  (`login.microsoftonline.com`), the token to Microsoft Graph only - a redirect is not followed, a next page outside
  Graph is refused, and a file's download address (pre-authenticated by Microsoft) is fetched without the token and
  only from a `*.sharepoint.com` host. Give the app the application permission `Sites.Selected` and read on the one
  site or OneDrive it is to read (write only where a task writes into it with the store's switch) - nothing else: `Files.Read.All` or `User.Read.All` next to it can widen what it
  reaches, `Sites.Read.All` reads every site of the company.
- **A scheduled task's text is data, never code.** A tenant admin writes a task in YAML or JSON; it is checked against
  a fixed format before it is stored - no expressions beyond comparing counts, no scripts, no anchors -, and its steps
  are the app's own. Its agent steps reach no tool but searching and reading the lines the task read, and no tool of
  connected software. Only the admin's switch lets a tenant have tasks at all. From outside a task is started only with
  an API key a tenant admin assigned it to, or through the tenant's MCP or A2A server with an MCP key it is assigned
  to - one run
  of a task at a time, and a schedule at most every 15 minutes. A task drafted with AI is checked like one written by hand and never saved by the
  draft itself - a tenant admin reads and saves it.
- **The certificate check is never switched off.** With `USE_SYSTEM_CA_CERTS=1` the app also trusts every certificate
  in the volume's `certificates/` - for the requests to model servers, AI providers, connected software and FTPS
  file stores alike.
  Whoever can write into that volume can therefore make the app trust any server: put only the company's own root
  authority there.

- **A2A** (both ways, A2A 1.0 on the official SDK): an A2A agent connected as software gets the connection's key
  only at the address the tenant admin entered (never at another host a card names), under the same address rules as
  MCP software; its answers are foreign text to the model and the user, like a software's tool result. The agent
  receives who asks (the company's name, the user's name and groups, the language - never a secret) and trusts it as
  far as it trusts the installation, since it comes with the connection's key. The admin decides per tenant whether
  its questions may leave for an agent. At `/a2a` an MCP key answers only when it opens that door; the public agent
  card names no tenant, the extended card is the key's. A foreign agent's question is foreign text like a client's at
  `/mcp`. Push notifications go to public HTTPS addresses only (no internal or listed host), with the client's own
  token. Behind a WAM proxy, `/a2a` must let bearer requests through like `/mcp`.

## What the software does not do

So nobody has to find it out the hard way:

- **No lockout and no rate limiting of logins.** Behind the reverse proxy every request has the proxy's address, so an
  address-based lockout would lock out everyone. Passwords are hashed with PBKDF2-HMAC-SHA256 (600 000 iterations) and
  must have at least 12 characters. If you need rate limiting, add it in the reverse proxy.
- **The recent warnings in the app can be pushed out.** The admin's page "Application log" and the daily report show
  the last 1,000 warnings and errors, kept in memory. Anonymous requests with invalid JSON each write a WARN line, so
  enough of them push older warnings out of that list. The list is bounded to keep the memory bounded; the container's
  log (`podman logs`) keeps every line.
- **A frontend of your own for the users** may use the routes published in `/swagger` besides `/api/v1` - signing in,
  the account, the chat - with the token of a person; it gets exactly what that person gets in Zirmo. On another
  origin it must be listed in `APP_CORS_ORIGINS` (exact origins, never `*`). CORS never allows credentials: a page of
  an allowed origin cannot have the browser send a WAM proxy's session cookie along and take a token. Where such a
  frontend keeps the token, and that it renders the chat's Markdown through a sanitizer, is its own responsibility.
- **Some routes are anonymous on purpose**: the frontend's static files, `/login`, `/login/wam`, `/watchdog` (liveness),
  `/branding` (name, colour and logo of the installation, and the same as the web app manifest for a phone's home
  screen), `/login/<key>` with `/branding/login/<key>` (a tenant's
  own login page with its name, colour and logo - the key is 128 random bits, so nobody finds out by trying which
  companies use an installation; an unknown key or a deactivated tenant shows the neutral page; the admin can renew a
  key) and `/swagger` (the documentation of the client software's API
  - it describes the endpoints, the endpoints themselves require a key), and `/doc-sites/<token>/...` - the files of a
  handbook's website, reached through an address a signed-in user gets for one website and one hour, and
  `/.well-known/agent-card.json` with `/a2a/extensions/...` - A2A's public card and the descriptions of its two
  extensions, neutral (no company's name, no skills); the company's card comes only with a key; and
  `/oauth/connections/callback` with `/oauth/client-metadata.json` - the way back from a connected software's OAuth
  sign-in (it signs in no one: it finds the single-use random state of a sign-in a tenant admin started, valid 10
  minutes, and sends the browser on to that page - a page of the installation's own frontend, never one that shows a
  company's own content -, which completes the sign-in with the session of the person who started it: a sign-in link
  passed on to someone else signs in no one) and the installation's
  client id metadata document (no database, nothing but the installation's name, address and callback).
- **Connected software that signs in with OAuth**: a tenant admin's sign-in at the software's authorization server
  leaves tokens with the installation - encrypted like the keys (`APP_SECRET_KEY`), never returned by the API, the
  access token sent to the connection's own host only. Every address of the sign-in the installation calls (the software's metadata,
  the authorization server's) passes the rules of a connected software's address: HTTPS, no internal address unless
  listed, no redirect followed. The server's sign-in page, which only the browser opens, must be a web address
  (`https://`, plain HTTP only to a listed host) - anything else, such as a `javascript:` address, starts no sign-in. A tenant admin can make the installation register itself as a client at a server the
  admin names (dynamic registration) - no secret of the installation is involved, and a secret the server hands out
  stays with that connection's grant.
- **A handbook's website runs its own scripts - sandboxed.** A tenant admin (or the admin) uploads a static website as
  a ZIP; its HTML and JavaScript are served as they are, every file with `Content-Security-Policy: sandbox` (scripts
  and new tabs only, no same origin) and framed only by the app (and by the origins allowed for CORS,
  `http://localhost:4300` by default - the frontend's development server). The browser treats them as a foreign site: they reach
  neither the user's session nor the API. An address handed out stays valid for its hour also after the user was
  deactivated or taken out of the knowledge area; it sends no referrer, so it does not reach the sites the website
  links to. The address is a URL: for its hour it can also show in a reverse proxy's access log, the browser's history
  and the application log. The sandbox protects the app, not the reader: a website is whatever its tenant admin
  uploaded, shown under the installation's own address to anyone who has the link for its hour - also a page that asks
  for a password. Who uploads websites must be trusted with that.
- **Data goes to the AI providers the admin configures.** Documents, questions, chat files and tool results are sent to
  the provider of the model in use (Anthropic, OpenAI, Google, or a server of your own). What happens there is governed
  by that provider's terms - see [docs/privacy.md](docs/privacy.md).
- **The web search sends the model's queries out.** Where a tenant admin turned it on (a user group, an API key, the
  MCP server - off by default, and only where the admin assigned the web search to the tenant), the chat model writes
  search queries that go to Tavily. Documents and files never go along, and the model is told to put only the public
  topic into a query - but a model may still carry a word of a document into one. Turn it on only for the groups and
  keys that need it. A scheduled task searches the web only where its text says so (`web: true` on an assistant's
  prompt step): there nobody watches the queries, and what the task reads - log lines, documents, mails - is written
  by others.
- **Models can be misled.** A document, an uploaded file, a web page the web search found or a connected software's
  answer may contain instructions
  aimed at the model (prompt injection). The design limits the damage - web pages reach the model marked as unchecked
  material whose instructions are never followed, at most three web searches per answer, links other than http(s)/mailto and content
  the browser loads by itself are not rendered, changing tools of connected software run only after the user's
  decision in the chat, the client software and `ask_assistant` never get changing tools (they may start a scheduled
  task a tenant admin assigned to their key, which does what its text says) and an MCP key gets them only passed
  through to its client, which asks its user before each call (below), addresses of connected software
  must not resolve to internal addresses - also the address of a file it returns as a link, which our server fetches
  at once (every redirect checked again, at most 50 MB, our key or token only to the connection's own host and never
  to another download host) -, a file a connected software returns is offered only as a download with a
  type from a fixed list (anything else as `application/octet-stream`) - also when it goes to an MCP client with an
  answer of the MCP server, images are created only from the conversation's own images and with at
  most three calls of the image model per answer (each one billed, also a refused one), code runs in the tenant's own sandbox only,
  one answer at a time per instance, without network and with nothing of another answer left - but no model is immune. Turn on only the tools a company needs.
- **Connected software passed through to MCP clients.** An MCP key reaches only the connected software a tenant admin
  gave it - through `ask_assistant` and, where a connection is switched "Directly to MCP clients" (off by default),
  directly: the client's model then calls its reading tools and prompts itself and reads their raw results, which
  leave the installation for the client (with claude.ai or Cowork, Anthropic). The software's address and key stay in
  the installation. Its changing tools are passed through only to a key a tenant admin let change in that software
  ("Darf ändern in", off by default), marked as not read-only so the client asks its user before each call. That
  question is the only stop between a prompt injection the client read and a change in the software: a user who lets
  the client always allow such a tool gives it up, and whoever holds the key may change what it may change - as the
  connection's one account in the software, not as a person. Every changing call is logged (key, tool, software). The software's tool descriptions, prompts and the
  instructions it gives (at most 2,000 characters, after ours) are text of another server that the client's and our
  model read - enter only software you trust, and leave a module whose knowledge must not leave answer-only: its raw
  results and the files its tools return then stay in the installation, an MCP client gets the assistant's answer.
- **MCP clients that act on the computer read the company's documents as text.** What `search_knowledge`,
  `read_document` and `ask_assistant` hand to an MCP client reaches an agent that may write files or run commands
  there (Claude Code, Cowork). The MCP server's own tools change nothing and are marked read-only (`run_task` starts a task a tenant
  admin assigned to the key; a passed-through changing tool is the software's, above), but a document's
  content - uploaded, pushed by the client software or read from a file store - is unchecked text to that agent: a
  prepared document is prompt injection on the user's computer. Give MCP keys only to people who know this, and keep
  the client's own permissions tight.

## Supported versions

Security fixes go into the current release. Older releases are not maintained - update to the latest
("Update" in the installation guide,
[Backend/podman/public/README.md](Backend/podman/public/README.md)).
