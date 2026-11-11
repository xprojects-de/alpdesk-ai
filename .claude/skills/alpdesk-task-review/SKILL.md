---
name: alpdesk-task-review
description: "Focused non-diff, multi-agent review of the path of a scheduled task in Zirmo: the text a tenant admin writes (YAML/JSON, checked against the fixed format with lines, names resolved to ids, never run as code), the engine (langgraph4j graph, checkpoints in the tenant database, a step's record per round, resuming after a restart, cancelling between steps and rounds), the steps (read_files from a file store with read positions, read_documents - every document of a folder once, scans through the image model -, extract (a table's row per document, typed, a second round, unclear rows), write_file (rows appended to a CSV of a store with the switch, nothing twice, the old file never lost), call_tool as a target (rows to a connected software's tool, unclear rows held back) and as a source (a reading tool's rows or documents, {lastRun}), agent and verify through the chat's AgentLoop with only search_file and read_file_lines, prompt chains with choices, send_mail), how a run starts without a person (schedule every 15 minutes at most, the client software with a key the task is assigned to, the tenant's MCP server for a task assigned to the MCP key, the admin's switch per tenant), the mails (findings, failed, unclear), Draft with AI (checked, never saved) - what a run cost (each step's tokens, cached ones apart, and its cost as the usage recorded it - kept when the step fails; a run from before computed at today's price) - and the tenants' file stores (SFTP/FTPS: the server proves itself by its fingerprint before the password goes out; S3 and SharePoint / OneDrive: ready once a check read the folder; an IMAP mailbox read only, its messages as folders; another server needs the password again, internal hosts only by the operator's list, a store a task or a knowledge source reads cannot be deleted, a task writes only into a store with the switch). Answers its key questions, walks the path step by step and checks every failure case (store out of reach, model gone, provider fails midway, answer without result, restart mid-run, cancel, mail not sendable). Asks which parts to review. Follows the shared review base (no hallucination, no over-optimisation, decision register, at most 10 verified findings, fix packages). Use when the user invokes /alpdesk-task-review or asks whether scheduled tasks and file stores behave correctly - NOT a diff/branch/PR comparison."
---

## What this does

A read-only review of **one task's way from its text to its mail**: written by a tenant admin as YAML (or JSON),
checked against the fixed format with every mistake at its line and the tenant's names resolved to ids, stored twice
(the text as written, the compiled definition), run as a langgraph4j graph with a checkpoint after every step and our
own record of every step and round in the tenant database, its steps reading the new lines of a file store's files
from the last read position - or every document of a folder once (`read_documents`, scans read by the image model),
or a connected software's rows or documents (`call_tool` as a source) -, asking the tenant's chat model through the
chat's `AgentLoop` with the structured result as the only acceptable answer, taking a table's row out of every
document (`extract`, checked by type, one more round, else unclear), writing the rows into a CSV of a store
(`write_file`: nothing twice by `source`, the old file never lost) or sending them to a software's tool (`call_tool`,
unclear rows held back), verifying the findings, chaining prompts with choices, and mailing what is not ok - or
that the run failed or stayed unclear. A run starts by hand, by its schedule, from the client software with a key the
task is assigned to, or through the tenant's MCP server; only while the admin's switch lets the tenant have tasks.
**A file store** is the server, bucket, library or mailbox a task reads (SFTP, FTPS, S3, SharePoint / OneDrive, IMAP):
an SFTP or FTPS server proves itself by its fingerprint before the password goes out, an S3 or SharePoint store over
HTTPS - it is ready once its last check read the folder; a mailbox is read only, its messages folders of attachments
and `mail.txt`; a task writes only into a store whose switch "Aufgaben dürfen schreiben" is on, never into a mailbox; a
store a task reads cannot be deleted - [tasks.md](../../../docs/architecture/tasks.md),
[file-stores.md](../../../docs/architecture/file-stores.md). The review follows the path step by step, goes through
every failure case on it, and checks what is left behind when it breaks off: the run's state, the read positions, the
checkpoints, the mail.

**It follows [.claude/review-base.md](../../review-base.md)**: rules, the decision register, no hallucination, no
over-optimisation, what counts as a finding, the verifier, the budget, fix packages and the report. Read it first; this
skill adds parts, paths, key questions and its own report sections.

Relation to the other reviews: whether a task's text or a store's host can reach where it must not (SSRF through the
store's address, a step's tools, log lines steering the model, a run started by someone not allowed) is
`alpdesk-security-review`; what disappears with a store, a task, a key or the tenant is `alpdesk-tenancy-review` part 3;
whether every provider call of a step and a draft is counted and billed is `alpdesk-usage-review`; the `AgentLoop`
itself, as the chat uses it, is `alpdesk-chat-review` - here only as a task's step uses it (`Asker.TASK`, `onlyTools`,
`stopWhen`). Here the question is: is a task checked, run, recorded and reported correctly, and does it stay usable
after every failure - nothing lost, nothing read twice, nothing silent.

## Step A - choose the parts

- If the arguments name parts (`teil=1,3`, `alle` ...), take them.
- Otherwise **ask** with `AskUserQuestion` - `AskUserQuestion` takes at most four options per question, so **two
  questions** in one call, both `multiSelect: true`:
  - header "Teile 1–3", question "Welche Teile der Aufgaben soll das Review prüfen (Teile 1–3)? Nichts gewählt in
    beiden Fragen = alle sechs.", options:
    - "1: Format & Prüfung" - der Compiler (YAML 1.2, Felder, Ids, Inputs, Bedingungen, Kanten, Trigger), die Namen des Mandanten (Store, Modell, Mail), die Referenz, der Entwurf mit KI
    - "2: Engine & Schritte" - der Graph, Checkpoints, der Runner (Record je Schritt und Runde, Wiederaufnahme, Abbruch, Aufbewahrung der Läufe), read_files mit Lesepositionen und Masken, agent/verify, prompt mit choices und mit assistant: true (Wissen, Software, Websuche, Vorlage; bei einem antwortenden Agenten über den Agenten), send_mail, die Mails, Tokens und Kosten je Schritt und Lauf
    - "3: Start ohne Person" - der Schalter des Admins, der Scheduler, /api/v1/tasks mit den Aufgaben eines Keys, die MCP-Werkzeuge, der A2A-Server (metadata.task, StartedBy.A2A, der Lauf als A2A-Task), ein Lauf je Aufgabe
  - header "Teile 4–6", question "Welche Teile der Aufgaben soll das Review prüfen (Teile 4–6)?", options:
    - "4: Dateiablagen" - SFTP/FTPS: Fingerprint vor dem Passwort; S3 und SharePoint: bereit nach erfolgreicher Prüfung; IMAP-Postfach nur lesend; anderer Server, interne Hosts, Lesen, Schreiben je Typ (temporäre Datei, Umbenennen), der Schalter "Aufgaben dürfen schreiben", Ordner auswählen
    - "5: Dokumente & Tabellen" - read_documents (jedes Dokument einmal, Scans über das Bildmodell), extract (Zeile je Dokument, Typen, zweite Runde, unklar), write_file (CSV je Datei-Platzhalter, nichts doppelt, Ersetzen ohne Verlust), call_tool als Ziel (Zeilen an ein Werkzeug, unklare zurückgehalten, die Software als Ganzes ausgefallen) und als Quelle (Zeilen oder Dokumente einer lesenden Software, {lastRun}, ein inzwischen ändernd gewordenes Werkzeug), call_agent (Zeilen als Daten an einen A2A-Agenten, je Zeile oder alle, Rückfrage schlägt die Zeile fehl, der Agent als Ganzes ausgefallen), die Zeilen in Run-View, /api/v1 und task_run
    - "6: Frontend" - die Seiten "Aufgaben" (Editor mit Graph, Läufe mit Kosten, Run-View, Guard) und "Dateiablagen" (ein Formular, Felder je Typ, Passwort nur wenn nötig, Ordner-Auswahl), die Spalte "Aufgaben" der API-Keys, Polling, Abmelden
  Nothing chosen in either question (or "Other: alle") means all six. Part 6 was the second half of part 4 until
  November 2026 (one agent could no longer read both).

## Scope

Base paths `Backend/src/main/java/de/alpdesk/` (below `…/`), tests `Backend/src/test/groovy/de/alpdesk/`, frontend
`Frontend/src/app/`, management schema `Backend/src/main/resources/db/migration/V10__file_stores_and_tasks.sql`
(`file_store`, `tenant.tasks_allowed`, `task`, `task_file_store` - deferred foreign key -, `tenant_api_key_task`),
tenant schema `Backend/src/main/resources/db/tenant/V5__task_runs.sql` (`task_run` with its partial unique index,
`task_run_step`, `task_run_file`, `task_read_position`, langgraph4j's `lg4jthread` / `lg4jcheckpoint`) and
`V6__run_cost.sql` (cached tokens and `cost` per step and run - NULL for a run from before), management
`V15__file_store_types.sql` (`S3`, `SHAREPOINT`), `V17__release_2_0_0.sql` (`task_write`; `IMAP`, `security`,
`mailbox`, `days` - 2.0.0's migrations are one per set), tenant `V8__release_2_0_0.sql` (`task_run_row`: an extract step's or a source's
rows, `ON DELETE CASCADE` with the run; `task_read_position.modified_at` for documents), settings
`app.task.*`, `app.file-store.internal-hosts`, `micronaut.executors.task` and `app.mail.*` in `application.properties`.

| Part | Where |
|---|---|
| 1 Format & Prüfung | `…/services/task/{TaskCompiler,Condition,TaskDefinition,TaskError,TaskResults,TaskSchedule,TaskReference,TaskDraftService}.java`, `Backend/src/main/resources/tasks/reference/{en,de}.md`, `…/services/filestore/FileStoreService.java` (`findByName`, `readable`, `folderBelow`, `storesForDraft`), `…/services/ai/{TenantModelService,ProviderKeyService}.java` (only as the check resolves a model and its key), `…/mail/MailSender.java` (`enabled`), `…/dto/task/{TaskSourceRequest,TaskValidationResponse,TaskStepView,TaskDraft*,TaskReferenceResponse}.java` |
| 2 Engine & Schritte | `…/services/task/{TaskRunner,TaskGraph,TaskService,TaskMails,StepContext,StepOutcome,TaskAnswer}.java`, `…/services/task/step/{ReadFilesStep,LineMasks,AgentStep,PromptStep,SendMailStep,StepInputs,TaskFindings,TaskPrompts}.java` (`LineMasks`: `mask` with `after:` and `regex:`, 100 ms per line, `task.maskTooSlow`; `PromptStep` with `assistant: true` - `AssistantAsk`, `TaskPrompts.ASSISTANT`, `ToolContext.taskConnections`, `McpTools.forTask` reading tools only, `answeredByAgent` for a tenant answering through its agent, `task.agentAsksBack`), `…/services/task/TaskRunCleanupJob.java` + `…/services/common/Retention.java` (runs deleted `app.task.run-retention-days` after their end - never an open run, never the read positions), `…/repositories/task/TaskRunRepository.java`, `…/entities/task/**`, `…/services/agent/AgentLoop.java` (only `stopRequested`, `record`, the rounds as a step uses them), `…/services/tools/{ToolContext,Toolbox,SearchFileTool,ReadFileLinesTool}.java` (`Asker.TASK`, `onlyTools`, `allows`, `stopWhen`, `exploredFiles`, `countInto`), `…/services/ai/{Spent,CallTokens}.java`, `…/services/task/RunCosts.java` (a run from before at today's price, `costPartial`), `…/dto/task/{TaskRunSummary,TaskRunResponse}.java` (`cost`), `…/services/filestore/{FileStoreService,FileStoreSession,RemoteFile,NamePattern}.java` (`open`, `list` - its folders left out by `read_files` -, `read`), `Backend/src/main/resources/mail/{answer,footer-task}.html`, `…/mail/MailTemplate.java` (escaping) |
| 3 Start ohne Person | `…/services/task/{TaskScheduler,TaskSchedule,TaskService}.java` (`startRun`, `tenant`, `assignApiKey`, `keyTask`, `tasksOfMcpKey`, `mcpTask`, `assignMcpKey`, `findings`, `handedOn`), `…/repositories/task/TaskRepository.java` (`findDue`, `moveNextRun`, the key's tasks), `…/services/tenant/{TenantService,ApiKeyService}.java` (`tasksAllowed`, `taskIds`), `…/entities/tenant/Tenant.java`, `…/controller/api/v1/TaskApiController.java`, `…/dto/api/v1/ApiTask*.java`, `…/services/mcp/TaskMcpTools.java`, the A2A server's way (a2a.md "Tasks over A2A"): `…/services/a2a/{AssistantAgent,RunTasks,JdbcTaskStore}.java` (`runTask`, `startByA2a`, the run mirrored every 2 s up to `app.task.a2a-subscribe`, the artifacts `report` and `findings` at its end, `cancel` through `TaskService.cancel`), `…/services/task/TaskService.java` (`startByA2a`, `taskOfTenant`), `…/entities/task/StartedBy.java` (`A2A`), `…/controller/task/{TaskAdminController,TenantAdminTaskController}.java`, `…/dto/tenant/ApiKey{Request,Response}.java`, `…/security/TenantContext.java` (`apiKeyId`) |
| 4 Dateiablagen | `…/services/filestore/{FileStoreService,FileStoreAddresses,FileStoreClient,SftpStoreClient,FtpsStoreClient,ServerSettings,S3StoreClient,S3Settings,GraphStoreClient,GraphAddresses,GraphSettings,MailboxStoreClient,MailboxSettings,CheckResult,StoreFailures,RemoteFile}.java` (`readable` per type, `folders`), `…/services/common/InternalAddresses.java`, `…/controller/filestore/{TenantAdminFileStoreController,FileStoreAdminController}.java`, `…/repositories/filestore/FileStoreRepository.java` (`updateCheck`), `…/entities/filestore/**`, `…/dto/filestore/**`, `…/security/SecretCipher.java` (only that the password goes through it), `Backend/src/test/resources/filestore/**` (read, not run) |
| 6 Frontend | `store/{task,file-store}/**`, `services/{task,file-store}.service.ts`, `utils/{task,task-graph,file-store}.util.ts`, `components/{tasks-page,task-editor,task-run,file-stores-page}/**`, `components/shared/{tasks-panel,task-source-editor,task-graph,file-stores-panel}/**` (the dialog's one form, `file-store-form.ts`, with a fields component per type), `components/shared/api-keys-panel/**` (the column "Aufgaben"), `guards/task-editor.guard.ts`, `interceptors/timeout.interceptor.ts` (check, draft), `store/load-failure/load-failure.effects.ts`, `components/tenant-app/tenant-app.component.ts` and `components/tenant-detail/tenant-detail.component.ts` (the page and the tab only with the switch); from the backend only what the pages call: `…/controller/task/{TaskAdminController,TenantAdminTaskController}.java`, `…/controller/filestore/{TenantAdminFileStoreController,FileStoreAdminController}.java`, `…/dto/task/**`, `…/dto/filestore/**` (the models matching the DTOs, every code the pages translate) |
| 5 Dokumente & Tabellen | `…/services/task/step/{ReadDocumentsStep,DocumentReader,ExtractStep,ExtractedRow,WriteFileStep,CsvRows,CallToolStep,CallAgentStep,ToolArguments,StepRows}.java` (`CallAgentStep`: a `TextPart` prompt and a `DataPart` per row or `rows` for `each: all`, `source` and `with`, the asker's metadata without a person, `task.agentAsksBack` / `task.agentTimeout` as the row's reason, the agent down marking the rows after it; `McpConnectionService.agentForTask` / `agentForRun`, `TaskCompiler.callAgent` / `withAgentConnection`: `task.agentUnknown`, `task.a2aNotAllowed`, `task.skillUnknown`), `…/services/task/{TaskCompiler,TaskDefinition,TaskResults,TaskRunner,TaskService}.java`, `…/services/task/step/TaskPrompts.java` (the rules of the five steps: `FILE_PLACEHOLDER`, `LAST_RUN`, `withTool`, `foundTool`, `givesWhatIsTaken`, `withWritableStore`, `requireFreeFiles`, `writeStores`, `unclearChecks`, `EXTRACT_SYSTEM`, `extractAgain`, `counts(Step)`), `…/repositories/task/TaskRunRepository.java` (`saveRow`, `findRows`, `lastSuccessfulStart`, `savePositions` with `modified_at`), `…/entities/task/RunRow.java`, `…/services/document/{DocumentTextExtractor,ScanReader,DocumentImages,DocumentService}.java` (`detect`, `ALLOWED`, `needsReading`, the 7-argument `read` with `Spent`, `sha256`, `MAX_DOCUMENT_BYTES`), `…/services/filestore/{FileStoreSession,FileStoreService}.java` (`write`, `readWhole`, `writable`, `requireWritable`), `…/services/mcp/McpConnectionService.java` (`toolForTask`, `toolSchema`, `callForTask`, `credential` - a connection's key or its OAuth sign-in, `oauth.signInNeeded`), `…/services/mcp/{McpClient,LinkDownloads,Credential}.java` (`CallResult`, `files`, `MAX_MESSAGE_BYTES`; a file returned as an `https` link fetched at once, `ROW_REFUSED` / `FILE_REFUSED` in `CallToolStep` and `CallAgentStep`), `…/dto/task/TaskRunResponse.java` (its `Row`), `…/controller/api/v1/TaskApiController.java` and `…/services/mcp/TaskMcpTools.java` (rows only once the run ended), `Backend/src/main/resources/tasks/reference/{en,de}.md` (the five steps); frontend `components/task-run/**` (documents, the rows table, written files, calls), `components/shared/task-graph/**` (the icons), `utils/task.util.ts` (`rowColumns`, `runDocuments`, `toolCalls`) |

Specs: `controller.task.TaskSpec` (also the data table of the `read_documents`, `extract`, `write_file` and `call_tool`
rules, `task.fileTaken`), `services.task.TaskRunSpec` (also masks reaching neither model nor mail, the retention),
`TaskStartSpec`, `TaskScheduleSpec`, `TaskPromptSpec`, `TaskMailFailureSpec` (a mail not sent leaves the run's end),
`TaskReadPositionsSpec` (two steps on one file: the smaller position kept),
`TaskDocumentsSpec` (the five steps end to end against `FakeStoreClient`, `FakeAiModels.extractAnswer` and
`FakeMcpSoftware`: read once, oldest 50, scans, CSV per year with nothing twice, replace, the switch off at run time,
a refused call, the software out of reach marking every row after it unsent, `each: all`, a rows and a documents
source, a source's tool or file tool turned changing since the save (`task.toolChanges`), `{lastRun}`, the rows over
`/api/v1` and `task_run`),
`services.task.step.CsvRowsSpec`, `ExtractedRowSpec`, `LineMasksSpec`, `StepInputsSpec`, `TaskFindingsSpec`,
`TaskDraftSpec`, `TaskDraftTimeoutSpec`, `TaskReferenceSpec`, `controller.filestore.FileStoreSpec` (the switch
`taskWrite`), `FileStoreFoldersSpec`, `services.filestore.S3StoreClientSpec` (a bucket's listing that never stops
or breaks off), `FtpsCertificateCheckSpec` (the certificate checked on every handshake, a data connection's too),
`FtpsListingBoundSpec` (a listing without end cut off), `controller.filestore.MailboxStoreSpec` (the real `MailboxStoreClient` against GreenMail IMAP: messages
as folders, attachments and `mail.txt`, a forwarded mail, `days`, read only, a task reading it once, a knowledge source),
`SharePointStoreSpec` (against `services.filestore.FakeMicrosoft`; a task reading a library, an upload, a locked file), `services.task.step.ReadFilesPatternSpec`,
`security.TenantApiKeySpec`, `TenantAdminSpec` (the new routes), `controller.mcp.McpSpec` (the tools listed),
`controller.a2a.A2aServerSpec` (a task started through `metadata.task`),
`services.tools.ToolboxSpec`, `services.tenant.TenantDatabasesSpec` (migrations 5 and 6); `controller.filestore.FileStoreServerSpec`
(SFTP, FTPS, S3 - reading and, since 2.0.0, writing: replaced whole, no temporary file left, a missing folder refused)
runs only with `FILE_STORE_TEST` against real servers - read and named, not run (say so). Shared steps:
`services.task.TaskApi` (`allowWriting`), `FakeStoreClient` (`put`, `write`, `unwritable`, `written`),
`services.filestore.FileStoreApi`, `services.ai.FakeAiModels` (`[[find TEXT]]`, `[[invent]]`, `[[unclear-until N]]`,
`brokenTaskAnswers`, `[[choose a,b]]`, `[[draft <name>]]`, `extractAnswer`, `extractHold`, `[[call <tool> <json>]]`,
`[[tools]]` in an assistant prompt step), `services.mcp.FakeMcpSoftware` (`answers`, `refusedSources`, `blob`),
`services.a2a.FakeA2aAgent` for `services.task.{TaskAgentSpec,TaskAssistantSpec}` (`call_agent`, the assistant prompt
step with an agent and for a tenant answering through its agent). Frontend:
`store/{task,file-store}/*.reducer.spec.ts`, `utils/{task,file-store}.util.spec.ts`,
`components/shared/task-source-editor/task-source-editor.component.spec.ts`, `guards/task-editor.guard.spec.ts`,
`i18n/i18n.spec.ts`.

## Key questions (every agent keeps them in mind; the report answers each one explicitly)

1. **Is the text data, never code?** Every field of the format checked (unknown fields, types, ids, inputs of the
   right type, conditions only on counts and choices, an edge back only with `maxRounds`), YAML 1.2 without anchors,
   every mistake with its line, the tenant's names resolved once and the ids kept in the definition - and a run keeps
   the definition it started with, whatever is saved later.
2. **Does every run end, and with the truth?** A bounded graph (recursion limit, `maxRounds` per edge back), a step
   that fails ends the run `FAILED` with its code and a mail, an edge back at its limit ends it `UNCLEAR` with a mail -
   never `DONE` for "nothing found" when something stayed unclear; a cancel ends it `CANCELLED` before the next step or
   round, without a mail.
3. **Is nothing lost and nothing read twice?** The read positions move only after a successful run and never past a
   complete line; a failed or cancelled run leaves them; a file smaller than its position is read from its start; the
   limits per step (files, bytes, lines) say `truncated`; one run of a task at a time. A `mask` replaces its value
   before the line is kept - neither run file, model nor mail ever has it, also at a line's cut; a regex too slow
   fails the run (`task.maskTooSlow`) with the positions left. The retention deletes ended runs with their lines,
   never an open run, never the read positions and never a task's last `DONE` or `UNCLEAR` run (`{lastRun}` is its
   start - a monthly task's source would otherwise be asked for everything again).
4. **Does the model get what it should, and only that?** The lines with their numbers, the check points, a verify's
   findings, a prompt's inputs and the answers that sent it back; only `search_file` and `read_file_lines`, no software
   tool, no company knowledge - **except a `prompt` step with `assistant: true`** (tasks.md "The step `prompt`"): the
   company knowledge and the areas named (`similarity_search`, `read_document`), the reading tools of the software
   named (an A2A agent as its `__ask` tool; never a changing tool - a task has no person to approve), the web search
   only with `web`, the template's instructions after the step's rules; the other fields refused without
   `assistant: true` (`task.fieldNotTaken`), every name checked when the task is checked (`task.areaUnknown`,
   `task.softwareUnknown`, `task.templateUnknown`, an A2A agent only with the admin's switch); for a tenant answering
   through its A2A agent the step's question goes to the agent with the rules in the text (`areas`, `software`, `web`
   without effect), a question back fails the step, the admin's A2A switch off fails it with `a2a.notAllowed`; only such
   a tenant may leave out an assistant step's `model` - any other gets `task.modelRequiredWithoutAgent` at the check and
   at the run, when its agent stopped answering since; the structured result checked (every check point once, evidence lines really in the
   files, a finding without evidence counts unclear), one more round for a broken answer, then the step fails; without
   lines no model is asked.
5. **Does a run go on after a restart?** Every `QUEUED` or `RUNNING` run submitted again on start; one with a
   checkpoint resumes after the last finished step; its record per step and round replaced, not doubled; the
   checkpoints deleted at every end.
6. **Does a run know what it cost?** Every model call of a step counted into the step (`ToolContext.countInto`) with
   its tokens - the cached ones apart - and the amount `UsageService.record` gave it, so the run's cost equals what the
   usage recorded; a step that fails keeps what its calls spent and adds it to the run; a step run again after a
   restart adds its calls again (as the usage has them); a run from before `V6` (`cost` NULL) computed from its steps'
   tokens at today's catalog price without tiers, `costPartial` when a step's model is gone; a step that asked no model
   costs nothing and is not partial.
7. **Who may start, and when?** Nothing starts without the admin's switch and an active tenant - not by the admin,
   not by the schedule, not by a key, not through MCP; the schedule at most every 15 minutes, a downtime made up for
   by one run, a task still running skipped; the client software only the tasks assigned to its key (404 for any
   other), the MCP server only the tasks assigned to the MCP key the call is made with
   (`tenant_mcp_key_task`, per key since 25 October 2026 - another key of the tenant gets none of them); what they get back is the findings and the answers handed on -
   never the lines read.
8. **Does a store prove itself before the password goes out?** Without a confirmed fingerprint the check stops after
   the key exchange or the TLS handshake; a changed key or certificate gets no login; another type, host or port drops
   the fingerprint and needs the password again; the password encrypted and never in a response; a host in an internal
   network only when the operator listed it, resolved again before every connection; a store a task reads cannot be
   deleted. **S3 and SharePoint** have no fingerprint (confirming one 409 `fileStore.noFingerprint`): `readable` only
   after a check that read the folder, a task naming one not ready refused at its line (`task.storeNotReady`); another
   endpoint, region, bucket or access key (S3), another directory or app (SharePoint) needs the secret again; their
   failures named by code (bucket or site not found, key or app refused, a client secret expired, throttled); a
   listing's subfolders never read as files by `read_files`.
9. **Does the frontend show what happened?** (part 6) The editor keeps an edit while marks change and asks before leaving with
   unsaved changes - but not after a logout; the graph stays at the last text that compiled; the run view polls while
   the run is open and stops on leaving or logout; a failed check of a store is an answer with its translated code,
   not an error; the page "Aufgaben" and the column of the API keys only with the switch.
10. **Is it tested?** Each failure case below has a spec with `FakeAiModels`, `FakeStoreClient` and GreenMail - or the
   missing case and the spec it belongs in.
11. **Is every document read once, and the right ones?** `read_documents` keeps a position per document (size and
   modification time to the millisecond): unchanged skipped, changed read again with another `source`; at most 50 a
   run, the oldest first, `truncated` says there are more; a document the document itself is to blame for (empty,
   broken, a type not agreed, too long) reported once and never again, one that needs an image model or a key read
   again once the admin gives one, a provider failing fails the run so nothing is marked read; the positions move only
   after a successful run; a mailbox's messages listed as folders, only with `subfolders: true`.
12. **Is a row what the document says, or unclear?** `extract` asks one document per question, checks every value by
   its type (`ExtractedRow`: ISO dates, numbers with a point, percent 0-100, a choice's words, required fields), one
   more round on a value not as asked, then the row keeps what passed and is unclear with why; the `source` of a row
   is `<name>#<checksum>` so a changed document gives a new row; the rows are in the run view always, over `/api/v1`
   and `task_run` only once the run ended; a call_tool source's rows checked the same way, its `source`
   `<connection>/<tool>#<key>`.
13. **Is nothing written twice and nothing lost?** `write_file` only into a store with the switch - checked at the
   save (`task.storeNotWritable`) and again at the run; the file from `FILE_PLACEHOLDER` only (year, month, day of a
   date field, a choice's word - never a path); rows already in the file by `source` skipped, fields added at the end
   taken, another header refused (`task.fileHeaderChanged`); the file written whole beside the old one and renamed over
   it - a server without an overwriting rename gets the old file removed first, and if the rename then fails the
   temporary file stays and the failure names it; `replace` writes this run's rows only; one task's file never another
   task's (`task.fileTaken`); 20 files and 20 MB a run; a call_tool target sends clear rows only, each with its
   `source` so the software recognises a repeat, a failed call marks its row and ends the run `UNCLEAR` with a mail, a
   tool turned off since the save fails the run; the software failing as a whole (out of reach, `app.ai.timeout`, our
   key refused or unreadable, its address refused, its OAuth sign-in to be repeated - `oauth.signInNeeded`, nothing
   reaches the software) marks its row and every row after it unsent with that reason - no second wait for the timeout
   on one of the two task threads; `mcpServer.requestFailed` marks only its row at a target but fails a documents
   source's step (decided 6 November 2026: a document must not be lost silently to a network failure); a source calls reading tools only - a tool or
   `fileTool` turned changing since the save (a correction, `readOnlyHint` dropped at a reload) fails the run with
   `task.toolChanges` before it is called -, at most 200 objects, a text `McpClient` cut at 100 000 characters is
   `task.toolAnswerTooLong`, `{lastRun}` the start of the last `DONE` or `UNCLEAR` run (a failed one leaves it), a
   file the tool fails at or sends too large kept with its reason while the software out of reach fails the step; a
   file returned as an `https` link fetched at once (`LinkDownloads`: the connections' address rules, our key only to
   the connection's own host, 50 MB), an expired or refused link the document's reason; the
   log gets a failure's code only (a message can quote the software's answer), the run and its mail keep the message.
   **`call_agent`** (tasks.md "The step `call_agent`") sends clear rows to an A2A agent of the tenant as `call_tool`
   sends them to a tool: a target only, exactly one `extract` input, the agent by its connection's name (an MCP
   connection named is `task.agentUnknown`), only with the admin's switch, the skill on the card as last read; a
   `TextPart` (the `prompt`, or "Row of <task>") and a `DataPart` with the row's fields, `source` and `with` - or
   `rows` for `each: all`; the metadata carry tenant, language, task, run and the one skill, no user and no groups; a
   Message is the call's answer (2,000 characters kept), a Task is waited for up to `app.a2a.timeout`
   (`task.agentTimeout` as the row's reason), a question back fails the row (`task.agentAsksBack` - no person to
   answer), a refused call marks the row, the agent as a whole out of reach marks every row after it; files the agent
   returns are dropped; the run ends `UNCLEAR` with a mail as after a failed `call_tool`; the agent gone, no longer A2A
   or the switch off at run time fails the run (`task.agentUnavailable`); a cancel stops between calls; no agent is
   asked for when every row is unclear.

## Failure cases (every agent walks through them for its part)

The store out of reach · the store's key or certificate changed since the fingerprint was confirmed · the store's
fingerprint never confirmed · the folder gone or not readable · a file rotated (smaller than its position) · a file
that grew past its old offset after a rotation · a last line without its line break · more files, bytes or lines than
the step's limits · a file in Latin-1 · a folder with subfolders · a masked value at a line's cut · `mask after: 'password:'` with a space before the value · a mask regex
with a backreference that backtracks for seconds · the retention due while a run of the task is open · an S3 store ignoring the range, a SharePoint
download answered whole (200 instead of 206) or past its end (416) · Graph throttling (429 with a short and a long
`Retry-After`) · a SharePoint token expiring during a run · a client secret expired · a document changed since it was
read (same name) · a document the store refuses to read · a scan without an image model, then with one · a PDF with a
text page and a scanned page · a document of a type not agreed, over 50 MB, over 20 000 lines · 51 documents in the
folder · the model's extract answer no JSON, a value not its type, twice, a number like `1e99999999` · a scan's unclear row
 (no image model) read again once one is assigned - same `source`, must still reach the CSV · a required field missing · a CSV with a
header of another field order · a CSV over 20 MB · a CSV saved again as Windows-1252 by a spreadsheet · 21 files by the date placeholder · the store's switch turned off
after the save · the folder of the CSV gone · the old CSV removed and the rename of the new one refused (FTPS, SFTP 3)
· the same file named by two tasks · a tool turned off between save and run · the software refusing one row's call, a
call with every row · the software out of reach at a target, at a source · the software not answering within
`app.ai.timeout` at the first of 50 rows · a source's tool or file tool turned changing between save and run · a
source's answer cut at 100 000 characters · a source's answer no array, 201 objects · a `call_agent` whose agent asks
back, answers a Task that never ends, is down at the first of 50 rows, was deleted or lost the admin's switch since the
save · a `call_agent` naming an MCP connection, a skill the card lost · an assistant `prompt` step naming an area,
software or template that is gone, a software whose tool turned changing, a tenant whose A2A agent now answers (the
step's areas and software without effect) · a `prompt` step's `areas` without `assistant: true` · a run started over A2A
(`metadata.task`) while one is open, for a task not assigned to the key, with the switch off; its client gone before the
run ends (`GetTask` reads the run), cancelled through `CancelTask`, with a webhook set
(push to public HTTPS only - the push itself is `alpdesk-mcp-a2a-review`) ·
a file tool answering no file, an error, a file over 6 MB, a link that expired (410) or redirects to an internal
 host · a connection whose OAuth sign-in has to be repeated, or whose token expires mid-run, at a target, a source and
 `call_agent` · `mcpServer.requestFailed` at a target's row and at a documents source · an assistant `prompt` step
 without `model` for a tenant not (or no longer) answering through its agent · the A2A switch turned off while a
 tenant answering through its agent has an assistant step · `{lastRun}` after a failed run, after the retention passed the last successful run · a mailbox with a
forwarded mail, an attachment without a name, a mail without attachments, more than `days` old · a step failing after two model
calls (its cost) · a price changed or a model deleted after a run (its kept cost) · the model taken away from the tenant or its key gone since the save · the
provider refuses the key · the provider fails in round 1 or round 5 · the model answers without the structured result
once and twice · the model names a line that is in no file · a finding without evidence · the model picks none of the
choices · an edge back reaching its limit with findings still unclear · a step skipped because its input gave nothing ·
a cancel while the run is queued, between two steps, between two rounds of a model, during the last step · a restart
while a run is queued, mid-step, between steps · the tenant switched off or its switch turned off while a run is queued
or running · the task saved (new version) while a run of it is open · a store removed from the task while a run reads
it · a second start while a run is open (by hand, by the schedule, by a key, through MCP) · the schedule due during a
downtime, several times · the mail server down at the findings, at the failure mail · the tenant database briefly out
of reach when the run ends · the task or the tenant deleted while a run is open · a draft whose model answers no YAML
or YAML that never compiles within three rounds · the draft timeout · the user logging out in the editor with unsaved
changes, in the run view while it polls.

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
> `local-data/review/decisions.md` and - the sections named here, not the whole file - read another one when a finding depends on it - for part 1 `tasks.md` "Who keeps tasks", "The format", "The check",
> "The step `prompt`", "Draft with AI"; part 2 `tasks.md` "Steps", "The step `prompt`", "Engine", "Data", "Mails" and
> `ai-features.md` "Tools", "Usage and prices"; part 3 `tasks.md` "Starting without a person", "Engine", "API" and
> `a2a.md` "The server"; part 4 `file-stores.md` in full; part 5 `tasks.md` the sections of the steps
> `read_documents`, `extract`, `write_file`, `call_tool`, `call_agent` and "Data", `file-stores.md` "Writing",
> "Mailbox" and `ai-features.md` "Images and scans"; part 6 `tasks.md` "The frontend", `file-stores.md` "API and
> frontend" and `i18n.md` - first, then every file of your part in full. Context: Micronaut 5 / Java 25,
> SnakeYAML Engine (YAML 1.2 composed as nodes, lines kept), langgraph4j 1.8 (a `StateGraph` with conditional edges,
> `PostgresSaver` on the tenant's `DataSource` with tables of our own migration, `GraphInput.resume()`), langchain4j
> through the chat's `AgentLoop`, Apache MINA SSHD (SFTP) and Commons Net (FTPS with explicit TLS, data connections
> resuming the control connection's TLS session), the AWS SDK (S3), MSAL and Graph REST (SharePoint), Angus Mail (IMAP,
> `Folder.READ_ONLY`), Tika and the tenant's image model for documents (`DocumentReader`), the MCP Java SDK's client
> for `call_tool` (a message of at most 8 MB), the A2A Java SDK's client behind `services.a2a.A2aClient` for
> `call_agent` and `AgentAnswers` for an assistant prompt step of a tenant answering through its agent
> (`docs/architecture/a2a.md`), Micronaut's `@Scheduled` and a fixed task executor of two threads,
> one database per tenant reached with plain JDBC; a step may wait minutes for a server or a provider and must not
> hold a transaction. Specs use `FakeAiModels`, `FakeStoreClient` (an SFTP store in memory), `FakeMcpSoftware`,
> `FakeA2aAgent` and one GreenMail.
> Read-only: do not edit files, do not run the application, never touch the developer's dev database, never call an
> AI provider, a file server, an MCP server or an A2A agent.
> Deliver first a **walkthrough** of your part: one row per step - step · `Class.method` (path:line) · transaction yes/no
> · what is stored (management database, tenant database, checkpoint) · what happens on failure · verdict ✅ / ⚠️ / ❗.
> Every ⚠️/❗ row is also a finding.
> Severity for this review: **Kritisch** = a task's text run as code or reaching a file above the store's folder; a
> store's password sent to a server whose key or certificate was not confirmed, or to another server than it was
> entered for; one tenant's task, run, lines read or store reachable from another tenant or key; a run started without
> the admin's switch; a run's lines or findings mailed to or handed to someone the task did not name; a task writing
> into a store without the switch, into a mailbox, or into a path outside the placeholders' form; a changing tool
> called by a source;
> **Hoch** = a run that never ends or ends `DONE` with findings still unclear, read positions moved after a failed run
> (lines lost) or not moved after a successful one (lines read twice), a run not taken up after a restart or taken up
> twice, a step's record doubled or missing, a failed run without its mail, a provider call or a server read inside a
> transaction, the scheduler starting a task twice or never after a downtime, a check of a store written to a store
> changed meanwhile, the editor losing an edit; a CSV's old rows lost on a replace, a row written or sent twice for the
> same `source`, a document marked read that was not, an unclear row sent to a tool, a source's failure that leaves the
> task dead for ever (`{lastRun}` never moving on).

## Step 2 - Synthesize

Follow **section 6 of the review base**, then the report as in **section 7**. The skill's own sections:

- **Der Weg einer Aufgabe**: the walkthroughs as one table from the text to the mail, only ⚠️/❗ rows in full.
- **Abbruch-Matrix**: one row per failure case · state of the run · read positions moved yes/no · checkpoints left
  yes/no · mail sent to whom · next run works ✅/❌ · spec.
- **Einstiege**: one row per way a run starts - by hand (tenant admin, admin), schedule, `/api/v1/tasks`, MCP
  `run_task`, A2A `metadata.task`: who is checked (switch, active tenant, active task, key's assignment, the key's door)
  · `StartedBy` · what the caller gets back (for A2A: the Task's states and the artifacts `report` and `findings`) -
  intended (with the line of `tasks.md` / `a2a.md`) or not.
- **Dateiablage** (when part 4 ran): one row per step of a store's way and per type (SFTP, FTPS, S3, SharePoint) -
  saved, checked unconfirmed, fingerprint confirmed (SFTP, FTPS only), checked ok, changed server, opened by a run,
  deleted: `Schritt` · `Bedingung` (fingerprint, authority,
  internal host) · `was den Server erreicht` (nothing, the key exchange, the password; S3: a signed request; SharePoint: the secret to
  Microsoft's login, the token to Graph) · `geprüft in` (path:line) ·
  `Spec` · Urteil ✅ / ⚠️ / ❗.
- **Dokumente & Tabellen** (when part 5 ran): one row per step of the way from a document to its row in the CSV, at
  the software or at the agent - listed, position compared, read (Tika, image model), extracted (round 1, round 2),
  checked, stored, written or sent, position saved: `Schritt` · `Bedingung` · `was bei einem Fehler bleibt` (position, row,
  file, mail) · `geprüft in` (path:line) · `Spec` · Urteil ✅ / ⚠️ / ❗.
- **Kernfragen**: each key question answered in two or three sentences with the evidence (path:line, spec).
