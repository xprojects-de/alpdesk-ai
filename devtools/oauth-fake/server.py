"""A hosted software that signs its clients in with OAuth, for a test stack (README.md): an OAuth 2.1 authorization
server - metadata (RFC 8414), dynamic registration (RFC 7591), authorization code with PKCE (S256), the resource
indicator (RFC 8707), iss in the callback (RFC 9207), rotating refresh tokens - with a consent page a person clicks,
and behind it a small MCP server "Ticketsystem" (streamable HTTP, JSON answers, no session) that takes only its own
access tokens and names its resource metadata (RFC 9728) in a 401. Invented data, standard library only.

Two addresses: INTERNAL_URL is how the app reaches it (metadata, registration, token, MCP); PUBLIC_URL is how the
browser reaches the consent page (through the stack's Caddy). /test/... lets a check revoke the tokens, refuse the
next refreshes and read what happened. Every request is logged as one JSON line - never a token, code or secret.
"""
import base64
import hashlib
import html
import json
import os
import secrets
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("OAUTH_FAKE_PORT", "9100"))
INTERNAL_URL = os.environ.get("INTERNAL_URL", f"http://127.0.0.1:{PORT}").rstrip("/")
PUBLIC_URL = os.environ.get("PUBLIC_URL", INTERNAL_URL).rstrip("/")
TOKEN_LIFETIME = int(os.environ.get("TOKEN_LIFETIME", "3600"))
SCOPES = ["tickets:read", "tickets:write"]

lock = threading.Lock()
clients = {}          # client_id -> {secret, method, redirect_uris, name}
codes = {}            # code -> {client_id, redirect_uri, challenge, resource, scope}
access_tokens = {}    # token -> scope
refresh_tokens = {}   # token -> {client_id, scope}
counters = {"registrations": 0, "signIns": 0, "refreshes": 0, "refusedRefreshes": 0, "mcpCalls": 0, "mcpRefused": 0}
settings = {"refuseRefresh": False}

TICKETS = [
    {"number": "T-1001", "title": "Förderband 3 stoppt sporadisch", "status": "offen", "priority": "hoch"},
    {"number": "T-1002", "title": "Presse P200: Wartung fällig", "status": "geplant", "priority": "mittel"},
    {"number": "T-1003", "title": "Drucker Halle 2 druckt leer", "status": "erledigt", "priority": "niedrig"},
]
TOOLS = [
    {"name": "list_tickets", "description": "Lists the open and planned tickets of the maintenance team",
     "inputSchema": {"type": "object", "properties": {"status": {"type": "string",
                                                                  "description": "offen, geplant or erledigt"}}},
     "annotations": {"readOnlyHint": True}},
    {"name": "create_ticket", "description": "Creates a ticket for a fault",
     "inputSchema": {"type": "object", "properties": {"title": {"type": "string"}}, "required": ["title"]}},
]


def metadata():
    return {"issuer": INTERNAL_URL,
            "authorization_endpoint": PUBLIC_URL + "/authorize",
            "token_endpoint": INTERNAL_URL + "/token",
            "registration_endpoint": INTERNAL_URL + "/register",
            "response_types_supported": ["code"],
            "grant_types_supported": ["authorization_code", "refresh_token"],
            "code_challenge_methods_supported": ["S256"],
            "token_endpoint_auth_methods_supported": ["none", "client_secret_basic"],
            "scopes_supported": SCOPES,
            "authorization_response_iss_parameter_supported": True}


def s256(verifier):
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")


def log(**fields):
    print(json.dumps(fields, ensure_ascii=False), flush=True)


class Handler(BaseHTTPRequestHandler):

    # --- routing -----------------------------------------------------------------------------------------------------

    def do_GET(self):
        path, query = self.split()
        if path == "/.well-known/oauth-authorization-server":
            return self.json(200, metadata())
        if path == "/.well-known/oauth-protected-resource/mcp":
            return self.json(200, {"resource": INTERNAL_URL + "/mcp", "authorization_servers": [INTERNAL_URL],
                                   "scopes_supported": SCOPES})
        if path == "/authorize":
            return self.consent_page(query)
        if path == "/test/state":
            with lock:
                return self.json(200, {**counters, **settings, "validTokens": len(access_tokens)})
        if path == "/mcp":
            # No server-sent stream of its own: the client goes on with POST only.
            return self.json(405, {"error": "no stream"})
        self.json(404, {"error": "not_found"})

    def do_POST(self):
        path, query = self.split()
        body = self.rfile.read(int(self.headers.get("Content-Length", "0") or 0)).decode()
        if path == "/register":
            return self.register(json.loads(body or "{}"))
        if path == "/authorize":
            return self.decide(dict(urllib.parse.parse_qsl(body)))
        if path == "/token":
            return self.token(dict(urllib.parse.parse_qsl(body)))
        if path == "/mcp":
            return self.mcp(json.loads(body or "{}"))
        if path == "/test/revoke":
            with lock:
                access_tokens.clear()
            log(test="revoked every access token")
            return self.json(200, {"revoked": True})
        if path == "/test/refuse-refresh":
            settings["refuseRefresh"] = bool(json.loads(body or "{}").get("on", True))
            log(test="refuse refresh", on=settings["refuseRefresh"])
            return self.json(200, settings)
        self.json(404, {"error": "not_found"})

    def do_DELETE(self):
        self.json(405, {"error": "no session"})

    # --- the authorization server ------------------------------------------------------------------------------------

    def register(self, request):
        method = request.get("token_endpoint_auth_method") or "client_secret_basic"
        client_id = "client-" + secrets.token_hex(6)
        secret = None if method == "none" else secrets.token_urlsafe(24)
        with lock:
            clients[client_id] = {"secret": secret, "method": method, "redirect_uris": request.get("redirect_uris", []),
                                  "name": request.get("client_name", "?")}
            counters["registrations"] += 1
        log(register=client_id, name=request.get("client_name"), method=method,
            applicationType=request.get("application_type"), redirectUris=request.get("redirect_uris"))
        answer = {"client_id": client_id, "token_endpoint_auth_method": method,
                  "redirect_uris": request.get("redirect_uris", []), "grant_types": request.get("grant_types", []),
                  "client_name": request.get("client_name")}
        if secret:
            answer["client_secret"] = secret
        self.json(201, answer)

    def consent_page(self, query):
        client = clients.get(query.get("client_id", ""))
        problem = self.authorize_problem(query, client)
        if problem:
            log(authorize="refused", reason=problem)
            return self.page(400, f"<p>Die Anmeldung kann nicht beginnen: {html.escape(problem)}</p>")
        hidden = "".join(f'<input type="hidden" name="{html.escape(key)}" value="{html.escape(value)}">'
                         for key, value in query.items())
        scopes = "".join(f"<li>{html.escape(scope)}</li>" for scope in (query.get("scope") or "").split() if scope)
        self.page(200, f"""
            <h1>Ticketsystem Musterfirma</h1>
            <p><b>{html.escape(client['name'])}</b> möchte auf das Ticketsystem zugreifen.</p>
            <p>Angefragte Rechte:</p><ul>{scopes or '<li>(keine genannt)</li>'}</ul>
            <form method="post" action="{PUBLIC_URL}/authorize">{hidden}
              <button name="decision" value="allow">Zulassen</button>
              <button name="decision" value="deny">Ablehnen</button>
            </form>
            <p class="small">Ein Test-Server mit erfundenen Daten - keine echte Anmeldung.</p>""")

    def decide(self, form):
        client = clients.get(form.get("client_id", ""))
        problem = self.authorize_problem(form, client)
        if problem:
            return self.page(400, f"<p>{html.escape(problem)}</p>")
        back = {"state": form.get("state"), "iss": INTERNAL_URL}
        if form.get("decision") != "allow":
            back["error"] = "access_denied"
            log(authorize="denied", client=form["client_id"])
        else:
            code = secrets.token_urlsafe(24)
            with lock:
                codes[code] = {"client_id": form["client_id"], "redirect_uri": form["redirect_uri"],
                               "challenge": form["code_challenge"], "resource": form.get("resource"),
                               "scope": form.get("scope") or " ".join(SCOPES)}
            back["code"] = code
            log(authorize="allowed", client=form["client_id"], scope=form.get("scope"), resource=form.get("resource"))
        location = form["redirect_uri"] + ("&" if "?" in form["redirect_uri"] else "?") + urllib.parse.urlencode(
            {key: value for key, value in back.items() if value is not None})
        self.send_response(302)
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        self.end_headers()

    @staticmethod
    def authorize_problem(params, client):
        if client is None:
            return "unbekannter Client"
        if params.get("redirect_uri") not in client["redirect_uris"]:
            return "die Weiterleitungsadresse ist nicht registriert"
        if params.get("response_type") != "code" or params.get("code_challenge_method") != "S256" \
                or not params.get("code_challenge"):
            return "ohne Authorization Code mit PKCE (S256)"
        if params.get("resource") != INTERNAL_URL + "/mcp":
            return "ohne die Ressource des Ticketsystems"
        return None

    def token(self, form):
        client_id = self.authenticated(form)
        if client_id is None:
            log(token="refused", reason="client")
            return self.json(401, {"error": "invalid_client"})
        if form.get("resource") != INTERNAL_URL + "/mcp":
            return self.json(400, {"error": "invalid_target"})
        with lock:
            if form.get("grant_type") == "authorization_code":
                issued = codes.pop(form.get("code", ""), None)
                if issued is None or issued["client_id"] != client_id or issued["redirect_uri"] != form.get(
                        "redirect_uri") or s256(form.get("code_verifier", "")) != issued["challenge"]:
                    log(token="refused", reason="code")
                    return self.json(400, {"error": "invalid_grant"})
                counters["signIns"] += 1
                log(token="signed in", client=client_id)
                return self.json(200, self.tokens(client_id, issued["scope"]))
            if form.get("grant_type") == "refresh_token":
                counters["refreshes"] += 1
                held = refresh_tokens.get(form.get("refresh_token", ""))
                if settings["refuseRefresh"] or held is None or held["client_id"] != client_id:
                    counters["refusedRefreshes"] += 1
                    log(token="refresh refused")
                    return self.json(400, {"error": "invalid_grant"})
                del refresh_tokens[form["refresh_token"]]
                log(token="refreshed", client=client_id)
                return self.json(200, self.tokens(client_id, held["scope"]))
        self.json(400, {"error": "unsupported_grant_type"})

    def authenticated(self, form):
        authorization = self.headers.get("Authorization", "")
        if authorization.startswith("Basic "):
            client_id, _, secret = base64.b64decode(authorization[6:]).decode().partition(":")
            client_id, secret = urllib.parse.unquote(client_id), urllib.parse.unquote(secret)
            client = clients.get(client_id)
            return client_id if client and client["secret"] == secret else None
        client = clients.get(form.get("client_id", ""))
        return form.get("client_id") if client and client["method"] == "none" else None

    @staticmethod
    def tokens(client_id, scope):
        access, refresh = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
        access_tokens[access] = scope
        refresh_tokens[refresh] = {"client_id": client_id, "scope": scope}
        return {"access_token": access, "token_type": "Bearer", "expires_in": TOKEN_LIFETIME,
                "refresh_token": refresh, "scope": scope}

    # --- the MCP server ----------------------------------------------------------------------------------------------

    def mcp(self, message):
        token = self.headers.get("Authorization", "").removeprefix("Bearer ")
        with lock:
            valid = token in access_tokens
            counters["mcpCalls" if valid else "mcpRefused"] += 1
        if not valid:
            log(mcp="401", method=message.get("method"))
            self.send_response(401)
            self.send_header("WWW-Authenticate", f'Bearer resource_metadata="{INTERNAL_URL}/.well-known/'
                                                 f'oauth-protected-resource/mcp", scope="{" ".join(SCOPES)}"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        log(mcp=message.get("method"), tool=(message.get("params") or {}).get("name"))
        if message.get("id") is None:
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        method = message.get("method")
        if method == "initialize":
            result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}},
                      "serverInfo": {"name": "ticketsystem-fake", "version": "1"},
                      "instructions": "The maintenance team's tickets (invented data)."}
        elif method == "tools/list":
            result = {"tools": TOOLS}
        elif method == "tools/call":
            result = self.call((message.get("params") or {}))
        else:
            return self.json(200, {"jsonrpc": "2.0", "id": message["id"],
                                   "error": {"code": -32601, "message": "Unknown method"}})
        self.json(200, {"jsonrpc": "2.0", "id": message["id"], "result": result})

    @staticmethod
    def call(params):
        arguments = params.get("arguments") or {}
        if params.get("name") == "list_tickets":
            status = arguments.get("status")
            found = [ticket for ticket in TICKETS if not status or ticket["status"] == status]
            text = "\n".join(f"{t['number']}: {t['title']} ({t['status']}, Priorität {t['priority']})" for t in found)
            return {"content": [{"type": "text", "text": text or "Keine Tickets."}], "isError": False}
        if params.get("name") == "create_ticket":
            return {"content": [{"type": "text", "text": f"Ticket T-1004 angelegt: {arguments.get('title')}"}],
                    "isError": False}
        return {"content": [{"type": "text", "text": "Unknown tool"}], "isError": True}

    # --- helpers -----------------------------------------------------------------------------------------------------

    def split(self):
        parsed = urllib.parse.urlsplit(self.path)
        return parsed.path, dict(urllib.parse.parse_qsl(parsed.query))

    def json(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def page(self, status, content):
        data = f"""<!doctype html><html lang="de"><head><meta charset="utf-8"><title>Ticketsystem - Anmeldung</title>
            <style>body{{font-family:sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem}}
            button{{font-size:1rem;padding:.5rem 1rem;margin-right:.5rem}} .small{{color:#666;font-size:.8rem}}</style>
            </head><body>{content}</body></html>""".encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"OAuth fake on :{PORT} - internal {INTERNAL_URL}, public {PUBLIC_URL}", flush=True)
    ThreadingHTTPServer(("", PORT), Handler).serve_forever()
