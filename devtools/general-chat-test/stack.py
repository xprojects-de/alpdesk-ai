"""The test stack's address book and HTTP helpers (README.md): everything goes through Caddy, as a person's browser
would - in the WAM mode a session at Authelia's portal first, then the app with the headers Authelia's forward auth
sets; in the plain mode (`./mode.sh plain`) the app's own password login.

Standard library only. The names *.general-chat.localhost are reached at 127.0.0.1:8443 whatever the system's
resolver says, and Caddy's local CA is not checked (a test stack of this machine only).
"""
import email
import email.header
import http.client
import json
import pathlib
import socket
import ssl
import subprocess
import time
import uuid

HERE = pathlib.Path(__file__).parent


def _mode():
    """The mode mode.sh wrote into .env - "wam" without one (the stack as it was first built)."""
    env = HERE / ".env"
    if env.exists():
        for line in env.read_text().splitlines():
            if line.startswith("STACK_MODE="):
                return line.split("=", 1)[1].strip() or "wam"
    return "wam"


MODE = _mode()
WAM = MODE == "wam"
# Each mode its own ids - its own database.
STATE_FILE = HERE / ("state.json" if WAM else f"state-{MODE}.json")

PORT = 8443
AUTH_HOST = "auth.general-chat.localhost"
APP_HOST = "ai.general-chat.localhost"
# MailHog's page and API (the container mailhog) and the file store's container (docker-compose.yml).
MAIL_HOST = "mail.general-chat.localhost"
SFTP_CONTAINER = "general-chat-test-sftp"
# The file store's folder on the SFTP server, below it: wissen (the knowledge source) and logs (the task).
STORE_PATH = "/home/check/ablage"
# Every invented person's password at Authelia (authelia/users_database.yml) - published, for this stack only.
PERSON_PASSWORD = "Testpasswort-2026"
# The app's own admin (docker-compose.yml) - its password login, behind Authelia like every page.
ADMIN_USER = "admin"
ADMIN_PASSWORD = "Admin-Testpasswort-2026"
# The admin's identity at Authelia: every request passes the portal first.
ADMIN_PERSON = "betrieb"

_CONTEXT = ssl.create_default_context()
_CONTEXT.check_hostname = False
_CONTEXT.verify_mode = ssl.CERT_NONE


class _Local(http.client.HTTPSConnection):
    """HTTPS to 127.0.0.1 with the stack's host name in SNI and Host."""

    def __init__(self, host, timeout=600):
        super().__init__(host, PORT, context=_CONTEXT, timeout=timeout)

    def connect(self):
        sock = socket.create_connection(("127.0.0.1", PORT), self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)


def request(host, method, path, body=None, headers=None, raw=False, timeout=600):
    """status, response headers and the parsed body (raw: the text) of one request."""
    connection = _Local(host, timeout)
    sent = dict(headers or {})
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        sent.setdefault("Content-Type", "application/json")
    sent.setdefault("Accept", "application/json")
    connection.request(method, path, body=data, headers=sent)
    response = connection.getresponse()
    text = response.read().decode("utf-8", "replace")
    connection.close()
    if raw:
        return response.status, response.getheaders(), text
    try:
        parsed = json.loads(text) if text else None
    except json.JSONDecodeError:
        parsed = text
    return response.status, response.getheaders(), parsed


class Person:
    """
    Someone at the stack: in the WAM mode signed in at Authelia (its session cookie goes with every request to the
    app); in the plain mode nobody yet - sign_in logs in with the password.
    """

    def __init__(self, username, password=PERSON_PASSWORD):
        self.username = username
        self.password = password
        self.cookie = ""
        self.token = None
        if not WAM:
            return
        status, headers, body = request(AUTH_HOST, "POST", "/api/firstfactor",
                                        {"username": username, "password": password, "keepMeLoggedIn": False,
                                         "targetURL": f"https://{APP_HOST}:{PORT}/"})
        if status != 200:
            raise SystemExit(f"Authelia refused {username}: {status} {body}")
        cookies = [value.split(";")[0] for name, value in headers if name.lower() == "set-cookie"]
        self.cookie = "; ".join(cookies)
        self.token = None

    def app(self, method, path, body=None, headers=None, raw=False):
        sent = {**({"Cookie": self.cookie} if self.cookie else {}), **(headers or {})}
        if self.token:
            sent["Authorization"] = "Bearer " + self.token
        return request(APP_HOST, method, path, body, sent, raw)

    def sign_in(self, tenant_id=None, language=None):
        """
        WAM: POST /login/wam (language: the browser's, as the frontend sends it) - the token (kept), or the tenants to
        choose from, or the status of a refusal. Plain: POST /login with the password - with tenant_id through that
        tenant's own login page (its login key, needed for a name in several tenants).
        """
        if not WAM:
            return self.log_in(tenant_id)
        body = {} if tenant_id is None else {"tenantId": tenant_id}
        if language is not None:
            body["language"] = language
        status, _, body = self.app("POST", "/login/wam", body)
        if status == 200 and isinstance(body, dict) and body.get("access_token"):
            self.token = body["access_token"]
        return status, body

    def log_in(self, tenant_id=None):
        headers = {}
        if tenant_id is not None:
            headers["X-Login-Key"] = login_key(tenant_id)
        status, _, body = self.app("POST", "/login", {"username": self.username, "password": self.password}, headers)
        if status == 200 and isinstance(body, dict) and body.get("access_token"):
            self.token = body["access_token"]
        return status, body

    def ask(self, model_id, question, excluded=None, conversation_id=None, excluded_skills=None):
        """A question through the chat's stream: the answer, the tools called (name, software) and the seconds it took.
        model_id None: a tenant answering through its A2A agent (excluded_skills: the agent's skills left out)."""
        request_body = {"modelId": model_id, "message": question}
        if excluded is not None:
            request_body["excludedConnectionIds"] = excluded
        if excluded_skills is not None:
            request_body["excludedSkillIds"] = excluded_skills
        if conversation_id is not None:
            request_body["conversationId"] = conversation_id
        started = time.monotonic()
        status, _, text = self.app("POST", "/tenant/chat/stream", request_body,
                                   {"Accept": "text/event-stream, application/json"}, raw=True)
        seconds = time.monotonic() - started
        if status != 200:
            return {"error": f"{status} {text}", "tools": [], "seconds": seconds}
        tools, done, error = [], None, None
        for event, data in _events(text):
            if event == "tool":
                tools.append((data.get("name"), data.get("software")))
            elif event == "done":
                done = data
            elif event == "error":
                error = data.get("message")
        answer = (done or {}).get("answer") or {}
        return {"answer": answer.get("content", ""), "sources": (done or {}).get("sources", []),
                "files": [file["fileName"] for file in answer.get("files") or []],
                "conversation": (done or {}).get("conversation"), "tools": tools, "error": error, "seconds": seconds}

    def upload(self, path, conversation_id=None):
        """A file into the chat as the paperclip sends it - into the conversation, or a new one: the conversation."""
        boundary = "----generalchat" + uuid.uuid4().hex
        parts = []
        if conversation_id is not None:
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="conversationId"\r\n\r\n'
                         f'{conversation_id}\r\n'.encode())
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{path.name}"\r\n'
                     f'Content-Type: application/octet-stream\r\n\r\n'.encode() + path.read_bytes() + b"\r\n")
        parts.append(f"--{boundary}--\r\n".encode())
        connection = _Local(APP_HOST)
        connection.request("POST", "/tenant/chat/files", body=b"".join(parts),
                           headers={"Content-Type": f"multipart/form-data; boundary={boundary}", "Cookie": self.cookie,
                                    "Authorization": "Bearer " + self.token, "Accept": "application/json"})
        response = connection.getresponse()
        body = response.read().decode()
        if response.status != 201:
            raise SystemExit(f"uploading {path.name} answered {response.status}: {body}")
        return json.loads(body)["conversation"]


def _events(text):
    for block in text.split("\n\n"):
        event, data = None, []
        for line in block.splitlines():
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data.append(line[5:].lstrip())
        if event and data:
            try:
                yield event, json.loads("\n".join(data))
            except json.JSONDecodeError:
                pass


def login_key(tenant_id):
    """The tenant's own login page's key (setup.py keeps them in the mode's state)."""
    state = json.loads(STATE_FILE.read_text())
    return state["loginKeys"][str(tenant_id)]


def admin():
    """The app's admin with its own password - in the WAM mode signed in at Authelia as its identity first."""
    person = Person(ADMIN_PERSON)
    status, _, body = person.app("POST", "/login", {"username": ADMIN_USER, "password": ADMIN_PASSWORD})
    if status != 200:
        raise SystemExit(f"the admin's login answered {status} {body}")
    person.token = body["access_token"]
    return person


def wait_until_up(seconds=300):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            status = request(AUTH_HOST, "GET", "/api/health")[0] if WAM else 200
            if status == 200:
                person = Person(ADMIN_PERSON)
                if person.app("GET", "/watchdog")[0] == 200:
                    return
        except (OSError, SystemExit, http.client.HTTPException):
            pass
        time.sleep(3)
    raise SystemExit("the stack did not come up")


# --- Machines with a key: the client software (/api/v1), an MCP client (/mcp), an A2A agent (/a2a) ---------------------


def with_key(key, method, path, body=None, headers=None, raw=False):
    """A machine's request: its bearer key and nothing else - no session at Authelia, Caddy lets these paths pass."""
    return request(APP_HOST, method, path, body, {"Authorization": "Bearer " + key, **(headers or {})}, raw)


def push(api_key, file_name, content, external_id=None, area=None):
    """POST /api/v1/documents as the client software pushes a file: the status and the document (or the refusal)."""
    boundary = "----generalchat" + uuid.uuid4().hex
    parts = []
    for name, value in (("externalId", external_id), ("area", area)):
        if value is not None:
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{file_name}"\r\n'
                 f'Content-Type: application/octet-stream\r\n\r\n'.encode() + content + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    connection = _Local(APP_HOST)
    connection.request("POST", "/api/v1/documents", body=b"".join(parts),
                       headers={"Content-Type": f"multipart/form-data; boundary={boundary}",
                                "Authorization": "Bearer " + api_key, "Accept": "application/json"})
    response = connection.getresponse()
    text = response.read().decode()
    try:
        return response.status, json.loads(text)
    except json.JSONDecodeError:
        return response.status, text


def upload_with_key(api_key, file_name, content, conversation_id=None):
    """POST /api/v1/chat/files as the client software sends a file for a conversation: the status and the answer."""
    boundary = "----generalchat" + uuid.uuid4().hex
    parts = []
    if conversation_id is not None:
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="conversationId"\r\n\r\n'
                     f'{conversation_id}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{file_name}"\r\n'
                 f'Content-Type: application/octet-stream\r\n\r\n'.encode() + content + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    connection = _Local(APP_HOST)
    connection.request("POST", "/api/v1/chat/files", body=b"".join(parts),
                       headers={"Content-Type": f"multipart/form-data; boundary={boundary}",
                                "Authorization": "Bearer " + api_key, "Accept": "application/json"})
    response = connection.getresponse()
    text = response.read().decode()
    try:
        return response.status, json.loads(text)
    except json.JSONDecodeError:
        return response.status, text


def wait_embedded(api_key, seconds=600):
    """The documents the key finds, once none waits for its embedding any more."""
    deadline = time.monotonic() + seconds
    while True:
        documents = with_key(api_key, "GET", "/api/v1/documents")[2]
        if all(document["status"] in ("READY", "FAILED") for document in documents):
            return documents
        if time.monotonic() > deadline:
            raise SystemExit(f"the documents were not embedded in time: {documents}")
        time.sleep(2)


def mcp(key, method, params=None):
    """One JSON-RPC call at the tenant's MCP server: the status and the message (answered as JSON or as one event)."""
    status, _, text = with_key(key, "POST", "/mcp", {"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}},
                               {"Accept": "application/json, text/event-stream"}, raw=True)
    if "{" not in text:
        return status, {}
    return status, json.JSONDecoder().raw_decode(text[text.index("{"):])[0]


def tool(key, name, arguments):
    """tools/call: the text the tool answered and whether it is a tool error."""
    status, message = mcp(key, "tools/call", {"name": name, "arguments": arguments})
    result = message.get("result") or {}
    text = "\n".join(part.get("text", "") for part in result.get("content") or [])
    return text or f"{status} {message}", bool(result.get("isError")) or "result" not in message


def a2a(key, method, params):
    """One JSON-RPC call at the tenant's A2A agent: the status and the message."""
    status, _, message = with_key(key, "POST", "/a2a", {"jsonrpc": "2.0", "id": 1, "method": method, "params": params},
                                  {"A2A-Version": "1.0"})
    return status, message


# --- The stack's own servers --------------------------------------------------------------------------------------------


def sftp(script):
    """A shell line on the file store's server, as its user - how the checks put, change and remove its files."""
    done = subprocess.run(["podman", "exec", "--user", "check", SFTP_CONTAINER, "sh", "-c", script],
                          capture_output=True, text=True)
    if done.returncode != 0:
        raise SystemExit(f"the file store's server refused `{script}`: {done.stderr.strip()}")
    return done.stdout


def mails():
    """Every mail MailHog holds, the newest first: (recipients, subject, the whole message as text)."""
    found = request(MAIL_HOST, "GET", "/api/v2/messages?limit=250")[2]
    messages = []
    for item in (found or {}).get("items", []):
        headers = item["Content"]["Headers"]
        subject = str(email.header.make_header(email.header.decode_header(headers.get("Subject", [""])[0])))
        message = email.message_from_string(item["Raw"]["Data"])
        text = "\n".join(part.get_payload(decode=True).decode(part.get_content_charset() or "utf-8", "replace")
                         for part in message.walk() if not part.is_multipart())
        messages.append((item["Raw"]["To"], subject, text))
    return messages
