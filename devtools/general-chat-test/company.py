"""A company's own MCP server behind Zirmo's proxy, end to end (README.md "A company's MCP server through the proxy").

Sets up a tenant "Entwicklung (Proxy-Test)" of its own - the eight topics stay as they are - with the connection
"Entwicklung" to the stack's container company-software (devtools/mcp-test-server/company.py: ten tools with German
descriptions, resources, prompts), every tool and prompt turned on, "Direkt an MCP-Clients" and "Ressourcen an
MCP-Clients" on, and three MCP keys: one that reads, one that may change in the software, one without it. Then it
runs devtools/mcp-test-server/company_check.py with the official MCP SDK's client inside the stack's network: the
server asked directly and through Zirmo, both ends compared.

    python3 company.py          # set up (or take what is there) and check
    python3 company.py keys     # the same, and print the reading key with the command for Claude Code
    python3 company.py ask      # the same, then a real model asks through ask_assistant (after `setup.py models`):
                                # it must find the right tools by their descriptions - and never the changing one

Runs again and again: the keys are made anew each time (a key's secret is shown once).
"""
import json
import pathlib
import subprocess
import sys
import time

import stack
from setup import call, connection_named

TENANT = "Entwicklung (Proxy-Test)"
CONNECTION = "Entwicklung"
SOFTWARE_URL = "http://company-software:8000/mcp"
# The app as the stack's containers reach it - the check runs among them, past Caddy (machines pass it with their key).
APP_URL = "http://alpdesk-ai:19100/mcp"
DESCRIPTION = "Softwareentwicklung der Musterfirma: Richtlinien, Repositories, Quellcode, Builds und Reviews."
NETWORK = "alpdesk-ai-general-chat-test_default"
IMAGE = "localhost/alpdesk-mcp-test:general-chat-test"
CHECK = pathlib.Path(__file__).parent.parent / "mcp-test-server" / "company_check.py"
KEYS = {"reading": "Claude Code (liest)", "changing": "Claude Code (darf ändern)", "without": "Ohne Software"}


def tenant_id(admin):
    found = next((tenant for tenant in call(admin, "GET", "/admin/tenants") if tenant["name"] == TENANT), None)
    return (found or call(admin, "POST", "/admin/tenants", {"name": TENANT, "globalKeysAllowed": True}))["id"]


def connection(admin, tenant):
    base = f"/admin/tenants/{tenant}/mcp-connections"
    software = connection_named(admin, tenant, CONNECTION)
    # Read again when it is there: company.py may have changed since.
    software = call(admin, "POST", f"{base}/{software['id']}/reload") if software \
        else call(admin, "POST", base, {"name": CONNECTION, "baseUrl": SOFTWARE_URL})
    if software.get("toolsError") or software.get("promptsError"):
        raise SystemExit(f"{CONNECTION} could not be read at {SOFTWARE_URL}: "
                         f"{software.get('toolsError') or software.get('promptsError')}")
    for tool in software["tools"]:
        if not tool.get("enabled"):
            call(admin, "PUT", f"{base}/{software['id']}/tools/{tool['id']}", {"enabled": True})
    for prompt in software.get("prompts") or []:
        if not prompt.get("enabled"):
            call(admin, "PUT", f"{base}/{software['id']}/prompts/{prompt['id']}", {"enabled": True})
    call(admin, "PUT", f"{base}/{software['id']}/pass-through", {"passesThrough": True})
    call(admin, "PUT", f"{base}/{software['id']}/resources", {"passesResources": True})
    return connection_named(admin, tenant, CONNECTION)


def keys(admin, tenant, software):
    base = f"/admin/tenants/{tenant}/mcp-keys"
    for key in call(admin, "GET", base):
        if key["name"] in KEYS.values():
            call(admin, "DELETE", f"{base}/{key['id']}")
    made = {}
    for role, name in KEYS.items():
        body = {"name": name, "description": DESCRIPTION}
        if role != "without":
            body["connectionIds"] = [software["id"]]
        if role == "changing":
            body["changingConnectionIds"] = [software["id"]]
        made[role] = call(admin, "POST", base, body)["plainKey"]
    return made


def show(software):
    print(f"{CONNECTION}: {len(software['tools'])} tools, {len(software.get('prompts') or [])} prompts read by Zirmo")
    for tool in software["tools"]:
        print(f"  {tool['name']:<30} {'ändert' if tool.get('changes') else 'liest':<7} "
              f"{len(tool.get('description') or ''):>5} characters of description kept for the chat")
    if software.get("instructions"):
        print(f"  instructions kept: {len(software['instructions'])} characters")


# What the tenant's model is asked through ask_assistant: the tools it must call at the software (company.py's log
# names every call), a text its answer must hold.
QUESTIONS = [
    ("Wie berechnen wir im Repository shop-backend den Bruttopreis einer Position? Nennen Sie Datei und Klasse.",
     ["search_code", "get_file"], ["PreisService"]),
    ("Wie viele Freigaben braucht ein Merge Request nach unserer Richtlinie, und welche Nummer hat die Richtlinie?",
     ["search_guidelines"], ["RL-012"]),
    # The log has 129,000 characters and its cause in the last line: the tenant's own model gets a tool's text up to
    # 100,000 characters (an MCP client through the proxy gets it whole) - it must say so, not guess a cause.
    ("Warum ist der Build 4711 fehlgeschlagen?", ["get_build_log"],
     ["118.99", "118,99", "PreisServiceTest", "abgeschnitten", "gekürzt", "unvollständig"]),
    ("Welche Repositories hat das Team logistik?", ["list_repositories"], ["lager-app"]),
    ("Legen Sie bitte ein Review-Ticket für die Rundung im PreisService des shop-backend an.", [], []),
]
SOFTWARE_CONTAINER = "general-chat-test-company-software"


def software_calls():
    """The tools called at the software so far - its own log lines start with the time, the rest is the SDK's noise."""
    log = subprocess.run(["podman", "logs", SOFTWARE_CONTAINER], capture_output=True, text=True)
    return [line.split()[1] for line in (log.stdout + log.stderr).splitlines()
            if len(line.split()) > 1 and line[:2].isdigit() and line[2:3] == ":"]


def ask(admin, tenant, key):
    """The tenant's model answers through ask_assistant with the software's reading tools - found by their descriptions."""
    state = json.loads(stack.STATE_FILE.read_text())
    if not state.get("chatModelId"):
        raise SystemExit("no chat model in the stack - run setup.py models first")
    models = call(admin, "GET", f"/admin/tenants/{tenant}/models")["modelIds"]
    call(admin, "PUT", f"/admin/tenants/{tenant}/models", {"modelIds": list(dict.fromkeys(models + [state["chatModelId"]]))})
    call(admin, "PUT", f"/admin/tenants/{tenant}/assistant",
         {"instructions": "", "answerSource": "DOCUMENTS_FIRST", "creativity": "LOW", "reasoning": "LOW",
          "searchResults": 5, "defaultModelId": state["chatModelId"]})
    failed = 0
    for question, tools, texts in QUESTIONS:
        before = len(software_calls())
        started = time.monotonic()
        answer, error = stack.tool(key, "ask_assistant", {"question": question})
        called = software_calls()[before:]
        ok = not error and all(tool in called for tool in tools) and (not texts or any(text in answer for text in texts)) \
            and "create_review_ticket" not in called
        failed += not ok
        print(f"\n{'ok  ' if ok else 'FAIL'} {question}\n     called: {', '.join(called) or '-'} "
              f"({time.monotonic() - started:.0f} s)\n     " + answer.strip().replace("\n", "\n     ")[:900])
    return failed


def main():
    admin = stack.admin()
    tenant = tenant_id(admin)
    software = connection(admin, tenant)
    show(software)
    made = keys(admin, tenant, software)
    result = subprocess.run(
        ["podman", "run", "--rm", "--network", NETWORK, "-v", f"{CHECK}:/app/company_check.py:ro,Z", IMAGE,
         "python", "-I", "/app/company_check.py", APP_URL, SOFTWARE_URL, made["reading"], made["changing"],
         made["without"], CONNECTION, DESCRIPTION])
    if len(sys.argv) > 1 and sys.argv[1] == "keys":
        address = f"https://{stack.APP_HOST}:{stack.PORT}/mcp"
        print(f"\nClaude Code (the stack's certificate is Caddy's own - NODE_EXTRA_CA_CERTS, README.md):\n"
              f"  claude mcp add --transport http entwicklung {address} --header \"Authorization: Bearer {made['reading']}\"")
    if len(sys.argv) > 1 and sys.argv[1] == "ask":
        sys.exit(result.returncode or ask(admin, tenant, made["reading"]))
    sys.exit(result.returncode)


if __name__ == "__main__":
    main()
