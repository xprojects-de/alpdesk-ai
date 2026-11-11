# Handbook (websites beside the chat)

A tenant's pages to read beside the chat - how-tos, rules, an overview - as a **finished static website**: one for the
whole tenant (shown as "Firmenhandbuch") and one per knowledge area, uploaded as a ZIP, shown in an iframe on the tenant users' page
"Handbuch". **For people only**: the assistant does not know them; no search, tool, `/api/v1` or MCP route reaches
them. Roadmap point 34 (decided 15 and 20 October 2026; the first plan, Markdown pages shown as a tree in the app's look,
is dropped for now).

## Data

- **Tenant database**, table `doc_site` (`db/tenant/V4__doc_site.sql`): `area_id` (NULL = the whole tenant; no foreign
  key across databases, like `document.area_id`), `storage_prefix`, `file_count`, `size_bytes`, `uploaded_at`; one row
  per scope (`UNIQUE (COALESCE(area_id, 0))`).
- **Files** in the `FileStorage` below `tenants/<id>/doc-sites/<uuid>/`. A ZIP whose files all sit in one folder (a
  zipped folder) is served from that folder: `storage_prefix` then points into it.
- **Replacing**: the new ZIP is written to a new `<uuid>` directory, the row is pointed at it in one JDBC transaction
  (the old row locked while its prefix is read), then the old directory is deleted. A reader never sees half of each; a
  refused or failed upload deletes its own directory. Two first uploads of the same scope at the same moment leave the
  files of one behind, unreached - accepted.
- **Deleting**: the row, then its directory (a failure is logged, the files stay unreached). A knowledge area with a
  website cannot be deleted (409 `knowledgeArea.hasDocSite`), like one with documents. Deleting the tenant drops its
  database and its storage prefix with every website.

## Upload

`DocSiteService.replace`, reading the ZIP with `DocumentArchive` and limits of its own: the ZIP at most
**`app.doc-site.max-zip-size`** (`APP_DOC_SITE_MAX_ZIP_SIZE`, default 100 MB, at most the server's request limit of
512 MB - a larger value is capped at it with a warning in the log; `docSite.tooLarge`, by the size the upload declares, before it is unpacked), at most **2000 entries**, a file
up to 50 MB, all together up to five times the ZIP's limit unpacked (a zip-bomb guard). The admins' list of websites
carries the limit (`maxZipBytes`), and the upload dialog refuses a larger ZIP before sending it - the backend would
receive it whole before its check. Allowed by extension, served with that type: HTML, CSS, JavaScript (`js`, `mjs`),
JSON, text, SVG, PNG, JPEG, GIF, WebP, AVIF, ICO, WOFF/WOFF2, TTF, OTF. Other files, hidden ones and paths leaving the
archive are skipped with their reason (shown to the uploader). `index.html` at the top - or in the ZIP's one folder -
is required (`docSite.noIndex`).

## Who reads what

The rule of the company knowledge (`KnowledgeAreaService.scopeOfUser`): the website for everyone and those of the
user's knowledge areas (through its groups); tenant admins every one. Another area's website answers 404 like an
unknown one.

## API

| Route | Role | What |
|-------|------|------|
| `GET /tenant/admin/doc-sites` | TENANT_ADMIN | `{sites, maxZipBytes}`: the tenant's websites (scope, files, size, when) and the ZIP's limit |
| `POST /tenant/admin/doc-sites` | TENANT_ADMIN | multipart `file` (the ZIP) and `areaId` (left out: for everyone) - replaces that scope's website; answers it and the skipped files |
| `DELETE /tenant/admin/doc-sites/{id}` | TENANT_ADMIN | removes it |
| `POST /tenant/admin/doc-sites/{id}/address` | TENANT_ADMIN | the preview's address |
| `/admin/tenants/{id}/doc-sites/**` | ADMIN | the same for a tenant |
| `GET /tenant/doc-sites` | TENANT_USER | the websites the user reads |
| `POST /tenant/doc-sites/{id}/address` | TENANT_USER | `{path: "doc-sites/<token>/"}`, below the API's base |
| `GET /doc-sites/<token>/<path>` | anonymous - the token is the check | the website's files |

## Security

The user's JWT lives in the browser storage of the app's origin, so the uploaded HTML must never run as the app's page.

1. **Sandboxed files**: every file of a website is answered with `Content-Security-Policy: sandbox allow-scripts
   allow-popups allow-popups-to-escape-sandbox; frame-ancestors 'self' <the frontend's CORS origins>`. Without
   `allow-same-origin` the browser treats it as a foreign origin: its scripts run but reach neither the token nor the
   API - also when the address is opened in a tab of its own. A tab the website opens leaves the sandbox (an external
   link works as usual). The iframe carries the same `sandbox` attribute and `referrerpolicy="no-referrer"`.
2. **The address carries the right**: an iframe sends no `Authorization` header. `DocSiteTokens` signs
   `<tenantId>.<siteId>.<expiresAt>` with HMAC-SHA256 under a key derived from the JWT secret - stateless, valid one
   hour, for one website. A deactivated or deleted tenant, a deleted website, a forged or expired token: 404. A user
   deactivated or taken out of the area keeps an address already handed out until it expires (at most an hour).
3. **Framing**: `ResponseHeaders` leaves an answer's own Content-Security-Policy alone - only these files set one;
   every other answer keeps `frame-ancestors 'none'` and `X-Frame-Options: DENY`. The files also send
   `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy: no-referrer` (the token never reaches a linked site) and
   `Cache-Control: private, max-age=300`.
4. **CORS for the website itself**: a sandboxed page's origin is `null`, and it sends it with requests - Firefox with
   every one, every browser with a font's. `DocSiteFilesController` carries `@CrossOrigin(allowedOriginsRegex =
   "^null$")` (GET, no credentials): otherwise Micronaut's CORS filter refuses them on localhost (its drive-by-localhost
   guard, an empty 403 that Firefox drops as a stylesheet of no type) and gives a font no
   `Access-Control-Allow-Origin` anywhere. No other origin is allowed; the files hold nothing but the website.
5. **Paths**: a requested path with `..`, a hidden segment, a backslash or a leading slash is not served; a folder named
   without its `/` is redirected to it (`./<name>/`, percent-encoded - a space or an umlaut is no character of an
   address), so its relative links resolve; `""` and `folder/` mean its `index.html`. The website's start without its
   `/` (`/doc-sites/<token>`) is redirected only for a token the installation signed - the path variable arrives
   decoded, and `%2F%2Fevil.example` would otherwise send the browser to another site.

The frontend sets the iframe's `src` by hand after checking the address lies below the API's base and `/doc-sites/`
(`utils/doc-site.util.ts` `isDocSiteUrl`) - no binding that would bypass Angular's sanitizer.

## Frontend

- Slice `docSite` (`store/doc-site/`): the websites of one scope (admin and tenant admin, starting with `scope: null`),
  the user's handbook, and the handbook's website open now and the admins' preview, each with its own address - a
  tenant admin previews on the handbook's page, and closing the preview must not empty the handbook.
- `components/shared/doc-sites-panel/`: the shared panel (`TenantScope`) - list, upload dialog (`DialogSave`: stays open
  on a refused ZIP; refuses one larger than `maxZipBytes` before it is sent, `utils/doc-site.util.ts` `refusedZip`), preview dialog, the iframe component.
- `components/handbook-page/`: `/app/handbook/:site`, the website shown in the address, a choice above when there are
  several; a tenant admin opens the panel there ("Websites verwalten"). The menu entry "Handbuch" shows when the user
  has a website, for a tenant admin always. The admin's tenant page has a tab "Handbuch". Five minutes before an
  address's hour is over the page takes a new one (`ADDRESS_RENEWAL_MS`) and loads the website again at its first page:
  a link clicked after the hour would open nothing. Measured by the clock (`addressDue`, looked at every minute and when
  the tab is shown again), not by one timer over the hour: a browser's timer stands still while the computer sleeps.
  When the list or the address cannot be fetched (the server restarting, the website no longer the user's), the page
  says so with "Erneut versuchen" instead of loading for ever.
- Given up on purpose: the website keeps its own look (white behind it, `--app-doc-site-bg`; no dark mode, no
  branding); a link without `target="_blank"` opens inside the iframe, and many sites refuse that.
