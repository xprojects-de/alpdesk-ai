---
name: alpdesk-knowledge-review
description: "Focused non-diff, multi-agent review of the path of a document in Zirmo: upload in the frontend (single or ZIP archive), push by the client software (/api/v1/documents with externalId), a knowledge source syncing a folder of a file store (SFTP, FTPS, S3, SharePoint / OneDrive, an IMAP mailbox whose messages are folders of attachments and mail.txt, read only - listing, reading by hand or by schedule, replacing in place, deleting what vanished only after a complete listing, deleted and moved by hand kept), text extraction (Tika, allowed types by content), images and scans (ScanReader, DocumentImages), background embedding (DocumentIndexer, states, restart, failures, the tenants taking turns), changing the embedding model, the pgvector store (TenantEmbeddings) and the search (KnowledgeSearch, with the tenant's reranker on a TEI server reordering its candidates - switched per tenant, app.search.rerank-candidates, the hybrid order kept when it fails) up to the excerpt the chat gets - and the MCP server's own search and reading (search_knowledge, read_document per MCP key) - plus deleting and replacing, knowledge areas (which area a document lands in, moving it, deleting an area's uploads, that every search finds exactly the asker's areas through their groups) and the handbook (a static website per scope uploaded as a ZIP, replaced and deleted, who reads which, its short-lived address, kept away from the assistant). Answers its key questions, walks the path step by step and checks every failure case. Asks which parts to review. Follows the shared review base (no hallucination, no over-optimisation, decision register, at most 10 verified findings, fix packages). Use when the user invokes /alpdesk-knowledge-review or asks whether documents get into the company knowledge and out into the chat correctly - NOT a diff/branch/PR comparison."
---

## What this does

A read-only review of **one document's way into the company knowledge and out again**: uploaded, pushed or read
from a folder of a file store by a **knowledge source** ([knowledge-sources.md](../../../docs/architecture/knowledge-sources.md)), its type
recognised, its text (and its images or scanned pages) read, cut into pieces, embedded in the background, stored in the
tenant's database, found by a question, handed to the chat as an excerpt - and replaced, re-embedded, moved to another
knowledge area or deleted later. A document belongs to the company knowledge (everyone finds it) or to one knowledge
area (only the members of the groups and the keys assigned to it, and the tenant admins, find it). Beside it the
**handbook**: per scope - the whole tenant or one knowledge area - a finished static website uploaded as a ZIP, read by
people in an iframe on the page "Handbuch", never by the assistant (no search, tool, `/api/v1` or MCP route reaches it -
[doc-sites.md](../../../docs/architecture/doc-sites.md)).
It follows the path step by step, goes through every failure case on it, and looks at what runs at the same time on it
(two uploads of the same file, a push replacing a document that is still being embedded, a model change, a restart).

**It follows [.claude/review-base.md](../../review-base.md)**: rules, the decision register, no hallucination, no
over-optimisation, what counts as a finding, the verifier, the budget, fix packages and the report. Read it first; this
skill adds parts, paths, key questions and its own report sections.

Relation to the other reviews: what the chat does with the excerpts is `alpdesk-chat-review`; whether the upload can be
abused (ZIP traversal, bombs, XSS) is `alpdesk-security-review` part 5, and whether an attacker can reach another
area's documents is its part 7, and whether a handbook's HTML can run as the app or its address be forged its part 5 -
here the same code is read for correctness and robustness (a document in the wrong
area, an area's document lost or stuck, a search that misses what the asker may find, a website half replaced, lost
or shown to the wrong people); the cost of embedding and image reading is `alpdesk-usage-review`. Search quality (ranking, weights) is a
finding only with a concrete wrong result (a document that is never found, an excerpt of a deleted document) - tuning is
not this review's job.

## Step A - choose the parts

- If the arguments name parts (`teil=1,3`, `alle` ...), take them.
- Otherwise **ask** with `AskUserQuestion` - one question, `multiSelect: true`, header "Teile", question "Welche Teile
  des Firmenwissens soll das Review prüfen?", options:
  - "1: Hochladen & Lesen" - Einzel-Upload, ZIP-Import, Push per /api/v1 mit externalId, Tika, erlaubte Typen, Bilder und Scans
  - "2: Einbetten im Hintergrund" - DocumentIndexer, Zustände, Neustart, Fehler, Wechsel des Embedding-Modells, Mandanten abwechselnd
  - "3: Speichern & Suchen" - pgvector, KnowledgeSearch, Suchsprache des Mandanten (Volltext-Index), Reranker, Ersetzen und Löschen, was der Chat als Auszug bekommt, search_knowledge / read_document über MCP
  - "4: Wissensbereiche" - Bereich beim Hochladen/ZIP/Push, Verschieben, alle Uploads eines Bereichs löschen, Bereich löschen, was jeder Fragende (über seine Gruppen, jeder API- und MCP-Key) findet
  - "5: Handbuch" - Website je Bereich als ZIP: Hochladen, Ersetzen, Löschen, wer welche liest, Adresse, Auslieferung der Dateien, Anzeige im Frontend
  - "6: Wissensquellen" - Ordner einer Dateiablage ins Wissen: Listing, Lauf von Hand und nach Zeitplan, Ersetzen, Löschen nur nach vollständigem Listing, von Hand gelöscht/verschoben, anderer Bereich/Ordner/Muster, Entfernen mit/ohne Dokumente, Abbrechen, Frontend
  Four options per question at most: ask parts 1-4 in one question and parts 5 and 6 in a second (header "Teile 5-6",
  `multiSelect: true`). Nothing chosen (or "Other: alle") means all six.

## Scope

Base paths `Backend/src/main/java/de/alpdesk/` (below `…/`), tests `Backend/src/test/groovy/de/alpdesk/`, frontend
`Frontend/src/app/`, tenant schema `Backend/src/main/resources/db/tenant/`.

| Part | Where |
|---|---|
| 1 Hochladen & Lesen | `…/controller/document/{DocumentController,DocumentUploads,TenantKnowledgeController}.java`, `…/controller/api/v1/DocumentApiController.java`, `…/services/document/{DocumentService,DocumentArchive,DocumentTextExtractor,ScanReader,DocumentImages}.java` (`DocumentTextExtractor.TYPES`: the one list of types with their extensions, `.log` as `text/x-log`; `readableByName` only leaves files out, the content decides), `…/dto/document/**`; frontend `components/shared/document-panel/**`, `components/document-list/**`, `components/tenant-detail/tenant-knowledge.component.ts`, `store/document/**`, `services/document.service.ts`, `utils/document.util.ts` |
| 2 Einbetten | `…/services/document/{DocumentIndexer,DocumentService}.java` (`submit` / `next`: a queue per tenant, at most one job per tenant in the executor, the order within a tenant kept - knowledge-sources.md "The indexer, fair between tenants"), `…/services/ai/AiModels.java` (only the embedding and image calls), `…/entities/document/{Document,DocumentStatus,DocumentOrigin}.java`, `…/repositories/document/DocumentRepository.java`, `Application.java` (what runs at start), the embedding-model endpoints of `DocumentController`/`TenantKnowledgeController` |
| 3 Speichern & Suchen | `…/services/document/{TenantEmbeddings,KnowledgeSearch}.java`, `…/services/tools/{SimilaritySearchTool,ReadDocumentTool}.java`, `…/services/mcp/KnowledgeMcpTools.java` (search_knowledge, read_document - mcp.md "Searching the knowledge"), `…/repositories/document/DocumentRepository.java`, `db/tenant/V1__init.sql` and `V2__document_area.sql` (tables, indexes), `app.search.*` in `application.properties`; the search language (ai-features.md "The search language"): `…/entities/tenant/SearchLanguage.java`, `DocumentService.setSearchLanguage`, `DocumentIndexer.ensureTextIndex` and `TenantEmbeddings.ensureTextIndex` (`INDEX_LOCK_TIMEOUT`, `INDEX_ATTEMPTS`), the search-language endpoints of `DocumentController`/`TenantKnowledgeController`, frontend `components/shared/search-language-panel/**`; the reranker (ai-features.md "Reranking"): `…/services/ai/TeiScoringModel.java`, `AiModels.rerank` (only that call), `DocumentService.setRerankingModel`, `TenantModelService.assign` (a reranker left out switched off), the reranking-model endpoints of `DocumentController`/`TenantKnowledgeController`, `db/migration/V16__reranking.sql`, `Backend/podman/public/docker-compose.reranker{,-gpu}.yml`; frontend `components/shared/reranker-panel/**` |
| 4 Wissensbereiche | `…/services/document/{KnowledgeAreaService,KnowledgeScope,DocumentService,KnowledgeSearch,TenantEmbeddings}.java`, `…/repositories/document/{KnowledgeAreaRepository,DocumentRepository}.java`, `…/controller/document/{KnowledgeAreaController,KnowledgeAreaAdminController,DocumentController,TenantKnowledgeController}.java`, `…/controller/api/v1/DocumentApiController.java` (`area`), `…/services/chat/ChatService.java` and `…/services/tools/ToolContext.java` (only where the asker's scope is set), `…/services/tenant/UserGroupService.java` (a user's areas: the union of its groups'), `…/services/mcp/McpKeyService.java` (an MCP key's areas), `db/migration/V2__knowledge_areas.sql`, `V12__several_mcp_keys.sql` (`tenant_mcp_key_area`), `V7__user_groups.sql` (`group_area`), `db/tenant/V2__document_area.sql`; frontend `components/shared/document-panel/**` (area choice, upload target menu, `move-document-dialog`, `knowledge-areas-dialog`), `store/knowledge-area/**`, `services/knowledge-area.service.ts`, `utils/knowledge-area.util.ts` |
| 5 Handbuch | `…/services/docsite/{DocSiteService,DocSiteTokens}.java`, `…/controller/docsite/{DocSiteController,DocSiteAdminController,HandbookController,DocSiteFilesController}.java`, `…/repositories/docsite/DocSiteRepository.java`, `…/entities/docsite/DocSite.java`, `…/dto/docsite/**`, `…/services/document/{DocumentArchive,KnowledgeAreaService}.java` (reading the ZIP; `scopeOfUser` for who reads, `knowledgeArea.hasDocSite`), `…/security/ResponseHeaders.java` (only that the files keep their own headers), `…/services/tenant/TenantService.java` (the storage prefix deleted with the tenant), `db/tenant/V4__doc_site.sql`; frontend `components/shared/doc-sites-panel/**`, `components/handbook-page/**`, `store/doc-site/**`, `services/doc-site.service.ts`, `utils/doc-site.util.ts`, the menu entry "Handbuch" in `components/tenant-app/tenant-app.component.ts`, the tab in `components/tenant-detail/tenant-detail.component.ts` |
| 6 Wissensquellen | `…/services/document/{KnowledgeSourceService,SourceSync,SourceScheduler}.java`, `…/services/document/DocumentService.java` (`fromSource`, `deleteBySource`, `detachSource`, `moveSourceDocuments`, `requireEmbedding`, `readsImages`), `…/repositories/document/{KnowledgeSourceRepository,SourceFileRepository,DocumentRepository}.java` (`replace`, `deleteBySource`, `detachSource`, `moveSourceDocuments`), `…/entities/document/{KnowledgeSource,SourceFile,SourceSchedule,DocumentOrigin}.java`, `…/controller/document/{KnowledgeSourceController,KnowledgeSourceAdminController}.java`, `…/dto/document/{KnowledgeSourceRequest,KnowledgeSourceResponse,SourceFileResponse}.java`, `…/services/filestore/{FileStoreService,FileStoreSession,RemoteFile,NamePattern}.java` (only `readable`, `open`, `folderOf`, `folders`, `readWhole`, the listing's folders and hidden names, and that a store a source reads keeps its server and folder - for a mailbox also its folder, since another mailbox folder is other files), `…/services/filestore/MailboxStoreClient.java` (as a source reads it: messages as folders only with subfolders, `mail.txt` and the attachments as files, a message older than `days` vanished - so its documents go like a deleted file), `…/services/filestore/S3StoreClient.java` (as a source reads it: the listing through every page of `ListObjectsV2` - continuation token while truncated -, folders as common prefixes, a failure on a later page failing the whole listing, never a partial one), `…/services/filestore/GraphStoreClient.java` + `GraphAddresses.java` (as a source reads it: the listing through every `@odata.nextLink`, throttling 429/503 waited for once with `Retry-After`, the token renewed during a long run, a failure on a later page failing the whole listing; the library and folder a source reads kept), `…/services/filestore/{FileStoreClient,StoreFailures}.java` (the one failure picture of all types: what a source sees as "store failed" - no deletion - and what as "file refused"), `…/services/document/KnowledgeAreaService.java` (`knowledgeArea.usedBySource`), `db/migration/V14__knowledge_sources.sql`, `db/tenant/V7__source_files.sql`, `micronaut.executors.sources` in `application.properties`; frontend `components/shared/knowledge-sources-panel/**` (panel, source dialog, `folder-picker-dialog`, `remove-source-dialog`, `source-files-dialog`), `store/knowledge-source/**`, `services/knowledge-source.service.ts`, `utils/knowledge-source.util.ts`, the folders of `store/file-store/**` |

Specs: `controller.document.DocumentSpec`, `DocumentArchiveSpec`, `ScanReadingSpec`, `controller.api.v1.DocumentApiSpec`,
`services.document.DocumentArchiveFilesSpec`, `DocumentImagesSpec`, `DocumentTextExtractorSpec`, `KnowledgeSearchSpec`,
`KnowledgeSearchModeSpec`, `repositories.document.DocumentReadingSpec`, `controller.document.KnowledgeAreaSpec`,
`services.tools.ReadDocumentSpec`, `controller.mcp.McpKnowledgeSpec`, `controller.document.RerankingSpec`,
`RerankCandidatesSpec`, `services.ai.ModelServerSpec` (the TEI client), `controller.document.DocumentIndexerFairnessSpec`,
`EmbedAgainSpec` (every document embedded again with its model), `SearchLanguageSpec` (the language changed: the index
built again, nothing embedded), `DocumentDeleteFailureSpec` (a document deleted although its file or its chunks cannot
be removed);
frontend `utils/document.util.spec.ts`, `store/document/document.reducer.spec.ts`, `utils/knowledge-area.util.spec.ts`,
`store/knowledge-area/knowledge-area.reducer.spec.ts`; part 5: `controller.docsite.DocSiteSpec`, `DocSiteLimitSpec`,
`controller.tenant.UserGroupSpec` (areas through groups), frontend `utils/doc-site.util.spec.ts`,
`store/doc-site/doc-site.reducer.spec.ts`; part 6: `controller.document.KnowledgeSourceSpec`,
`services.document.SourceSyncSpec` (shared steps `SourceApi`, the store `FakeStoreClient`), `controller.filestore.FileStoreFoldersSpec`,
`SharePointStoreSpec` (a source on a library; `FakeMicrosoft` - whether it pages, throttles and lets a token expire), `MailboxStoreSpec` (a source on a mailbox: attachments and `mail.txt`
embedded, a forwarded mail, `days` as the window, nothing in the mailbox touched), frontend `utils/knowledge-source.util.spec.ts`,
`store/knowledge-source/knowledge-source.reducer.spec.ts`; `FileStoreServerSpec` only with `FILE_STORE_TEST`. Not in the ground-truth run: `RetrievalCorpusSpec`, `RetrievalEvaluationSpec`
(search-quality measurements, slow) - read them, don't run them.

## Key questions (every agent keeps them in mind; the report answers each one explicitly)

1. **Does every accepted document end in a final state?** READY or FAILED with a reason the user sees - never PENDING
   or PROCESSING forever (provider error, process killed mid-embedding, restart, a document deleted while embedding).
2. **Is the knowledge exactly the current state?** After replace (same path in a ZIP, same `externalId`, same content
   hash), re-embed and delete: no pieces of an old version, no duplicates, no orphaned rows or stored files.
3. **Is what is refused refused early and clearly?** Unsupported type, empty text, too large, broken archive: 400/415
   with a German-presentable message, before work or provider calls, and nothing half-stored.
4. **Does a model change re-embed everything** - and can a search during it mix vectors of two models (different
   dimensions, different spaces)?
5. **Does the search return what the chat may use?** Only READY documents of this tenant, excerpts that
   point to the right document and place. **Over MCP** (`KnowledgeMcpTools`): `search_knowledge` finds with the MCP
   key's areas and the tenant's number of results, each passage under its path and document id, a longer document's
   "read on from offset" pointing near the passage (`ReadDocumentTool.locate`); `/mcp` keeps nothing between calls, so
   `read_document` re-checks the key's areas by id at every call - a deleted, moved, not yet re-embedded or unknown
   document is "not found", the same answer for each; a short document whole, a long one in parts with where to read
   on; an offset beyond the end is answered, a negative one refused. **With a reranker**: it gets only the candidates
   the asker's scope found (never another tenant's or area's), at least as many as the results asked for and at most
   `app.search.rerank-candidates`; its scores are put back in the candidates' order by index; a failing, slow (30 s)
   or deactivated reranker keeps the hybrid order and never fails the search; its call is recorded once, a failed one
   not at all. **The search language** (ai-features.md "The search language"): the full-text half of the hybrid
   search reads the chunks in the tenant's language (SIMPLE by default) and the index must be the one of that
   language - indexing and searching use the same expression; a change embeds nothing again, it only builds the
   index for the new language in the background (`CREATE INDEX CONCURRENTLY` on a connection of its own, not the
   tenant's pool, under the tenant's lock); the search meanwhile is right and only slower, never empty and never in
   the old language; a build that fails leaves an invalid index that is dropped and tried again (five times, then
   an error in the admin's log and the next start); every start checks every tenant's index after the server took
   requests, so no build delays the start.
6. **Does every document land in the area meant for it - and stay there?** Upload, ZIP import and push into the
   chosen area (a push without `area` keeps a known document's area, `area=""` is the company knowledge); a ZIP's path
   unique within its area, duplicates counted within the area; a move changes no chunk and is found at once; deleting
   an area's uploads removes exactly those; an area holding documents is not deleted; the search finds exactly the
   company knowledge plus the asker's areas - also while a document is replaced (only READY documents, READY only for
   the version embedded) - for a user the union of its groups' areas, read from the database at every question; for
   an API key and for **each MCP key** its own areas (`tenant_mcp_key_area`, a tenant has several MCP keys); deleting
   an area takes it off every key.
7. **Is the handbook always one whole website, shown to the right people?** A replace switches the row to the new
   directory in one transaction and deletes the old one only after (a reader never sees half of each); a refused or
   failed upload deletes its own directory and leaves the old website; the limits (100 MB, 2000 entries, 50 MB a file,
   500 MB unpacked) and `index.html` checked before anything is kept, skipped files named with their reason; a ZIP of
   one folder served from it; one website per scope; the users' list and the addresses follow the company knowledge's
   rule (everyone's website, those of the user's areas through its groups, tenant admins every one), another area's
   website 404; an address one hour, renewed by the page five minutes before; an area with a website not deleted
   (409); nothing of a website reaches the assistant (no search, tool, `/api/v1` or MCP route).
8. **Does a knowledge source keep the knowledge equal to its folder - and nothing more?** A save only lists (nothing
   read, nothing costs); a run by hand reads everything open, a scheduled one at most 100 files and 250 MB; a file is
   open only when new or its size or time changed (to the millisecond); a known document is replaced in place (same
   id, its area as it is now), an unchanged text not embedded again; a refused file is remembered and keeps the
   document of its earlier version; a document deleted by hand (also by the client software) stays away until its
   file changes, one moved by hand stays moved; documents of vanished files are deleted **only after a complete
   listing** (for S3 and SharePoint every page of it) - never after a store failing or a page of its listing failing, a limit (20 levels, 20 000 files) or a cancel, never a file only left out
   by its extension; another area moves the documents still in the old one, another store or folder deletes them and
   lists anew, another pattern deletes at the next reading run what no longer matches; one run per source at a time,
   changes of store, folder, pattern or area and removing refused while it runs (409 `source.running`); removing
   deletes its documents or keeps them as uploads (`origin = UPLOAD`, `source_id` NULL); a store a source reads is
   neither deleted nor pointed at another server, folder or library, an area it writes into not deleted; the
   scheduler moves the next time on before it starts, not for a deactivated tenant.
9. **Is it tested?** Every state transition and failure case above has a spec with `FakeAiModels` (or the missing case
   and the spec it belongs in).

## Failure cases (every agent walks through them for its part)

Provider refuses the key or times out · provider returns fewer vectors than pieces or another dimension · Tika throws
or returns nothing · a scanned PDF with hundreds of pages · the same file uploaded twice at once · a push with an
`externalId` while the previous version is embedding · delete during embedding · restart during embedding · the
tenant's embedding model changed or removed from the tenant · the search language changed while documents embed, while
people search, twice in a row, or to the one it already is · the index build waiting past its lock timeout or the
server out of reach during it (an invalid index left behind) · a restart during the build · the tenant deactivated or deleted while embedding · a ZIP
with folders, duplicates, hidden files and odd name encodings · the same ZIP imported into two areas · a push that
changes text and area while the previous version is embedding · a document moved while its key pushes it · an area
deleted while an import into it runs · a user's or key's area taken away · a large tenant with a small area (pgvector's
iterative scan stops at `hnsw.max_scan_tuples`) · a user's group removed or the group's area taken away between two
questions · a website replaced while a user reads it · a ZIP without `index.html`, with only skipped files, over a limit,
of one folder, with odd name encodings · two first uploads of the same scope at once (decided: one directory left
unreached) · deleting the old directory fails after a replace · an area deleted while its website is uploaded · the
address expired while the page is open · the tenant deactivated with a website open · the reranker out of reach,
refusing its key, answering slower than 30 s, fewer scores than texts or an unknown index · the reranker deactivated,
taken from the tenant or deleted while chosen · `app.search.rerank-candidates` below the tenant's number of results ·
one tenant's import of thousands of files while another tenant uploads one · a document of a source deleted by hand,
by the client software or moved to another area · a file that grows between listing and reading · a file refused,
then changed · a store out of reach, its fingerprint changed, its check failed since the source was saved · a mailbox
message that falls out of the `days` window (its documents vanish like deleted files - intended, file-stores.md
"Mailbox") · a forwarded mail, an attachment without a name, two attachments of one name · an S3 listing or a
Graph listing failing on its second page (truncated, continuation token or `nextLink` refused, timeout) - must fail the
whole listing, never delete the documents of the files on the pages not read · Graph throttling (429/503) with a long
or missing `Retry-After` · the Graph token expiring during a long run · the client secret or S3 access key replaced or
revoked while a source reads the store · an S3 key with a trailing `/` (an empty "folder" object) or a key that
differs only in case · a SharePoint file checked out, or moved within the library between listing and reading · a folder
deeper than 20 levels or with more than 20 000 files · a run cancelled halfway · the source's store, folder, pattern or
area changed while it runs, or between its listing and its reading · the store's own folder or server changed while a
source reads it · two sources on folders inside one another · a restart during a run · the tenant without an
embedding model when the schedule comes · images while the tenant has no image model, and after it gets one.

## Step 1 - Spawn the agents

**Every agent runs on Opus** - pass `model: "opus"` in each Agent call, whatever model the session runs on (decided
9 November 2026: a session on a larger model would hand it to every agent and use up the token budget at once). The
session's own model only verifies and joins the findings (Step 2). Never leave `model` out, and never start an agent as
a fork - a fork takes the session's model.

One agent per chosen part, in a single turn, plus the ground-truth run (review base section 1) of the specs above. Give
every agent "What this does", its part and paths, the key questions, the failure cases, sections 2 to 5 of the review
base verbatim, and:

> You are reviewing the CURRENT STATE of a real codebase: not a diff. Read `CLAUDE.md`
> (only "Project overview", the sections marked "(always)" except "Open source" and "Changelog and version", "Backend tests (always)" and "Backend architecture" - "Frontend architecture" when your part has frontend paths; not "Repository and build", "CI" or the commands),
> of `docs/architecture/ai-features.md` only "Documents and embeddings", "Importing a ZIP archive", "Images and
> scans", "Documents pushed by the client software", "Knowledge areas", "Search", "Reranking" and "Reading on in a
> document" (another section when a finding depends on it), `tenancy.md` ("User groups"), - for part 5 -
> `doc-sites.md`, - for part 6 - `knowledge-sources.md` and `file-stores.md`, and
> `local-data/review/decisions.md` first, then every file of your part in full. Context: Micronaut 5 / Java 25,
> langchain4j, Apache Tika, PostgreSQL with pgvector, one database per tenant reached with plain JDBC, embedding in the
> background, Spock specs with `FakeAiModels` (word-hash embeddings). Read-only: do not edit files, do not run the
> application, never touch the developer's dev database, never call an AI provider.
> Deliver first a **walkthrough** of your part: one row per step - step · `Class.method` (path:line) · transaction yes/no
> · what happens on failure · verdict ✅ / ⚠️ / ❗. Every ⚠️/❗ row is also a finding.
> Severity for this review: **Kritisch** = a document or excerpt of another tenant reaches a
> chat, or a document of a knowledge area reaches someone not assigned to it; a document's content in the log; data
> loss of documents the user did not delete (also a source deleting its documents after an incomplete listing); an
> area's handbook website shown to someone not assigned to it;
> **Hoch** = a document stuck for good, an old version still found after
> replace or delete, a document landing in or moved to another area than meant, a model change leaving mixed vectors,
> a whole upload lost because of one bad file, memory exhausted by a normal-sized upload, a website half replaced or
> lost by a refused upload, its old directory reachable after a replace.

## Step 2 - Synthesize

Follow **section 6 of the review base**, then the report as in **section 7**. The skill's own sections:

- **Der Weg eines Dokuments**: the walkthroughs of the agents as one table from upload to excerpt, only ⚠️/❗ rows in
  full.
- **Zustände**: the document states and every transition with the line that makes it - and the transitions that are
  missing (a state without a way out).
- **Kernfragen**: each key question answered in two or three sentences with the evidence (path:line, spec).
- **Bereiche** (when part 4 ran): one row per way a document gets or changes its area - upload, ZIP import, push (new,
  known, without/with/empty `area`), move, delete uploads, delete area: `Weg` · `Bereich danach` · `geprüft in`
  (path:line, in the writing statement or before it) · `Spec` · Urteil ✅ / ⚠️ / ❗.
- **Wissensquelle** (when part 6 ran): one row per way - saved (listing), run by hand, scheduled run, file new /
  changed / touched / refused / vanished / deleted or moved by hand, another area / store / folder / pattern, the
  store's folder changed, cancelled, removed with and without documents, a run that failed: `Weg` · `Dokumente danach`
  · `source_file danach` · `geprüft in` (path:line) · `Spec` · Urteil ✅ / ⚠️ / ❗.
- **Handbuch** (when part 5 ran): one row per way - upload (first, replace, refused, failed), delete, delete the area,
  delete the tenant, list and address for a user, a tenant admin and the admin, a file served: `Weg` · `was danach gilt`
  (row, directory, who reads it) · `geprüft in` (path:line) · `Spec` · Urteil ✅ / ⚠️ / ❗.
