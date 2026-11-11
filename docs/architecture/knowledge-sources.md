# Knowledge sources

Decided and built 27 October 2026 (roadmap point 8, first part). A
**knowledge source** is a folder of one of the tenant's [file stores](file-stores.md) - SFTP, FTPS, S3 or SharePoint /
OneDrive (the last two since 29 October 2026) - synced into the company knowledge or a knowledge area: its files, subfolders included,
become documents; changed ones are embedded again, vanished ones deleted. It reads only through
`FileStoreService.open` and a `FileStoreSession`, so every new type of store serves the sources as it serves the tasks.
Any server, bucket or library the app reaches by SFTP, FTPS, S3 or Microsoft Graph is read this way (pull).
Nothing in it is specific to one kind of server or device (decided 27 October 2026).

## Data

- **Management database** (`knowledge_source`, migration V14), kept like the stores: `tenant_id` (cascade),
  `name` (unique per tenant whatever the case - `source.nameTaken`), `file_store_id`, `folder` (relative to the
  store's folder, `''` its folder itself), `area_id` (null: the company knowledge), `pattern` (file names with `*` and
  `?`, null: every file), `schedule`, `active`, `next_run_at`, and the last run's outcome: `last_started_at`,
  `last_finished_at`, `last_error` (a code, null: it worked), `last_added`, `last_changed`, `last_deleted`,
  `last_refused`, `last_skipped`, `last_succeeded_at` (the last run that listed the whole tree), `open_files` and
  `open_bytes` (new and changed files not read yet). The references to the store and the area have no `ON DELETE`:
  their deletion is refused (409) before - and checked at the statement's end, so deleting the tenant, which cascades
  to stores, areas and sources at once, passes.
- **A run writes only its outcome, a save only the settings** (`KnowledgeSourceRepository.recordRun`,
  `updateSettings`) - never the whole row, so neither overwrites what the other wrote meanwhile.
- **Tenant database** (migration V7): `source_file` - per source and path the size and time the store listed, the
  document it became, or why there is none (`error`, a code). `document.origin` gains `SOURCE`, `document.source_id`
  names the source (no foreign key across databases). `external_id` stays empty: two sources may hold the same path.
  **A document deleted by hand** is recognised, not written: `source_file.document_id` is set NULL by the foreign key
  (`ON DELETE SET NULL`), so `document_id IS NULL AND error IS NULL` means deleted by hand - in the frontend, by the
  admin or by the client software (`DELETE /api/v1/documents/{id}`), without a change in `DocumentService.delete`.

## A run (`services.document.SourceSync`)

1. **Listing**: the folder's tree through one session - at most 20 levels deep and 20 000 entries (folders and names
   the pattern passes over count too; a cancel stops it at once). Left out: names
   starting with `.` (hidden, `RemoteFile.isHidden`), files the pattern does not match,
   and - counted as `last_skipped`, not read - **files whose extension is not one of an allowed type**
   (`DocumentTextExtractor.readableByName`; images only while the tenant has an image model). The extensions live
   with the allowed types in `DocumentTextExtractor.TYPES`, the one place they are named; `.log` is read as text
   (`text/x-log`). The check by content stays the gate - the extension only leaves files out.
2. **Compare** with `source_file`: a file is open when it is new, or its size or time changed (to the millisecond). A
   refused file and one deleted by hand are left alone until they change - except a file refused for want of an image
   model (`document.imageNeedsScanModel`, `document.noText` of a PDF), which is open again once the tenant has one,
   and a file the store could not read (a `fileStore.*` code), open again at every run - after all the other open
   files.
3. **Read** the open files by path, each in one read of its listed size (an FTPS transfer read to its end; one grown
   since it was listed is read on in parts, at most a byte beyond a document's limit). A path over 500 characters
   (`source.pathTooLong`) and a file over 50 MB (`document.tooLarge`) are refused from the listing, unread. Each file
   goes through `DocumentService.fromSource`: extracted like an upload, a known document **replaced in place**
   (`DocumentRepository.replace`: same id, its area as it is now - one moved by hand stays moved), an unchanged text not
   embedded again (no cost). A file the knowledge refuses (a `document.*` code) is recorded and the run goes on - it
   keeps the document of its earlier version, if any. A file the store cannot read (no right to it, locked) stays open
   for the next run and the others are read - one such file must not stop the source for good; the store's code
   (`fileStore.readFailed`) is shown with the source and recorded with the file (it keeps the document of its earlier
   version), so the next run tries it after the new and changed files: files that stay unreadable do not take a
   scheduled run's budget from them for good.
4. **Delete** the documents of vanished files - **only after a complete listing**: a listing that failed or hit a
   limit, a store out of reach, a cancelled run delete nothing. Every store type fails a listing it got only in part
   (an FTPS folder that may not be read, an S3 or Graph page that breaks off - file-stores.md "Sessions"). A file only left out by its extension is still there.
5. **Record** each file as it is done and the run's numbers with the source; nothing to resume after a restart.

**Three kinds of run**, one `SourceSync`, the difference only in step 3:

| Kind | Started by | Reads |
|------|-----------|-------|
| `LISTING` | saving a new source, or another store, folder or pattern | nothing - the source shows what is open ("4 900 Dateien, 3,2 GB") before anything costs; deletes nothing |
| `BY_HAND` | "Jetzt synchronisieren" (`POST .../run`, 202), after a confirmation with the numbers | everything open; can be cancelled (`POST .../cancel`: stops the listing at once or after the file it reads, `source.cancelled`) |
| `SCHEDULED` | the schedule | at most 100 files and 250 MB (`SourceSync.MAX_FILES_PER_RUN`, `MAX_BYTES_PER_RUN`) - a flood nobody started on purpose is read over several runs |

**Failures**: the store out of reach, another fingerprint, no embedding model (`document.chooseEmbeddingModel`, checked
before the listing) end the run with that code as `last_error` - nothing read, nothing deleted, the next regular run
tries again (no retry in between). The run logs a warning (source, tenant, code - no path), so the admin's application
log and the daily report show it; `last_succeeded_at` stays, and the frontend shows "Fehler seit ...". Anything else
(the tenant database out of reach) is an error in the log and `source.failed`.

## When it runs

- **`SourceScheduler`** once a minute, like the tasks' scheduler: the active sources due (`next_run_at`), each started
  once, its next time moved on first (so a downtime is made up for by one run); not for a deactivated tenant; not tied
  to the admin's task switch. A source still running is skipped and logged. **One run per source at a time**
  (`SourceSync.start`, a set in memory - one installation, no cluster); the runs go to the executor `sources` (2
  threads), so a slow store holds neither the tasks nor the indexing. A source's `running` is asked before its row is
  read: a run ending in between shows as still running for one more look, never as ended with the run before's outcome
  (the frontend and the specs stop polling once `running` is false).
- **The schedule is a fixed list** (`SourceSchedule`): `MANUAL` (by hand only), `HOURLY`, `EVERY_3_HOURS`,
  `EVERY_6_HOURS`, `EVERY_12_HOURS`, `DAILY` (at 2 o'clock) - each a cron in the code, in `app.timezone`, so the runs
  fall on the same times every day; no "every 9 hours" (24 is not divisible by 9). Not more often than hourly: every
  run lists the whole tree. Default `DAILY`.
- **`active = false` pauses**: no scheduled run, the schedule kept, a run by hand still works. `next_run_at` is
  computed anew when the schedule or the switch changes.

## Changing and removing

- **Another area**: the source's documents still in the old area move along (nothing embedded again); one moved by
  hand into a third area stays. New documents go into the new area.
- **Another store or folder**: other files - its documents and `source_file` rows are deleted, a listing run follows.
- **Another pattern**: a listing run; the next reading run deletes what no longer matches.
- **409 `source.running`** while a run is under way: removing it, and changing its store, folder, pattern or area -
  and no run starts while such a change is made (`SourceSync.holding`): one starting between the move and the saved
  settings would file new documents under the old area for good; a name, the schedule or the switch may change any
  time.
- **Removing asks** (`DELETE .../{id}`, `?keepDocuments=true`): delete its documents with it, or keep them as ordinary
  documents (`origin = UPLOAD`, `source_id` NULL, folder, name and area as they are, no longer kept up to date). The
  tenant database first, then the management row - a failure in between leaves a source to remove again, not
  documents nobody owns. While it is removed no run starts - neither the scheduler's nor one by hand (409
  `source.running`): it would write documents of a source that is gone.
- **A store or area a source uses cannot be deleted**, and a store a source reads keeps its files - server, bucket or
  site, folder and library (its next complete listing would delete the source's documents as vanished; renaming, a new
  password, a rotated S3 key and another SharePoint app are fine - file-stores.md "sameFiles"):
  409 `fileStore.usedBySource`, `knowledgeArea.usedBySource`
  with the sources' names. A store must be ready (its server confirmed) when a source is saved (409
  `source.storeNotReady`), and is checked again by every run.

## The indexer, fair between tenants

With the sources a large import is no longer rare (a large folder's first import), so `DocumentIndexer` takes the tenants in
turn: each has a queue of its own, the executor `indexing` holds at most one job per tenant, which embeds the tenant's
next document and queues itself again at the end. One tenant's import of thousands of files holds up another tenant
by one document, not by all of them; no thread waits on a tenant's lock. **Within a tenant the order stays as
queued** (decided 27 October 2026): its own upload during an import waits behind it - no error, the document shows
"Wartet", and the page "Firmenwissen" says how many documents wait (counted from the list it already loads).

**Not built on purpose**: two imports by hand of thousands of files hold both threads of the executor `sources` for
hours, and every scheduled run waits behind them - rare, and a waiting scheduled run costs nothing.

## API and frontend

- `/tenant/admin/knowledge-sources` (`controller.document.KnowledgeSourceController`, TENANT_ADMIN) and
  `/admin/tenants/{id}/knowledge-sources` (`KnowledgeSourceAdminController`, ADMIN): `GET`, `POST`, `PUT /{id}`,
  `DELETE /{id}`, `POST /{id}/run`, `POST /{id}/cancel`, `GET /{id}/files` (the files without a document, at most
  1000: refused with the code, or deleted by hand). Another tenant's source answers 404. Not in `/api/v1` - a setting,
  as a store is. The document list carries `sourceId`.
- **Frontend**: `components/shared/knowledge-sources-panel` (with a `TenantScope`) on the tenant admin's page
  "Firmenwissen" and the admin's tab, above the document list; slice `knowledgeSource`. Each source with its status
  chip, store and folder, area, schedule and next run, the last run's numbers, what is open; "Jetzt synchronisieren"
  (asks with the open files and their size, and that scans cost) or "Abbrechen" while it runs; in its menu edit, the
  files not read, remove (a dialog with "Dokumente behalten" and "Mit Dokumenten löschen"). The dialog warns before
  another store or folder is saved. **The folder is typed or chosen** (since 27 October 2026): a button next to the
  field opens `folder-picker-dialog` - the chosen store's subfolders level by level (`GET .../file-stores/{id}/folders`,
  through the slice `fileStore`), the path as steps to jump back, "Diesen Ordner übernehmen". **Folders inside one
  another** (two sources of the same store, one folder equal to or below the other's) are allowed - two patterns may
  keep them apart -, but the dialog names the other source: its files would stand twice in the knowledge
  (`utils/knowledge-source.util.ts` `overlappingSource`, decided 27 October 2026). **While a source runs, the browser reloads the list from the app every 3 seconds**
  (and the documents, which are its work) - the store itself sees a connection only when a run works. A document of a
  source shows "Dateiablage: <name>"; deleting one says it comes back when its file changes.

## Specs

`controller.document.KnowledgeSourceSpec` (HTTP: the tree, the kinds of run, changed / touched / vanished files,
refusals, limits per file and path, failures that delete nothing, deleted and moved by hand, the client software
deleting, another area / folder / pattern, removing with and without documents, running and cancelling, no embedding
model, a store not ready, every validation rule, names, defaults and the switch, tenant isolation, a store and an area
in use, the admin's API), `services.document.SourceSyncSpec` (the scheduled limits of 100 files and 250 MB, the
scheduler, a deactivated tenant, each schedule's next time), `controller.document.DocumentIndexerFairnessSpec`,
`DocumentTextExtractorSpec` (the extensions) - all through `FakeStoreClient` (`test.fake-store=true`) and
`FakeAiModels`; shared steps in `services.document.SourceApi`. Listing folders on real servers:
`FileStoreServerSpec` (`FILE_STORE_TEST`).
