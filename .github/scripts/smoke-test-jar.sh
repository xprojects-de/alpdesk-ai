#!/usr/bin/env bash
# Starts the AOT-optimized jar the way the container does (no "dev" environment) and checks what the Spock suite cannot
# see, because it runs the unoptimized classes: AOT precomputes the environment, so a change of the aot {} settings or
# a Micronaut/AOT upgrade could stop the APP_* / JWT_* / DATASOURCES_* variables from being read.
#
#   1. without its secrets the jar refuses to start (ProductionSecretsCheck),
#   2. with them it starts against the database, answers /watchdog, and the admin can log in with APP_USERNAME and
#      APP_PASSWORD - read from the environment, not the defaults,
#   3. the log lines carry the date (the logback.xml baked into the jar),
#   4. a new tenant gets its own database (Flyway reads db/tenant/ from the jar) and a tenant user logs in; the tenant's
#      API key uploads a DOCX that Tika recognises and reads (its detectors and parsers are found through service
#      loading, which AOT touches), it is embedded and the chat answers, in one piece and as the stream's events
#      (/api/v1/chat/stream ends with "done") - both through a fake Ollama server of this
#      script (langchain4j-ollama's HTTP client and JSON under AOT, plain HTTP to an allowed host) - and the admin
#      imports a ZIP archive with it; the tenant's MCP server initializes, answers ask_assistant through the same
#      fake, finds the DOCX with search_knowledge and reads it with read_document, and offers the tenant's templates as
#      prompts; and the tenant's software is connected through its MCP server (the same fake answers as one): its tools
#      are read, and in the chat of a tenant user whose group may read the software the model calls one (the MCP Java SDK's
#      client and its JSON under AOT), and - switched "Direkt an MCP-Clients" and given to an MCP key - passed through: its
#      tool listed for the key with its own entry (title, annotations, _meta) and called by the client itself, its
#      instructions after ours, its resources listed and read with the connection in front once switched on. Knowledge areas: a key of an area pushes into it, a member of the area's group
#      finds its document in the chat and a user
#      without it does not (the searches' own SQL with the area filter and pgvector's iterative scan), the keys list
#      what they find, and the company knowledge's uploads are deleted at once while the pushed documents stay. A
#      scheduled task is checked and saved (SnakeYAML Engine) and its run fails at a store out of reach (langgraph4j,
#      its checkpoint saver and the run's record in the tenant database, under AOT).
#      No smoke test calls a real AI provider,
#   5. without WAM the user header of a WAM proxy counts for nothing: a fake WAM proxy of this script in front of the
#      jar (it removes the header from outside and sends the person signed in at it) - the password login works
#      through it, POST /login/wam does not exist, and a token works whatever user the proxy names,
#   6. a jar built with the frontend serves it,
#   7. started under a context path (MICRONAUT_SERVER_CONTEXT_PATH, behind a reverse proxy at a sub-path - read at
#      startup despite AOT) everything lives under it: the liveness probe, the login, Swagger (its page built without the
#      path, told it at runtime, and its API description), the tenant's MCP server with its key, and the frontend's
#      shell with the path as its base href; started with the chat files' retention variables set empty (the README's
#      way to keep the files) the log says they are kept - with the defaults (step 2) what is deleted when; with
#      APP_CORS_ORIGINS a listed origin's preflight passes, without credentials, another one is refused,
#   8. with APP_WAM_ENABLED and APP_WAM_GROUPS_HEADER (read at startup despite AOT) everything goes through the fake
#      WAM proxy: a header set from outside signs nobody in, someone unknown waits, the admin assigns them active as a
#      tenant's admin, who signs in and approves the next one; another person at the proxy ends a session (403); tenant users have no password
#      login, the admin keeps its own; the same person made a user of a second tenant chooses between them, switches
#      in the user menu without a password, and the switched token holds only for that person,
#   9. the general chat (roadmap point 51): a topic tenant's MCP key with a description, a second tenant connected to the
#      jar's own /mcp with it; a person the fake proxy lists in the two tenants' IDM groups gets a user in each (the
#      choice), signs in at the general chat, whose model (the fake Ollama) calls <connection>__search_knowledge and
#      gets the topic's DOCX text with [<connection>] in front; the topic left out of the question - no call; the
#      switch to the topic. No real model.
#  11. (within 4 and 7) the tenant's assistant as an A2A agent (docs/architecture/a2a.md): the anonymous neutral card,
#      a key with the A2A switch - its extended card with the brand, a question answered through the fake Ollama -, a key
#      without it refused at /a2a; under the context path the card names /alpdesk-ai/a2a.
#
# Needs a PostgreSQL in DATASOURCES_DEFAULT_URL / _USERNAME / _PASSWORD. JAR and PORT can be overridden (run from the
# repository root): DATASOURCES_DEFAULT_URL=... PORT=29100 .github/scripts/smoke-test-jar.sh
set -euo pipefail

JAR="${JAR:-Backend/build/libs/alpdesk-ai.jar}"
PORT="${PORT:-19100}"
# The fake Ollama server of step 4 - also the tenant's software with an MCP server at /mcp.
OLLAMA_PORT="${OLLAMA_PORT:-$((PORT + 1))}"
ollama_pid=""
# Nothing listens here: the file store of step 4's task and the mail server it reports to.
STORE_PORT="${STORE_PORT:-$((PORT + 3))}"
SMTP_PORT="${SMTP_PORT:-$((PORT + 4))}"
# The fake WAM proxy of steps 5 and 8, in front of the jar.
WAM_PORT="${WAM_PORT:-$((PORT + 2))}"
wam_pid=""
LOG="$(mktemp)"
DOCX="$(mktemp).docx"
# The chat's file storage of the started jar - never a folder of the working tree.
STORAGE="$(mktemp -d)"
export APP_STORAGE_PATH="$STORAGE"
pid=""
# Whatever ends the script: no JVM and no temporary files stay behind.
trap 'for p in "$pid" "$ollama_pid" "$wam_pid"; do if [ -n "$p" ]; then kill "$p" 2>/dev/null || true; fi; done; rm -rf "$LOG" "$DOCX" "$STORAGE"' EXIT
: "${DATASOURCES_DEFAULT_URL:?the database URL is required}"
: "${DATASOURCES_DEFAULT_USERNAME:?the database user is required}"
: "${DATASOURCES_DEFAULT_PASSWORD:?the database password is required}"

fail() {
  echo "SMOKE TEST FAILED: $*"
  echo "--- application log ---"
  cat "$LOG"
  exit 1
}

# A request through the fake WAM proxy as the person signed in at it ("" - nobody): prints the body, then the HTTP
# status on a line of its own (status_of / body_of take them apart).
through_wam() {
  local person="$1" method="$2" path="$3" token="${4:-}" body="${5:-}" groups="${6:-}"
  local args=(-sS -X "$method" -w '\n%{http_code}')
  if [ -n "$person" ]; then args+=(-H "X-Wam-Person: $person"); fi
  if [ -n "$groups" ]; then args+=(-H "X-Wam-Groups: $groups"); fi
  if [ -n "$token" ]; then args+=(-H "Authorization: Bearer $token"); fi
  if [ -n "$body" ]; then args+=(-H "Content-Type: application/json" -d "$body"); fi
  curl "${args[@]}" "http://127.0.0.1:$WAM_PORT$path" || true
}
status_of() { tail -n 1 <<< "$1"; }
body_of() { sed '$d' <<< "$1"; }

echo "1. Without its secrets the jar must refuse to start"
env -u APP_USERNAME -u APP_PASSWORD -u APP_SECRET_KEY -u JWT_GENERATOR_SIGNATURE_SECRET -u MICRONAUT_ENVIRONMENTS \
  MICRONAUT_SERVER_PORT="$PORT" \
  java --enable-native-access=ALL-UNNAMED -jar "$JAR" > "$LOG" 2>&1 &
pid=$!
for _ in $(seq 1 60); do
  kill -0 "$pid" 2>/dev/null || break
  sleep 1
done
if kill -0 "$pid" 2>/dev/null; then
  kill "$pid"
  fail "it was still running after 60 s"
fi
grep -q "Refusing to start" "$LOG" || fail "it stopped, but not with 'Refusing to start'"

echo "2. With its secrets it starts, answers /watchdog and takes the admin login from the environment"
export APP_USERNAME="smoke-admin"
APP_PASSWORD="$(openssl rand -base64 24)"
APP_SECRET_KEY="$(openssl rand -base64 32)"
JWT_GENERATOR_SIGNATURE_SECRET="$(openssl rand -base64 48)"
export APP_PASSWORD APP_SECRET_KEY JWT_GENERATOR_SIGNATURE_SECRET
unset MICRONAUT_ENVIRONMENTS
MICRONAUT_SERVER_PORT="$PORT" APP_AI_PLAIN_HTTP_HOSTS="127.0.0.1" APP_FILE_STORE_INTERNAL_HOSTS="127.0.0.1" \
  APP_MAIL_HOST=127.0.0.1 APP_MAIL_PORT="$SMTP_PORT" APP_MAIL_SECURITY=NONE APP_MAIL_FROM=smoke@example.com \
  java --enable-native-access=ALL-UNNAMED -jar "$JAR" > "$LOG" 2>&1 &
pid=$!
for _ in $(seq 1 120); do
  curl -fsS "http://127.0.0.1:$PORT/watchdog" > /dev/null 2>&1 && break
  kill -0 "$pid" 2>/dev/null || fail "the jar exited during startup"
  sleep 1
done
curl -fsS "http://127.0.0.1:$PORT/watchdog" > /dev/null || fail "/watchdog did not answer within 120 s"
grep -q "Chat files: an uploaded video deleted 8 hours after its upload, every other file deleted 14 days after it was stored" "$LOG" \
  || fail "the start does not say the chat files' default retention"
# Answers are read into a variable first: `curl | grep -q` fails under pipefail when grep stops reading early.
login=$(curl -sS -X POST -H "Content-Type: application/json" \
  -d "{\"username\":\"$APP_USERNAME\",\"password\":\"$APP_PASSWORD\"}" "http://127.0.0.1:$PORT/login") || true
grep -q '"access_token"' <<< "$login" || fail "the login with APP_USERNAME/APP_PASSWORD was refused"
token=$(sed -E 's/.*"access_token":"([^"]+)".*/\1/' <<< "$login")
default_login=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Content-Type: application/json" \
  -d '{"username":"alpdesk","password":"alpdesk"}' "http://127.0.0.1:$PORT/login") || true
[ "$default_login" = "401" ] || fail "the default login alpdesk/alpdesk answered $default_login, not 401"

echo "3. The log lines carry the date"
grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:.]+ \[' "$LOG" || fail "no dated log line - is logback.xml baked into the jar?"

echo "4. A tenant gets its database, its user logs in, its API key uploads a DOCX"
tenant=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"name\":\"Smoke $(date +%s)\"}" "http://127.0.0.1:$PORT/admin/tenants") || true
tenant_id=$(sed -E 's/.*"id":([0-9]+).*/\1/' <<< "$tenant")
[[ "$tenant_id" =~ ^[0-9]+$ ]] || fail "creating a tenant (and its database) answered: $tenant"
user_password="$(openssl rand -hex 16)"
user=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"username\":\"smoke-user-$tenant_id\",\"displayName\":\"Smoke\",\"password\":\"$user_password\"}" \
  "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/users") || true
user_id=$(sed -E 's/.*"id":([0-9]+).*/\1/' <<< "$user")
user_login=$(curl -sS -X POST -H "Content-Type: application/json" \
  -d "{\"username\":\"smoke-user-$tenant_id\",\"password\":\"$user_password\"}" "http://127.0.0.1:$PORT/login") || true
grep -q 'ROLE_TENANT_USER' <<< "$user_login" || fail "the tenant user's login answered: $user_login"
user_token=$(sed -E 's/.*"access_token":"([^"]+)".*/\1/' <<< "$user_login")
account=$(curl -sS -H "Authorization: Bearer $user_token" "http://127.0.0.1:$PORT/tenant/account") || true
grep -q "\"tenantId\":$tenant_id" <<< "$account" || fail "the tenant user's account answered: $account"
python3 - "$OLLAMA_PORT" <<'PYTHON' &
import json, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SOFTWARE_TOOLS = [
    {"name": "find_invoice", "title": "Find an invoice", "description": "Finds an invoice by its number",
     "annotations": {"readOnlyHint": True, "idempotentHint": True}, "_meta": {"com.example/category": "smoke"},
     "inputSchema": {"type": "object", "properties": {"number": {"type": "string"}}, "required": ["number"]}},
    {"name": "cancel_invoice", "description": "Cancels an invoice",
     "inputSchema": {"type": "object", "properties": {"number": {"type": "string"}}, "required": ["number"]}}]

class FakeOllama(BaseHTTPRequestHandler):
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path == "/api/embed":
            texts = request["input"] if isinstance(request["input"], list) else [request["input"]]
            self.answer({"model": request["model"], "embeddings": [[0.5] * 8 for _ in texts], "prompt_eval_count": 3})
        elif self.path == "/api/chat":
            self.answer({"model": request["model"], "created_at": "2026-10-06T10:00:00Z", "done": True,
                         "message": self.chat_message(request), "done_reason": "stop", "prompt_eval_count": 5,
                         "eval_count": 4})
        elif self.path == "/mcp":
            self.mcp(request)
        else:
            self.send_error(404)

    def do_GET(self):
        # No event stream of its own, as MCP's streamable HTTP allows.
        self.send_error(405)

    def chat_message(self, request):
        # Offered a software's find_invoice, the model calls it once - then answers with what the software said.
        offered = [t["function"]["name"] for t in request.get("tools") or []]
        software = next((name for name in offered if name.endswith("__find_invoice")), None)
        topic = next((name for name in offered if name.endswith("__search_knowledge")), None)
        results = [m["content"] for m in request["messages"] if m["role"] == "tool"]
        if software and not results:
            return {"role": "assistant", "content": "",
                    "tool_calls": [{"function": {"name": software, "arguments": {"number": "4711"}}}]}
        # The general chat of step 9: a topic tenant's MCP server reached as connected software.
        if topic and not results:
            return {"role": "assistant", "content": "",
                    "tool_calls": [{"function": {"name": topic, "arguments": {"query": "alpdesk smoke test document"}}}]}
        return {"role": "assistant", "content": "smoke answer from ollama" + "".join(" - " + r for r in results)}

    def mcp(self, message):
        if "id" not in message:
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        method = message["method"]
        if method == "initialize":
            result = {"protocolVersion": message["params"]["protocolVersion"], "capabilities": {"tools": {}, "resources": {}},
                      "serverInfo": {"name": "smoke-software", "version": "1"},
                      "instructions": "Call find_invoice with the invoice's number."}
        elif method == "tools/list":
            result = {"tools": SOFTWARE_TOOLS}
        elif method == "tools/call":
            number = message["params"]["arguments"]["number"]
            result = {"content": [{"type": "text", "text": "Invoice " + number + " paid"}], "isError": False}
        elif method == "resources/list":
            result = {"resources": [{"uri": "invoice://4711/receipt.txt", "name": "receipt.txt"}]}
        elif method == "resources/read" and message["params"]["uri"] == "invoice://4711/receipt.txt":
            result = {"contents": [{"uri": "invoice://4711/receipt.txt", "mimeType": "text/plain",
                                    "text": "Receipt of invoice 4711"}]}
        else:
            self.answer({"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": "Unknown method"}})
            return
        self.answer({"jsonrpc": "2.0", "id": message["id"], "result": result}, {"Mcp-Session-Id": "smoke-session"})

    def answer(self, body, headers={}):
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        for name, value in headers.items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass

ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), FakeOllama).serve_forever()
PYTHON
ollama_pid=$!
ollama="http://127.0.0.1:$OLLAMA_PORT"
embedding=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"provider\":\"OLLAMA\",\"kind\":\"EMBEDDING\",\"modelName\":\"smoke-embed-$tenant_id\",\"displayName\":\"Smoke\",\"dimension\":8,\"inputPrice\":0,\"outputPrice\":0,\"baseUrl\":\"$ollama\"}" \
  "http://127.0.0.1:$PORT/admin/models") || true
embedding_id=$(sed -E 's/.*"id":([0-9]+).*/\1/' <<< "$embedding")
[[ "$embedding_id" =~ ^[0-9]+$ ]] || fail "creating an Ollama embedding model answered: $embedding"
chat=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"provider\":\"OLLAMA\",\"kind\":\"CHAT\",\"modelName\":\"smoke-chat-$tenant_id\",\"displayName\":\"Smoke Chat\",\"inputPrice\":0,\"outputPrice\":0,\"baseUrl\":\"$ollama\"}" \
  "http://127.0.0.1:$PORT/admin/models") || true
chat_id=$(sed -E 's/.*"id":([0-9]+).*/\1/' <<< "$chat")
[[ "$chat_id" =~ ^[0-9]+$ ]] || fail "creating an Ollama chat model answered: $chat"
curl -sS -o /dev/null -X PUT -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"modelIds\":[$embedding_id,$chat_id]}" "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/models" || true
chosen=$(curl -sS -X PUT -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"modelId\":$embedding_id}" "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/embedding-model") || true
grep -q "\"embeddingModelId\":$embedding_id" <<< "$chosen" || fail "choosing the embedding model answered: $chosen"
created=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d '{"name":"smoke"}' "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/api-keys") || true
key=$(sed -E 's/.*"plainKey":"([^"]+)".*/\1/' <<< "$created")
[[ "$key" == ai_* ]] || fail "creating an API key answered: $created"
key_id=$(sed -E 's/.*"key":\{"id":([0-9]+).*/\1/' <<< "$created")
python3 - "$DOCX" <<'PYTHON'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as docx:
    docx.writestr("[Content_Types].xml", '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
    docx.writestr("_rels/.rels", '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
    docx.writestr("word/document.xml", '<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>alpdesk smoke test document</w:t></w:r></w:p></w:body></w:document>')
PYTHON
uploaded=$(curl -sS -X POST -H "Authorization: Bearer $key" -F "file=@$DOCX;type=application/octet-stream;filename=smoke.docx" \
  "http://127.0.0.1:$PORT/api/v1/documents") || true
grep -q 'wordprocessingml.document' <<< "$uploaded" || fail "the DOCX upload answered: $uploaded"
documents=""
for _ in $(seq 1 30); do
  documents=$(curl -sS -H "Authorization: Bearer $key" "http://127.0.0.1:$PORT/api/v1/documents") || true
  grep -q '"status":"\(READY\|FAILED\)"' <<< "$documents" && break
  sleep 1
done
grep -q '"status":"READY"' <<< "$documents" || fail "the DOCX was not embedded through the fake Ollama: $documents"
answer=$(curl -sS -X POST -H "Authorization: Bearer $key" -H "Content-Type: application/json" \
  -d "{\"modelId\":$chat_id,\"message\":\"smoke question\"}" "http://127.0.0.1:$PORT/api/v1/chat") || true
grep -q 'smoke answer from ollama' <<< "$answer" || fail "the chat through the fake Ollama answered: $answer"
# The same, read as it comes: the stream's events end with "done" carrying the answer.
streamed=$(curl -sS -N -X POST -H "Authorization: Bearer $key" -H "Content-Type: application/json" \
  -H "Accept: text/event-stream, application/json" \
  -d "{\"modelId\":$chat_id,\"message\":\"smoke question\"}" "http://127.0.0.1:$PORT/api/v1/chat/stream") || true
grep -q '^event: done' <<< "$streamed" || fail "the chat stream did not end with done: $streamed"
grep -q 'smoke answer from ollama' <<< "$streamed" || fail "the chat stream answered: $streamed"
curl -sS -o /dev/null -X PUT -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"answerSource\":\"DOCUMENTS_ONLY\",\"creativity\":\"MEDIUM\",\"reasoning\":\"LOW\",\"searchResults\":5,\"defaultModelId\":$chat_id}" \
  "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/assistant" || true
curl -sS -o /dev/null -X PUT -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d '{"name":"Smoke Brand"}' "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/branding" || true
mcp_created=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d '{"name":"Smoke"}' "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/mcp-keys") || true
mcp_key=$(sed -E 's/.*"plainKey":"([^"]+)".*/\1/' <<< "$mcp_created")
[[ "$mcp_key" == mcp_* ]] || fail "creating the MCP key answered: $mcp_created"
# Step 11: the tenant's assistant as an A2A agent on the official SDK (Gson and protobuf under AOT): the neutral public
# card, the key's extended card with the brand, a question answered through the fake Ollama, the doors of a key.
public_card=$(curl -sS "http://127.0.0.1:$PORT/.well-known/agent-card.json") || true
grep -q '"name":"AI assistant"' <<< "$public_card" || fail "the public agent card answered: $public_card"
grep -q "\"url\":\"http://127.0.0.1:$PORT/a2a\"" <<< "$public_card" || fail "the public agent card names no /a2a: $public_card"
a2a_created=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d '{"name":"Smoke A2A","a2a":true,"mcp":false}' "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/mcp-keys") || true
a2a_key=$(sed -E 's/.*"plainKey":"([^"]+)".*/\1/' <<< "$a2a_created")
[[ "$a2a_key" == mcp_* ]] || fail "creating the A2A key answered: $a2a_created"
extended=$(curl -sS -X POST -H "Authorization: Bearer $a2a_key" -H "Content-Type: application/json" -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":1,"method":"GetExtendedAgentCard","params":{}}' "http://127.0.0.1:$PORT/a2a") || true
grep -q '"Smoke Brand"' <<< "$extended" || fail "the extended agent card answered: $extended"
a2a_answer=$(curl -sS -X POST -H "Authorization: Bearer $a2a_key" -H "Content-Type: application/json" -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":2,"method":"SendMessage","params":{"message":{"role":"ROLE_USER","messageId":"smoke-1","parts":[{"text":"smoke question"}]}}}' \
  "http://127.0.0.1:$PORT/a2a") || true
grep -q 'smoke answer from ollama' <<< "$a2a_answer" || fail "the A2A agent answered: $a2a_answer"
a2a_refused=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $mcp_key" \
  -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":3,"method":"GetExtendedAgentCard","params":{}}' \
  "http://127.0.0.1:$PORT/a2a") || true
[ "$a2a_refused" = "401" ] || fail "an MCP key without A2A answered $a2a_refused at /a2a, not 401"
initialized=$(curl -sS -X POST -H "Authorization: Bearer $mcp_key" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
  "http://127.0.0.1:$PORT/mcp") || true
grep -q '"protocolVersion":"2025-06-18"' <<< "$initialized" || fail "the MCP server's initialize answered: $initialized"
# The server goes by the tenant's brand - BrandedMcpServer wraps the SDK's handler, which the AOT jar must keep.
grep -q '"serverInfo":{"name":"Smoke Brand"' <<< "$initialized" \
  && grep -q '"instructions":"Smoke Brand is the AI assistant of the company' <<< "$initialized" \
  || fail "the MCP server's initialize does not carry the tenant's brand: $initialized"
asked=$(curl -sS -X POST -H "Authorization: Bearer $mcp_key" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ask_assistant","arguments":{"question":"smoke question"}}}' \
  "http://127.0.0.1:$PORT/mcp") || true
grep -q 'smoke answer from ollama' <<< "$asked" || fail "the MCP server's ask_assistant answered: $asked"
# The client's model searches and reads itself (KnowledgeMcpTools): the passage under its document's id, then the
# document read by that id - the query embedded through the fake Ollama, the text read from the tenant's database.
searched=$(curl -sS -X POST -H "Authorization: Bearer $mcp_key" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search_knowledge","arguments":{"query":"alpdesk smoke test document"}}}' \
  "http://127.0.0.1:$PORT/mcp") || true
document_id=$(sed -nE 's/.*smoke\.docx\\" \(document ([0-9]+).*/\1/p' <<< "$searched")
[[ "$document_id" =~ ^[0-9]+$ ]] || fail "the MCP server's search_knowledge answered: $searched"
read_back=$(curl -sS -X POST -H "Authorization: Bearer $mcp_key" -H "Content-Type: application/json" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"read_document\",\"arguments\":{\"document\":$document_id}}}" \
  "http://127.0.0.1:$PORT/mcp") || true
grep -q 'alpdesk smoke test document' <<< "$read_back" && grep -q '(complete)' <<< "$read_back" \
  || fail "the MCP server's read_document answered: $read_back"
# The tenant's templates are its prompts, answered by BrandedMcpServer itself (AssistantMcpPrompts) - the AOT jar too.
grep -q '"prompts":{' <<< "$initialized" || fail "the MCP server's initialize does not offer prompts: $initialized"
curl -sS -o /dev/null -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d '{"name":"Smoke Prüfung","instructions":"Antworte knapp."}' "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/assistant/templates" || true
prompts=$(curl -sS -X POST -H "Authorization: Bearer $mcp_key" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"prompts/list"}' "http://127.0.0.1:$PORT/mcp") || true
grep -q '"name":"smoke-pruefung"' <<< "$prompts" && grep -q 'Asks Smoke Brand' <<< "$prompts" \
  || fail "the MCP server's prompts/list answered: $prompts"
connection=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"name\":\"Software\",\"baseUrl\":\"$ollama/mcp\"}" "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/mcp-connections") || true
read -r connection_id tool_id < <(python3 -c '
import json, sys
c = json.loads(sys.argv[1])
print(c["id"], next(t["id"] for t in c["tools"] if t["name"] == "find_invoice" and not t["changes"]))
' "$connection" 2>/dev/null) || fail "connecting the software through its MCP server answered: $connection"
connections="http://127.0.0.1:$PORT/admin/tenants/$tenant_id/mcp-connections/$connection_id"
curl -sS -o /dev/null -X PUT -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d '{"enabled":true}' "$connections/tools/$tool_id" || true
software_group=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"name\":\"Software\",\"userIds\":[$user_id],\"levels\":[{\"connectionId\":$connection_id,\"level\":\"READ\"}]}" \
  "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/groups") || true
grep -q '"level":"READ"' <<< "$software_group" || fail "creating a user group with its software answered: $software_group"
software_answer=$(curl -sS -N -X POST -H "Authorization: Bearer $user_token" -H "Content-Type: application/json" \
  -H "Accept: text/event-stream, application/json" \
  -d "{\"modelId\":$chat_id,\"message\":\"smoke software question\"}" "http://127.0.0.1:$PORT/tenant/chat/stream") || true
grep -q 'Invoice 4711 paid' <<< "$software_answer" || fail "the chat calling the software's tool answered: $software_answer"
# The API key is given the software (roadmap point 42) - the connection opens nothing to the client software itself.
curl -sS -o /dev/null -X PUT -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"connectionIds\":[$connection_id]}" "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/api-keys/$key_id" || true
client_answer=$(curl -sS -X POST -H "Authorization: Bearer $key" -H "Content-Type: application/json" \
  -d "{\"modelId\":$chat_id,\"message\":\"smoke software question\"}" "http://127.0.0.1:$PORT/api/v1/chat") || true
grep -q 'Invoice 4711 paid' <<< "$client_answer" || fail "the client software's chat calling the software's tool answered: $client_answer"
# Passed through to MCP clients (roadmap point 41): switched "Direkt an MCP-Clients" and given to a key, the software's
# tool is in the key's tools/list - its entry as the software listed it - and the client calls it itself through the
# AOT jar; its instructions follow ours; its resources are listed and read once switched on.
curl -sS -o /dev/null -X PUT -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d '{"passesThrough":true}' "$connections/pass-through" || true
proxy_created=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"name\":\"Proxy\",\"connectionIds\":[$connection_id]}" "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/mcp-keys") || true
proxy_key=$(sed -E 's/.*"plainKey":"([^"]+)".*/\1/' <<< "$proxy_created")
[[ "$proxy_key" == mcp_* ]] || fail "creating an MCP key with the software answered: $proxy_created"
proxy_tools=$(curl -sS -X POST -H "Authorization: Bearer $proxy_key" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":6,"method":"tools/list"}' "http://127.0.0.1:$PORT/mcp") || true
grep -q '"name":"Software__find_invoice"' <<< "$proxy_tools" && ! grep -q 'Software__cancel_invoice' <<< "$proxy_tools" \
  || fail "the MCP server's tools/list does not pass the software's reading tool through: $proxy_tools"
proxy_call=$(curl -sS -X POST -H "Authorization: Bearer $proxy_key" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"Software__find_invoice","arguments":{"number":"4711"}}}' \
  "http://127.0.0.1:$PORT/mcp") || true
grep -q 'Invoice 4711 paid' <<< "$proxy_call" || fail "the passed-through tool answered: $proxy_call"
# The tool's entry as the software listed it (kept as JSON when the tools were read, read back through the AOT jar).
grep -q '"title":"Find an invoice"' <<< "$proxy_tools" && grep -q '"idempotentHint":true' <<< "$proxy_tools" \
  && grep -q '"com.example/category":"smoke"' <<< "$proxy_tools" \
  || fail "the MCP server's tools/list does not pass the tool's own entry on: $proxy_tools"
# The software's resources: none until switched "Ressourcen an MCP-Clients", then listed and read with the connection
# in front of the address.
proxy_no_resources=$(curl -sS -X POST -H "Authorization: Bearer $proxy_key" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":9,"method":"resources/list"}' "http://127.0.0.1:$PORT/mcp") || true
grep -q '"code":-32601' <<< "$proxy_no_resources" || fail "resources/list without the switch answered: $proxy_no_resources"
curl -sS -o /dev/null -X PUT -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d '{"passesResources":true}' "$connections/resources" || true
proxy_resources=$(curl -sS -X POST -H "Authorization: Bearer $proxy_key" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":10,"method":"resources/list"}' "http://127.0.0.1:$PORT/mcp") || true
grep -q '"uri":"software.invoice://4711/receipt.txt"' <<< "$proxy_resources" \
  || fail "the MCP server's resources/list does not pass the software's resource through: $proxy_resources"
proxy_read=$(curl -sS -X POST -H "Authorization: Bearer $proxy_key" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":11,"method":"resources/read","params":{"uri":"software.invoice://4711/receipt.txt"}}' \
  "http://127.0.0.1:$PORT/mcp") || true
grep -q 'Receipt of invoice 4711' <<< "$proxy_read" || fail "reading the passed-through resource answered: $proxy_read"
proxy_not_found=$(curl -sS -X POST -H "Authorization: Bearer $proxy_key" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":12,"method":"resources/read","params":{"uri":"invoice://4711/receipt.txt"}}' \
  "http://127.0.0.1:$PORT/mcp") || true
grep -q '"code":-32002' <<< "$proxy_not_found" || fail "reading an address without the connection answered: $proxy_not_found"
# ... with HTTP 200: an SDK client takes another status for a broken server and ends its session.
proxy_not_found_status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $proxy_key" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":13,"method":"resources/read","params":{"uri":"invoice://4711/receipt.txt"}}' \
  "http://127.0.0.1:$PORT/mcp") || true
[[ "$proxy_not_found_status" == "200" ]] || fail "a resource not found was sent with HTTP $proxy_not_found_status"
proxy_initialized=$(curl -sS -X POST -H "Authorization: Bearer $proxy_key" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":8,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
  "http://127.0.0.1:$PORT/mcp") || true
grep -q "Software Software (its tools' descriptions start with \[Software\]): Call find_invoice" <<< "$proxy_initialized" \
  || fail "the MCP server's initialize does not pass the software's instructions on: $proxy_initialized"
grep -q "Connected software: Software - " <<< "$proxy_initialized" \
  || fail "the MCP server's initialize does not name the key's software first: $proxy_initialized"
ZIP="$(mktemp).zip"
python3 - "$DOCX" "$ZIP" <<'PYTHON'
import sys, zipfile
with zipfile.ZipFile(sys.argv[2], "w") as archive:
    archive.write(sys.argv[1], "Ordner/Unterordner/smoke.docx")
PYTHON
imported=$(curl -sS -X POST -H "Authorization: Bearer $token" -F "file=@$ZIP;type=application/zip;filename=smoke.zip" \
  "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/documents/archive") || true
rm -f "$ZIP"
grep -q '"outcome":"CREATED"' <<< "$imported" || fail "the ZIP import answered: $imported"

echo "4b. Knowledge areas: only the members of an area's groups and its keys find its documents"
area=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d '{"name":"Vertrieb"}' "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/knowledge-areas") || true
area_id=$(sed -E 's/.*"id":([0-9]+).*/\1/' <<< "$area")
[[ "$area_id" =~ ^[0-9]+$ ]] || fail "creating a knowledge area answered: $area"
# A model that calls no tools: the application searches with the question itself and names the sources it found.
plain=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"provider\":\"OLLAMA\",\"kind\":\"CHAT\",\"modelName\":\"smoke-plain-$tenant_id\",\"displayName\":\"Smoke Plain\",\"inputPrice\":0,\"outputPrice\":0,\"callsTools\":false,\"baseUrl\":\"$ollama\"}" \
  "http://127.0.0.1:$PORT/admin/models") || true
plain_id=$(sed -E 's/.*"id":([0-9]+).*/\1/' <<< "$plain")
[[ "$plain_id" =~ ^[0-9]+$ ]] || fail "creating a chat model without tools answered: $plain"
curl -sS -o /dev/null -X PUT -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"modelIds\":[$embedding_id,$chat_id,$plain_id]}" "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/models" || true
area_created=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"name\":\"smoke-area\",\"areaIds\":[$area_id]}" "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/api-keys") || true
area_key=$(sed -E 's/.*"plainKey":"([^"]+)".*/\1/' <<< "$area_created")
[[ "$area_key" == ai_* ]] || fail "creating an API key with an area answered: $area_created"
pushed=$(curl -sS -X POST -H "Authorization: Bearer $area_key" -F "file=@$DOCX;type=application/octet-stream;filename=vertrieb.docx" \
  -F "area=Vertrieb" "http://127.0.0.1:$PORT/api/v1/documents") || true
grep -q '"area":"Vertrieb"' <<< "$pushed" || fail "the push into the area answered: $pushed"
refused=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $key" \
  -F "file=@$DOCX;type=application/octet-stream;filename=fremd.docx" -F "area=Vertrieb" \
  "http://127.0.0.1:$PORT/api/v1/documents") || true
[ "$refused" = "400" ] || fail "a key without the area pushed into it: $refused"
for _ in $(seq 1 30); do
  documents=$(curl -sS -H "Authorization: Bearer $area_key" "http://127.0.0.1:$PORT/api/v1/documents") || true
  grep -q '"fileName":"vertrieb.docx","[^}]*"status":"\(READY\|FAILED\)"' <<< "$documents" && break
  sleep 1
done
grep -q '"fileName":"vertrieb.docx","[^}]*"status":"READY"' <<< "$documents" || fail "the area's document was not embedded: $documents"
grep -q 'vertrieb.docx' <<< "$(curl -sS -H "Authorization: Bearer $key" "http://127.0.0.1:$PORT/api/v1/documents")" \
  && fail "a key without the area lists its document"
area_password="$(openssl rand -hex 16)"
area_group=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"name\":\"Vertrieb\",\"areaIds\":[$area_id]}" "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/groups") || true
area_group_id=$(sed -E 's/^\{"id":([0-9]+).*/\1/' <<< "$area_group")
[[ "$area_group_id" =~ ^[0-9]+$ ]] || fail "creating the area's user group answered: $area_group"
curl -sS -o /dev/null -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"username\":\"smoke-area-$tenant_id\",\"displayName\":\"Smoke\",\"password\":\"$area_password\",\"groupIds\":[$area_group_id]}" \
  "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/users" || true
area_login=$(curl -sS -X POST -H "Content-Type: application/json" \
  -d "{\"username\":\"smoke-area-$tenant_id\",\"password\":\"$area_password\"}" "http://127.0.0.1:$PORT/login") || true
area_token=$(sed -E 's/.*"access_token":"([^"]+)".*/\1/' <<< "$area_login")
ask_plain() {
  curl -sS -N -X POST -H "Authorization: Bearer $1" -H "Content-Type: application/json" \
    -H "Accept: text/event-stream, application/json" \
    -d "{\"modelId\":$plain_id,\"message\":\"smoke area question\"}" "http://127.0.0.1:$PORT/tenant/chat/stream" || true
}
area_answer=$(ask_plain "$area_token")
grep -q 'vertrieb.docx' <<< "$area_answer" || fail "the area's user did not find its document: $area_answer"
other_answer=$(ask_plain "$user_token")
grep -q 'smoke answer from ollama' <<< "$other_answer" || fail "the chat of a user without the area answered: $other_answer"
grep -q 'vertrieb.docx' <<< "$other_answer" && fail "a user without the area found its document: $other_answer"
deleted=$(curl -sS -X DELETE -H "Authorization: Bearer $token" \
  "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/documents/uploads?area=company") || true
grep -q '"deleted":1}' <<< "$deleted" || fail "deleting the company knowledge's uploads answered: $deleted"
remaining=$(curl -sS -H "Authorization: Bearer $token" "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/documents") || true
grep -q 'Unterordner' <<< "$remaining" && fail "the ZIP's upload is still there: $remaining"
grep -q '"fileName":"smoke.docx"' <<< "$remaining" || fail "the pushed document went with the uploads: $remaining"

echo "   A scheduled task is checked, saved and run - its run fails at a store out of reach and is recorded"
tenant_name=$(sed -E 's/.*"name":"([^"]+)".*/\1/' <<< "$tenant")
curl -sS -o /dev/null -X PUT -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"name\":\"$tenant_name\",\"tasksAllowed\":true}" "http://127.0.0.1:$PORT/admin/tenants/$tenant_id" || true
store=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"name\":\"Logdateien\",\"type\":\"SFTP\",\"host\":\"127.0.0.1\",\"port\":$STORE_PORT,\"username\":\"smoke\",\"password\":\"smoke-secret\",\"basePath\":\"/logs\"}" \
  "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/file-stores") || true
store_id=$(sed -E 's/.*"id":([0-9]+).*/\1/' <<< "$store")
[[ "$store_id" =~ ^[0-9]+$ ]] || fail "creating a file store answered: $store"
curl -sS -o /dev/null -X PUT -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
  -d "{\"fingerprint\":\"SHA256:$(printf 'A%.0s' $(seq 1 43))\"}" \
  "http://127.0.0.1:$PORT/admin/tenants/$tenant_id/file-stores/$store_id/fingerprint" || true
task_body=$(python3 -c 'import json, sys; print(json.dumps({"source": sys.stdin.read()}))' <<'YAML'
version: 1
name: Smoke log check
notify: [it@example.com]
steps:
  - id: logs
    type: read_files
    store: Logdateien
    pattern: "{yyyy-MM-dd}_*.log"
    include: [ERROR]
  - id: check
    type: agent
    model: Smoke Chat
    input: [logs]
    checks:
      - name: Interface
        severity: error
        instruction: Were there timeouts?
  - id: mail
    type: send_mail
    if: check.errors > 0
    input: [check]
YAML
)
tasks="http://127.0.0.1:$PORT/admin/tenants/$tenant_id/tasks"
checked=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" -d "$task_body" "$tasks/validate") || true
grep -q '"valid":true' <<< "$checked" || fail "checking a task answered: $checked"
task=$(curl -sS -X POST -H "Authorization: Bearer $token" -H "Content-Type: application/json" -d "$task_body" "$tasks") || true
task_id=$(sed -E 's/^\{"id":([0-9]+).*/\1/' <<< "$task")
[[ "$task_id" =~ ^[0-9]+$ ]] || fail "saving a task answered: $task"
started=$(curl -sS -X POST -H "Authorization: Bearer $token" "$tasks/$task_id/runs") || true
run_id=$(sed -E 's/.*"runId":([0-9]+).*/\1/' <<< "$started")
[[ "$run_id" =~ ^[0-9]+$ ]] || fail "starting the task answered: $started"
for _ in $(seq 1 60); do
  run=$(curl -sS -H "Authorization: Bearer $token" "$tasks/$task_id/runs/$run_id") || true
  grep -q '"state":"\(QUEUED\|RUNNING\)"' <<< "$run" || break
  sleep 1
done
grep -q '"state":"FAILED"' <<< "$run" && grep -q '"errorCode":"fileStore.unreachable"' <<< "$run" \
  || fail "the task's run did not fail at the store out of reach: $run"
grep -q '"mailFailed":true' <<< "$run" || fail "the run's failure mail did not fail at the mail server out of reach: $run"

echo "5. Without WAM the proxy's user header counts for nothing"
python3 - "$WAM_PORT" "$PORT" <<'PYTHON' &
import http.client, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Like a WAM proxy: whatever user or groups header comes from outside is removed; the person signed in at the proxy
# (here: the test's X-Wam-Person) goes on in X-Remote-User, its IDM groups (X-Wam-Groups) in X-Remote-Groups.
DROPPED = {"host", "connection", "content-length", "transfer-encoding", "x-remote-user", "x-wam-person",
           "x-remote-groups", "x-wam-groups"}

class FakeWam(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def forward(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        headers = {name: value for name, value in self.headers.items() if name.lower() not in DROPPED}
        person = self.headers.get("X-Wam-Person")
        if person:
            headers["X-Remote-User"] = person
        groups = self.headers.get("X-Wam-Groups")
        if groups:
            headers["X-Remote-Groups"] = groups
        app = http.client.HTTPConnection("127.0.0.1", int(sys.argv[2]), timeout=60)
        app.request(self.command, self.path, body=body or None, headers=headers)
        response = app.getresponse()
        data = response.read()
        self.send_response(response.status)
        for name, value in response.getheaders():
            if name.lower() not in {"content-length", "connection", "transfer-encoding"}:
                self.send_header(name, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    do_GET = do_POST = do_PUT = do_DELETE = forward

    def log_message(self, *args):
        pass

ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), FakeWam).serve_forever()
PYTHON
wam_pid=$!
for _ in $(seq 1 20); do
  curl -fsS "http://127.0.0.1:$WAM_PORT/watchdog" > /dev/null 2>&1 && break
  sleep 0.5
done
wam_status=$(through_wam "" GET /login/wam)
[ "$(body_of "$wam_status")" = '{"enabled":false,"groupsChecked":false}' ] || fail "/login/wam answered without WAM: $wam_status"
wam_sign_in=$(through_wam "smoke-user-$tenant_id" POST /login/wam)
[ "$(status_of "$wam_sign_in")" = "404" ] || fail "POST /login/wam without WAM answered: $wam_sign_in"
proxied_login=$(through_wam "smoke-user-$tenant_id" POST /login "" \
  "{\"username\":\"smoke-user-$tenant_id\",\"password\":\"$user_password\"}")
[ "$(status_of "$proxied_login")" = "200" ] || fail "the password login through the proxy answered: $proxied_login"
other_person=$(through_wam "jemand-anders" GET /tenant/account "$user_token")
[ "$(status_of "$other_person")" = "200" ] || fail "without WAM a token failed for another proxy user: $other_person"
curl -sS -o /dev/null -X DELETE -H "Authorization: Bearer $token" "http://127.0.0.1:$PORT/admin/tenants/$tenant_id" || true

echo "6. A jar with the frontend serves it"
if unzip -l "$JAR" public/index.html > /dev/null 2>&1; then
  page=$(curl -sS "http://127.0.0.1:$PORT/") || true
  grep -qi '<app-root' <<< "$page" || fail "the frontend is in the jar but / does not serve it"
else
  echo "   (no frontend in this jar - skipped)"
fi

echo "7. Under a context path everything lives below it - and the chat files' retention set empty keeps them"
kill "$pid"
wait "$pid" 2>/dev/null || true
# The retention variables set but empty, as the README tells an operator who wants to keep the files: they must
# arrive as empty, not as the defaults (8 hours, 14 days) - a jar that deleted here would delete there.
MICRONAUT_SERVER_PORT="$PORT" APP_AI_PLAIN_HTTP_HOSTS="127.0.0.1" MICRONAUT_SERVER_CONTEXT_PATH="/alpdesk-ai" APP_WAM_ENABLED=true \
  APP_WAM_GROUPS_HEADER=X-Remote-Groups APP_CHAT_VIDEO_RETENTION_HOURS= APP_CHAT_FILE_RETENTION_DAYS= \
  APP_CORS_ORIGINS="https://chat.example.com" \
  java --enable-native-access=ALL-UNNAMED -jar "$JAR" > "$LOG" 2>&1 &
pid=$!
for _ in $(seq 1 120); do
  curl -fsS "http://127.0.0.1:$PORT/alpdesk-ai/watchdog" > /dev/null 2>&1 && break
  kill -0 "$pid" 2>/dev/null || fail "the jar exited during startup under a context path"
  sleep 1
done
curl -fsS "http://127.0.0.1:$PORT/alpdesk-ai/watchdog" > /dev/null || fail "/alpdesk-ai/watchdog did not answer"
# A frontend of the tenant's own on another origin (APP_CORS_ORIGINS, read at startup despite AOT) - never with credentials.
# The preflight names its headers as a browser does: Micronaut refuses one without them.
cors_listed=$(curl -sS -o /dev/null -D - -X OPTIONS -H "Origin: https://chat.example.com" \
  -H "Access-Control-Request-Method: POST" -H "Access-Control-Request-Headers: Content-Type, X-Login-Key" \
  "http://127.0.0.1:$PORT/alpdesk-ai/login") || true
grep -qi '^access-control-allow-origin: https://chat.example.com' <<< "$cors_listed" \
  && ! grep -qi '^access-control-allow-credentials' <<< "$cors_listed" \
  || fail "APP_CORS_ORIGINS was not read, or credentials are allowed: $cors_listed"
cors_other=$(curl -s -o /dev/null -w '%{http_code}' -X OPTIONS -H "Origin: https://evil.example.com" \
  -H "Access-Control-Request-Method: POST" -H "Access-Control-Request-Headers: Content-Type, X-Login-Key" \
  "http://127.0.0.1:$PORT/alpdesk-ai/login") || true
[ "$cors_other" = "403" ] || fail "an origin not in APP_CORS_ORIGINS answered $cors_other, not 403"
grep -q "Chat files: kept - no retention set" "$LOG" \
  || fail "with the retention variables set empty the start does not say the chat files are kept"
root_status=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/watchdog") || true
[ "$root_status" = "404" ] || fail "/watchdog answered $root_status under a context path, not 404"
prefixed_login=$(curl -sS -X POST -H "Content-Type: application/json" \
  -d "{\"username\":\"$APP_USERNAME\",\"password\":\"$APP_PASSWORD\"}" "http://127.0.0.1:$PORT/alpdesk-ai/login") || true
grep -q '"access_token"' <<< "$prefixed_login" || fail "the login under the context path answered: $prefixed_login"
swagger_location=$(curl -s -o /dev/null -w '%{redirect_url}' "http://127.0.0.1:$PORT/alpdesk-ai/swagger") || true
grep -q '/alpdesk-ai/swagger-ui/index.html?contextPath=' <<< "$swagger_location" \
  || fail "/alpdesk-ai/swagger led to $swagger_location"
swagger_page=$(curl -sS "http://127.0.0.1:$PORT/alpdesk-ai/swagger-ui/index.html") || true
grep -q 'contextPath + "/swagger-ui/res/' <<< "$swagger_page" \
  || fail "the Swagger page has a context path built in - it must be told it at runtime only"
api_description=$(unzip -Z1 "$JAR" 'META-INF/swagger/*.yml' 2>/dev/null | head -n 1)
[ -n "$api_description" ] || fail "the jar has no API description under META-INF/swagger"
description_status=$(curl -s -o /dev/null -w '%{http_code}' \
  "http://127.0.0.1:$PORT/alpdesk-ai/swagger/$(basename "$api_description")") || true
[ "$description_status" = "200" ] || fail "Swagger's API description answered $description_status under a context path"
# The users' routes are published next to /api/v1 - Micronaut Security's /login included (@OpenAPIInclude).
api_yaml=$(curl -sS "http://127.0.0.1:$PORT/alpdesk-ai/swagger/$(basename "$api_description")") || true
grep -q '^  /login:' <<< "$api_yaml" && grep -q '^  /tenant/chat/stream:' <<< "$api_yaml" && ! grep -q '^  /admin' <<< "$api_yaml" \
  || fail "the API description does not publish the users' routes (or publishes the admin's)"
# The anonymous paths and /mcp's key rule (intercept-url-map) are written without the context path - they must still hold.
prefixed_token=$(sed -E 's/.*"access_token":"([^"]+)".*/\1/' <<< "$prefixed_login")
prefixed_tenant=$(curl -sS -X POST -H "Authorization: Bearer $prefixed_token" -H "Content-Type: application/json" \
  -d "{\"name\":\"Context path $(date +%s)\"}" "http://127.0.0.1:$PORT/alpdesk-ai/admin/tenants") || true
prefixed_tenant_id=$(sed -E 's/.*"id":([0-9]+).*/\1/' <<< "$prefixed_tenant")
[[ "$prefixed_tenant_id" =~ ^[0-9]+$ ]] || fail "creating a tenant under the context path answered: $prefixed_tenant"
prefixed_mcp=$(curl -sS -X POST -H "Authorization: Bearer $prefixed_token" -H "Content-Type: application/json" \
  -d '{"name":"Smoke"}' "http://127.0.0.1:$PORT/alpdesk-ai/admin/tenants/$prefixed_tenant_id/mcp-keys") || true
prefixed_mcp_key=$(sed -E 's/.*"plainKey":"([^"]+)".*/\1/' <<< "$prefixed_mcp")
[[ "$prefixed_mcp_key" == mcp_* ]] || fail "creating the MCP key under the context path answered: $prefixed_mcp"
prefixed_initialized=$(curl -sS -X POST -H "Authorization: Bearer $prefixed_mcp_key" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
  "http://127.0.0.1:$PORT/alpdesk-ai/mcp") || true
grep -q '"protocolVersion":"2025-06-18"' <<< "$prefixed_initialized" \
  || fail "the MCP server under the context path answered: $prefixed_initialized"
prefixed_card=$(curl -sS "http://127.0.0.1:$PORT/alpdesk-ai/.well-known/agent-card.json") || true
grep -q "/alpdesk-ai/a2a\"" <<< "$prefixed_card" || fail "the agent card under the context path answered: $prefixed_card"
curl -sS -o /dev/null -X DELETE -H "Authorization: Bearer $prefixed_token" \
  "http://127.0.0.1:$PORT/alpdesk-ai/admin/tenants/$prefixed_tenant_id" || true
if unzip -l "$JAR" public/index.html > /dev/null 2>&1; then
  shell=$(curl -sS "http://127.0.0.1:$PORT/alpdesk-ai/app/chat") || true
  grep -q '<base href="/alpdesk-ai/">' <<< "$shell" || fail "the app's shell has no base href /alpdesk-ai/"
fi

echo "8. With WAM everything goes through the proxy: requests, approvals, no passwords"
wam_status=$(through_wam "" GET /alpdesk-ai/login/wam)
[ "$(body_of "$wam_status")" = '{"enabled":true,"groupsChecked":true}' ] \
  || fail "APP_WAM_ENABLED or APP_WAM_GROUPS_HEADER was not read - /login/wam answered: $wam_status"
forged=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H "X-Remote-User: $APP_USERNAME" \
  "http://127.0.0.1:$WAM_PORT/alpdesk-ai/login/wam") || true
[ "$forged" = "400" ] || fail "a user header set from outside reached the app through the proxy: $forged"
suffix=$(date +%s)
chef="wam-chef-$suffix"
staff="wam-staff-$suffix"
waiting=$(through_wam "$chef" POST /alpdesk-ai/login/wam)
[ "$(status_of "$waiting")" = "409" ] || fail "someone unknown was not told to wait: $waiting"
admin_login=$(curl -sS -X POST -H "Content-Type: application/json" \
  -d "{\"username\":\"$APP_USERNAME\",\"password\":\"$APP_PASSWORD\"}" "http://127.0.0.1:$WAM_PORT/alpdesk-ai/login") || true
grep -q '"access_token"' <<< "$admin_login" || fail "with WAM the admin's password login answered: $admin_login"
admin_token=$(sed -E 's/.*"access_token":"([^"]+)".*/\1/' <<< "$admin_login")
request_id() {
  python3 -c '
import json, sys
print(next(r["id"] for r in json.loads(sys.argv[1]) if r["username"] == sys.argv[2]))
' "$(body_of "$(through_wam "" GET /alpdesk-ai/admin/access-requests "$admin_token")")" "$1" 2>/dev/null \
    || fail "no access request of $1"
}
wam_tenant=$(body_of "$(through_wam "" POST /alpdesk-ai/admin/tenants "$admin_token" "{\"name\":\"WAM $suffix\"}")")
wam_tenant_id=$(sed -E 's/.*"id":([0-9]+).*/\1/' <<< "$wam_tenant")
[[ "$wam_tenant_id" =~ ^[0-9]+$ ]] || fail "creating the WAM tenant answered: $wam_tenant"
assigned=$(through_wam "" POST "/alpdesk-ai/admin/access-requests/$(request_id "$chef")/assign" "$admin_token" \
  "{\"tenantId\":$wam_tenant_id,\"active\":true,\"admin\":true}")
[ "$(status_of "$assigned")" = "201" ] || fail "assigning the first user answered: $assigned"
chef_sign_in=$(through_wam "$chef" POST /alpdesk-ai/login/wam)
grep -q 'ROLE_TENANT_ADMIN' <<< "$chef_sign_in" || fail "the tenant's first user did not sign in as its admin: $chef_sign_in"
chef_token=$(sed -E 's/.*"access_token":"([^"]+)".*/\1/' <<< "$(body_of "$chef_sign_in")")
through_wam "$staff" POST /alpdesk-ai/login/wam > /dev/null
staff_assigned=$(through_wam "" POST "/alpdesk-ai/admin/access-requests/$(request_id "$staff")/assign" "$admin_token" \
  "{\"tenantId\":$wam_tenant_id}")
staff_id=$(sed -E 's/.*"id":([0-9]+).*/\1/' <<< "$(body_of "$staff_assigned")")
[[ "$staff_id" =~ ^[0-9]+$ ]] || fail "assigning the second user answered: $staff_assigned"
[ "$(status_of "$(through_wam "$staff" POST /alpdesk-ai/login/wam)")" = "409" ] \
  || fail "a user the tenant admin has not approved yet was signed in"
approved=$(through_wam "$chef" PUT "/alpdesk-ai/tenant/admin/users/$staff_id" "$chef_token" \
  "{\"username\":\"$staff\",\"displayName\":\"$staff\",\"active\":true}")
[ "$(status_of "$approved")" = "200" ] || fail "the tenant admin's approval answered: $approved"
staff_sign_in=$(through_wam "$staff" POST /alpdesk-ai/login/wam)
grep -q 'ROLE_TENANT_USER' <<< "$staff_sign_in" || fail "the approved user did not sign in: $staff_sign_in"
switched=$(through_wam "$staff" GET /alpdesk-ai/tenant/account "$chef_token")
[ "$(status_of "$switched")" = "403" ] || fail "another person at the proxy went on in the first one's session: $switched"
no_password=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Content-Type: application/json" \
  -d "{\"username\":\"$chef\",\"password\":\"irgendein-passwort\"}" "http://127.0.0.1:$WAM_PORT/alpdesk-ai/login") || true
[ "$no_password" = "401" ] || fail "with WAM a tenant user's password login answered $no_password, not 401"
# One person, users in two tenants (roadmap point 51): the choice, the switch without a password, the header checked.
second_tenant=$(body_of "$(through_wam "" POST /alpdesk-ai/admin/tenants "$admin_token" "{\"name\":\"WAM 2 $suffix\"}")")
second_tenant_id=$(sed -E 's/.*"id":([0-9]+).*/\1/' <<< "$second_tenant")
[[ "$second_tenant_id" =~ ^[0-9]+$ ]] || fail "creating the second WAM tenant answered: $second_tenant"
added=$(through_wam "" POST "/alpdesk-ai/admin/tenants/$second_tenant_id/users/from-tenant" "$admin_token" \
  "{\"userId\":$staff_id}")
[ "$(status_of "$added")" = "201" ] || fail "adding the user to a second tenant answered: $added"
choice=$(through_wam "$staff" POST /alpdesk-ai/login/wam)
grep -q '"tenants":\[' <<< "$choice" && ! grep -q 'access_token' <<< "$choice" \
  || fail "a person with users in two tenants was not given the choice: $choice"
chosen=$(through_wam "$staff" POST /alpdesk-ai/login/wam "" "{\"tenantId\":$second_tenant_id}")
grep -q 'access_token' <<< "$chosen" || fail "signing in at the chosen tenant answered: $chosen"
chosen_token=$(sed -E 's/.*"access_token":"([^"]+)".*/\1/' <<< "$(body_of "$chosen")")
grep -q "\"tenantId\":$second_tenant_id" <<< "$(through_wam "$staff" GET /alpdesk-ai/tenant/account "$chosen_token")" \
  || fail "the token of the chosen tenant is not that tenant's"
switched_back=$(through_wam "$staff" POST /alpdesk-ai/tenant/account/switch "$chosen_token" "{\"tenantId\":$wam_tenant_id}")
grep -q 'access_token' <<< "$switched_back" || fail "the switch answered: $switched_back"
switched_token=$(sed -E 's/.*"access_token":"([^"]+)".*/\1/' <<< "$(body_of "$switched_back")")
grep -q "\"tenantId\":$wam_tenant_id" <<< "$(through_wam "$staff" GET /alpdesk-ai/tenant/account "$switched_token")" \
  || fail "the switched token is not the first tenant's"
[ "$(status_of "$(through_wam "$chef" GET /alpdesk-ai/tenant/account "$switched_token")")" = "403" ] \
  || fail "after the switch another person at the proxy went on in the session"
through_wam "" DELETE "/alpdesk-ai/admin/tenants/$wam_tenant_id" "$admin_token" > /dev/null
through_wam "" DELETE "/alpdesk-ai/admin/tenants/$second_tenant_id" "$admin_token" > /dev/null

echo "9. The general chat asks a topic tenant through its MCP server - accounts from the IDM, the choice, the switch"
json_id() { sed -E 's/.*"id":([0-9]+).*/\1/' <<< "$1"; }
topic_tenant_id=$(json_id "$(body_of "$(through_wam "" POST /alpdesk-ai/admin/tenants "$admin_token" "{\"name\":\"Thema $suffix\"}")")")
general_tenant_id=$(json_id "$(body_of "$(through_wam "" POST /alpdesk-ai/admin/tenants "$admin_token" "{\"name\":\"Allgemein $suffix\"}")")")
[[ "$topic_tenant_id" =~ ^[0-9]+$ && "$general_tenant_id" =~ ^[0-9]+$ ]] || fail "creating the topic and the general chat failed"
for t in "$topic_tenant_id" "$general_tenant_id"; do
  through_wam "" PUT "/alpdesk-ai/admin/tenants/$t/models" "$admin_token" "{\"modelIds\":[$embedding_id,$chat_id]}" > /dev/null
done
through_wam "" PUT "/alpdesk-ai/admin/tenants/$topic_tenant_id/embedding-model" "$admin_token" "{\"modelId\":$embedding_id}" > /dev/null
topic_key=$(sed -E 's/.*"plainKey":"([^"]+)".*/\1/' <<< "$(body_of "$(through_wam "" POST \
  "/alpdesk-ai/admin/tenants/$topic_tenant_id/api-keys" "$admin_token" '{"name":"Upload"}')")")
topic_upload=$(curl -sS -X POST -H "Authorization: Bearer $topic_key" -F "file=@$DOCX;type=application/octet-stream;filename=smoke.docx" \
  "http://127.0.0.1:$PORT/alpdesk-ai/api/v1/documents") || true
grep -q 'wordprocessingml.document' <<< "$topic_upload" || fail "the topic's DOCX upload answered: $topic_upload"
for _ in $(seq 1 30); do
  topic_documents=$(curl -sS -H "Authorization: Bearer $topic_key" "http://127.0.0.1:$PORT/alpdesk-ai/api/v1/documents") || true
  grep -q '"status":"\(READY\|FAILED\)"' <<< "$topic_documents" && break
  sleep 1
done
grep -q '"status":"READY"' <<< "$topic_documents" || fail "the topic's DOCX was not embedded: $topic_documents"
topic_mcp=$(body_of "$(through_wam "" POST "/alpdesk-ai/admin/tenants/$topic_tenant_id/mcp-keys" "$admin_token" \
  '{"name":"Allgemeiner Chat","description":"Smoke documents of the topic"}')")
topic_mcp_key=$(sed -E 's/.*"plainKey":"([^"]+)".*/\1/' <<< "$topic_mcp")
[[ "$topic_mcp_key" == mcp_* ]] || fail "creating the topic's MCP key answered: $topic_mcp"
grep -q '"description":"Smoke documents of the topic"' <<< "$topic_mcp" || fail "the key's description was not kept: $topic_mcp"
# The installation's own /mcp at its internal address - the WAM proxy would ask the MCP call to sign in.
connection_request=$(python3 -c 'import json, sys; print(json.dumps({"name": "Thema", "baseUrl": sys.argv[1], "apiKey": sys.argv[2]}))' \
  "http://127.0.0.1:$PORT/alpdesk-ai/mcp" "$topic_mcp_key")
connection=$(body_of "$(through_wam "" POST "/alpdesk-ai/admin/tenants/$general_tenant_id/mcp-connections" "$admin_token" \
  "$connection_request")")
read -r connection_id search_tool_id <<< "$(python3 -c '
import json, sys
c = json.loads(sys.argv[1])
print(c["id"], next(t["id"] for t in c["tools"] if t["name"] == "search_knowledge"))
' "$connection" 2>/dev/null)" || fail "connecting the topic's MCP server answered: $connection"
grep -q 'This access knows: Smoke documents of the topic' <<< "$connection" \
  || fail "the topic's description did not reach the connection's instructions: $connection"
through_wam "" PUT "/alpdesk-ai/admin/tenants/$general_tenant_id/mcp-connections/$connection_id/tools/$search_tool_id" \
  "$admin_token" '{"enabled":true}' > /dev/null
topic_group="AI-Thema-$suffix"
general_group="AI-Allgemein-$suffix"
through_wam "" POST "/alpdesk-ai/admin/tenants/$topic_tenant_id/groups" "$admin_token" \
  "{\"name\":\"Thema\",\"wamGroup\":\"$topic_group\"}" > /dev/null
general_created=$(through_wam "" POST "/alpdesk-ai/admin/tenants/$general_tenant_id/groups" "$admin_token" \
  "{\"name\":\"Allgemein\",\"wamGroup\":\"$general_group\",\"levels\":[{\"connectionId\":$connection_id,\"level\":\"READ\"}]}")
[ "$(status_of "$general_created")" = "201" ] || fail "creating the general chat's group answered: $general_created"
person="idm-person-$suffix"
idm_choice=$(through_wam "$person" POST /alpdesk-ai/login/wam "" "" "$topic_group,$general_group")
grep -q "\"id\":$topic_tenant_id" <<< "$idm_choice" && grep -q "\"id\":$general_tenant_id" <<< "$idm_choice" \
  || fail "the IDM's groups did not give the person a user in both tenants: $idm_choice"
general_sign_in=$(through_wam "$person" POST /alpdesk-ai/login/wam "" "{\"tenantId\":$general_tenant_id}" "$topic_group,$general_group")
general_token=$(sed -E 's/.*"access_token":"([^"]+)".*/\1/' <<< "$(body_of "$general_sign_in")")
[ -n "$general_token" ] || fail "signing in at the general chat answered: $general_sign_in"
ask_general() {
  curl -sS -N -X POST -H "X-Wam-Person: $person" -H "X-Wam-Groups: $topic_group,$general_group" \
    -H "Authorization: Bearer $general_token" -H "Content-Type: application/json" \
    -H "Accept: text/event-stream, application/json" -d "$1" "http://127.0.0.1:$WAM_PORT/alpdesk-ai/tenant/chat/stream" || true
}
general_answer=$(ask_general "{\"modelId\":$chat_id,\"message\":\"What does the topic know?\"}")
grep -q '^event: done' <<< "$general_answer" || fail "the general chat's stream did not end with done: $general_answer"
grep -q 'smoke answer from ollama - \[Thema\] --- From' <<< "$general_answer" && grep -q 'alpdesk smoke test document' <<< "$general_answer" \
  || fail "the general chat did not get the topic's passage with [Thema] in front: $general_answer"
left_out=$(ask_general "{\"modelId\":$chat_id,\"message\":\"Without the topic\",\"excludedConnectionIds\":[$connection_id]}")
grep -q '^event: done' <<< "$left_out" && ! grep -q '\[Thema\]' <<< "$left_out" \
  || fail "the topic left out of the question was still asked: $left_out"
to_topic=$(through_wam "$person" POST /alpdesk-ai/tenant/account/switch "$general_token" "{\"tenantId\":$topic_tenant_id}" \
  "$topic_group,$general_group")
topic_token=$(sed -E 's/.*"access_token":"([^"]+)".*/\1/' <<< "$(body_of "$to_topic")")
grep -q "\"tenantId\":$topic_tenant_id" <<< "$(through_wam "$person" GET /alpdesk-ai/tenant/account "$topic_token" "" "$topic_group")" \
  || fail "the switch to the topic answered: $to_topic"
forged_groups=$(curl -sS -X POST -H "X-Remote-User: forged-$suffix" -H "X-Remote-Groups: $general_group" \
  -o /dev/null -w '%{http_code}' "http://127.0.0.1:$WAM_PORT/alpdesk-ai/login/wam") || true
[ "$forged_groups" = "400" ] || fail "a forged user and groups header reached the app through the proxy: $forged_groups"
through_wam "" DELETE "/alpdesk-ai/admin/tenants/$topic_tenant_id" "$admin_token" > /dev/null
through_wam "" DELETE "/alpdesk-ai/admin/tenants/$general_tenant_id" "$admin_token" > /dev/null

kill "$pid" "$ollama_pid" "$wam_pid"
wait "$pid" "$ollama_pid" "$wam_pid" 2>/dev/null || true
pid=""
ollama_pid=""
wam_pid=""
echo "Smoke test passed"
