---
name: alpdesk-tenancy-review
description: "Focused non-diff, multi-agent review of the life cycle of tenants and their data in Zirmo: creating a tenant with its own database (TenantDatabases, Flyway on every tenant database at creation and start, the pool per tenant), changing, deactivating and deleting it (database dropped, stored files, management rows), the tenant users (create, deactivate, delete with their chats, the last tenant admin, password set by the admin) and access requests through WAM, the user groups (members, knowledge areas, levels per connected software, the WAM group and the IDM groups kept on the user), the knowledge areas and their assignments across the management and the tenant database - and what really disappears when a tenant, user, user group, document, conversation, file, key (with the client software's conversations it started), knowledge area, handbook website, connection, file store or knowledge source (with its documents, or keeping them as uploads) is deleted, checked against docs/privacy.md. Answers its key questions, builds a deletion matrix and checks every failure halfway. Asks which parts to review. Follows the shared review base (no hallucination, no over-optimisation, decision register, at most 10 verified findings, fix packages). Use when the user invokes /alpdesk-tenancy-review or asks whether tenants, users and deletions are handled correctly - NOT a diff/branch/PR comparison."
---

## What this does

A read-only review of **a tenant's life from creation to deletion**: its database created and migrated, its pool
opened, its users and admins added, deactivated and removed, people let in through WAM, its user groups created, given
members, knowledge areas and levels per connected software, and deleted, its knowledge areas created, assigned to
groups, API keys and its MCP keys (several per tenant, each with its own areas), and deleted, its handbook's websites uploaded and removed - and at the end, or for any
single item on the way, **what is really gone** when something is deleted: rows in the management database, rows in the
tenant database, stored files, vectors, keys. It checks the steps that cannot run in one transaction (CREATE and DROP
DATABASE, files on disk, a knowledge area in the management database whose documents and website live in the tenant
database, a key revoked before its conversations are deleted, a website replaced or deleted before its directory) for what is left behind when they fail halfway.

**It follows [.claude/review-base.md](../../review-base.md)**: rules, the decision register (E01: usage is deleted with
the tenant), no hallucination, no over-optimisation, what counts as a finding, the verifier, the budget, fix packages and
the report. Read it first; this skill adds parts, paths, key questions and its own report sections.

Relation to the other reviews: whether a request can reach another tenant's data, and the login itself, is
`alpdesk-security-review` parts 1 and 2; here the question is whether creating, changing and deleting leaves the system
consistent and keeps what [docs/privacy.md](../../../docs/privacy.md) promises. A document's own replace/delete in the
search, and which area a document lands in, is `alpdesk-knowledge-review`; whether someone finds an area's documents
without being assigned, and whether a group's right reaches a non-member, is `alpdesk-security-review` part 7; how a
handbook's website is uploaded and served is `alpdesk-knowledge-review` part 5; here only whether rows, memberships,
assignments and files are gone and consistent.

## Step A - choose the parts

- If the arguments name parts (`teil=1,3`, `alle` ...), take them.
- Otherwise **ask** with `AskUserQuestion` - one question, `multiSelect: true`, header "Teile", question "Welche Teile
  des Mandanten-Lebenszyklus soll das Review prüfen?", options:
  - "1: Mandant & Datenbank" - Anlegen, eigene Datenbank, Flyway beim Anlegen und Start, Pool je Mandant, Ändern, Deaktivieren
  - "2: Benutzer, Gruppen & Zugänge" - Benutzer anlegen, deaktivieren, löschen, letzter Mandanten-Admin, Passwort durch Admin, Benutzergruppen (Mitglieder, Bereiche, Software-Stufen, WAM-Gruppe), Wissensbereiche der Gruppen, API-Keys und MCP-Keys (mehrere je Mandant), Zugangsanfragen über WAM
  - "3: Löschen & Aufräumen" - was beim Löschen von Mandant, Benutzer, Gruppe, Dokument, Chat, Datei, Key, Wissensbereich, Handbuch-Website, Verbindung, Dateiablage, Wissensquelle (mit oder ohne ihre Dokumente), Aufgabe mit ihren Läufen wirklich verschwindet, gegen privacy.md
  Nothing chosen (or "Other: alle") means all three.

## Scope

Base paths `Backend/src/main/java/de/alpdesk/` (below `…/`), tests `Backend/src/test/groovy/de/alpdesk/`, frontend
`Frontend/src/app/`, schemas `Backend/src/main/resources/db/migration/` (management: `V1__init.sql`,
`V2__knowledge_areas.sql`, `V7__user_groups.sql` - `user_group`, `user_group_member`, `group_area`, `group_mcp_access`,
`tenant_user_wam_group`; `V10__file_stores_and_tasks.sql` - `file_store`, `tenant.tasks_allowed`, `task`,
`task_file_store` with its deferred foreign key (also the store a `write_file` step writes into), `tenant_api_key_task`;
`V17__release_2_0_0.sql` (the one management migration of 2.0.0 - unreleased migrations are folded per set) -
`file_store.task_write`, `IMAP` with `security`, `mailbox`, `days`, the username unique per tenant
(`tenant_user_tenant_username_ci_uq`), `tenant_mcp_key.description`, `mcp_connection.protocol` and `answers_chat`,
`tenant.a2a_allowed`, the key's doors `mcp` / `a2a`, `a2a_task_index` (`ON DELETE CASCADE` with the tenant - which
tenant and key an A2A task belongs to, the task itself in the tenant database); `V12__several_mcp_keys.sql` - several
`tenant_mcp_key` per tenant with `name` and `web_search`, `tenant_mcp_key_area`; the tenant's former single key became
"Standard" with the tenant's areas and web search, `tenant_mcp_area` and `tenant_mcp_web_search` dropped) and `db/tenant/` (tenant: `V1__init.sql`,
`V2__document_area.sql` - `document.area_id`, `conversation.api_key_id`; `V3__conversation_file_expiry.sql`;
`V4__doc_site.sql`; `V5__task_runs.sql` - `task_run` with `task_id` without a foreign key, `task_run_step`,
`task_run_file`, `task_read_position`, langgraph4j's checkpoints; `V8__release_2_0_0.sql` - `task_run_row` `ON DELETE
CASCADE` with its run: an extract step's or a source's rows go with the run, never alone; `conversation
.excluded_connection_ids`, the topics a conversation leaves out; `conversation.a2a_context_id` / `a2a_task_id` /
`excluded_skill_ids` and `message.a2a_waiting` for a tenant answering through its A2A agent; `a2a_task` - the A2A
tasks of the tenant's assistant as the SDK's JSON, owned by an MCP key, answer and files inside, never the question;
`a2a_push_config` `ON DELETE CASCADE` with its task).

| Part | Where |
|---|---|
| 1 Mandant & Datenbank | `…/services/tenant/{TenantService,TenantDatabases,TenantDatabaseMigration}.java`, `…/entities/tenant/Tenant.java`, `…/repositories/tenant/TenantRepository.java`, `…/controller/tenant/TenantController.java`, `…/dto/tenant/Tenant{Request,Response}.java`, `Application.java`, `datasources.*` in `application.properties`; what is under way for a tenant without a request, only as far as a deactivated or deleted tenant or user is concerned (key question 3): `…/services/document/{DocumentIndexer,SourceSync,SourceScheduler}.java`, `…/services/task/{TaskRunner,TaskScheduler,TaskRunCleanupJob}.java`, `…/services/chat/{ChatService,ConversationTitles,ChatFileCleanupJob}.java`, `…/services/sandbox/Sandboxes.java`, `…/services/a2a/{AssistantAgent,A2aTaskCleanupJob}.java`, `…/services/monitoring/DailyReportJob.java`; frontend `components/tenant-list/{tenant-list,tenant-dialog}.component.ts`, `store/tenant/**`, `services/tenant.service.ts` |
| 2 Benutzer & Zugänge | `…/services/tenant/{TenantUserService,AccessRequestService}.java`, `…/entities/tenant/{TenantUser,AccessRequest}.java`, `…/repositories/tenant/{TenantUserRepository,AccessRequestRepository}.java`, `…/controller/tenant/{TenantUserController,TenantAdminUserController,AccessRequestController}.java`, `…/controller/account/AccountController.java` (`/switch`: a token of the target tenant's user only), `…/dto/account/SwitchTenantRequest.java`; one person in several tenants through WAM: `…/controller/login/WamLoginController.java` (the choice of tenant, a token only for the chosen tenant's user), `…/dto/login/{TenantChoice,WamChoiceResponse}.java`, `…/security/WamProxy.java` (only how the user name is read from the header); the admin's `users/from-tenant` in `…/controller/tenant/TenantUserController.java` (`addFromOtherTenant`, `…/dto/tenant/UserFromTenantRequest.java`); `…/security/ActiveTenantUserRule.java` (only what deactivation and deletion change); frontend `components/shared/tenant-users-panel/**`, `components/tenant-users-page/**`, `components/access-request-list/**`, `store/{tenant-user,access-request}/**`, `utils/tenant-user.util.ts`; knowledge areas and who is assigned: `…/services/document/KnowledgeAreaService.java`, `…/repositories/document/KnowledgeAreaRepository.java`, `…/services/tenant/ApiKeyService.java` (areas, update lock, revoke), the `/areas` endpoints of `…/controller/mcp/{TenantAdminMcpKeyController,McpKeyAdminController}.java`, `…/controller/document/{KnowledgeAreaController,KnowledgeAreaAdminController}.java`; frontend `store/knowledge-area/**`, `components/shared/document-panel/knowledge-areas-dialog.component.ts`; user groups: `…/services/tenant/UserGroupService.java` (members, areas, `groupIds` of a user, the WAM group, `forgetWamGroupsWhenUnchecked`), `…/entities/tenant/UserGroup.java`, `…/repositories/tenant/UserGroupRepository.java`, `…/controller/tenant/{UserGroupAdminController,TenantAdminUserGroupController}.java`, `…/dto/tenant/UserGroup*.java`, the access list by group of `…/services/mcp/McpConnectionService.java`, `…/security/ActiveTenantUserRule.java` (writes `tenant_user_wam_group`); frontend `components/shared/user-groups-panel/**`, `store/user-group/**`, `utils/user-group.util.ts` |
| 3 Löschen & Aufräumen | every `delete*` method of `…/services/**` and the repositories behind them (`grep -rn "public .* delete" Backend/src/main/java/de/alpdesk/services`), `…/storage/**`, `TenantService.storagePrefix`, `…/services/chat/{ChatService,ChatFileService}.java` (`deleteAllOf`, `deleteConversation`, files), `…/services/document/{DocumentService,TenantEmbeddings}.java` (delete), `…/services/mcp/{McpConnectionService,McpKeyService}.java`, `…/services/branding/BrandingService.java`, `…/services/assistant/AssistantService.java`, `…/services/tenant/ApiKeyService.java` (`revoke` deletes the key's conversations after the commit), `…/services/document/{KnowledgeAreaService,DocumentService}.java` (`delete` of an area, `deleteUploads`), `…/services/document/KnowledgeSourceService.java` (`delete` with and without `keepDocuments`, `update` to another store or folder) with `DocumentService.{deleteBySource,detachSource}` and `…/repositories/document/{KnowledgeSourceRepository,SourceFileRepository}.java`, `db/migration/V14__knowledge_sources.sql` (no `ON DELETE` on store and area - checked at the statement's end, so the tenant's cascade passes), `db/tenant/V7__source_files.sql` (`source_file.document_id ON DELETE SET NULL`), `…/services/filestore/FileStoreService.java` (`delete`, and `update` refused while a source reads it), `…/repositories/chat/{ConversationRepository,ConversationFileRepository}.java` (`ConversationOwner`), `…/services/chat/ChatFileCleanupJob.java` (expired files: storage deleted, row kept with `expired_at`), `…/services/tenant/UserGroupService.java` (`delete` of a group), `…/services/docsite/DocSiteService.java` and `…/repositories/docsite/DocSiteRepository.java` (replace and delete: row, then directory), `…/services/filestore/FileStoreService.java` (`delete`: 409 `fileStore.inUse` while a task reads it, cascade with the tenant), `…/services/task/TaskService.java` (`delete`: runs, lines read and read positions in the tenant database first, then the task - 409 while a run is open; `deleteRun`, `deleteRuns`), `…/repositories/task/TaskRunRepository.java` (`deleteTask`, `deleteCheckpoints`), `…/services/task/TaskRunner.java` (the checkpoints deleted at every end of a run), the A2A tasks (a2a.md "The task store"): `…/services/a2a/{JdbcTaskStore,A2aTaskCleanupJob,JdbcPushConfigStore}.java` (`delete`, `deleteEndedBefore` - ended tasks only, `app.a2a.task-retention-hours`, hourly, the tenants with tasks only, the index rows with them), `…/repositories/a2a/**`, `…/services/chat/ChatService.java` (`deleteConversation` cancelling a waiting agent task), `…/services/mcp/McpConnectionService.java` (deleting the answering connection: the tenant answers itself again, waiting conversations keep their stored state), the `ON DELETE` clauses of both schemas |

Specs: `controller.tenant.TenantSpec`, `services.tenant.TenantDatabasesSpec`, `TenantDatabaseFailureSpec`,
`security.TenantUserSpec`, `TenantAdminSpec`, `WamLoginSpec`, `LoginWithWamSpec`, `LoginModesSpec` (the switch, `from-tenant`), `LoginLockoutSpec`, `controller.document.DocumentSpec`,
`controller.chat.ChatFileSpec`, `ChatFileRetentionSpec`, `controller.document.KnowledgeAreaSpec`, `controller.api.v1.ChatApiSpec`,
`controller.tenant.UserGroupSpec`, `security.WamGroupsSpec`, `controller.docsite.DocSiteSpec`,
`controller.filestore.FileStoreSpec`, `MailboxStoreSpec` (a mailbox store like any store: deleted, in use), `controller.task.TaskSpec` (`fileStore.inUse` for a store a task reads or writes), `services.task.TaskRunSpec` (deleting), `TaskDocumentsSpec` (the rows read by run id of the tenant's own database),
`controller.document.KnowledgeSourceSpec` (removing with and without documents, a store and an area in use, the tenant
deleted with a source); frontend
`store/{tenant,tenant-user,access-request,user-group}/*.reducer.spec.ts`, `utils/{tenant-user,user-group}.util.spec.ts`,
`store/knowledge-area/knowledge-area.reducer.spec.ts`, `store/knowledge-source/knowledge-source.reducer.spec.ts`.

## Key questions (every agent keeps them in mind; the report answers each one explicitly)

1. **Does a failure halfway leave something behind?** Tenant created but its database not (or migrated only partly);
   database dropped but the tenant row kept (or the other way round); rows deleted but files left on disk - and does a
   retry or the next start repair it?
2. **Is every tenant database migrated?** On creation and on every start, all of them, a failing one handled as
   `tenancy.md` says - and the pool of a deleted tenant closed, not kept open for good.
3. **Does a deactivated or deleted tenant or user stop at once?** Logins, open sessions, API keys and every MCP key
   of the tenant - **and everything that is under way for it without a request** (found by `grep -rlE
   "@Scheduled|StartupEvent|ExecutorService" Backend/src/main/java`, each read only for what it does once the tenant
   or user is deactivated, deleted or its database dropped): the background embedding (`DocumentIndexer`, its queue
   per tenant), a knowledge source's run and its schedule (`SourceSync`, `SourceScheduler`), a scheduled task's run,
   its schedule and the runs taken up at the start (`TaskRunner`, `TaskScheduler`, `TaskRunCleanupJob`), an answer
   still streaming with its sandbox session and lease (`ChatService`, `Sandboxes`), a conversation's title
   (`ConversationTitles`), an A2A task still answered and its webhooks (`AssistantAgent`, `A2aTaskCleanupJob`), the
   chat files' cleanup (`ChatFileCleanupJob`) and the daily report (`DailyReportJob`). For each: does it end or fail
   cleanly, does it open the dropped database's pool again, does it write a row, a file or a usage record for a
   tenant that is gone, does it send a mail or call a provider, a store or a connected software after the
   deactivation - one row each in the walkthrough. A deleted or renewed MCP key stops at once, the tenant's
   other MCP keys keep working (mcp.md "MCP keys"). A deleted connection leaves every key's software
   (`tenant_mcp_key_connection` and `tenant_api_key_connection` cascade), a deleted task every key's tasks
   (`tenant_mcp_key_task` and `tenant_api_key_task`), a deleted area every key's areas (`tenant_mcp_key_area`), a
   deleted key takes its choices with it. The instructions a connected software gives are kept on its connection (at
   most 2,000 characters) until its address changes or it is deleted - as `docs/privacy.md` says. **A2A** (a2a.md): a
   deleted or renewed MCP key, or one whose `a2a` door is closed, is unknown at `/a2a` at once - its stored tasks and
   webhooks stay until the retention or the tenant's deletion (the index row keeps the key id; nobody reaches them
   any more); the admin's switch `a2a_allowed` taken away stops the tenant's A2A connections at once (not offered, the
   chat refused, a task's `call_agent` fails) without deleting them; a deleted A2A connection takes its skills
   (`mcp_tool` rows) and - if it answered the chat - the answering mode with it, a conversation's stored context, task
   and skill choice stay as rows that nobody reads; deleting a conversation that waits for an agent's answer cancels
   the agent's task best effort.
4. **Are the user rules kept?** A tenant never without a tenant admin, a user deleted with its conversations and files
   (as decided), user names unique per tenant (2.0.0: one person may have a user in several tenants - the admin's
   name taken everywhere; the switch `POST /tenant/account/switch` and `POST /login/wam`'s choice issue a token of
   that tenant's user only; the admin's `users/from-tenant` copies name, display name and language, no password, and
   marks source and new user as one person's (`tenant_user.person` - without WAM the account's tenants and the switch
   go by it, a user created by name is nobody else's; what happens to the mark when the source is deleted or a user
   renamed), wrong passwords lock an account for a while (`failed_logins`, `locked_until` - outside the entity, lifted
   by a password an admin sets), a
   password set by the admin ends the old sessions, WAM: assigned, approved, refused - **access needs a group**
   (2.0.0, 7 November 2026): a user who is no tenant admin gets in only through a group that counts for it (its
   password login, `POST /login/wam` - 409 `user.noGroup` -, the choice and switch of tenants, every request through
   `ActiveTenantUserRule` / `TenantUserService.inAGroup`); every tenant has the group "Standard" without rights (created
   with the tenant, by V17 for the tenants of before), into which a user created by hand without `groupIds`, an access
   request assigned and a user added from another tenant go (`joinStandard`) - never a user the IDM creates
   (`accountsFromIdm`), which is active, has no password and gets no further once the IDM drops it from every group of
   the tenant (its account and conversations stay).
5. **Do the user groups stay consistent?** A name unique per tenant whatever the case; members, areas and connections
   only of the group's tenant (400/404 as tenancy.md says), also through a user's `groupIds`; members and areas left out
   on update stay, a rename keeps the WAM group, an empty one removes it; a group with a WAM group has its members from
   the IDM alone (2.0.0): members sent 400, assigned rows dropped when the WAM group is set, a user's `groupIds` pass
   over it, only the admin sets the WAM group under WAM (409 for a tenant admin); a deleted user, group, area or connection
   leaves every group (`ON DELETE CASCADE`), the group itself stays; the IDM groups kept on the user
   (`tenant_user_wam_group`) go with the user and are deleted when the app starts without the check; two requests at
   once store one list, never a mix.
6. **Is what is deleted really gone - and nothing else?** One row per kind in the deletion matrix below, against
   `docs/privacy.md`; nothing of another tenant or user touched. A file store a task reads or writes into, or a
   knowledge source reads, is refused (409), not deleted - a mailbox store the same, and nothing in the mailbox itself
   is ever deleted by Zirmo; a CSV a task wrote stays on the store when the task or the tenant goes (it is the
   tenant's file, docs/privacy.md) - and while a source reads it, not pointed at another server, folder or library either;
   a knowledge source removed takes its `source_file` rows and either its documents with their chunks and originals,
   or leaves them as ordinary uploads (`origin = UPLOAD`, `source_id` NULL) - the tenant database first, then the
   management row, so a failure in between leaves a source to remove again, not documents nobody owns; a source
   pointed at another store or folder deletes its documents and rows the same way; a task takes its runs, the lines they read, its rows (`task_run_row`), its read positions - of files and of documents - and its key assignments with it, and a run
   still open is never deleted under its own feet (409); both go with the tenant.
7. **Do the knowledge areas stay consistent across the two databases?** Assignments of a deleted group, key or tenant
   go with it (`ON DELETE CASCADE`); an area holding documents, a handbook website or a knowledge source writing into
   it is not deleted (409 `knowledgeArea.hasDocSite`, `knowledgeArea.usedBySource`); a document's `source_id` names a
   management row without a foreign key - a source deleted halfway leaves documents a new removal still finds - and a website replaced or deleted leaves no directory reachable (the
   decided race of two first uploads leaves one unreached, accepted) - and what the decided race with a
   concurrent upload leaves (a document with a deleted area's id, found by tenant admins only) is reachable and
   removable; an area id of another tenant is never assigned; a revoked key's conversations are deleted after the
   commit, and what a question still running at that moment stores is unreachable and goes with the tenant (as
   `docs/privacy.md` says).
8. **Is it tested?** Each failure halfway and each deletion has a spec (or the missing case and the spec it belongs in).

## Step 1 - Spawn the agents

**Every agent runs on Opus** - pass `model: "opus"` in each Agent call, whatever model the session runs on (decided
9 November 2026: a session on a larger model would hand it to every agent and use up the token budget at once). The
session's own model only verifies and joins the findings (Step 2). Never leave `model` out, and never start an agent as
a fork - a fork takes the session's model.

One agent per chosen part, in a single turn, plus the ground-truth run (review base section 1) of the specs above. Give
every agent "What this does", its part and paths, the key questions, sections 2 to 5 of the review base verbatim, and:

> You are reviewing the CURRENT STATE of a real codebase: not a diff. Read `CLAUDE.md`
> (only "Project overview", the sections marked "(always)" except "Open source" and "Changelog and version", "Backend tests (always)" and "Backend architecture" - "Frontend architecture" when your part has frontend paths; not "Repository and build", "CI" or the commands),
> `docs/architecture/tenancy.md` (part 3 also `tasks.md` "Data" and `file-stores.md` "Data"), `docs/privacy.md` and
> `local-data/review/decisions.md` first, then every file of your part in full. Context: Micronaut 5 / Java 25, Micronaut Data JDBC on the management database, one PostgreSQL database
> per tenant on the same server reached with plain JDBC through `TenantDatabases.dataSource`, Flyway for both schema
> sets, CREATE and DROP DATABASE outside a transaction, chat files in `FileStorage`. Read-only: do not edit files, do not
> run the application, never touch the developer's dev database.
> Deliver first a **walkthrough** of your part: one row per operation (create, update, deactivate, delete ...) -
> `Class.method` (path:line) · transaction yes / no / deliberately split · steps in order (management rows, tenant
> database, files, pool) · what is left when it fails after each step · verdict ✅ / ⚠️ / ❗. Every ⚠️/❗ row is also a
> finding. The agent of part 3 also delivers the **deletion matrix** (see "Step 2").
> Severity for this review: **Kritisch** = data of another tenant or user deleted or left reachable, data kept that
> `docs/privacy.md` says is deleted, a tenant without any admin and no way back; **Hoch** = a failure halfway that
> blocks creating or deleting the tenant again, a pool or database left open for good, a deactivated tenant or user
> still working, a run, sync, answer or job that **starts** for a deactivated or deleted tenant (a provider, store or
> software called, a mail sent, a row or file written), that goes on for a deleted one, or that wedges because its
> database is gone - work already under way when a tenant is deactivated runs to its end and is decided (register E09
> for the embedding, E16 for a task's run and a knowledge source's run): no finding, at most "Entscheidung prüfen", a membership or right of a deleted user, group, area or connection left behind, files left on disk for good, a revoked key's conversations kept, an area assignment or a document
> with an area left that nobody can clean up.

## Step 2 - Synthesize

Follow **section 6 of the review base**, then the report as in **section 7**. The skill's own sections:

- **Lebenszyklus**: the walkthroughs as one table per part, only ⚠️/❗ rows in full.
- **Lösch-Matrix**: one row per kind (tenant, user, user group, document, the uploads of an area at once, conversation,
  chat file - deleted and expired -, API key with its conversations, MCP key (one of several, with its `tenant_mcp_key_area` - and the tenant's keys
  with the tenant; its A2A tasks stay until the retention), A2A task - ended and expired, with its webhooks; a tenant's
  tasks with the tenant (`a2a_task_index` cascade, the tenant database dropped) -, provider key, MCP connection, A2A connection (its skills; the answering mode), knowledge
  area (taken off every API key, MCP key and group), handbook website - deleted and replaced -, access request, file store (also a mailbox, and one a task writes into), knowledge source - with its documents, keeping them, and pointed at another store or folder -, scheduled task with its runs and their rows, one run
  or every ended run of a task) · management rows
  (incl. area assignments and group memberships) · tenant-database rows · vectors · files · usage · what `docs/privacy.md` says · matches
  ✅ / ❌.
- **Kernfragen**: each key question answered in two or three sentences with the evidence (path:line, spec).
