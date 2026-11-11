# File stores

Decided and built 13 October 2026 (roadmap point 28, package 1). A **file store** is a server,
bucket or library the app reads files from and writes files to on a tenant's behalf - SFTP and FTPS, and since 29 October
2026 S3 and SharePoint / OneDrive (roadmap point 44; Dropbox no longer planned, a local folder of the server no
longer since 29 October 2026). A task asks only `readable` and reads through `open` - a new type changes nothing in the tasks, nor in the
knowledge sources. A tenant has any number of them, kept by its tenant admins (and the admin per tenant). Today a store
can be added, changed, checked and removed; a scheduled task reads its files ([tasks.md](tasks.md), since 21 October
2026), and a knowledge source syncs a folder of it into the knowledge ([knowledge-sources.md](knowledge-sources.md),
since 27 October 2026); writing files comes when a case asks.

## Data

- **One table for every type** (management database, `file_store`, migration V10): `tenant_id` (cascade with the
  tenant), `name` (unique per tenant whatever the case - `fileStore.nameTaken`, 409), `type` (`SFTP`, `FTPS`, `S3`,
  `SHAREPOINT` since V15; a check constraint), `settings` (what the type needs that is no secret, JSON in `TEXT` like
  `mcp_tool.input_schema`), `secret` (the password, for S3 the secret access key, for SharePoint the app's client secret, `SecretCipher` - never
  sent out), `checked_at` / `check_error` (the last check's outcome).
  The management database because the stores are kept like the MCP connections and provider keys - small, the
  tenant's settings, read by the scheduler across tenants later. What a store's files contain never lands here.
- **Settings per type as a record**: SFTP and FTPS share `services.filestore.ServerSettings` (host, port, username,
  base path, confirmed fingerprint), S3 has `S3Settings` (endpoint, region, bucket, base path, access key ID),
  SharePoint `GraphSettings` (directory ID, client ID, site address, library, base path). A new
  type gets a record of its own and a case in `FileStoreType`, the migration's check and the switches of
  `FileStoreService` (settings, readable, base path, open, check, response) - no class hierarchy. `FileStoreClient`
  stays the interface of the two server types; `S3StoreClient` and `GraphStoreClient` answer the same `CheckResult`
  and `FileStoreSession` without it, since their check takes no address and no fingerprint.
- **Rules of the fields**: the host is a name or an IP address only (no protocol, port, user, path), stored in lower
  case; the port 1-65535, left out the type's (22, 21); the user name required, no control characters; the password
  required on create, kept as typed (a space may belong to it), no control characters; the base path absolute,
  without `.` or `..` and without backslashes, doubled slashes merged, a trailing one dropped, left out `/` - for S3 a
  folder in the bucket (a key prefix). S3's fields: the endpoint left out for Amazon S3, else `https://host[:port]` only
  (no user, path, query or fragment - `fileStore.endpointInvalid`; `http://` only to a host in
  `app.file-store.internal-hosts` - `fileStore.plainHttp`; scheme and host stored in lower case); the region required
  for Amazon S3 (`fileStore.regionRequired`), left out `us-east-1` for an own endpoint, lower case letters, digits and
  hyphens; the bucket by S3's naming rules (3-63 lower case letters, digits, dots, hyphens); the access key ID
  required, without spaces or control characters; the secret required on create. SharePoint's fields: the directory
  ID (the company's Entra ID - Microsoft's "tenant", called directory so it is never confused with ours) and the
  client ID GUIDs, stored in lower case; the site's address `https://<name>.sharepoint.com/...` or a OneDrive's
  `https://<name>-my.sharepoint.com/personal/...` - no other host or port, no user, query or fragment, no `..`; the
  host in lower case, doubled and trailing slashes dropped (custom domains and the national clouds are not offered);
  the library's name optional (none: the site's default library), without `/`.

## The server proves itself before the password goes out

- **A check** (`POST .../file-stores/{id}/check`) connects to the server. Without a confirmed fingerprint it stops
  after the key exchange (SFTP) or the TLS handshake (FTPS) and answers `UNCONFIRMED` with the server's fingerprint -
  nothing was logged in to, so the password never went to an unknown server. A tenant admin compares the fingerprint
  and confirms it (`PUT .../{id}/fingerprint`); the frontend checks again at once, which now logs in and lists the
  folder's first 50 names (`OK`). A server that later presents another key or certificate gets no login either:
  `CHANGED`, shown as a warning with both fingerprints; only confirming the new one takes it.
- **Fingerprints** are `SHA256:` and the Base64 of a SHA-256 (no padding) - for SFTP the host key's (MINA's
  `KeyUtils.getFingerPrint`, as `ssh-keygen -lf` shows it), for FTPS the leaf certificate's DER encoding.
- **FTPS and authorities**: a certificate an authority the installation trusts vouches for (the JVM's, with
  `USE_SYSTEM_CA_CERTS` the company's own) and whose host name matches is taken without a fingerprint - so renewing
  it changes nothing. Any other certificate (a server's own, common in a company's network) must match the confirmed
  fingerprint. Once the control connection's server proved itself, a data connection that does not resume its TLS
  session must prove itself the same way (`CertificateCheck`, `FtpsCertificateCheckSpec`) - else someone on the way
  could hand over files under a certificate of their own.
- **A failed check is the answer, not an error**: status `FAILED` with the code - `fileStore.unreachable`,
  `loginFailed`, `folderNotFound`, `tlsRefused`, `dataConnectionFailed`, `internalNetwork`, `unknownHost`, `failed` -
  and the code is kept with the store (`check_error`), so the list shows each store's state. Only the outcome is
  written (`FileStoreRepository.updateCheck`): a change saved while a check ran stays.
- **Another server needs the password again**: an update to another type, host or port drops the confirmed
  fingerprint and refuses to keep the stored password (`fileStore.passwordRequired`) - the password is not sent to a
  server it was not entered for. Renaming, another user name or folder keep both. For S3 another endpoint, region,
  bucket or access key ID needs the secret again; renaming or another folder keep it. For SharePoint another
  directory or client ID (another app) needs it again; another site, library or folder keeps it - its secret goes to
  Microsoft's login only, whatever site is read.
- **S3 and SharePoint have no fingerprint**: their server proves itself over HTTPS with a certificate an authority the installation
  trusts vouches for (the JVM's, with `USE_SYSTEM_CA_CERTS` the company's own); its check reads the folder at once and
  answers `OK` or `FAILED`. Confirming a fingerprint answers 409 `fileStore.noFingerprint`. A task or knowledge source
  may read one once its last check succeeded (`readable`, the rule of FTPS with an authority's certificate).

## Addresses

`FileStoreAddresses` checks a host when it is saved and again before every connection (its name resolved anew): it
must not lie in an internal network (`services.common.InternalAddresses`, shared with the MCP clients - loopback,
private, link-local, unique-local, 100.64/10) unless it is listed in `app.file-store.internal-hosts`
(`APP_FILE_STORE_INTERNAL_HOSTS`): a store in the company's network is common, but which hosts every tenant admin may
reach is the operator's decision. Not `APP_AI_PLAIN_HTTP_HOSTS` - that list means "plain HTTP to model servers". The
SFTP connection goes to the address the check resolved; FTPS connects by the name (its certificate names that, and
SNI needs it), which looks the host up again - the connection is dropped (`fileStore.addressChanged`) unless it
reached the address checked, before a fingerprint is shown or a password sent - so a name answered differently
between the check and the connection (an expired cache entry, DNS rebinding) leads nowhere the check never saw. An IMAP
mailbox connects by the name without comparing the address reached - a known limit (DNS rebinding), like S3's: its
password is protected by TLS with the server's identity checked (plain IMAP only to a listed host).

## Addresses of S3 stores

An own endpoint's host is checked like an SFTP or FTPS host - when saved and again before every session
(`FileStoreAddresses.normalizeEndpoint`, `requireAllowedEndpoint`), and a host taken off the list loses plain HTTP at
its next session. Amazon S3 (no endpoint) is reached at `https://s3.<region>.amazonaws.com`. **A known limit** (DNS rebinding): the
SDK's HTTP client resolves the name once more when it connects and the address reached is not compared with the one
checked - unlike FTPS. Not closed on purpose (29 October 2026): the secret never goes over the wire, and closing it
needs an HTTP client of our own; the MCP connections share the limit. In SECURITY.md.

## The clients

- **`SftpStoreClient`** - Apache MINA SSHD 2.20 (the stable line; 3.0 is a milestone). One `SshClient` for the app;
  each connection carries what its host key is checked against (`AttributeRepository` of the connect call, read by
  the client's `ServerKeyVerifier`). The client uses nothing of the server it runs on: no `~/.ssh/config`
  (`HostConfigEntryResolver.EMPTY`), none of its keys (`KeyIdentityProvider.EMPTY_KEYS_PROVIDER`), password
  authentication only. A folder is opened before it is listed, so a missing one is `folderNotFound`, not an error
  halfway through the listing.
- **`FtpsStoreClient`** - Apache Commons Net 3.13, explicit TLS (`AUTH TLS`) only; plain FTP and implicit FTPS are
  not offered. `PBSZ 0`, `PROT P` (the data connections are encrypted too), passive mode. Two rules:
  - **The data connection always goes to the control connection's server** (`setPassiveNatWorkaroundStrategy`),
    whatever address the server names in `PASV` - a server must not send the app to another host of its network.
  - **Data connections resume the control connection's TLS session.** Many servers require that (vsftpd's
    `require_ssl_reuse`, ProFTPD's default, FileZilla Server) and refuse a new session (`522`, `425`) - so nobody
    else can take over a transfer. Commons Net does not do it on modern Java: JSSE resumes a session it finds under
    the peer's host and port, and takes the port from the socket below - the data port. `ResumingDataSockets` layers
    the TLS socket over a plain socket that reports the control connection's port, and starts the handshake when the
    data is first read or written (the server starts TLS after the FTP command). No reflection, no JVM flag; TLS 1.2
    and 1.3. Found and proven with a ProFTPD on 13 October 2026; the common workaround through reflection into
    `sun.security.ssl` was not taken.
- **`S3StoreClient`** - AWS SDK for Java 2 (the version the Micronaut platform manages), with the JDK's
  `url-connection-client` set explicitly; the Apache, Apache 5 and Netty clients it brings are excluded in
  `build.gradle` (about 8 MB in the jar). It takes nothing of the server it runs on: credentials only from the store
  (`StaticCredentialsProvider` - never the default chain with the environment, `~/.aws` or an instance role, which would
  read with the operator's rights), an empty profile file, the region and endpoint always set (so neither the metadata
  service nor an `AWS_ENDPOINT_URL` is asked), no retry. An own endpoint is addressed path-style
  (`https://host/bucket/key`), Amazon virtual-hosted. Checksums only where S3 requires them (`WHEN_REQUIRED`) -
  compatible stores do not all know the newer ones. One `S3Client` per session, the HTTP client shared.
  - **Listing**: `ListObjectsV2` with the folder as prefix and `/` as delimiter, page by page until complete (a page
    holds 1000; more than 1000 pages are refused like too many entries, below; a page announcing more without a
    continuation token fails the listing, `fileStore.readFailed`) - objects are files (size, last modified), common prefixes subfolders (size 0); the object named like
    the folder itself (what a console creates for an empty folder) is neither.
  - **Reading**: `GetObject` with a `Range` - from any offset, past the end (416) nothing; a store ignoring the range is
    read up to the offset and cut after the limit.
  - **The check** lists the folder's first 50 names. S3 knows no folders: a folder with nothing below it cannot be
    told from a missing one - both `fileStore.folderNotFound`; the bucket itself may be empty.
  - **Failures**: no connection or timeout `fileStore.unreachable`; a TLS failure `fileStore.tlsFailed`; a refused key
    (`InvalidAccessKeyId`, `SignatureDoesNotMatch`, `AccessDenied`, 401, 403) `fileStore.accessKeyRefused`;
    `NoSuchBucket` `fileStore.bucketNotFound`; anything else while reading `fileStore.readFailed` (502).
  - **Rights**: the key needs `s3:ListBucket` and `s3:GetObject` only - the public README shows a policy.
- **`GraphStoreClient`** - SharePoint and OneDrive through Microsoft Graph: the token from Microsoft's own library
  (MSAL, `msal4j`, under 1 MB - decided 29 October 2026), the few Graph calls without its SDK (measured that day: the
  Graph SDK 75 MB, azure-identity 15 MB more, with their own Jackson, Netty and Kotlin) - the JDK's
  `HttpURLConnection`, the one client with a timeout per read, redirects not followed (one would take the token
  along). A OneDrive is a SharePoint site of its own and is entered
  by its address - one type for both (decided 29 October 2026). Microsoft's addresses are fixed in `GraphAddresses`
  (not a setting; the specs replace the bean).
  - **The app's token**: MSAL's `ConfidentialClientApplication` with the client secret against
    `login.microsoftonline.com/<directory>/`, scope `https://graph.microsoft.com/.default`, one application per session
    (its token cache lives as long); a token Graph refuses as expired (401) is fetched once more past the cache. The
    authority is fixed, so its validation and the instance discovery are switched off (calls of their own that add
    nothing); MSAL's HTTP client gets the connect and read timeouts and the TLS of `GraphAddresses`. Its refusals are
    read by Entra's number in the text (`AADSTS7000215: ...`). MSAL's own log is held to errors (`logback.xml`): it
    logs every token request and every refused login, which the check answers with its code. A certificate instead of
    the secret is one more `ClientCredentialFactory` when a company's policy asks for it.
  - **Site and library**: `GET /sites/<host>:<path>` resolves the address, the library by name from
    `GET /sites/<id>/drives` (case does not matter), none named the default one (`/sites/<id>/drive`) - once per
    session.
  - **Listing**: `GET /drives/<drive>/root:<folder>:/children` with name, size, last modified, `file` and `folder`, page
    by page through `@odata.nextLink` - only a next page on Graph itself is followed, the token goes with it. A page without its
    `value` list (a proxy's page answered with 200, a body cut off) fails the listing (`fileStore.readFailed`) - never
    an empty last page. Items that are neither file nor folder (a OneNote notebook) are left out.
  - **Reading**: the item's `@microsoft.graph.downloadUrl` (pre-authenticated, valid minutes) with a `Range` - without
    the token, and only to an HTTPS `*.sharepoint.com` host (`GraphAddresses.downloadAllowed`). Microsoft may answer
    the range with the whole file (200): read up to the offset and cut after the limit; past the end (416) nothing.
  - **Throttling**: Graph answers 429 or 503 with `Retry-After` under load - one wait of at most 30 s, then the request
    once more; otherwise `fileStore.throttled`. The other types do not retry: for Graph throttling is normal behaviour.
  - **Failures**: Entra's codes `AADSTS90002`/`900023` `fileStore.directoryNotFound`, `AADSTS7000222`
    `fileStore.secretExpired` (a client secret lives two years at most - the common failure), any other refused login
    `fileStore.clientRefused`; a site Graph does not find `fileStore.siteNotFound`, 401/403 `fileStore.accessDenied`
    (the app was not granted the site), `fileStore.libraryNotFound`, `fileStore.folderNotFound`; no connection
    `fileStore.unreachable`, TLS `fileStore.tlsFailed`, anything else while reading `fileStore.readFailed` (502).
  - **Rights**: the company's admin registers an app with the application permission `Sites.Selected` and grants it
    read on the one site (or OneDrive) - nothing else; reported 29 October 2026: `Files.Read.All` or `User.Read.All`
    next to it widened what such an app reached. `Sites.Read.All` works too and reads every site.
  - **Not tried against Microsoft yet** (29 October 2026): built and specced against `FakeMicrosoft`; a try with a
    Microsoft 365 test tenant is open - see "Trying SharePoint by hand" below.
- Timeouts: connect 10 s, SFTP login 15 s, SFTP reading 60 s per request (MINA's idle timeout, else 10 minutes: a
  server hanging with the connection open would hold one of the two task threads that long), FTPS, S3 and SharePoint
  reading 30 s, an S3 call at most 60 s. The
  frontend gives the check the long timeout.
- File names in **UTF-8** (RFC 2640, what every current server writes): SFTP by MINA's default; FTPS sets the control
  connection's encoding before it connects - commons-net applies it when TLS takes the connection over - and sends
  `OPTS UTF8 ON` after the login (some servers send UTF-8 names only when asked; the answer does not matter). Not by
  `FEAT`: commons-net's autodetection asks before `AUTH TLS`, in plain text. A server writing Latin-1 names shows
  its names with umlauts wrong - the rare case now, the common one before.
- **Reading** (`FileStoreClient.open` → `FileStoreSession`, `FileStoreService.open(tenantId, storeId)`): logs in only
  to the server with the confirmed key or certificate (FTPS: or one an authority vouches for) - else
  `fileStore.fingerprintUnconfirmed` / `fingerprintChanged`, nothing sent; one connection for as long as the caller
  reads. `list(folder)` gives the folder's files with size and time, and its subfolders (`RemoteFile.folder`, size 0;
  without `.` and `..`, which servers list as folders; no links), sorted by name - SFTP from the directory's
  attributes, FTPS through `MLSD` (exact sizes and times), else `LIST` (only where the server does not know `MLSD`) - a 450/550 to
  either in a folder `CWD` entered is a folder that may not be read (`fileStore.folderNotFound`), never an empty one,
  which both answer with 226; `LIST` is not asked then, since ProFTPD answers it with an empty list where `MLSD` said
  "Permission denied" (only the check's `NLST` takes 450/550 as empty). A server without `MLSD` that lists a folder
  it may not read as empty cannot be told from an empty folder. **A listing is complete or a failure** - a knowledge source deletes the documents of
  files it no longer lists, so a part of a listing taken for all of it would delete documents nobody deleted. A folder with more than 50 000 entries
  (`FileStoreSession.MAX_ENTRIES`) is refused (`fileStore.tooManyEntries`, 502) while it is listed, SFTP and S3 -
  a server a tenant admin runs could otherwise answer without end and fill the memory every tenant shares; FTPS,
  where Commons Net reads the whole listing itself, cuts a listing's data connection off after 32 MB
  (`FtpsStoreClient.LISTING_MAX_BYTES`, enough for `MAX_ENTRIES` lines of MLSD) and refuses more entries than
  `MAX_ENTRIES` after it - the check too. **Not bounded, accepted**: not every answer
  of a server is bounded by the app (the libraries offer no setting for it); a store's server is one the tenant admin
  chose. A task's `read_files` step takes the files only
  (it reads one folder); a knowledge source walks into the subfolders. File name patterns (`*`, `?`) of both are
  matched by `NamePattern`, without a regular expression. `read(path, offset, maxBytes)`
  reads from any offset - SFTP by offset, FTPS with `REST` before `RETR`. **An FTPS transfer broken off before the
  file's end** (a file larger than the step's limit, or grown since it was listed - an active log, always) **ends the
  whole session on some servers**: ProFTPD closes the control connection without a reply, and the next command failed
  with "Connection closed without indication" (seen with ProFTPD; reproduced in
  `FileStoreServerSpec` with a large file read partly). So the session drops its connection itself after such a
  read, without waiting for an answer that may never come, and the next command opens a new one - its certificate
  checked against the confirmed fingerprint again, as on every open. A failure is `fileStore.readFailed` (502). A task's folder is relative to the store's own
  (`FileStoreService.folderBelow`: no `..`, no backslash) - a task never reaches above the store's folder.
- **A store a task reads cannot be deleted** (`task_file_store`, written from the task's definition on every save):
  409 `fileStore.inUse` with the tasks' names - the tenant's own: another tenant's store id answers 404 and names
  nothing (`TaskSpec`). Nor one a knowledge source reads: 409 `fileStore.usedBySource` with the sources' names - the
  same when it is changed to other files (`Settings.sameFiles`: SFTP and FTPS another host, port or folder; S3 another
  endpoint, region, bucket or folder; SharePoint another site, library or folder), since the source would then read
  other files and delete its documents at the next listing (`KnowledgeSourceSpec`, `SharePointStoreSpec`,
  `FileStoreServerSpec`). A name, user name or password change is allowed, and so are a rotated S3 access key and
  another SharePoint app with its secret: they read the same files.

## Writing (roadmap point 50)

Decided 4 November 2026: a scheduled task's
`write_file` step appends rows to a CSV in a store. Reading stays the default; writing is the exception.

- **The switch per store** "Aufgaben dürfen hier schreiben" (`file_store.task_write`, management migration V17 of 2.0.0, `false`
  for every store; `FileStoreRequest.taskWrite` - left out on create: off, on update: unchanged), kept by tenant admins
  and the admin. `FileStoreService.writable(store)`: the switch on and the store readable (checked, its fingerprint
  confirmed) - a write logs in like a read. Turning it off makes the next check and run of every task writing there
  fail; the tasks stay.
- **`FileStoreSession.write(path, content)`**: one method - creates the file or replaces it whole; appending is read and
  write in the step (S3 and Graph cannot append). No folder made, nothing deleted or renamed besides its own temporary
  file, never half written for a reader:
  - **SFTP** (MINA): a temporary file `<name>.tmp-<8 hex>` beside it, then renamed over it - OpenSSH's
    `posix-rename@openssh.com` where the server has it, SFTP 5's overwrite, else (SFTP 3 without the extension) the
    old file removed first. MINA refuses overwrite on SFTP 3 (4 November 2026, OpenSSH in the specs speaks 3).
  - **FTPS** (Commons Net): `STOR` of the temporary file, `RNFR`/`RNTO` - a server refusing to rename onto an existing
    file (ProFTPD's default `AllowOverwrite off`) gets it deleted first. After a write the session's connection is
    dropped: the next transfer on it delivered nothing once (ProFTPD, 4 November 2026) - and a `RETR` whose transfer
    the server ends with an error is a failure now, never an empty file (`completePendingCommand` checked).
  - **S3**: `PutObject` - an object is replaced whole. A key that may read but not write answers 403 - said as
    `fileStore.writeFailed`, not as a refused key.
  - **SharePoint / OneDrive**: the simple upload `PUT .../root:/<path>:/content`, which replaces the item; a file open
    in Office answers 423 - `fileStore.fileLocked`, the run fails and the next tries again; 404 (no such folder) and
    403 (the app may not write) are `fileStore.writeFailed` with the reason. The CSV a task writes stays far below the
    simple upload's limit (a year of documents is a few MB); an upload session is not built.
- **Failures**: `fileStore.writeFailed` (with the server's reason; a missing folder is one), `fileStore.fileLocked`.
- **Frontend**: the store dialog's toggle "Aufgaben dürfen hier schreiben" with a sentence what it allows
  (`FileStoreFormValue.taskWrite`, always sent); the list tags such a store "Aufgaben schreiben".
- **Rights at the server**: the store's account needs write rights on the folder; the public README shows them (S3
  `s3:PutObject` on the prefix, SharePoint the role `write` instead of `read`).
- **Specs**: `FileStoreSpec` (the switch: off by default, kept, turned off; `writable` only once readable),
  `FileStoreServerSpec` (SFTP, FTPS, S3: created, replaced whole, a second file right after on the same session, no
  temporary file left; a missing folder refused - `FILE_STORE_TEST`), `SharePointStoreSpec` (the upload with the
  token, a locked file, a missing folder - against `FakeMicrosoft`). The test servers have a folder `export`.

## Mailbox (IMAP, roadmap point 50)

Decided 4 November 2026: documents mailed to an address of the company's own (`invoices@example.com`) are read like the
files of a store - a fifth type, **read only** (`FileStoreType.IMAP`, migration V17 of 2.0.0). `read_documents` and the
knowledge sources read it without a line changed in them; most of roadmap point 9.

- **Settings** (`MailboxSettings`): host, port (993 for SSL, 143 otherwise, when none is given), user name, `security`
  (`SSL` - IMAPS, the default; `STARTTLS` - required, not offered; `NONE` - only to a host in
  `app.file-store.internal-hosts`, `fileStore.plainImap`, as plain http for S3), `mailbox` (the mailbox folder, `INBOX`
  by default, e.g. `INBOX/Belege`), `days` (365 by default, 1-3650). The password is the store's secret. Another host,
  port, user or security needs it again; another mailbox folder reads other messages (a knowledge source's store is
  refused it). The host is checked against the internal networks when saved and before every session; the certificate
  against the JVM's trust (`checkserveridentity`), no fingerprint. `readable` when the last check logged in.
- **What the files are** (`MailboxStoreClient`, Angus Mail - in the jar already for SMTP): the folder opened
  **read only** - nothing deleted, moved or flagged, not even `\Seen`. `list("/")`: every message received in the last
  `days` (IMAP `SEARCH SINCE`, the internal date) is a folder `<date>_<UID>` (UTC date; the UID is stable within a
  mailbox folder, so the path is). `list("/<message>")`: its attachments - every part with a file name, except one
  embedded in an HTML text (a part of `multipart/related` after its first: a signature's logo) - and `mail.txt` (from,
  to, date, subject and the first text part; HTML without its tags, script and style - by steps linear in the text's
  length: anyone can send a mail, and a backtracking pattern could be held up by one made for it). Apple Mail's attachments marked inline in
  `multipart/mixed` are files. A message **forwarded as an attachment** (`message/rfc822`) is unpacked one level: its
  attachments are files, its headers and text go into `mail.txt` below "--- Forwarded message ---"; such a mail is
  fetched whole once and parsed locally (a nested message's parts fetched one by one came back empty from GreenMail),
  every other mail is listed from its structure without downloading it. Two attachments of one name: "name (2).pdf".
  An attachment's listed size is the server's (encoded) - only to see a change; it is read when asked for, the session
  keeping the one message and file last read.
- **Never written**: `write` answers `fileStore.notWritable`; the switch "Aufgaben dürfen hier schreiben" is refused for
  a mailbox (`fileStore.mailboxReadOnly`), so `write_file` is refused by the check.
- **The folder chooser** answers an empty list - the mailbox folder is set at the store, messages are no folders to
  choose. "Draft with AI" names a mailbox as one, without listing it.
- **As a knowledge source**: a source on a mailbox embeds every attachment and every message's `mail.txt`. A message
  older than `days` drops out of the listing, so its documents leave the knowledge - `days` is how long mails stay in
  the knowledge.
- **Frontend**: the type "Postfach (IMAP)" in the store dialog (`mailbox-store-fields`: host, port - 993 or 143 by the
  connection when empty -, user, connection, mailbox folder, days); no folder field and no write toggle for it, a
  sentence that it is only read instead. The list shows `user@host · folder`. `utils/file-store.util.ts`: the request
  never asks to write into a mailbox; another host, port, user or connection needs the password again.
- **Specs**: `MailboxStoreSpec` against GreenMail's IMAP (no container, runs always): saved and checked, password not
  sent back, writing refused, plain IMAP only to an internal host, a wrong password; the listing (the last days only,
  attachments and `mail.txt`, the embedded logo left out, a forwarded message unpacked, nothing marked seen); a task
  reading attachments into rows, once; a knowledge source syncing a mailbox.

## API and frontend

- `/tenant/admin/file-stores` (`TenantAdminFileStoreController`, TENANT_ADMIN) and `/admin/tenants/{id}/file-stores`
  (`FileStoreAdminController`, ADMIN): `GET`, `POST`, `PUT /{id}`, `DELETE /{id}`, `POST /{id}/check`,
  `PUT /{id}/fingerprint`, `GET /{id}/folders?path=...` (since 27 October 2026, to choose a knowledge source's folder:
  the subfolders of one folder below the store's, sorted, without hidden ones (a name starting with `.` - the rule
  `RemoteFile.isHidden` a source uses too), at most 500 with `truncated`; only of a ready store, 409
  `fileStore.notReady`; 400 `fileStore.folderInvalid` for a path with `..` or `.`; a server failing answers its code,
  502; the names are shown, not stored). Another tenant's store answers 404. Not in `/api/v1` - a store is a setting, not something
  the client software keeps.
- Frontend: the shared `components/shared/file-stores-panel` with a `TenantScope` (slice `fileStore`, one tenant at
  a time); the tenant admin's page "Dateiablagen" (`/app/file-stores`), the admin's tab "Dateiablagen" of a tenant.
  The dialog shows the fields of the chosen type - a component each (`server-store-fields`, `s3-store-fields`,
  `sharepoint-store-fields`) on the dialog's one form (`file-store-form.ts`); `requiredFields` says which are required
  (S3 needs the region only for Amazon S3), `fieldTexts` how a type names its secret and folder. It asks for the
  password - for S3 the secret access key, for SharePoint the client secret - on create and for another server, bucket
  or app (`utils/file-store.util.ts` `passwordNeeded`); the check's `UNCONFIRMED` and `CHANGED` open a confirmation with the
  fingerprint (`CHANGED` in warning colours). The list shows a bucket with its endpoint (`storeAddress`) and its access
  key ID, a library with its site and the app's client ID instead of a user, and no fingerprint.

## Specs

- **`controller/filestore/FileStoreSpec`** - runs always, no server: adding, changing and removing connect nowhere.
  Every validation rule, the defaults, unique names, another server needing the password again, the password
  encrypted and never sent back, an unreachable port (a closed one on localhost), a check's outcome not written to a
  store changed meanwhile, tenant isolation and the admin API - and S3's: every field rule, plain HTTP only to an
  internal host, which changes need the secret again, an unreachable endpoint, no fingerprint to confirm; and
  SharePoint's: every field rule, the address in one form, the secret again only for another directory or app.
- **`controller/filestore/SharePointStoreSpec`** - runs always, against `services/filestore/FakeMicrosoft` (an HTTPS
  server of the spec with a certificate of its own - MSAL takes no other authority - answering like Entra ID's login
  and Graph, replacing `GraphAddresses` and the TLS it trusts with `test.fake-graph=true`): the check without a fingerprint, every refusal (unknown directory and app, wrong and
  expired secret, unknown and not granted site, unknown library, missing folder), the default and a named library, a
  OneDrive, listing over pages without a notebook, reading from an offset when Microsoft answers the range and when it
  sends the whole file - never with the token -, a download address on a foreign host refused, throttling (one short
  wait, twice, a long wait), a token expiring mid-session, the folder chooser, a knowledge source and a task's
  `read_files` step.
- **`services/filestore/FtpsCertificateCheckSpec`** - runs always, no server: the FTPS certificate check handshake by
  handshake - an authority's certificate, a confirmed fingerprint, none confirmed, another one - and a data
  connection refused under a certificate the server did not prove itself with.
- **`controller/filestore/FileStoreServerSpec`** - against real servers built from `src/test/resources/filestore/`
  and started once per test JVM (`services/filestore/FileStoreServers`): OpenSSH with a password login and ProFTPD
  with explicit TLS, a certificate of its own and the TLS session reuse it requires by default (its passive ports
  39400-39404 published 1:1); both on Debian, so they run on arm64 and amd64 alike. For S3 SeaweedFS (a pulled image
  for both architectures, checking the requests' signatures; MinIO publishes no images any more), reached over plain
  HTTP on localhost, its bucket filled through the SDK like the other servers' folders plus a folder of 1005 files
  (more than one listing page) - the same features for S3 (wrong secret, unknown key, missing bucket, empty folder and
  bucket, reading from an offset), a knowledge source syncing a bucket's folder and a task's `read_files` step reading
  one. The two-step check for both types,
  another key or certificate refused, wrong password, missing and empty folder, renaming keeping the confirmed
  server, the admin's check. **Runs only with `FILE_STORE_TEST` set** (decided 13 October 2026) - not on GitHub, not
  in a plain local run, since the two images are built from their Dockerfiles. Run it by hand with every change of
  `services.filestore` and before a release: `FILE_STORE_TEST=1 ./gradlew test --tests "de.alpdesk.controller.filestore.*"`.
- Shared steps in `services/filestore/FileStoreApi`. The test properties list `localhost` and `127.0.0.1` as internal
  hosts.

## Trying SharePoint by hand

Not done yet (29 October 2026). With a Microsoft 365 test tenant:

1. In Entra ID register an app, create a client secret, add the application permission `Sites.Selected` (Microsoft
   Graph) and grant admin consent.
2. Grant the app read on one SharePoint site and one OneDrive - Graph `POST /sites/{site-id}/permissions` with
   `{"roles": ["read"], "grantedToIdentities": [{"application": {"id": "<client id>"}}]}` (called by an admin or an
   app with `Sites.FullControl.All`), or PnP PowerShell `Grant-PnPAzureADAppSitePermission`.
3. In Zirmo add a store of the type "SharePoint / OneDrive" for each: check, choose a folder, sync a knowledge
   source, run a task with `read_files`.
4. Check the refusals: a site not granted (`accessDenied`), a wrong secret (`clientRefused`), another library name.
5. Look at what Graph lists in a library's root (the hidden "Forms" folder must not appear) and how long a token
   lives during a large sync.

