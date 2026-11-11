---
name: alpdesk-usage-review
description: "Focused non-diff, multi-agent review of models, keys and usage in Zirmo: the model catalog with prices (admin), which models a tenant may use, own model servers, provider keys (the tenant's own before the admin's global ones, globalKeysAllowed, encryption), which key every provider call takes, and whether every call is recorded exactly once with the right tokens, key source and user (chat rounds, titles, embedding, search queries, scans, audio transcription, the web search's Tavily calls, failed calls), priced as the provider bills it (price tiers by the prompt's length, cached tokens at their own price, each provider's way of counting them), prompt caching only where rounds follow, a task's run keeping its cost - and that knowledge areas and knowledge sources cost nothing extra (moving a document, an unchanged push or an unchanged file of a source embeds nothing again) - up to the usage pages of the admin and the tenant admin. Builds an inventory of every provider call site. Asks which parts to review. Follows the shared review base (no hallucination, no over-optimisation, decision register, at most 10 verified findings, fix packages). Use when the user invokes /alpdesk-usage-review or asks whether usage and billing are correct - NOT a diff/branch/PR comparison."
---

## What this does

A read-only review of **what a provider call costs and who pays for it**: which model a tenant may use, which key a call
takes (the tenant's own or the admin's global one - then billed), whether the call is recorded once with the right
numbers, and whether the usage pages sum it up correctly. It starts from an **inventory of every provider call** and
follows each one to its usage record.

**It follows [.claude/review-base.md](../../review-base.md)**: rules, the decision register (E01: usage is deleted with
the tenant), no hallucination, no over-optimisation, what counts as a finding, the verifier, the budget, fix packages and
the report. Read it first; this skill adds parts, paths, key questions and its own report sections.

Relation to the other reviews: that provider keys are stored encrypted and never leave the system is
`alpdesk-security-review` part 4, the SSRF rules of own model servers part 6. Here the question is correctness: right
model, right key, right numbers. Since 1.9.0 `ai-features.md` promises price tiers and cache prices ("Models and
prices", "Usage and prices", "Prompt caching"): a call priced otherwise than those sections say - the tier chosen by
another length, a cached token billed twice or at the wrong price - is a finding; a provider rule the catalog
deliberately does not model (a second way of counting a tier, Gemini's explicit cached content) is not. Knowledge areas (`ai-features.md` "Knowledge areas")
matter here only for cost and attribution: who a call is recorded on (the user, or none for the client software and the
MCP server - the conversation's owner is no longer the usage's user; a tenant's several MCP keys are not told apart in
the usage, decided - it stays the tenant's; a call of connected software passed through to an MCP client costs no
provider call and is not recorded, decided; **an answer through a tenant's A2A agent records nothing** - no tokens of
ours, the agent's costs are the customer's (a2a.md "A tenant answering through its agent"): the chat, `/api/v1/chat`,
`ask_assistant`, `/a2a` and an assistant `prompt` step of such a tenant leave the usage untouched, as does the tool way
`<connection>__ask` and a task's `call_agent`; a question at `/a2a` of an ordinary tenant is recorded like
`ask_assistant`'s - the tenant's, no user), and which area operations must not cause a
provider call.

## Step A - choose the parts

- If the arguments name parts (`teil=1,3`, `alle` ...), take them.
- Otherwise **ask** with `AskUserQuestion` - one question, `multiSelect: true`, header "Teile", question "Welche Teile
  von Modellen, Keys und Verbrauch soll das Review prüfen?", options:
  - "1: Modellkatalog & Freigaben" - Modelle mit Preisen, Preisstufen und Cache-Preisen, eigene Modell-Server, welche Modelle ein Mandant nutzen darf, Standardmodelle
  - "2: Keys & Auswahl" - eigene und globale Provider-Keys, globalKeysAllowed, welcher Key bei welchem Aufruf
  - "3: Verbrauch erfassen & anzeigen" - jeder Provider-Aufruf, Tokens (gecachte getrennt), Betrag mit Stufe und Cache-Preis, Prompt-Caching, Key-Quelle, Benutzer, Kosten eines Aufgabenlaufs, Monatsauswertung für Admin und Mandanten-Admin, keine Zusatzkosten durch Wissensbereiche
  Nothing chosen (or "Other: alle") means all three. **The call-site inventory is always built** (agent of part 3, or the
  first chosen agent).

## Scope

Base paths `Backend/src/main/java/de/alpdesk/` (below `…/`), tests `Backend/src/test/groovy/de/alpdesk/`, frontend
`Frontend/src/app/`, management schema `Backend/src/main/resources/db/migration/V1__init.sql`.

| Part | Where |
|---|---|
| 1 Modellkatalog & Freigaben | `…/services/ai/{AiModelService,TenantModelService,ModelServers,BedrockClients,ChatOptions}.java` (`validateTierAndCache`), `…/entities/ai/{AiModel,AiProvider,ModelKind}.java` (tier and cache prices, `V13__price_tiers_and_cache.sql`), `…/repositories/ai/AiModelRepository.java`, `…/controller/ai/{AiModelController,AiSettingsController}.java`, `…/controller/tenant/TenantModelController.java`, `…/dto/ai/**`, `…/dto/tenant/TenantModels*.java`, `…/services/assistant/AssistantService.java` (default model), the embedding-model and reranking-model endpoints of `…/controller/document/**` (a reranker: kind `RERANK` only with provider `TEI`, an own server, `V16__reranking.sql`; left out of a tenant's models it is switched off); frontend `components/ai-model-list/**`, `components/tenant-list/tenant-models-dialog.component.ts`, `store/{ai-model,tenant-model,tenant-ai}/**`, `utils/{ai-model,tenant-model}.util.ts` |
| 2 Keys & Auswahl | `…/services/ai/{ProviderKeyService,ResolvedKey}.java`, `…/entities/ai/{ProviderKey,KeySource}.java`, `…/repositories/ai/ProviderKeyRepository.java`, `…/controller/ai/{ProviderKeyController,GlobalProviderKeyController}.java`, `…/controller/tenant/TenantProviderKeyController.java`, `…/entities/tenant/Tenant.java` (`globalKeysAllowed`); frontend `components/provider-key-list/**`, `components/tenant-detail/tenant-detail.component.ts` (its tab "Keys"), `store/provider-key/**` |
| 3 Verbrauch | `…/services/ai/{UsageService,AiModels,CallTokens,Spent,ChatOptions}.java` (`UsageService.amount` - the one formula; `CallTokens.of` - the providers' cache counts; `AiModels.request` with a cache key - what is cached), `…/services/agent/AgentLoop.java` (`cached`: one cache key per answer), `…/entities/ai/{UsageRecord,UsageSummary}.java` (`cache_read_tokens`, `cache_write_tokens`), `…/repositories/ai/UsageRecordRepository.java`, `…/controller/ai/{UsageAdminController,TenantAdminUsageController,Months}.java`, every caller of `AiModels` and `UsageService.record` - the scheduled tasks among them: `…/services/task/step/{AgentStep,PromptStep}.java` through `…/services/agent/AgentLoop.java` (`record`, `ToolContext.count` into the step's `StepContext.spent`; a run has no user), `…/services/task/{TaskRunner,TaskService,RunCosts}.java` and `…/repositories/task/TaskRunRepository.java` (a step's and a run's kept tokens and cost - tenant `V6__run_cost.sql`; a run from before computed) and `…/services/task/TaskDraftService.java` (the tenant admin as the user, the admin none); for the knowledge areas `…/services/chat/PreparedChat.java` (`userId()` from the `ConversationOwner`), `…/services/document/{KnowledgeSearch,DocumentService,DocumentIndexer}.java` (search embedding per scoped search; `moveToArea`, `push`, `deleteUploads`, `markReady`); frontend `components/usage/**`, `store/usage/**`, `utils/usage.util.ts` |

Starting points for the inventory (not its scope - every provider call counts, found by reading the callers):
`grep -rn "usage\.record" Backend/src/main/java` and
`grep -rln "AiModels" Backend/src/main/java`. One billed call is no model's: the web search
(`…/services/tools/{WebSearchTool,TavilySearch}.java` - Tavily, the only call outside `AiModels`), a catalog entry of
kind `WEB_SEARCH` priced per 1,000 searches, recorded by `UsageService.recordSearches` (the searches as input tokens).
The reranker (`AiModels.rerank` through `TeiScoringModel`, called by `KnowledgeSearch.rerank` - ai-features.md
"Reranking") is a provider call too: one per search of a tenant with a reranker, recorded by `UsageService.record`
with tokens estimated from the question and the texts (TEI counts none), the key source `GLOBAL` (an own server), the
asker as the user; a failed call is not recorded.

Specs: `controller.ai.AiModelSpec`, `ProviderKeySpec`, `controller.tenant.TenantModelSpec`, `services.ai.ModelServerSpec`,
`SentSpec`, `PricesAndCacheSpec` (tiers, cache prices, the providers' counts, which requests are cached), `controller.chat.ChatSpec`, `controller.document.DocumentSpec`, `ScanReadingSpec`, `KnowledgeAreaSpec`
(moving and an unchanged push embed nothing again), `controller.chat.WebSearchSpec` (own and global key, billed per
search, a failed one not), `controller.document.RerankingSpec` (the reranker's call billed once, estimated, a failed one not), `controller.api.v1.ChatApiSpec`, `services.task.TaskRunSpec` (a run's kept cost equal to its usage, unchanged by a later price, a failed step keeping its cost, a run from before computed), `TaskDraftSpec` (tokens per step and run), `TaskDocumentsSpec` ("a scan is read by the tenant's image model - its call counted into the step's cost", the extract rounds) (and every spec found
asserting on usage: `grep -rln "usage" Backend/src/test/groovy`); frontend `utils/{usage,ai-model,tenant-model,web-search}.util.spec.ts`,
`store/{provider-key,tenant-model}/*.reducer.spec.ts`.

## Key questions (every agent keeps them in mind; the report answers each one explicitly)

1. **Is every provider call recorded exactly once?** Chat rounds (streamed and not, every tool round), conversation
   titles, embedding of documents, embedding of search queries, scans and images, audio transcription, the MCP server's
   answers (`ask_assistant`) and its searches (`search_knowledge` embeds the query once and calls no chat model;
   `read_document` calls no provider), the API's answers without history, a scheduled task's agent, verify and prompt steps (every round, the
   second try after a broken answer too), its `extract` step (one call per document, the second round too, `knowledge:
   true` embedding the search query), the scans a `read_documents` step or a `call_tool` source reads through the
   image model (`ScanReader.read` with `Spent` - counted into the step's cost like a model's round, recorded without a
   user) and a task's draft (every round), the A2A server's answers (`/a2a` `SendMessage`, plain and streamed - as
   `ask_assistant`'s, the tenant's without a user) - and calls that fail after the provider
   already counted tokens; `write_file`, `call_tool` (target, a rows source) and `call_agent` call no provider, and a
   tenant answering through its A2A agent records nothing for an answer (spec: `controller.chat.AgentChatSpec`).
2. **With the right numbers?** Tokens in and out from the provider's answer (what if it sends none?), the cached ones
   apart and never in the input as well (`CallTokens`: Anthropic and Bedrock leave them out, OpenAI, Gemini and vLLM count them
   in - whole and streamed answers), key source, user (or none for the client software, background work and a task's
   run; a draft by a tenant admin with that user, by the admin none), model and tenant - and a run's kept tokens and
   cost per step (`task_run_step`) and in all (`task_run`) equal what the usage recorded, a failed step's too.
3. **Does every call take the right key?** The tenant's own before the global one, the global one only with
   `globalKeysAllowed`, the key of the model's provider (and own server), a deleted key no longer used, a missing key a
   clear refusal before work starts.
4. **May the tenant use the model?** Every entry point (chat, API, template, default model, embedding model, image and
   transcription model) checks the tenant's released models - and what happens to settings that name a model the admin
   later withdraws or deletes.
5. **Do knowledge areas cost only what they must?** Moving a document, a push that only changes the area and taking an
   area away cause no provider call; deleting an area's uploads and importing its ZIP again embeds every file once - as
   the frontend's confirmation says; a document replaced while it is embedded is embedded by both runs, each recorded
   once, and the older run's result is not used (`markReady`); the client software's and the MCP server's calls carry
   no user although their conversations now belong to a key. **A knowledge source** (`SourceSync`,
   `DocumentService.fromSource`, `DocumentRepository.replace`) embeds a file only when its text changed or its last
   embedding failed - a touched file (new time, same text), a source writing into another area and keeping its
   documents on removal cause no provider call; a save only lists (nothing read, nothing embedded); its embeddings and
   scans are recorded like an upload's, without a user; another store or folder embeds the new files once.
6. **Do the usage pages add up?** Month boundaries in `app.timezone`, prices applied as stored, admin and tenant admin
   see the same numbers for the same tenant, the tenant admin only its own.
7. **Is the web search billed like a model?** One record per search that answered (nothing found included, as Tavily
   bills it), none for a failed or refused one, at the entry's price per 1,000 searches and its key source; the
   tenant's own Tavily key before the admin's global one, the global one only with `globalKeysAllowed`; the catalog
   keeps `WEB_SEARCH` and `TAVILY` together (API, check constraint, dialog); the usage pages count searches as calls,
   never as tokens (`usageTotals`), and a tenant's model lists (chat, embedding, `/api/v1/models`) never show it.
8. **Is a call priced as the provider bills it?** The tier chosen by the prompt's whole length (input plus cache read
   plus cache write) and then for all of the call's tokens; a cache price left out being the input price of the call's
   tier; the catalog refusing a tier that is not whole (`model.tierIncomplete`); the dialog sending only what it
   shows; nothing priced differently in the usage, a run's cost and the frontend's sums.
9. **Is prompt caching asked where it pays and nowhere else?** Every round of the agent loop under one key per answer
   (Anthropic `cacheAutomatically`, Bedrock a cache point after the last user message, OpenAI `promptCacheKey`), the
   model seeing the same messages and tools; single
   calls with no next round (titles, transcripts, scans, images, drafts, embeddings) never cached; Gemini and own servers
   sent nothing for it; privacy.md saying what Anthropic keeps and how long.
10. **Is it tested?** Each key question has a spec (or the missing case and the spec it belongs in).

## Step 1 - Spawn the agents

**Every agent runs on Opus** - pass `model: "opus"` in each Agent call, whatever model the session runs on (decided
9 November 2026: a session on a larger model would hand it to every agent and use up the token budget at once). The
session's own model only verifies and joins the findings (Step 2). Never leave `model` out, and never start an agent as
a fork - a fork takes the session's model.

One agent per chosen part, in a single turn, plus the ground-truth run (review base section 1) of the specs above. Give
every agent "What this does", its part and paths, the key questions, sections 2 to 5 of the review base verbatim, and:

> You are reviewing the CURRENT STATE of a real codebase: not a diff. Read `CLAUDE.md`
> (only "Project overview", the sections marked "(always)" except "Open source" and "Changelog and version", "Backend tests (always)" and "Backend architecture" - "Frontend architecture" when your part has frontend paths; not "Repository and build", "CI" or the commands),
> of `docs/architecture/ai-features.md` only "Models and prices (admin)", "Model access", "Provider keys", "Own model
> servers", "Amazon Bedrock", "Reranking", "Web search", "Usage and prices" and "Prompt caching" (another section when
> a finding depends on it), of `tenancy.md` "Who does what", and
> `local-data/review/decisions.md` first, then every file of your part in full. Context: Micronaut 5 / Java 25,
> langchain4j (`TokenUsage` in every response), `AiModels` is the only class calling a model's provider (Tavily, the web
> search, is called by `TavilySearch`), the management
> database holds models, keys and usage. Specs use `FakeAiModels`. Read-only: do not edit files, do not run the
> application, never touch the developer's dev database, never call an AI provider.
> The agent of part 3 delivers the **call-site inventory**: one row per provider call - `Class.method` (path:line) · kind
> (chat / stream / embed / image / transcription / web search) · key resolved where · usage recorded where (path:line) or ❌ · user
> recorded · on failure recorded yes/no · verdict ✅ / ⚠️ / ❗. Every ⚠️/❗ row is also a finding. Agents of parts 1 and 2
> deliver one row per entry point: where the model and the key are chosen and checked.
> Severity for this review: **Kritisch** = usage of one tenant recorded on another, a tenant using the admin's global key
> without `globalKeysAllowed`, a tenant using a model it is not released for; **Hoch** = a kind of provider call never
> recorded, usage recorded twice, wrong numbers on the usage page, a refused key found only after expensive work, an
> area operation that embeds again although nothing changed.

## Step 2 - Synthesize

Follow **section 6 of the review base**, then the report as in **section 7**. The skill's own sections:

- **Provider-Aufrufe**: the call-site inventory, only ⚠️/❗ rows in full, the ✅ rows counted.
- **Key-Auswahl**: one line per entry point - model chosen from, key resolved by, refusal when none.
- **Kernfragen**: each key question answered in two or three sentences with the evidence (path:line, spec).
