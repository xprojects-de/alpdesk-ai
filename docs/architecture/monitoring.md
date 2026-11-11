# Monitoring: the daily report and the application log

Decided 12 October 2026: the operator gets one e-mail every morning with the
state of the installation and of every tenant, and reads the recent warnings and errors on the admin's page
"Application log" - both for the admin only, never for a tenant admin (`mail.MailSender`,
`mail.MailTemplate`, `logging.RecentLogAppender`, `DailyReportJob`, `ApplicationLogController` and the page).
There is deliberately no problem mail every few minutes: nothing here is that urgent, and the daily report is the
heartbeat.

## The report

- **`services.monitoring.DailyReportJob`**, cron `app.monitoring.daily-report-cron` (default 06:00, env
  `APP_MONITORING_DAILY_REPORT_CRON`) in `app.timezone`, on a scheduler of its own (`micronaut.executors.monitoring`),
  so a slow mail server holds up nothing else. To `app.monitoring.mail-to` (comma separated, env
  `APP_MONITORING_MAIL_TO`, every address checked at startup) over `mail.MailSender`; without recipients or a mail host
  nothing is sent. The job is built at start (`@Context`), not when the scheduler first runs it - so a wrong recipient or
  sender address stops the start instead of failing every report from the next morning on.
- **Always sent**, also when everything is fine: a report that does not come means the process or the mail server is
  down - which the process cannot tell itself. A report that cannot be sent is logged (WARN) and not repeated; the next
  one goes out the next morning.
- **Content**, over the last 24 hours (`repositories.monitoring.DailyReportRepository`, read-only across other features'
  tables; only active tenants):
  1. *Needs attention* (red, or "Nothing - everything is fine"): less than 10 % free on the disk of
     `app.storage.local.path`; a tenant's documents waiting (PENDING/PROCESSING) longer than an hour - the oldest
     `updated_at` of them, so a large ZIP import still working through its queue counts too after an hour; documents
     that failed in the period (`status = 'FAILED'` and `updated_at` in it); a tenant database that cannot be read;
     open access requests (WAM).
  2. *System*: `app.version`, the JVM's start (a restart in the period pointed out), the PostgreSQL version (and with
     it: the database is reachable), the size of the management database and of all the app's databases
     (`pg_database_size` of the management database and the databases in `tenant` only - the server may hold another
     application's database, which the app's role may not measure and which is no part of
     the app), the free space of the stored files' disk.
  3. *Tenants: data*: per active tenant its database size, active users, documents (all, ready, waiting, failed, new in
     the period).
  4. *Tenants: questions and usage*: per active tenant the questions of stored conversations (`message.role = 'USER'`)
     and the users who asked, the model calls of the period, the cost of the period and of the month so far (in
     `app.timezone`), each with the part of the admin's global keys (billed) - and the totals. A question through an
     API key without history or the MCP server is stored nowhere, so it counts only in the model calls.
  5. *Application log*: warnings and errors counted, the ten loggers that logged most with their newest message, the
     twenty newest entries with the exception's first line; a note when the buffer rolled over or the process
     restarted in the period.
- **Per tenant database**: one query over a connection of its own, closed at once (`TenantDatabases.openConnection`),
  outside the management database's transaction - **not the tenant's pool**: a pool keeps its connection open for half
  a minute, and reading hundreds of tenants one after the other took the server's last connections (found by the full
  suite on 12 October 2026; `DailyReportSpec` checks that no connection stays open). One that cannot be read is a point of block 1 and a row of dashes; the other tenants are
  reported all the same. The queries run once a day - their scans over `usage_record` and `message` get no index (no
  optimisation without a real case).
- **Without the management database** (`collect` throws): a report of its own ("database not readable", the root
  cause) with what the process knows without it - version, start, disk and the application log. Only reading falls
  back; an error in rendering is not taken for an unreachable database.
- **Nothing of a tenant's content** goes into it: no document or file names, no questions or answers, no names of
  people - counts, sizes, amounts and the tenants' names. The log lines carry no content either (the rule of
  `GlobalExceptionHandler.rootCauseMessage` and the log's own rule); docs/privacy.md says what is mailed.
- **At the admin's request**: `POST /admin/daily-report/send` (`controller.monitoring.DailyReportController`,
  `Roles.ADMIN`, `DailyReportJob.sendNow`) sends it now - the test of the mail settings, button "Send daily report now"
  on the admin's page "Application log" (`applicationLog` slice: `sendDailyReport`, snackbar with the number of
  recipients; a refusal reloads the log, which then shows the warning). Answers
  `{recipients}`, 409 `dailyReport.notConfigured` without mail host or recipients, 502 `dailyReport.sendFailed` with the
  mail server's reason. Unlike the scheduled run, a failure is the answer, not only a log line. Not in a transaction:
  it reads every tenant database and waits for the mail server.

## Mail

- **English**, like everything the backend writes (i18n.md "The backend knows no languages"). `DailyReportMail` holds
  the texts and which value goes where; the layout is in small HTML building blocks under `resources/mail/`
  (`page`, `footer-report`, `detail`, `report-section|subtitle|line|table|row|head|cell`), Outlook-safe (tables with
  `bgcolor`, an MSO wrapper for the 760px width, inline styles). The header and the section lines take the
  installation's name and colour (`BrandingService.installation()`, branding.md); everything else is neutral grey.
- `mail.MailTemplate` fills a block: text always HTML-escaped, only `Html` (a filled block) inserted as it is, every
  placeholder required. No template engine and no micronaut-email: one mail over SMTP does not need them.
- In "dev" (`./gradlew run`) the mails go to a local MailHog (`application-dev.properties`: `127.0.0.1:1025`, UI on
  http://localhost:8025, started by hand - CLAUDE.md "Commands"); the specs use GreenMail.
- `mail.MailSender` sends over SMTP (Jakarta Mail / Eclipse Angus, used under EDL-1.0; `app.mail.host|port|security
  (STARTTLS, SSL, NONE)|username|password|from`, env `APP_MAIL_*`; with a host the sender must be an address, checked
  at startup; 10 s timeouts).

## Application log

- `logging.RecentLogAppender`, appender `RECENT` in `logback.xml` with a `ThresholdFilter` WARN and `maxSize` 1000:
  every event is kept as a flat `Entry` (time, level, logger, thread, formatted message, stack trace capped at 4000
  characters) - never the event itself, which holds its arguments and MDC. In memory only: a restart empties it, the
  container log keeps everything.
- `services.monitoring.ApplicationLogService` finds the appender on the root logger **by its type**: the AOT jar
  replaces `logback.xml` with generated code (`replaceLogbackXml`), which builds the appender with its filter and
  `maxSize` but gives it no name. `logback-classic` is therefore an `implementation` dependency.
- `GET /admin/application-log?level=ERROR|WARN&search=` (`controller.monitoring.ApplicationLogController`,
  `Roles.ADMIN` only): newest first, `level` ERROR for errors only (another value 400 `applicationLog.levelInvalid`),
  `search` case-insensitive in logger, message and stack trace; with the buffer's `capacity` and the process start.
  Read from memory, so no blocking executor.
- Frontend page "Application log" (`/dashboard/application-log`, menu group "System" of the admin's dashboard,
  `application-log-list`, slice `applicationLog`): filter "Warnings and errors" / "Errors only", search while typing,
  a row opens to the thread, the full logger and the stack trace; the button "Send daily report now" in its header.
- Not yet in the report: checking the tenants' sandboxes and MCP connections in the report.

## Specs

`DailyReportJobSpec` (real SMTP with GreenMail, data from mocks: every block's texts, the totals, a tenant database
that cannot be read, what needs attention as a data table, the log block, restart and buffer notes, escaping, the
report without the database, a failed scheduled send, `sendNow` with its 409 and 502, a wrong recipient or sender stopping a real context's start, sizes),
`DailyReportSpec` (the queries against real databases: a tenant's documents and questions, usage in the period and
the month, active tenants and users, sizes, access requests; one report end to end over GreenMail; rights),
`DailyReportControllerSpec` (409 with its code without mail settings), `ApplicationLogSpec` (the endpoint: levels,
search, stack trace, capacity, 400, only the admin), `RecentLogAppenderSpec` (the buffer), `TenantApiKeySpec` (the
tenant key's 403 on both routes); frontend `application-log.reducer.spec.ts`.
