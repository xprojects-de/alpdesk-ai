# Roadmap

What is to come, as agreed on 6 October 2026. Size: S (a day or two), M (about a week), L (more).

**How a point is built**: first a written proposal (what changes in which layer, data, API, UI, open questions) that
the user agrees to - no code before that. Then the implementation with its specs; the decisions go into
`docs/architecture/` and the point is marked done here.

| # | Point | Size | Status |
|---|-------|------|--------|
| 11 | Model access per tenant: the admin decides which models a tenant may use | S-M | done |
| 1 | Knowledge scopes: tenant-wide documents, user documents that can be switched off | M | replaced by 12 |
| 12 | Tenant admins: each company keeps its own settings, users only chat | M | done |
| 13 | Documents pushed by the client software: an `externalId` replaces the earlier version | S | done |
| 2 | MCP client tool: call MCP servers (e.g. an ERP's) from the chat | M | done |
| 3 | Own model servers: Ollama and other OpenAI-compatible APIs | S-M | done |
| 4 | Better search results: measured; hybrid search, chunk headers, HNSW index | M | done |
| 5 | Branding: name, colour, logo - the installation's from the environment, each tenant's from the admin | S-M | done |
| 6 | Workflows per tenant with langgraph4j | L | open - since 6 Nov 2026 a tenant's own workflows run in an engine of the tenant's own (the LangGraph Python library in a container per tenant) asked over A2A (54); this point stays for workflows built in Alpdesk AI itself, on the tasks' format |
| 7 | Embeddable chatbot: a chat box for the tenant's website (end customers) first | M | agreed - not built yet |
| 8 | Documents from other sources: a file store's folder synced into the knowledge (SFTP, FTPS; the types of 44 later), mailboxes see 9 | M (about 6 days) | built 27 Oct 2026 (1.9.0) - knowledge sources, [architecture/knowledge-sources.md](architecture/knowledge-sources.md) (proposal and decisions: a separate plan (kept outside the repository)); with it the indexer fair between tenants and `.log` read as text; the types of point 44 (S3, SharePoint / OneDrive) read by sources since 29 Oct 2026 |
| 9 | Documents from e-mails and their attachments | M | built with point 50 (5 Nov 2026, 2.0.0): a mailbox (IMAP) as a read-only file store - its messages' attachments read by the tasks' `read_documents` and by the knowledge sources; the message's text as `mail.txt` |
| 10 | Claude Code (and other MCP clients) reading from Alpdesk AI | M | done |
| 14 | Sub-agents: specialists the tenant describes, the chat's model delegates to them | M-L | open on purpose - measured, groundwork done; next: measure again with a strong model for the specialist, then build |
| 15 | Prompt caching: cheaper rounds of the agent loop, cached tokens billed at their own price | S-M | built 27 Oct 2026 (1.9.0) with price tiers in the catalog and a run's kept cost - [architecture/ai-features.md](architecture/ai-features.md) "Prompt caching"; measured the same day on the test stack: tasks -41%, chat -37%, the same results (task check points alike, chat 30 of 30 answers right on both sides) |
| 16 | Knowledge areas: the company knowledge for everyone, areas only for the users and keys assigned to them | M | done |
| 17 | File templates: the chat fills a tenant's template (a zipped project, a DOCX) instead of writing the file itself | M (+ M-L invoice) | proposal |
| 18 | Plans as PDF: drawings read as images, large formats in tiles | S-M | open - measure first |
| 19 | Faster full-text search for large tenants: rank from a stored `tsvector` | S | later - when a tenant needs it |
| 20 | Web crawler: a website (e.g. a product's online documentation) as a knowledge source | M | on hold |
| 21 | Staff chat embedded in the client software: the normal chat in an iframe, signed in by the client software, may change | S-M | proposal |
| 22 | White label: the MCP server goes by the tenant's brand (`muster-ai`), the MCP client too | S | done |
| 23 | Read more than a passage: short documents whole, long ones around the hit | S | done - first real test passed |
| 24 | Web search in the chat: current facts from the web, switchable per user group | S-M | built 23 Oct 2026 (1.7.0) - Tavily through langchain4j, a switch per group, API key and MCP server, the documents first; [architecture/ai-features.md](architecture/ai-features.md) "Web search"; measured with Claude Haiku the same day: documents first held, current facts right with sources |
| 25 | Edit images in the chat: images sent with the question, changed or combined by an image model (Gemini first), improved round by round | M | done - real tests of combining open |
| 26 | Code execution: Python and shell in the chat, in a sandbox container of the tenant's own, without privileges | M | done - one sandbox per tenant since 11 Oct 2026; eight known weaknesses noted, none built before a case; the record of what ran first |
| 27 | The frontend in English and German: translated at runtime (Transloco), the language kept on the account (V4), more languages as one file each; backend errors with a code the frontend translates | M | done - 11 Oct 2026, [architecture/i18n.md](architecture/i18n.md); English the default, the language on the account (admin sets it, the user may change it) |
| 28 | Scheduled tasks: a graph drawn in an editor (langgraph4j, the `AgentLoop` as a node), started by schedule or `/api/v1`; first case files of a file store (SFTP, FTPS) checked against the tenant's check points, a mail only on findings; the file stores also a source for embedding (8) and a place to write to | L | done 27 Oct 2026 - prototypes passed, file stores (SFTP, FTPS) built 13 Oct 2026; packages A (format, check, steps, runs, start by hand, the admin's switch), B (schedule, `/api/v1`, MCP, cancel) and C (the page "Aufgaben": list, editor with the graph, run view) built 21 Oct 2026, [architecture/tasks.md](architecture/tasks.md); package D ("Draft with AI", the step `prompt` with `choices`) built 21 Oct 2026; package E agreed 27 Oct 2026: the cost per run shown and the task code without fingerprints built 27 Oct 2026 (1.8.1); a cheaper verify step built, measured and taken back 27 Oct 2026 (a quarter of the cost, but not proven equal); price tiers in the catalog built with point 15 (1.9.0); read_files' 20 MB per step accepted as a limit; keeping only the lines an earlier step names moved to point 45, more file stores to point 44; the further building blocks only when a task needs them; completion and graphic editing in the editor dropped |
| 29 | Files from the MCP server: `ask_assistant` hands files to Claude Code and GitHub Copilot (programming) | S-M | done - 14 Oct 2026 (1.4.0): files as embedded resources (text as text, others as base64, no unpacking), templates as prompts; files from the client stay text; tried with Claude Code 2.1.295, Copilot not yet |
| 30 | MCP elicitation: a connected software's tool asks the user for values through a form in the chat, not the model | M-L | open - planned for 1.5.0 (decided 14 Oct 2026), once a connected software's MCP server uses elicitation; proposal first |
| 31 | Tool chain: files a connected software's tool returns (an archive too) reach the model in the same answer - unpacked, chosen and handed on, in the chat and over the MCP server, without a workflow | M | open - planned for 1.5.0 (decided 14 Oct 2026); proposal first |
| 32 | Prompts of a connected software in the chat: a tenant admin turns them on, the user fills their values in a form, the text lands in the input field | M | done - 14 Oct 2026 (1.4.0); mcp.md "Prompts of the connected software" |
| 33 | User groups: the software's levels and the knowledge areas per group instead of per user; with WAM optionally checked against the IDM groups the proxy sends | M (+ S WAM) | done - 16 Oct 2026 (1.5.0); tenancy.md "User groups" |
| 34 | Handbook: a static HTML website per knowledge area, uploaded as a ZIP, shown sandboxed in an iframe beside the chat (Markdown dropped) | M | done - 20 Oct 2026 (1.6.0); [architecture/doc-sites.md](architecture/doc-sites.md) |
| 35 | Videos through code execution: the model cuts frames out of a video in the sandbox and looks at them, hands out clips and stills; the model's own video reading (Gemini) goes | M-L | done - steps 1 to 4 built 17 Oct 2026, first real test done; phases of a movement built 18 Oct 2026 (RTMPose in the video sandbox, the phases from measured values, stills with skeleton and angles); real test of the phases passed with Claude Sonnet 5.5 (reported 27 Oct 2026) |
| 36 | Binding and supplementary documents: the tenant's own documentation decides how its product is set up, a manufacturer's manuals only add facts | S-M | proposal - measure first |
| 37 | Claude Cowork and Alpdesk AI together: Cowork works on the PC, Alpdesk AI knows the company - connected as a custom connector; knowledge tools over MCP, the NAS's files through point 8, later sign-in per person (OAuth) | S (try) + M (+ L OAuth) | in progress - step 0 passed 24 Oct 2026 (connector with a fixed header); the card for the connector (1b) and the knowledge tools (1) built (1.8.0); the NAS push (2) dropped 27 Oct 2026 - the NAS is read as a file store (point 8); next: a try in a Cowork task; OAuth later |
| 38 | Python in scheduled tasks: an agent step computes with code in the tenant's sandbox (measurements, statistics, trends) instead of estimating, the model explains the result | M | proposal - e.g. the measurements of a technical plant |
| 39 | Several MCP keys per tenant: the admin and the tenant admins create named MCP keys, each with its own knowledge areas and web search (like the client software's API keys) | S-M | built 24 Oct 2026 (1.8.0) - mcp.md "MCP keys" |
| 40 | Writing back from Cowork: an MCP key may save a text (a project summary) into the company knowledge or one of its areas - switched per key, off by default, its origin shown | S-M (3-4 days) | proposal - after the sources of point 8, when a customer asks |
| 41 | Connected software through to MCP clients (proxy): its reading tools, its instructions and its prompts reach Claude Code and Cowork directly through the tenant's MCP server - several connections chosen per MCP key, like the areas; a software that only Zirmo reaches (an internal service, a module in a pod of its own, its key held by Zirmo alone) answer-only unless passed through | M (about 5 days) | built 24/25 Oct 2026 (1.8.0) |
| 42 | The connected software chosen per API key: like the MCP keys since point 41, each API key of the client software gets the connections it may use - instead of one switch per connection for all API keys | S (about 1 day) | built 25 Oct 2026 (1.8.0) |
| 43 | A user group's rights in one dialog: areas, web search, members and the level per connected software (read / read and change) together, like the keys' dialog - today the level is set on the page "Software" | S (about half a day) | built 25 Oct 2026 (1.8.0) |
| 44 | More file stores: S3, SharePoint and OneDrive (Microsoft Graph) - for the tasks' `read_files` and the knowledge sources of point 8 | M-L (about 7-9 days) | built 29 Oct 2026 (1.9.0) - split from 28 on 27 Oct 2026; after point 8 (decided 27 Oct 2026); the local folder dropped (decided 29 Oct 2026); **S3 built 29 Oct 2026 (1.9.0)** - a separate plan (kept outside the repository); **SharePoint / OneDrive built 29 Oct 2026 (1.9.0)** against a fake of Microsoft - [architecture/file-stores.md](architecture/file-stores.md); open: the real try with a Microsoft 365 test tenant |
| 45 | Lines of one file by the values found in another: a `read_files` step keeps only the lines naming a value an earlier step found (an order number, a serial number, a booking) - a few lines instead of thousands | S-M | on hold (decided 27 Oct 2026) - one case only, the saving uncertain; taken up with a second case |
| 46 | Reranking, switchable: a reranker (cross-encoder) of the catalog reorders the hybrid search's candidates, assigned per tenant - off unless chosen, so answers can be compared with and without | S-M (about 3-4 days) | built 29 Oct 2026 (1.9.0) - [architecture/ai-features.md](architecture/ai-features.md) "Reranking"; TEI only, chosen like the embedding model, one reranker for all tenants (Hugging Face's TEI image with bge-reranker-v2-m3, compose files for CPU and GPU - no image of our own since 30 Oct 2026); measured 30 Oct 2026: first hit 56 % → 85 %, but ~1.1 s per candidate on a CPU (45 s per search) - the number of candidates a setting since 30 Oct 2026 (`APP_SEARCH_RERANK_CANDIDATES`, 2-40, default 10: measured 82 % first hits in 13 s, 20 gave 85 % in 26 s); open: the GPU file tried on a GPU, a smaller reranker, and the comparison on a real tenant's documents |
| 47 | Contextual retrieval with a model: before embedding, a chat model writes each chunk a sentence of where it stands in its document - beyond today's header of file name and title | S-M (about 3-4 days) | on hold (decided 29 Oct 2026) - measured together with point 46 on a large tenant's documents; built only if the header of today falls short; since 2 Nov 2026 its cheap variant (the document's description from point 49 in every chunk's header, one call per document) is measured with point 49 |
| 48 | Tool retrieval: with many tools of the connected software, a vector search picks per question the ones that fit, instead of offering every tool turned on | S-M (about 3-4 days) | on hold (decided 29 Oct 2026) - no case today; taken up with a small own model and software with many tools |
| 49 | Knowledge compiled by a model ("LLM wiki"): a summary per document and an overview of what the asker may find, as a layer above the search - pages per subject and a contradiction report later | M (about 4-5 days) for step 1 | proposal - questions answered 2 Nov 2026, built on the go-ahead; kept only if measured worth it |
| 50 | Documents into a table in a task: a task reads documents of a file store (PDF, scans, images), takes the same fields out of each and appends them as rows to a CSV in a store - e.g. invoices received and sent, collected through the year | L (about 7-8 days, + 2-3 days call_tool as a target, + 2 days as a source, + 2-3 days a mailbox as a store) | built 5 Nov 2026 (2.0.0, all five packages) - [architecture/tasks.md](architecture/tasks.md) "read_documents", "extract", "write_file", "call_tool", [architecture/file-stores.md](architecture/file-stores.md) "Writing", "Mailbox"; plan and decisions kept outside the repository; open: the try by hand with real documents and models (`devtools/task-documents/`), a real MCP server of a bookkeeping software, a real mailbox; package 3 sends the rows to a connected software's MCP tool (`call_tool`), built once a real MCP server of the receiving software exists |
| 51 | General chat over topic tenants: a company's topic tenants (A, B, C) stay separate; a tenant of the same installation asks the chosen topics through their MCP servers - one person with a user in several tenants (a switch in the menu), a description per MCP key so the model tells the topics apart, the topics deselectable per question (chat and `/api/v1`), members from the IDM (a person put into the IDM group gets in without anyone clicking), and a guide | M (about 7-9 days) | agreed 5 Nov 2026; **built** unattended in the night of 5/6 Nov 2026 on `feature/orchestrator` (2.0.0), packages 0-4 one commit each, specs, smoke test (step 9: the general chat) and the test stack's checks green; the measurement with a real model open (`devtools/general-chat-test/measure.py`) - a separate plan (kept outside the repository); A2A (52) not in it |
| 52 | A2A (Agent2Agent): a tenant's assistant offered as an A2A agent next to `ask_assistant` - the same core, the MCP keys | M (about 6 days on the official SDK) | **built in the night of 6/7 Nov 2026** (2.0.0, `feature/a2a`, its plan's "Built") - agreed and planned in full 6 Nov 2026 - on the official A2A Java SDK's server core (request handler, task lifecycle, subscriptions, push notifications to webhooks under our address rules, its TCK in CI; spike 6 Nov: builds without CDI, the SDK writes the wire format): `/a2a` with SendMessage (a question answered stateless like `ask_assistant`) and the stream (a streamed or `returnImmediately` question a stored task of the key, GetTask / SubscribeToTask after the stream broke, 24 h retention - the full build, decided the same evening), the scheduled tasks as A2A tasks (GetTask, ListTasks, CancelTask, SubscribeToTask on `task_run`), the neutral card and the tenant's card for a key, two switches per key (MCP / A2A); **after the client (54), both in 2.0.0** (decided 6 Nov 2026 - 2.0.0 is not released) - the order turned round the same evening: a workflow engine of the tenant's own reaches the tenants back over MCP, the server is needed only for a conversation with a tenant's assistant or a platform speaking A2A only - a separate plan (kept outside the repository) |
| 53 | A frontend of the tenant's own for the users: signing in, the account and the chat published in Swagger next to `/api/v1` and kept compatible like it, `APP_CORS_ORIGINS` for another origin, CORS without credentials - the admin's and tenant admins' pages stay in Alpdesk AI | S (about 1 day) | **built** 6 Nov 2026 on `feature/orchestrator` (2.0.0) - decided the same day; the README's "A frontend of your own for the users" |
| 54 | A2A client: an A2A agent as connected software of a tenant - a workflow engine of the tenant's own (the LangGraph Python library in a container per tenant, never shipped by us), a foreign agent, or our own topics over A2A. Package A: a connection of protocol A2A (address rules as MCP, the agent card with its skills read on reload), one tool `<connection>__ask` for the chat's model by the groups' level; package B: a tenant answering **every** question through its agent (the orchestrator's case - the stream mapped onto the chat's events, the context kept with the conversation, a question back answered by the next question, the asker's tenant, language, user or key, groups and IDM groups in `message.metadata.alpdesk`, the way back over the topics' MCP keys in the container); package C: the agent's skills deselectable like the topics of 51; package D: the step `call_agent` of the scheduled tasks (rows to an agent as `call_tool` sends them to a tool, a target only); package E: a `prompt` step answered by the assistant (`assistant: true` with areas, software - A2A agents included -, web search and a template: the prompt chain with the company's knowledge); `devtools/a2a-test-agent/` - an agent on the official Python SDK to try the client against by hand, the template for an adapter of one's own | M (about 13-15 days) | **built in the night of 6/7 Nov 2026** (2.0.0, `feature/a2a`, its plan's "Built") - decided 6 Nov 2026 (workflows: A2A, not MCP or a REST API of our own; the engine the tenant's own; the orchestrator always through the workflow; groups in the metadata) - **2.0.0, before the server (52)**; the way back from the workflow over `/mcp` or `/a2a` with the topics' standard MCP keys (decided 6 Nov 2026 - no token per asker, see 55); operations: a log line per agent call, the check button, the admin's switch per tenant; the skills filtered by group later - a separate plan (kept outside the repository) |
| 55 | A signed short-lived assertion per asker for the way back from a workflow: the person resolved to its own user in every topic (rights, usage and audit per person, no fixed keys in the engine), JWKS of the installation, accepted at `/mcp` and `/a2a` | M (about 3-4 days) | idea - designed 6 Nov 2026 with 54 and put aside as too much to explain; taken up when the person must be visible in the topics |
| 56 | A second factor for the password login (TOTP): set up in the account (QR code, recovery codes), asked as a second step of `/login`, mandatory per tenant as the admin's switch, reset by the admin or a tenant admin; the admin account's from the environment; passkeys later | M (about 3-4 days) | agreed 8 Nov 2026 - proposal first; the first of the three points taken from a comparison with a hosted assistant (the product stays as it is) |
| 57 | A budget per tenant and month: the admin sets an amount, every provider call is checked against the month's usage, over it the chat, the API and the MCP server answer 409 with a clear message, the usage pages show it, the daily report warns at 80 % | S-M (about 2-3 days) | agreed 8 Nov 2026 - proposal first; the safety an operator billing global keys has no equivalent of today |
| 58 | Retention of chat histories per tenant: days or unlimited, set by the admin, a nightly job deletes a tenant's conversations older than that with their files; the installation's file and video retention stays | S (about 1 day) | agreed 8 Nov 2026 - proposal first; after 56 and 57 |
| 59 | Custom sandboxes: an operator builds a tenant's sandbox image on ours with the tenant's own programs (a test framework, a simulator, a CLI) and writes one README into it; the sandbox hands the README to the app, the chat's model gets it with the code rules - nothing else in the core | S (about 1 day) | **built** 8 Nov 2026 (2.0.0) - the README through `/health` into the code rules, the admin's check, `sandbox-example/`; selftest and the example image checked by hand |
| 60 | Frontier models through the hyperscalers' EU regions: Azure OpenAI (GPT), Claude through Google Vertex AI and AWS Bedrock - three more providers of the catalog, the installation's own cloud account, nothing of ours in between; `docs/privacy.md` says plainly what an EU region gives and what not | M (about 3-4 days, after checking the three sign-up ways) | **built** 8 Nov 2026 (2.0.0), smaller than proposed: provider `BEDROCK` (Converse API, Frankfurt), Azure OpenAI as `OPENAI_COMPATIBLE`; Vertex left out - taken from a comparison with a hosted assistant: the one point where it could do something ours could not |
| 61 | Connections to servers that sign in with OAuth, MCP first: a tenant admin connects a hosted MCP server (a gateway to a bookkeeping system, an issue tracker, a wiki) by signing in once in the browser - OAuth 2.1 authorization code with PKCE, dynamic client registration, refresh tokens kept encrypted; the flow and the tokens protocol-neutral in `services.oauth` (one table `oauth_grant`), so the A2A door costs a day when an agent asks; today a connection takes a fixed bearer token only | M (about 4-6 days, both doors) | **plan agreed** 8 Nov 2026 for 2.0.0, both doors at once - a hosted gateway that accepts OAuth only (checked: no other door); our client cannot sign in there |
| 62 | The IMAP mailbox of a knowledge source signs in with OAuth (XOAUTH2): Microsoft 365 no longer takes a password for IMAP, Google wants app passwords - the mailbox store gets an OAuth token instead of a password, as the SharePoint store gets its token through MSAL today (client credentials with the mail scopes; a personal mailbox through the authorization code of point 61's flow) | S-M (about 2 days) | **idea** 8 Nov 2026, from the OAuth survey of point 61 - the one other place a token replaces a secret; waits for the first company with a mailbox at a hosted provider |
| 63 | Files a connected software's tool returns as a link (MCP `resource_link`): resolved at once inside the call - an address of the server's own through `resources/read` in the same session, an `https` address fetched under the connections' address rules (no internal host, every redirect checked again, size and time limits, our token only to the connection's own host) - and then the same way as an embedded file today, so the chat, the tasks' `call_tool` source of documents and the MCP proxy get them without a change of their own | S (about 1 day) | **built** 9 Nov 2026 (2.0.0) on `feature/OAuth` - a hosted gateway hands out a document management system's files as a temporary download; today the link is left out and neither the chat nor a task gets the file |
| 64 | SMB as a file store: a share of a Windows file server or a NAS in the company's network (`\\fileserver\share`) for the tasks and the knowledge sources, through the Java library smbj inside the app - no CIFS mount, no privilege, Kubernetes "restricted" kept; SMB 3 encrypted and signed to any host, SMB 2 or unencrypted only to the operator's listed internal hosts (NTLM does not prove the server - a long random password), a test Samba under `devtools/` | S (about 2.5-3 days) | on hold (decided 9 Nov 2026) - not in 2.0.0, built when a customer has only the share; the proposal is written, checked and settled for an unattended build, a NAS for the hand test: a separate plan (kept outside the repository) |
| 65 | Kerberos for the SMB file store: the store signs in with a ticket of the company's Active Directory instead of NTLM - for companies whose policy switches NTLM off, and the server proves itself (the limit of point 64 closed) | M (about 4-6 days with a test domain) | later - when a customer's policy refuses NTLM and a test domain is at hand; after point 64; proposal first - what is known: a separate plan (kept outside the repository) |
| 66 | Media and documents read apart from the app: defence in depth for the two places that read foreign files - ffmpeg (audio and video of the chat) runs in the app's container, Tika has no time limit. ffmpeg started without the app's environment, the app's container hardened (no capabilities, no new privileges, later a read-only root), then conversion in a process or container of its own | S for the first two steps, M-L for the separation | later; from the security review of 10 November 2026; the first two steps can go into any release; proposal first |
| 67 | A user's own connections to software: a tenant user connects an MCP server or an A2A agent on the account page with their own address and sign-in (a token, or OAuth) - the tools are read for that user and used in that user's chat only, with the person's rights in the software; the tenant's connections stay as they are, shared and the tenant admin's; nothing of an issue tracker or a wiki in the core | M (about 3-4 days) | **plan agreed** 10 Nov 2026 for 2.1.0, the user's shape (replaces the modes on a tenant's connection agreed earlier that day): a tenant admin's switch, off by default, and any address the rules allow; not started - after 2.0.0 is released |
| 68 | Changing tools of the connected software through to MCP clients: a company's own MCP server behind ours offers tools that change something (a ticket created in an issue tracker, a document filed on another server) and Claude Code, Cowork or Copilot call them directly - a level per MCP key and software (none, read, read and change) where the key card has a tick per software today, the tool marked as changing so the client asks its user before each call; `ask_assistant` and `/a2a` stay reading | S (about 2-3 days) | **built** 11 Nov 2026 (2.0.0, migration `V18` - the release candidate is out) - [architecture/mcp.md](architecture/mcp.md) "Changing tools passed through"; the "later" of point 41 |

## Target architecture

How the eleven points fit into one structure, so that each lands in a place that is already prepared for the next
ones. It is a plan, not a rule yet: each part becomes binding (in `docs/architecture/`) with the point that builds it.
The code style's "no abstraction in advance" still holds - the plan says *where* something goes; a shared class is
only extracted when its second user is built.

### Five building blocks

```
 Entry points           Assistant              Knowledge                 Connections
 (who asks)             (what answers)         (what it knows)           (what it reaches)
 ─────────────          ─────────────          ───────────────           ─────────────────
 frontend user ─┐                              ingest ◄── upload, API
 client software├──►    agent loop ──► tools ─► search                   ◄── sources (8), mailboxes (9)
 widget (7)     │       (chat today,     │      (scopes 1,                ──► MCP servers (2)
 MCP server (10)│        workflows 6)    └────► padding/hybrid 4)        ──► model servers (3)
 workflow run(6)┘                │
                                 ▼
                       AI access: catalog, model access per tenant (11), keys, usage
```

1. **AI access** (`services.ai`) - which model a tenant may use, with which key, at which price. Every model call
   goes through it: today `AiModelService.requireActive`, then `requireUsable(tenant, id, kind)` (11). New providers
   (3) are one more case in `AiProvider` and in the `switch` of `AiModels` - no factory hierarchy. A tenant's own
   model server is a catalog entry with a base URL that the admin assigns to that tenant alone (11) - so no
   tenant-entered URLs for models. Usage records also say **who caused the call** (`caller`, see 3.): the admin sees
   what the widget, the MCP server or a workflow cost, not only the chat.
2. **Knowledge** (`services.document`) - one way in and one way out:
   - *in*: every document - upload, `/api/v1`, a source, a mail - goes through `DocumentService` and the
     `DocumentIndexer`. A document records where it comes from (`origin`: UPLOAD, API, SOURCE; with the source's id
     and its path) and whom it belongs to (`scope`, 1).
   - *out*: one class `KnowledgeSearch` (extracted from `SimilaritySearchTool` with point 4) does the search with
     scope filter, hybrid search and neighbour chunks. The tool, the MCP server (10) and workflows (6) all use it -
     so the knowledge rules exist once.
3. **Tools** (`services.tools`) - the `Toolbox` stays the one place that offers and runs tools. Beside the
   `ChatTool` beans it adds the tenant's MCP tools (2), built per answer from the tenant's MCP servers. The
   `ToolContext` learns **who asks** (`caller`: USER, CLIENT_SOFTWARE, WIDGET, WORKFLOW, MCP): a tool decides with
   it whether it is offered (e.g. no `create_file` in a public widget). Tools that change something outside get a
   **confirmation** step - the same mechanism serves the chat (2) and a workflow's approval (6). Its price: a
   pending call must be stored with the conversation and the loop resumed later - the first piece of the
   checkpointing that workflows need. Until then the chat only offers tools that read (MCP marks them with
   `readOnlyHint`). Every call of an external tool is kept with the message (name, arguments, outcome): what the
   assistant did in another system must be traceable.
4. **Assistant** (`services.chat`, `services.agent`) - the agent loop is its own class `AgentLoop` (model, messages,
   tools, context → answer, in one piece or streamed) since 10 October 2026, when its three copies in `ChatService`
   were merged; `ChatService` keeps the conversations.
5. **Connections** - everything that reaches out of the installation, with three shared rules:
   - credentials encrypted with `SecretCipher`, never sent out again (like the provider keys);
   - an address a tenant enters (MCP server, source host, mail server, possibly a model server) is checked against
     internal networks (SSRF) by one helper, built with the first point that needs it;
   - runs in the background on the existing executors; the scheduled ones (sources, mailboxes) run with
     `@Scheduled` over all active tenants - one instance, so no cluster locking (said in the docs, not built);
   - every connection type gets a test double in the specs, as the providers have `FakeAiModels`: an in-process
     MCP server, MinIO for S3, an SFTP container, GreenMail for IMAP - nothing external is called from a spec.

### Entry points and roles

| Caller | Path | Authentication | Role |
|--------|------|----------------|------|
| Admin | `/admin/**` | JWT | ADMIN |
| Tenant admin (12) | `/tenant/admin/**` | JWT | TENANT_ADMIN (and TENANT_USER) |
| Tenant user | `/tenant/**` | JWT | TENANT_USER |
| Client software | `/api/v1/**` | API key `ai_` | TENANT |
| Claude Code & co. (10) | `/mcp` | API key `ai_` | TENANT (same keys, possibly with a purpose) |
| Widget (7) | `/widget/**` | short-lived widget token, issued to the client software | WIDGET (new) |
| Branding (5) | `/branding` | anonymous | - |

Each new path keeps the rule "one controller, one role, matching its path"; `TenantApiKeySpec` grows with it.

### Where data lives

The rule of tenancy.md stays: what the admin manages or bills → management database; what a tenant owns →
its database.

| Data | Database | Point |
|------|----------|-------|
| Models a tenant may use (`tenant_model`) | management | 11 |
| Branding (if per tenant) | management | 5 |
| MCP servers of a tenant (URL, encrypted auth, enabled tools) | management - like the tenant's provider keys | 2 |
| Sources and mailboxes (type, encrypted credentials, interval, last run, error) | management - the scheduler reads them for all tenants, the admin sees failures | 8, 9 |
| Widget settings (allowed origins, limits) | management | 7 |
| Documents with `origin`, `scope`, owner; chunk position | tenant | 1, 4, 8, 9 |
| A source's file list (path, ETag/hash → document), a mailbox's seen Message-IDs | tenant | 8, 9 |
| Workflow definitions, runs, checkpoints, approvals | tenant | 6 |

### Package layout when everything is built

Layer first, then feature, as today. New feature packages:

```
controller/  branding/ (5)   mcp/ (10, the server)   widget/ (7)   source/ (8, 9)   workflow/ (6)
services/    ai/ + tenant model access (11), new providers (3)
             document/ + KnowledgeSearch (4, 1)
             tools/ + McpTool (2)
             mcp/      McpClients - connections to the tenant's MCP servers (2); the server's tools (10)
             source/   SourceSync + one class per type: S3Source, SftpSource, SharePointSource, DropboxSource,
                       MailboxSource (8, 9) - e-mail is one more source type, not a world of its own
             agent/    AgentLoop (built), workflow/ (6)
             common/   + the outbound address check
entities/, repositories/, dto/   one sub-package per new feature, as always
```

Frontend: the admin's tenant dialog gets the model access (11); the tenant area gets the pages "Quellen" (8, 9),
"Verbindungen" (MCP servers, 2), later "Workflows" (6). Branding is applied at start (CSS variables, title, logo). The
widget (7) is a separate small build next to the app (an Angular library/second project or a plain web component) -
decided with point 7.

### Order

1. **11 Model access** - small, admin-side, and point 3 builds on the same catalog.
2. **5 Branding**, **4 Search results** - small and visible; 4 brings `KnowledgeSearch`.
3. **3 Own model servers** - new providers in the catalog, now with model access per tenant.
4. **1 Knowledge scopes** - `origin`/`scope` on documents before more sources fill them.
5. **2 MCP client**, then **10 MCP server** - the same protocol from both sides; 2 brings the outbound address
   check and the confirmation step.
6. **9 E-mails** and **8 other sources** - one source framework; mailbox first or S3/SFTP first (to discuss).
7. **7 Widget** - new role, separate build.
8. **6 Workflows** - uses all of the above and the `AgentLoop`.

## 11 Model access per tenant

**Goal**: the admin assigns each tenant the models it may use - e.g. only Gemini and only one model of it. A tenant
sees, chooses and calls only those, with its own key or the admin's.

**First thoughts**: a table `tenant_model (tenant_id, model_id)` in the management database; the tenant dialog gets
a model list with check boxes. `AiModelService.requireUsable(tenant, id, kind)` replaces `requireActive` for the chat
and the embedding model; the model lists of `/tenant/ai/models` and `/api/v1/models` show only the assigned ones; the
provider-key page only the providers of assigned models. Removing the model a tenant embeds with is refused (409),
like deleting it today.

**To discuss**: a new tenant gets no models or all active ones? Existing tenants get all active models with the
migration. Does the transcriber (audio at upload) need the assignment too, or is it a service of the installation?

## 12 Tenant admins

**Done** (6 October 2026) - see docs/architecture/tenancy.md "Who does what". Every company has tenant admins (a
tenant user with `admin`, role `ROLE_TENANT_ADMIN`, API `/tenant/admin/**`) who keep its users, provider keys, client
software keys, embedding model, company knowledge and usage, and chat like everyone; plain users only chat. The admin
keeps doing all of it per tenant and alone decides which models a tenant may use and whether it may use the global
keys. This replaced point 1: there are no documents of single users - all documents are the company's knowledge,
found by every user without seeing its list.

## 13 Documents pushed by the client software

**Done** (6 October 2026) - see docs/architecture/ai-features.md "Documents pushed by the client software".

**Goal**: third-party software pushes its documents through `/api/v1/documents` again and again; today a new version of
the same file becomes a second document and the chat finds both.

**First thoughts**: an optional `externalId` per upload (the software's own key, e.g. an article or file number),
unique per tenant: an upload with a known `externalId` replaces that document and its chunks; `DELETE` by
`externalId` too. The admin's list shows where a document comes from (`origin`: UPLOAD, API) - the same "replace
instead of duplicate" that point 8 needs.

## 1 Knowledge scopes (replaced by 12, the areas by 16)

**Goal**: tenant-wide documents (the company's knowledge base) and documents of single users; a switch per tenant
(or per user) so that the chat only finds the tenant-wide ones.

**Today**: every document belongs to the tenant - any of its users uploads, all of them find it. There are no
per-user documents yet.

**First thoughts**: a `scope` per document (`TENANT` / `USER` with the owner's id) stored as chunk metadata;
`KnowledgeSearch` filters by metadata (tenant-wide plus, if allowed, the asking user's own). Who may upload
tenant-wide documents (all users, or only some - a role "Wissensverwalter")?

**To discuss**: is "global" meant per tenant (as above) or across all tenants (knowledge the admin maintains for
everyone, e.g. legal texts)? Both fit the same metadata filter.

## 2 MCP client tool

**Done** (7 October 2026) - see docs/architecture/mcp.md "MCP clients to the tenant's software".

**Goal**: the chat (and later workflows) call tools of external MCP servers, e.g. an ERP's for prices and
orders.

**First thoughts**: langchain4j's MCP module (`langchain4j-mcp`: clients over Streamable HTTP/SSE) - per tenant a
list of MCP servers (URL, auth header encrypted like the provider keys, enabled tools). Their tools appear in the
`Toolbox` as tools named with a prefix per server. The tenant's API key for the other software stays on our side.

**Suggested in two steps**: first only tools that read (MCP's `readOnlyHint`; a server that does not mark its tools
gets a per-tool switch in our settings) - no confirmation needed. Tools that change something come with the
confirmation step (see the target architecture), which the chat and workflows (6) share.

**To discuss**: who enters the servers (the tenant, like its own provider keys - with the admin able to see and
disable them). Timeouts and how errors of the external server reach the model.

## 3 Own model servers (Ollama, OpenAI-compatible)

**Built (6 October 2026)**: providers `OLLAMA` (native, with the context length per request) and `OPENAI_COMPATIBLE`,
address and key at the catalog entry, HTTPS only, the key bound to its host, no redirects, the search run beforehand
for models without tools - see docs/architecture/ai-features.md "Own model servers". The thoughts below were the
starting point.

**Goal**: run with models on own hardware or with smaller providers, without Google/OpenAI/Anthropic.

**First thoughts**: two more providers in the catalog - `OLLAMA` (langchain4j-ollama, chat and embeddings) and
`OPENAI_COMPATIBLE` (any server with the OpenAI API: vLLM, LM Studio, LocalAI, Mistral, ...). The catalog entry gets a
**base URL**; the API key becomes optional. Capabilities (tools, images, PDF, audio) per model as today - many local
models cannot call tools well, which matters since the chat relies on tools. With 11, such a model is assigned only to
the tenants it is meant for.

**Decided by the architecture**: the base URL is part of the admin's catalog entry; a tenant's own server becomes an
entry assigned to that tenant alone (11). No tenant-entered URLs for models, so no SSRF question here.

## 4 Better search results ("chunk padding")

**Goal**: a hit brings enough context - today a passage of 1000 characters can cut off what belongs to it.

**Options** (to discuss, they combine):
- **Neighbour chunks**: store each chunk's position in its document; return a hit together with the chunks before and
  after it (merged, without duplicates). Cheap, no extra model call; needs re-embedding once (for the position).
- **Hybrid search**: a full-text search (`tsvector`) next to the vector search, merged by rank: exact keywords
  (article numbers, names) are found even when the meaning-based search misses them. langchain4j's store owns the
  embedding table, so this is own SQL on that table - `KnowledgeSearch` is the one place for it.
- **Larger chunks or a parent/child split**: search small chunks, return the larger section they belong to.
- **Re-ranking**: fetch more candidates, let a model pick the best - better results, but a call more per search.

**Agreed (6 October 2026)**: measure first, then improve one step at a time, each measured. Built so far:
`KnowledgeSearch` (extracted from the tool, behaviour unchanged) and the measurement with a fictional test company
(docs/architecture/ai-features.md "Search"). Baseline (text-embedding-3-small, vector only): answering passage first 56 %, among the first five 91 %; misses on
exact codes and table rows; questions without an answer are not told apart by score. Steps, each measured:
1. hybrid search (full text with the German configuration, any word, merged by rank, full text weighted 0.3) - **done**:
   first 56 % to 68 %, among the first five 91 % to 94 %, MRR 0.71 to 0.77; langchain4j's hybrid mode measured as well
   (no gain with questions in sentences);
2. a context header per chunk (file name and the document's title - headings are not in Tika's plain text) - **done**:
   hybrid 0.3 among the first five 94 % to 97 %, table rows 60 % to 100 %, similar documents first 80 % to 100 %, first
   hit 68 % to 65 % (one question); the vector search alone gains too (first 56 % to 59 %). Overall since the baseline:
   first 56 % to 65 %, among the first five 91 % to 97 %;
3. ~~a relative cut-off instead of the fixed minimum score~~ - dropped: the measurement shows scores cannot tell;
4. neighbour chunks around a hit - only if a measurement shows missing context (long step lists are found already).
5. an HNSW vector index, with embedding models limited to 2000 dimensions (asked of the provider) - **done**
   (performance for large tenants, quality unchanged by design).

**Later, when needed**: the questions as the chat model's short search terms too (closer to real use), a larger test
corpus (with 39 chunks "among the first five" is 13 % of everything), neighbour chunks if a measurement shows missing
context, re-ranking.

## 5 Branding

**Goal**: an installation shows its own name, colours and logo instead of "Alpdesk AI" in green.

**First thoughts**: settings `app.branding.*` (name, primary colour, logo) from the environment / `.env`; an anonymous
endpoint `GET /branding` that the frontend reads at start and applies as CSS variables (Angular Material 3 works with
them) and as page title / favicon. No rebuild needed.

**To discuss**: per installation (one customer per server) or per tenant (each company sees its own look after
login - then the login page cannot know it yet)?

## 6 Workflows per tenant (langgraph4j)

**Goal**: fixed processes with branches, tools and approvals - e.g. answering customer requests with a staff member's
release, checking incoming invoices, recording defects.

**First thoughts**: workflows as graphs (langgraph4j) built from our tools and the `AgentLoop`; runs with checkpoints
in the tenant database so a run can wait for an approval and continue; an approval list in the frontend; triggers by
API, schedule or a new document from a source or mailbox (8, 9). The chat stays as it is.

**To discuss**: are workflows templates we write (configured per tenant) or does a tenant build them itself (an
editor - much larger)? Which first workflow?

**Since 13 October 2026**: started by point 28 - a task is a graph in our JSON format, drawn in an editor, run by
langgraph4j with the `AgentLoop` as a node; workflows build on that format, approvals are its next step.

## 7 Embeddable chatbot

**Goal**: a chat window to embed - into the client software (staff) or a public website (end customers).

**First thoughts**: a small web component / script tag. The tenant API key must never reach a browser: the client
software's backend fetches a short-lived token (scoped to the chat, optionally to one user) and hands it to the
widget; for a public website a widget key bound to allowed origins, with rate limits and without file creation or
internal tools (the `caller` WIDGET in the `ToolContext`).

**To discuss**: staff inside the client software, or end customers on a website - or both (then two modes with
different tools and knowledge)?

**Proposal** (10 October 2026, agreed the same day - see "Decided" below): end customers on the tenant's website first. Staff inside the client
software are covered by `/api/v1/chat` today (the client software builds its own UI); staff who may change something
through the chat get the normal chat embedded - point 21.

- **An iframe served by us, no API calls from the website's page**: the website holds only
  `<script src="https://<host>/widget.js" data-widget="w_...">`, which adds a button and an iframe on
  `/widget/{publicId}`. Which websites may embed it is `Content-Security-Policy: frame-ancestors <allowed origins>` per
  chat box - every call stays on our origin, no dynamic CORS. The public id is no secret: what protects is how little
  the chat box may do and its limits.
- **What it may do** (a new asker `ToolContext.Asker.WIDGET`): search **only its own knowledge area** (e.g.
  "Website") - not the company knowledge, which is for everyone else; the reading tools of connections opened to the
  chat box (`mcp_connection.widget_reads`, a row "Chatbox" next to "MCP-Server" and "API-Keys"); never changing tools,
  created files or uploads. Model, instructions and greeting per chat box. A connected software gives the chat box a
  connection of its own: an endpoint with only the tools meant for end customers (for a booking software: availability
  and rates, no bookings of other people) and a key of its own - so opening a connection is enough, no switch per tool.
- **Data** (management database): `chat_widget` - tenant, name, public id (`w_...`), allowed origins, knowledge area,
  model, instructions, greeting, active, limits (questions per visitor and hour, per day). Usage is recorded as now,
  with the chat box as its source.
- **No history stored**: the conversation lives in the visitor's browser (`sessionStorage`) and its last rounds go with
  every question; only the usage is stored. Visitors write personal data into a chat, and the tenant would otherwise
  hold an archive of them.
- **API**: `GET /widget/{publicId}` (the iframe's page, anonymous, with `frame-ancestors`), `POST
  /widget/{publicId}/session` (anonymous: a short-lived token, `ROLE_WIDGET`, tenant and chat box, one hour), `POST
  /widget/chat/stream` (server-sent events like the chat, `ROLE_WIDGET`). Managed by the tenant admins
  (`/tenant/admin/widgets`) and the admin (`/admin/tenants/{id}/widgets`), one shared panel with a `TenantScope`.
- **Abuse**: a limit per session token, per chat box and day, and per client address - the one Caddy forwards, trusted
  from the proxy only. A chat box switched off or a deactivated tenant answers 401 at once.
- **UI**: a page "Chatbox" for the tenant admins and a tab on the admin's tenant page: chat boxes with origins, area,
  model, instructions and limits, the **snippet to copy** and a preview. The chat box itself is a slim lazy route with
  the tenant's branding and the chat's Markdown; `widget.js` a few lines of plain JavaScript, no Angular.
- **Docs and specs**: a new `docs/architecture/widget.md`; `SECURITY.md` (new anonymous routes), `docs/privacy.md`
  (what a chat box keeps), `mcp.md`. Specs for the session, the limits, tenant isolation, the area (the company
  knowledge stays unseen), reading tools only, `frame-ancestors`, a chat box switched off; the smoke test asks a chat
  box once.

**Open questions**: (1) end customers first, staff later? (recommended); (2) no history - or may the tenant read the
conversations for a while (e.g. 30 days) to improve the answers?; (3) the chat box's own texts (button, placeholder):
visitors are often not German-speaking - set per chat box by the tenant? (the model answers in the visitor's language
anyway); (4) several chat boxes per tenant (per website or language) or exactly one?; (5) a captcha only when bots show
up? (recommended - the limits first).

**Decided** (10 October 2026): (1) end customers on the website first, staff later; (2) no history stored - only the
usage; (3) the chat box's texts (button, placeholder, greeting) are set per chat box, with German defaults - several
chat boxes cover several languages; (4) several chat boxes per tenant; (5) no captcha - the limits first.

**Noted for building**: `KnowledgeScope` today always includes the company knowledge (documents without an area) - the
chat box needs a scope of its area alone, and the search's SQL must honour it.

## 8 Documents from other sources

**Goal**: keep the knowledge base filled from where the documents live: SFTP/FTP, SharePoint (Microsoft Graph),
Dropbox, S3-compatible storage.

**First thoughts**: per tenant "sources" (type, credentials encrypted, path/folder, interval); a scheduled sync
compares (path, size/ETag/hash), embeds new and changed files and removes deleted ones; status and errors per source
in the frontend. One class per type - start with S3 and SFTP (simplest), then SharePoint and Dropbox (OAuth).

**To discuss**: which sources first; files of a source in the tenant-wide scope (1)?

**Since 13 October 2026**: the file stores are built with point 28 (SFTP and FTPS, several per tenant -
[architecture/file-stores.md](architecture/file-stores.md)); a source for embedding is one more use of a store, and
belongs here (moved from 28 on 27 October 2026). The further types (S3, SharePoint, OneDrive) come with
point 44 - this point then only syncs a store's folder into the knowledge.

**Built 27 October 2026 (1.9.0)** - [architecture/knowledge-sources.md](architecture/knowledge-sources.md). The proposal, agreed the same day: built
**before** point 44 on SFTP and FTPS, reading only through `FileStoreSession` - so every type of 44 serves the tasks and
the sources alike. Decided before it was written: a source of its own on the page "Firmenwissen" (not a task step, not
tied to the admin's task switch); the whole tree below the folder, the relative path the key; fixed limits per run,
the rest with the next run; the NAS script of point 37, step 2 dropped - a NAS is read as a file store. Its open
questions answered the same day: 100 files and 250 MB per run; a schedule from a fixed list (by hand, hourly, every 3, 6 or 12
hours, daily at night - first "free like the tasks'", changed the same day); a source's documents may be deleted (back when the file changes) and moved by hand; version 1.9.0. The first import:
after saving a source is only listed and counted, a run by hand reads everything open (cancellable), the limits hold
for scheduled runs only. Removing a source asks whether its documents go with it or stay as ordinary documents. Built with it: the indexer takes the
tenants in turn, so one tenant's large import no longer holds up another tenant's documents (within a tenant the order
stays as queued). A store out of reach skips the time (no retry before the next run, nothing
deleted), logs a warning and shows "nicht erreichbar seit ...". Files of an unsupported type are skipped by their extension
when listed (the check by content stays).

## 9 Documents from e-mails

**Goal**: e-mails and their attachments become documents of the knowledge base (and later the input of workflows).

**First thoughts**: one more source type (`MailboxSource`, IMAP) polled on a schedule; the body as text, attachments
through the existing extraction (Tika, images, scans); deduplication by Message-ID; the scope from 1.

**To discuss**: everything in a mailbox, or only certain folders/senders? How long e-mails stay (personal data).

## 10 Claude Code reading from Alpdesk AI

**Done** (7 October 2026) - see docs/architecture/mcp.md "The tenant's MCP server".

**Goal**: Claude Code (or Claude Desktop, other MCP clients) gets information from Alpdesk AI - the knowledge base,
documents, the chat.

**How**: Alpdesk AI becomes an **MCP server** itself: an endpoint `/mcp` (Streamable HTTP) with tools such as
`search_knowledge` (through `KnowledgeSearch`), `read_document`, `ask_assistant`, authenticated with a tenant API key.
Claude Code connects remote MCP servers out of the box
(`claude mcp add --transport http alpdesk https://<host>/mcp --header "Authorization: Bearer ai_..."`).
Implementation with the MCP Java SDK (or a Micronaut MCP module, if available then).

**To discuss**: which tools to offer; read-only or also writing (uploading documents)? A key per developer (to know
who asked)?

## 14 Sub-agents

**Goal**: specialists a tenant describes itself - e.g. one that analyses a machine's logs sent with a question, one
that checks offers against the company's terms. The chat's model knows them by name and description and hands a task
to one when it needs it (like Claude Code's subagents); the specialist works in a context of its own and returns only
its findings. What a trade needs comes as configuration, not as a specialist built into the core ("Industry-neutral"
in CLAUDE.md).

**Not before a measured case** (discussed 9 October 2026): the log analysis works today in the one agent loop
(overview, `search_file`, `read_file_lines`, up to 12 rounds - ai-features.md "Files sent with a question"). Whether its
results suffer from searching the log and checking it against documents and code in the same context and rounds is
not known yet. First measure with a handful of real logs of 3 - 4 MB through `/api/v1/chat`: rounds per answer, tokens
and cost in the usage, whether the 12 rounds run out, whether the answer is right and really checks against documents
and code. If it falls short, the cheaper levers come first: better instructions or a chat template, more rounds
(`app.chat.sent-files.rounds`), a model with a larger context. The sub-agent is built when these do not help - or when
a second, different specialist is asked for. A protocol and a script
were prepared on 10 October 2026 (its decision rule agreed; not run yet): five synthetic cases with hidden causes
against the retrieval test corpus.
- **Baseline, 10 October 2026** (Gemini 2.5 Flash, 5 synthetic cases x 2 runs): no failure, no run used up its
  rounds - 2 to 4 rounds (12 allowed), 3,000 to 63,000 input tokens, 4 to 15 s, 0.002 to 0.02 $ per answer
  (0.09 $ for all ten). Cause found and shown in the log: 1 of 10, partly 5, missed 4. The documents searched in 7
  of 10. The model read the logs superficially: at most two `search_file` (mostly for `ERROR`), `read_file_lines`
  once, never the status values a cause hides in (flue temperature trend, mains voltage, outdoor temperature); in 3
  runs it did not open the log at all and answered from the error code document. **Misses of the first kind**
  (the log read too little, with rounds and context to spare) - by the agreed rule no case for sub-agents; next
  lever: instructions (variant `template`).
- **Variant `template`, 10 October 2026** (the same, with a chat template telling it to look at warnings, maintenance
  and status values before and at each fault, read the lines before, compare files, check every fault in the documents
  and name the lines): 3 to 7 rounds, 11,000 to 135,000 input tokens, 10 to 21 s, 0.005 to 0.04 $ per answer (0.20 $
  for all ten). Cause found and shown in the log: 5 of 10 (was 1), partly 4, missed 1; the documents searched in 9 of
  10. It now reads around the faults and found the overdue maintenance, the frost and the low mains voltage - but not
  every time. Still misses of the first kind, with rounds and context to spare: no case for sub-agents, more rounds
  (`rounds-20`) would not help - none ran out. The levers left: such rules built into the assistant's own instructions
  for files sent with a question, and a stronger model.
- **Harder logs, 10 October 2026**: the generator got more noise that looks alarming (four kinds of stack traces,
  ignition retries that light on the second try, fan speed deviations, restarts with banners, out-of-order lines), and
  the overview of a large file lost its time stamps and log levels (`SentFiles` stays generic). Cause found and shown
  in the log, partly, missed (of 10): baseline 0 / 7 / 3 (0.07 $); template 2 / 5 / 3 (0.19 $); a **prototype
  sub-agent** (`explore_file`: the large files explored by the same model in a loop of its own, step by step - start,
  middle, end, recurring and rare lines, values around the moments that matter - its findings returned with line
  numbers; offered instead of `search_file`/`read_file_lines`) 1 / 6 / 3 (0.57 $, up to 98 s). The harmless ignition
  retries were taken for the cause (E01) in all three. The sub-agent did not help: the asking model briefed it
  narrowly from the error code document ("find all E and F codes"), three times it was not called at all, and once it
  reported "pressure stable" where the pressure fell. Not built on; the prototype stays uncommitted.
- **Stage 1 of a sub-agent with code execution, 10 October 2026** (agreed bar: 7 of 10; GPT-4.1 mini, the harder
  logs): `analyse_files` - the application calls it before the model is asked (GPT-4.1 mini never called it as a
  tool), the specialist gets the user's question word for word and has `run_python` (python3 of the host, prototype
  only, no sandbox) next to `search_file`/`read_file_lines`. Cause found, partly, missed: baseline 0 / 6 / 4
  (0.27 $), template 0 / 8 / 2 (0.32 $), **sub-agent 0 / 5 / 5** (0.56 $, 15 to 31 s). **The bar is missed**; the
  harmless ignition retries were again taken for the cause. The specialist ran 18 scripts in ten runs, mostly a look
  at the start, middle and end; once it grouped the lines by kind. Two faults of the tool, not of the idea: every
  script ran in a fresh process (the model expected its variables from the script before - "prior code context is
  lost") and a last expression was not printed (the first script of a run printed nothing).
- **The tool repaired, 10 October 2026**: one Python session per analysis like a notebook (variables kept from
  call to call, a last expression shown). With `search_file`/`read_file_lines` next to it: 0 / 6 / 4 (0.68 $) - the
  specialist ran 20 scripts but searched 34 times and read lines 60 times. **Python only**: 0 / 5 / 5 (0.30 $, 88
  scripts). The scripts did the right things at first - a look at the structure, the lines grouped by kind - and then
  went wrong in the reasoning: errors and warnings collected up to a limit of 20 lines, the frequent ignition retries
  compared between the files, the rare lockouts (E14, five lines) never looked at. **The limit is the small models'
  care, not the tools, the rounds or the context**: neither Gemini 2.5 Flash nor GPT-4.1 mini reached the bar in any
  variant.
- **A stronger model for the specialist only, 10 October 2026** (asking model GPT-4.1 mini, specialist GPT-4.1,
  Python only): 0 / 5 / 5 (1.00 $). Its reports, logged for two cases, showed why: for E23 a good report (every
  lockout with its line, the flue temperature rising before it, the noise told apart) that the asking model turned
  into a right answer; for E14 the specialist stopped after its first step with a plan and a question for the asker
  ("Soll ich weitermachen?") - the loop takes a text without tool calls as the end. With the rule "nobody can answer
  you, never ask, never stop at a plan" and up to two prompts to go on when it stopped before running code or ended
  with a question: **3 / 6 / 1** (1.50 $, 21 to 36 s) - the best of all variants, still below the bar. Right: E23 twice
  (lockouts, overdue maintenance, clean the heat exchanger first), E14 once (mains voltage 193 - 198 V in hall B
  against 228 - 230 V in hall A). Left: one more early stop (E14), the frost behind E28 and the refill behind E07 not
  correlated, and the asking model's answers keeping less than the reports held.
- **With a report tool, 10 October 2026**: the specialist ends its work only with `report(findings)`; a text
  without it gets up to three prompts to go on, with its whole course kept; the asking model is told to keep the line
  numbers and values of the findings. Every run handed in a report. **3 / 6 / 1** (3.43 $, 29 to 76 s, up to 21
  rounds) - the same as before at more than twice the cost; the one miss was OpenAI's rate limit (HTTP 429, the
  account's tokens per minute). Right: E23 (overdue maintenance, P.70), F12/F15 (the pressure swinging between 2.86
  and 1.00 bar, no water lost - the expansion vessel), E28 (the lockouts on frosty nights). **Stage 1 ends below the
  bar** (best 3 of 10): the specialist with code works only with a stronger model than the asking one, and even
  GPT-4.1 relates values over time only now and then (the refill behind E07, the mains voltage behind E14 missed in
  this run). Stage 2 (the sandbox) is not built; the next measurement waits for real logs or a stronger model.

**First thoughts**:
- A table `sub_agent` in the management database (it belongs to the assistant's settings): tenant, `name` (the tool
  name, `[a-z0-9_]`), `description` (for the main model: *when* to delegate), `instructions` (the specialist's system
  prompt: *how* it works), `model_id` (NULL = the asking chat's model), the tools it may use, `max_rounds`, `active`.
- One generic class (`services.agent.SubAgents` or next to `McpTools`), not one class per specialist: the `Toolbox`
  offers each active specialist as a tool (`task` as its parameter), as it offers the connected software's tools
  today. A call runs a loop of its own with the specialist's instructions, tools, model and rounds and returns its
  last text as the tool's result: `AgentLoop.run` (moved out of `ChatService` on 10 October 2026 - see below).
- **Several specialists of one round run in parallel** (decided 9 October 2026): the providers ask for several tool
  calls in one response (three logs of three machines → three calls of `log_analyse`); one after the other the user
  would wait for the sum, in parallel for the slowest. Parallel is not cheaper - the same rounds are billed.
  - `continueWithTools` runs a round's specialist calls as futures on an executor of their own (virtual threads - a
    specialist mostly waits for its provider) and collects the results in the order of the calls (the model matches
    them by call id). The other tools of the round keep running as today, on the loop's thread.
  - Every specialist gets a **child `ToolContext`** of its own: the context is not made for use at the same time
    (created files, sources, pending call are plain collections). Afterwards the loop's thread takes the children's
    sources into the main context, one after the other.
  - Limits: at most `app.chat.sub-agents.parallel` specialists at once per answer (3) and a bound over the whole
    installation (the executor's size) - otherwise one question fills the provider's rate limits and the tenant
    database's pool. Calls above the limit wait for a free slot.
  - A failing specialist is an error text for its own call; the others deliver.
  - The stream's progress events carry the call's id, so the frontend shows several specialists side by side.
- Rules from the start:
  - never more rights than the asker: the specialist's tools are the intersection of its configuration and what the
    asker may use (the user's levels, the client software's and the MCP server's opened tools);
  - reading tools only: a changing call stops an answer for the user's decision (`PendingAction`), which cannot be
    done in the middle of a specialist's loop without resuming it later;
  - no nesting: a specialist calls no specialists;
  - every round is recorded in the usage, marked as the specialist's, so the usage pages show what it costs;
  - a failing specialist is an error text for the main model (`ToolResult.error`), not a failed answer.
- The specialist's key: `ProviderKeyService.resolve(tenant, model)`, as `CreateImageTool` and `KnowledgeSearch` take
  theirs - the same key the chat got for the same model, and the one of the specialist's own model else. Nothing has
  to be carried in the `ToolContext` (decided 10 October 2026).
- `FakeAiModels` learns to act as a specialist (searches the file, reports a finding) for the specs.
- Frontend: a section "Spezialisten" on the page "Assistent", in the shared `assistant-panel` (`TenantScope`: tenant
  admin and admin) - name, "Wann einsetzen?", "Wie arbeiten?", model, tools as check boxes, maximum rounds, active,
  with a hint that the description decides whether the specialist is used.
- Not chat templates: a template is chosen by the user before the question and answers in the whole chat; a
  specialist is chosen by the model during the answer and works in a fresh context. Merging them only if the two turn
  out to overlap in practice.
- No langgraph4j for this: one more tool in the existing loop; graphs only with point 6 (and langchain4j's own agentic
  module compared there first). The proposal checks whether `langchain4j-agentic`'s supervisor brings anything a
  `ChatTool` starting a nested round of our own loop does not - it would need the same streaming, usage per call and
  stop (ai-features.md "What of langchain4j is used").
- **`AgentLoop` on langchain4j's `AiServices`?** (agreed 9 October 2026: as little own code as possible) When the loop
  moves out of `ChatService`, the proposal measures first whether `AiServices` with `TokenStream` and a `ToolProvider`
  can carry it: a spike on a branch, the chat specs green, own lines before and after. Known gaps in 1.21: no last
  round without tools (`maxToolCallingRoundTrips` throws), a changing call cannot stop the answer before it runs (only
  through `ReturnBehavior.IMMEDIATE`), the history with its notes needs a `ChatMemoryStore` of our own. Taken if it is
  clearly smaller without workarounds; else the own loop moves as it is. Tool results with files
  (`ToolExecutionResult.resultContents`) are checked there too: they could replace our extra user message.
  - **Checked 10 October 2026 against the sources of langchain4j 1.22 - not taken, the own loop moves as it is.** The
    gaps remain and each needs a workaround: the round limit still throws (`ToolService`,
    `AiServiceStreamingResponseHandler`) - a last round without tools only through a dynamic `ToolProvider` or a
    `chatRequestTransformer` counting the rounds from the messages, plus `ToolChoice.NONE` for Claude, which refuses a
    history with tool calls without the tools; `beforeToolExecution` only observes, `IMMEDIATE` returns only when every
    call of the response is immediate - a changing call cannot stop the answer without running or faking a result;
    `AiServices` needs `ChatModel` objects, so `FakeAiModels`, which replaces `AiModels` at our calls, would have to
    become one; `StreamStop` would need a wrapper around the `StreamingChatModel`. Usable but no reason on its own:
    `TokenStream.onIntermediateResponse` (usage per round). Files in tool results: `ToolExecutionResultMessage.contents`
    exists, but OpenAI's chat completions and Ollama refuse anything but text there - our extra user message stays.

**Groundwork done (10 October 2026)**, independent of the measurement: the agent loop is `services.agent.AgentLoop`
(`run` in one piece, `stream` streamed, one round step), its three copies in `ChatService` merged; behaviour unchanged.

**Left open on purpose (11 October 2026)** - the sub-agents themselves are not built; the branch `feature/subAgents`
brought their groundwork only. Adding them later is a contained job, not a redesign (about a week, M-L):
- **There already**: `AgentLoop` runs a loop of its own with its own model, tools and usage per round; the `Toolbox`
  offers a specialist to the asking model as one more tool; code execution in the tenant's own sandbox (26); what the
  prototypes taught - the report only through a `report` tool, no ending at a question or a plan, the user's question
  handed over word for word.
- **To build**: the specialists per tenant (name, description, model, instructions - data, service, admin page,
  specs), the tool that runs the specialist's loop and returns its report (stop, logout and time limits reaching into
  that loop), its usage shown per specialist (a call cost 3 to 10 times an answer in the measurements).
- **The risk is the quality, not the code**: no prototype reached the agreed 7 of 10; the best, 3 of 10, came only with
  a stronger model for the specialist. Before building, measure again with a strong model for the specialist - with
  real logs instead of the synthetic ones where they can be had.

**To discuss**:
- Does the stream show the specialist's work ("Spezialist ‚Log-Analyse' arbeitet ... Runde 3/10")? Recommended -
  otherwise the user looks at an empty answer for a minute, and a proxy's idle timeout may end the stream. The stream
  has no heartbeat (checked 10 October 2026: only `delta`, `thinking`, `sources`, `done`, `error`; silent while a model
  thinks without handing its reasoning over and while tools run). It comes with the specialists' progress events,
  unless a stream is cut off before.
- Does a stop or logout end the specialist, or does it run on like the main answer does today (and keep costing)?
- Specialists for the client software (`/api/v1/chat`) and the MCP server's `ask_assistant` too - the log analysis is
  exactly that case - as a conscious decision of the tenant API.

## 15 Prompt caching

**Goal**: every round of the agent loop sends the system prompt, the tools and the history again - with caching the
provider reads that repeated start from its cache, cheaper and faster. A question with several rounds costs less.

**Today**: we ask for no caching. OpenAI and Gemini cache long prompts by themselves and bill the cached part
cheaper; we record and bill all input tokens at the model's one input price. Anthropic caches only when asked:
langchain4j 1.22 (8 October 2026) brings automatic prompt caching with a cache duration for its Anthropic models.

**First thoughts**:
- Switch on Anthropic's automatic caching in `AiModels` (one setting per model build).
- Usage: the providers bill cached tokens differently - Anthropic writing to the cache dearer, reading from it much
  cheaper than normal input; OpenAI and Gemini reading cheaper. `usage_record` gets the cached input tokens (read
  and written) as columns of their own, and the catalog prices for them per model - or a factor of the input price.
  langchain4j reports them in the provider's `TokenUsage` subclass.
- Without the usage part, switching caching on makes the recorded amounts wrong: never one without the other.

**To discuss**: prices for cached tokens per model in the catalog, or one factor per provider? Do the usage pages show
the cached part? Measure first what a typical answer with tools saves (rounds per answer, share of repeated input).

## 16 Knowledge areas

**Done** (9 October 2026) - see docs/architecture/ai-features.md "Knowledge areas". Built as proposed below, with the
three open questions decided: a key pushes only into its own areas (400), the chat does not name a
source's area, no areas from a ZIP's folders. Added on request: deleting every upload of the company knowledge or of
an area at once, before its ZIP is imported anew (the client software's documents stay). Decided after the code
review the same day: a push without `area` keeps a known document's area (`area=""` = company knowledge), the client
software's conversations belong to the API key that started them, and the search finds only READY documents. Where the
proposal below differs from the build (table `tenant_mcp_area`, `KnowledgeScope` instead of an asker, a target menu
instead of a preselected field), ai-features.md "Knowledge areas" holds what was built.

**Goal** (asked for on 9 October 2026): the company knowledge stays what every user finds; on top a tenant keeps
**areas** ("Vertrieb", "Geschäftsleitung", "Werkstatt") whose documents only the users and keys assigned to them find.
Within one tenant - knowledge shared across tenants is not part of it.

**decided (9 October 2026)**:
- A document without an area is company knowledge, found by everyone - the default of every upload and push.
- Tenant admins find everything in the chat, every area, assigned or not (they keep all documents anyway).
- The client software's API keys and the MCP key get areas of their own, from the start.
- `/api/v1/documents` takes an optional area; without one the document lands in the company knowledge.
- A user (or key) losing an area finds its documents no longer in new questions; answers already given stay in the
  conversations as they are (docs/privacy.md says so).

**Proposal**:
- **Data** (management database, where users and keys are; a tenant database refers to no other's rows):
  - `knowledge_area`: `tenant_id`, `name` (unique per tenant, case-insensitive), `created_at`.
  - `tenant_user_area`, `tenant_api_key_area`, `tenant_mcp_key_area`: who is assigned to which area (foreign keys,
    `ON DELETE CASCADE` on both sides).
  - Tenant database: `document.area_id BIGINT` (NULL = company knowledge; no foreign key across databases, like
    `conversation.template_id`).
  - As new migrations `V2__...` in both sets, not by extending `V1`: the developer's dev database (with the test
    tenant's corpus) is migrated instead of recreated.
- **The rule exists once**: `KnowledgeSearch.search` takes **who asks** (an `Asker` with user id, API key id or MCP key)
  instead of the bare user id and resolves the areas it may see: company knowledge plus the assigned areas; everything
  for a tenant admin. The chat, `/api/v1/chat`, the MCP server's `ask_assistant` and later sub-agents (14) and
  workflows (6) all come through it - a sub-agent sees what its asker sees.
- **The filter is the document's area, not a copy in the chunks**: both searches join the chunk's `document_id` to
  `document` (`area_id IS NULL OR area_id = ANY(?)`, or no filter for a tenant admin). Moving a document to another
  area is then one row, no embedding again. The vector search moves from langchain4j's store to SQL of our own for that,
  like the full-text search already is (a few lines).
  - To measure first: an HNSW index filters *after* the index scan - an area holding most chunks, or a user with
    only a small area, could get fewer than the asked number of hits. pgvector 0.8 (in our image) scans on until
    enough rows pass (`hnsw.iterative_scan`); the retrieval measurement gets a variant with an area to check it.
- **API**:
  - Areas: `GET/POST/PUT/DELETE /tenant/admin/knowledge-areas` and the admin's mirror
    `/admin/tenants/{id}/knowledge-areas`. Deleting an area that still holds documents answers 409 - moving them to
    the company knowledge on the quiet would show them to everyone.
  - Documents: `areaId` in the response and on upload (multipart field), for a ZIP import one area for the whole
    archive, `PUT .../documents/{id}/area` (`{areaId}` or null) to move one.
  - Users, API keys, MCP key: `areaIds` in request and response of the existing endpoints.
  - `/api/v1/documents`: optional field `area` - by **name** (the client software knows no ids; an unknown name answers
    400). `GET /api/v1/documents` lists only what the key may see. A push with a known `externalId` and another area
    moves the document.
- **Frontend** (shared panels with `TenantScope`, so the admin and the tenant admins get it together):
  - Page "Firmenwissen": a panel "Bereiche" (add, rename, delete), a column and a filter "Bereich" in the document list,
    the area in the upload and ZIP dialog ("Firmenwissen" preselected), "Bereich ändern" in the row's ⋮ menu.
  - Page "Benutzer": the areas as check boxes in the user dialog, a column in the list. A tenant admin's dialog says
    "sieht alle Bereiche" instead.
  - Page "API-Keys": the areas of every client-software key and of the MCP key.
- **Specs**: search per asker (user with and without an area, tenant admin, API key, MCP key - each finds exactly its
  documents), a user losing an area, moving a document without embedding it again, an area of another tenant (404),
  validation (name, unknown area, unknown name at `/api/v1`), deleting an area with documents (409), the role rules in
  `TenantApiKeySpec`; Vitest for the dialogs' utils.
- **Docs**: ai-features.md "Documents" and "Search", tenancy.md "Who does what", the `/api/v1` list in CLAUDE.md,
  docs/privacy.md (answers stay after an area is taken away).

**To discuss**:
- May a key push into an area it is not assigned to? Recommended: no (400) - a key writes only where it may read,
  plus the company knowledge.
- Does the chat say which area a source comes from ("Vertrieb: preisliste.pdf")? Recommended: no - the path is
  enough, and the area is a matter of rights, not of content.
- Areas from the ZIP's folders (one area per top folder)? Recommended: not now - one area per import is simpler, a
  folder rule can follow when a tenant asks.

## 17 File templates

**Goal** (asked for on 9 October 2026): "create me a base program after specification XY" answers with the company's
own zipped Gradle project, adapted (version, package name, project name, ...) and handed out as a file - not with a
project the model makes up. The same for an invoice after the company's template, and any other document of a fixed
form. The model understands the request and fills in values; the server builds the file.

**Why not the knowledge base**: the search hands the model excerpts of 1000 characters, five at a time - a project
would come back as fragments and the model would invent the rest. The ZIP import splits an archive into single
documents, source files (Java, Groovy, C) are no allowed type, and nothing gives a ZIP back.

**decided (9 October 2026)**:
- The placeholders are defined freely per template - their names, descriptions and rules are the template's, not a
  fixed list of ours.
- A new ZIP (or DOCX) replaces the template - no history of versions.
- Templates belong to the company knowledge or to a knowledge area (point 16): whoever finds the area may use its
  templates - users, the client software's keys and the MCP server alike.

**Proposal**:
- **A template** (management database, `file_template`): tenant, area (NULL = everyone), `name` (also what the model
  calls it by), `description` ("when to use it" - the model chooses by it), the file (ZIP or DOCX, file storage
  `<tenant>/templates/<id>`), and its **parameters**: name, description for the model, type (text, number, date,
  list), required, default, a rule (a regular expression, e.g. a Java package name) and an example.
- **Placeholders**: `{{name}}` in the text files of a ZIP and in its paths (`src/main/java/{{packagePath}}/App.java`),
  and in a DOCX's text. The markers are a setting of the template (`{{ }}` by default) - a project whose files contain
  `{{` themselves (Mustache, Angular) chooses others. Derived values are parameters too, computed by the server
  (`packagePath` from `packageName`) - written down per template as "derived from", not computed by the model.
  Uploading a template lists the placeholders found in it, so the admin only describes them; one found but not
  described, or described but not found, is shown.
- **Building is deterministic**: `services.template.FileTemplates` copies the template, replaces the placeholders in
  the text files (recognised by content, like the documents - binary files such as `gradle-wrapper.jar` are copied
  unchanged), renames the paths, packs the ZIP and hands it to the chat's created files (`ToolContext.createdFiles`) -
  the download below the answer, as today with `create_file`. Read with the zip-bomb and path rules of
  `DocumentArchive` (no `..`, limits on entries and size).
- **The model's tool** `create_from_template(template, values)`: offered with the templates the asker finds (their
  names, descriptions and parameters in the tool's description, or `list_templates` once there are many). The server
  checks every value against its rule; a missing or wrong one is a tool error the model reads - it asks the user or
  corrects itself. It writes no file of the project.
- **Page "Vorlagen"** (shared panel with `TenantScope`, tenant admins and the admin): upload or replace the file, the
  area, the description, the found placeholders with their descriptions and rules, a test with sample values that
  downloads the result.
- **Specs**: building a ZIP (placeholders in files and paths, binary files untouched, derived values, other markers),
  refused values, an area's template not offered outside it, replacing the file, the tool through `FakeAiModels`.

**Second step - the model adds to the project** (only when asked for): a tool `add_file(path, content)` into the
project being built, limited to the folders the template allows, and the answer names the files the model wrote. The
company's coding rules and examples then come from the knowledge base as Markdown - that is what it is good for.

**Invoice after a template** (with "An invoice document" of ai-features.md "Not built yet"): the same template, a DOCX
with placeholders and **repeated rows** (the positions) - a library that fills Word templates is needed (its license
checked against `allowed-licenses.json`), output DOCX or PDF. **The server calculates**, never the model: sums, VAT,
rounding. The invoice number comes from the client software (its MCP tool) or a counter of ours - not from the model.

**Size**: templates from ZIP and text with the tool and the page M (about a week); the invoice with rows, calculation
and PDF another M-L; the second step S-M.

**To discuss**:
- Which library fills DOCX templates (rows, tables, images) - and does the invoice need PDF/A?
- Do the client software (`/api/v1/chat`) and the MCP server get the created ZIP as a download (the API's chat files
  exist) - or only the frontend's users at first?
- An upper limit for a template's size (the ZIP import takes 512 MB, a template should be far smaller - 50 MB?).

## 18 Plans as PDF

**Goal** (asked for on 9 October 2026): architects', craftsmen's and engineers' plans exported as PDF are found in the
chat with what they show - room heights, where the technical room is, dimensions and labels - not only as loose
words. Reading only, decided the same day: no CAD formats (DWG, DXF, IFC), no 3D models, no plans drawn by the AI -
those wait for a concrete requirement.

**Today** (ai-features.md "Images and scans"): a plan exported from a CAD program has text (labels, dimensions, the
title block), so Tika's text is embedded - loose numbers and words - and with 200 characters or more the page is not
read as an image (`ScanReader.MIN_PAGE_TEXT`): the drawing is lost. A page read as an image is rendered at most 2048
pixels long (`DocumentImages.MAX_EDGE`): an A0 plan at about 44 dpi, its dimensions and small labels unreadable.

**First thoughts**:
- A switch on upload (and for a ZIP import), "als Zeichnung lesen": every page is read by the image model, whatever
  text it has; the text Tika found is kept next to the reading. Stored per document, so embedding it again does not
  read it again (as today).
- Large formats in **tiles**: a page larger than A3 is rendered in overlapping tiles at a readable resolution (e.g.
  150 dpi), each read on its own, plus one view of the whole page for the layout; the readings are joined per page
  ("--- Page n, tile 2/12 ---"). Limits per page and document, so one plan set cannot cost hundreds of calls unseen;
  the usage shows the calls as today.
- The instructions of the image model for drawings: title block, rooms with labels and dimensions, levels, symbols
  and the legend, scale - in the language of the plan.

**Not before measuring**: a few real plans of a tenant (with its consent) and 10 to 20 questions its people would
ask - with the switch alone, then with tiles. The tiles are built only if the switch alone does not answer them.

## 19 Faster full-text search for large tenants

**Goal** (agreed 9 October 2026 - later, not now): the knowledge search stays fast in a tenant with thousands of
documents. Built when a tenant with several thousand documents is in sight, or a real one searches measurably slowly.

**Measured** (`/alpdesk-load-test`, size "Schnell", 9 October 2026, Testcontainers PostgreSQL on a developer machine):
the vector side is not the problem - from 20,000 chunks on it takes the HNSW index, 3-7 ms. The full-text side grows
with the chunks: median 153 ms / p95 764 ms at 10,000 chunks (~290 documents), 308 / 1,506 ms at 20,000 (~570).
`EXPLAIN ANALYZE`: the GIN index finds the matching chunks in under a millisecond (3,000 at 20,000 chunks), then
`ts_rank_cd(to_tsvector('german', e.text), ...)` parses the text of **every** match again - the index holds no
`tsvector` - which is nearly all of the time. Extrapolated linearly: about 1.5 s at 3,000 documents, 3 s at 6,000, per
search, and the chat often searches several times per question. The test's made-up vocabulary has very common words
(a question matches 10-15 % of the chunks); real texts have such words too ("Kessel", "NK" - see `KnowledgeSearch`).
Today it is not noticed: the model's rounds take seconds, a tenant with a few hundred documents searches in 0.3 s.

**First thoughts**:
- A stored generated column on the table `embedding` (`TenantEmbeddings.createIndexes`): `text_vector tsvector
  GENERATED ALWAYS AS (to_tsvector('german', coalesce(text, ''))) STORED`, the GIN index on it instead of the
  expression, and `searchText` matching and ranking on the column - nothing is parsed at search time. Expected several
  times faster; not measured yet.
- The table is created by the code, not by Flyway: an existing tenant's table gets the column on start or on first use
  (`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, rewrites the table once - for a large one that takes a moment). More
  space per chunk (the stored vector).
- Measure before and after with `/alpdesk-load-test` (the report's "One large tenant: searching" and query plans); the
  retrieval measurement must find the same passages (same ranking function, same text).

**Not part of it**: changing what the full text finds or how both sides are merged - only how fast.

## 20 Web crawler

**Goal** (discussed 9 October 2026 - on hold): a website, e.g. a product's online documentation, becomes part of a
tenant's knowledge and stays up to date.

**Options discussed**:
1. Fetch at question time: a chat tool `fetch_url` reads a page when the model needs it - nothing indexed, every
   question pays for the fetch, the search does not find it.
2. Crawl and index (like Cursor's @Docs or Dify): a start URL with sitemap, a URL prefix and a page limit, the main
   content of each page extracted, the URL as `externalId` (so a recrawl replaces a page), the outbound address check
   against internal networks (SSRF).
3. Use the source instead: many documentation sites are built from Markdown in a public repository - a ZIP upload, or
   a sync script of the client software through `/api/v1/documents` with `externalId`. Works today.

**Recommendation**: 3 now; 2 once a second source without a repository shows up. Close to 8 (other sources): a
crawled site is one more source with the same compare-and-sync.

## 21 Staff chat embedded in the client software

**Goal** (10 October 2026): the client software (e.g. a booking software's back office) shows the chat to its staff,
and the chat may change records there through the connected software's changing tools - each change confirmed by the
person who asked.

**Why not `/api/v1/chat`**: it knows no user, so it gets reading tools only - nobody could confirm a change. Extending
it (a user per question, the waiting call in the response, run/cancel endpoints, streaming) would cost us about as much,
and every vendor would rebuild the chat and its confirmation - which could then be clicked away by its own code. The
API stays the way for reading (works today); a native variant with users and actions comes only when a vendor asks for
one.

**Proposal** (not agreed yet): the normal chat in an iframe, signed in as a user of the tenant.

1. **The client software signs its staff member in**: its server calls `POST /api/v1/chat-sessions
   {username: "..."}` with its API key and gets a short-lived token (one hour) for that tenant user.
2. **It embeds an iframe on `/embed/chat`** and hands it the token: the chat without sidebar and account menu, with
   everything the chat has - the user's level per connection ("Nur lesen" / "Lesen und ändern"), the card "Ausführen /
   Abbrechen" before every change, the user's knowledge areas, the history (the user's own, also found in `/app`), the
   tenant's branding.
3. **The API key vouches for the person** - like the WAM proxy:
   - a switch per API key "darf Mitarbeiter anmelden", off by default;
   - existing, active users of the tenant only - an unknown name answers 404; the tenant admins create users, not the
     client software;
   - the token reaches the chat only (`/tenant/chat/**`, the branding), not the account or `/tenant/admin/**` - also
     for a tenant admin; after an hour the client software fetches a new one;
   - `ActiveTenantUserRule` still applies: a user switched off is out at once.
4. **Who did it at the software**: our key there is the tenant's, shared - the software sees only "Alpdesk AI". Every
   tool call carries the acting user in the MCP request's `_meta` (e.g. `user` - neutral, point 22), so the software can log it and
   check its own rights.

**Builds on** what is there: the chat, the levels, the confirmation (docs/architecture/mcp.md). New: the session
endpoint, the key's switch, the rule limiting the token to the chat, the route `/embed/chat` (and which websites may
frame it - `frame-ancestors`, as for point 7), the user in `_meta`. Docs: tenancy.md (a new way in), mcp.md,
`SECURITY.md`, `docs/privacy.md`.

**To discuss**: which origins may frame `/embed/chat` (per API key?); whether the client software may create a user
on first use (not proposed - the tenant admins keep the users); the token's lifetime.

## 22 White label: the MCP server and the MCP client carry the brand

**Goal** (10 October 2026): a tenant branded "Muster AI" gets a server named `muster-ai` in Claude Code and Copilot, not
`alpdesk`, and the model knows that "Frag Muster AI ..." or "Muster-Wissen" means this server.

**Built** (10 October 2026) - details in docs/architecture/mcp.md "The brand":
- the command and the Copilot configuration on the MCP key card name the server after the tenant's brand
  (`utils/mcp.util.ts` `mcpServerName`, the tenant's look for the tenant admin, that tenant's branding for the admin);
- the server answers `initialize` with the brand as `serverInfo.name` and instructions naming it, and `tools/list` with
  descriptions naming it (`services.mcp.BrandedMcpServer` wraps the SDK's handler - the SDK fixes them at start); the
  configured `serverInfo.name` is neutral, `ai-assistant` (decided: never the operator's brand);
- the MCP client introduces itself to the tenant's software with the tenant's brand (`clientInfo`).

**Not built**:
- the tenant's logo as home-screen icon - a tenant's logo is a `blob:` URL behind the login, which a device cannot fetch,
  and an anonymous logo address would give the brand away before the login (branding.md "Not built");
- the frontend's fallback `DEFAULT_BRANDING` ("Alpdesk AI") - shown only when `/branding` does not answer at start, and
  equal to the backend's default;
- image, container, database and release names - only the operator sees them, they are the open-source project's name;
- a tenant admin setting the MCP name - it follows the branding the admin sets.

## 23 Read more than a passage

**Goal** (10 October 2026): an answer gets everything that belongs to a hit, not just its passage of 1000 characters.
The case: a tenant teaches Claude Code and Copilot (through `ask_assistant`) how its own software is extended - "how do
I write a test step for our framework" - with short how-tos and API overviews in Markdown, pushed by its CI. Five
passages give the rule but not the example, or the other way round, and the model guesses. Continues point 4, step 4
("neighbour chunks - only if a measurement shows missing context"): this is that case.

**Proposal** (agreed in principle 10 October 2026, details open):
1. **A tool `read_document`** next to `similarity_search`: the model reads more of a document its own search found in
   this answer - nothing else, so knowledge areas and tenants hold by construction (checked again when read: `READY`,
   in the asker's scope). Not on every question: the passages come first as today, the model reads on when something
   is missing.
   - a **short document** (a how-to, up to some 20,000 characters) whole;
   - a **long one** (a manual of 100 pages) only around the hit - a page or two before and after, or its section;
   - a **limit per answer**, so a question never reads a whole manual.
   The full text is already stored (`document.text`); a chunk's place in it is found from its text, so nothing is
   embedded again for this. No provider call, so no usage of its own - only the chat model's tokens.
2. **Around the hit means characters, not pages**: the text before and after the hit's place in `document.text` -
   the same for every type (PDF, DOCX, Markdown, ...), a PDF's pages play no part. Markdown is not cut by its
   sections for this (decided 10 October 2026): only if the measurement shows windows that cut off what belongs to a
   hit (an example's code block), Markdown gets split at its headings - then its documents are embedded once again.
3. **Measured** like point 4 (`ai-features.md` "Search"), with the fictional company's documents plus a few Markdown
   how-tos and API overviews: hit among the first five, and whether the answer has the rule *and* the example.

**Speed**: the search stays as it is (the same SQL, the document id comes with the join it already has). Reading is
one row by its key, cut to the part asked for - milliseconds. What costs time is the model: a call of `read_document`
is one round more (another wait for the provider's first token) and its text more input tokens - only when the model
reads on, within the limit per answer. Measured with the rest (step 3): answer time and tokens with and without.

**Layers**: `TenantEmbeddings.ChunkMatch` and `KnowledgeSearch.Hit` carry the document id; `ToolContext` keeps the
documents found; `services.tools.ReadDocumentTool`; `DocumentRepository` reads a READY document within a scope. No migration, no frontend change, no new endpoint.

**Specs**: reading in a user's chat, over `/api/v1/chat` and `ask_assistant`; a document not found in this answer,
another area's, another tenant's, one deleted or moved meanwhile are refused; a long document is read around the hit
and within the limit. `FakeAiModels` learns to read
a document it found.

**Decided** (10 October 2026): the MCP server's answers may quote longer parts of a document; the sizes start at
12,000 characters read whole, parts of 5,000 starting 2,000 before the passage, 30,000 per answer - the measurement
may move them.

**Built** (10 October 2026) - details in docs/architecture/ai-features.md "Reading on in a document".

**First real test** (10 October 2026, Gemini 2.5 Flash through `ask_assistant`, a fictional framework's how-to of 4,000
characters and its handbook of 18,000): with the rule in the system prompt alone the model answered "all 12 steps" with
the 5 of its passage; with the passages marked "one passage of n" and the hint below them it read on and gave all 12.
The short how-to gave code following all its rules. **Still open**: a measurement with numbers (answer time, tokens)
over more questions.

## 24 Web search in the chat

**Goal** (10 October 2026): the chat answers questions about current facts - a minimum wage, a new law, who holds an
office - from the web instead of from the model's training. **Requirement of the user: it can be switched off per
user.**

**Why - measured** (10 October 2026, Gemini 2.5 Flash, the same 15 questions through `/api/v1/chat` with "documents
only", "documents first" with reasoning low and high, and through Gemini's API without a system prompt): with
"documents first" Alpdesk AI answers general questions as well as the plain model - same knowledge, same mistakes,
shorter and about three times faster. Every variant, the plain model included, gave outdated current facts (the
minimum wage of 2024, the chancellor before May 2025) without saying they might be outdated. Gemini's own chat app
searches Google for these; Alpdesk AI has no tool for it. "Documents only" (the default) refuses general questions,
even a rule of three.

**Options weighed (23 October 2026)** - and why they were dropped:
- **The provider's own search** (Gemini's Google Search grounding, Anthropic's and OpenAI's web search tools): none for
  Ollama and own model servers; we would not see the queries before they go out; sources, prices and streaming differ
  per provider. Prices (23 October 2026): Gemini 3 5,000 searches a month free, then $14 per 1,000; Anthropic and
  OpenAI $10 per 1,000 plus the results' tokens. OpenAI's needs the Responses API (`OpenAiResponsesChatModel`), we use
  `OpenAiChatModel`.
- **SearXNG** (self-hosted, free): a scraper of other search engines - they change their pages and block a server's
  address (Google since early 2025 without JavaScript, DuckDuckGo with CAPTCHAs); no versioned releases, an image a
  day. Needs care by the operator and still fails now and then.
- **Whoogle**: discontinued, archived 14 August 2026 - Google answers it no more.
- **DuckDuckGo directly**: no official search API; scraping it is against its terms.
- **Google Custom Search API**: closed to new customers, ends 1 January 2027. **Bing Search API**: retired
  11 August 2025.
- **Google results through SerpApi, Serper**: Google sued SerpApi in December 2025 (dismissed July 2026, amended,
  pending) - not something to build on for years.
- **Brave Search API** (own index, $5 per 1,000, $5 credit a month per key): the strongest independent index, kept as
  the fallback should Tavily go - a change of one class (see "Plan").

**decided (23 October 2026)**:
- **Tavily only**, through langchain4j's `langchain4j-web-search-engine-tavily` (`TavilyWebSearchEngine`, from the
  langchain4j BOM - a "beta" module like `langchain4j-pgvector`, Apache 2.0). **As much langchain4j as possible, as
  little of our own as possible**: no HTTP client, request or answer records of our own.
- **Switched on per user group** (not per user, not by the user in the chat): a group with web search lets its
  members search the web - as it decides their knowledge areas and levels. A single user gets it through a group of
  their own.
- **Not an answer source, a switch next to it** (changed the same day: "documents only and the web" is a case of its
  own - every statement then has a source, a document or a web page, and only the model's own, outdated knowledge is
  left out). `AnswerSource` keeps its two values; the switch at the asker's group, API key or MCP key decides whether
  the web is searched. Four cases: documents only; documents and the web (each statement with its source, no general
  knowledge); documents and general knowledge (as today); documents, the web, then general knowledge. No switch in the
  assistant's settings or templates - a template that turns web search off comes when a case needs it.
- **The documents first - enforced, not only asked for**: `web_search` refuses to run until the answer has searched
  the company's documents (`similarity_search`), and its answer tells the model so. Whether the passages found answer
  the question the model decides, by the rule in the system prompt: the web only where the documents do not answer
  or the question asks for current or outside facts (a law, a price, a person, another company); where a document and
  the web differ, the document wins and the answer names the difference. A tenant without documents to search (no
  embedding model) searches the web at once. A model that cannot call tools gets no web search.
- **Measured before the release** with a real model and key (like 10 October 2026): questions the documents answer
  (no web search, the same answer), current facts (right, with a source), conflicts (the document wins).
- **Never documents to the outside**: only the model's search query goes to Tavily. The model could still put a word
  of a document into a query (a product name) - the system prompt forbids it, it cannot be ruled out; privacy.md and
  SECURITY.md say so.
- **Sources as links under the answer**, taken from the tool's results, never from the model's text (models make up
  links): in the chat a strip like the created files, in `/api/v1/chat` a field of its own, through the MCP server's
  `ask_assistant` appended to the answer's text.
- **One tool `web_search`**, no `read_web_page`: each hit brings Tavily's extract - the part of the page matching the
  query, about 1,000 to 1,500 characters (changed the same day from the pages' whole text: its first characters were
  menus and cookie notes; the measurement with the extracts answered as well). Tavily's Extract (not in the langchain4j
  module) only if the extracts turn out not to be enough.
- **At most 3 searches per answer**, basic search depth only (1 credit; advanced costs 2).
- **The key**: the tenant's own Tavily key first (1,000 credits a month free per key), else the admin's global key -
  then billed, like the models' keys.

**What it costs** (Tavily, 23 October 2026: 1,000 credits a month free, then $0.008 per credit; a basic search 1
credit): assumed 1.5 searches per answer with web search, about 2 credits with the pages' text, about 1.6 cents. 21
working days a month:

| Tenant | Users with web search | Web answers per user and day | Credits a month | Cost a month |
|---|---|---|---|---|
| Small | 5 | 3 | 630 | $0 (within the free credits) |
| Medium | 20 | 5 | 4,200 | $25.60 |
| Large | 100 | 5 | 21,000 | $160 |

Per user above the free credits: about $0.67 a month at 2 web answers a day, $1.68 at 5. Not counted: the tokens of
the results the model reads (in the usage already, at the model's price).

**Plan** (proposal - the layers; without a group with web search everything behaves as today):

1. **Catalog, access, key and price reused**: a catalog entry of the new kind `WEB_SEARCH` with the new provider
   `TAVILY` (a check constraint: only together). The admin enters it with its price **per 1,000 searches** and assigns
   it to a tenant like a model (model access, point 11) - so the operator decides which tenants have web search at all.
   The provider keys' storage, encryption, key hint and "the tenant's own before the global one" serve the Tavily key
   unchanged.
2. **Switches** (management database, migration): `user_group.web_search`, `tenant_api_key.web_search` and one for the
   MCP server per tenant (kept like its areas, so a new MCP key keeps it) - all off by default; in the dialogs of the
   groups, the API keys and the MCP key.
3. **Rules** in `ChatService`'s system prompt for the four cases; web content is unchecked material - instructions in
   it are never followed (prompt injection; changing tool calls of the connected software still wait for the user).
4. **Tool** `services.tools.WebSearchTool` (a `ChatTool`): `availableFor` = the asker may (group, key, MCP), the tenant
   has a usable `WEB_SEARCH` entry and a key; `execute` refuses before `similarity_search` ran, builds
   `TavilyWebSearchEngine` with the key and calls `search`, answers the hits as text (title, address, cut content) and
   adds their addresses to the answer's sources. The only class that knows Tavily - Brave would replace it.
5. **Usage**: one record per search at the entry's price, on the usage pages next to the models.
6. **Sources**: the addresses join the answer's sources as they are (the stream's `sources` event, `/api/v1/chat`'s
   `sources`, `ask_assistant`'s "Sources:" line); the chat shows an address as a link. Kept for the session like the
   documents' sources - not stored with the message.
7. **Specs**: `FakeAiModels` calls `web_search` on `[[web <query>]]`; Tavily is faked by an HTTP server of the spec
   (`baseUrl`); the switches, the documents first, no key, the global key billed, isolation, at most 3 searches.
8. **Docs**: ai-features.md "Tools", tenancy.md "User groups", privacy.md, SECURITY.md, THIRD-PARTY.md, CHANGELOG.md
   (a minor version: a new feature).

**The client software's keys and the MCP key** (decided 23 October 2026): they have no user group, so each API key
and the MCP key get a switch of their own, like their knowledge areas - off unless a tenant admin turns it on.

## 25 Edit images in the chat

**Goal** (10 October 2026): a user sends one or more images with the question ("make the background white", "place
this house into the landscape of these three photos") and gets the changed image back - or asks for a new image
without sending one ("make me a birthday invitation") - and improves it round by
round ("the house a little further left"), each round working on the last result. The same through the client
software's API.

**decided (10 October 2026)**:
- Only images sent in the chat (the conversation's files) - never documents of the company knowledge or an area.
- `/api/v1/chat` can do it too - with "Verlauf speichern" on (a conversation that keeps its files).
- The chat's model does not look at the result itself: the user sees it and says what is still missing (no extra
  tokens per round).
- **Gemini only for now** ("Nano Banana", `gemini-2.5-flash-image` and its successors), through langchain4j - no
  request of our own to a provider's API. Built so that another provider (OpenAI's `gpt-image-1`, ...) or another
  langchain4j class (`ImageModel`) can be added later without touching the tool, the chat or the frontend (see
  "Open for other providers"). **Anthropic has no model that creates or changes images** - Claude only reads them; a
  tenant chatting with Claude still edits images, through its Gemini image model.
- **A new image from text alone too** ("make me a birthday invitation"): the same tool without images. The tool is then
  offered in every conversation of a tenant with an image model, not only where an image was sent; its description
  tells the model to call it only when the user asks for an image (each call costs). Text in the image may have a
  wrong letter (umlauts, dates) - an invitation with exact text as a PDF with the image in it needs `create_file` to
  embed images, a step of its own later.
- **Only where the admin has set it up**: the feature exists for a tenant only when the admin has entered an image
  model in the catalog **and** allowed it for that tenant (model access, point 11). With exactly one allowed it is
  used - no switch of its own for the tenant admin. Missing either, nothing of it is visible: no tool for the chat's
  model, no "Bildmodell" setting, no image model in a list. An installation without an image model behaves exactly as
  today.

**Why a model kind of its own, not a chat model**: an image model is no chat model - it calls no tools, and the
chat's agent loop offers tools in every round (Gemini refuses the request). Entered as a chat model today, its image
would be dropped too: `AiModels` reads only the answer's text.

**What langchain4j 1.22 offers** (the latest release, read in its sources):
- **Gemini, several images: `GoogleAiGeminiChatModel`** with the image model's name takes a user message with the
  instruction and any number of `ImageContent`s, and maps the image parts of the answer into the `AiMessage`
  (`GeneratedImageHelper.getGeneratedImages`); the usage comes as for every chat call. Sent without tools and without
  a thinking configuration. **This is the way chosen.**
- Gemini, one image: `GoogleAiGeminiImageModel.edit(image, prompt)` (plus a mask, which it sends as a second image
  part) - with the image output switched on for sure, aspect ratio and size, and its tokens in the `Response`.
- OpenAI, one image: `OpenAiImageModel.edit(image, prompt)` - the second image is a real mask (the area that may
  change), the Responses model reads no generated image out. Its `/v1/images/edits` itself takes up to 16 images.
- **Checked with real calls (10 October 2026, `gemini-2.5-flash-image`)**: the chat model answers with an image
  although no `responseModalities` is sent - one image edited (1024 x 1024 PNG back, about 0.7 MB) and two images
  combined (a house placed into a landscape, 1248 x 832 PNG with a shadow). The way through `GoogleAiGeminiChatModel`
  holds; `GoogleAiGeminiImageModel` stays the fallback should Google change that.

**Plan** (the current behaviour stays as it is - without an image model the tool is never offered):

1. **Catalog** (`entities.ai`, migration): `ModelKind.IMAGE` next to `CHAT` and `EMBEDDING` - for `GEMINI` only for
   now (a check constraint). Prices as for the other models. The tenant may use it through the model access (point
   11). Every place that today tells `CHAT` from `EMBEDDING` is checked so an image model never turns up as a chat
   model: the model lists of the admin and the tenant, `/api/v1/models`, the chat's model choice, `AssistantService`,
   `DocumentService`, the usage pages.
2. **Which image model a tenant's chat uses**: the tenant's assistant settings name one of the image models it may
   use (`tenant_assistant.image_model_id`); with exactly one allowed it is that one without being chosen, with none
   the tool is off.
3. **`AiModels.createImage(tenant, model, key, images, instruction)`** (images may be empty) → the new image (bytes, MIME type) and its
   token usage: the one new provider call, a `switch` over the provider like every other call in `AiModels` - today
   one case, `GEMINI` through `GoogleAiGeminiChatModel`. Next to it `AiModels.imagesPerCall(model)`: how many images
   the model takes in one call (Gemini 5). The key is chosen as for every call (the tenant's own before the admin's
   global one); usage is recorded per call (`UsageService`, the asker as user). A refusal of the provider (safety
   filter, size) is a tool error the model passes on to the user.
4. **Tool `create_image(instruction, file_ids)`** (`services.tools.CreateImageTool`, a `ChatTool`): offered only where
   the conversation keeps its files and the tenant has an image model. Without `file_ids` it creates a new image from
   the instruction; with them it changes or combines those images. It takes only `file_id`s of the conversation's
   images - a document cannot be named - at most `imagesPerCall` of them (its
   description says how many; more is a tool error the model passes on). It sends the **originals** (decoded at a
   provider's limit, e.g. 2048 pixels on the long edge, PNG), never the 1024-pixel JPEG copies the chat model sees -
   otherwise the image would lose quality with every round. At most a few edits per answer (like `create_file`).
5. **The result is a file of the conversation** (`ChatFileService`, like `createGenerated`): `<name>-v2.png`, with
   its JPEG copy for the chat model, attached to the answer as a created file. Improving it needs nothing of its
   own: the next question lists the conversation's files, the new image among them, and the model edits that one.
   The tool's text tells the model the new file's id and name and that the application shows it (no made-up link).
6. **`/api/v1/chat`**: the same tool when the key keeps the history; the client software uploads the images
   (`POST /api/v1/chat/files`), the answer names the created files, it downloads them
   (`GET /api/v1/chat/files/{id}/content`) - all existing. Without the history the tool is not offered (nothing to
   keep the image in). The MCP server's `ask_assistant` does not get it (it carries no files).
7. **Frontend**: the model kind "Bild" in the catalog dialog (provider Gemini) and the lists; "Bildmodell" in the
   assistant settings; the created-file chip shows an image as a preview (loaded as a blob through HttpClient like
   the download, so the answer's Markdown still loads nothing by itself), a click shows it large. German texts, light
   and dark. Nothing in it names a provider beyond the catalog dialog.
8. **Specs**: `FakeAiModels` edits images deterministically (e.g. a PNG carrying the instruction and the number of
   images it got) and calls `create_image` for `[[create_image <file> ...]]` (no file: a new image); feature methods for the tool through the
   HTTP API (tenant user and `/api/v1`), a new image from text alone, rounds v1 → v2 → v3 each on the last result, the originals sent (not the
   JPEG copies), several images in one call and more than `imagesPerCall` refused, a document or another
   conversation's file refused, another tenant's file 404, no tool without an image model or without history, the
   image model never in a chat model list, an image model of another provider refused by the catalog, usage per call.
   Gemini itself cannot be reached in a spec (like `SentSpec` for the providers' chat models; `ModelServerSpec` and the
   smoke test only reach own servers): `ImageModelSpec` checks the request and the reading of the answer on
   langchain4j's types, the real call was checked by hand (above).
9. **Docs**: ai-features.md (model kinds, the tool, the chat's files), privacy.md (the images and the instruction go
   to Google), SECURITY.md if the limits change, the frontend notes in CLAUDE.md.

**Open for other providers** - what a second one costs, without building anything for it in advance (no interface
with one implementation, CLAUDE.md "Code style"):
- **OpenAI or another provider**: one more `case` in `AiModels.createImage` (`OpenAiImageModel.edit` - one image) and
  in `imagesPerCall`, the provider allowed in the catalog's check constraint and dialog, its fake in
  `ModelServerSpec`, privacy.md. The tool, the stored files, the API and the chat's preview stay as they are -
  about a day.
- **Gemini to `GoogleAiGeminiImageModel`** (if the chat model's way stops working, or once its `edit` takes several
  images): only the inside of the `GEMINI` case changes.
- **More images for OpenAI**: when a langchain4j release takes several (check its release notes with each update),
  only its number in `imagesPerCall` rises.

**Size**: M - backend (catalog, provider call, tool, files) about two days, frontend one, specs and docs one.

**Built** (10 October 2026), the limits as decided when built: at most 3 calls of the image model per answer
(counted as calls since the review of 10 October 2026 - a refused call is billed too), 5 images per call
(Gemini), an image sent as it is up to 2.5 MB (PNG or JPEG), else as the 2048-pixel JPEG copy. Checked in the app with
Gemini 2.5 Flash as the chat model: a new invitation from text alone, then changed. From that test: a note of the
history the model copied into its answer is left out now, and the chat model is told to keep text in an image short
and large and to say exactly what to change. From the review of the same day: a chosen image model the tenant can
no longer use is left out of the settings (saving them failed), and the real Gemini request and answer run against a
fake server in `ModelServerSpec`. **Still open**: combining several real photos (whether 5 images hold up
in quality), and Claude or GPT as the chat model with Gemini's image model.

## 26 Code execution

**Built 10 October 2026** (asked for after the measurements of point 14): the chat's model runs Python and shell
commands on the question's files - `run_python` (a session per answer, like a notebook) and `run_shell` - in a sandbox,
a container of its own (`Backend/podman/sandbox/`) that needs no root, capabilities or cgroups and runs on any kernel from 4.x on,
Kubernetes' "restricted" pod security included. One instance runs one answer's code at a time; the admin allows it per
tenant. Everything that was decided - the isolation, what was left (nsjail, chroot, Landlock, WebAssembly, a container
per call), the settings - is in [architecture/code-execution.md](architecture/code-execution.md).

**One sandbox per tenant** (decided and built 11 October 2026): the shared pool is gone - every tenant that wants code
execution gets instances of its own, their addresses and token entered by the admin at the tenant and checked there;
the cleanup between sessions empties every writable place, System V IPC is denied, and `multiprocessing` works in the
notebook - [architecture/code-execution.md](architecture/code-execution.md) "One sandbox per tenant". Not built on
purpose: a script for every tenant's sandbox at once, a queue.

**Open**:
- the known weaknesses ([architecture/code-execution.md](architecture/code-execution.md) "Known weaknesses", 11
  October 2026, agreed: nothing built before a case): the seccomp filter as a deny list, the files sent in one piece,
  one answer per instance without a queue or notice, the memory limit per program only, no record of what ran (first
  when something comes), not yet run on Docker with AppArmor and on Kubernetes, the code's view of the server's file
  system (Landlock as the first hardening step), a runtime of the platform (gVisor, Kata) as the real step up. Closed
  in the second review of 11 October: the kernel's answer mistaken for a subprocess's output, unbounded output, SIGTERM
  past the signal rules, the cleanup racing the next session, an ended session read as unreachable, an address at two
  tenants;
- the sandbox's base image: plain Ubuntu stays - Debian slim, `python:3.14-slim`, a chiselled Ubuntu and Alpine were
  checked on 11 October 2026; **check again** at every new Ubuntu LTS or Python minor version, or when the image's
  size or the tools in it start to matter ([architecture/code-execution.md](architecture/code-execution.md) "The base
  image", with what was measured and how to repeat it);
- files a session's code writes (a chart, a CSV) as downloads of the answer;
- the specialists of point 14 with the code tools and a model of their own - the measurements there showed that the
  specialist's model decides the result.

## 28 Scheduled tasks

**Goal** (asked for on 13 October 2026): a process that runs by itself - fetch files from where they live, check them
against what the tenant wants checked, report by e-mail only when something is wrong. For example: the log files of
a client software, lying on an SFTP share with a date in their names, of several kinds, checked every 5 hours.
Kept generic: a task checks files, not logs - no log format is known to the code (see the generic handling of files
sent with a question, ai-features.md). A task is a **graph**, drawn by the tenant admin in the frontend and started by
schedule, by the client software through `/api/v1` or through the tenant's MCP server. This is the first piece of
point 6 (workflows).

**Proposal** (agreed as a plan on 13 October 2026, not built yet):

1. **File stores - built 13 October 2026** ([architecture/file-stores.md](architecture/file-stores.md)): asked for
   on 13 October 2026 as their own component - several per tenant, and later more types (decided 27 October 2026: a
   local folder, S3, SharePoint, OneDrive - point 44).
   One table `file_store` for every type (settings as JSON per type, the password encrypted), SFTP (Apache MINA
   SSHD) and FTPS with explicit TLS (Apache Commons Net) - FTPS was added on the user's request. A check shows the
   server's fingerprint first; a tenant admin confirms it, only then the password goes out; another key or
   certificate later gets no login. Internal hosts only from `APP_FILE_STORE_INTERNAL_HOSTS`. FTPS data connections
   resume the control connection's TLS session (most servers require it) without reflection. Pages: "Dateiablagen"
   for tenant admins, a tab for the admin. **Uses** of a store, each its own plain flow on top of its client: the
   task's `read_files` step (package 3); a **source for embedding** (point 8: a folder synced on a schedule through
   the existing push, `DocumentService`, the file's path as key, `origin = SOURCE`, optionally into a knowledge area);
   a **place to write to** (a task's report, a file created in the chat) - built when a case asks. Not as the
   installation's storage (`app.storage.type`): every download and embedding would hang on the share and every
   tenant's files would lie on one server - a central storage, if ever needed, will be S3-compatible.
2. **The task's format - our JSON, the source of truth**: versioned, independent of any library, so the engine can
   change without touching a tenant's tasks. Steps with `id`, `type`, `input` (the results of earlier steps), `if` (a
   condition on an earlier result: the step is skipped when it is false) and `next` (edges; an edge back to an earlier
   step needs `maxRounds` - a loop between two model steps must never run unbounded); triggers; the editor's
   positions under `ui`. Example:
   ```json
   {
     "version": 1,
     "triggers": [{ "type": "schedule", "every": "PT5H" }, { "type": "api", "inputs": [] }],
     "steps": [
       { "id": "logs", "type": "read_files", "connection": 3, "pattern": "logs/{yyyy-MM-dd}_*.log",
         "onlyNew": true, "include": ["ERROR", "WARN", "Exception"] },
       { "id": "check", "type": "agent", "model": 12, "input": ["logs"],
         "tools": ["search_file", "read_file_lines", "run_python"],
         "checks": [{ "name": "Interface", "severity": "error",
                      "instruction": "Were there connection losses or timeouts to interface X?" }] },
       { "id": "verify", "type": "agent", "input": ["check"], "tools": ["list_files", "read_file"],
         "next": [{ "if": "verify.unclear > 0", "to": "check", "maxRounds": 2 }] },
       { "id": "mail", "type": "send_mail", "input": ["verify"], "if": "verify.errors > 0",
         "to": ["it@example.com"] }
     ]
   }
   ```
3. **Step types**, each a small class: `read_files` (files of a connection by pattern with a date placeholder,
   pre-filters - lines that contain / do not contain a text or pattern, settings of the task, not format detection -,
   **only what is new**: the position read up to is kept per file, so a fault is not reported every run); `agent` (our
   `AgentLoop` with instructions or check points, the chosen tools - also `list_files` / `read_file` on the connection,
   so it can check a finding against another file - and a **structured result**: per check point ok, warning, error
   or unclear with the lines as evidence, counted into `errors`, `warnings`, `unclear`); `tool` (a `Toolbox` tool
   called with fixed arguments, e.g. a tool of the connected software); `send_mail` (`MailSender`); `write_file`;
   later `approval` (the run waits for a person's decision - proven by the prototype, built when a case asks).
   Conditions read only the structured results, never the model's free text. A step that gives no valid result fails
   the run - and **a failed run always sends a mail**, so a broken check is never silent. A second `agent` step
   "verify" that tests every finding against the other files keeps false alarms down. **A finding still unclear
   when an edge back reaches its limit sends a mail too** (found by the prototype: the mail's condition "errors > 0"
   skipped it, and an unclear fault went unreported) - the run ends as "unclear", never as "nothing found".
4. **Engine: langgraph4j** (MIT; the 1.8 line is its LTS - 1.9.4 of 9 October 2026 the newest, with experimental
   features; a Postgres checkpoint saver exists). At the start of a run the JSON is translated into a `StateGraph`:
   steps become nodes, conditions conditional edges, edges back cycles with their limit. **The `AgentLoop` stays** -
   an `agent` node calls it, so usage per call, the tenant's key, stop and our round rules hold in tasks too;
   langgraph4j's own `AgentExecutor` is not used (the reasons of `AiServices` in ai-features.md). Checkpoints lie
   between nodes, not between the rounds of the loop: after a restart an interrupted `agent` step runs again from its
   start (its tokens once more; changing tools wait for a decision as `PendingAction` and do not run twice).
   `langchain4j-agentic` was compared on 13 October 2026: it has the same building blocks (sequential, loop,
   conditional, parallel, a scope store to resume) but the whole module is experimental and a waiting human blocks a
   thread - not waited for, since the format and the steps stay ours either way.
   **The prototype** (built 13 October 2026, outside the project, langgraph4j 1.8.27 against an own PostgreSQL;
   the agent step a deterministic stand-in - the graph was tested, not a model) - **langgraph4j holds**:
   - our JSON translated into a `StateGraph` at the start of a run: a node per step, after every step one
     conditional edge (edges back while their limit allows, else the next step whose `if` holds; skipped steps
     recorded), approval steps as `interruptsBefore`; an edge back without `maxRounds` refused before the run;
   - all ran: errors found → verify unclear → back to check → confirmed → mail; nothing found → mail skipped;
     verify never sure → the loop ends after `maxRounds`; the process killed before a step → a new process resumes
     there (the steps before it do not run again); an approval → the run stops and **the process ends - no waiting
     thread** -, later another process approves or rejects (`updateState` with the decision, then
     `GraphInput.resume()`) and the run goes on or the mail is skipped; approving a finished run again is refused
     (a 409 for us);
   - **checkpoints in the tenant database under Flyway**: `PostgresSaver` takes our `DataSource` and runs with
     `createTables(false)` - its two tables (`lg4jthread`, `lg4jcheckpoint`) come from our migration;
   - **`plainTextStateSerializerLegacyMode(true)` is required** with a Jackson `StateSerializer`: without it the JSON
     is wrapped in a Java `ObjectOutputStream` (and read through an `ObjectInputStream`); with it the column holds
     plain, readable JSON - despite the flag's name;
   - every checkpoint stores the whole state (not a diff): with references instead of the files' content under 1 KB
     per step - the rule "the state stays small" (8.) is needed, not a nicety;
   - a finished run's checkpoints are only marked released (`releaseThread(true)`) - **we delete them** at the end of
     the run;
   - dependencies: `langgraph4j-core` brings `async-generator` and slf4j only (Jackson and the PostgreSQL driver as
     we have them), MIT, Java 17+; no `ServiceLoader`, no reflection beyond Jackson on our maps - no conflict with
     AOT expected; **the AOT jar and its smoke test are proven with the first build in the project**;
   - for the build: a skipped step is recorded in the node, not in the edge function as in the prototype - an edge
     function may run again on resume.
5. **Triggers**: a schedule (interval or cron; `@Scheduled` over all active tenants on an executor of its own - one
   instance, no cluster locking) and **the client software through `/api/v1`** (a conscious new part of the tenant
   API): `GET /api/v1/tasks` (the tasks this key may start - assigned by the tenant admin, like knowledge areas; no
   assignment, no task), `POST /api/v1/tasks/{id}/runs` (inputs as the API trigger declares them, e.g. `files`
   required, `note` optional - else 400; answered **202** with the `runId`), `GET /api/v1/tasks/{id}/runs/{runId}`
   (state, steps, result), `POST /api/v1/tasks/{id}/runs/stream` (the same as server-sent events, like the chat).
   Plus "test run" in the editor (mail only to the editing person). **The MCP server too** (decided 13 October
   2026): tools `list_tasks` and `run_task` for the tenant's MCP key, which starts only the tasks the tenant admin
   opened to it (one switch per task, like a key's assignment). Every run - scheduled, by API, by MCP, test - is
   stored and mails the same way. **A task started while it still runs** (decided 13 October 2026): a scheduled
   start is skipped and logged, a start by API or MCP gets 409 - one run of a task at a time.
6. **The frontend** (the page "Aufgaben" - see 7.; the app's look as everywhere: `app-shell`, `page-header`, panels,
   tokens only, light and dark; desktop - on a phone read only). Sketched on 13 October 2026 as a
   graphic editor; **since 14 October 2026 the graph is shown, not edited** (see "Text with the graph beside it") - the
   sketch's canvas and look stand for that graph; its step bar and properties panel wait for graphic editing:

   ```
   ┌──────────────────────────────────────────────────────────────────────────────────────┐
   │ ←  Log check interfaces                              [Runs]  [Test run]  [■ Save]    │
   │    saved · version 4 · last run 09:00, 2 findings                                    │
   ├──────────────────────────────────────────────────────────────────────────────────────┤
   │ Add step:  [folder Read files] [sparkles Agent] [tool Tool] [mail Mail] [Write file] │
   ├─────────────────────────────────────────────────────┬────────────────────────────────┤
   │   ┌───────────────┐        ┌───────────────┐        │ Step                           │
   │   │ Schedule      │        │ API call      │        │ ✦ Check (agent)                │
   │   │ every 5 hours │        │ /api/v1/tasks │        │ Model    [Claude Sonnet    ▾]  │
   │   └───────┬───────┘        └───────┬───────┘        │ Tools    search_file           │
   │           └──────────┬─────────────┘                │          read_file_lines       │
   │              ┌───────▼───────┐                      │          run_python  + Tool    │
   │              │ Read files    │                      │ Check points                   │
   │              │ SFTP·only new │                      │ ┌────────────────────────────┐ │
   │              └───────┬───────┘                      │ │ Interface        [error]   │ │
   │             ╔════════▼════════╗ ◄╌╌╌╌╌╌╌╌╮          │ │ losses or timeouts?        │ │
   │             ║ Check           ║          ╎          │ └────────────────────────────┘ │
   │             ║ agent·3 points  ║    [unclear·max 2]  │ ┌────────────────────────────┐ │
   │             ╚════════┬════════╝          ╎          │ │ Night run       [warning]  │ │
   │              ┌───────▼───────┐           ╎          │ │ fully completed?           │ │
   │              │ Verify        │ ╌╌╌╌╌╌╌╌╌╌╯          │ └────────────────────────────┘ │
   │              │ agent·other   │                      │ + Check point                  │
   │              └───────┬───────┘                      │                                │
   │                [errors > 0]                         │ ✓ Graph valid                  │
   │              ┌───────▼───────┐                      │                                │
   │              │ Send mail     │                      │                                │
   │              └───────────────┘                      │                                │
   │ ■ trigger  ■ fixed step  ■ agent  ■ output  ╌ back with limit                         │
   └─────────────────────────────────────────────────────┴────────────────────────────────┘
   ```

   - **Text with the graph beside it** (decided 14 October 2026, replacing "text or graphic" of the same day): the
     task is written as **YAML** - by hand or by "Draft with AI" - and the **graph is drawn beside it** to read and
     check it: left the YAML editor, right the graph. The graph is **not live**: it is drawn when the task is
     compiled - the "Check" button, before every save, after an AI draft and when a task is opened. A click on a node
     marks its lines in the YAML and the other way round; an error stands at its line and red at its node; after an
     AI draft the nodes it changed are marked. The same graph component, read only, is the run view. Why not a
     graphic editor: with the AI writing and the admin correcting in text, dragging steps and drawing edges is hardly
     needed - and it was about half of the frontend, with the hard part of writing a graphic change back into YAML
     with its comments. **Graphic editing dropped** (decided 27 October 2026; "later, when customers ask" before). **No
     script language** for tasks (asked and decided 14 October 2026): a program's own loops and ifs would hide the
     graph - no checkpoints, no resuming, no waiting for an approval, no run view, no check before saving - and code a
     tenant admin writes would run on our server; most tenant admins do not program, and a model drafts a declarative
     task more reliably than code.
   - **Why YAML** (compared on 14 October 2026, decided): the only candidate that is readable, takes
     comments and long texts (instructions, check points, regex) comfortably, is widely known (GitHub Actions,
     Kubernetes) and written reliably by models - without becoming a programming language. Looked at and left:
     **JSON** (stays the storage, export and API format - but no comments, no multi-line texts, regex escaped twice);
     **JSON5/JSONC** (multi-line texts still awkward, little known); **TOML** (nested lists like steps with their
     branches get unwieldy); **HCL** (weak in Java, an expression language that invites programming); **Pkl**, CUE,
     Jsonnet, Dhall, Starlark (programming languages for configuration - functions, loops, an interpreter running
     a tenant admin's code on our server, no way back from a graphic change to a program with locals and loops, no
     browser editor ready, a language runtime next to our AOT jar, too hard for most tenant admins; Pkl's best part,
     types in a template, comes from the JSON Schema below); **CNCF Serverless Workflow** (a standard in YAML/JSON
     with a Java SDK - but agent steps with check points, file stores and `match` would all be extensions, its
     expressions are jq, and a part of a standard would mislead; its words are borrowed instead); **Amazon States
     Language** (JSON, made for AWS) and **BPMN 2.0** (XML, hardly written by hand); **an own language** (parser,
     editor, highlighting and docs all of our own).
   - **YAML 1.2**, read with **SnakeYAML Engine** - not Jackson's YAML module, which reads YAML 1.1, where `no`, `on`
     or `yes` silently become booleans (a severity `no`, a country code `NO`); SnakeYAML Engine also gives each
     value's line for the errors.
   - **A JSON Schema as the shared contract**, generated from the format's definition in the backend: the check's
     first stage (fields, types), the editor's completion, hover help and marks while typing (CodeMirror 6 with its
     YAML language and `codemirror-json-schema` - whether that package covers YAML fully is checked before the
     build) and the reference behind the info button, which then cannot fall behind. The second stage (references,
     targets, conditions, file stores) stays in the backend.
   - **Words borrowed from the standards** (`switch`-like `next`, `forEach`, `approval`), the standards themselves not
     adopted.
   - **No YAML anchors** (`&`, `*`, `<<`) - a task stays plain to read for the admin and the model, and the graph
     shows every step as it is; `local`-like reuse is not offered either. **Comments stay**: the YAML is stored as it
     was written, next to the JSON the engine runs - only the text is edited, so nothing rewrites it.
   - **Checking a task in two stages, in the backend only** (decided 14 October 2026 - one truth for text, graphic
     and saving): (1) **syntax** - valid YAML 1.2, from SnakeYAML Engine with line and column;
     (2) **"compiling" against the format** (built from the prototype's `TaskDefinitions.validate`): required fields
     and types, known step types, unique ids, every `input` naming an earlier step, every edge's `to` an existing step
     and an edge back with `maxRounds`, conditions naming a step that gives that count, and the tenant's references -
     the file store exists and its fingerprint is confirmed, the model is one the tenant may use, the recipients are
     valid. **Every error carries its line** (the parser keeps where each field came from). Compiling (see "Text with
     the graph beside it") asks `POST .../tasks/validate`, marks the lines and draws the graph; saving runs the same
     check - an invalid task is never stored. While typing only the JSON Schema marks fields, in the browser, without
     asking the server.
   - **Loops** are always bounded - no `while`, no `for i`: an edge back with `maxRounds` (in the format). **Later,
     when a case asks for it**: `forEach` on a step (`forEach: logs.files` - once per element, e.g. a mail per file);
     not needed for the log check, whose agent step reads every file itself; langgraph4j would carry it as parallel
     branches or a subgraph - to be checked then.
   - **More building blocks** (sketched on 14 October 2026 in two full examples - a nightly check of
     interfaces and a regex branching; agreed as the target of the format, each built when the first task needs it,
     the check of 2 covering each one as it comes):
     - **`match`**, a step without a model: regular expressions over a text (`logs.lines`, or an agent step's
       `answer` - possible, but fragile: a model's wording changes; fixed formats are its strength - log lines, order
       numbers, error codes, percentages). It gives a structured result per pattern: the count (`classify.auth`), the
       matches with their line and named groups (`.matches`, for `forEach`), a group's values (`.values.iface`), the
       number of different ones (`.distinct.iface`), the largest and smallest number (`.max.pct`, `.min.pct`).
       Conditions read this result - a model's free text is still never read by a condition. **RE2/J** (Google, BSD)
       instead of `java.util.regex`: log lines come from outside, and a pattern like `(a+)+$` would make the JDK's
       backtracking engine run for minutes (ReDoS); RE2/J runs in linear time - without back references and
       lookarounds, named groups as `(?P<name>...)`; plus limits on pattern length, line length and matches.
     - **Branching with `next` as a switch**: entries checked from top to bottom, the first true one wins, `else` when
       none is; targets forward anywhere, backwards only with `maxRounds`; a step without `next` goes on to the next
       one in the list.
     - **Conditions with `&&` and `||`** - nothing beyond (no brackets, no functions), or the format becomes a
       programming language after all.
     - **Placeholders** `{{ trigger.note }}`, `{{ run.date }}`, `{{ each.name }}`, `{{ classify.timeout.values.iface }}`
       - they only insert a value (no loops, no ifs); each must name something known, else the check fails with its
       line.
     - **`forEach`** (see loops), **`approval`** (the run waits, the prototype proved it without a thread; approvers,
       a `timeout` after which it counts as rejected), **`tool`** (a tool of the connected software with fixed
       arguments), **`write_file`** (onto a store), **`read_files` with several `sources`** (stores and patterns in
       one step), **triggers** `schedule` (cron), `api` (with declared `inputs`) and `mcp`.
     - **Multi-line texts** (`|`) and comments - plain YAML; anchors are not allowed (see "No YAML anchors").
   - **The format's reference behind an info button** (asked for on 14 October 2026): in the text view and on the
     canvas a button opens a reference of everything the format offers - every step type with its fields, conditions
     and their operators, `next` and `else`, loops with `maxRounds` and `forEach`, `match` with RE2/J's syntax and its
     limits, placeholders, triggers, comments and multi-line texts - each with a short example that can be copied
     into the editor, and the two full examples of 14 October 2026 (made industry-neutral, without anchors). In English
     and German like every text of the frontend. Its part on fields and types comes from the JSON Schema (see "A JSON
     Schema as the shared contract"), so it cannot fall behind the check; explanations and examples are written per
     language as Markdown next to the frontend (rendered like the chat's answers) - a spec ensures every step type and
     field of the schema appears in them.
   - **The list** (the page itself): the tenant's tasks as a table - name, triggers, active, last run (status chip
     ok / warn / err with its time), next run; the ⋮ menu with run now, duplicate, export JSON, delete; "new task"
     and "import JSON" as the page's actions; an empty list shows `empty-state`. A row opens the editor.
   - **The editor's top bar**: back, the task's name, a line "saved · version n · last run hh:mm, n findings"
     (unsaved changes said there), then "Runs", "Test run" (`mat-stroked-button`) and "Save" (`mat-flat-button`, the
     one main action).
   - **Dropped with graphic editing (27 October 2026) - the step bar** below it: one entry per step type with its icon, dragged onto the canvas or clicked (added
     below the chosen step).
   - **The canvas**, top to bottom: a node is a rounded box with icon, title and one short line of what it does
     ("every 5 hours", "SFTP · only new", "agent · 3 check points", the recipient); **one colour per kind** - trigger
     neutral, fixed step, agent, output - each a token pair of `styles.scss` (new `--app-flow-*` tokens), never a
     literal colour; the chosen node has an outline in `--app-brand`. Several triggers join into the first step.
     Edges are plain lines with an arrow; a **condition sits on its edge as a small chip** ("errors > 0"); an **edge
     back is dashed** and its chip says the limit ("unclear · max 2"). A legend below the canvas names the colours and
     the dashed edge. Zoom and pan, "arrange" (dagre) on a button, positions kept under `ui`.
   - **Dropped with graphic editing (27 October 2026) - the properties panel** on the right, Material forms for the chosen node: for an agent the model (select), the
     tools as chips with "+ Tool", the check points as small cards (name, severity chip - error `err`, warning `warn`
     - one line of the instruction; a click opens it, "+ Check point" adds one); for `read_files` connection, folder,
     pattern with the date placeholder and a preview of the files it matches today, pre-filters, "only new"; for
     `send_mail` recipients; for an edge its condition **chosen from a list** ("errors > 0", "warnings > 0",
     "unclear") and, going back, "at most n times" (required). At its foot the validation: "✓ graph valid", or the
     problems, each also marked red at its node (loop without limit, step without input, no recipient) - saving only
     a valid graph.
   - **Runs**: a list of the task's runs (start, trigger - schedule, API key, MCP, test -, duration, tokens, status
     chip), each deletable, and "delete all runs" for the task (runs are kept until deleted - 8.);
     a run opens **the run view**: the same graph read only, every node with its state (ran, skipped, failed),
     duration and tokens, a click shows its input and output - for an agent the findings with their evidence lines.
     It reads our run data (`task_run`, `task_run_step`), written from the graph's events, so the page does not
     depend on langgraph4j's checkpoint format.
   - **Test run**: runs the task at once, the mail only to the person editing it; the run view opens on it.
   - **Texts** are translation keys in `en.json` and `de.json` (i18n.md); "Aufgabe", "Prüfpunkt", "Lauf",
     "Testlauf", "Gegenprüfen" are the German words.
   - **Canvas library: Foblex Flow** (`@foblex/flow`, MIT; decided after the canvas prototype of 13 October 2026 -
     an Angular 22 app outside the project drew the example task with both candidates, our tokens, light and dark):
     - both installed on Angular 22 without conflicts and drew the graph: nodes as our own markup, conditions as
       chips on their edge, the edge back dashed, dragging with the edges following, selection feeding the
       properties panel;
     - **Foblex** has an accessibility layer (`withA11y()`: the canvas a single tab stop with `role="application"`,
       arrow keys move between nodes, a live region announces what happens, its messages can be replaced - so they
       go through Transloco), and its theme is driven entirely by `--ff-*` variables: mapped once onto our `--app-*`
       tokens, light and dark follow with no other rule;
     - **ngx-vflow** has no ARIA attributes and nothing focusable at all - the editor could not be used with the
       keyboard; its look is ours only because every node and edge is our own template;
     - for the build: nodes need an accessible name of their own (the title), connections are announced by their
       connector ids unless we name them; a connector needs its own size (Foblex' rule), the theme's variables are
       set in `styles.scss`, not per component.
   - **Layout**: **dagre** (`@dagrejs/dagre` 3.1.1, MIT) - in the prototype top to bottom with the edges back left
     out of the layout, so the main path stays straight. elkjs not needed (EPL-2.0 stays out).
   - **"Draft with AI"** (asked for and agreed on 14 October 2026): in the text view a tenant admin describes in their
     own words what the task shall do ("check the logs on the store *Logdateien* every 5 hours for timeouts of
     interface X; mail it@example.com on errors, sicherheit@example.com at once on failed logins") - or, for an
     existing task, what to change ("also mail the management"). The backend asks the tenant's chat model with:
     the JSON Schema and the reference behind the info button with its examples; what the tenant has - the names of
     its file stores (only confirmed ones count), its connected software with its tools, the models it may use - so
     nothing is made up; the current YAML when a task is changed. The model writes YAML; the two-stage check runs
     over it, and its errors with their lines go back to the model to correct itself - at most two or three rounds.
     The result is a **draft** in the text view (for an existing task with its changes marked); an error left after
     the rounds stands at its line like any other. **Nothing is saved by it**: the tenant admin reads the draft,
     changes it in the text and saves it - a run needs a saved task, which passed the same check as one
     written by hand. The model writes data of the format, never code. The tenant's chat model and key, usage
     recorded with the caller "task-draft"; to the provider go the description, the names of stores, software and
     tools and the existing YAML - no file's content (docs/privacy.md with the build). Later possibly the same as a
     tool in the chat, whose draft then waits on the page "Aufgaben".
7. **Who keeps tasks**: the **tenant admin** creates and edits them (`/tenant/admin/tasks`, the page "Aufgaben" in
   their menu); the admin has the same panels per tenant (`/admin/tenants/{id}/tasks`, a `TenantScope` like the
   company knowledge and the keys); plain users see no tasks.
8. **Where the state lives and who writes it** (decided 13 October 2026):

   | What | Written by | Where | How long |
   |------|-----------|-------|----------|
   | The task (the JSON, its versions, schedule, next run, active, open to the MCP key) and which API key may start it (foreign keys to `tenant` and `tenant_api_key`, like `tenant_api_key_area`) | our task service, when the tenant admin (or the admin) saves in the editor | **management database** - like the assistant's instructions, MCP connections and knowledge areas, kept by the tenant admin; the scheduler reads all tenants' tasks with one query | until deleted - with the tenant or the key by cascade |
   | The file stores (password encrypted) - built, [architecture/file-stores.md](architecture/file-stores.md) | `FileStoreService` | management database - like MCP connections and provider keys | until deleted |
   | Checkpoints: the graph's state after every node, the run's id as `thread_id` | **langgraph4j**, through the tenant's `DataSource` (`TenantDatabases.dataSource`) | tenant database, tables created by **our** Flyway migration (`db/tenant/`) - if langgraph4j's Postgres saver does not accept that, an own saver over its interface with plain JDBC | while the run lasts, deleted at its end |
   | The run: state, per step duration, tokens, input, output, findings | **us**, from the graph's events | tenant database (`task_run`, `task_run_step`; the task's id without a foreign key, as `pending_action` keeps the MCP connection's) - the run view and `/api/v1` read these, never the checkpoints | **until the tenant admin deletes them** (decided 13 October 2026) - one run or all runs of a task; with the task or the tenant they go too |
   | Read positions per file | the `read_files` step | tenant database | while the task exists |

   The definition is the tenant's but small and needed across tenants (scheduler); what a run reads and finds holds
   the files' content and stays in the tenant's database.
   **The graph's state stays small**: references to files and their positions plus the structured results - never
   the files' content (megabytes serialised at every node, personal data from the files stored twice). Read positions
   move on **only after a successful run**: a failed run leaves them, so the next one reads the same lines and no
   fault is lost. Packages `source/` and `task/` in every layer. Usage recorded per call with the caller "task".
9. **Specs**: an SFTP Testcontainer and `FakeAiModels` (taught the structured result) - nothing external called;
   `TenantApiKeySpec` for the new controllers and the key's tasks; the MCP tools start only tasks opened to the MCP
   key; another tenant's task or run answers 404; a second start while a run is open answers 409; deleting a task
   deletes its runs in the tenant database (no foreign key across the two databases - the service does it).
10. **Privacy**: files like logs often hold personal data - `docs/privacy.md` says what goes to the provider, what is in
    the e-mail and that runs - with their evidence lines - are kept until the tenant admin deletes them; SECURITY.md
    the new tenant-entered address (SFTP), the pinned host key, and the API and MCP triggers.

**Order** (agreed 13 October 2026 - the first task runs before there is an editor):
1. the langgraph4j prototype - **passed 13 October 2026** (see 4.); the canvas prototype - **passed 13 October
   2026**: Foblex Flow with dagre (see 6.);
2. **the file stores - built 13 October 2026** (see 1.); then the rest of the backend: the format, the step types
   (reading files through the stores' clients), runs, the scheduler, `/api/v1`, the MCP tools; the
   first task is imported as JSON (import is planned anyway) - **the log check runs from here on**, and its check
   points can be tuned while the frontend is built;
3. the list, the run view and **the text view (YAML) with the graph beside it**, drawn when compiled;
4. **"Draft with AI"** on top of the text view (see 6.) - from then on a tenant admin need not write YAML, only read
   and check it, with the graph beside it;
5. graphic editing on the same graph - only when customers ask for it;
6. the source for embedding (8); the place to write to when a case comes.

**Weighed before agreeing** (13 October 2026): the size - L, about half of it the editor (since 14 October 2026
the graph is shown, not edited - much less); too much for the one case
alone, justified as the building block of workflows (6), sources (8) and approvals. Check points in plain language
bring false alarms: the "verify" step helps, and the check points will need two or three weeks of tuning. Cost: an
agent run every 5 hours over log files, plus verifying, is easily some hundred thousand tokens a day per task - the
pre-filters and "only what is new" are mandatory, not comfort; an own model server brings the cost to zero at the
price of quality. The editor is the frontend's risk: none of the canvas libraries is known with Angular 22 and our
tokens - the prototype decides, before we end up drawing ourselves (it did: Foblex Flow, 6.).

**Decided before building** (13 October 2026): one run of a task at a time (a scheduled start skipped, API and MCP
409); tasks startable by `/api/v1` and the MCP server; SFTP and FTPS with a password; runs kept until the tenant admin deletes
them.

**After packages A, B and C - decided 21 October 2026** (A, B and C built that day, [architecture/tasks.md](architecture/tasks.md)):
- **Package D: "Draft with AI" and the step `prompt`** - built 21 October 2026 ([architecture/tasks.md](architecture/tasks.md)).
  - **"Draft with AI"** as planned in 6. above: the tenant admin describes the task in their own words (or a change of
    an existing one), the tenant's chat model writes the YAML with the format's reference and the tenant's stores,
    models and software as context, the two-stage check corrects it over two or three rounds, the result is a draft in
    the editor - never saved by it. It knows the new step `prompt`.
  - **A new step `prompt` - a chain of prompts** (asked for: answers of one model handed to the next and
    refined by a further prompt, depending on the answer before): a prompt of its own, a model, inputs from earlier
    steps (files read, or the answers of earlier `prompt` steps), a free text answer that the next `prompt` takes as
    its input. **Branching on the answer** through fixed `choices` (e.g. `[approve, rework, reject]`): the model picks
    one, a condition reads it (`review.choice == rework`) - so a condition still never reads a model's free text; with
    an edge back and `maxRounds` "refine until it fits". **The step keeps its answer text** with the run, so the run
    view shows the whole chain. `send_mail` (and later `write_file`) hand the result on. Not possible with A-C: an
    agent step takes only `read_files` as input, answers only in check points, and conditions read only counts.
- **Completion from a JSON Schema in the editor: dropped** (decided 27 October 2026) - about 3-4 days,
  `codemirror-json-schema` would first have to prove it covers YAML, and with "Draft with AI" and "Check" few will
  write the YAML by hand.
- **Graphic editing: dropped** (decided 27 October 2026; "when customers ask for it" since 14 October 2026).
- **The run as a stream of events over `/api/v1`: not built** - the whole course of a run is readable when it has
  ended: the run view (every step, every round, its result) and `GET /api/v1/tasks/{id}/runs/{runId}`; runs are kept
  until deleted. The step `prompt` closes the one gap (an agent step keeps only its structured result, not the model's
  text). Built only when a client software needs it - the software polls today.
- **Still open, each when a task needs it**: `match` (RE2/J), `forEach`, `approval`, `tool`, `write_file`, inputs
  handed to a run over `/api/v1`. The file store as a source for embedding belongs to point 8.

**After a test with a real model - 22 October 2026** (a log check drafted and run with Claude Sonnet against a day's
logs, 311 lines: draft valid in round one, both check points found and verified, the mail sent).
Three things found - the first two built the same day, the third to decide:
- **The draft does not know the file names**: it is told the stores' names and folders only (decided, nothing of the
  files goes out), so it copied the reference's pattern `{yyyy-MM-dd}_*.log` - the files were named
  `<name>-<date>.log`, and the run would have read nothing. **Built 22 October 2026**: the draft
  is told the names of the 20 newest files of each store's folder - names only, never content (docs/privacy.md).
- **The note under a finding describes the verification, not the problem**: mail and run view show the verify step's
  note ("the lines quoted match the logs ..."), while the reader wants to know what is broken. **Built 22 October
  2026**: the verify prompt asks for a note that states the problem itself, never that it was checked - whether a real
  model keeps to it is looked at in the cost review (below).
- **The cost of a run**: about 302 000 input and 5 000 output tokens for 311 lines (verify alone 186 000) - the agent
  loop sends the whole context again every round. Every 5 hours that is a few dollars a day with Sonnet. Taken up in
  the cost review (below).

**Left to close the point - decided 27 October 2026** (package E,
agreed the same day):
1. **The cost shown per run - built 27 October 2026**: first computed when a run was read (1.8.1), since 1.9.0 kept with
   every step and run as the usage recorded it, a failed step's too ([architecture/tasks.md](architecture/tasks.md)
   "Data"; a run from before computed at the lower prices).
2. **The task code without fingerprints - built 27 October 2026**: a store not ready is `task.storeNotReady`; what
   "ready" means is `FileStoreService.readable` by type - a new type of store needs no change in the tasks.
3. **A cheaper verify step - built, measured and taken back 27 October 2026**: excerpts around the findings instead
   of the files and at most 4 rounds cost a quarter of before and lost no error the check step had found (6 pairs
   old/new on the same logs), but were more often "unclear" where the old verification confirmed, and dismissing a
   false finding was never measured - not good enough for results that must be proven equal
  . Kept: the reference says a cheaper model may
   verify. The cost lever without a change of what the model sees is prompt caching (point 15); the check step is
   85-90% of a run. Seen on the way: the task's answers file is cut at `read_files`' limit (the last hours never
   read), and the check step missed the main error in 1 of 6 runs - see 5 and point 45.
4. **Price tiers in the catalog - built 27 October 2026** with point 15 (1.9.0): tiers and cache prices per model, the
   usage priced by them, a run keeping the cost of its steps (a failed step too) instead of computing it.
5. **read_files' limit of 20 MB per step - accepted** (decided 27 October 2026): files over 20 MB per run are rare; where
   one comes, the step is cut and says so, the next run reads on. The real case behind it - an answers file of
   thousands of lines of which only a few matter - is point 45.
6. **Further building blocks**: only when a task needs them (the list above) - they do not keep the point open.

**Point 28 closed 27 October 2026.**

More file stores (S3, SharePoint, OneDrive) are point 44 since 27 October 2026 - the tasks read
through `FileStoreService` and need no change for them.

Dropped: graphic editing (step bar, properties panel) and completion in the editor.

## 29 Files from the MCP server

**Goal** (14 October 2026): the tenant's MCP server is used for programming - Claude Code and GitHub Copilot ask
`ask_assistant`. Today it answers with text only (nothing stored, no `create_file`), and a file the connected
software returns in such a question is dropped (`[file "..." returned - files cannot be offered here]`). The user wants
these files - and files the assistant creates - to reach the client. **Order agreed: first finish the MCP client's
files (tool results of the connected software, mcp.md "Files in tool results"), then this point.**

**Done** (14 October 2026, agreed proposal - mcp.md "Files for the client", "Templates as prompts"): files as
embedded resources, text as text and the rest as base64 as it is (no unpacking: the client's matter), at most 8 MB per
answer, nothing kept; the chat templates as prompts per tenant; files from the client stay text (MCP has no upload,
binary waits for a standard). Open: trying GitHub Copilot with `devtools/mcp-test-server`.

**Claude Code 2.1.295, tried 14 October 2026** (headless, `--mcp-config`, against `devtools/mcp-test-server` and a
backend of our own with a branded tenant):
- a text resource: shown to its model in full - it can write the file into the project; nothing saved by the client;
- a blob resource (ZIP, PDF): the client saves it in its session folder (`~/.claude/projects/.../tool-results/`,
  a generated name, the original's extension) and gives its model the path - the model sees no content but can work
  with the file (`unzip -l` listed a 5 MB ZIP); 5 MB of base64 came whole, nothing cut;
- image content: its model sees the image, the client also saves a copy;
- prompts: `/mcp__mcp-test__code_review Kotlin` and our server's `/mcp__muster-ai__code-review ...` were expanded and
  acted on (the model went to ask Muster AI with the template);
- elicitation: the client declares it; headless it answers `cancel` (no form) - point 30 needs the interactive client
  to be tried.
Our server's files themselves were not tried with a real model (the check backend had none); they have the shape the
test server sends.

**First step - test, before the proposal** (left for the real clients): how Claude Code and Copilot handle what a tool
result may carry:
- text (code, Markdown, CSV) in the answer - the client's model writes it into the project with its own tools;
- an `EmbeddedResource` with `text` or `blob` (base64) - does the client save it, offer it, or give it to its model;
  what size it takes (both limit an MCP tool result);
- `resource_link` with `resources/read` - the clean way for large files, but our server is stateless: a file would have
  to be kept for a while and removed again.

**Open questions for the proposal**: which of these per client; what goes out - the connected software's files only,
or `create_file` too (then `ask_assistant` creates files without keeping a conversation); limits (size, count); privacy
(the files leave to the holder of the MCP key - privacy.md, SECURITY.md); the decision "nothing is stored" in mcp.md
stays unless `resource_link` needs a short-lived store.

## 30 MCP elicitation

**Goal** (14 October 2026): the values a connected software's tool needs come from the user, not from the model. The
case: a connected software's tool that creates a program needs a package and a project name; the model may make them up or take the
examples of the tool's description. MCP's elicitation (`elicitation/create`, in the protocol since June 2025) lets the
**server** ask the user during a tool call: a message and a flat schema (text, number, yes/no, a choice), the client
shows a form, the answer (accept, decline, cancel) goes to the server, past the model, and the call goes on. Not for
passwords or keys - the standard forbids asking for them this way.

**Condition**: the connected software's MCP server uses elicitation (asks for missing values that way). Without a server that asks, our part
lies idle. **Order agreed**: after the MCP client's files and point 29 - in version 1.5.0 (decided 14 October 2026). **Until then, without code**: the rule in the
assistant's instructions (ask for missing values, never make them up), and where needed the tool marked "ändert etwas"
by its tenant admin (the confirmation card shows the model's values first).

**How it differs from what is there**: the confirmation card of a changing tool (mcp.md "Changing tools") is our
client's own - MCP only recommends that a person can refuse a call. It stops **before** the call, no connection to the
server is open, and the user confirms or declines the model's values; nothing can be entered. Elicitation happens
**during** the call, the server asks, the user enters values.

**Open questions for the proposal**:
- the call stays open while the user types: the client session per call (`McpClient.session`) holds its connection,
  the answer's thread waits - how long (today `app.ai.timeout`, 300 s for the whole call), and what happens on stop,
  logout or a closed tab while the form waits;
- the SDK: the capability `elicitation` in `McpClient.session` (today `ClientCapabilities.builder().build()`) and its
  handler, which waits for the user's answer;
- the chat: an event in the stream that shows the form, an endpoint for the answer (only the asking user, once), a
  form in the frontend built from the schema (texts as translation keys around the server's own words);
- without a user - the client software (`/api/v1/chat`) and `ask_assistant` of our MCP server - every elicitation is
  declined at once;
- whether the confirmation of a changing tool and an elicitation in the same call fit together;
- what the user entered: stored with the conversation or not (privacy.md); never in the log;
- a wish from a hand test (a "confirm before running" switch apart from "changes") is likely not needed once
  this is there - decide in the proposal.

## 31 Tool chain

**Goal** (14 October 2026, asked for with a hand test's case): GitHub Copilot or Claude Code asks our MCP server and
sends three Java classes; our assistant calls a connected software's tool with them, which returns a
project as a ZIP archive; the assistant looks into the archive, chooses and adapts the files the asker needs and hands
exactly those back. **It must work without a workflow** (decided): a free question - in the chat as over
`ask_assistant` - gets the whole chain. The scheduled tasks (`feature/automaticTasks`, its own point) may use the same
building blocks as steps later, not the other way round.

**What is there (1.4.0)**: the files sent with the question reach the model and the sandbox; a returned text file's
text is in the tool's result at once (`McpTools.appendTexts`, within 100,000 characters); every returned file goes to
the asker (chat download, MCP resource). **What is missing**: a returned archive (or any binary file) stays closed to
the model in the answer that got it - it is neither unpacked nor in the sandbox's `data/` -, and the model cannot hand
on a file unchanged: it has to write it again with `create_file`.

**Options for the proposal** (worked out on 14 October 2026):
- **(a) Unpack in the backend**: a returned archive (ZIP; tar.gz?) is unpacked with the limits of the document
  import (`DocumentArchive`: entries, sizes, paths, nested archives), its file list and its text files go into the
  tool's result like a text file today. Small to medium, no sandbox needed - but bounded by the result's characters,
  and handing on still means writing the file again.
- **(b) In the sandbox**: files a tool returns in an answer reach the session's `data/` (a sandbox endpoint to add
  files to an open session); the model unpacks and searches there with `run_shell` / `run_python`; a new tool
  `offer_file(path)` hands a file of the session on unchanged (a sandbox endpoint to read a file, within the session's
  work area only) - a download in the chat, a resource over MCP. Medium, a change of the sandbox; only for tenants with
  a sandbox. Closes the known gap "files a session's code writes as downloads" too.
- **(c) Files to a connected software's tool by reference** (optional): `{"$file": "OrderService.java"}` as an
  argument value, replaced by the file's text before the call - instead of the model copying three classes into the
  arguments.

**Open questions**: (a), (b) or both (a for every tenant, b where a sandbox is); the limit of files per answer over MCP
(5 today; 20 text files at 8 MB?); whether a returned archive still goes to the asker when the model hands on parts
of it; security - only reading tools over MCP as today, the sandbox without network, paths checked by the sandbox
itself, types from the allow list; the docs (code-execution.md, mcp.md, privacy.md, SECURITY.md).

## 33 User groups

**Goal** (asked for on 15 October 2026): with many users, setting each one's level per connected software ("Software",
`mcp_access`) and each one's knowledge areas (`tenant_user_area`) is a lot of work. Rights are given to **groups**,
users are members of groups.

**What is there**: both rights are read in few places - `McpConnectionRepository` (4 queries on `mcp_access`) and
`KnowledgeAreaRepository` (4 queries on `tenant_user_area`). The chat, the search, the tools, the software's prompts
and the pending actions only ask "which level / which areas does this user have"; those queries change, the rest
does not. API keys and the MCP key keep their own rights (`tenant_api_key_area`, `tenant_mcp_area`,
`mcp_connection.mcp_server_reads` / `client_software_reads`) - `/api/v1` and `/mcp` do not change.

**Proposal** (draft of 15 October 2026):
- **Data** (management database, `V7__user_groups.sql`): `user_group` (`tenant_id`, `name` unique per tenant
  case-insensitive), `user_group_member` (`group_id`, `user_id`), `group_mcp_access` (`group_id`,
  `connection_id`, `level` READ/WRITE), `group_area` (`group_id`, `area_id`) - foreign keys `ON DELETE CASCADE`.
- **Several groups per user**: the highest level of all groups counts, and the union of their areas. Tenant admins
  keep finding every area.
- **No default group** (decided 16 October 2026): a user without a group has the base every user has today - the chat
  and the company knowledge; everything above it (a software's level, a knowledge area) comes only through groups
  the admin assigns. A new user - also one approved through WAM - is in no group. A "default" group without rights
  would do nothing; one with rights is added when the case is there.
- **API**: the admin's `/admin/tenants/{id}/groups` and the tenant admin's `/tenant/admin/groups` (one service); the
  connection's access list sets groups instead of users (`.../mcp-connections/{id}/access/groups/{groupId}`); the user takes
  `groupIds` instead of `areaIds`.
- **UI**: a shared panel "Gruppen" (tenant admin's page, admin's tab), the software's access list by group, the user
  dialog with groups.
- **No migration of the rights** (decided 16 October 2026, replacing the same day's "migration without loss" - a
  step by hand at the update is accepted): `mcp_access` and `tenant_user_area` are dropped; after the update a tenant admin creates
  the groups and assigns the users - the CHANGELOG says so. Until then every user has the chat and the company
  knowledge.
- **Changes besides**: specs (a new `GroupSpec`; `McpConnectionSpec`, `KnowledgeAreaSpec`, `TenantAdminSpec`,
  `TenantApiKeySpec`), the smoke test (sets a user's level and areas today), mcp.md,
  tenancy.md, ai-features.md, privacy.md, CHANGELOG.

**Decisions** (16 October 2026):
1. No direct rights per user besides the groups - an exception is a group of one; otherwise every right needs "where
   does it come from".
2. Both: the software's levels and the knowledge areas - the same work per user, one model.
3. No default group (first "yes", changed the same day): the base is what every user has without a group.

**Extension: checked against the IDM groups of a WAM proxy** (asked for on 15 October 2026, size S on top): a
company's WAM proxy can send the person's IDM groups in a header next to the user. A group in Alpdesk AI gets an
optional **WAM group**; with WAM on and a groups header configured, membership of such a group counts only while
the proxy's header lists that IDM group - assigned in Alpdesk AI **and** in the IDM. A right taken away in the IDM is
gone with the next request, without a tenant admin.
- Who looks the groups up: not Alpdesk AI (no LDAP of our own) - the proxy, like the user name: the groups come from
  LDAP / Active Directory, read by the proxy for the signed-in user and sent in the configured header. A commercial
  WAM usually has this built in; Apache `mod_authnz_ldap` can; plain nginx cannot look up LDAP itself - it takes the
  groups from an auth service through `auth_request` (`auth_request_set $groups $upstream_http_x_groups;
  proxy_set_header X-Remote-Groups $groups;` - which also replaces a header sent from outside). An example goes into
  the public README's WAM section.
- Settings: `app.wam.groups-header` (`APP_WAM_GROUPS_HEADER`, empty = off) and its separator; compared
  case-insensitively and trimmed; what the proxy sends (plain names, `CN=...` of a DN) is decided with a real header.
- **Fail closed** (decided 16 October 2026): a group with a WAM group does not count when the header is missing or
  does not list it - otherwise a proxy that stops sending groups would hand out every right. Also while no groups
  header is configured (review of 16 October 2026). Groups without a WAM group count as before.
- Where it is checked (decided 16 October 2026): the IDM groups are **kept on the user**. `ActiveTenantUserRule` reads
  the header on every request already and writes the groups to the user when they differ (a request without the
  header writes none - fail closed); every query of the rights filters with them, so the streamed answer's tools, the
  software's prompts and the pending actions check the same. Not handed through to every check (`ToolContext`,
  prompts, pending actions): more places, and one forgotten checks wrongly. The groups the proxy sent last can be
  shown to the admin - it shows what the proxy really sends. privacy.md names that they are stored.
- The header is trusted like the user header (SECURITY.md): the proxy strips it from outside requests.
- Header size: a long list of groups can exceed the server's header limit (`micronaut.server.netty.max-header-size`, 8 KB by default) -
  the proxy should send only the groups that matter, or the limit is raised.
- Not part of it (a later option): membership taken from the IDM alone, without assigning in Alpdesk AI.

## 34 Handbook

**Goal** (asked for and agreed on 15 October 2026): a tenant has pages to read beside the chat - how-tos, rules, an
overview. They are **for people only**: the assistant does not know them (what it should know goes into the company
knowledge as before).

**Changed on 20 October 2026**: the first plan (15 October) was Markdown pages shown as a tree in the app's own look.
It is **dropped for now** in favour of a finished static HTML website per scope, shown in an iframe - simpler to build
(no tree, no Markdown rendering; the website brings its own navigation) and what the tenants have. Who has Markdown
builds a website from it (MkDocs or the like). Planned for **1.6.0**.

**Agreed** (20 October 2026):
- **Upload**: a tenant admin (and the admin for a tenant) uploads a **ZIP with a static website** (`index.html` at its
  root, CSS, JavaScript, images, fonts, sub-pages) for one scope: **"Alle"** (no area) or **one knowledge area**. The
  ZIP **replaces that scope's website**; "Entfernen" deletes it. Files of other types are skipped with their reason.
- **Who sees what**: the website of "Alle" and those of the user's own knowledge areas (through the user groups);
  tenant admins see all - the rule of the company knowledge (`KnowledgeAreaService.scopeOfUser`).
- **UI**: one entry **"Handbuch"** in the sidebar, shown only when the user has a website; one entry per scope, a click
  shows the website in an iframe. The admin gets a tab in the tenant's page (a shared panel with `TenantScope`).
- **Security** - the user's JWT lives in the browser storage of our origin, so the uploaded HTML must never run as our
  page:
  1. every file of a website is answered with `Content-Security-Policy: sandbox allow-scripts allow-popups` (no
     `allow-same-origin`): the browser treats it as a foreign origin - its scripts run, but reach neither the token nor
     the API, also when the address is opened directly in a tab;
  2. an iframe cannot send an `Authorization` header: the app fetches, with the user's login, an address with a
     short-lived token in the path (`/doc-sites/<token>/index.html`, about an hour, one scope only); relative links stay
     below it and work as they are;
  3. `frame-ancestors 'self'` for this route only - every other answer keeps `'none'`.
- **Given up on purpose**: the website keeps its own look (no dark mode, no branding of the app); a link without
  `target="_blank"` opens inside the iframe, and many sites refuse that; the assistant does not know the content.

**Built** (20 October 2026, 1.6.0) - the rules are in [architecture/doc-sites.md](architecture/doc-sites.md); changed while
building: the upload is a multipart `POST` with `areaId` and deletion and the address go by the website's id
(`DELETE .../doc-sites/{id}`, `POST .../{id}/address`); a ZIP of one folder is served from that folder; 500 MB unpacked
at most. **The proposal as agreed**:
- **Data**: the tenant database (`db/tenant/V4__doc_site.sql`), deleted with it: `doc_site` (`id`, `area_id` NULL =
  "Alle" - no foreign key across databases, like `document.area_id` -, `storage_prefix`, `file_count`, `size_bytes`,
  `uploaded_at`), unique per scope. The files live in the `FileStorage` below `tenants/<id>/doc-sites/<uuid>/`: a new
  upload writes a new prefix, swaps the row in one transaction and then deletes the old prefix - the website is never
  half replaced. Deleting the tenant deletes them with its storage prefix. A knowledge area with a website cannot be
  deleted (409, like an area still holding documents).
- **Token**: signed (HMAC with a secret of the installation), carrying tenant, scope and expiry - stateless, no table.
- **API** (package `docsite` in every layer): `GET /tenant/admin/doc-sites` (per scope: files, size, when uploaded),
  `PUT /tenant/admin/doc-sites?area=` (the ZIP), `DELETE /tenant/admin/doc-sites?area=`; the admin's mirror below
  `/admin/tenants/{id}/doc-sites`; the users' `GET /tenant/doc-sites` (their scopes) and `POST
  /tenant/doc-sites/{scope}/session` (the iframe's address); `GET /doc-sites/{token}/**` serves the files (anonymous
  in `intercept-url-map`, the token is the check). Not in `/api/v1`, not over MCP, not in any search or tool.
- **Limits** (proposal): ZIP 100 MB, 2000 files, allowed types HTML, CSS, JS, images, fonts, JSON, text.
- **Specs**: replacing a scope (the others untouched, the old files gone), validation (no ZIP, no `index.html`,
  limits, skipped types), isolation between tenants and between areas, an expired or foreign token, the sandbox and
  frame headers on every file, path traversal in the ZIP and in the address, roles (`TenantApiKeySpec`), an area with
  a website not deletable.
- **Docs**: a new `docs/architecture/doc-sites.md` (linked from CLAUDE.md and the architecture index), `SECURITY.md`
  (a new route reached by token, uploaded scripts sandboxed), privacy.md (what is stored, when it is deleted),
  CHANGELOG under 1.6.0.

**Later, when asked for**: Markdown pages in the app's own look (the plan of 15 October), search across the websites.

## 35 Videos through code execution

**Agreed on 17 October 2026.** Step 1 built (17 October 2026): ffmpeg in the sandbox image, with
`MALLOC_ARENA_MAX=2` for the address-space limit, measured - `/work` stays a tmpfs of 1 GB for 500 MB videos
([architecture/code-execution.md](architecture/code-execution.md) "Videos"). Step 2 built (17 October 2026): files
streamed into the session, `view_images` and `hand_out_file`, a call may run 300 s
([architecture/code-execution.md](architecture/code-execution.md) "Images and files out of the session"). Step 3 built
(17 October 2026): the upload (500 MB, streamed above 50 MB, refused without a sandbox), the cleanup job (videos after 8 hours, every
other chat file after 14 days, `expired_at`), the Gemini path removed (V8) - left out on purpose: the video's length and resolution in the file list
(the model asks ffprobe). Step 4 built (17 October 2026): the rules for videos in the system prompt
([architecture/code-execution.md](architecture/code-execution.md) "Videos"). Next: the first real test with a video
and a test tenant's company knowledge (decision 6), then the full suite.

**First real test, 17 October 2026** (a test stack of its own: a short phone video, 36 PDFs of course
material on skiing embedded with OpenAI `text-embedding-3-small`, "look from second 16 on"):
- Claude Opus 5.5 and Haiku 5.5 refuse a temperature (HTTP 400) - the catalog's "takes temperature" off; no code.
- Haiku 5.5, first run: six rounds ran out before it had looked at the frames - hence `app.chat.video-rounds` (16).
- Haiku 5.5, second run: the whole chain in 82 s and 15 tool calls (ffprobe, overview, frames, view_images four times,
  three searches, a still drawn with Pillow and handed out) - an answer with sources and two drills from the
  documents, the marks well placed on the person. About 0.33 $ for both runs (284 000 input tokens, the catalog's
  prices placeholders); the embedding of all documents 0.002 $. The text in the still lost its umlauts (Pillow's
  default font) - the prompt now names DejaVu, the image installs it.
- Open: whether the assessment is right ("hips too high" - to a layman the still rather shows the hips behind the
  boots) - the user judges it; the same question with Opus 5.5 to compare.

**Next: a movement in its phases** (agreed 18 October 2026; steps 1 to 4 built 18 October 2026 - the
video sandbox's image with rtmlib and `posture.py`, the rules in the system prompt,
[architecture/code-execution.md](architecture/code-execution.md) "Videos"; step 5 passed with Claude Sonnet 5.5,
reported 27 October 2026). One still is too little for
a technique: faults lie in the course of a movement (e.g. edged too late at the start of a turn, then sitting back in
its middle). The model looks at a movement in its phases - for a turn: entry, middle, exit - and shows them. In this
order, each after the one before has proved itself:

1. **Pose estimation in the sandbox** - measured first (18 October 2026): RTMPose (Halpe-26,
   with heels and toes) and RTMO through rtmlib, MediaPipe's Pose Landmarker, all Apache-2.0, on the first real test's
   video - do the points sit on the person, are the angles stable, does it run under the sandbox's 1 GB per program,
   what does it add to the image. Left out on purpose: Ultralytics' YOLO-pose (AGPL-3.0 - not in the public image,
   whatever it scores), OpenPose and MMPose (no longer developed). The winner goes into the sandbox's venv with its
   models baked in (no network there); Sports2D (BSD-3, angles from rtmlib's points) is considered with it.
2. **The phases from measured values**, not from the model's eye: the body's lean over time (feet to hips against the
   vertical) is largest in the middle of a turn and passes zero at the change of edges - entry and exit lie between.
   Angles between the body's parts, not paths across the picture (a phone pans along). At least 10 frames a second.
   A helper script for it only if the model does not find the phases reliably with the pose data itself.
3. **A sequence of 5 - 30 stills** (decided 18 October 2026), each with the person's skeleton and its
   main angles drawn in - no aligning of frames needed -, handed out in one `hand_out_file` call and shown in the
   chat with a slider that steps through them or plays them like a short clip (built 18 October 2026). Sequence images on one background (A: skeletons, B:
   the person cut out, like a stroboscope picture) were tried and worked (below), but are not wanted: no MediaPipe
   (it was needed for B's cut-out) and no aligning code in the product - rtmlib alone.
4. **The prompt, industry-neutral**: the core's rules speak of a person, a movement and its phases, a sequence image
   and a strip of stills - never of a sport. What a phase is called and what counts in it for a trade belongs in the
   tenant's chat template (e.g. "Technikanalyse Ski", resting on its company knowledge; for a craftsman
   e.g. "Hebetechnik prüfen" - lifting, carrying, setting down). The same tools serve any movement of a person: a golf
   swing, a run, ergonomics at the workplace, a transfer in care.
5. **A real test** (passed with Claude Sonnet 5.5, reported 27 October 2026) with the test video and a sample chat template, with Opus 5.5 next to Haiku 5.5; the rounds
   (`app.chat.video-rounds`, 16) checked against two turns in three phases each.

**Measured 18 October 2026** (a test video from second 16, 60 frames at 10 per
second, Podman with 2 CPUs; the images stay in `local-data/pose/`):

| | RTMPose (rtmlib, "performance") | RTMO-l (rtmlib) | MediaPipe heavy |
|---|---|---|---|
| Person found | 60 of 60 | 44 of 60 | 43 of 60 |
| Points on the joints | good, also small and far away and in a steep lean; heels and toes on the boots | good, a little less often | good close to the camera; took a snow cannon for the person far away, off in a steep lean |
| Time per frame | 3.7 s | 1.5 s | 0.17 s |
| Peak memory | 998 MB | 483 MB | 299 MB |
| Under the sandbox's 1 GB address space | no (ONNX Runtime's `bad_alloc` loading the models) | no | yes |

All three install and run on Python 3.14 (rtmlib with ONNX Runtime 1.31, MediaPipe 1.1.1 - it needs `libegl1` and
`libgles2`). RTMPose is the best by far. Its smaller sizes, measured the same day on the same frames:

| RTMPose | Person found | Time per frame | Peak memory | Under 1 GB | Points |
|---|---|---|---|---|---|
| performance | 60 of 60 | 4.1 s | 999 MB | no | good |
| **balanced** | 60 of 60 | 0.96 s | 447 MB | yes | as good as "performance" - also the small racer far away in a steep lean |
| lightweight | 60 of 60 | 0.16 s | 195 MB | yes | good close to the camera; the small racer's legs wrong |

**Decided: RTMPose "balanced" through rtmlib** - the quality of the largest size, under the sandbox's limit per
program (nothing loosened), about a second a frame (ten stills of a turn in about ten seconds). Not MediaPipe, not RTMO.

Sequence images (`strobe.py`): two turns of the video, A and B, both worked - one at a panning, zooming camera
past a gate, 12 of 12 frames aligned neighbour to neighbour (300 - 2 700 inliers a step), ten positions in one
panorama; the other 7 of 11 - where the racer passes so close that he fills the frame in front of plain snow, no
background is left to align on (0 - 3 inliers). Aligning every frame to the middle one directly lost half the frames;
the chain fixed it. The median background at the canvas's edge, covered by few frames, still shows the person faintly
(variant A). Not tried: a tripod - the easy case.

**The case**: a tenant has strong company knowledge on a technique (e.g. ski racing) and wants a phone
video of a person analysed against it - "what can she do better?". A second case seen on claude.ai: a 29-minute
video of a finish area plus a result list, cut into one clip per finisher with the time shown. Both need what the
model's own video reading cannot give: many frames of a few seconds, in full resolution, chosen by what happens.

**Why the current path goes** (decided 17 October 2026): a video is turned into a copy of at most 640
pixels width, one frame per second, strongly compressed (`MediaConverter`, `-crf 32`), at most 20 MB, and only a
model that reads video (Gemini) gets it - and Gemini samples one frame per second itself. A ski turn takes about a
second: the model sees one frame per turn. The tenant's model (Claude, GPT) does not read video at all. Gemini stays
for what it is strong at - creating and changing images (`create_image`, point 25).

**How it works instead** - like Claude on claude.ai: the video lies in the sandbox's `data/` as uploaded, the model
uses ffprobe and ffmpeg through `run_shell` / `run_python`, first an overview (a contact sheet: 16-25 small frames
tiled into one image, about the tokens of one image), then dense frames around the moments that matter (e.g. 10 per
second for 3 seconds, cropped to the person), looks at them, searches the company knowledge for what it saw and
answers - optionally with a cut clip or marked stills as files of the answer. Every chat model reads images, so it
works with every model, and the video never leaves the installation: only the frames the model chose go to the
provider.

### What changes

1. **The sandbox image gets ffmpeg** (with ffprobe; Ubuntu's package) - built 17 October 2026: +450 MB (869 MB),
   `MALLOC_ARENA_MAX=2` so its threads fit the address-space limit, `selftest.py` cuts frames out of a full HD video
   with sound. No OpenCV or pose estimation in this step.
2. **Files out of the sandbox** - two things the sandbox cannot do today (its calls answer text only):
   - the model **looks at images** its code wrote: a new tool (name open, e.g. `view_images`) takes paths in the
     session, fetches them through a new endpoint of the sandbox (`GET /sessions/<id>/files/<path>`, only below the
     session's directory) and returns them as images after the tool results (`ToolResult` attachments, as `read_file`
     does). Each scaled to at most 1568 pixels on the longest edge, at most about 12 images per call - a frame of
     1280x720 costs Claude about 1 200 tokens;
   - the model **hands a file to the user**: a cut clip, stills with marks, a CSV. A second tool (e.g.
     `hand_out_file`) stores a session's file as a file of the answer, like `create_file`. This also closes the open
     point of 26 ("files a session's code writes as downloads of the answer").
3. **Large videos into the sandbox.** Today every file goes to the sandbox base64 in one JSON body (256 MB at most),
   lies in the app's heap as `byte[]` on the way, and `/work` is a tmpfs of 1 GB counted against the container's 2 GB
   memory. A phone video of 29 minutes in its original quality has 2-4 GB. Needed: the upload streamed to storage
   without the heap, a streamed transfer of one file into the session (e.g. `PUT /sessions/<id>/files/<name>`, raw
   body to disk), and enough room in `/work`: with 500 MB per video measure first whether the tmpfs of 1 GB (counted
   against the container's 2 GB) holds the video, ffmpeg and the frames - only if not, `/work` goes on disk
   (`docker-compose.tenant-sandbox.yml`).
4. **The upload of a video**: no model copy any more - the original is stored as it is. ffprobe (in the app, which has
   ffmpeg for audio) still checks it is a video and reads its length and resolution for the file list the model
   gets ("video, 85 s, 848x478, 30 fps"). Audio stays as it is (its copy for the transcribing model, the transcript).
5. **The Gemini video path is removed**: the catalog's `reads_video` (a new management migration drops the column),
   its field in `AiModelRequest` / `AiModelResponse`, the checkbox and icon in the frontend with their keys in
   `en.json` / `de.json`, `VideoContent` in `ToolResult`, the video branch of `MediaConverter`, `ReadFileTool` and
   `ConversationFile.mediaType`. `read_file` on a video answers: "examine it with run_shell / run_python in data/" -
   or, when the tenant's sandbox was removed since, that the video cannot be looked at here. The stored video copies of
   existing conversations stay until the conversation is deleted (not worth a cleanup of their own).
6. **The prompt** (`ChatService.CODE_RULES`, only when a video is among the files): probe first, overview before
   detail, frames small and few until it knows where to look, crop to the subject, say what it could not see (too
   far, too blurred, hidden) instead of guessing, and - for an assessment - search the company knowledge for each
   point before it recommends. A tenant's chat template ("technique analysis") does the rest of the trade-specific
   part - no ski wording in the core.
7. **The client software and MCP**: `/api/v1/chat/files` takes videos as the chat does (same path). `ask_assistant`
   only brings text files - no change.

**Specs**: `ChatFileSpec` / `ChatMediaSpec` (a video is stored as it is, the file list shows its length, no copy);
`ReadFileToolSpec` (a video points to the code tools, without a sandbox the other text); the new tools through the
`Toolbox` against `FakeSandboxes` (images returned scaled and limited, paths outside the session refused, a handed-out
file stored with the answer, tenant isolation); `FakeAiModels` learns to call them; `AiModelSpec` without `readsVideo`;
`SandboxesSpec` for the new endpoints; the upload refused above the size and without a sandbox (data table); the cleanup job deletes a video after its hours and only the video, keeps it with the setting empty, the expired file tells the model; `selftest.py` for ffmpeg, the file endpoint (no `../`, no symlink out of the
session) and a large streamed file; the smoke test uploads a short video and the fake model looks at a frame.

**Docs and files**: `ai-features.md` (files in the chat, the catalog), `code-execution.md` (ffmpeg, the file
endpoints, `/work` on disk, the new tools), `docs/privacy.md` (a video stays in the installation, only chosen frames
go to the provider; files handed out from the sandbox are stored with the answer), `THIRD-PARTY.md` (ffmpeg in the
sandbox image, LGPL/GPL build of Ubuntu's package - check which), the public README "Code execution" (`/work`),
`CHANGELOG.md` and `app.version` 1.6.0 (a feature; Removed: the catalog's "reads video") (decided).

**Not built on purpose**: the Gemini Files API and `videoMetadata.fps` (not needed once frames come from code; not in
langchain4j 1.22.0 anyway); pose estimation (MediaPipe, joint angles) - a later step if the frames alone are not
precise enough; measure with real videos first.

### Decided (17 October 2026)

1. **Size only**: one setting of the installation in `application.properties` (`app.chat.video-max-size`, default
   500 MB, from the environment like every `app.*`), checked before the upload is read, as every limit today - no
   check of the length, no setting per tenant (no case for one; the videos of all tenants share the disk). Other files
   stay at 50 MB.
2. **Run time per call**: 300 s (`SANDBOX_TIMEOUT_SECONDS`), the session's 30 minutes stay.
3. **Tools**: `view_images` and `hand_out_file`.
4. **No sandbox, no video**: a tenant without code execution gets its video upload refused (with an error code the
   frontend translates); the chat's file picker says why.
5. **Version 1.6.0.**
6. **First test**: a short phone video (85 s, 848x478, 30 fps) with a test tenant's company knowledge on ski racing,
   asked like "look from second 20 on at the racer: what does he do wrong, how can it be improved?". So the prompt
   rule: a time or a passage the user names is where the model starts - it cuts frames there instead of an overview
   of the whole video.
7. **How long files are kept** (decided 17 October 2026; first for videos, then for every chat file):
   `app.chat.video-retention-hours` (default 8, after the upload) for an uploaded video, `app.chat.file-retention-days`
   (default 14) for every other chat file, uploaded or created; left empty, a retention is off. One job,
   `ChatFileCleanupJob`, at `app.chat.cleanup-cron` (every hour). The file's row stays, marked expired, without its
   text; the content goes from the storage. Deleting the conversation still deletes its files at once - through the
   existing path (`ChatService.deleteConversation` removes every file's storage folder), nothing new to build; a spec
   proves it for a video. Known gap, left on purpose: when the storage cannot delete, the content stays behind with a
   warning in the log and the job cannot find it (it has no row any more) - no case on a local disk. In the sandbox a
   file lies only while an answer runs, as before. An installation updated to 1.6.0 loses its chat files older than
   14 days within an hour - the changelog says so before the update.
8. **Drawing into a still** ("show me the mistake on a still and draw how the position must change"): the model draws
   with code - Pillow (in the sandbox already: lines, arrows, circles, angles, text) or ffmpeg's `drawbox` /
   `drawtext` - on a frame it cut, looks at the result with `view_images` and corrects it, then hands it out with
   `hand_out_file`; the chat shows it like a created image. To place its marks the model must know where things are
   in pixels: `view_images` names every image's size, and the prompt rule tells it to lay a labelled grid over a frame
   first when it needs coordinates (models estimate positions in an image only roughly). For a drawing that is about
   the look rather than the exact place, the model may hand the still to `create_image` (Gemini, point 25 - it changes
   images well, but may alter the person). Exact joint positions and angles would need pose estimation - not in this
   point (above).
9. **Kubernetes**: nothing new is needed - ffmpeg needs no privilege and runs under the "restricted" pod security like
   every program of a session; `/work` is an `emptyDir` with `medium: Memory` and `sizeLimit: 1Gi` there
   (`Backend/podman/public/README.md`), the same room as the compose file's tmpfs, counted against the pod's memory
   like there. The app's `/tmp` (`emptyDir`) already holds a 512 MB ZIP import, so a 500 MB upload fits; the storage's
   PersistentVolumeClaim must hold the videos of the retention time. Still not run on a real cluster - the known
   weakness of point 26.

## 36 Binding and supplementary documents

**Goal** (13 October 2026): a tenant keeps two kinds of documents in its knowledge - its **own documentation** (how
its product is set up and configured, what it supports) and **manufacturers' manuals** (hardware, devices, parts),
embedded so the chat can tell more about the hardware. For how the tenant's product is set up only its own
documentation counts - it does not support everything a manufacturer describes. The case: the model took the
manufacturer's configuration steps into answers about configuring the tenant's product. A rule in the tenant's
instructions ("never take configuration from the manufacturers' documents") helps, but the model has to guess from a
file name which passage is whose.

**Why the instructions alone are not enough**:
1. **Telling them apart**: `similarity_search` marks a passage with its file name only (`--- From "<name>" ---`); the
   model guesses from "Handbuch_v3.pdf" whether it is the tenant's or a manufacturer's. A ZIP folder helps today (the
   path is in the chunk's header and the source: `Herstellerdokumentation/...`), a pushed or single upload has none.
2. **Crowding out**: a manufacturer's PDF is long and has many chunks; for a configuration question its passages can
   fill the hits and push the tenant's own shorter passages out - then the model has no choice, whatever the rule.
   **To be measured first**: how often this happens with real questions. If it does not, step 2 below is left out.

**Proposal** (not agreed yet):
1. **A document is binding (the default) or supplementary**, with an optional note per document ("Herstellerangaben -
   für die Einrichtung gilt allein unsere Dokumentation"). Set on upload and ZIP import (per archive), changed in the
   list "Firmenwissen" (shared `document-panel`, admin and tenant admin) and by a push of the client software (an
   optional multipart field on `/api/v1/documents`; left out: unchanged, like `area`). A metadata change like moving to
   another area - nothing is embedded again. Industry-neutral: a craftsman marks standards or data sheets, a company
   its suppliers' manuals the same way.
2. **The search keeps a share for each** (only if the measurement of point 2 above shows the crowding out): the
   binding hits first with their own quota, the supplementary ones after them with theirs - a long manual can no longer
   push the tenant's documentation out. One tool, not a second `search_reference_docs`: the model would have to
   remember to call both.
3. **Supplementary passages are marked** in the tool's result (`--- From "X" (supplementary source: <note>) ---`),
   also in `read_document` and the sources under the answer.
4. **A fixed rule in the system prompt**, only when the tenant has supplementary documents: how the tenant's product is
   set up, configured or operated comes from binding documents only; supplementary ones only for facts the binding
   ones do not hold, named as such; where they disagree the binding ones win.

**Layers**: tenant migration (a column on `document`, the note); `DocumentRepository`, `TenantEmbeddings` /
`KnowledgeSearch` (the flag in the hit, the quotas), `SimilaritySearchTool`, `ReadDocumentTool`, `ChatService` (the
rule); upload, ZIP import, push and a `PUT .../documents/{id}/...` for the admin and the tenant admin; the
`document-panel` (choice on upload, a column or chip, the change), en.json and de.json.

**Specs**: set and changed through every way in (admin, tenant admin, `/api/v1` push - left out keeps it); another
tenant's document 404; the search keeps both shares and the marking reaches the tool's result and `read_document`;
the rule is in the system prompt only with supplementary documents; nothing is embedded again on a change.

**Open questions**: the names in the UI ("verbindlich" / "ergänzend"? "Referenz"?); a default per knowledge area or
per ZIP folder instead of per document; the quotas (e.g. 5 binding, 3 supplementary); whether the note is free text
or the flag alone is enough; the measurement - a few real configuration and hardware questions against the tenant's
documents with and without the manuals.

## 37 Claude Cowork and Alpdesk AI together

**Goal** (23 October 2026): a small company - three people in the office, the files on a NAS - gets both: the owner
works with **Claude Cowork** (an agent on the PC: it reads and writes local files, drafts offers, sorts folders), the
staff use **the chat of Alpdesk AI**, and Alpdesk AI is the **memory of the company** behind both - the projects and
delivery notes of the last years, searchable for everyone. Cowork reaches that memory through the tenant's MCP server.
Alpdesk AI does not try to become a local agent itself; the large clients (Cowork, claude.ai, Claude Code, Copilot)
are clients of it, not competitors.

**Checked on 23 October 2026** (the providers' pages - to be read again before relying on them):
- **Custom connectors with remote MCP** work in Claude, Cowork and Claude Desktop, on every plan (Free: one
  connector). Besides OAuth they take **fixed request headers** ("Anforderungsheader": an API key, a bearer token),
  stored encrypted - so `Authorization: Bearer mcp_...` should work **without OAuth and without code**. The connection
  comes from Anthropic's network: the server must be reachable from there (public HTTPS, or a firewall open to
  Anthropic's IP ranges). Pro/Max: the user enters URL and header; Team/Enterprise: the owner, then everyone uses the
  same credentials. Changing a connector means removing and adding it again.
- **API credits with a subscription** (since 7 October 2026): Max 5x 100 USD, Max 20x 200 USD, Team up to 500 USD
  pooled, **Pro none**; for API calls with a key of a Console organisation linked to the plan, not for Claude Code or
  the apps; they expire at the end of every billing month. The owner's key as the tenant's own Anthropic key would pay
  the staff's chat out of these credits. **Open**: whether the credits of a personal Max plan may serve the staff.
- Anthropic offers no embedding and no image model: embeddings stay with bge-m3 (own server) or another provider's
  key, images with an OpenAI or Gemini key.

**Step 0 - try, no code** (S): add the tenant's MCP server as a custom connector in Cowork with the header, have a
Cowork task call `ask_assistant`, check the answer with its sources and its files (point 29). Confirms the header is
taken as sent and Cowork's tasks see the connector. A known report of OAuth connectors failing in Cowork (closed)
does not concern the header way.

**Step 0, first result (24 October 2026, 1.7.1, a test tenant with a course corpus)**: the custom connector with
a fixed header works - authentication "No sign-in" (with OAuth the `authorization` header is greyed out), header
`authorization`, value `Bearer mcp_...`. The first try failed because the space after "Bearer" was missing: the
claude.ai dialog sends the value exactly as typed, and `/mcp` answers 401; a header cannot be changed afterwards
(remove and add again). Called from a Claude Code session of the desktop app through the claude.ai connector:
`list_templates` and `list_tasks` answer, `ask_assistant` answers a domain question with its sources and a template
by name, and says plainly when nothing is found. Seen on the way:
- `ask_assistant` names passages it "did not read completely" - one answer per call, its own rounds; with
  `search_knowledge` / `read_document` (step 1) the client's model could read on itself.
- The sources line names every document found, also those the answer did not use, with their full paths - with
  Cowork they reach Anthropic too (file names may name people).
- Without a key `/.well-known/oauth-protected-resource` answers 401, not 404: harmless with the header; a client
  trying OAuth would look there in vain.
- The MCP key card shows the Claude Code command and Copilot's `mcp.json`, not yet what to enter in the claude.ai /
  Cowork dialog ("No sign-in", `authorization`, `Bearer ` with the space) - the missing space was the one stumble.

Still open: the same in a Cowork task (local files together with `ask_assistant`), a file created by the assistant
reaching the local folder (point 29), the usage page.

**Step 1 - knowledge tools over MCP** (M, built 24 October 2026, 1.8.0 - mcp.md "Searching the knowledge"; decided:
always offered, by document id, the chat's number of results, paths like the chat, search first in the descriptions): `search_knowledge(question)` (excerpts with their sources, through
`KnowledgeSearch`, the MCP key's areas) and `read_document` (reading on in a document found, point 23) next to
`ask_assistant` - as first planned in point 10. Then Claude's own model does the thinking and Alpdesk AI only supplies
the knowledge: no second model behind the first, no double cost, less waiting. `ask_assistant` stays for templates and
for tenants whose model must stay in the house. **Changes a decision** of [architecture/mcp.md](architecture/mcp.md):
the MCP server "sees no documents, only answers" - it would then see excerpts and passages (still no list of
documents, no download). Usage: the search query's embedding is recorded as today; the client's tokens are not ours.

*How* (looked at on 24 October 2026): the parts are there - `KnowledgeSearch.search(tenant, null,
scopeOfMcpServer(...), query, n)` and `DocumentRepository.findFoundText(dataSource, id, allowedAreas)`, which already
checks the area. What differs from the chat's `similarity_search` / `read_document`: `/mcp` is stateless (no session),
so `read_document` cannot rely on "found earlier in this answer" (`ToolContext.foundDocument`) and has no reading
limit per answer. The search therefore hands out a reference with every passage (the document's id and the offset the
passage starts at, `ReadDocumentTool.locate`), and `read_document(document, offset?)` reads one part per call, checked
against the MCP key's areas on every call - an id of another area or a deleted document is "not found", the same as
one that never was. Both read-only, flat schemas, the tenant's brand in their descriptions like `ask_assistant`; only
with an embedding model. Probably a class of their own next to `AssistantMcpTools` (`KnowledgeMcpTools`).

*Size* (about 4-5 days, M at its lower end): the two tools 1.5-2 days; specs 1-1.5 (happy path through `/mcp`, the
areas of the MCP key, another tenant's or area's id, a deleted document, offsets beyond the end, no embedding model,
usage recorded); the smoke test calls both (half a day); mcp.md (the changed decision), privacy.md (excerpts and
passages go to the client), CHANGELOG `### Added` (half a day); a real try with Claude (half a day). Decided with the build: always offered (no
switch per tenant), as many passages as the chat's search returns.

**Step 1b - the MCP key card for claude.ai and Cowork** (S, agreed and built 24 October 2026, 1.8.0 - mcp.md
"Connecting claude.ai and Cowork"): the card "Claude Code (MCP)"
gets a third part next to the Claude Code command and Copilot's `mcp.json` - what to enter in the connector dialog of
claude.ai, Claude Desktop and Cowork: the URL, authentication "No sign-in", request header `authorization`, the value
`Bearer mcp_...` (copyable while the key is shown, the space spelled out), and that a header cannot be changed (a new
key: remove the connector and add it again). Frontend only: the values from `utils/mcp.util.ts` with its spec, texts
in en.json and de.json, a CHANGELOG line.

**Step 2 - push from the NAS - dropped 27 October 2026**: the app reads the NAS as a file store (SFTP, a read-only
user) through a source of point 8 - no script at the company's. The price: the server must reach the NAS (the operator's internal hosts, or a port
or VPN for an installation outside the company's network). What was planned: a small script on the company's NAS (its task scheduler, Python, a
read-only user) walks the chosen folders and pushes new and changed files to `/api/v1/documents` with the relative path
as `externalId` (a hash where it is longer than 200 characters), deletes vanished ones by `externalId`, skips types
not read, logs refusals instead of retrying them. Outbound HTTPS only - no port opened at the NAS. No backend change:
an unchanged push is recognised by its content and not embedded again. Belongs to point 8 (sources); one script
configurable for every installation (folders, types, area, interval). **Open**: where it lives (outside the source ZIP
like `devtools/`, or published for operators), and the cost of the first import (scanned pages through the image
model - count and limit first).

**Later - sign-in per person (OAuth for `/mcp`)** (L, about 2-3 weeks): only when several people of one tenant use
Claude clients and each needs their own knowledge areas and usage, or a client takes no fixed header. Alpdesk AI
becomes the OAuth server of its MCP server: discovery (`/.well-known/oauth-protected-resource`,
`.../oauth-authorization-server`, also below a context path), dynamic client registration (anonymous - limits),
`/oauth/authorize` with PKCE through an Angular consent page (password or WAM), `/oauth/token` with rotating refresh
tokens stored as hashes, a validator yielding tenant and user with the rules of `ActiveTenantUserRule`, the tools with
the person's groups and areas, "connected apps" on the account with revoke. Changes the decision "one MCP key per
tenant" (it stays for Claude Code and Copilot). A new attack surface - into the security review afterwards.

**Later, when a customer asks**: writing back (Cowork files a summary or an offer into the company knowledge, an
area). Alpdesk AI on a company's NAS or a Raspberry Pi has its groundwork in 1.8.0 (images for `linux/arm64`,
`docker-compose.proxy.yml` behind a NAS's reverse proxy); left: memory for Java, PostgreSQL and Ollama, and the connector
reaches it only through an open port (Anthropic's range only) or a tunnel.

**Privacy and security**: with Cowork the excerpts go to Anthropic under the customer's plan (Pro and Max are
consumer plans) - [privacy.md](privacy.md) says what goes where. Excerpts of documents reach an agent that may write
on the PC: a prepared document weighs more there than in the chat - [SECURITY.md](../SECURITY.md).

**Open questions**: the credits for the staff (above). Decided with steps 1 and 1b (24 October 2026):
`search_knowledge` is always offered with the chat's number of excerpts, and the MCP key card shows the connector's
settings for claude.ai and Cowork next to the Claude Code command and Copilot's `mcp.json`.

## 38 Python in scheduled tasks

**Goal** (24 October 2026): a task analyses measurements - e.g. the readings of a technical plant (flow, water
levels, power, speed, bearing temperatures, vibration) - and finds slow changes over days and weeks: a bearing that
runs warmer at the same power, efficiency dropping (less power at the same flow), vibration rising at the same speed.
Not a protection or alarm system (a task runs at most every 15 minutes, a model may err): the second look at what
creeps up unnoticed.

**Why the model alone is not enough**: it reads numbers, it does not compute them. Thresholds, gaps, a sensor that
stopped and a rough direction it finds reliably, with lines as evidence (tried with another application's logs, 24
October 2026). A mean over hundreds of values, a slope, a standard deviation or "less power at the same flow" it
estimates - wrong without showing it; above about 60,000 characters it sees a file only as an overview, and `verify`
checks the lines cited, not the arithmetic. Today exact numbers need the source to write daily values already (mean,
minimum, maximum per day) - then 31 days are a few hundred numbers, and the model compares them reliably.

**What exists**: the tenant's own sandbox (point 26, [architecture/code-execution.md](architecture/code-execution.md)) -
Python with pandas, no privileges, no network out, one session per answer; `CodeSessions` opens it with the question's
files in `data/`. A task's step offers only `search_file` and `read_file_lines` (`AgentStep`, `onlyTools`).

**What it needs** (rough, about 300-400 lines with specs, a minor version):
1. **Format**: `code: true` on an `agent` step (opt-in - more rounds, more cost, more time); the check refuses it
   when the tenant has no sandbox.
2. **Data in**: the run files of the step's inputs go into the session's `data/` as files (a CSV as it was read), so
   Python reads them whole instead of the model paging through lines.
3. **Evidence**: a finding must point at lines today, a value computed by Python is in none - Python's output is kept
   as a run file of the step (like a `prompt` step's answer), and a finding may cite its lines.
4. **Prompt**: compute with Python, read the numbers off its output, never estimate them; the session closed at the
   step's end.

**Open questions**:
- **Who writes the code**: the model in every run, as in the chat (recommended - the task stays text, the
  instructions say how to compute; the code may differ a little from run to run) or a script in the task's YAML
  (computes the same every time, but breaks the decision that the format stays data and never runs code).
- **The sandbox is shared with the chat**: a task's step takes an instance like a chat answer - when none is free,
  does the task wait, fail (and mail), or go on without Python?
- **History**: only what is in the files - several days' files with `days` and `onlyNew: false`; a task keeps no
  memory between runs (runs go after 14 days). Python could write a history of its own only into the store, which
  tasks cannot write to today.
- **Privacy**: the measurements also reach the sandbox (the tenant's, nothing kept after the session) -
  [privacy.md](privacy.md) and [SECURITY.md](../SECURITY.md).

**Test**: real readings if some are at hand, else an invented data set with a trend built in that
the task must find (a bearing 0.2 °C warmer per day, efficiency 1 % lower per week) and one it must not report
(a jump on a single day). Locally with the real sandbox (`./gradlew sandboxStart`) and Haiku 5.5, as the logs
of the log check were tested.

## 39 Several MCP keys per tenant

**Goal** (24 October 2026): a tenant has more than one MCP key - one per purpose or circle of people ("Entwicklung":
the company knowledge and the area with the how-tos; "Geschäftsleitung": with the calculation and the projects and the
web search; "Werkstatt": the company knowledge only). Until now there was one key per tenant, and its areas and web
search held for everyone who had it; whoever was given the key for Cowork found the same as whoever used it in Claude
Code. The admin and the tenant admins compose each key's rights and hand the key on deliberately.

**Weighed against** (24 October 2026): a key per user (his own rights through his groups - about 7-9 days, a card on
the account page, the WAM question, and in Claude's Team plan the owner's one header holds for everyone anyway) and a
key per user group (the group's rights - about 5-6 days, coupled to the groups: a person in two groups needs two
keys). Several keys composed by the admin follow what the client software's API keys already do (a name, areas per
key) - no new concept, the smallest step. Rights per person stay for OAuth (point 37, "Later").

**Decided** (24 October 2026):
- Per key: **a name, its knowledge areas and the web search**. The connected software's reading tools opened to the
  MCP server and the tasks opened to it stay the tenant's, for all its MCP keys - per key later when a case asks.
- **The key a tenant has keeps working** after the update: it becomes the first key of the list, named "Standard",
  with the tenant's areas and web search - nothing to do by hand.
- **The usage stays the tenant's** as today - not shown per key.
- The account page keeps showing the command with `<MCP-KEY>`: which key a person gets is the admin's decision.

**Limits, said plainly**: a key is a shared secret - whoever leaves keeps it until it is renewed; no rights per
person; in Claude's Team plan every member sees every connector the owner adds.

## 40 Writing back from Cowork

**Goal** (24 October 2026): Claude in Cowork (or another MCP client) files what it worked out into the company
knowledge - a project summary, the minutes of a site visit, a how-to - so the staff find it in the chat and the next
question over MCP finds it too. Until now the knowledge grows only by uploads, ZIP imports and the client software's
pushes. Named in point 37 under "Later"; not asked for by a customer yet - the sources of point 8 come first (the NAS read as a
file store; 37's script dropped 27 October 2026): there the originals already lie on the NAS.

**Proposal** (not agreed yet):
- **A tool `save_document(name, content, area?)`** on the MCP server: **text only** (Markdown, plain text), stored as
  `.md` and embedded in the background like an upload - through the client software's existing way in
  (`DocumentService.push`, which already checks the area against the key's scope and replaces by `externalId`).
  Binary files (DOCX, PDF) as base64 stay out: the client's model would write them out character by character - files
  come through a source of point 8.
- **The same name replaces** the key's earlier document (`externalId` "mcp:<key id>:<name>") instead of piling up
  copies; another key's document of the same name is another document.
- **Where it goes**: the company knowledge, or one of the key's own areas by name - never an area the key does not
  find (400, as for the client software's key).
- **A switch per MCP key** "darf ins Firmenwissen schreiben" (`tenant_mcp_key.writes`, off by default, the key card's
  table and create form like the web search) - without it the tool answers with a tool error naming the switch.
- **Its origin shown**: a new `DocumentOrigin.MCP` with the key's name, visible in the list "Firmenwissen" (a tenant
  migration for the new value), so a tenant admin sees what came from Cowork and can delete it.
- The tool is marked as writing (not read-only, destructive for a replacement): Cowork and Claude Code ask before each
  call.
- Limits: a size limit for the text (e.g. 1 MB), a name rule like the upload's.

**Why careful**:
- **Poisoning the knowledge**: an agent that may write can file wrong content, or content a prompt injection slipped
  in - and then everyone finds it in the chat. Hence the switch per key, off by default, the origin shown, and a line in
  SECURITY.md.
- **AI text becomes a source**: a summary may hold mistakes that are later quoted as facts. Fits point 36 (binding
  and supplementary documents): what comes from Cowork could count as supplementary by itself.

**Size**: about 3-4 days - the tool on the push way (1), the switch per key with migration, API and the card's column
(1), the origin with the tenant migration and the document list (0.5), specs - rights, areas, replacing, switch off,
another tenant (1), smoke test, mcp.md, SECURITY.md, privacy.md, CHANGELOG (0.5).

**Open questions**: whether what comes from Cowork is supplementary by itself (point 36); whether a key may also
delete its own documents (`delete_document` by name); the size limit; whether the tenant admin is told (the daily
report) of documents written over MCP.

## 41 Connected software through to MCP clients (proxy)

**Goal** (24 October 2026): the tenant's MCP server passes the connected software on to MCP clients - Claude Code,
Cowork, claude.ai - so the client's model calls it **itself**, without a second model behind `ask_assistant`. The
case: **a software that only Zirmo reaches** (an internal service, a module in a pod of its own, its key held by Zirmo
alone - internal network, a key per tenant), and the tenant's own internal software. Zirmo is the one gate to them:
the rights come from the MCP key, the module's address and token never leave it. A developer's key gets software A,
another key software B.

**Without code, today**: such a module already works as connected software - its own pod, its host in
`APP_AI_PLAIN_HTTP_HOSTS`, the admin enters it at the tenant (page "Software", the tenant's token stored encrypted,
shown only as a hint), its reading tools turned on and opened to the MCP server; Cowork and Claude Code then reach it
through `ask_assistant` - answers only. **The key per tenant is the module's gate**: once the host is allowed any
tenant admin could enter the address, but without a valid token the module does not answer. A plugin system with code
loaded into the app was weighed and dropped: a plugin in the app's process sees every tenant's data and the secrets, a
module's code and knowledge would lie on the operator's server, and loading code at runtime does not suit the AOT jar -
the plugins are MCP servers, Zirmo keeps the rules.

**Decided** (24 October 2026):
- **Several connections per MCP key, chosen like the areas**: a multi-select "Software" in the MCP key card's table
  and create form (`tenant_mcp_key_connection (mcp_key_id, connection_id)`, `connectionIds` in `McpKeyRequest` /
  `McpKeyResponse`, left out on a change = unchanged, another tenant's connection 400). Every connection of the tenant
  can be chosen.
- **The key's choice replaces the connection's switch "für den MCP-Server"** (`mcp_connection.mcp_server_reads` is
  dropped): one place says what a key may, no gate at the connection that could close what a key was given. What
  remains at the connection is a property of the module, not a right: "darf direkt durchgereicht werden". The client
  software's switch at the connection ("für die angebundene Software") stays as it is - a choice per API key would be
  consistent, but no case asks for it.
- **The key's choice holds for both ways**: the tools passed through **and** `ask_assistant` - a key never reaches a
  software through `ask_assistant` that it was not given (`McpTools.forMcpServer` takes the key's connections instead of `mcp_server_reads`). The key is the one
  place that says what it may: areas, software, web search.
- **Passing through is switched per connection** (`mcp_connection.passes_through`, off by default; in the UI
  "Direkt an MCP-Clients (Claude Code, Cowork)" next to "für die angebundene Software", the hint: off - Claude gets
  the assistant's answers only; on - Claude calls the tools itself and sees their results; decided 24 October 2026
  over "Für MCP-Proxy freigeben": the switch says what the module allows, not a right, and "Proxy" appears nowhere
  else in the UI): a software whose raw results must stay inside stays
  answer-only - a key with it uses it through `ask_assistant` only; switched on, a key with it gets its tools directly
  and through `ask_assistant`. Passed through, the client's model sees the raw results and can keep them.
- **Updating needs nothing by hand**: the migration gives every existing MCP key every connection opened to the MCP
  server today (`mcp_server_reads`), then drops that column - so it works as before. The page "Software" loses the
  switch "für den MCP-Server" and gains "Direkt an MCP-Clients".
- **Reading tools only** - changing ones are passed neither through nor to `ask_assistant`, as today.
- **What is passed through**:
  - **tools**: in `tools/list` the reading tools of the key's connections that pass through, named with the
    connection as prefix (`McpTools.uniqueName`, at most 64 characters), their description and input schema as the
    software gives them (read from `mcp_tool`, no call to the module); `tools/call` of such a name answered by the
    server itself - through `McpClient` to the module with its token, the result's text and its files as MCP resources
    (`AssistantMcpTools.withFiles`), the limits of today (size, timeout `app.ai.timeout`);
  - **instructions**: the software's `instructions` from its `initialize` read and stored with its tools and prompts;
    added to the MCP server's own instructions for the key's connections that pass through ("Software <name>: ..."),
    within Claude Code's 4,096 characters; and given to our own assistant's system prompt wherever the software is
    offered (chat, `ask_assistant`) - the chat gains from it too;
  - **prompts**: the turned-on prompts of the key's connections that pass through added to `prompts/list` with the
    prefix, `prompts/get` handed to the software (`SoftwarePrompts`) - slash commands in Claude Code, prompts in
    Cowork.
- **Where**: `BrandedMcpServer` already answers per tenant (`initialize`, `tools/list`, `prompts/*`); it adds the
  passed-through tools, instructions and prompts and answers their calls itself - the SDK never sees them. One new
  class `SoftwareMcpProxy` (the key's tools and their call). No pool of sessions to a module: one MCP session per call
  as today - built only if a measurement shows the extra round trip matters.

**Checked against the code** (24 October 2026, before building - what the decisions above need in detail):
- **The key reaches the tools**: `Toolbox.softwareToolsFor` asks `McpTools.forMcpServer(tenantId)` from a
  `ToolContext` that knows no key. `ToolContext` gets the asking key's id (`prepareOnce` has it), `forMcpServer(keyId)`
  reads `tenant_mcp_key_connection` - the one place both ways go through.
- **A passed-through call does not go through `McpTools.execute`**: that stores returned files with `ChatFileService`
  for a conversation (`keep`, needs a `ToolContext`). The proxy calls `McpClient.callTool` itself and turns
  `ReturnedFile`s into `AssistantMcpTools.withFiles`'s `DeliveredFile`s - text files as text, the rest as base64, the
  8 MB limit of all files together, a file beyond it named in the text (one adapter, the same rules as `ask_assistant`'s
  files). The result's text is cut as today (`McpClient.MAX_RESULT_TEXT`); an `isError` of the module stays an
  `isError` for the client.
- **Off the event loop**: `BrandedMcpServer` answers its own requests with `Mono.fromCallable(...)
  .subscribeOn(boundedElastic)` (the prompts do); the proxy's `tools/call` - JDBC, then HTTP to the module for up to
  `app.ai.timeout` - the same way.
- **Names**: `McpTools.uniqueName` (`<connection>__<tool>`, a `_2` where two collide) is computed anew at every
  `tools/list` and `tools/call` from the key's connections ordered by name - deterministic, so list and call agree; a
  connection renamed or added in between can shift a suffixed name, then the call answers "unknown tool" (harmless, the
  client lists again). Our own tools never contain `__`, a passed-through name always does - no collision.
  **Passed-through names are at most 32 characters** (decided 24 October 2026; the chat keeps 64): Claude Code shows
  the model `mcp__<server>__<tool>`, the Anthropic API takes tool names of at most 64 characters (`FakeAiModels`
  checks the same for our tools), and the server name has up to 40 - with 32 a passed-through name stays within 64
  for a server name of up to 25 characters; a longer brand is cut by the client, to be seen in the first try. `uniqueName` takes the limit as a parameter.
- **No schema check by the SDK**: the SDK validates arguments only for its registered tools; a passed-through call's
  arguments go to the module as the client sent them - the module checks them, as it does for the chat.
- **No `tools/list_changed`**: `/mcp` is stateless (no event stream), so a client learns of a key's changed software
  only at its next `tools/list` - Claude Code at the start of a session, Cowork when it connects again. Said in mcp.md.
- **Instructions**: a column `mcp_connection.instructions` (nullable), read from `InitializeResult.instructions()` in
  `McpClient`'s session (today thrown away) when the tools are read, kept with them; **a cap of 2,000 characters per
  connection** (decided 24 October 2026; longer ones cut when read, the page "Software" says so) so the MCP server's own instructions stay within Claude Code's 4,096 together with a key's
  connections (ours first, then the connections in name order); the chat's system prompt (`ChatService.instructions`,
  today only "has software tools") gets the offered connections' instructions with the same cap.
- **Prompts for a key**: `SoftwarePrompts.fill` checks a **user's** level (`hasLevel(userId, ...)`); the proxy's
  `prompts/get` needs the same by key - a prompt enabled and offered, of one of the key's connections that pass
  through - without a user (a second method, the rest shared).
- **API and frontend of the switch**: `McpConnectionResponse.mcpServerAccess` and `PUT .../mcp-connections/{id}/mcp-server`
  go; `passesThrough` in the response and `PUT .../mcp-connections/{id}/pass-through` `{passesThrough}` come, like
  `/client-software`; the "Software" page and the admin's dialog show the new switch in place of the old; the MCP key
  card's "Software" multi-select reads the tenant's connections (`store/mcp-connection`, loaded for the scope like the
  areas); the key slice drops a deleted connection from every key (as it drops a deleted area). `TenantApiKeySpec` and
  `TenantAdminSpec` route tables follow.
- **Migration `V13`** (`V12` is the MCP keys'): `tenant_mcp_key_connection (mcp_key_id, connection_id)` with
  `ON DELETE CASCADE` on both, filled from `mcp_server_reads` for every key of the tenant, then the column dropped;
  `mcp_connection.passes_through BOOLEAN NOT NULL DEFAULT FALSE`, `mcp_connection.instructions TEXT`.
- **A module that asks Zirmo back** (mcp.md "A connected software that asks Zirmo back") gets an MCP key
  with no software - with this point that is the one rule against the loop.
- **Built in three packages**, each green on its own: A - the choice per key for both ways, the migration, the
  switch per connection (replaces the old one), `ask_assistant` through the key; B - tools passed through; C -
  instructions and prompts.

**Not built now, on purpose**: resources (`resources/list`, `resources/read` - raw content, own rules; when a
module offers some); a catalog of the connected software at the admin (a module is entered per tenant as connected
software); usage per call of a connected software (only the AI providers are counted); changing tools passed through (Cowork would ask before each call -
later: point 68); elicitation (point 30).

**Size** (about 5 days): tools passed through with the choice per key for both ways and the switch per connection,
migration (existing keys get every opened connection, `mcp_server_reads` dropped; the switch "für den MCP-Server" gone
from the page "Software") - about 3 days; instructions (stored, to MCP clients, into our
system prompt) - about 1 day; prompts to MCP clients - about 1 day. Specs with `FakeMcpSoftware` (a key sees only its
software, both ways; a connection that does not pass through is answer-only; another tenant's connection 400; names
with prefix; files as resources; instructions and prompts per key), the smoke test (a passed-through tool called through
the AOT jar), mcp.md, SECURITY.md (a module's instructions and descriptions are text of another server read by the
model; the raw results leave with passing through), privacy.md (what a module's results send to the client),
CHANGELOG.

**Decided on the open points** (24 October 2026): the switch is "Direkt an MCP-Clients"; the MCP key card's
"Software" multi-select shows **every** connection of the tenant (one not passing through works through `ask_assistant`
only - the option says so), since the choice governs `ask_assistant` as well; passed-through names at most 32
characters; the instructions capped at 2,000 per connection. Still open, but not before building: the tasks opened to
the MCP server (`task.open_to_mcp`) stay per task for all keys - per key when a case asks. **Built 25 October 2026**
(asked for after the review): the tasks are chosen per MCP key (`tenant_mcp_key_task`, `taskIds`, the column "Aufgaben"
on the key card), `open_to_mcp` dropped by `V12` (every key keeps the tasks opened so far), the task editor names the
keys that may start a task.


**Built** (24 October 2026, 1.8.0, packages A, B and C at once, `V12` extended - no version of its own) - the rules
are in [architecture/mcp.md](architecture/mcp.md) "Connected software through to MCP clients"; Changed while
building: a name
the key does not have is a tool error, not a JSON-RPC error (Micronaut MCP's transport sends those with HTTP 400, and
the MCP SDK's client ends the session on it); the instructions are read with the tools ("Werkzeuge neu einlesen"), a
new address drops them.

## 42 The connected software chosen per API key

**Goal** (25 October 2026): a tenant has several API keys for its client software ("ERP Produktion", "Shop", "Test"),
each with its own name, history, web search, knowledge areas and tasks - but the connected software `/api/v1/chat` may
use is one switch per connection for **all** of them (row "API-Keys" on the page "Software", `PUT
.../mcp-connections/{id}/client-software`, `mcp_connection.client_software_reads`, `McpTools.forClientSoftware`). A
tenant with two connected programs (accounting, stock) cannot give the accounting only to the ERP's key. Since point 41
the MCP keys choose their software each; the API keys should do the same, so both kinds of key work alike.

**Proposal** (agreed 25 October 2026):
- `tenant_api_key_connection (api_key_id, connection_id)`, `ON DELETE CASCADE` on both, like `tenant_mcp_key_connection`;
  `connectionIds` on the API key's request and response (left out on create = none, on a change = unchanged; another
  tenant's connection 400), a multi-select "Software" on the API key card (shown when the tenant has connections).
- `McpTools.forClientSoftware(tenantId, apiKeyId)` takes the key's connections - still only reading tools, still only
  through our assistant (nothing passed through to the client software: it gets the answer, not the tools).
- The migration gives every existing API key the connections opened to the client software today, then drops
  `client_software_reads`; the row "API-Keys" leaves the page "Software", `PUT .../client-software` is gone. Nothing
  changes for an installation.
- A module asking Alpdesk AI back with an API key (mcp.md "A connected software that asks Alpdesk AI back") is then
  kept from the loop by giving its key no software, as with the MCP keys.

**Decided** (25 October 2026, ready to build after the review of point 41):
- `V12` is extended (1.8.0 is not released) - no migration of its own.
- `/api/v1/me` does **not** name the key's software: the client software never calls the tools itself, our assistant
  does - a list it cannot use would be a lasting promise of the public API (adding it later is a minor version,
  removing it a major one).
- CHANGELOG 1.8.0, under `### Changed` next to the MCP keys' entry: "**API keys choose their software too**: which
  connected software the client software's API key may use through the chat is chosen per key on the API key card
  ("Software"), no longer per connection for all keys - the row "API-Keys" on the page "Software" is gone. After the
  update every key has the software opened to it so far. Admin API: `PUT .../mcp-connections/{id}/client-software` is
  removed, `connectionIds` on the API keys." - 1.8.0 stays: `/api/v1` does not change, the migration keeps the state.
- Like the MCP keys: a new key gets no software until given some; the multi-select shows only when the tenant has
  connections; deleting a connection takes it off every key.
- The tasks need nothing here: the API keys choose theirs already (`taskIds`), the MCP keys since 25 October 2026 (point
  41) - after this point every right of a key (areas, web search, software, tasks) is chosen at the key.

**Size** (about 1 day): migration, `ApiKeyService`, `McpTools`, `Toolbox` (the asking key's id in the `ToolContext`,
as for the MCP keys), the API key card, specs (a key sees only its software, another key and another tenant nothing,
another tenant's connection 400, deleting a connection takes it off every key), `TenantApiKeySpec`, the smoke test's
`/api/v1/chat` step with the key's software, mcp.md, tenancy.md, SECURITY.md, CHANGELOG.

**Built** (25 October 2026, 1.8.0, `V12` extended) as agreed - the rules are in [architecture/mcp.md](architecture/mcp.md)
"MCP clients to the tenant's software". The software's choice moved with it: `McpTools.forApiKey`, the asking key's id
in the `ToolContext` (`keyId`, one field for the API key and the MCP key, by the asker); the API key card's column
"Software" shows every connection of the tenant. Specs: `McpConnectionSpec` (the choice per key, the chat with and
without the software, another key, another tenant, a deleted connection); the smoke test gives its API key the
software.

## 43 A user group's rights in one dialog

**Goal** (25 October 2026): a key's rights are now set in one dialog (areas, software, tasks, web search). A user
group's are spread over two pages: areas, web search, members and the WAM group on the page "Benutzer", the level per
connected software on the page "Software" (its access list, one row per group). The same dialog for a group would make
people and programs alike to keep: groups rule the people in the chat, keys the programs.

**Proposal** (to be agreed after the code review of 1.8.0):
- The group dialog gets, under its areas and web search, one row per connected software with the level "Kein Zugriff"
  / "Nur lesen" / "Lesen und ändern" - the backend has it already (`PUT .../mcp-connections/{id}/access/groups/{groupId}`).
- The page "Software" keeps its access list as an overview per connection (who may what), or shows it read-only with a
  link to the group - to be decided.
- The groups' table shows a group's rights in short, like the keys' (`key-rights`).
- Unchanged: what a group means (the union of its members' groups, changing tools only with "Lesen und ändern", the
  chat asking before every change), tasks stay with the keys and the tenant admin.

**To decide**: whether the access list on the page "Software" stays editable; whether the levels go out as one request
per connection (as today) or one for the group.

**Size** (about half a day, frontend only): the group dialog, its util with a spec, the texts, mcp.md and tenancy.md.

**Built** (25 October 2026, 1.8.0) - with the backend after all: the levels go in the group's own request
(`levels` on `.../groups`, one transaction with the group), so a new group with its levels is saved whole or not at
all - in the frontend alone a refused level after the group was created would have left half a group and a second
save refused as a name taken. The software's access list stays editable (the same data from the connection's side).
Specs: `UserGroupSpec` (create, change, left out, emptied, another tenant's software, a level without a value - nothing
kept), the frontend's `user-group.util.spec`; rules in [architecture/tenancy.md](architecture/tenancy.md) "User groups".

## 44 More file stores

**Order** (decided 27 October 2026): after point 8 - the sources read through `FileStoreSession` as the tasks do,
and listing folders comes with 8; a new type then serves
both.

**Goal** (split from point 28 on 27 October 2026): more types next to SFTP and FTPS - **S3** (S3-compatible storage;
an own endpoint checked against the internal addresses), **SharePoint** and **OneDrive** (both through Microsoft Graph -
one client, an app registration in the customer's Entra ID, `Sites.Selected` preferred; the largest of them). Dropbox is
no longer planned.

**The local folder dropped** (decided 29 October 2026): Alpdesk AI runs on a server, the tenants' files lie with the
tenants. A folder of the server helps only if the operator mounts every tenant's share into the pod by hand, which does
not scale over several tenants; a server or NAS of the customer is reached through SFTP or FTPS today. Taken up again
when Alpdesk AI runs at a customer's next to its files (point 37, "later, when a customer asks").

**S3 built** (29 October 2026, 1.9.0): the groundwork every type needs (settings per type, a check without a
fingerprint, the dialog's fields per type, migration V15) and S3 through the AWS SDK - [architecture/file-stores.md](architecture/file-stores.md).

**SharePoint / OneDrive built** (29 October 2026, 1.9.0): one type for both (a OneDrive by its site's address), the
app's client secret, Microsoft Graph without its SDK - a separate plan (kept outside the repository). Built
and specced against a fake of Microsoft; **open: the real try with a Microsoft 365 test tenant** (there is none yet) -
the point is not done before it (architecture/file-stores.md "Trying SharePoint by hand").

**Order** (decided 29 October 2026): S3 first - the groundwork every type needs comes with it, testable against a MinIO
of the specs without anything outside; Microsoft Graph after it, with a Microsoft 365 test tenant.

**What it needs**: the work lies in `services.filestore` only - settings per type instead of host, port, user and
fingerprint (`ServerSettings`), a client without `InetAddress`, a check without a fingerprint for the HTTPS types,
the dialog's fields per type, the allowed types in a migration. The tasks read through `FileStoreService.open` and a
`FileStoreSession` (`list`, `read` from an offset - every type reads from an offset) and ask
`FileStoreService.readable` - nothing there changes (point 28, package E part 2), nor in the knowledge sources.

**Rough effort** (27 October 2026, the local folder taken out 29 October 2026): groundwork 1.5-2 days, S3 about 2,
Microsoft Graph 3-4, docs half a day to a day. **Open**: OneDrive for Business only (a private OneDrive needs a
sign-in per person); S3 through the AWS SDK v2 or the MinIO client (both Apache-2.0 - the jar's growth checked first);
a real try of Graph needs a Microsoft 365 test tenant.

## 45 Lines of one file by the values found in another

**On hold** (decided 27 October 2026): one case only, the saving uncertain, the cost of that case acceptable - taken up
again when a second task of the kind "values found in file A, their lines in file B" comes along, with its own numbers.

**The idea**: a `read_files` step keeps only the lines containing a value an earlier step found - an order number of a
CSV export in the delivery notes, a serial number in the service records. Not possible with `include`: that is fixed
in the task's text, the values change with every run.

**The one case so far** (a log check, measured 27 October 2026 - a separate plan (kept outside the repository)
"Measured"): the failures are a few dozen lines, the file that tells whether they were repaired keeps about 8 300 lines
a day (four channels). The check step searches it in up to 7 rounds - 85-90% of a run's cost, and once in six runs it
missed the main error. A fixed `include` by channel would keep 1 200 lines but miss a repair on another channel - a
worse result, so no way out. **Why not now**: the lines are long (an XML answer each, cut at 2 000 characters), so even
50 kept lines may pass the 60 000 characters that go with a question whole - the step would still search, only less;
what it saves is known only after a measurement. With prompt caching a run of that task costs about $0.20-0.40 with
Claude Haiku 5.5, two runs a day about $12-25 a month.

**When taken up**: a proposal first (how the values are taken - a regex on the earlier step's lines; the most values
and lines; nothing found keeps nothing and says so; read positions; what the checking step is told about lines left
out), then the same A/B as on 27 October 2026 before it is kept.

## 46 Reranking, switchable

**Taken up** (29 October 2026): measured and dropped on 9 October 2026 - on the test corpus (39 chunks) hybrid 0.3 had
97 % of the answering passages among the first five, the one miss at rank 8, so a reranker could have gained one
question at most. The corpus is too small to show what reranking does on a large tenant's documents. Built as a switch,
off unless chosen, so the user can compare answers with and without it on real documents and keep it only where they
get better.

**How it works**: the embedding model (a bi-encoder) turns question and chunk into vectors separately - fast over every
chunk, but it never reads them together, so a chunk on the same subject scores like the one that answers. A reranker
(a cross-encoder) reads question and chunk as one pair and scores how well the chunk answers - more exact, but one pass
per pair, nothing computed in advance, so it runs over the candidates of the first search only. It finds nothing the
candidates lack; it only reorders them. What it may bring: the answering passage first among many similar chunks (the
"Kessel" / "NK" case of point 4), questions with a relation, a condition or a negation, and fewer results needed per
search (fewer tokens for the chat model).

**The idea** (to be worked out in the proposal):
1. **A new model kind `RERANK` in the catalog**, with its price; the admin assigns it to the tenants that may use it,
   the tenant (admin or tenant admin) chooses it - without a choice the search stays exactly as today.
2. **In `KnowledgeSearch`**: the hybrid search merges its candidates as today (20 per side, up to 40 chunks); with a
   reranker chosen they are scored against the question in one call and the best `maxResults` go to the model. The same
   everywhere the search runs: the chat, `/api/v1/chat`, the MCP server's `search_knowledge`, the tasks.
3. **A failing reranker** (key, timeout, server down) keeps the hybrid order with a warning in the log - the answer
   never fails for it.
4. **Usage**: every call recorded like an embedding's, with its key source.
5. **The candidates stay at 20 per side** for a start (the miss of 9 October 2026 was at rank 8): more cost time with
   every chunk and its length. Raised - then as a setting next to `app.search.text-weight` - only if a measurement on a
   large tenant shows the answering passage beyond rank 20.

**Where the reranker runs**: usually on an own server - one for every tenant, like the embedding server: it is
stateless (question and candidates in, scores out, nothing kept), and the tenants are kept apart before it (the
candidates come from the asker's tenant database and areas only). E.g. `bge-reranker-v2-m3` (multilingual, next to
`bge-m3`) behind Hugging Face TEI as a further service of `docker-compose.embeddings.yml` - Ollama does not rerank. No
price per search, only time (a few hundred ms with a GPU, a second or more on a CPU for 40 pairs - to be measured) and
1-2 GB of memory. One server for all means: searches at the same time wait for each other (TEI batches them; a second
instance if ever needed), and a server down hits every tenant - hence point 3. Alternatively a cloud reranker (Cohere,
Jina, Voyage - langchain4j modules): paid per search, and the candidate passages go to one more provider
(docs/privacy.md).

**Which reranker** (29 October 2026): the catalog holds the model, nothing in the code names one - it can be changed
later without building anything. A reranker reads text only (question and chunk), so it works with every embedding
model; one of the same family is convenient, not needed. `bge-m3` stays the embedding model: changing it means
embedding every tenant's documents again, and no measurement asks for it. The reranker is chosen by a measurement
(the same questions on the test corpus and on a large tenant's documents, with and without), not by benchmarks:
- `bge-reranker-v2-m3` (Apache-2.0, about 570 M parameters, trained from `bge-m3`, runs in TEI on a CPU) - the start;
- `Qwen3-Reranker-0.6B` (Apache-2.0, 2025, strong on multilingual benchmarks) - LLM-based, more likely behind vLLM than
  TEI (to be checked);
- not: Jina's open reranker weights (non-commercial licence). Newer models checked when the point is taken up.

**Decided** (29 October 2026, before building): an own server only (TEI's `/rerank`, Cohere or Jina later when someone
asks); chosen like the embedding model - the admin assigns, the tenant admin or the admin switches it on and off;
released as an image of its own like the embeddings image (`build-reranker.yml`, bge-reranker-v2-m3 baked in) -
dropped on 30 October 2026 for Hugging Face's official TEI image with compose files for CPU and GPU; only
bge-reranker-v2-m3 is measured. **Built** the same day (1.9.0) - [architecture/ai-features.md](architecture/ai-features.md)
"Reranking".

**Open for the proposal** (as asked on 29 October 2026): own server only or a cloud provider too (and whether one langchain4j module with its own
address covers both - TEI, Infinity and vLLM speak a Cohere- or Jina-like `/rerank`); who chooses it (admin only or the
tenant admin); a measurement variant in `RetrievalEvaluationSpec` and the comparison on a real tenant's documents; jar
size and licenses of the module (THIRD-PARTY.md). A minor version (a feature).

## 47 Contextual retrieval with a model

**On hold** (decided 29 October 2026): weighed with a list of retrieval techniques on 29 October 2026 - the others are
built or solved otherwise (reciprocal rank fusion: point 4; the question rewritten for the search: the chat model
writes `similarity_search`'s query itself from the history and may search again with other words; small-to-big:
`read_document` reads on around a passage; a prompt built static first: system prompt and tools before history and
question, nothing changing in the system prompt). This one is the only real gap, and only a measurement can tell
whether it is worth its cost.

**The idea**: a chunk alone often says too little to be found - "Aufpreis: 15 € pro Stück" or "Schritt 4: Ventil
schließen" does not say what it belongs to. Before embedding, a chat model reads the document and writes each chunk a
short sentence of where it stands ("Aus der Betriebsanleitung Heizung NK 24, Abschnitt Wartung, jährliche Prüfung"),
which is embedded and searched with the chunk (vector and full text). Known as "contextual retrieval"; it combines with
the reranker of point 46.

**What we have today** (point 4, step 2): every chunk starts with a header of the document's file name (with its path
in a knowledge source) and its title - no model call. Measured on the test corpus (hybrid 0.3): among the first five
94 % to 97 %, table rows 60 % to 100 %. Headings inside a document are not used: Tika's plain text does not mark them,
in PDFs not at all - so a chunk deep in a long document knows its document, not its section. That is the gap a model
would close.

**Why not now**:
- **The cost**: one model call per chunk (the document as context, the chunk as question) at every upload, every
  knowledge source's sync, every push of the client software and every "Alle Dokumente neu einbetten" - for a large
  tenant (tens of thousands of chunks) a real amount. Prompt caching (point 15) makes the repeated document cheaper,
  not free. The time to embed grows the same way.
- **No measurement can show it yet**: the test corpus (39 chunks) has 97 % among the first five with today's header.
  A gain would show only on a large tenant's documents - the same measurement point 46 needs.
- **Privacy**: every document goes to the chat model's provider once more when it is embedded (docs/privacy.md) -
  with a cloud model a change of what is sent.

**A cheaper step first** (to be weighed in the proposal): the section heading of each chunk from the formats that mark
headings (DOCX, ODT, Markdown, HTML, PPTX slide titles) - no model call, read from Tika's XHTML instead of its plain
text. It leaves PDFs and scans as they are.

**When taken up**: with point 46's measurement on a large tenant's documents - today's header, the cheaper step and
the model's context side by side, with and without the reranker. Built only if the model's context finds clearly more
answering passages than the cheaper step. Then a proposal first: which model (the catalog's, assigned like the image
model; a small one is enough), recorded as usage per document, what a failing call does (the chunk keeps today's
header, the document still becomes READY), the switch per tenant (off unless chosen) and the re-embedding it needs. A
minor version (a feature).

**Together with point 49** (2 November 2026): point 49 has a model write a description and a summary per document
when it is embedded - the same kind of step as this one,
once per document instead of once per chunk. Its description in every chunk's header is this point's cheap variant
and is measured as a variant of point 49's step 1 (decided 2 November 2026). This point's call per chunk is taken up
only if that variant falls short.

## 48 Tool retrieval

**On hold** (decided 29 October 2026): no case today - weighed together with point 47.

**The idea**: every answer sends the model the description and input schema of every tool it may use. With ten tools
that does not matter; with a connected software of 100 or more (a large ERP) two things go wrong:
- **Tokens**: a tool costs a few hundred tokens, in every round of the agent loop - 200 tools are easily 30,000-60,000
  tokens before the question.
- **Wrong choices**: the more similar tools (`rechnung_suchen`, `rechnung_positionen`, `auftrag_status`), the more often
  a model takes the wrong one - small and local models far more than large ones. A model with a small context (32k)
  also has little room left for history and documents.

Tool retrieval does what the document search does: the tools' descriptions are embedded once (when a connection's tools
are read), the question is embedded, and only the 10-20 closest tools are offered.

**An example** (invented): the Musterfirma GmbH connects its ERP with 120 tools. "Ist Rechnung 4711 schon bezahlt?"
finds `rechnung_suchen`, `rechnung_positionen`, `rechnung_pdf`, `zahlungen_suchen`, `auftrag_status` - five tools,
about 1,500 tokens instead of 40,000, and hardly a wrong choice left. **The risk on the same example**: "Hat die
Beispiel AG schon überwiesen?" names no invoice; the search may bring `kunde_suchen` and `kunde_adressen` but not
`zahlungen_suchen` - the model cannot answer although the tool is there. A question needing several tools that sound
different ("the customer, then its open invoices, then the stock of the articles") is rarely covered by one search
either.

**What we have today**: a choice by hand instead of per question (`McpTools`) - the tenant admin turns every tool of a
connection on or off, a user group's level offers the reading tools or the changing ones too, API keys and MCP keys get
only the reading tools of their connections. With prompt caching (point 15) the unchanged tool schemas cost a fraction
from the second round on. Limits: at most 500 tools per connection.

**Cost or quality**: both, but quality only under conditions. It gains with very many tools (from about 30-50 the
choices get noticeably worse), with a small or own model (7B-14B on vLLM call wrong tools, make up arguments or call
none), with a small context. It loses where the search misses the right tool or a question needs several. With large
cloud models and the tools the tenant admin keeps to what is needed, the gain is small and the risk larger.

**When taken up**: when both come together - a tenant answering with a small own model and a connected software with
many tools turned on - or when a tenant's answers get slow and expensive by their tools. Then a proposal first: a
switch per connection (off unless chosen), how many tools are offered, a tool `find_tools` so the model can look for
more when the first choice lacks one, the embedding with the tenant's embedding model (recorded as usage, once per
reload of the tools plus once per question - not the document search's, which embeds the model's query, not the question), and a
measurement - the same questions with and without, counted how often the right tool was called. A minor version (a
feature).

## 49 Knowledge compiled by a model ("LLM wiki")

**Proposal** (2 November 2026, its questions answered the same day): a separate plan (kept outside the repository). An "LLM
wiki" has a model read every source once and keep pages per subject, instead of searching raw chunks at every question.
Not instead of the search - the areas, deleting and replacing and the risk of statements made up at ingest speak
against it - but as a layer above it, in two steps: first a summary per document (written once per version, embedded as
one more chunk) and a tool listing what the asker may find, each area apart; pages per subject and a contradiction
report only after step 1 is measured worth it. Related to point 47: the summary's description could serve as the
chunks' context with one call per document instead of one per chunk.

## 50 Documents into a table in a task

**Built** 4/5 November 2026 (2.0.0), all five packages: a separate plan (kept outside the repository)
(the plan, its decisions and where the build differs), [architecture/tasks.md](architecture/tasks.md),
[architecture/file-stores.md](architecture/file-stores.md). Sample documents and two tasks to try it:
`devtools/task-documents/`.

**Goal** (4 November 2026): a task reads the documents a company drops into a folder of a file store - PDFs, scanned
pages, photos - takes the same fields out of each (date, number, partner, amounts, an account) and appends them as rows
to a CSV in a store, one file through the year. First case: invoices received and invoices sent, two tasks; at the
end of the year the CSV is imported into the bookkeeping software. Nothing in the steps knows invoices - delivery
notes, test reports or forms work the same way.

**Why not with what exists**: `read_files` reads text lines (log files), no PDF or scan; `agent` and `prompt` answer
with findings or free text, not with rows of fixed fields; a file store is only read. Today it works only from outside,
through `/api/v1` (`POST /api/v1/chat/files` and `/api/v1/chat` with a template, an own script writing the CSV) - a
program the tenant would have to run.

**Three new steps** (the format stays data, nothing runs code):

```yaml
version: 1
name: Invoices received
notify: [accounting@example.com]
steps:
  - id: documents
    type: read_documents
    store: Accounting
    folder: Inbox
    pattern: "*"                  # the agreed types, recognised by content
  - id: rows
    type: extract
    model: Claude Sonnet
    input: documents              # one document at a time, one row each
    instructions: |
      Account by the company's chart of accounts: software subscriptions 4806, ...
    fields:
      - {name: date, type: date, required: true}
      - {name: number, type: text}
      - {name: partner, type: text, required: true}
      - {name: net, type: amount}
      - {name: vat_rate, type: percent}
      - {name: gross, type: amount, required: true}
      - {name: account, type: text}
  - id: csv
    type: write_file
    input: rows
    store: Accounting
    file: "Export/invoices-received-{yyyy}.csv"
    mode: append                  # the header only when the file is new
  - id: mail
    type: send_mail
    if: rows.unclear > 0          # documents with a required field missing
```

1. **`read_documents`**: lists the folder like `read_files` (pattern, `onlyNew`, `firstRun`) and reads each new
   document through what the knowledge already uses - Tika for the agreed types, the catalog's image model for images
   and scanned pages (`DocumentTextExtractor`, `ScanReader`). A document is remembered by path and checksum, not by a
   byte offset: read once, read again only when it changed. Its text is a run file like a file read. Counts: `files`.
2. **`extract`**: the model gets one document at a time and answers with the fields of the step - a structured answer
   like the `agent` step's, checked by the engine: each field's type (date, amount, percent, text), `required` ones
   present. A document whose answer fails the check gets one more round, then its row is written with a column
   `unclear` set and counts as `unclear`. Every row carries the column `source` (path and checksum). Counts: `rows`,
   `unclear`. The run view shows the rows.
3. **`write_file`**: writes the rows of an `extract` step as CSV (`;` or `,`, UTF-8, the header from the fields) into a
   file of a store - `append` adds to it, `replace` writes it anew; `{yyyy}`, `{MM}`, `{dd}` in the name. Rows whose
   `source` is already in the file are skipped, so a run that broke after writing and before its read positions were
   saved writes nothing twice. Counts: `rows` (written).

**File stores learn to write** (SFTP, FTPS, S3, SharePoint / OneDrive): a switch per store "tasks may write here",
off by default and only for the tenant admin and the admin; the check refuses `write_file` to a store without it. A
task writes only the files named in its text, below the store's folder - it never deletes, renames or overwrites
another. Written as a temporary file renamed into place (S3 and Graph: the whole file uploaded again - there is no
append), so a reader never sees half a file. Two tasks writing the same file are refused by the check (a lost update
on S3 and Graph otherwise).

**Open questions** (for the proposal):
- `extract` searching the company knowledge (the tenant's accounting rules as documents) - or the rules only in
  `instructions` (4 000 characters) for a start? Recommended: `instructions` first.
- Many documents in one run (a folder filled once with a year's invoices): a limit per run, the rest in the next one?
- The CSV's dialect per task (separator, decimal comma, date format) - the bookkeeping software's import decides.
- A cross-check of amounts (net + VAT = gross) would be trade-specific - left to the model's instructions, not the
  engine.
- Documents and their text reach the model - [privacy.md](privacy.md); tasks now write into a tenant's store -
  [SECURITY.md](../SECURITY.md).

**Package 3 - sending the rows to a connected software** (`call_tool`, about 2-3 days, decided 4 November 2026):
rather than an HTTP step with a URL and a token, a step calls a tool of a connected software (MCP, point 2) per row -
the connection, its token, internal hosts and the tool's schema already exist, the arguments are checked against the
schema when the task is checked. The tenant admin naming the tool in the task's text is the approval. Recognising a
repeat is the receiving software's matter: every call carries `source` and the document's own number. Built once a
real MCP server of the receiving software (or an adapter in front of its API) exists to try it against.

**Package 4 - `call_tool` as a source** (about 2 days, decided 4 November 2026): the same step without `input` calls a
reading tool of the connected software as the first step - the company's own invoicing software through a small MCP
server in front of its API. Rows as JSON (with `fields` and a `key`: no model, exact values) go to `write_file` or a
target tool; files go to `extract` like documents of a store. `{lastRun}` in the arguments is the tool's "only new".

**Package 5 - a mailbox as a file store** (about 2-3 days, decided 4 November 2026): documents mailed to an address of
the company's own are read like files of a store - a fifth, read-only store type (IMAP, Angus Mail is in the jar):
every message a folder named by date and UID, its attachments the files, its text `mail.txt`; nothing in the mailbox
is changed; the last `days` listed. `read_documents` and the knowledge sources read it unchanged - most of point 9.

## 51 General chat over topic tenants

The full plan, agreed 5 November 2026 with every decision: a separate plan (kept outside the repository).

**Goal** (5 November 2026): a company runs several topic tenants (topic A, B, C - each with its own chat, company
knowledge, assistant and admins). Most people stay in their topic's chat; who needs another topic's knowledge switches
to a general chat that asks the chosen topics. The topic chats stay separate on purpose: inside a topic a user has
the full rights of their groups, the general chat sees each topic only as far as that topic released it. Who gets in
is decided in the company's IDM: the WAM proxy sends the person's IDM groups, and a person put into a group gets
access without anyone clicking in Alpdesk AI.

**What exists already**: the general chat is a tenant of its own whose connected software (point 2) are the topics'
MCP servers; the groups' level per connection (point 43) decides who reaches which topic; a key given to another
tenant gets no software (point 41), so nothing loops; the reranker (point 46) works at each topic.

**Packages** (about 7-9 days in all, in this order):
0. **One person, several tenants** (2-3 days): a username unique per tenant instead of per installation; with WAM
   the sign-in offers the person's tenants to choose and the user menu switches between them (a new token each, the
   tenant isolation untouched); without WAM `/login/<key>` picks the tenant and the switch asks the target account's
   password (a username proves no person there - two accounts, two passwords, the same one if the person wants);
   the admin adds a known person's user to another tenant. A second installation next to
   the same PostgreSQL was the first idea and dropped the same day: doubled operation for good to get around one
   index.
1. **A description per MCP key** (1 day): what the key's access knows, written by the tenant admin at the key (500
   characters), served in `initialize` and the descriptions of `search_knowledge` and `ask_assistant` - so a client's
   model tells three topics apart (today every tenant's server describes itself alike).
2. **Topics deselectable per question** (2 days): chips above the chat's input, one per connected software the user
   reaches; the list only narrows what the groups allow (an id not reached 400), kept with the conversation and
   returned with it, the same on `/api/v1/chat` with `GET /api/v1/software` to choose from - a frontend of a
   tenant's own gets what ours does.
3. **Members from the IDM** (1-2 days): one mode, no switch - a user group with a WAM group takes its members from
   the IDM alone (a change for groups that have one today: "assigned and in the IDM" becomes "in the IDM"), and a
   person unknown to a tenant gets its user there at once when a group of it names an IDM group of the header;
   fail closed as today; no migration.
4. **A guide "A general chat over several tenants"** (half a day) in the public README, after a test on a stack of
   our own behind a real auth proxy with groups (Authelia, file-based users), measured with a real model; two small follow-ups go with package 2: the tool result
   naming its topic (document ids hold only within a topic) and the system prompt as an orchestrator.
   Also in it, nothing new: the general chat's own MCP server passes the topics' reading tools through to Claude
   Code and Cowork (point 41's proxy, `thema_a__search_knowledge`) - one address and key for all topics.

**Not part of it**: reranking the topics' hits together; the asker's identity passed to a topic; A2A (52); users and
groups through `/api/v1` or SCIM; an LDAP client of our own; a second installation.

**Migrations**: one per set for the whole of 2.0.0 (`V17__release_2_0_0.sql`, tenant `V8__release_2_0_0.sql` -
the point-50 migrations folded in, decided 5 November 2026: applied nowhere yet); the PR check compares migrations
with `main` (the released state) from now on, so an unreleased version's migrations may be folded on `dev`.

## 52 A2A (Agent2Agent)

**Goal** (5 November 2026): A2A is an open protocol between agents (MCP connects an agent to tools and data, A2A to
another agent): an agent card describes what an agent can do, a task has a state and may run long, the agent may ask
back ("input-required"), results come as artifacts. A tenant's assistant offered as an A2A agent would let a
company's own agent platform orchestrate Alpdesk AI's tenants.

**Idea** (no proposal yet): a second way into the core `ask_assistant` uses (the chat's agent loop) - a route per
tenant next to `/mcp` with `message/send` and `message/stream` (our server-sent events), the MCP keys as its keys (they
carry areas, software, tasks and web search already), a question and its answer as a completed task first. Later
long tasks (the scheduled tasks' runs), asking back (the changing tool calls waiting for a decision) and the other
direction (a tenant using foreign A2A agents like connected software, perhaps through langchain4j's A2A module).

**Why not now**: inside one company it adds nothing over the MCP server (point 51). The official Java SDK has server
modules for Quarkus and Jakarta, none for Micronaut (a controller of our own on the SDK's types, or the JSON-RPC
ourselves); the specification moved fast in 2025 - its state to be checked first. Taken up when a customer's A2A
platform is to orchestrate and a real client exists to try against. Its agent card per tenant (`/.well-known/` is
per host) and whether it is anonymous are decisions for the proposal ([SECURITY.md](../SECURITY.md)).

## 56 A second factor for the password login

**Goal** (8 November 2026): every installation without a WAM proxy protects a company's knowledge with one password
per user. A second factor closes the one gap with real damage today. Agreed after comparing Alpdesk AI with a hosted
assistant - of its list only three points stood the test "a real case today, damage
without it" (56, 57, 58); the product itself stays as it is.

**Idea** (proposal to write): TOTP (RFC 6238) with an authenticator app, no SMS. A tenant user sets it up in "Konto"
(secret shown as a QR code and as text, confirmed with a first code, recovery codes shown once), `/login` answers a
second step when the account has a factor (the password checked, a short-lived challenge instead of the JWT, the code
or a recovery code completes it). The admin switches it on as a duty per tenant (`tenant.totp_required`): a user
without a factor is led to the setup right after signing in and may do nothing else. A user who lost the device is
reset by the admin or a tenant admin (the factor removed, the setup asked again). The admin account's factor comes
from the environment (`APP_TOTP_SECRET`, optional). WAM users have no password and no factor - the proxy signs them in.
The secret is stored encrypted (`SecretCipher`), the recovery codes hashed (`PasswordHasher`). Passkeys (WebAuthn)
later, when a customer asks - TOTP works with every authenticator.

**Layers**: management migration (the secret, the recovery codes, the switch on the tenant); `security` (the second
step of the login, the challenge token, `ActiveTenantUserRule` leading a user without a factor to the setup);
`controller.account` (setup, confirm, remove, recovery codes), the admin's and tenant admin's reset; the frontend's
login page (the second step), "Konto" (the card with the QR code), the user lists (a chip, the reset in the ⋮ menu),
the tenant dialog (the switch); en.json, de.json; [SECURITY.md](../SECURITY.md), [docs/privacy.md](privacy.md),
[architecture/tenancy.md](architecture/tenancy.md) "Who logs in".

**Specs**: the second step through `/login` (right code, wrong code, a used recovery code, an expired challenge); the
setup needs a first right code; the duty leads a user without a factor to the setup and nowhere else; the reset by
admin and tenant admin, another tenant's user 404; WAM users untouched; the admin's factor from the environment.

**Open questions**: the challenge as a short-lived JWT of its own or a row; how many recovery codes (8 or 10); whether
a tenant admin may switch the duty for their own tenant or only the admin; the frontend's QR code library (a license
check, [THIRD-PARTY.md](../THIRD-PARTY.md)).

## 57 A budget per tenant and month

**Goal** (8 November 2026): an operator who bills the admin's global keys has every call recorded with its price,
but no limit: one tenant with an expensive model and many videos makes a month expensive, and the operator sees it
in the usage list afterwards. A budget stops it when it is reached.

**Idea** (proposal to write): an amount per tenant and month (`tenant.monthly_budget`, in the catalog's currency,
null = unlimited), set by the admin in the tenant dialog. Before every provider call that is billed to the tenant
(chat rounds, titles, embedding, scans, transcription, web searches - every call `UsageService` records), the month's
sum is compared with it; over it the call is refused with 409 (`usage.budgetExhausted`) - the chat shows the
message, `/api/v1` and the MCP server answer it as every other refused rule, a running agent loop ends its round. The
sum is read once per answer, not per call (a long answer may overshoot by its last round - accepted; a hard stop per
token would cost a query per call). The usage pages of the admin and the tenant admin show the budget next to the
month's sum (a chip: ok, warn from 80 %, err), the daily report names the tenants over 80 %. Optionally a budget per
user (`tenant_user.monthly_budget`) with the same rule - only if a case comes.

**Layers**: management migration (the column); `UsageService` (the month's sum per tenant - today's `summarize` has
it, a cheaper sum query), `AgentLoop` / `ChatService` / `DocumentIndexer` (the check before the call); `TenantRequest`
(the field); the tenant dialog, the usage pages' chip; en.json, de.json; `DailyReportMail`;
[architecture/ai-features.md](architecture/ai-features.md) "Usage".

**Specs**: a tenant under its budget chats, one over it gets 409 from the chat, `/api/v1/chat` and `ask_assistant`;
the embedding of a new document is refused too (the document marked failed with the reason); null = unlimited; the
sum counts the current month in `app.timezone`; the usage endpoints carry the budget; the daily report names the
tenants over 80 %.

**Open questions**: whether a tenant's own provider keys count against the budget (they cost the operator nothing -
I say no: the budget is the operator's protection, a tenant with its own keys pays its provider); whether the
background jobs (knowledge sources' sync, scheduled tasks) are stopped or only the interactive calls; the message's
text.

## 58 Retention of chat histories per tenant

**Goal** (8 November 2026): the chat's files and videos and the tasks' runs have retention times, set by the operator
in the environment; a conversation itself lives until its user deletes it. A company that must not keep its
employees' chats for years needs a time of its own, set by the company's admin, not the operator.

**Idea** (proposal to write): days per tenant (`tenant.chat_retention_days`, null = unlimited), set by the admin in
the tenant dialog and by a tenant admin on the page "Assistent" (open: whether both or the admin only). A nightly job
walks the tenants with a retention (not every tenant - see "No migration of every tenant on a spec context's start")
and deletes, in each tenant's database, every conversation whose last message is older than that, with its files in
the storage and its pending actions - the way a user's deletion does it today (`ChatService` / `ChatFileService`), so
nothing is left behind. The client software's conversations (owned by an API key) follow the same time. Usage rows
stay (they are the operator's billing, in the management database, without the question's text). The account page
tells the user the time ("Chats werden nach n Tagen gelöscht").

**Layers**: management migration (the column); a job in `services.chat` next to `ChatFileCleanupJob` (or the same
job with a second step); `TenantRequest`, the tenant dialog, the assistant page, the account's response; en.json,
de.json; [docs/privacy.md](privacy.md) "What is deleted", [architecture/ai-features.md](architecture/ai-features.md).

**Specs**: a conversation older than the time goes with its files and pending actions, a younger one stays; null keeps
everything; another tenant's conversations untouched; the API key's conversations follow; the account carries the time.

**Open questions**: the last message's time or the conversation's creation; whether a tenant admin may set it; the
job's hour (with the files' cleanup, every hour, or once a night).

## 59 Custom sandboxes

**Goal** (direction agreed 5 November 2026, proposal 8 November 2026): a tenant wants its own programs in the sandbox
the chat's Python and shell run in - its test framework and its scripts' runtime (a company whose software is tested
with Groovy and Python scripts), a simulator, a vendor's CLI, a library of its trade - so the chat can dry-run, test
and explain scripts against them. Today there are two images, ours and the video one. The core cannot carry a
trade's programs ("Industry-neutral"), and an operator who builds an image of their own on ours has no way to tell
the model what is in it - the model guesses from `pip list`, or does not know to look.

**The rule**: the content comes from the image, never from the core. The core learns one thing: the image may carry
a README, and the model gets it. No format, no capabilities, no setting at the tenant that could disagree with the
image (as with the video flag today), no new mechanism beyond passing a text through - cut down on 8 November 2026
from a first draft with `extras.json`, capabilities, chips, limits and `SANDBOX_TAG`: all of it was abstraction
before a case.

**Proposal** (S, about 1 day):

1. **The operator builds the image on ours and writes one file**, `/opt/extras/README.md`: what is installed and how
   to call it, in the words the model should use (English recommended, like our prompts) - the how, not the
   framework's handbook, because the text stands in every code question's prompt. The README's example Dockerfile:
   ```dockerfile
   FROM ghcr.io/xprojects-de/alpdesk-ai-sandbox:2.0.0
   USER 0
   RUN apt-get update && apt-get install -y --no-install-recommends <packages> && rm -rf /var/lib/apt/lists/* \
    && /opt/venv/bin/pip install --no-cache-dir --target /opt/extras/python <libraries> \
    && echo /opt/extras/python > "$(echo /opt/venv/lib/python3*/site-packages)/extras.pth"
   COPY README.md /opt/extras/README.md
   USER 10001:10001
   ```
   (the `.pth` as the video image does it; `ENTRYPOINT` untouched, `USER` back at the end, nothing written into
   `/opt/venv` itself, no secrets in the image - the token comes from the env). The operator tags the image with our
   version (`registry.example.com/musterfirma/sandbox:2.0.0`) and names it in the tenant's `sandboxes/<name>.env` as
   `SANDBOX_IMAGE=registry.example.com/musterfirma/sandbox` - the compose file is unchanged, the tag is the
   release's as for every sandbox; with every release the operator rebuilds on the new base (the README's "After an
   update" says so). `selftest.py` against the running instance keeps passing: the server is ours.
2. **`app.py` reads the file at start** and **`/health` returns its text**: `"extras": "<text>"` or `null` - at most
   16 KB, a longer one cut there with a line saying so (logged at start). A missing or unreadable file is `null`,
   never an error: the instance runs as today.
3. **`Sandboxes` keeps the text with the health** (`Health.extras`, the same 10 s), with the rule of the video flag:
   a session lands on any free instance of the tenant, so the model gets the text only when every answering instance
   reports the same one; otherwise none, and the admin's check says so.
4. **The model gets the text as it is**: `ChatService` appends to the code tools' rules, only when the tenant's
   sandbox reports one, a fixed heading "Programs installed in this sandbox (its README):" and the operator's text
   below it. No sentence of ours, no round to read it - the model knows at once. The rounds (`ChatService.maxRounds`)
   and the 300 s per command hold: a framework that needs more is wrapped by the operator into one command, and the
   README says which.
5. **The admin's check** ("Sandbox prüfen", `SandboxStatusResponse`) shows "Eigene Programme" next to "Video" per
   instance, and a warning when the instances differ. Nothing for the tenant admin (the sandbox is the admin's).

**What the user sees**: nothing new - the model knows what is installed and uses it.

6. **A ready example to copy** (added 8 November 2026): `Backend/podman/public/sandbox-example/` with a `Dockerfile`
   and a `README.md` for an invented program - in the repository next to the compose files, linked from the README's section. Built once by hand to check it.

**Layers**: `Backend/podman/sandbox/app.py`, `sessions.py` (read at start, `/health`), `selftest.py` (`extras` is a
string or null); `services.sandbox.Sandboxes` (`Health.extras`, the agreement rule, `extras(tenant)`, `extras(url)`),
`services.tools.CodeSessions`, `services.chat.ChatService` (the heading and the text), `dto.tenant.SandboxStatusResponse`
(`extras: boolean`, `agree`); the frontend's sandbox check (the chip, the warning - en.json, de.json);
`Backend/podman/public/README.md` ("Code execution" - "Your own sandbox image": the Dockerfile above, what to write
into the README, `SANDBOX_IMAGE`, rebuild per release), [architecture/code-execution.md](architecture/code-execution.md)
("Your own image": the file, the limit, the agreement rule), CHANGELOG (Added: your own sandbox image - nothing to do
when updating). No migration, no setting of the app, no compose change.

**Specs**: `Sandboxes` against a fake `/health` (as the video tests do): the text kept with the health, one instance
without it among instances with it gives none and the check says so, two instances with the same text give it, two
with different texts give none; the system prompt holds the heading and the text only when the tenant's sandbox
reports one (`ChatService` through `FakeAiModels`' recorded messages, like the video rules); the admin's check
endpoint carries the flag, the tenant admin has no such endpoint; `selftest.py` in CI against our image (`extras:
null`); the cut at 16 KB in `app.py` checked by hand once against a test image (`Backend/podman/sandbox/` gets no
test image - a README written into a running instance's `/tmp` cannot be read at start, so the check is a local
build on the Dockerfile above with a long README, done once and noted here).

**Deliberately not built** (decided 5 and 8 November 2026): a description format (`extras.json`, summary,
capabilities) - the README is the description; chips or rules per capability - the core knows none, a capability
that needs a rule of the core is a release of ours (as `video` is); `SANDBOX_TAG` - the operator tags with our
version and rebuilds per release anyway; more rounds per tenant or image - rounds are cost; a registry of images, an
upload by a tenant admin; the text in `/api/v1`, the MCP server or the A2A card. The video image stays described by
the core (its rules are the chat's: upload of 500 MB, 16 rounds, `view_images` / `hand_out_file`); moving its library
description into a README of its own is a later point, only if a case asks for it.

**Decided** (8 November 2026): the limit is 16 KB (about 4,000 tokens in every code question of that tenant). No open
question - built on the go-ahead.

**Built** (8 November 2026, 2.0.0): as proposed. `sessions.read_extras` / `/health` `extras`, `selftest.py` checks it;
`Sandboxes.extras(...)` and `extrasAgree`, `ChatService` appends the heading and the text when run_python is offered
(`ChatService` asks `Sandboxes` itself, as for the video flag - `CodeSessions` unchanged); `SandboxStatusResponse`
`extras` per instance and `extrasAgree`, the chip "Eigene Programme" and the warning on the tenant's page;
`Backend/podman/public/sandbox-example/` (`simtest`, invented, and pytest), its base tag
the release's version like the compose files. One change to the Dockerfile above: the image's `/opt/venv` has no pip, so the example
installs the system's `python3-pip` for the build (`--target /opt/extras/python`) and removes it again. Checked by hand:
`selftest.py` against `sandboxStart` (`extras: null`), the example built on the local sandbox image - `/health` returns
its README, `simtest run` and pytest run in a session -, and a README of 22 KB cut at 16 KB with the line and the log.

## 60 Frontier models through the hyperscalers' EU regions

**Goal** (8 November 2026): a German company, a law firm or an authority wants Claude or GPT, but no prompt at a US
endpoint. Anthropic's own API (`api.anthropic.com`) has no EU region, OpenAI's neither - Claude in the EU exists only
through **Google Vertex AI** (Belgium, `europe-west1`) or **AWS Bedrock** (Frankfurt, `eu-central-1`), GPT through
**Azure OpenAI** (Sweden Central, Germany West Central). Today's providers (`AiProvider`: `ANTHROPIC`, `OPENAI`,
`GEMINI`, `OLLAMA`, `OPENAI_COMPATIBLE`) cannot reach any of the three: Azure has its own URL layout and an `api-key`
header, Vertex wants short-lived OAuth tokens of a service account (noted in
[architecture/ai-features.md](architecture/ai-features.md) "Own model servers" as not built on purpose), Bedrock
wants AWS request signing. A customer with an Azure subscription of its own has nowhere to enter it. A hosted assistant
offers exactly this route - the one thing of its list ours could not do.

**Built (8 November 2026), smaller than proposed** - step 0 (the providers' pages of that day, in
[architecture/ai-features.md](architecture/ai-features.md) "Amazon Bedrock"): a provider `BEDROCK` on
`langchain4j-bedrock` (Converse API, the standard way), kept like an own server - the region's address and an IAM
access key at the catalog entry, no credential dialog of a new kind; chat models only. **Azure OpenAI** needs nothing:
its v1 endpoint is `OPENAI_COMPATIBLE` (documented). **Vertex** left out - Bedrock has the newer Claude models in the
EU, and the Vertex module fakes streaming. An Anthropic-compatible provider (Bedrock's `bedrock-mantle` endpoint) was
built the same day and taken out again: the Converse API is the standard; it can come back if a gateway needs it.
Tested against AWS the same day with Claude Haiku 4.5 (EU profile, Frankfurt): stream, tools, thinking, caching.
Open: the 5.x models (not yet approved for the test account; adaptive reasoning?), Azure untested.
The proposal below is kept as it was.

**Why it fits us**: nothing of ours stands in between - the **installation** holds the cloud account, accepts the
hyperscaler's standard data processing agreement (a click, no negotiation, no minimum spend) and enters the
credentials as it enters an API key today. Alpdesk AI is the client. The data stays where it is (the customer's
server); the prompt goes to the region the customer chose. Together with own model servers (Ollama) for data that
must not leave the house, that is a stronger statement than any SaaS can make.

**Proposal** (M, about 3-4 days; first step half a day of checking):

0. **Check the three ways in** before building (the portals change: forms, quotas, regions, retention terms):
   Azure OpenAI (resource, deployment, key; the default abuse monitoring keeps prompts up to 30 days - "modified
   abuse monitoring" is a request, not granted to every subscription), Vertex AI for Claude (Model Garden terms,
   regions, the zero-retention statement for Anthropic models), Bedrock (model access request, regions, retention).
   Write the findings into the proposal and into `docs/privacy.md` - including what an EU region does **not** give:
   it is no transfer to a third country, but the provider stays a US company under the CLOUD Act.
1. **Three providers** in `AiProvider` and `services.ai.AiModels`, each on its langchain4j module
   (`langchain4j-azure-open-ai`, `langchain4j-vertex-ai-anthropic` or the Vertex Gemini module as it offers Claude,
   `langchain4j-bedrock`) - chat, streaming, embeddings where the provider has them (Azure: OpenAI's embeddings;
   Vertex: Google's; Bedrock: Titan/Cohere - or none, the embedding stays with another provider), prompt caching
   where it works through the hyperscaler (Claude's `cacheAutomatically` on Vertex and Bedrock - to be checked),
   reasoning as with the direct providers. `THIRD-PARTY.md` for the modules and their transitive libraries (the AWS
   SDK and Google's auth library are large - `checkLicense` decides), `NOTICE` if they bring one.
2. **Credentials, not just a key**: Azure takes endpoint + key (+ API version), Vertex a service account's JSON (or
   project + location + a key file's content), Bedrock access key + secret + region. The provider key dialog
   (`ProviderKeyController`, `GlobalProviderKeyController`, `provider-key-list`) gets fields per provider; everything
   encrypted like a key today (`SecretCipher`), `keyHint` from the part that identifies it. The catalog entry carries
   the region/deployment where the credentials do not (Azure: deployment name as `modelName`; Vertex and Bedrock:
   the region at the credentials, the model id as `modelName`).
3. **Prices**: the hyperscalers charge the providers' list prices (to be checked per model) - the catalog's prices
   per model as today, nothing new.
4. **Usage and failures**: recorded as today per call (`UsageService`); the failure texts name the display name,
   never the address or the account (`AiModels` as today); `ModelServerSpec`-like specs against an HTTP server inside
   the spec that answers like Azure (the OpenAI shape with `api-key`) - Vertex and Bedrock through their SDKs' fakes
   or a local endpoint override, to be seen in step 0.
5. **Docs**: `docs/privacy.md` "What leaves the server" and "For the big providers, check before use" (the EU routes,
   retention per hyperscaler, the CLOUD Act sentence), `SECURITY.md` if credentials of a new kind are stored,
   `Backend/podman/public/README.md` a section "Frontier models in EU regions" (which account, which region, what to
   switch off at Azure), `website/index.html` (the claim, soberly), `architecture/ai-features.md` "Providers", CHANGELOG
   (Added). A minor version: nothing to do by hand when updating.

**Specs**: the three providers build their models from the stored credentials (no provider is called -
`FakeAiModels` replaces `AiModels`; `ModelServerSpec` for the Azure shape against the spec's server); the credential
dialogs' validation (400 per missing field, data table); the key hint; tenant isolation of the keys as for every
provider key; prices and usage as today.

**Deliberately not**: Anthropic's or OpenAI's enterprise contracts for data residency (a customer with one enters
its endpoint as today); a gateway of ours; a "where does my data go" page per tenant (the admin assigns models, the
tenant admin sees their providers - as today); Mistral is `OPENAI_COMPATIBLE` already.

**Open questions** (for the go-ahead, after step 0): whether Bedrock is needed next to Vertex (one EU route for
Claude may be enough - Vertex, because Gemini is on it already), the embedding providers per route, and whether the
credentials are a kind of provider key (one dialog) or settings of the catalog entry (as own servers are).

## 61 Connections to servers that sign in with OAuth

**Plan agreed 8 November 2026** for **2.0.0**, both doors (MCP and A2A) in one change, against a fake authorization
server in the specs: a separate plan (kept outside the repository). It supersedes the proposal below where
the two differ: the grant row carries the pending flow (state, verifier) instead of a signed state; the callback is
`/oauth/callback` and sends the browser back to the Software page; the A2A door is built now, not later; the daily
report names connections that need a sign-in; a fallback field for a client id covers servers without dynamic
registration; built against MCP 2026-07-28 (Client ID Metadata Document first, dynamic registration as the deprecated
fallback, `iss` checked, `resource` in both requests); the OAuth messages come from Nimbus `oauth2-oidc-sdk` (decided
8 November 2026 after checking it, Micronaut Security OAuth2 and the MCP SDK against that specification - RFC 9728,
CIMD and the storage are ours, no client SDK runs a flow). The proposal is kept as it was.

**Goal** (8 November 2026): a tenant's software behind a **hosted** MCP server that accepts no fixed token. The case
that brought it up: a company's bookkeeping data. Its bookkeeping system has no MCP server of its own; a third party
runs a gateway next to the installation and offers the data as an MCP server (`https://<gateway>/mcp`, read as
standard, writes per scope) - and that server signs in with **OAuth 2.1 and dynamic client registration only**. Claude
Code, claude.ai and Copilot connect to it, because their MCP clients sign in with OAuth. Ours cannot: an MCP connection
(`McpConnection`, `McpClient.transport`) carries the address and an optional key sent as `Authorization: Bearer`,
nothing else. The same holds for a growing number of hosted MCP servers (issue trackers, wikis, cloud storages):
OAuth is becoming the normal door of a hosted server, the fixed token the exception of a server one runs oneself.

**Not point 37's OAuth**: there Alpdesk AI is the **server** signing people in to `/mcp` (rights per person, L,
"Later"). Here it is the **client** signing in to someone else's server. Nothing of the two is shared but the name.

**Checked on 8 November 2026** (the gateway's setup pages for several MCP clients):
OAuth 2.1 with dynamic client registration is the only door - no bearer, no header, no stdio; the "API key" of its
pages is a way to sign in on its consent page in the browser (instead of e-mail and password), not a token for the
endpoint. Every client, the self-hosted ones too, sends a person through the browser once and keeps the refresh
token. Every client that speaks MCP's streamable HTTP with OAuth 2.1 can use the endpoint - no whitelist. Which
person the server represents afterwards (a shared service user, or a profile per person) is decided on the
server's side when signing in - the connection on our side is one, its rights are the groups' levels. So this point
is needed for that server, not optional.

**The shape** (decided 8 November 2026): **the OAuth part is protocol-neutral and lives in one place; the doors are
small.** Nothing of the flow is MCP's: authorization server metadata (RFC 8414), dynamic client registration (RFC
7591), authorization code with PKCE (RFC 7636), the callback, the token exchange, the refresh with rotation, the
protected-resource metadata (RFC 9728). MCP only says where the flow starts (a 401 with `WWW-Authenticate:
resource_metadata=...`, or `/.well-known/oauth-protected-resource` at the server's address); an A2A agent says it in
its card (`securitySchemes`). So the flow and the tokens go into `services.oauth` (`OAuthClient`, `OAuthGrant`) and
one table `oauth_grant` a connection points to - not into `McpClient` and not as `oauth_*` columns of
`mcp_connection`. Not an abstraction in advance: the same code with no coupling to what the token is for. The first
door is MCP; the A2A door comes when an agent demands it (about a day then, see "Later"). SharePoint's MSAL (client
credentials, an app without a person) is another flow and stays as it is.

**Proposal** (M, about 3-5 days):

1. **The flow**, `services.oauth.OAuthClient`: given the authorization server's metadata address and the resource,
   read the metadata, register the client dynamically where the server offers it (otherwise a client id entered by
   hand), build the authorization URL with PKCE and `state`, exchange the code for tokens, refresh - rotating refresh
   tokens are stored in the same transaction as they arrive, and a connection refreshes under a lock of its own, so
   two calls at once never lose the one valid token. The callback (`/oauth/callback`, one anonymous route - the
   `state` bound to the grant and the signed-in person, once, short-lived, is the check, like the handbook's token)
   is the flow's, not MCP's. `OAuthGrant` holds client id and secret, access and refresh token (encrypted with
   `SecretCipher` like `api_key`), expiry, scopes granted, the token endpoint and the redirect address used.
2. **The MCP door**: on "Connect with sign-in" in the connection's dialog, `services.mcp` finds the authorization
   server (the 401's `WWW-Authenticate` or `/.well-known/oauth-protected-resource`), starts the flow and sends the
   tenant admin's browser there; `mcp_connection.oauth_grant_id` (`V17__release_2_0_0.sql` or the next version's set)
   points to the grant. `apiKey` stays for servers with a fixed token - a connection has one or the other. The DTO
   never returns a token, only "signed in", the expiry and the scopes granted.
3. **Using them**: `McpClient.transport` sends the grant's access token as bearer; before a call whose token is about
   to expire, and on the SDK's authorization error (`McpHttpClientTransportAuthorizationErrorHandler`, mcp-core
   2.0.1), it refreshes once through `OAuthClient` and retries; a refresh refused marks the connection "sign-in
   needed" (the tenant admin sees it on the Software page and in the daily report) - the chat's tool call fails like
   every failed call of the connected software, the model is told, the answer goes on. Deleting a connection deletes
   its grant.
4. **Scopes**: requested as the server's metadata lists them; which ones are granted (writing ones have to be released
   on the server's consent page, as the gateway does it) the connection shows afterwards - the levels per user group
   (`mcp.md` "Levels") stay the gate on our side, and a changing tool waits for the user's decision as today.
5. **Internal hosts and addresses**: the authorization server's and the token endpoint's addresses go through the
   same rules as the server's own (`McpAddresses` for an MCP connection) - HTTPS only, no private address unless listed
   as internal, no redirects followed; the browser's redirect is the person's, not ours.
6. **Docs**: a section "Signing in to someone else's server (OAuth)" in `architecture/mcp.md` (the flow, the grant,
   the refresh, the failure states; `a2a.md` points to it when the A2A door comes), `docs/privacy.md` (tokens stored
   encrypted, what a refresh sends), `SECURITY.md` (the callback as an anonymous route and its check),
   `Backend/podman/public/README.md` (connecting a hosted server, the redirect address behind the proxy), CHANGELOG
   (Added). A minor version.

**Specs**: `services.oauth.OAuthClientSpec` against a fake authorization server inside the spec (metadata,
registration, code exchange, refresh with rotation, a refused refresh, two refreshes at once keep one valid token);
`McpConnectionSpec` for the door: the connection's states, the token never in a response, the `state` check (a
callback with another grant's or an expired `state` → 400, nothing stored), tenant isolation (another tenant's
connection id → 404), the retry once on 401 and the failure state after it, the grant gone with the connection;
`TenantApiKeySpec` for the anonymous callback. `FakeMcpSoftware` learns to demand a bearer token and to answer 401
for a stale one.

**Later, when an agent demands it - the A2A door** (S, about a day): the card's `securitySchemes` names the
authorization server; `a2a_connection.oauth_grant_id`; `A2aHttpClient` sends the grant's token and refreshes through
`OAuthClient` like `McpClient`. The flow, the callback, the table and the specs of the flow exist by then.

**Deliberately not**: a connector catalog or anything named after a product or the gateway - the connection is a hosted
MCP server like any other; a token per user (the connection signs in as the tenant admin who connected it, as the
fixed key does today - rights per person are point 37's OAuth, the other direction); client-credentials flows and
device flows until a server needs one; the A2A door before an agent asks (see "Later"); a grant shared by several
connections (one grant, one connection - simpler to delete and to show).

**Open questions** (for the go-ahead): where the redirect address comes from - the installation has no setting for
its public address today (the MCP key's commands take it from the browser), so either a new setting `app.public-url`
or the address the browser shows at the moment of connecting; whether a connection may be shared by the API keys' software
(`connectionIds`) when it was signed in by one tenant admin - probably yes, the token is the tenant's.

## 62 The IMAP mailbox signs in with OAuth (XOAUTH2)

**Idea** (8 November 2026, from the survey "where else does OAuth come up" of point 61): a knowledge source's mailbox
(`FileStoreType.IMAP`, point 50) connects with a password over TLS today. Microsoft 365 switched basic
authentication off for IMAP; Google takes app passwords only. A company's mailbox at a hosted provider therefore
needs **XOAUTH2** - an OAuth access token presented in the IMAP login instead of a password. The SharePoint store
already holds a client id, secret and tenant for MSAL (client credentials): the same credentials with the mail scopes
give the token for IMAP; a personal mailbox (a person's consent instead of an app's) would use point 61's
authorization-code flow and grant. Not in point 61: another layer (SASL in the IMAP login, `jakarta.mail`'s
`mail.imap.auth.mechanisms=XOAUTH2`), and no company has asked. When one does: the store dialog gets "Anmeldung:
Passwort | Microsoft 365 (App) | ..." as the SharePoint fields are, `MailboxStoreSpec` a GreenMail that accepts
XOAUTH2 (or a fake), `docs/privacy.md` the token. About 2 days.

## 63 Files a tool returns as a link (MCP `resource_link`)

**Plan agreed** 9 November 2026, to be built right after point 61, in 2.0.0 (`### Added` in CHANGELOG.md).

**Built** 9 November 2026 as planned (`McpClient.callTool`, `LinkDownloads`), with these differences:
- The redirect is followed by hand (3 at most, each target checked) - not refused: a presigned download often
  redirects to the storage host.
- A link of a failed call is not fetched and noted `[file "x" not fetched - the call failed]`; a link beyond the
  answer's 5 files `... - an answer takes at most 5 files`.
- A task's document without a file names the note in its reason (`McpClient.unfetchedNotes`).
- Not specced: a timeout (60 s would hold the suite a minute) and the bearer with an OAuth grant - the same path as
  a key (`Credential.token()`), specced with a key.

**The case.** A tenant keeps its files in a document management system reached through a hosted MCP gateway (point
61). The gateway's tool "retrieve a document" hands out the file as a **temporary download** - valid once, for a few
minutes - not as an embedded resource. The users must work with these files in the chat (read, check, compare, run
Python on them) and in scheduled tasks (`call_tool` as a source of documents, then `extract`, `write_file`,
`send_mail`). Today `McpClient.callTool` drops every content part but text, embedded resources and images:
`[resource_link content left out]`. The model sees no file, the task's document has none.

**The standard.** Since the MCP specification of 18 June 2025 a tool result may carry a `resource_link` (`uri`,
`name`, `title`, `description`, `mimeType`, `size`) instead of the content. What the client does depends on the uri:
- **an address of the server's own** (`file:`, a scheme of the server's own, any scheme but http/https): the client reads it with
  `resources/read` at the same server - a resource link "is not guaranteed to appear in `resources/list`";
- **`https`**: the client fetches it from the web itself.
The MCP Java SDK has both: `McpSchema.ResourceLink` and `McpSyncClient.readResource`. A plain address written into
the result's text is no standard and stays text - nothing can tell a file's link from any other link there.

**Binding decisions**
1. **One place**: `McpClient.callTool` resolves every `resource_link` of the result before it returns, inside the same
   `session(...)` (a server's own uri may live only as long as the session; a temporary download expires in minutes -
   resolved later, it would be gone). The result is a `ReturnedFile` like an embedded one, so `McpTools.execute` (the
   chat), the task's documents source (`DocumentReader`) and `SoftwareMcpProxy` (files passed through to MCP clients)
   take it without a line changed. Its name: the link's `name`, else the last part of the uri; its type: what the
   content shows (`ChatFileReader` already corrects the server's claim), the link's `mimeType` only as a hint.
2. **A server's own uri** - `resources/read` in the same session, with the connection's credential (the session has
   it). Its `contents` as today's embedded resource (`returnedFile`): blob or text. Within `MAX_MESSAGE_BYTES` (8 MB)
   like every message of the server.
3. **An `https` uri** - fetched with the JDK `HttpClient` under the connections' address rules:
   `McpAddresses.requireAllowed` on the link's address before the request (no internal host unless the tenant's
   connection itself is on a listed internal host - then the same rule as for the connection), `followRedirects(NEVER)`
   (a redirect is followed by hand at most 3 times, each target checked again: a presigned download often redirects to
   a storage host), the body read up to `ChatFileService.MAX_FILE_BYTES` (50 MB, the chat file's limit - checked while
   reading, never trusting `Content-Length`) and 60 s for the whole download, not `app.ai.timeout`. **Our bearer goes
   with it only when the link's origin is the connection's own** (scheme, host, port - as `McpAddresses.normalize`
   gives them); a download host elsewhere never sees the token - a temporary link carries its own right. `http`
   (plain) only to a host in the internal hosts, as for the connection's own address.
4. **Limits as today**: at most `CreateFileTool.MAX_FILES_PER_ANSWER` (5) files per answer - a link beyond it is not
   fetched at all (no download for a file that is dropped); the task's documents source one file per answer as now.
   A link in a failed call's result (`isError`) is not fetched: its files are not offered anyway.
5. **A link that cannot be resolved** is a note for the model, never a failed call:
   `[file "<name>" could not be fetched: <reason>]` - expired (404/410), too large, refused address, timeout, no
   content. The tool's text stays; the task's document gets the reason (as `mcpServer.tooLarge` today).
6. **Not built, on purpose**: links in the result's text; `resources/subscribe`; resolving a link lazily when the
   model asks for it (the download expires); A2A's `FilePart` with a uri (an agent answering with a file link - the
   same shape, its own client; when an agent does it).

**Specs** (`FakeMcpSoftware` gets a tool returning links of both kinds, and an `https` download server of the spec):
- the chat: a link of the server's own and an `https` link each become a file of the answer, read like an upload (a
  PDF's text, an image), the model told it was created;
- a task's `call_tool` source of documents whose file tool answers with a link: the document extracted;
- the MCP proxy passes the resolved file on as an embedded resource;
- `where:` table of refusals, each a note and no file: expired (410), larger than 50 MB (the body cut while read), an
  internal address, a redirect to an internal address, plain `http` to a public host, a timeout, an unknown scheme
  that `resources/read` refuses;
- the bearer goes to the connection's own host and never to another (the download server records the headers), for
  a key and for an OAuth grant;
- a sixth link of an answer is not fetched; a link in an `isError` result is not fetched.

**Docs**: `docs/architecture/mcp.md` "Files in tool results" (the link, the two ways, the rules), SECURITY.md (one more
outgoing request: a link's address, the same rules as a connection), `docs/privacy.md` (a file fetched from a link is
stored like one returned by the tool), CHANGELOG `### Added`, the MCP/A2A review skill's checklist.

**Hand test**: against the gateway of point 61's hand test - "retrieve a document" in the chat and as a task's source,
the file at the answer and in the run.

**Effort**: about 1 day with specs and docs.

## 64 SMB as a file store

**Idea** (9 November 2026): a company's documents often lie on a share of a Windows file server or a NAS
(`\\fileserver\Belege`), reached by SMB - not by SFTP. A sixth type of file store next to the five of points 44 and
50; the tasks and the knowledge sources read it without a line changed.

**Decided** (9 November 2026): SMB inside the app through **smbj** (Apache-2.0) - **not a CIFS mount**: mounting needs
`CAP_SYS_ADMIN`, which rootless Podman cannot give and Kubernetes' "baseline" and "restricted" pod security refuse,
and a tenant admin's address would have a container serving every tenant mount a share into the kernel. A share the
operator mounts outside and the app reads as a local folder stays dropped (point 44).

**The limit**: NTLM does not prove the server - unlike every other type, the server cannot be confirmed before the
login. Decided (9 November 2026): public hosts too, internal ones only when listed (the rule of SFTP); SMB 3
encrypted and signed, SMB 2 and unencrypted SMB 3 only to a listed internal host (signed), no guest, no DFS; the limit in SECURITY.md with the advice of a user of its own and a long random
password; Kerberos when a customer asks. Without a Windows server or NAS to test against, the hand test runs against
`devtools/smb-test-server/` (Samba with invented data, one share per scenario, `check.py`).

**On hold, not in 2.0.0** (decided 9 November 2026, after weighing it once more): built when a customer has only
the share - the proposal is complete and its questions are answered, so it is about three days from the go-ahead. The hand test can
then run against a NAS (a real device in a real network; nearly every NAS runs Samba, so a Windows server's
own behaviour stays untried). **What speaks against building it before a case** (noted the same day): no customer
has asked, and SFTP reaches the same files today - a Windows
server has an OpenSSH server built in (since Server 2019), nearly every NAS a switch for SFTP - with the server
proving itself by its fingerprint, which SMB cannot. SMB would also be the one type never tried against the real thing
(no Windows server to test against). Its case is the customer who says "we may not switch anything on at the server, there
is only the share".

**Proposal**: a separate plan (kept outside the repository) - data, client, failures, frontend, specs against a
Samba container, the test server, docs. **About 2.5-3 days**, a minor version after 2.0.0 (the type's check constraint then in a migration of its own).

## 65 Kerberos for the SMB file store

**Idea** (9 November 2026, asked while planning point 64): Microsoft is retiring NTLM (NTLMv1 gone from current
Windows, NTLMv2 deprecated), and hardened companies switch it off by policy - there point 64's SMB store gets no
login. With Kerberos the store signs in with a ticket of the company's Active Directory, and the server proves itself,
which NTLM cannot.

**Not built with point 64** (decided 9 November 2026): it is larger than the store itself and cannot be tried.
What is known:
- the JDK's Kerberos is configured per process (`krb5.conf`), the tenants bring their own realms - two may carry the
  same name with different domain controllers; this has to be designed first;
- the domain controller (port 88) is one more address a tenant admin enters, checked like every other;
- the server must be addressed by its name (the ticket is for `cifs@<name>`), not by the checked IP address;
- smbj logs in through `Subject.doAs`, deprecated for removal - tried on Java 25 first; `org.codelibs:jcifs` (LGPL)
  is looked at again, Kerberos is where it is ahead;
- it needs an Active Directory to try against, and a Samba domain controller in a container for the specs.

**When**: a customer whose policy refuses NTLM and who can lend a test domain. Then a proposal of its own; in the
store's dialog "Anmeldung: NTLM | Kerberos" with realm and domain controller. Nothing is prepared for it in point 64.
Until then: where NTLM is switched off, SFTP (OpenSSH on the Windows server) reaches the same files.

**Effort**: about 4-6 days with a test domain.

## 66 Media and documents read apart from the app

**Idea** (10 November 2026, from the security review): defence in depth for the two places that read foreign
files - they have more reach than they need.

- **ffmpeg** converts the chat's audio and video (`services.chat.MediaConverter`) inside the app's container, with
  the app's environment and without the hardening the sandbox has (`cap_drop`, `no-new-privileges`, a read-only
  root). It already reads local files only (`-protocol_whitelist file`), refuses playlists, has a timeout and a size
  limit, runs without a shell and not as root (user 999).
- **Tika** has no time limit around a parse (`DocumentTextExtractor.text`): a file that makes a parser loop holds
  its thread until the app is restarted. Java cannot end a computing thread - only a process of its own can be ended.

**Steps**, smallest first:

1. ffmpeg started without the app's environment - one line; a first step, the separation of step 4 is the real one.
2. `cap_drop: ALL` and `no-new-privileges` for the app in both compose files (`docker-compose.prod.yml`,
   `docker-compose.proxy.yml`) and the README's Kubernetes notes - a Java app on port 19100 needs neither.
3. A read-only root for the app: it writes to `/tmp` (uploads up to 512 MB, Tika's temporary files, ffmpeg) and the
   files volume - the tmpfs sizes have to be tried first, with a video of 500 MB.
4. The separation itself: ffmpeg in a container of its own without secrets and network (the tenant's sandbox already
   runs code that way - one for the installation, not per tenant), and, only if a real case shows, Tika in a process
   of its own with a time limit (Tika's `ForkParser`).

**Why later**: the releases pick up ffmpeg's and Tika's fixes with the image's base packages and the libraries -
updating regularly is what protects most. Steps 1 and 2 are small enough for any release; 3 and 4
get a proposal of their own.

**Effort**: steps 1 and 2 about an hour with their spec and the compose check; step 3 half a day with trying; step 4
about 2-3 days for ffmpeg, more for Tika.

## 67 A user's own connections to software

**Plan agreed 10 November 2026** for **2.1.0** (a minor version), in the shape the user chose that day. Not
started: 2.0.0 is released first, no code before the go-ahead.

**Goal**: a company connects its issue tracker, its wiki and other software as MCP servers (ready-made ones, or a
small server in front of an API - nothing of them in the core). In a large company these show each person only what
that person may see. A tenant's connection signs in **once** (its key, or one OAuth sign-in a tenant admin made,
point 61): every user sees what that one account sees, every change is recorded under its name. Right for a
company's own software with a service account, wrong where rights differ per person.

**The shape** (the user, 10 November 2026): **nothing personal on a tenant's connection - a personal connection is
the user's own, whole.** Two kinds of connection, not one with modes:

| | The tenant's connection (as today) | A user's own connection (new) |
|---|---|---|
| Kept by | the tenant admin, the admin | the user, on the account page |
| Sign-in | one, for everyone | the user's own (token or OAuth) |
| Tools, prompts, instructions | read once, for all | read with the user's sign-in, for that user |
| Used by | the groups' levels, API keys, MCP keys, tasks | that user's chat only |

So the tenant admin needs no access of their own to offer such software, no list of tools is shared between people
who see different ones, and no sign-in ever stands in for another.

**Proposal** (M, about 3-4 days):

0. **The tenant admin's switch** "users may connect software of their own" (`tenant.user_connections_allowed`, off
   by default; the admin sets it too): a company decides whether its people may send questions to servers of their
   choice. Off: the account page shows no panel, the routes answer 409, and connections made while it was on are
   kept but not used in the chat until it is on again.
1. **Data** (management database): `mcp_connection.user_id` (null: the tenant's; `ON DELETE CASCADE` to the user).
   Everything else of a connection is as it is - its tools, prompts, instructions and its one `oauth_grant` row.
   **OAuth per person needs nothing new**: a user's connection has its own grant, started and completed by that
   user (the callback already takes only the one who started it). Names are unique per tenant for the tenant's and
   per user for their own.
2. **`McpConnectionService` learns the owner**: every list of a tenant's connections - the Software page, the keys'
   and tasks' choices, the proxy, the groups' levels, the daily report - reads `user_id IS NULL` only; a user's
   connections are read by that user's id alone. The one rule the specs guard hardest: **no one but its user sees,
   uses or changes a personal connection** - not another user, not the tenant admin, not the admin, no key, no task.
3. **In the chat**: the user's connections come on top of the tenant's the user's groups allow - the same tools
   (`McpTools`, an A2A agent as a tool), the same chips to leave one out per question. No group level: reading
   tools are called directly, a changing tool waits for the user's decision as today (`PendingAction`). Not for a
   personal connection: "answers the chat", pass-through to MCP clients, API keys, MCP keys, tasks.
4. **The user's API** (`controller.account`, `Roles.TENANT_USER`, part of the published users' API):
   `/tenant/account/software` - list, create, change, delete, reload, check, `.../{id}/sign-in` (OAuth) -, the
   tenant admins' connection routes for one owner. Another user's connection answers 404. The address goes through
   the connections' address rules as an admin's does (`McpAddresses`, internal hosts only when the operator listed
   them); the key and the tokens encrypted, never sent out again; at most 10 connections per user.
5. **Frontend**: a panel "My software" on the account page - the shared `mcp-connections-panel` for the owner
   (name, address, token or "Sign in", the tools read, reload, delete), without levels, pass-through and keys.
6. **A2A** (decided 10 November 2026: both protocols in one change): a user's own A2A agent as a tool in their
   chat, where the tenant may use A2A at all (`requireA2aAllowed`); cancelling uses the connection's own sign-in,
   which is the user's - no special case.
7. **Docs and specs**: `architecture/mcp.md`, `architecture/a2a.md`, `docs/privacy.md` (a user's addresses and
   tokens stored encrypted, deleted with the user), `SECURITY.md` (who may make the server call an address),
   CHANGELOG `### Added`. Specs: a user's connection in their chat and in no one else's; absent from every list of
   the tenant's (admin, tenant admin, keys, tasks, proxy, `/api/v1/software`); another user's id 404; the address
   rules; OAuth for two users at the same server (`FakeAuthorizationServer`), one's refresh leaving the other; a
   user's A2A agent (`FakeA2aAgent`); deleted with the user; `TenantApiKeySpec` for the new routes.

**What it costs** (said plainly):
- **Every user enters the address themselves** - with many users the same one, each typing it.
- **A user decides where their questions go**: what the assistant sends a personal connection's tool is what that
  user may read anyway, but it leaves to a server the tenant admin did not choose - hence the switch.
- **More tools per question** for a user with many connections - the limit of 10; tool retrieval is point 48.
- With dynamic client registration each user's connection registers a client of its own at the server.

**Not built, on purpose**: user name and password (as a Basic header - a company's passwords stored with us; a
personal token does the same); a personal sign-in on a tenant's connection (the modes `SHARED` / `OPTIONAL` /
`REQUIRED` agreed earlier on 10 November 2026 and replaced by this shape the same day: a list of tools shared by
people who see different ones, and a fallback between sign-ins, were what made them complicated); sharing a
personal connection with colleagues; personal connections for keys and tasks.

**Decided 10 November 2026** (the proposal's two open questions):
1. **Who may**: only with the tenant admin's switch, off by default (step 0).
2. **Which addresses**: any address the connections' rules allow. Not built: addresses offered by the tenant admin
   (name and address, the user adds the sign-in) - when many users of one company type the same address.

## 68 Changing tools of the connected software through to MCP clients

**Built 11 November 2026** for **2.0.0** (agreed the same day: a level per MCP key and software) - the decisions
in [architecture/mcp.md](architecture/mcp.md) "Changing tools passed through". It is the "later" of point 41. The API
took `changingConnectionIds` next to `connectionIds` instead of the groups' `levels`: the key's software stays as
the smoke test, the test stacks and the frontend send it.

**A migration of its own** (decided 11 November 2026): the release candidate of 2.0.0 is out and installed, so the
column does not go into `V17__release_2_0_0.sql` - an installation of the candidate has applied that file, and a
changed one would stop its start. A new file after it.

**Goal**: a company uses its MCP keys from Claude Code, Cowork or Copilot and puts an MCP server of its own behind
ours as connected software. That server also offers tools that change something - a ticket created in an issue
tracker, a comment added, a document filed on another server. Today the proxy passes a connection's **reading** tools
only (`SoftwareMcpProxy.passed`, the level fixed to `READ`); a changing tool reaches nobody outside the chat. The
client's model shall call those tools directly, as it calls the reading ones.

**Why it is safe to pass them now**: in the chat a changing call waits for the user's decision, and a key has no user
- hence "never" until now. Passed through directly there is a person again: the MCP client asks its user before every
call of a tool that is not marked read-only. The approval moves to the client. The scheduled tasks already work that
way round (the tenant admin naming the tool is the approval, mcp.md "Tools called by scheduled tasks").

**The shape**:
- **A level per MCP key and software** where the key card has a tick per software today: none, `READ` ("nur lesen" -
  the tick as it is), `WRITE` ("lesen und ändern" - new), the user groups' `McpAccessLevel`. A column `level` on
  `tenant_mcp_key_connection`, every row there `READ` - a key that exists gets nothing new. Per key and software, not
  one switch per key: the assignment is there already, and a key may then change in one software and only read in
  another without turning the other's tools off for everyone.
- **Which tools**: the connection's turned-on tools as today - a tenant admin turns each tool on; one left off (a
  delete) reaches no key. With `WRITE` the key's `tools/list` adds the changing ones of a connection that **passes
  through** ("Direkt an MCP-Clients"); `tools/call` checks the same (`McpTools.usable(tool, level)`, one place for
  list and call). A connection that answers only has no `WRITE` to choose - the key card offers it only where the
  connection passes through, and the service refuses it otherwise.
- **Marked as changing**: `readOnlyHint: false` and nothing else - a client then takes the tool for one that may
  destroy and asks. The software's own `destructiveHint` is not stored (`mcp_tool` keeps `readOnlyHint` and
  `changes`); no column for it until a client makes a difference of it.
- **`ask_assistant` and `/a2a` stay reading** (`McpTools.forMcpKey`): there the tenant's model would decide and no
  person sees the call before it runs. The client software's API keys stay reading too.
- **A call that failed after it was sent may have run** (no answer in time, an answer without content or too large):
  the tool error of a changing call says so ("... the change may have been made; check before calling again"), since
  the client's model repeats a failed call.
- **A software that stops passing through takes the level off its keys** (the review of 11 November 2026: left, it
  would be in force again unseen when the switch is turned back on).
- **A line in the log for every changing call** - key, software, tool, never the arguments: the chat keeps who decided
  what (`PendingAction`), the proxy logs failures only.
- **API and frontend**: `changingConnectionIds` next to `connectionIds` on the key (of its software, those it may
  change in); in the key's dialog "Darf ändern in" below the software, the key's row says where it may change; both
  languages.

**What it costs** (said plainly):
- **A key is a shared secret**: whoever holds it changes what the key may change; no rights per person.
- **Everyone writes as one account**: a connection signs in once (its key, or one OAuth sign-in) - the issue tracker
  sees that account, not the person at the client. A software that must know the person takes it as an argument, or
  the person connects it themselves (point 67 - in the chat only).
- **The client's question is the only stop** between a prompt injection read at the client and a change in the
  software; a user who lets the client always allow a tool gives it up. `SECURITY.md` says so.
- **Binary files stay poor**: a tool takes JSON arguments, so a PDF to file would be base64 written out by the
  client's model - text is fine, large binary files are not this point's.
- **No question back from the software**: `/mcp` is stateless, a tool asking for values during the call (elicitation,
  point 30) fails through the proxy - its tools take everything as arguments.

**Changes with it**: `docs/architecture/mcp.md` ("Connected software through to MCP clients", the MCP keys),
`SECURITY.md` (the three places that say a key never gets a changing tool), CHANGELOG `### Added`; a changing scenario
in `devtools/mcp-test-server` and `proxy_check.py`, the line in `devtools/general-chat-test/walk/features.md`. Specs:
`McpProxySpec` (a key with `READ` lists and calls no changing tool, with `WRITE` both; a tool turned off; a connection
not passing through; another key; another tenant; the annotation; the timeout's text), `McpSpec` (`ask_assistant`
still reading with `WRITE`), the key's API (the level saved, refused for an unknown connection and for `WRITE` on a
connection that answers only), the migration keeping every key at `READ`.

**Size**: about 2-3 days - the column, the key's API and service (0.5), the proxy with annotation, error text and log
line (0.5), the key card (0.5), specs (1), docs, SECURITY.md, the test server (0.5).

**Not built, on purpose**: changing tools for `ask_assistant`, `/a2a` or the client software's API keys; a record of
the changing calls in the database (the log line until someone must look them up); the software's `destructiveHint`
and `idempotentHint` passed on; elicitation (point 30); rights per person (point 67, OAuth per person).

**Noted for later - a question back over A2A** (11 November 2026, left out for now): `/a2a` could offer changing
tools the way the chat does, with the calling agent in the user's place - the task goes to `input-required` naming
the tool and its values, the agent confirms or declines with a message to the task, then the call runs or is dropped.
What it needs: `input-required` at our server (a message to an existing task is `UnsupportedOperationError` today,
a2a.md), every such question a stored task, the waiting call owned by the key instead of a user - about a week. Its
limit: an agent decides at the other end, not surely a person - a model confirming a model's question is no check,
and a workflow engine without a user confirms by itself or hangs; `SECURITY.md` would have to say the calling agent's
operator answers for it. `ask_assistant` has no counterpart (elicitation, point 30 - `/mcp` is stateless). When: an
agent that speaks A2A only must change something and a person decides behind it.
