# Branding

Decided 6 October 2026 (roadmap point 5). The frontend shows a name, a primary colour and a logo instead of the
fixed "Zirmo" in green.

## Whose look where

| Where | Look |
|-------|------|
| Login page `/login` (not logged in) | **none** (decided 6 October 2026): one login page serves every tenant, so it shows neither the operator's nor a company's brand - greys of its own instead of a brand colour (set in the login component, light and dark), a narrow dark panel on the left with faint AI sparks (`login-backdrop.ts`, fixed coordinates) and a spark mark - no product name, no text - and the form on the page's surface; "Anmelden", a neutral favicon (`favicon.ico`, a white spark on grey) and the title "Anmeldung" (`NEUTRAL_BRANDING`) |
| A tenant's own login page `/login/<key>` | the **tenant's** (decided 20 October 2026), with the same fallbacks as in the tenant area - see "The tenant's login page" |
| The admin's dashboard | the **installation's**: `app.branding.name`, `app.branding.primary-color`, `app.branding.logo` (env `APP_BRANDING_*`, `.env` in production) |
| The tenant area (`/app`) after login | the **tenant's own**, set by the admin (tenant list, dialog "Branding") - each value left empty falls back to the installation's |

- Only the admin sets a tenant's look (`/admin/tenants/{id}/branding`, `.../logo`); tenant admins do not.
- Defaults: "Zirmo", `#49732a` (the colour the build's theme was generated from) and the Zirmo logo in the
  jar (`src/main/resources/branding/default-logo.png`, 512 px: a Zirbe cone with AI sparks on green; its source
  `website/images/zirmo-logo.svg`, rendered to PNG in a browser). The frontend's static
  `favicon.ico` and `apple-touch-icon.png` are neutral (they show before the login); after it the favicon is the logo. `app.branding.logo` takes a file path or
  `classpath:...`; set empty, there is no logo. An invalid default (blank name, colour not `#rrggbb`, unreadable logo)
  stops the start (`BrandingService`), like the secrets check.
- A tenant's values are in the management database (`tenant_branding`: name, colour, logo type - `NULL` = default),
  its logo in the file storage (`tenants/<id>/branding/logo`) - deleted with the tenant.
- **Logos**: PNG, JPEG or WebP, recognised by content, at most 512 KB - **never SVG**, it can carry scripts.
- **The name reaches beyond the page** (decided 10 October 2026, roadmap point 22): the tenant's MCP server goes by it in
  Claude Code and Copilot (`muster-ai` for "Muster AI": server name, instructions, tool descriptions), and our MCP client
  introduces itself with it to the tenant's software - [mcp.md](mcp.md) "The brand". Whatever names the product
  towards a tenant takes the tenant's brand, never a fixed "Alpdesk".

## The tenant's login page

Decided 20 October 2026: one neutral `/login` for everyone, and next to it a login page per tenant that shows its look.

- **The address** is `/login/<key>`: `tenant.login_key`, 128 random bits as 32 hex characters, made when the tenant is
  created (`TenantService.newLoginKey`; existing tenants got theirs from migration V9). Random on purpose: a readable
  name (`/login/musterfirma`) would let anyone find out by trying which companies use the installation.
- **The look - and the tenant of a name in several**: the key gives no rights, the login itself is the same `POST
  /login`. Since 2.0.0 (roadmap point 51) the page sends the key as the header `X-Login-Key`: a username that exists
  in several tenants signs in only so, into this tenant ([tenancy.md](tenancy.md) "Who does what"); on the neutral
  `/login` a name in one tenant signs in as before, on another tenant's page it is refused like a wrong password (a
  change against 1.9, where the key only set the look); the admin signs in on any page. A key
  nobody has (any more) or a deactivated tenant answers 404, and the page stays neutral.
- **Handing it on**: the admin sees the address on the tenant's tab "Branding" (`tenant-branding-summary`) and can make
  a new one ("Neuer Link", `POST .../branding/login-key`) - the old one shows the neutral page from then on; tenant
  admins see it (read only) on their page "Benutzer". The frontend builds it below the base href
  (`utils/login-page.util.ts`), so it holds behind a reverse proxy under a sub-path.
- **Back to it**: the login page remembers the key in the browser (`localStorage`, only after the look was loaded; a
  404 forgets it), and a logout or a lost session (`AuthEffects.logoutSuccess$`, the route guards) leads to that page
  instead of `/login`. `/login` opened by hand shows the neutral page but keeps the remembered key.
- **On the page**: the dark panel takes the tenant's colour, the logo on a white tile (logos are made for a light
  ground) and the name below it; below 860px, where the panel is gone, logo and name stand above the form. The
  colours of fields and buttons are the tenant's (BrandingEffects sets them as everywhere), the greys only apply on
  `/login`. Title and favicon are the tenant's name and logo.

## Endpoints

| Path | Who | Returns |
|------|-----|---------|
| `GET /branding`, `/branding/logo` | anonymous - loaded at start, so the dashboard shows without a flash after a reload | the installation's look: name, colour, `hasLogo`; the logo |
| `GET /branding/login/<key>`, `.../logo` | anonymous - the tenant's own login page | the tenant's look (with the fallbacks); 404 for an unknown key or a deactivated tenant |
| `GET /tenant/branding`, `/tenant/branding/logo` | tenant user | the tenant's look (with the fallbacks) |
| `GET/PUT /admin/tenants/{id}/branding`, `GET/PUT/DELETE .../logo` | admin | the tenant's own values (`null` = default), the defaults and `loginKey`; the logo |
| `POST /admin/tenants/{id}/branding/login-key` | admin | a new key for the tenant's login page (the response as above) |
| `GET /tenant/admin/branding/login-key` | tenant admin | `{loginKey}` of the own tenant |

Logos are sent with `Cache-Control: no-cache` - a new logo shows at the next load.

## How it reaches Angular

- **At start, before anything is shown** (`provideAppInitializer` in `app.config.ts`): the installation's look is
  loaded (at most 3 s, else the build's own look) - no flash of the green "Zirmo".
- **The tenant area** (`tenant-app`) loads the tenant's look; logout drops it, the installation's stays (`branding`
  slice, kept by the logout meta-reducer). A logo behind a login comes as a blob (an `<img src>` would not carry the
  token) and is shown through an object URL, revoked when replaced.
- **`BrandingEffects.apply$`** puts whatever look is shown into the page: the title, the favicon (the logo, else
  `favicon.ico`) and the colours.
- **Colours**: Angular Material 3 reads every colour from `--mat-sys-*` CSS variables. From the one primary colour
  `utils/branding.util.ts` computes the colour roles with Google's `@material/material-color-utilities` (0.3.0 - 0.4.0's
  package breaks in Node) using the **content** scheme, which keeps a brand colour as it is (the primary container is
  the colour itself; "tonal spot" would tone it down), for light and dark, and sets each on `<html>` as
  `light-dark(light, dark)`; next to them `--app-brand` (the colour itself) and `--app-on-brand`. Error colours stay
  the theme's (red stays red); for the build's own colour nothing is set, its theme stays exactly as built.
  **Surfaces are neutral** whatever the colour: `styles.scss` sets `--mat-sys-surface`, `-background`, the surface
  containers and the text on them (white and greys in light, near-black in dark), and they are not computed from the
  brand colour - Material tints them with it (the build's green gave `#f9faf0`, others looked violet). The brand colour
  is an accent only: the active menu entry, filled buttons, the chat's own question and the assistant's mark.
- **Light and dark** (decided 9 October 2026, with the redesign): `styles.scss` builds Material's theme with
  `theme-type: color-scheme`, so every colour - Material's and the app's `--app-*` tokens - is a `light-dark()` pair, and
  the CSS property `color-scheme` on `<html>` picks one. `utils/color-scheme.util.ts` sets it before the app shows
  (`provideAppInitializer`): "Automatisch" follows the operating system, "Hell" and "Dunkel" force one - chosen in the
  shell's user menu and kept in the browser (`localStorage`, for every login in it).
- `selectBranding` / `selectLogoUrl` give the neutral look while nobody is logged in - or the tenant's on its own login
  page -, then the tenant's or the installation's. Name and logo: both toolbars and the texts naming the product read
  them; `/login` shows none.
- Fixed colours in components are status colours (active, failed) on purpose; brand-coloured ones use the variables.

## On a phone's home screen

Added to a phone's home screen, the frontend opens as an app of its own, without the browser's bars, with the
**installation's** name, colour and logo: `GET /branding/manifest.webmanifest` (`BrandingController`, anonymous like
`/branding`, `application/manifest+json`, `display: standalone`, every address relative to it - `../` the frontend's
root, `logo` the installation's logo; without a logo the frontend's spark) and `apple-touch-icon` = `branding/logo`
for iOS, both linked from `index.html` with relative addresses (a context path's base href carries them), the manifest
with credentials (behind a WAM proxy it is fetched with the proxy's session). A tenant's own look is not in it: inside
`/app` the request carries no token, so the server cannot tell the tenant (below, "Not built").
`viewport-fit=cover` lets the page reach the screen's edges; the shell (and its sliding sidebar) keeps the notch and the
home indicator free with `env(safe-area-inset-*)`, which is 0 in a browser tab. Such an app has no back button or
reload of the browser: the shell's menu is the way around. A page of another host (the WAM proxy's sign-in, an OAuth
server's consent page) opens inside it with a small bar of its own.

## Not built

- A second accent colour - until someone asks.
- On a reload inside `/app` the installation's look shows for a moment before the tenant's arrives.
- The tenant's look on the home screen (name, colour, logo as icon - the installation's is there): inside `/app` a
  tenant's logo reaches the page as a `blob:` URL (it is behind the login), which a device cannot fetch for the home
  screen, and the manifest's request carries no token. A tenant with its own login page could get a manifest of its
  own under its anonymous key (`/branding/login/<key>/manifest.webmanifest`, start at `/login/<key>`) - it would need
  the frontend to know its tenant's key inside `/app`, which today only the address of the login page tells; a WAM
  installation without keys keeps the installation's (not built).
- A readable address per tenant (`/login/musterfirma`) - it would tell anyone which companies use the installation.
