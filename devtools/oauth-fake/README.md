# OAuth fake

A hosted software that signs its clients in with OAuth - for a test stack that should show a connection's sign-in
(roadmap point 61, docs/architecture/mcp.md "Signing in to the software's server") without a real server
(`devtools/general-chat-test`). One process, two roles:

- **An OAuth 2.1 authorization server**: metadata at `/.well-known/oauth-authorization-server` (RFC 8414, `iss` in
  the callback announced - RFC 9207), dynamic registration at `/register` (RFC 7591; a public client for `none`, else a
  secret), a **consent page** at `/authorize` a person clicks ("Zulassen" / "Ablehnen"), the token endpoint with the
  authorization code (PKCE S256, the redirect address and the resource checked, a code once) and rotating refresh
  tokens. The scopes `tickets:read` and `tickets:write`.
- **An MCP server "Ticketsystem"** at `/mcp` (streamable HTTP, JSON answers, no session): `list_tickets` (reading) and
  `create_ticket` (changing), invented tickets. Without one of its own access tokens it answers 401 with
  `WWW-Authenticate: Bearer resource_metadata=".../.well-known/oauth-protected-resource/mcp", scope="..."` and serves
  that document (RFC 9728).

Two addresses: `INTERNAL_URL` - how the app reaches it (metadata, registration, token, MCP; the host must be in
`APP_AI_PLAIN_HTTP_HOSTS` for plain HTTP) - and `PUBLIC_URL` - how the browser reaches the consent page (the
metadata's `authorization_endpoint`). For a check: `POST /test/revoke` (every access token invalid - the app must
renew), `POST /test/refuse-refresh` `{"on": true}` (the next renewals refused: "Anmeldung nötig"), `GET /test/state`
(registrations, sign-ins, refreshes, refused ones, MCP calls). Every request is logged as one JSON line - never a
token, code or secret: `podman logs <container>`.

Standard library only, no image of its own: `python -I server.py` in `python:3.13-slim` (port `OAUTH_FAKE_PORT`,
default 9100, `TOKEN_LIFETIME` default 3600 s). A local helper, not used by the specs (they have
`FakeAuthorizationServer`).
