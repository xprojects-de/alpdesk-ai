# Tenancy: management database and one database per tenant

Decided 6 October 2026, before any of it was built. Status per part at the end.

## Two kinds of databases on one PostgreSQL server

All databases run on **one PostgreSQL 18 server with pgvector** (`pgvector/pgvector:pg18`, the `postgres` service of
the compose files):

- **The management database `alpdesk_ai`** (`datasources.default`, Flyway `db/migration/`, Micronaut Data
  repositories): tenants, tenant users, API keys, the provider keys of tenants and admin, model prices, usage. Whatever
  the admin manages or bills lives here - never in a tenant's database.
- **One database per tenant `tenant_<id>`** (Flyway `db/tenant/`, its own small migration set): the tenant's
  documents, their chunks with the embeddings (pgvector), the conversations and messages of its chat. Nothing in it
  refers to another tenant, so a tenant can be exported, restored or deleted as a whole (`pg_dump` / `DROP DATABASE`),
  which also covers a GDPR deletion.

Rejected, with the reasons:

- *One database with a `tenant_id` column*: one forgotten `WHERE` shows another tenant's documents and chats.
- *One schema per tenant*: Micronaut Data would work unchanged, but the separation is weaker and a tenant cannot be
  moved to its own server.
- *One PostgreSQL container per tenant*: the app would have to start containers through the Podman socket (a security
  risk) and every tenant would bring its own ports, volumes, backups and updates.

## How a tenant database is reached

- `services.tenant.TenantDatabases` does all of it. The tenant row stores its **database name**
  (`tenant.database_name`, `tenant_` + 16 random characters): a database left behind by a deleted tenant (a failed
  drop) is never taken over by a new tenant, as an id-based name could be. The server and the credentials are the
  management database's (its role owns the tenant databases); the server address is read from a live connection, since
  the specs' Testcontainers URL names none. A tenant database's URL is the management database's with the name
  replaced - its parameters (`sslmode`, a certificate, ...) included, so a verified TLS connection to the one is a
  verified one to every tenant database too (fixed 23 October 2026).
- **Creating a tenant**: the row is committed first, then `CREATE DATABASE` (it cannot run in a transaction) and the
  tenant migrations (Flyway, `db/tenant/` - on connections of its own with `logServerErrorDetail=false`, so a
  migration failing on a tenant's data is logged without the row's values; without the pools' `options`, whose idle
  limit would end a long migration). If the database cannot be created or migrated, the row is removed again, the
  half-made database dropped and the admin gets 503. **Deleting**: the row goes first (with its users and keys by cascade), then
  `DROP DATABASE ... WITH (FORCE)`; a failed drop is logged and leaves an unreachable database behind.
- **On start** (`TenantDatabaseMigration`) every tenant database is migrated, and one that is missing is created - the
  process may have stopped between the row and the database. A tenant that fails is logged and skipped. It runs on the
  context's `StartupEvent`, before the HTTP server binds its port, so no request meets a tenant database on the previous
  schema; the indexer takes up unfinished documents on `ServerStartupEvent`, after it. Always on in an installation;
  only the specs switch it off (`app.tenant-databases.migrate-on-start=false` in `application-test.properties`): every
  spec class starts a context of its own, the suite's tenants pile up in one database server, and each start migrating
  all of them again cost three quarters of the suite's time (measured 23 October 2026). `TenantDatabasesSpec` checks it
  with a context of its own that switches it on, and checks that it is off in the specs' contexts.
- Migrations open short-lived connections of their own, not the pool: on start all tenants are migrated. Whatever
  reads every tenant one after the other does the same (`TenantDatabases.openConnection`, closed at once): the
  indexer looking for unfinished documents on start, the daily report (monitoring.md) and the hourly cleanup of the
  chat's files (`ChatFileCleanupJob`, code-execution.md). Through the pools, every tenant would keep a connection open
  for half a minute - with hundreds of tenants the server's last ones (the full suite failed this way on 12 October
  2026, and again on 19 October with the cleanup; `DocumentSpec`, `DailyReportSpec` and `ChatFileRetentionSpec` check
  that none stays open).
- Each tenant gets a **small HikariCP pool of its own** (at most 3 connections, `minimumIdle` 0, idle connections closed
  after 30 s), opened on first use - hundreds of tenants hold only the connections in use. PostgreSQL's
  `max_connections` (100 by default) bounds the tenants working at the same moment; raise it when that becomes real.
- **Moving a tenant to a server of its own** is prepared, not built: it needs a
  server address and credentials per tenant next to `database_name` - added when the first tenant needs it.
- Micronaut Data binds its repositories to configured datasources at compile time, so it cannot reach databases created
  at runtime: the tenant database is accessed with plain JDBC (`repositories.document`, `repositories.chat`). That is
  little code - a handful of tables - and langchain4j brings the vector part itself (`PgVectorEmbeddingStore` takes a
  `DataSource`); the chat history is plain tables of our own, not langchain4j's chat memory.
- The tenant comes from the authentication (`TenantContext.tenantId`), never from a path, query or body value.
- **The tenant's sandbox** for Python and shell in the chat ([code-execution.md](code-execution.md) "One sandbox per
  tenant") is a container of its own per tenant, never shared: its addresses and its token (encrypted, like the
  provider keys) are columns of the tenant row in the management database, entered by the admin. Deleting the tenant
  deletes them; the operator takes the container down (it holds nothing of the tenant between answers).

## Who does what

Decided 6 October 2026: every company has its own admins who keep its settings, and plain users who use them.

| Who | How | Role | Reaches |
|-----|-----|------|---------|
| The admin (the operator) | `POST /login` with `APP_USERNAME`/`APP_PASSWORD`, JWT | `ROLE_ADMIN` | everything: the admin API (`/admin/**`, `/version`) and the dashboard - tenants, model catalog and prices, global keys, which models a tenant may use, whether it may use the global keys; and for every tenant what its tenant admins can do |
| A tenant admin | a tenant user with `tenant_user.admin`, JWT with tenant and user id | `ROLE_TENANT_USER` + `ROLE_TENANT_ADMIN` | everything a user reaches, and `/tenant/admin/**`: the tenant's users (other tenant admins too) and user groups, its provider keys, its client software's API keys, its embedding model, search language and company knowledge with its knowledge areas, its assistant (instructions, settings, templates), its usage |
| A user | `POST /login` with their own user (with WAM on: through the proxy, "Sign-in through a WAM proxy"), JWT with tenant and user id | `ROLE_TENANT_USER` | `/tenant/**` without `/tenant/admin/**`: chat (with files in the conversation), own account - nothing that creates embeddings or changes a setting |
| An MCP client (Claude Code, Cowork) | one of the tenant's MCP keys `mcp_...` (`McpKeyTokenValidator`) | `ROLE_MCP` | `/mcp` only: asks the assistant like a user who sees no documents, searches the key's areas, uses the connected software given the key (some of it directly) - see [mcp.md](mcp.md) |
| The client software | API key `ai_...` (`ApiKeyTokenValidator`) | `ROLE_TENANT` | `/api/v1/**`: the tenant itself, no person behind it - chat (with the templates' names to choose; a key without history - "Verlauf speichern" off - answers every question on its own and stores nothing, see [ai-features.md](ai-features.md)), and the company knowledge plus the knowledge areas assigned to its key (upload - into an area by name -, list, delete), the reading tools of the connected software given its key, with conversations of its own key only; never users, keys or settings |

The admin and the tenant admins use the same services: `/admin/tenants/{id}/X` and `/tenant/admin/X` have the same
sub-paths (`users`, `groups`, `api-keys`, `provider-keys`, `documents`, `knowledge-areas`, `embedding-model`, `search-language`,
`assistant`), the frontend's `TenantScope` (`"self"` or a tenant's id) picks one. Which models a tenant may use stays
the admin's (it decides prices and billing); the tenant admin chooses the embedding model among them, and the language
the full-text search reads the documents in (`tenant.search_language`, chosen by the admin when the tenant is created -
[ai-features.md](ai-features.md) "The search language").

A tenant has **several users** (one per employee), created by the admin or a tenant admin. A tenant admin **cannot
lock itself out**: deactivating itself, taking its own admin rights away or deleting itself answers 409.

**An admin may see a user's chats and set its password** (decided 6 October 2026): the admin and a tenant admin set a
user's password without the user having to change it afterwards, and may thus log in as that user. Wanted - the
company's admin is responsible for its accounts. There is no view of another user's chats in the frontend.

**One person, users in several tenants** (decided 5 November 2026, roadmap point 51 package 0 - an installation may run topic
tenants and a general chat, and one person may work in both): a username is **unique per tenant**
(case-insensitive, `tenant_user_tenant_username_ci_uq`); the admin's username is taken in every tenant. A token stays
one user's of one tenant (`TenantUserClaims`: tenant and user id) - nothing of the isolation changed; switching
tenants is a new token.
- **Password login** (`TenantUserService.authenticate`): a username in one tenant signs in as before; in several it is
  refused like a wrong password unless the login names the tenant - the tenant's own login page `/login/<key>` sends
  its key as the header `X-Login-Key` with Micronaut's `POST /login` ([branding.md](branding.md); read by
  `AuthenticationProviderUserPassword`, in the CORS allowed headers; ignored with WAM on). A wrong key is refused like
  a wrong password. The neutral `/login` says after a refused attempt - any refused attempt, so the answer tells
  nothing of which usernames exist - that a name in several companies signs in on the company's own login page.
  **Wrong passwords lock the account for a while** (decided 13 November 2026, roadmap point 70): counted per account
  (`tenant_user.failed_logins`), not per address - behind the reverse proxy every request has the proxy's. The one
  reaching `app.login.max-attempts` (5) sets `locked_until` to now plus `app.login.lock-minutes` (15); until then every
  password is refused, the right one too, after the same hashing - the answer and its time those of a wrong password,
  the frontend shows its usual message. Every attempt is counted before its password is checked, in one statement
  (`TenantUserRepository.countLoginAttempt`): attempts sent at once cannot outrun the count, so no more than the limit
  are ever tried. Refused attempts while locked do not count (nobody prolongs the lock). Counted
  at the login and at the switch (on the user switched to); an unknown name or a wrong login key counts on nobody. The
  right password resets the count with the last login, a password an admin sets lifts the lock. Not for the admin's
  account (no row, and it would lock out the operator) nor with WAM (no passwords).
- **The switch** (`POST /tenant/account/switch {tenantId, password?}`, `AccountController`): a new token - the same
  answer as a login - for the user of the same username in that tenant, who must be allowed to log in (404
  `account.switchTenantNotFound` otherwise). The account's `tenants` carry the tenants' login keys only with WAM
  (the switch then updates the remembered login page) - without WAM the same name may be another person's. With WAM the proxy's header proves the person (checked on every request,
  see below) - no password. Without WAM a username proves no person (the admin may create "meier" in two tenants for
  two people, and a tenant admin may create a user under any name its tenant does not have yet), so **only the users
  of the same person count** - those the admin added from one another (`tenant_user.person`, below): the account lists
  no other tenant and the switch answers 404 for any other, as for a tenant without the name, so nobody learns
  through a user of a guessed name which companies use the installation (security review 12 November 2026). The
  switch then takes the **target user's password** (400 `account.passwordRequired` /
  `account.switchPasswordWrong` - a 400, never a 401, which would log the person out): a login into the other tenant
  from the menu. Two accounts, two passwords. `GET /tenant/account` names the tenants with a user of this username
  who may log in (`tenants`, the current one included; without WAM only users with a password - a user the admin added
  from another tenant is not the person's until it has one); the shell's user menu shows them with two or more and switches
  (without WAM a dialog asks the password); the frontend resets every slice like a logout
  (`store/logout.meta-reducer.ts`), ends what waits in a queue (`endedByLogout` - the new token is stored before the
  page reloads) and starts the tenant area anew with the new token. Every tab of the browser shares the token: another
  open tab reloads when it changes (`AuthEffects.otherTabSession$`), so it never goes on with the other tenant's.
- **The admin** creates a user with a name that exists in another tenant, and adds a person's existing user to
  another tenant from the tenant's users (`POST /admin/tenants/{id}/users/from-tenant {userId, active?, admin?}`,
  "Benutzer aus anderem Mandanten": the same username, display name and language, no password - without WAM the
  admin sets one; 404 for an unknown user, 409 for a name taken in the tenant). It marks both as **one person's**:
  `tenant_user.person` - the id of the user first taken from, on every user of the person; NULL for a user created by
  name, also when its name is another tenant's user's. With WAM it is not read (the proxy proves the person). **The admin only**: a tenant admin
  must not see another tenant's users. A tenant admin without WAM creates a user by name as before - allowed now that
  the name is unique per tenant only.
- Not built (decided 5 November 2026): one person account with one password for all tenants; a default tenant set by
  the admin (the browser remembers the last choice). Passwords: at least 12 characters, PBKDF2-HMAC-SHA256 with 600 000
iterations (`security.PasswordHasher`); an unknown username takes as long as a wrong password, and so does a user without a password. A user changes its own
password with the current one (`PUT /tenant/account/password`). **The frontend's language** of a user is set by the admin or a
tenant admin in the user dialog (default English; through the WAM proxy the browser's language) and changed by the user
itself in the shell's menu (`PUT /tenant/account/language` - with WAM for every user of the person, without WAM for its own user only; it does
not end its sessions) -
[i18n.md](i18n.md).

`security.ActiveTenantUserRule` asks on every request of a tenant user whether the user and its tenant are still
active - and, for `/tenant/admin/**`, whether the user is still a tenant admin: deactivating, deleting or taking the
admin rights away ends that access at once, although the JWT (with its roles) is valid for a day. The path is compared
below the context path (`micronaut.server.context-path`) - behind a proxy under a sub-path a former tenant admin kept
the tenant admins' API until the token expired (`ContextPathSpec`). **A password set by
an admin ends the user's sessions** (`tenant_user.sessions_valid_from`, compared with the JWT's `iat` in the same
query): whoever misused the account is out at once. **So does a new username**: with WAM the old person's proxy
still sends the old name, which matches the old token - the account went to someone else (`LoginModesSpec`). The
user's own password change does not end its sessions.

**Deleting a user deletes its conversations** with their files (after the commit, `ChatService.deleteAllOf`) - nobody
could open them any more; what cannot be removed stays behind unreachable.

**The documents belong to the tenant, chats to the user**: every user finds the company's knowledge in the chat
without seeing its list; a conversation and its history are visible to the user who had it only. A document of a
**knowledge area** is found only by the members of the groups and the keys assigned to the area, and by every tenant
admin (decided 9 October 2026 - [ai-features.md](ai-features.md) "Knowledge areas").

## User groups

Decided 16 October 2026 (roadmap point 33): with many users, setting each one's level per connected software and
each one's knowledge areas was a lot of work. **Rights are given to groups, users are members of groups** - a user
has no rights of its own.

- **Data** (management database, `V7__user_groups.sql`): `user_group` (name unique per tenant whatever the case, an
  optional `wam_group`), `user_group_member`, `group_mcp_access` (the level READ/WRITE per connection) and
  `group_area` - all with `ON DELETE CASCADE`: a deleted user, group, connection or area leaves every group.
- **What a user gets** (`services.tenant.UserGroupService`, the only place that reads it): for each connected software
  the highest level of its groups, and the union of their knowledge areas. **Access needs a group** (decided
  7 November 2026): a user who is no tenant admin gets in only while one of its groups counts for it - its password
  login (409 `user.noGroup` after the right password, a wrong one the plain 401 ("wrong password" would
  mislead)), the WAM sign-in (409 `user.noGroup` - nothing to wait for), the choice and switch of
  tenants, every request
  (`TenantUserService.inAGroup`, `ActiveTenantUserRule`, which keeps the request's IDM groups first). A group without
  rights gives the base - the chat and the company knowledge. Taken out of its last group - by a tenant admin, or in the
  IDM for a group with a WAM group - the user keeps its account and conversations and is out with its next request;
  put back, it is in again. Tenant admins and the admin need no group. A tenant admin finds every area anyway; its software comes from its groups
  like anyone's. `McpTools`, `SoftwarePrompts` (and through them the pending actions) and `KnowledgeAreaService
  .scopeOfUser` ask it; nothing else changed. API keys and the MCP keys keep their own rights (`tenant_api_key_area`,
  `tenant_mcp_key_area`, `tenant_mcp_key_connection`, `tenant_api_key_connection`, the connection's switch) - `/api/v1` and `/mcp` did not change.
- **The group "Standard"** (7 November 2026, replacing "no default group" of 16 October): every tenant has one without
  rights - created with the tenant (`TenantService.create`), for the tenants of before by `V17` (empty: their users
  are assigned by hand after the update, CHANGELOG). Where a person gives access, the new user goes into it: created by
  hand without `groupIds` (`[]`: none), an access request assigned, a user added from another tenant
  (`UserGroupService.joinStandard`). Not a user the IDM creates - its access follows the IDM's groups. A tenant admin
  may rename or delete it; then nobody joins it any more.
- **No direct rights per user** besides the groups (decided): an exception is a group of one, so a right never needs
  "where does it come from".
- **API**: `/admin/tenants/{id}/groups` and `/tenant/admin/groups` (`UserGroupAdminController`,
  `TenantAdminUserGroupController`, one service): name, WAM group, members (`userIds`), areas (`areaIds`) and the web
  search (`webSearch`, V11 - its members may search the web in the chat, [ai-features.md](ai-features.md) "Web
  search"; off by default) - left out,
  they stay unchanged on update (also the WAM group: a rename must never drop the IDM check; an empty one removes it); 400 for another tenant's user or area, 404 for another tenant's group, 409 for a name
  taken. A user takes `groupIds` (400 for another tenant's group). The level per software is set with the group
  (since 25 October 2026, roadmap point 43): `levels: [{connectionId, level}]` on `.../groups` - none on create, left
  out on update unchanged, given they replace the group's (a connection left out: no access); 400 `group.unknownConnection`
  for another tenant's software, `group.levelRequired` for an entry without READ or WRITE; saved with the group in one
  transaction, so a refused level keeps nothing of the save. That is the one place a level is set: the connection's access
  list shows the levels from the software's side, read-only (`GET .../mcp-connections/{id}/access`, [mcp.md](mcp.md));
  its write route `PUT .../access/groups/{groupId}` is gone - answered 403 like every unknown route.
- **No migration of the rights** (V7, decided 16 October 2026): `mcp_access` and `tenant_user_area` were dropped;
  after the update a tenant admin creates the groups and assigns the users (CHANGELOG 1.5.0).
- **UI**: the panel "Gruppen" below the users (the tenant admin's page "Benutzer", the admin's tab "Benutzer") with a
  column "Software" (each connection with its level), the group dialog with everything a group gives - members, areas,
  web search, WAM group and one row per connected software with its level -, the user dialog with its groups, the
  software's access list by group, read-only (with a hint where the levels are set).
- **Checked against the WAM proxy's IDM groups** (optional, decided 16 October 2026): see "Sign-in through a WAM
  proxy". **Since 2.0.0 a group with a WAM group takes its members from the IDM alone** (decided 5 November 2026,
  roadmap point 51 package 3 - one mode, no switch: a switch would give one field two meanings): it counts for every
  user of its tenant whose stored IDM groups name it, and for nobody else, assigned or not (`UserGroupRepository
  .GROUPS_OF_USER`, the one place every right is read through). Members sent for such a group are refused (400
  `group.membersFromIdm`, an empty list is none); setting a WAM group removes the assigned members, so nothing stale is
  left; a user's own `groupIds` pass over such a group. The group dialog then hides the member picker, shows "Mitglieder
  aus dem IDM" read-only and warns before the assigned members are replaced; the users' table marks "Aus dem IDM". A
  group without a WAM group is kept by hand as before. **A change of behaviour** against 1.9: such a group's members
  were "assigned and in the IDM", now "in the IDM" (CHANGELOG).
- Specs: `UserGroupSpec` (API, validation, isolation, highest level and union, cascades, the levels saved with the group,
  members of a group with a WAM group refused and dropped), `WamGroupsSpec` (membership by the header alone, accounts from
  the IDM); the frontend's `user-group.util.spec`, `user-group.reducer.spec` (members kept alike in the users
  and the groups shown).

## Sign-in through a WAM proxy

Decided 7 October 2026 (companies often run intranet applications behind a WAM proxy, which authenticates the person
and passes their user name on in a header):

- **Off unless switched on**: `app.wam.enabled` (`APP_WAM_ENABLED`, default false), the header `app.wam.user-header`
  (`X-Remote-User`) and optionally `app.wam.name-header` for the person's name (`security.WamProxy`). Off, the header
  counts for nothing anywhere.
- **The app trusts the header completely** - no address list or shared secret of its own (decided): the proxy removes
  it from every request from outside, and the app is reachable through the proxy only (`Backend/podman/public/README.md`).
- **Sign-in**: the login page asks `GET /login/wam` (anonymous, `{enabled, groupsChecked}`) and signs in with `POST /login/wam`
  (`controller.login.WamLoginController`): a known user who may log in gets the same token as `/login`
  (`TenantUserClaims`, Micronaut's `AccessRefreshTokenLoginHandler`); anyone else waits - 409, never 401/403, which the
  frontend takes for a lost session. 400 without the header or with a user name that cannot be a username (3 to 100
  letters, digits, `. _ - @`). **A Windows domain account `DOMAIN\name` becomes `name@domain`** (`WamProxy.asUsername`,
  decided 7 October 2026): unique across domains - cutting the domain off could merge two people of different domains
  into one account. An admin creating such a user beforehand uses that form; the header check compares it the same way.
- **A person with users in several tenants** (roadmap point 51): `POST /login/wam` answers 200 without a token and
  with the tenants to choose from (`{tenants: [{id, name, loginKey}]}` - only those where the person may log in, by
  name; the login page shows each with its logo), and `POST /login/wam {tenantId}` signs in there (404
  `wam.tenantNotFound` for a tenant where the person has no user who may log in - also when it lost its last group
  there, as long as it may log in in another tenant: a 409 would keep the browser trying the remembered tenant and lock
  the person out of the others; 409 `user.noGroup` only when it may log in nowhere). One user who may log in: signed in
  at once. The browser keeps the last choice (`localStorage`, `utils/last-tenant.util.ts`) and sends it first, so a
  second visit needs no click; a stale one (404) is forgotten; the switch in the menu updates it. No default tenant
  set by the admin. The stored IDM groups stay per user row, refreshed by the person's requests in that tenant.
- **Someone unknown** becomes an access request (`access_request`, one per username, `AccessRequestService`): the admin
  lists them (`/admin/access-requests`, page "Zugangsanfragen" - in the nav only with WAM on), assigns one to a tenant
  - it becomes a tenant user without a password (`tenant_user.password_hash` NULL), active at once and/or a tenant admin
  if the admin says so (a tenant's first user), else inactive - or rejects it (asked again on the next visit). A
  request whose user the admin created directly goes at that person's next sign-in. The
  tenant admin approves an inactive user under "Benutzer", where one never signed in shows "Wartet auf Freigabe". A
  deactivated user, or one of a deactivated tenant, waits too.
- **Only the admin creates users and changes usernames while WAM is on** (decided 7 October 2026): a username is then
  a person's sign-in at the proxy - a tenant admin creating one could take a person meant for another tenant, renaming
  one to another person's sign-in would hand over the user's conversations. A tenant admin gets 409 for both
  (`TenantUserService.create`/`update` with the acting user); it still approves, deactivates, deletes, renames the
  display name and sets the admin flag. The page "Benutzer" shows no "Neuer Benutzer" then but a note, and the dialog
  locks the username (`usersCreatedByAdminOnly`). Without WAM nothing of this applies.
- **No passwords** for tenant users while WAM is on - tenant admins included: `/login` refuses every tenant user (with
  or without a password from before), a user is created without one (sending one is 400), none is set (400) or changed
  (409); the frontend hides the fields. **The admin keeps its password login** (`APP_USERNAME`), behind "Als
  Administrator anmelden" - also the way in when the proxy fails. A user who had a password signs in through the proxy
  when its user name equals the username.
- **The admin behind the proxy** (decided 7 October 2026): it reaches the app through the proxy too and has a WAM
  identity of its own. The login page tries the WAM sign-in first: a WAM name that is no tenant user waits ("Zugang
  angefragt", and becomes an access request the admin can ignore), one that is a tenant user is signed in as that user.
  Either way "Als Administrator anmelden" leads to the password login - in the second case after logging out first
  ("Sie sind abgemeldet" then offers it). Deliberately no list of admin WAM names that skips this.
- **Every request of a tenant user is checked against the header** (`ActiveTenantUserRule`): when the proxy sends
  another user than the token's (another person at the same computer) or none, the request is refused (403), the
  frontend logs out and signs the new person in. The admin's token does not depend on the header - the admin may be
  someone outside the WAM; its session lasts until it logs out (confirmed 7 November 2026 after a binding to the person
  at the proxy was tried and dropped: it needs a setting saying whether the admin is a person of the WAM).
- **IDM groups** (optional, decided 16 October 2026): with `app.wam.groups-header` (`APP_WAM_GROUPS_HEADER`, empty =
  off) the proxy sends the person's IDM groups, split at `app.wam.groups-separator` (`,` - a DN holds commas, so
  e.g. `;` there), trimmed, compared whatever the case. A user group with a **WAM group** then counts for a member only
  while the header lists that IDM group - assigned in Zirmo **and** in the IDM; a right taken away in the IDM is
  gone with the next request. **Fail closed**: a request without the header lists none, and while no groups header is
  configured (not set, mistyped) a group with a WAM group counts for nobody - the panel says so at the group (review
  of 16 October 2026: a forgotten setting must not hand the group to every member). Groups without a WAM group count
  as they are. Who looks the groups up is the proxy, not the
  app (no LDAP of our own): a commercial WAM usually can, Apache `mod_authnz_ldap` can, nginx takes them from an auth
  service through `auth_request` (`Backend/podman/public/README.md`). **Kept on the user** (decided): `ActiveTenantUserRule`
  writes the groups of each request to `tenant_user_wam_group` when they changed (with the user locked, so two
  requests at the same time store one list, never a mix), and every check of the user's rights goes by them. The
  software's tools are chosen when an answer starts and when a waiting action is decided: a change in the IDM or the
  groups counts from the next answer or decision on - one already running keeps its tools for its remaining rounds; the users' list shows the admins what the proxy
  sent last (`wamGroups`), which tells what a WAM group must say (plain names such as `Gruppe1`, or `CN=...`). Started
  without the check, the app deletes the stored IDM groups (`UserGroupService.forgetWamGroupsWhenUnchecked`). The header is trusted like
  the user header. A long list can exceed the server's header limit (`micronaut.server.netty.max-header-size`, 8 KB) -
  the proxy should send only the groups that matter. **Membership comes from the IDM alone** (since 2.0.0, see "User
  groups" - the decision of 16 October 2026, "assigned in Zirmo and in the IDM", is replaced).
- **Accounts from the IDM** (decided 5 November 2026, roadmap point 51 package 3): at the sign-in
  (`AccessRequestService.signIn`), for every active tenant with a group whose WAM group the groups header names, the
  person gets a user there if it has none - active at once, no password, display name from the name header, its IDM
  groups stored at once -, so one visit gives the person its account in a topic and in the general chat alike; an open
  access request of the name goes. A user that exists is not touched - only its membership follows. Then the sign-in
  goes on as above (one tenant: signed in; several: the choice). A person whose header names no WAM group of any tenant
  and who is user of no tenant waits as an access request. Taken out of every IDM group of a tenant (and in no other
  group there), the user keeps its account and conversations and gets no further - "Access needs a group" above
  (7 November 2026; before, it reached the chat and the company knowledge); a deactivated user stays as it is,
  while a deleted one is created again at the person's next sign-in as long as the IDM still lists it. Only with WAM on
  and a groups header configured; the first tenant admin of a tenant is still set by hand (an access request or a user
  the admin creates) - the IDM creates plain users only. **Only the admin sets, changes or removes a group's WAM group**
  (review of 6 November 2026; also while WAM is off since the security review of the same day - one set then would
  take effect the day WAM is turned on): it gives everyone in that IDM group a user in the tenant, and under WAM
  only the admin decides who gets a user (decided 7 October 2026) - a tenant admin gets 409 `group.wamGroupByAdmin`
  (a save that keeps it passes), its group dialog shows the field locked.
- **Logging out** marks the tab (session storage): the login page then shows "Sie sind abgemeldet" with "Erneut
  anmelden" instead of signing in again at once. A forced logout (the 403 above) signs in again at once.
- Specs: `security/LoginModesSpec` runs the same scenarios once without and once with WAM (`LoginWithoutWamSpec`,
  `LoginWithWamSpec`) - each way works on its own, nothing of one works in the other, the admin's login is the same in
  both, a tenant admin creates users and changes usernames only without WAM, a name in two tenants signs in at `/login`
  only with the login key, the switch with the target's password or the proxy's header; `WamLoginSpec` walks through
  requests and approvals, the choice of tenants and the switch; `TenantUserSpec` (off: the header counts for nothing). The
  frontend's `auth.effects.spec` / `auth.reducer.spec` keep the two sign-ins and their errors apart, `login.component.spec`
  checks that the login page signs in through the proxy exactly once and never again after a logout by the user, and
  `auth.interceptor.spec` that a 403 is a forced logout and the WAM sign-in carries no token. **End to end**: the smoke
  test runs the AOT jar behind a fake WAM proxy (it strips the header from outside requests, as a real one must) - step
  5 without WAM, step 8 with it: waiting, assignment, approval by the tenant admin, another person ending a session, no
  password login.

Every controller is secured with exactly one role, matching its path (`/admin/**` admin, `/tenant/admin/**` tenant
admin, the rest of `/tenant/**` tenant user, `/api/v1/**` client software); `TenantApiKeySpec`, `TenantUserSpec` and
`TenantAdminSpec` guard it.

## Status

| Part | Status |
|------|--------|
| Management database, tenants, API keys, `/api/v1/me`, `/api/v1/documents` | built |
| PostgreSQL with pgvector in every compose file and the specs' Testcontainers database | built |
| Tenant databases (create, migrate, repair on start, drop, pools) | built |
| Tenant users: admin management, login, own account and password, immediate deactivation | built |
| One person with users in several tenants: username unique per tenant, the WAM choice, the switch, the login key at `/login` | built (2.0.0) |
| Tenant admins: users, provider and API keys, embedding model, company knowledge, usage of their own tenant | built |
| User groups: levels per software and knowledge areas per group, optionally checked against the WAM proxy's IDM groups | built (1.5.0) |
| A group with a WAM group takes its members from the IDM; accounts from the IDM's groups at the sign-in | built (2.0.0) |
| Server and credentials per tenant (own instance) | prepared, not built |
| A sandbox per tenant for code execution (addresses and token at the tenant, the admin's check) | built |
