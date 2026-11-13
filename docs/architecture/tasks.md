# Scheduled tasks

Roadmap point 28. Built 21 October 2026: **package A** (the core - format, check, the steps `read_files`, `agent`,
`verify` and `send_mail`, the engine, runs, the start by hand), **package B** (schedule, `/api/v1`, MCP tools,
cancelling a run), **package C** (the page "Aufgaben": list, editor with the graph, runs, run view) and **package D**
(the step `prompt` - a chain of prompts with `choices` - and "Draft with AI"). Completion in the editor and graphic
editing are dropped (decided 27 October 2026); the cost per run is shown since 27 October 2026 (package E; its cheaper verification was measured and taken back the
same day); more file stores come as a point of their own. The plans are kept outside the repository.
Roadmap point 50 (from 4 November 2026) adds whole
documents: the step `read_documents`.

A **task** checks files by itself and reports only when something is wrong: it reads the new lines of files on one of
the tenant's file stores ([file-stores.md](file-stores.md)), lets the tenant's chat model check them against check
points, verifies the findings and mails them. For example: a client software's log files, checked every few hours.
A task checks *files*, not logs - no log format is known to the code.

## Who keeps tasks

- **The admin's switch per tenant** (`tenant.tasks_allowed`, migration V10, `tasksAllowed` in the tenant's master
  data; decided 21 October 2026): off for every tenant until the admin turns it on. While it is off, every endpoint of
  `/tenant/admin/tasks` answers **409 `task.notAllowed`** (never 403 - the frontend logs out on 403); the admin may
  still list, read, edit and delete the tenant's tasks under `/admin/tenants/{id}/tasks` (to prepare one), but **no run
  starts**, not by the admin either. Turning it off keeps the tasks and their runs; a run still waiting (queued, or to
  be taken up after a restart) ends `FAILED` with `task.notAllowed`, without a mail.
- **Tenant admins** keep their own tenant's tasks (`TenantAdminTaskController`), the admin every tenant's
  (`TaskAdminController`) - one `TaskService`, the caller as `StartedBy` (`TENANT_ADMIN`, `ADMIN`). Plain users see no
  tasks. Another tenant's task or run answers 404.

## The format

A task is written as **YAML 1.2** - JSON is YAML 1.2 too, so a task imported as JSON goes through the same parser and
its mistakes carry lines as well. It is stored twice in the management database (`task`): the **text as written**
(`source`, comments kept - nothing rewrites it) and the **compiled definition** (`definition`, the JSON of
`services.task.TaskDefinition`, every name resolved to its id). A run keeps the definition it ran with.

```yaml
version: 1
name: Log check interfaces
description: Checks the interfaces' logs   # optional: what it is for, for whoever may start it from outside
notify: [it@example.com]          # failed and unclear runs, and send_mail without its own recipients
steps:
  - id: logs
    type: read_files
    store: Logdateien             # the file store's name
    folder: ""                    # below the store's folder; left out: the store's folder
    pattern: "{yyyy-MM-dd}_*.log" # * and ?, dates as DateTimeFormatter writes them (y, M, d only)
    days: 2                       # the dates of today and the day before (1-31, default 1)
    onlyNew: true                 # default true: from where the last successful run stopped
    firstRun: end                 # start (default) or end: the task's first run reads nothing, only what comes after
    include: [ERROR, WARN]        # plain texts; a line needs one of them (none given: every line)
    exclude: [healthcheck]        # a line with one of them is left out
    mask:                         # replaced with *** before a line is kept
      - after: 'password="'       # the value after the text, up to the next " ' & < > , ; or space
      - regex: 'token=[A-Za-z0-9]+' # every match
  - id: check
    type: agent
    model: Claude Sonnet          # display or model name of a chat model the tenant may use
    input: [logs]                 # read_files steps
    instructions: |
      Optional text for the model.
    checks:
      - name: Interface
        severity: error           # error, warning or report
        instruction: Were there connection losses or timeouts to interface X?
  - id: verify
    type: verify
    of: check                     # the agent step whose findings it judges again; its check points
    next:                         # checked from top to bottom after the step, the first true one wins
      - if: verify.unclear > 0
        to: check                 # back: needs maxRounds (1-5) - how often it goes back
        maxRounds: 2
  - id: mail
    type: send_mail
    if: verify.errors > 0 || verify.warnings > 0
    input: [verify]               # agent or verify steps
    to: [it@example.com]          # left out: notify
    subject: Findings in the logs # left out: "<name>: findings"
```

- **Rules**: `version: 1`; unknown fields are an error (a typo never passes silently); a step's `id` is lower case
  letters, digits and `_`, beginning with a letter, unique; `input` and `of` name earlier steps of the right type -
  `input: []` is `task.fieldRequired` (an agent step would end without checking anything, a `call_tool` count as a
  target at the check and a source at run time; task review 9 November 2026); a
  condition names a step that has run at that point (an earlier one, an edge also its own step) and a count it gives;
  `next.to` names any step - one back (earlier or itself) needs `maxRounds`, one forward may not have it; the task's
  name at most 80 characters, unique per tenant (409 `task.nameTaken`); at most 20 steps, 30 check points per step, 20
  recipients, a text field 4000 characters, the whole text 64 KB.
- **Conditions** compare a count of a step's structured result: `<step>.<count> <op> <number>`, operators
  `> >= < <= == !=`, joined with `&&` and `||` (`&&` first), no brackets, no functions - the format stays data. The
  counts: `files`, `lines` (read_files), `errors`, `warnings`, `reports`, `unclear`, `ok` (agent, verify). A step that gave no
  result counts 0. A condition never reads a model's free text.
- **Model and store by name, not by id** (decided 21 October 2026): the text stays readable and exportable; the check
  resolves the name, the compiled definition keeps the id. Renaming a store makes the next check of the task fail with
  its line.
- **No anchors and aliases** (`&`, `*`): a task stays plain to read, step by step. **YAML 1.1's booleans** do not
  exist: `onlyNew: no` is refused (`task.wrongType`), a check point named `no` stays the text "no" - only `true` and
  `false` are booleans (SnakeYAML Engine composes nodes, the compiler reads every scalar itself).
- **The record component** is `notifyTo` (`notify` is a method of every Java object) and `verifies` for `of` (Micronaut's
  processor takes `of` for a factory method).

### Triggers

```yaml
triggers:
  - type: schedule
    every: 5h                       # 15m to 7d: m, h, d
  - type: schedule
    cron: "0 30 6 * * MON-FRI"      # six fields (second minute hour day month weekday), in app.timezone
```

A schedule has `every` or `cron`, one of the two; at most 10 triggers. **A task runs at most every 15 minutes**
(`TaskSchedule.MIN_INTERVAL`; a cron is checked over its next 50 times, `task.scheduleTooOften`) - every run asks a
model about the new lines. A task without triggers runs only by hand, from the client software or through MCP. Who may
start it from outside is not in its text: the API keys and MCP keys it is assigned to are set at the keys (access
stays out of what a tenant admin writes); the editor names them.

## The check

`TaskCompiler` turns the text into the definition or into every mistake found, each `TaskError` `{line, column, code,
params, message}` (code `task.<rule>`, translated by the frontend like every backend error; line 0 for the whole task):
1. **Syntax**: SnakeYAML Engine (YAML 1.2, no aliases), its problem with its position. Duplicate keys are refused by
   our own `Fields` (`task.duplicateField`) - composing nodes alone does not apply the engine's setting.
2. **The format**: fields, types, ids, inputs, conditions, edges.
3. **The tenant's references**: the store exists and is ready to read - `FileStoreService.readable`, the one rule the
   task code asks, decided by the store's type (SFTP and FTPS: its fingerprint confirmed, or its last check logged in -
   an FTPS certificate an installation's authority vouches for needs no fingerprint; S3 and SharePoint: its last
   check read the folder); else `task.storeNotReady`. Nothing
   else in `services.task` knows a type of store: a step reads through `FileStoreService.open` and the
   `FileStoreSession` (`list`, `read` from an offset), so a new type needs no change here; the model is an active chat
   model the tenant may use and has a key for; **the installation can send mail** (`app.mail.host` set) - else every
   task is refused with `task.mailNotConfigured`: a task that cannot report must not run silently.

`POST .../tasks/validate` answers `{valid, errors, steps}` (the steps for the graph: id, type, line, input, condition,
edges, a short line of what it does) and stores nothing. Saving runs the same check: **an invalid task is never stored**
(400 `task.invalid` with the first mistake; the check lists them all). A run's steps check their references again when
they run (a model taken away since: `model.unknownOrUnavailable`, a key gone: `providerKey.missing`) - the run fails
with its mail.

## Steps

One class per type in `services.task.step`, called by `TaskRunner` through a `switch` over the type. **The graph's
state holds the steps' structured results (`TaskResults`) and references only - never the files' content.**

- **`read_files`** (`ReadFilesStep`): lists the folder through `FileStoreService.open` (one connection for the step) -
  the task's folder stays relative in its definition and is placed below the store's folder as it is when the run
  reads (`FileStoreService.folderOf`), so a store moved to another folder takes its tasks along;
  keeps the names matching the pattern for the last `days` dates in `app.timezone` (matched without a regular
  expression, going back only to the last `*` - as `.*` a pattern of many `*` backtracked for minutes on every name it
  did not match and held a task thread), reads each from its **read
  position** (`task_read_position`: byte offset and line number per task, store and path), splits into lines (UTF-8,
  else Latin-1; without `\r`), keeps the lines with an `include` and without an `exclude`, **masks** them (below),
  cuts a line at 2000 characters, and stores them as a **run file** (`task_run_file`, `<number>\t<text>` per line). Only complete lines
  count: a last line without its line break may still be written - the position stops before it. A **rotated** file is
  read from its start: one smaller than its position, or one without a line break right before it - a position read
  up to always stops after a complete line, so a new file that grew past the old offset all but surely has other bytes
  there (one byte read along with the data, not as a request of its own: FTPS drops a connection whose transfer was
  broken off; not for a position `firstRun: end` set, which may stand inside a line). The one rotation this misses -
  a line of the new file ending exactly there - is read from the old position like before. A rotated file still empty
  (or not read for the step's limits) puts its position back to its start. **`firstRun: end`** (with `onlyNew`; default `start`, so a
  definition stored before it reads as it always did): while the task has **no read position on the store yet**, the
  step reads nothing and puts each file's position after its last complete line (found in the last 64 KB) - only
  what is written after the first run counts. A first run that finds no matching file yet leaves a position of the
  folder itself (it names no file), so the log that appears later is read from its start, not skipped by a second
  start at the end. It is the task's first run, not a file's: tomorrow's log, new later, is
  read from its start (decided 24 October 2026 - per file, the daily file would lose the hours before the first run of
  the day). The line numbers of a file started at its end count from there. Chosen because a first run reading
  logs with a long past whole ran into the 20 MB and judged a cut file. **`minLines`**
  (decided 24 October 2026): fewer new lines fail the run with `task.tooFewLines` - the failure mail tells the notify
  list. For a file that must keep growing, a cron's heartbeat: without new lines in any input no model is asked and
  every check point is ok, so "the cron stopped" could never be found by a check point. Not on a first run starting at the end. **`mask`**
  (`LineMasks`, decided 24 October 2026): a value goes as `***` before the line is kept - so neither the run file,
  the model nor the mail ever has it; `exclude` could only drop the whole line, and a log may carry passwords or
  tokens in its error lines. Two forms, each entry one of them: `after: TEXT` - the value after the
  text (spaces and tabs between them skipped: the task's text loses a trailing space, and `password: s3cret` would
  otherwise keep its password - review 6 November 2026)
  up to the next `"` `'` `&` `<` `>` `,` `;`, space or the line's end (`&` ends it in an HTML-escaped log, so
  `after: 'ApiPassword=&quot;'` works there) - for the usual name=value with no regular expression to get wrong;
  `regex:` - every match, for what has no fixed name. After filtering (a filter still sees the original), before
  cutting (a value at 2000 characters is never kept in half). A regex must compile and must not match an empty text
  (`task.maskRegexInvalid`, `task.maskRegexEmpty`); at most 20 masks of 200 characters. **Each regex gets 100 ms per
  line** (a `CharSequence` that throws past its deadline): Java 25 no longer backtracks for ever on `(a+)+$`, but still
  does with a backreference (`(a+)+\1$`: over 3 s on 31 characters); a mask too slow fails the run with
  `task.maskTooSlow`, the positions stay. Plain Java instead of RE2/J, which would refuse such patterns but is a
  library more and takes no backreferences. Limits per step: 50 files (above them the newest), 20 MB, 20 000
  kept lines - `truncated` says when something was left; a position never moves past what was read. The 20 MB are
  accepted as they are (decided 27 October 2026): more per run is rare, and the next run reads on. The new positions
  go into the result and are **written only after the run succeeded** - a failed run leaves them, so the next one reads
  the same lines and nothing is lost. The position belongs to the task and the file, not to the step: **two steps
  reading the same file** (e.g. split by `include`) share it, and the run keeps the one that read less
  (`TaskRunner.earliestPerFile`) - the file may grow between the two steps, and the larger position would lose those
  lines for the other step; the one that read more reads them again in the next run. **A run file is named after its
  file** - while every read_files step of the task reads one folder of one store; a task reading several gives it its
  store and folder too (`Webserver:logs/app.log`, `ReadFilesStep.runFileName`): the model, the evidence and the mail
  name a file by it, and two servers' `app.log` would otherwise be one file whose lines overwrite each other.
- **`agent`** (`AgentStep`): the tenant's chat model through the chat's `AgentLoop` (`ToolContext.Asker.TASK`, no user,
  no conversation) - so the tenant's own or the global key, the usage per round (with no user, like embedding) and the
  loop's rules hold. The run files go with the question **under their input steps** (`=== Step "<id>" ...`: a task's
  instructions name its steps, the files only their names - two steps reading the same file give two files of the same
  name), and **an input step that kept no lines is named as such** ("gave no lines this run"): left out, the model looked
  for it and called its check points unclear. **An input step cut at its limit says so**
  in its header (`truncated`): what comes after its last lines was not read this run, so nothing after them may be
  called missing - the model had to guess it from the last line's time. The files go whole up to 60 000 characters,
  each line as `<number>| text`; larger ones as an overview for `search_file` and `read_file_lines` - a file two steps
  gave is one file for the tools, the lines of both in the file's order (`StepInputs`); a model without tool calling
  gets their first lines instead, marked **CUT**: nobody judges the rest, so what depends on it is to be called unclear,
  never ok - **the only tools a step has**
  (`ToolContext.onlyTools`: another tool is neither offered nor run, no software tool either). The last answer must be
  the **structured result**, a JSON object (a code block around it is fine):
  `{"checks": [{"name", "status": "ok|finding|unclear", "note", "evidence": [{"file", "line"}]}]}` - every check point
  exactly once. `TaskFindings` checks it: an evidence line that is in none of the run files is dropped, a finding left
  without evidence counts as **unclear** (never as an error found); a finding counts as `errors`, `warnings` or
  `reports` by its check point's severity. **`severity: report`** (decided 24 October 2026) is no problem found but
  something to hand on - "list every other error": the model is told that "finding" means there is anything to report
  and its note is the report; `ok` only when there is nothing. Before it, such a check point had to pose as a question,
  and a model called "every other error" ok - its list was never mailed. A report's note has room for 6000
  characters (others 1000), and the model is told to read a file given as an overview line by line, not only by
  searching: a model reported the frequent errors of an overview it had searched and left out the rare ones,
  and its complete list then ran past 1000. An answer that is not the result gets one more round with the problem, then the step fails
  (`task.resultInvalid`). Chosen over the providers' JSON modes: it works the same with every provider and own model
  server. **Without lines to check no model is asked**: every check point is ok ("No new lines to check") - the
  pre-filters and "only new" are what keep a task affordable. 8 rounds (`app.task.agent-rounds`).
- **`verify`** (the same class): gets the findings of `of` with their evidence and the files of its input (left out: the
  inputs of `of`) and judges each again - confirmed (`finding`), dismissed (`ok`) or `unclear`; same result form. Its `model` may be a
  cheaper one than the agent's (the reference says so). **Tried and taken back** (package E, 27 October 2026): the
  verification given only the lines around the findings and at most 4 rounds cost a quarter, but was more often
  "unclear" where the old one confirmed, and dismissing a false finding was never measured - with the files and the
  same rounds as the agent step, as before, until a measurement proves a cheaper verification equal
 . When
  `of` found nothing, no model is asked. **Reports are handed on as `of` wrote them**: the verification is neither shown
  them nor asked about them (there is nothing to confirm or dismiss), and its result has them back in the order of the
  task's check points - so `verify.reports` counts them, and only reports means no verification is asked. Going back to `of` (an edge back), the agent step's next round gets the
  verification's notes ("Notes of the verification of your last findings"). Its notes are what the mail and the run
  view show under a finding, so it is told to state the problem itself, never that it checked it (a model otherwise
  mailed "the lines quoted match the logs").
- **`send_mail`** (`SendMailStep`, `TaskMails`): one mail with every check point of its inputs that is not ok - its
  status (a report as "report", in the neutral colour), note and evidence lines (at most 20, each cut at 300
  characters); whether a report alone sends it is the task's condition (`verify.reports > 0`). A mail that cannot be
  sent is logged and marks the run (`mail_failed`); it never fails the run.

## The step `read_documents` (point 50)

Whole documents instead of lines (decided 4 November 2026):
invoices, delivery notes, forms a company drops into a folder.

- **Format**: `store`, `folder` (as `read_files`), `pattern` - one name pattern or a list of up to 10 (`*` and `?`, no
  folders and no dates: a document is read once whatever its name says; `task.documentPatternInvalid`), matched
  **ignoring case** (`*.pdf` takes `INVOICE.PDF` - names come as senders wrote them), `subfolders` (default false: the
  folder alone; true: walked to 20 levels and 20 000 entries, as a knowledge source - the name of a document is its
  path below the folder). No `days`, `onlyNew` or `firstRun`: always only new or changed documents. No `input`.
- **Read once** (`ReadDocumentsStep`): a document is known by its absolute path in `task_read_position` - `byte_offset`
  its size, `modified_at` its time (the existing table: no migration). Same size and time, compared to the millisecond
  (a store's finer times would count every document as changed): skipped without being read. The positions go into
  the result as `read_files`' do, so `TaskRunner` saves them only after a successful run - a failed run reads the same
  documents again. At most 50 per run (`MAX_DOCUMENTS`), **the oldest first**; the rest wait for the next run, which
  still finds them unread (`truncated` set) - unlike `read_files`, which keeps the newest and loses the rest. A
  document to be read again (below) does not count to the 50, up to 500 of them (`MAX_READ_AGAIN`): else 50 scans
  waiting for an image model, the oldest, held back every newer document for good (task review 9 November 2026).
- **Text**: read whole (as `SourceSync`, at most `DocumentService.MAX_DOCUMENT_BYTES`); the type by content
  (`DocumentTextExtractor.detect`), the agreed types through Tika; an image, or a PDF with a page of (almost) no
  text, through the tenant's image model (`ScanReader`, looked up once per step and only when needed). Its calls go
  into the step's `Spent` (`ScanReader.read` with a `Spent`), so the run's cost includes them, besides the usage. Each
  document's text is a run file named by its path below the folder - its lines **not cut** at 2 000 characters (a
  document is no log); more than 20 000 lines is a reason, not a run file.
- **A document that cannot be read is no failure**: the document stays in the result with `problem` (the reason as
  text) and without a run file; the next step turns it into an unclear row (point 50, `extract`). What the document is
  to blame for - too large, empty, broken, a type not agreed, no text, too long, an image or PDF the image model is
  never shown because it cannot be decoded (`image.*`, `pdf.*` - else one broken photo failed every run) - keeps its
  position: reported once.
  What may be different next time keeps none, so the next run reads it again: the store refusing to read the file
  (`fileStore.*`, as a knowledge source leaves such a file open), no image model for a scan (`document.noScanModel` -
  once the admin assigns one, the scans are read), no key for it (`providerKey.missing`). Such a document's `source` is
  its name **without the checksum**: its unclear row now and its clear row later must differ, or a CSV would skip the
  clear one as already there (review 6 November 2026). A provider failing while it
  reads a scan fails the run like any step's provider call - never a document reported unreadable for an outage.
  Whole files are read by `FileStoreSession.readWhole` (shared with the knowledge sources).
- **Result**: `files` (documents taken, the only count), `truncated`, `fileIds`, `documents` (per document: `name`,
  `source` - its name and the first 12 hex characters of its content's SHA-256, so a changed document has another -,
  `fileId` or `problem`), `positions`.
- The stores a task reads documents from go into `task_file_store` like `read_files`' (`TaskService.writeStores`): such
  a store cannot be deleted while the task exists. The task list shows the step's store, patterns and `subfolders`.

## The step `extract` (point 50)

One row of fields per document (decided 4 November 2026). **The fields are the table's columns, not the document's
layout**: the model gets the whole text of one document and finds each value wherever it stands, under whatever name.

- **Format**: `model`, `instructions` (optional, up to 4 000 characters - the partner is the other party, how to account,
  what to do with several VAT rates), `input` (exactly one `read_documents` step, `task.inputOne`), `knowledge`
  (default false), `fields`: 1 to 30, each `name` (`[a-z][a-z0-9_]*`, unique, not `source` or `unclear` - the row's own
  columns; `task.fieldNameInvalid`, `task.fieldNameTaken`), `type` (`text`, `date`, `amount`, `number`, `percent`,
  `choice`; `task.fieldTypeUnknown`), `required` (default false), `description` (up to 500 characters, told to the
  model), `values` (a `choice`'s 2 to 20 words, refused for another type - `task.valuesInvalid`, `task.valuesNotTaken`).
  Kept as `Step.agent` (model, instructions - resolved like a prompt's model) and `Step.extract` (`Extract`: fields,
  knowledge). Counts: `rows`, `unclear`.
- **One document per question** (`ExtractStep`): for each document of the input step one call through the `AgentLoop`
  (the tenant's key, usage, cost into the step's `Spent`) - so the model cannot mix two documents' amounts, and a
  failure costs one document. The document goes as its run file (`StepInputs.filesText`: whole up to 60 000
  characters, else an overview to read with `search_file` and `read_file_lines`). The step checks a cancel between
  documents. The model and its key are resolved only when a document is to be asked about: a run without readable
  documents asks nothing.
- **`knowledge: true`** offers `similarity_search` and `read_document` besides the file tools, over the company
  knowledge alone (`KnowledgeScope.companyKnowledgeAnd([])` - a task has no user whose areas would count), with as many
  hits as the tenant's assistant settings give the chat. The search's embedding call is recorded as usage like the
  chat's; like the chat's, it is not counted into the step's cost (a fraction of a cent - not done on purpose).
- **The answer** (`TaskPrompts.EXTRACT_SYSTEM`): `{"values": {...}, "unclear": "<why>" or null}`. `ExtractedRow`
  checks every value by its field's type - a date `YYYY-MM-DD`, an amount, number or percent a number with `.` (a
  text "119.00" passes, "119,00" not - refused, not guessed; an exponent of two digits at most - "1e99999999" from a
  document steering the model would be a hundred million digits in the CSV, review 6 November 2026), a percent 0-100, a choice one of its words, a text at
  most 1 000 characters (a number is taken as its text) - and every required field present. A value the fields do not
  know is left out. Answer not a result, or a value not as asked: one more round with the reason
  (`TaskPrompts.extractAgain`). Failing again: the row keeps what passed and is unclear ("Not taken from the document:
  gross (...)"; the model's own reason first); no result at all: unclear "<model> gave no valid result".
- **A document `read_documents` could not read** becomes an unclear row with its reason, no model asked.
- **Rows** are stored in the tenant database (`task_run_row`, tenant migration V8 of 2.0.0: run, step, `source` - the document's
  path and checksum -, `values` as JSON, `unclear`), deleted with their run; the state keeps their ids (`rowIds`). The
  run's end is not changed by unclear rows (the task's `send_mail` with `if: rows.unclear > 0` says what is to be
  done) - unlike an agent's unclear check points.
- **Seen**: the run detail (`TaskRunResponse.rows`), `/api/v1/tasks/{id}/runs/{runId}` and MCP `task_run` once the run
  ended (`TaskService.rows`); a `send_mail` step taking the extract step mails its unclear rows (`- <source>: <why>`) and
  "N document(s) read, M row(s), K of them unclear." - so one mail serves to check (`if: rows.unclear > 0`) and to
  confirm (`if: rows.rows > 0`, or without `if`).

## The step `write_file` (point 50)

The rows of an `extract` step as a CSV in a store (decided 4 November 2026) - collected through the year and imported
into the bookkeeping software, or whatever a company does with a table.

- **Format**: `input` (exactly one `extract` step), `store` (one whose switch lets tasks write -
  [file-stores.md](file-stores.md) "Writing"; else `task.storeNotWritable` at the check and at the run), `file` (below
  the store's folder, ending in `.csv`, no `..`, no leading `/` - `task.fileInvalid`), `mode` (`append` default,
  `replace`), `separator` (`;` default, `,`), `decimal` (`,` default, `.`; not both `,` - `task.dialectInvalid`),
  `dateFormat` (`yMd`, default `yyyy-MM-dd`). Counts: `rows` (written), `skipped`.
- **The file's name** may hold placeholders (`TaskCompiler.FILE_PLACEHOLDER`): `{yyyy}`, `{MM}`, `{dd}` the run's date
  in `app.timezone`; `{<date field>:yyyy|MM|dd}` the row's date - an invoice of December written in January lands in
  last year's file; `{<choice field>}` the row's word - `{kind}` puts credit notes apart. **Never a text, amount or
  number field** (`task.filePlaceholderInvalid`): its value comes from a document through the model and must not name
  a file. A row without the date takes the run's, without the word `none`. The rows of one run may go into several
  files, at most 20 (`task.tooManyFiles`, the step fails before it writes anything).
- **Two tasks never write the same file** of a store (`task.fileTaken`, 409 at save, `TaskService.requireFreeFiles` -
  placeholders compared as written): on S3 and SharePoint a file is replaced whole, the two would drop each other's
  rows. The store goes into `task_file_store`: it cannot be deleted while the task exists.
- **Appending** (`WriteFileStep`): the folder listed (it must exist - `fileStore.folderNotFound`; nothing makes folders),
  the file read whole when it is there (at most 20 MB - `task.fileTooLarge`; a year's rows are far less), the rows
  whose `source` is not in it added, the whole file written back (`FileStoreSession.write`). **Nothing twice**: a run
  that wrote and then failed (a later step, a restart) has its read positions not moved on - the next run reads the
  same documents, extracts them again (the model is asked again - the cost of a rare case) and skips their rows. No
  new row: the file is not written. `replace`: only this run's rows (a run without rows leaves the file as it is).
- **Columns** (`CsvRows`): the fields in their order, then `unclear` and `source`. **Fields added at the end** of the
  task's list are taken: the header is written anew, the old rows get empty values; a field renamed, removed or moved
  is refused (`task.fileHeaderChanged`, the file's columns named) - the task names a new file. UTF-8 with a BOM, lines
  `\r\n`, a value quoted when it holds the separator, a quote or a line break. A file that is not UTF-8 (a spreadsheet
  saved it again as Windows-1252) is refused (`task.fileNotUtf8`) and left as it is - read as UTF-8 its umlauts would
  be written back broken.
- **Values**: an amount with two decimals, numbers and percentages in plain digits (never `1E+2`), all with the task's
  decimal separator; a date in its format; **a text beginning with `=`, `+`, `-`, `@`, a tab or a carriage return gets
  a leading `'`** (CSV injection - the documents come from outside; also the reason and the source). Typed values are
  the engine's, never the model's text.
- **Mail**: `send_mail` taking the step tells "N row(s) written into <files>, M already there.".
- **Specs**: `TaskSpec` (the rules as a data table, the store's switch at the check, `task.fileTaken`, a store written to
  in use), `TaskDocumentsSpec` (rows into the file of their year, the formula guard, nothing written without a new row,
  a changed document a row more, nothing twice after lost positions, a field added and one renamed, `replace`, the
  switch turned off, a missing folder), `step.CsvRowsSpec` (written and read back alike, every type's cell, the file's
  placeholders).

## The step `call_tool` (point 50)

The rows sent to a tool of a connected software ([mcp.md](mcp.md), point 2) - the bookkeeping software's own MCP server,
or a small adapter in front of its API (decided 4 November 2026: an MCP tool, not an HTTP step with a URL and a token -
the connection, its address and encrypted key, the internal hosts and the tool's schema are there already).

- **Format**: `input` (exactly one `extract` step), `connection` (its name, case does not matter), `tool` (a tool of it
  that is turned on and offered - `task.toolUnknown`), `each` (`row` default, `all` - `task.eachUnknown`), `with` (up to
  20 fixed arguments, plain values). Kept as `Step.callTool` (`CallTool`: the connection's id and name, the tool, `all`,
  `with`). Counts: `sent`, `failed`.
- **Arguments** (`ToolArguments`): a row's fields with a value by their names (amounts, numbers and percentages as JSON
  numbers, dates as ISO texts), `source`, and `with` (a value converted to the property's type - a number, a
  boolean). A field or `source` the tool's schema does not name is left out; a schema without properties takes
  everything.
  `each: all` sends `{"rows": [...], <with>}`. **Checked when the task is checked** against the tool's schema as the
  connection last read it: every required property given by a field, `source` or `with`; a field's type fitting the
  property's (text, date, choice - string; amount, percent - number; number - number or integer); `with` values of
  the property's type, and no `with` the schema does not name (a typo - the tool would never see it; a source's and a
  file tool's `with` alike); `all` only for a tool with an array `rows` (`task.toolArgumentsInvalid`, the reason
  named). A `with` the tool no longer names after a reload is left out of the call.
- **The approval**: a changing tool asks the user in the chat; a task has no user - **the tenant admin naming the tool
  in the task's text is the approval**, as writing into a store is through `write_file`. No group's level counts. The
  call goes through `McpConnectionService.callForTask` (the tool still there and turned on, else the run fails with
  `task.toolUnavailable`) and `McpClient.callTool` - its timeout and answer size as in the chat. Not checked again when
  the connection's tools are read anew: the next check of the task, or its run, says so. A source's tool (and its
  `fileTool`) that became a changing one since the task was saved - a tenant admin's correction, the software's
  `readOnlyHint` dropped at a reload - fails the run with `task.toolChanges`: nobody approved a changing call.
- **Repeats are the receiving software's matter** (decided 4 November 2026): the software knows its bookings, Zirmo
  does not, and keeps no list of what it sent. A row is sent again only when a run failed after sending it (the read
  positions move on after a successful run only) - every call carries `source`, which stays the same for the same
  document, so the software recognises the repeat (the test server's `create_booking` refuses a source booked
  before). **`call_tool` expects a tool that recognises a repeat.**
- **Unclear rows are never sent** - they wait in the mail and the CSV for a person. **A failing call** (the tool answers
  `isError` or an error, a value it does not accept, an answer too large) marks its row with the answer, the other rows
  are still sent. **The software as a whole failing** (out of reach, no answer within `app.ai.timeout`, our key refused
  or unreadable, its address refused) marks its row and every row after it with that reason, unsent - each would wait
  for the timeout again, holding one of the installation's two task threads for hours.
  **A connection that signs in with OAuth** ([mcp.md](mcp.md)) is called with its access token, renewed when needed,
  like in the chat (`McpConnectionService.credential`); a sign-in that has to be repeated (`oauth.signInNeeded`) is the
  software as a whole failing - every row unsent with "needs a sign-in", nothing reaches the software; at a source it
  fails the step. Either way the run ends
  `UNCLEAR` and its mail names every failed call ("<step>: <source>" with the tool's answer). A cancel stops between
  calls. The step's result keeps every call: source, `failed`, the tool's answer (2 000 characters at most) - the
  run view shows them; files the tool returns are dropped.
- **As a source** (no `input`, package 4, decided 4 November 2026): the first step, the company's own software (its
  API behind a small MCP server) giving what a task works on. **Only reading tools**
  (`task.toolChanges` - it runs without a person on every schedule). Exactly one form (`task.sourceFormRequired`):
  - **rows** - `fields` (as `extract`'s) and `key` (one of them - `task.keyUnknown`): the tool's answer is a JSON array
    of objects (found in its text; `task.toolAnswerInvalid` otherwise), at most 200 (`task.toolAnswerTooLarge` - the
    tool pages with `since`; a text `McpClient` cut at 100 000 characters is `task.toolAnswerTooLong`, not taken for no
    array). Each object's properties are checked
    as an extract's values (`ExtractedRow.check`, no second round - there is no model to ask): a value not as asked,
    or no key, makes the row unclear. `source` is `<connection>/<tool>#<key>` - the same invoice gives the same, so
    `write_file` skips a row it wrote and a target recognises a repeat. An invoice corrected in the software after
    `{lastRun}` comes again with the same source: the CSV keeps the first version, as for a changed document.
  - **documents** - `key` and `file: {tool, with}`: each listed object's file fetched with the file tool by its key
    (its argument of that name, plus the file tool's `with`) - **one file per answer**: `McpClient` takes a message of
    at most 8 MB (a file's base64 is one line the SDK reads in quadratic time), so about 6 MB a file. Each file is read
    like a store's document (`DocumentReader`, shared with `read_documents`); a row without key, an error or no file
    for it, a file too large for an answer (`mcpServer.error`, `mcpServer.tooLarge`) is a document with its reason -
    the software out of reach fails the step, so `{lastRun}` stays and the next run lists the same again. A file the
    tool returns as an `https` link (a temporary download) is fetched at once by `LinkDownloads` (mcp.md "Files in tool
    results": the connections' address rules, our key only to the connection's own host, a chat file's 50 MB); an
    expired or refused link is the document's reason, not the step's failure; a link whose server is out of reach,
    too slow or failing (5xx, 429 - `LinkDownloads.NotFetched.forNow`, `CallResult.linkFailedForNow`) fails the step
    (`task.linkFailedForNow`) - likewise a link to a resource of the software's own it gives no answer for when asked
    to read it (too slow, the connection lost; an error it answers is the document's reason) -, and so does a file
    read as a document to be read again - a scan without an image
    model or its key (`task.documentNotYet`): a source keeps no read positions, so with `{lastRun}` moved on it would
    never be asked for again (task review 9 November 2026).
    `mcpServer.requestFailed` fails the step too, unlike a target's row (decided 6 November 2026): `McpClient` cannot
    tell an HTTP 400 without a JSON-RPC error (the software not taking the key) from the connection breaking off after
    `initialize`, and a document lost silently to a network failure is worse than a source that fails loudly on one
    entry until the software fixes it - a software refusing a key as a JSON-RPC error is already a document with its
    reason. `source` is `<connection>/<tool>#<key>#<checksum>`. Every listed row's file is
    fetched in the same run - a source keeps no read positions, only `{lastRun}`. An `extract` step takes it as its
    input; `write_file`, a target `call_tool` and `send_mail` (its unclear rows) take a rows source
    (`task.inputWrongForm` otherwise). Counts: `rows`, `unclear` (rows) or `files` (documents) - a target's are `sent`,
    `failed`.
  - **`{lastRun:<format>}`** in `with` (`TaskCompiler.LAST_RUN`, checked as a `DateTimeFormatter` -
    `task.lastRunInvalid`): the start of the task's last run that ended `DONE` or `UNCLEAR`
    (`TaskRunRepository.lastSuccessfulStart`), in `app.timezone`; empty the first time. A run that fails after the
    call asks for the same again next time.
  - A tool failing (`isError`) or the software out of reach fails the step (`task.toolFailed`, the software's codes).
- **Specs**: `TaskDocumentsSpec` (every clear row sent with its fields, source and with; an unclear one held back; a
  refused call marked, the run unclear and mailed; `each: all`; the check: a tool not offered or turned off, a required
  argument missing, a field's type not fitting, `all` without a list `rows`, an unknown `each`; a source of rows typed
  and written into a CSV with `{lastRun}` empty then the date, nothing twice; a source of documents fetched one by one
  and extracted, one without a file kept with its reason; a file as a link fetched, an expired link the document's
  reason; a software whose OAuth sign-in has to be repeated; an answer no array or too long; the source's rules).
  `FakeMcpSoftware` refuses calls for the sources in `refusedSources` and answers a tool by `answers[name]`;
  `devtools/mcp-test-server` has `list_invoices`, `list_incoming_documents`, `get_document` and `create_booking`.

## The step `call_agent` (2.0.0, A2A)

- **Format**: `input` (exactly one `extract` step), `agent` (an A2A connection's name, case does not matter -
  `task.agentUnknown`, also for an MCP connection), `skill` (optional, one of its card's - `task.skillUnknown`), `each`
  (`row` default, `all`), `prompt` (optional, up to 2,000 characters), `with` (fixed values). The tenant needs the
  admin's A2A switch (`task.a2aNotAllowed`). Kept as `Step.callAgent` (`CallAgent`: the connection's id and name,
  skill, all, prompt, with). Counts `sent`, `failed`.
- **The message** (`CallAgentStep`): a text part (the prompt, or "Row of <task>") and a data part with the row's fields
  by name, its `source` and `with` - `each: all` sends `{rows: [...], <with>}`; our extension's metadata `tenant`,
  `language` (the search language), `task`, `run`, `skills` - no user, no groups. No context: each call its own.
- **The answer**: a Message's text, or a Task waited for up to `app.a2a.timeout` (a blocking answer as long); kept
  with the call (2,000 characters). The agent asking back (`task.agentAsksBack: ...`), a refused message and no end
  in time (`task.agentTimeout: ...`) mark the row; the run ends unclear and mails the rows (as call_tool's). The agent
  out of reach - or its OAuth sign-in to be repeated - marks every row still
  to go. Unclear rows are never sent; a cancel stops between calls. The connection gone or no longer A2A, or the
  switch off: the run fails with `task.agentUnavailable`. Naming the agent in the task's text is the approval.

## The step `prompt` (package D)

- **Format**: `model`, `prompt` (required, up to 4000 characters), `input` (any earlier `read_files`, `prompt`, `agent`
  or `verify` steps), optional `choices` (2 to 10 different words of `[a-z0-9_-]`, `task.choicesInvalid`). Kept in the
  definition as an `Agent` without check points (model, the prompt as its instructions) and `Step.choices`.
- **A chain**: its answer is free text and the next `prompt` (or an `agent` checking it, or `send_mail`) takes it as an
  input. `agent` and `verify` take `prompt` steps as input too; `send_mail` takes `prompt` steps and mails their answers.
- **Choices**: with `choices` the answer's last line is `CHOICE: <word>` (decided 21 October 2026 - the answer stays
  free text). A missing or unknown word gets one more round, then the step fails (`task.choiceInvalid`). A condition
  reads the word: `<step>.choice == <word>` / `!=` (`Condition.Comparison.word`), checked against the step's choices
  (`task.conditionChoiceUnknown`) - never the free text. The only count of a `prompt` step is `chars`.
- **Sent back**: an edge back to a `prompt` step (with `maxRounds`) gives its next round the answers of the `prompt`
  steps whose edges lead back to it ("Why you are asked again") - refine until it fits.
- **Kept like a file read** (`PromptStep`): the answer (without its `CHOICE` line, up to 20 000 characters - `truncated`
  beyond) is a run file `<step>.txt` with numbered lines - a later step reads it as it reads a log file, an agent's
  evidence may point into it, the graph's state keeps only its id. Result: `chars`, `choice`, `fileIds`, `truncated`.
- **Seen**: the run detail returns every answer per round (`answers`: step, round, choice, text); the run view shows it
  under its step, the graph's node its last choice ("→ rework"). `/api/v1/tasks/{id}/runs/{runId}` and MCP `task_run`
  / `run_task` give the answers a task **hands on** - the last answer of each `prompt` step a `send_mail` takes as its
  input (decided 21 October 2026).
- Through the chat's `AgentLoop` like `agent` (the same tools, rounds, key, usage, cancelling); `StepInputs` holds what
  both share (the run files of the inputs, how they go to the model).
- **Answered by the assistant** (2.0.0, `assistant: true`): the step searches the company knowledge and the knowledge
  areas named in `areas` (`task.areaUnknown`), uses the reading tools of the connections named in `software`
  (`task.softwareUnknown`; A2A agents as their `__ask` tool, with the A2A switch), the web search with `web: true`
  and a chat template named in `template` (`task.templateUnknown`) - `areas`, `software`, `web`, `template` without
  `assistant: true` are `task.fieldNotTaken`. Kept as `Step.assistant` (`AssistantAsk`); `PromptStep` runs it with the
  asker `TASK` (`ToolContext.taskConnections`, `McpTools.forTask`), reading tools only, no sandbox or files. A tenant
  answering through its A2A agent has the step answered by the agent (`task.agentAsksBack` when it asks back; the
  admin's A2A switch off fails the step with `a2a.notAllowed`, as the chat is refused): the
  agent gets the step's prompt, the findings and files of its inputs and, with `choices`, the request for the
  `CHOICE` line - not the rules our model gets (task and run go in the metadata). Such a tenant may leave `model` out
  of an assistant's prompt; any other gets `task.modelRequiredWithoutAgent` at the check - and at the run, when the
  tenant's agent stopped answering the chat since. An area, software or template deleted since the check fails the
  step (`task.assistantGone`) - answered without them, the run would end `DONE` as if nothing were missing. For the
  same reason an A2A agent among the software fails the step with `task.agentUnavailable` once the admin took A2A
  away from the tenant: its tool is no longer offered.

## Draft with AI (package D)

- `POST .../tasks/draft` `{description, source?, modelId?}` (tenant admin and admin) → `{source, valid, errors, steps,
  rounds}` - **never saved**. `GET .../tasks/draft-models` gives the tenant's chat models and the preselected one: the
  assistant's default model (decided 21 October 2026: changeable in the dialog). Without a model chosen and no default
  model: 409 `assistant.noDefaultModel`.
- **What the model is told** (`TaskDraftService`): the rules (YAML in one code block, the listed names only, ids,
  `maxRounds`, mail only when worth it, no anchors, keep what is not asked to change), **the format's reference**
  (English, the same text as the info button), **the tenant's names** - the file stores a task may read with their
  folders and the names of the 20 newest files in each (`FileStoreService.storesForDraft`; a store out of reach goes
  without them), the chat models with a key, whether mail is configured -, the description, and with `source` the
  current text to change. Never a file's content. The file names were added 22 October 2026:
  told the folders only, a model copied the reference's pattern `{yyyy-MM-dd}_*.log` for files named
  `<name>-<date>.log`, and the run would have read nothing.
- **The check corrects it**: the model's YAML (from its code block) goes through `TaskCompiler`; the mistakes with their
  lines go back - **at most 3 rounds**, no new round after `app.task.draft-timeout` (5 minutes; the frontend's long
  timeout is 6). The last version comes back with whatever mistakes are left.
- **Usage** per round as the tenant's, with the tenant admin as the user (the admin: none) - like a chat question.
- **The reference lives in the backend** (`resources/tasks/reference/en.md`, `de.md`, `TaskReference`, `GET
  .../tasks/reference?language=`; decided 21 October 2026): the info button and the draft read the same text;
  `TaskReferenceSpec` checks every field and step type is in each language.
- **Frontend**: "Mit KI entwerfen" in the editor and "Neue Aufgabe mit KI" in the list (`?draft=1` opens the dialog).
  The dialog (`task-draft-dialog`, `DialogSave`) stays open while the model works and when it fails; the draft replaces
  the editor's text as an unsaved change, the lines it wrote new marked (`changedLines`), its graph drawn at once.

## Engine

**langgraph4j** 1.9.6 (`langgraph4j-core`, `langgraph4j-postgres-saver`; MIT), as the prototype of 13 October 2026
proved:
- `TaskGraph` builds a `StateGraph` from the definition at the start of a run: a node per step, after each one
  conditional edge - the first `next` entry whose condition holds (one back only while `visits(target) <= maxRounds`),
  else the next step, else the end. **A step decides itself whether it is skipped** (its condition false, or it takes
  input and none of its inputs gave a result) and is recorded in its node; the edge functions only read the state,
  since langgraph4j may call one again when a run is resumed. The edges after a skipped step are not taken.
- State: `results` (step id → structured result), `visits` (step id → how often it ran), `skipped` (the step that
  skipped last) - plain maps, serialised as JSON (`JacksonStateSerializer`; since 1.9 the saver stores the serializer's
  text as it is, in the `TEXT` column `state_data`).
- **Checkpoints** in the tenant database: `PostgresSaver` on the tenant's `DataSource` with `createTables(false)`; its
  tables `lg4jthread` and `lg4jcheckpoint` come from our tenant migration `V5__task_runs.sql` (their DDL copied from the
  saver's schema v1.1 of 1.9.6 - **check it again with every new version**). The thread is `run-<id>`. Deleted at the end of every run
  (the saver would only mark them released).
- **Unclear**: a run whose edge back wanted to go on but had reached its limit, or whose last judging steps left a
  check point unclear, ends `UNCLEAR` and mails the notify list those check points - never "nothing found". Without
  an edge back a `send_mail` on errors would never send them, and the lines are not read again; a `send_mail` that
  already mailed them leaves the notify list a second mail (decided 23 October 2026).
- **A failed run** (a step threw) ends `FAILED` with the code and message of the failure (one of ours, else
  `task.stepFailed`), its step recorded `FAILED`, and **always mails the notify list** - a broken check is never silent.
- **Resuming**: on the server's start (`ServerStartupEvent`, after the tenant databases were migrated) every run still
  `QUEUED` or `RUNNING` is submitted again - only the databases of tenants with tasks are read (a run exists only with
  its task; the start waits for the scan, and opening every tenant's database made each spec context's start grow with
  the tenants of the whole suite); one with a checkpoint goes on after the last step that finished
  (`GraphInput.resume()`), a step interrupted midway runs again from its start (its tokens once more). The server
  takes requests while this scan still goes over the tenants: a run is submitted once per JVM (keyed by tenant and
  run - every tenant's database counts its runs from 1), so one a request started meanwhile is not carried out twice. What escapes a run outside its steps (its tenant database out of reach
  when it is taken up or its end is written) is logged as an error; the run stays open until the next start takes it
  up - or a cancel ends it at once ("Cancelling").
- **Retention** (`TaskRunCleanupJob`, `app.task.run-retention-days`, default 14 - decided 24 October 2026): every
  hour, the runs that ended longer ago go with their steps, run files and checkpoints, in every tenant with tasks (on a
  connection of its own, like the chat files' cleanup). An open run never; the read positions stay - they belong to the
  task, and the next run goes on where the last one stopped. **A task's last run that ended `DONE` or `UNCLEAR` stays**
  too: `{lastRun}` is its start, and a monthly task's source would otherwise be asked for everything on every run -
  a target sent the rows again, more than 200 objects failed every run (review 6 November 2026). A tenant admin
  deleting runs by hand deletes it as asked, and the next run's `{lastRun}` is empty. Empty keeps runs until a tenant
  admin deletes them. Why:
  every run keeps the lines its steps read, which adds up over time. The setting is checked at the start (`services.common.Retention`, shared with the chat files): anything but a
  positive number or empty stops it.
- Executor: `micronaut.executors.task` (fixed, 2 threads) - a run waits `QUEUED` while both are busy. Nothing holds a
  transaction or a database connection while a step waits for a server or a provider.
- **One run of a task at a time**: a unique partial index on `task_run (task_id) WHERE state IN ('QUEUED','RUNNING')`
  decides - a second start answers 409 `task.running` (package B: a scheduled start is then skipped and logged).

## Starting without a person (package B)

- **Schedule** (`TaskScheduler`, every minute, all tenants in one query - `task.next_run_at`, migration V10): a due
  task's next time moves on first (`moveNextRun`, only if it is still due then - a save meanwhile wins), then it starts
  with `StartedBy.SCHEDULE`. **A task still running is skipped** and logged (decided 13 October 2026); a tenant switched
  off or without the admin's switch starts nothing - its next time moves on all the same, so turning it on again does
  not make up for the times in between. **An interval counts from the last scheduled start** (a run of 20 minutes does
  not shift the rhythm); **a downtime is made up for by one run**, then from now (`TaskSchedule.afterStart`). Saving
  computes the next time from now; switching a task off clears it. A single instance, no cluster: no locking.
- **The client software** (`TaskApiController`, `/api/v1/tasks`, in Swagger): only the tasks assigned to its API key
  (`tenant_api_key_task`, `ApiKeyRequest.taskIds` like its knowledge areas - left out unchanged, 400
  `apiKey.taskUnknown` for another tenant's task; decided 13 October 2026). `GET /api/v1/tasks` (id, name, description, active, the
  last run's state and time), `POST /api/v1/tasks/{id}/runs` → **202** `{runId}` (409 `task.running`, `task.inactive`,
  `task.notAllowed`), `GET /api/v1/tasks/{id}/runs/{runId}` (state, error, **the findings** - the check points not ok of
  the last judging steps with their evidence lines - empty while the run is open, when an agent's findings are not yet
  verified; an agent's findings are left out too when its verify step failed, or was not reached in a run that broke
  off - they were never verified; not the lines read, not the steps). Any other task: 404. The run
  keeps the key (`task_run.started_by_key`). Not built (decided 21 October 2026): inputs handed to a run,
  the run as a stream of events - the software polls.
- **The description** (`description`, optional, up to 500 characters; decided 13 November 2026): what the task is for,
  for whoever may start it from outside - `GET /api/v1/tasks`, MCP `list_tasks` after the name, the A2A card's skill
  `task:<id>` ahead of how to start it. Kept in the definition (`TaskDefinition.description`, no column of its own); a
  definition stored before has none. Nothing of a run reads it.
- **The tenant's MCP server** (`TaskMcpTools`, [mcp.md](mcp.md)): `list_tasks`, `run_task`, `task_run` for the tasks
  assigned to the MCP key the call is made with (`tenant_mcp_key_task`, per key since 25 October 2026 - before, one
  switch per task for every key); `task_run` reads any run of such a task, whoever started it, like the API key's. `run_task` waits up to `app.task.mcp-wait` (60 s, decided 21 October 2026) for the
  run's end, else it answers with the run's id.
- **Cancelling** (`POST .../tasks/{id}/runs/{runId}/cancel`, tenant admin and admin): a queued run ends `CANCELLED` at
  once; a running one is marked (`task_run.cancel_requested`) and ends before its next step - and between the rounds of
  an agent step (`ToolContext.stopWhen`, asked by the `AgentLoop` before every round after the first: `answer.stopped`),
  and between the documents of a `read_documents` step, as between those of `extract` and the calls of `call_tool`.
  A running one no thread carries out any more (broken off outside its steps, see "Resuming") ends `CANCELLED` at
  once, its checkpoints deleted (`TaskRunner.endIfCarriedOutByNone`: a single instance, so what is not submitted in
  this JVM runs nowhere) - otherwise it would block every start of its task until the next start of the server.
  No mail, the read positions do not move, no failure is recorded for the run. 409 `task.runEnded` for one that has
  ended.

## The frontend (package C)

- **Where**: tenant admins get the page "Aufgaben" (`/app/tasks`, `tenantAdminGuard`) in their menu only when the
  tenant may have tasks (`AccountResponse.tasksAllowed`); the admin gets the tab "Aufgaben" on the tenant's page
  (`/dashboard/tenants/:id/tasks`, a note while the switch is off) and the switch "Darf Aufgaben anlegen" in the
  tenant's master data. The editor and the run view are routes of their own under both (`.../tasks/:taskId`,
  `.../tasks/:taskId/runs/:runId`); one slice `task` (`scope` like the other tenant slices) and the shared
  `tasks-panel` serve both.
- **The list** (`tasks-panel`): name, when it runs (`every 5h` as "alle 5h", a cron as "Zeitplan ...", none as "von
  Hand"), last run as a chip, next run; ⋮ start now, delete. "Neue Aufgabe" opens the editor with a template (the log
  check with the tenant's first file store); "Importieren" opens the editor with a file's text - nothing is stored
  before the editor saves it.
- **The editor** (`task-editor`, decided 14 October 2026 "text with the graph beside it"): left the text in CodeMirror 6
  (`task-source-editor`: YAML, the `--app-code-*` colours, a mistake's lines red, the chosen step's lines marked);
  right the graph (`task-graph`: Foblex Flow, laid out by dagre top to bottom with the edges back left out of the
  layout, one colour pair per kind - `--app-flow-*` -, conditions as chips on their edge, an edge back dashed with its
  limit, the step's line written by the frontend from the backend's `details`). The graph is drawn when the text is
  checked ("Prüfen", after a refused save, when the task is opened) - not live; it stays at the last text that
  compiled while the text has a mistake. A click on a node marks its lines; the cursor in a step's lines selects its
  node; every mistake is listed with its line, its backend code translated. Switch "Aktiv" and, of a saved task, the
  keys that may start it ("Starten dürfen: Client (API), Team (MCP)" - chosen at the key cards, `TaskResponse.keys`); ⋮ export (the text as `.yaml`), duplicate (a new task with the text), delete. Leaving with unsaved
  changes is asked about (`taskEditorGuard`). "Format" opens the reference (from the backend, Markdown per language,
  rendered like the chat's answers - see "Draft with AI").
- **Runs**: below the editor - start, by whom, state, tokens, cost, cancel an open one, delete one, delete all ended.
  "Jetzt starten" opens the new run's view.
- **The run view** (`task-run`): the definition's graph with each step's state, rounds and tokens, the run's cost in its
  head and each round's next to its tokens (cached ones included, the run's head says how many came from the cache); a
  click shows each round's result - the files and lines read, the check points with status, note and evidence lines, a failure's
  translated code. Asked again every 2 s while the run is open (`watchRun`, ended by leaving or logging out). A
  `read_documents` step shows the documents read, each unreadable one with a chip and its reason; an `extract` step its
  rows as a table (point 50): the document's source, one column per field that has a value in any row
  (`utils/task.util.ts` `rowColumns`), the unclear reason as a chip - at most 50 rows a run, so no paging. The graph
  has icons for both types (`description`, `table_rows`), an extract step counts as a model's step. A `write_file`
  step (icon `save`, an output like `send_mail`) shows how many rows it wrote and skipped and the files it wrote. A
  `call_tool` step (icon `api`; a source's short line says "source") shows how many calls went through and were
  refused, and every call with its source, a chip and the tool's answer.
- **Keys**: "Aufgaben" in the key dialog (`components/shared/key-dialog`) of the API keys and the MCP keys (only while
  the tenant may have tasks), on create and on a change - with the key's other rights.
- **Not built** (decided 21 October 2026): a separate test run ("Jetzt starten" is the test). Dropped (decided 27
  October 2026): completion from a JSON Schema in the editor, graphic editing.
- **Do not run `npm run lint:fix` blindly over new templates**: its i18n rule marks static text and attributes with
  `i18n`, which needs `$localize` (not loaded) - the page breaks with "$localize is not defined". Texts are keys,
  attributes bindings (`[fConnectorType]="'source'"`).

## Data

| What | Where | How long |
|------|-------|----------|
| The task (text, definition, version, active, next scheduled time) | management database `task` (cascade with the tenant) | until deleted |
| The tasks an API key may start | management database `tenant_api_key_task` (cascade with the key and the task) | until changed |
| The stores a task reads | management database `task_file_store` (`file_store_id` a foreign key checked at the commit - `RESTRICT` refused deleting a tenant whose task read a store): a store in use cannot be deleted (409 `fileStore.inUse` with the tasks' names) | written from the definition on every save |
| The runs: state, who started them, error, tokens, the definition | tenant database `task_run` (`task_id` without a foreign key) | until a tenant admin deletes them - one run or all ended runs of a task; with the task or the tenant they go too |
| What each step did, per round | tenant database `task_run_step` (result JSON, tokens, times) | with its run |

**A run keeps what its steps cost** (decided 27 October 2026, roadmap point 15): every call of a step is recorded
in the usage with its amount (`UsageService.record` returns it), and the step sums its calls' tokens - cached ones apart
- and amounts as they come (`StepContext.spent`, `services.ai.Spent`), so `task_run_step` and `task_run` keep them
(`cost`, `cache_read_tokens`, `cache_write_tokens`, V6 of the tenant schema). **A step that fails keeps what it
spent** - its calls are in the usage. Runs and steps from before (`cost` NULL) are computed when read, at the catalog's
current price without tiers (`RunCosts`); a model deleted since leaves such a step without a cost and the run
`costPartial` ("at least"). The usage pages stay the bill; whether a round was billed (the admin's global key) is not
known to a run. `/api/v1` carries no cost (`ApiTaskRunResponse`).

Deleting a task deletes its runs, files and read positions in the tenant database first (the service - no foreign key
across the databases), then the task; 409 `task.running` while a run of it is open. A run still open cannot be
deleted (409 `task.runOpen`). The deletion locks the task row (`FOR UPDATE`) before it looks for an open run, and every
start holds the row (`FOR KEY SHARE`, a save does not wait) while it creates its run: a start at the same moment either
created its run before (409) or finds the task gone (404 `task.notFound`) - a deleted task never leaves a run behind.

## API

Tenant admins `/tenant/admin/tasks` (`Roles.TENANT_ADMIN`), the admin the same under `/admin/tenants/{id}/tasks`
(`Roles.ADMIN`), both `@Hidden`:

| Method | Path | |
|--------|------|-|
| GET | `/tasks` | name, active, version, the last run |
| GET | `/tasks/{id}` | with `source` and the compiled steps |
| POST | `/tasks/validate` | `{source}` → `{valid, errors, steps}` |
| POST | `/tasks` | `{source, active}` → 201; 400 `task.invalid`, 409 `task.nameTaken` |
| PUT | `/tasks/{id}` | the same; the version goes up |
| DELETE | `/tasks/{id}` | with its runs; 409 `task.running` |
| POST | `/tasks/{id}/runs` | → **202** `{runId}`; 409 `task.notAllowed`, `task.inactive`, `task.running` |
| POST | `/tasks/{id}/runs/{runId}/cancel` | 204; 409 `task.runEnded` |
| GET | `/tasks/{id}/runs?limit=&offset=` | newest first, 50 by default, at most 200 |
| GET | `/tasks/{id}/runs/{runId}` | the run, its steps with their results, the definition's steps |
| DELETE | `/tasks/{id}/runs/{runId}`, `/tasks/{id}/runs` | one run (409 `task.runOpen` while open), all ended runs |

## Mails

English, like everything the backend writes (decided 21 October 2026), in the tenant's look (`BrandingService`) with the
daily report's building blocks (`resources/mail/`, footer `footer-task.html`): the findings of `send_mail`; "run failed"
(the step and why; the lines will be read again); "findings unclear". The findings are the model's notes and the log
lines, in whatever language they are.

## Specs

`controller.task.TaskSpec` (keeping tasks: CRUD for tenant admin and admin, the check's mistakes with their lines as a
data table, YAML and JSON, the switch, isolation, a store in use) and `services.task.TaskRunSpec` (runs: findings
mailed with their lines, only new lines and no model call without them, a line still being written, rotation, nothing
found, verify going back, unclear at the limit, unclear without an edge back, invented evidence, a broken answer once
and twice, a store out of reach, its folder gone, another key presented, a store moved to another folder, one run at a
time, a switched-off task, a run taken up at the start and one from its checkpoint, a step offered only its two tools,
a run submitted twice carried out once, runs of two tenants with the same id both carried out, a run breaking off outside its steps logged, deleting, the cost
per round, run, list and task list as the usage recorded it, unchanged by a later price or a deleted model, a run from
before computed at today's price, a failed step keeping its tokens and cost, and the run's tokens and amount in the
tenant's usage, on its own key); `TaskMailFailureSpec` (a
mail server out of reach: the run's end unchanged, `mailFailed` set); `TaskDocumentsSpec` (point 50: documents matched
ignoring case, subfolders or not, read once and again when changed - a finer time than milliseconds no change -, a
broken and an empty document reported once, a scan without image model read again once one is assigned, a file the
store refuses read again, a provider failing the run, a scan read by the image model with its cost in the step, 50 a
run the oldest first, a store out of reach; extract: rows typed and checked, seen in the run, over `/api/v1` and MCP,
mailed with the summary; one more round for no result and a wrong value, unclear after the second; the model's own
unclear; the knowledge search offered only with `knowledge: true`; a cancel between documents; nothing read asks no model);
`step.ExtractedRowSpec` (every type's values kept or named, a required field missing, a value not known left out, no
result). `FakeAiModels` answers an extract step from the document's lines `<field>: <value>` (`[[row-broken]]`,
`[[row-broken-twice]]`, `[[row-wrong]]`, `[[unclear <why>]]`). Both run with
`test.fake-store=true` (`FakeStoreClient`: an SFTP store in memory) and `app.mail.*` pointing at one GreenMail of the
test JVM (`TaskApi`). `FakeAiModels` answers a task's step with its structured result: `[[find TEXT]]` in a check
point's instruction is a finding with every line containing TEXT, `[[invent]]` names a line that is in no file,
`[[unclear-until N]]` keeps a verification unclear up to its round N, `brokenTaskAnswers` answers with no result. The
real SFTP, FTPS and S3 clients' `list` and `read` are in `FileStoreServerSpec` (`FILE_STORE_TEST`), with one real
run reading an S3 bucket; SharePoint's in `SharePointStoreSpec` against a fake of Microsoft, with one run reading a
library. Not covered by a spec:
stopping between an agent step's rounds (the fake answers a task's step in one round).
`services.task.TaskStartSpec` (package B: the schedule driven by the spec's time through `TaskScheduler.startDue`, a
due task skipped while running, one run after a downtime, a tenant without the switch; `/api/v1/tasks` with and
without the key's assignment, its findings - none after a failed verification; the MCP tools with `app.task.mcp-wait=3s`; cancelling) and
`TaskScheduleSpec` (the times, no context); `step.ReadFilesPatternSpec` (the pattern matched without a regex, many `*`
answering at once) and `step.StepInputsSpec` (a file cut for a model without tools marked CUT).
