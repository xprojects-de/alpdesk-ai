#!/usr/bin/env python3
"""Sets the test stack up (README.md) - as the admin, through Caddy and Authelia.

  python3 setup.py base     (in the mode of mode.sh - plain: the people as users by hand, with the tenants' login pages)
                            eight topic tenants and the general chat, the topics' MCP keys with their descriptions,
                            the general chat's connections to the topics at the app's internal address - five to
                            their /mcp (search, ask_assistant or both), three to their /a2a -, the user groups with
                            their WAM groups - no model, no provider key
  python3 setup.py knowledge the stack's own embedding model (bge-m3 in the container embeddings - no provider key) for
                            every tenant, the topics' documents pushed and embedded (again and again: they replace
                            themselves), the client software's keys check.py uses - no chat model
  python3 setup.py models   with PROVIDER and CHAT_MODEL from the environment: the chat model in the catalog, its key as
                            the admin's global key (see provider_key - or entered by hand before), every tenant allowed
                            it, the general chat's default model; with EMBEDDING_MODEL, EMBEDDING_DIMENSION (and
                            EMBEDDING_PROVIDER when it differs) also that embedding model instead of bge-m3 - then
                            `knowledge` is done with it

  python3 setup.py full     two topics with everything the assistant offers - Wartung asked over A2A, Qualitaet over
                            MCP: each its own sandbox, connected software (the MCP test server: reading tools, files,
                            a changing tool), the web search (the Tavily fake), a chat template; its MCP key gives the
                            software and the web search, Qualitaet's passes the software's reading tools through to
                            the general chat - no model needed (an agent of `a2a` stops answering the chat: it stays a
                            tool)

                            - and Thema Wartung a connection "Ticketsystem" to the OAuth fake (devtools/oauth-fake): a
                            hosted software that signs in with OAuth, left at "Anmeldung nötig" for a person (or
                            check.py) to sign in; `full` runs again and again
  python3 setup.py oauth    the Ticketsystem connected anew - after the OAuth fake's container restarted (mode.sh, a
                            rebuild): it keeps its registered clients in memory only

  python3 setup.py a2a      the general chat answered through an A2A agent: the admin's A2A switch, a connection
                            "Auftragsassistent" with "Beantwortet den Chat" to AGENT_URL (default: the demo of
                            devtools/a2a-test-agent on this machine, TEST_AGENT_DEMO=1 start.sh) - no model needed;
                            run again after `full`, the agent answers again
  python3 setup.py agent-off the agent of `a2a` removed from the general chat - when its demo stops (check.py expects
                            every connection to be reached)

  python3 setup.py stores   Thema Wartung's file store (the container sftp, its fingerprint confirmed), a knowledge
                            source on its folder "wissen" (synced by hand), the tenant's switch for tasks - and, once a
                            chat model is there (`models`), the task "Logprüfung Presse" (read_files, agent, send_mail)
                            assigned to the client software's key and the MCP key; run it again after `models`

  python3 setup.py walk     what the browser walk (walk/walk.md) needs and a person does not type: the other
                            providers' keys from KEYS_FILE as the admin's global keys, the chat model marked as the
                            one that reads PDFs, images and scans, an image model (IMAGE_PROVIDER / IMAGE_MODEL,
                            default Gemini) and - only when TRANSCRIBE_PROVIDER and TRANSCRIBE_MODEL name one - a model
                            that writes the transcripts of audio; after `models`. What has no key is left out and
                            said: the walk reports those steps as not run

The ids go to state.json next to this file (state-plain.json in the plain mode; not committed). A provider key is read from the environment or a keys
file of your own (KEYS_FILE) and goes into the stack only - never into a file here, never printed.
"""
import json
import os
import pathlib
import sys

import stack

HERE = pathlib.Path(__file__).parent
STATE = stack.STATE_FILE
APP_MCP = "http://alpdesk-ai:19100/mcp"
APP_A2A = "http://alpdesk-ai:19100/a2a"
# The software of the topics in full: the MCP test server, a container of docker-compose.yml.
SOFTWARE_URL = "http://mcp-software:8000/mcp"
SOFTWARE_TOOLS = ("list_invoices", "pdf_report", "cancel_order")
# A hosted software that signs in with OAuth: the OAuth fake's MCP server, a container of docker-compose.yml.
OAUTH_SOFTWARE_URL = "http://oauth-fake:9100/mcp"
OAUTH_SOFTWARE = "Ticketsystem"
OAUTH_TOPIC = "Wartung"
# The stack's embedding model: the container embeddings (docker-compose.yml) - Ollama with bge-m3 baked in.
EMBEDDINGS_URL = "http://embeddings:11434"
EMBEDDINGS_MODEL = "bge-m3"
EMBEDDINGS_DIMENSION = 1024
# Thema Wartung's file store: the SFTP server of the file store specs (container sftp), its user "check".
STORE = {"name": "Ablage Wartung", "type": "SFTP", "host": "sftp", "port": 22, "username": "check",
         "password": "check-secret", "basePath": stack.STORE_PATH}
STORE_TOPIC = "Wartung"
# A file of the knowledge source (folder wissen) - check.py changes, removes and restores it; its marker is invented.
SOURCE_FILE = ("wissen/schmierplan-band-f3.md",
               "# Schmierplan Förderband F3\n\nDie Lager des Förderbands F3 werden alle 120 Betriebsstunden mit Fett "
               "LUB-77 geschmiert. Prüfmarke SCHMIER-5150.\n")
TASK_NAME = "Logprüfung Presse"
# Any key - the Tavily fake (container tavily-fake) refuses only an empty one.
TAVILY_KEY = "tvly-general-chat-test-fake"
# The A2A agent as the app's container reaches this machine (the test agent listens on 127.0.0.1:19197 there).
AGENT_URL = os.environ.get("AGENT_URL", "http://host.containers.internal:19197/a2a")
AGENT = "Auftragsassistent"

SEARCH = ["search_knowledge", "read_document"]
# The topics: tenant name, connection name in the general chat, documents folder, the key's description, its WAM group,
# how the general chat asks it - "mcp" with these tools of the topic's MCP server, or "a2a" its assistant as an A2A
# agent - and, for `setup.py full`, what the topic gets on top (its sandbox, software, template).
TOPICS = [
    {"tenant": "Thema Wartung", "connection": "Wartung", "folder": "wartung", "wam": "AI-Thema-Wartung", "way": "a2a",
     "description": "Maintenance of the machines: maintenance plans (press P200), faults of the conveyor belts and how "
                    "to clear them.",
     "full": {"sandbox": "http://sandbox-wartung:8080", "token": "general-chat-test-sandbox-token-wartung-0001",
              "software": "Auftragsverwaltung", "template": "Störungsbericht",
              "instructions": "Write a short fault report: machine, symptom, cause found in the documents, the steps "
                              "to clear it, and what to check afterwards."}},
    {"tenant": "Thema Qualität", "connection": "Qualitaet", "folder": "qualitaet", "wam": "AI-Thema-Qualitaet",
     "way": "mcp", "tools": SEARCH + ["ask_assistant"],
     "description": "Quality: inspection instructions and tolerances for sheet metal parts, handling of customer "
                    "complaints (8D).",
     "full": {"sandbox": "http://sandbox-qualitaet:8080", "token": "general-chat-test-sandbox-token-qualitaet-01",
              "software": "Pruefsoftware", "template": "8D-Bericht",
              "instructions": "Write the complaint as an 8D report: team, problem, containment, root cause, "
                              "corrective action, verification, prevention, closure."}},
    {"tenant": "Thema Einkauf", "connection": "Einkauf", "folder": "einkauf", "wam": "AI-Thema-Einkauf", "way": "mcp",
     "tools": SEARCH,
     "description": "Purchasing: who approves orders up to which amount, offers needed, suppliers and delivery times, "
                    "framework contracts."},
    {"tenant": "Thema Arbeitssicherheit", "connection": "Arbeitssicherheit", "folder": "arbeitssicherheit",
     "wam": "AI-Thema-Arbeitssicherheit", "way": "mcp", "tools": SEARCH,
     "description": "Occupational safety: safety briefings, protective equipment per hall, first aiders, hazardous "
                    "substances and their storage."},
    {"tenant": "Thema Personal", "connection": "Personal", "folder": "personal", "wam": "AI-Thema-Personal",
     "way": "mcp", "tools": ["ask_assistant"],
     "description": "Human resources: holidays and how to request them, flexitime and its limits."},
    {"tenant": "Thema Logistik", "connection": "Logistik", "folder": "logistik", "wam": "AI-Thema-Logistik",
     "way": "a2a",
     "description": "Logistics: shipping cut-off times, carriers for heavy pallets, dangerous goods days, the warehouse "
                    "rules and the stocktaking."},
    {"tenant": "Thema IT-Service", "connection": "IT-Service", "folder": "it-service", "wam": "AI-Thema-IT-Service",
     "way": "a2a",
     "description": "IT service: new accounts and unlocking them, VPN, printers and scanners and their faults."},
    {"tenant": "Thema Vertrieb", "connection": "Vertrieb", "folder": "vertrieb", "wam": "AI-Thema-Vertrieb",
     "way": "mcp", "tools": SEARCH,
     "description": "Sales: discounts and who may grant them, payment terms, offers and their validity."},
]
GENERAL = {"tenant": "Allgemeiner Chat", "folder": "allgemein", "wam": "AI-Allgemein"}
# The plain mode's people (no IDM): Authelia's users kept by hand - their tenants ("general": the general chat), as
# their IDM groups give them in the WAM mode; clara a user of the general chat in no group.
PEOPLE = {
    "anna": ("Anna Muster", ["Wartung"]),
    "bernd": ("Bernd Beispiel", ["Einkauf", "general"]),
    "clara": ("Clara Probe", ["general"]),
    "david": ("David Test", ["general", "Qualitaet"]),
    "emil": ("Emil Neu", ["general", "Wartung"]),
}
# A document in an area the topic's key does not release - the measurement checks it never shows.
RESTRICTED = {"folder": "wartung", "file": "intern-personalplanung.md", "area": "Leitung Instandhaltung"}


def call(admin, method, path, body=None, expect=(200, 201, 204)):
    status, _, answer = admin.app(method, path, body)
    if status not in expect:
        raise SystemExit(f"{method} {path} answered {status}: {answer}")
    return answer


def base():
    admin = stack.admin()
    state = {"topics": [], "general": {}}
    for topic in TOPICS:
        tenant = call(admin, "POST", "/admin/tenants", {"name": topic["tenant"], "globalKeysAllowed": True})
        area = call(admin, "POST", f"/admin/tenants/{tenant['id']}/knowledge-areas", {"name": RESTRICTED["area"]}) \
            if topic["folder"] == RESTRICTED["folder"] else None
        key = call(admin, "POST", f"/admin/tenants/{tenant['id']}/mcp-keys",
                   {"name": "Allgemeiner Chat", "description": topic["description"], "a2a": topic["way"] == "a2a"})
        group = call(admin, "POST", f"/admin/tenants/{tenant['id']}/groups",
                     {"name": "Mitarbeitende", "wamGroup": topic["wam"] if stack.WAM else None})
        kept = {name: value for name, value in topic.items() if name != "full"}
        state["topics"].append(kept | {"tenantId": tenant["id"], "mcpKeyId": key["key"]["id"], "groupId": group["id"],
                                       "mcpKey": key["plainKey"], "areaId": area["id"] if area else None})
    general = call(admin, "POST", "/admin/tenants",
                   {"name": GENERAL["tenant"], "globalKeysAllowed": True, "a2aAllowed": True})
    general_id = general["id"]
    # The general chat's first tenant admin, set by hand - the IDM creates plain users only.
    call(admin, "POST", f"/admin/tenants/{general_id}/users",
         {"username": stack.ADMIN_PERSON, "displayName": "Betrieb", "active": True, "admin": True}
         | ({} if stack.WAM else {"password": stack.PERSON_PASSWORD}))
    connections = {}
    for topic in state["topics"]:
        connections[topic["connection"]] = ask_topic(admin, general_id, topic)["id"]
    group = call(admin, "POST", f"/admin/tenants/{general_id}/groups",
                 {"name": "Allgemeiner Chat", "wamGroup": GENERAL["wam"] if stack.WAM else None,
                  "levels": [{"connectionId": connection_id, "level": "READ"} for connection_id in connections.values()]})
    state["general"] = {**GENERAL, "tenantId": general_id, "connections": connections, "groupId": group["id"]}
    if not stack.WAM:
        people_by_hand(admin, state)
    STATE.write_text(json.dumps(state, indent=2, ensure_ascii=False))
    ways = {way: sum(1 for topic in TOPICS if topic["way"] == way) for way in ("mcp", "a2a")}
    print(f"base set up - {len(state['topics'])} topics ({ways['mcp']} over MCP, {ways['a2a']} over A2A) and the "
          f"general chat (tenant {general_id}); {STATE.name} written")


def people_by_hand(admin, state):
    """
    The plain mode: the people of Authelia's file as users kept by hand - one per tenant with the password, in the group
    their IDM group would have given -, clara in the general chat in no group (she must not get in), and every tenant's
    own login page (its key: a name in several tenants signs in there).
    """
    groups = {topic["connection"]: (topic["tenantId"], topic["groupId"]) for topic in state["topics"]}
    groups["general"] = (state["general"]["tenantId"], state["general"]["groupId"])
    for username, (name, places) in PEOPLE.items():
        for place in places:
            tenant_id, group_id = groups[place]
            call(admin, "POST", f"/admin/tenants/{tenant_id}/users",
                 {"username": username, "displayName": name, "password": stack.PERSON_PASSWORD,
                  "groupIds": [] if username == "clara" else [group_id]})
    state["loginKeys"] = {}
    for tenant_id, _ in groups.values():
        branding = call(admin, "POST", f"/admin/tenants/{tenant_id}/branding/login-key")
        state["loginKeys"][str(tenant_id)] = branding["loginKey"]


def ask_topic(admin, general_id, topic):
    """The general chat's connection to the topic: its MCP server with the topic's tools, or its A2A agent."""
    if topic["way"] == "a2a":
        body = {"name": topic["connection"], "baseUrl": APP_A2A, "apiKey": topic["mcpKey"], "protocol": "A2A"}
    else:
        body = {"name": topic["connection"], "baseUrl": APP_MCP, "apiKey": topic["mcpKey"]}
    connection = call(admin, "POST", f"/admin/tenants/{general_id}/mcp-connections", body)
    if connection.get("toolsError"):
        raise SystemExit(f"the connection {topic['connection']} could not read the topic: {connection['toolsError']}")
    enable_tools(admin, general_id, connection, offered(topic, connection))
    return connection


def offered(topic, connection):
    """What the general chat may use of the topic: an agent's every skill; of an MCP server its tools - and, in full,
    the software it passes through (named <software>__<tool>)."""
    names = [tool["name"] for tool in connection["tools"]]
    if topic["way"] == "a2a":
        return names
    return [name for name in names if name in topic["tools"] or "__" in name]


def a2a():
    """A tenant whose every question goes to a workflow engine behind A2A: the general chat answered by the agent."""
    admin = stack.admin()
    state = json.loads(STATE.read_text())
    general_id = state["general"]["tenantId"]
    body = {"name": AGENT, "baseUrl": AGENT_URL, "protocol": "A2A", "answersChat": True}
    # Kept when `full` made it a tool of the chat: it answers again. An update at the same address does not read the
    # card again, so the kept one is reloaded - a first run before the agent was up left it without skills.
    kept = connection_named(admin, general_id, AGENT)
    if kept:
        call(admin, "PUT", f"/admin/tenants/{general_id}/mcp-connections/{kept['id']}", body)
        connection = call(admin, "POST", f"/admin/tenants/{general_id}/mcp-connections/{kept['id']}/reload")
    else:
        connection = call(admin, "POST", f"/admin/tenants/{general_id}/mcp-connections", body)
    if connection.get("toolsError"):
        raise SystemExit(f"the agent's card could not be read at {AGENT_URL}: {connection['toolsError']} - "
                         "is the test agent running (TEST_AGENT_DEMO=1 devtools/a2a-test-agent/start.sh)?")
    state["general"]["agentConnection"] = connection["id"]
    state["general"]["agentAnswers"] = True
    STATE.write_text(json.dumps(state, indent=2, ensure_ascii=False))
    print(f"the general chat answers through {connection['name']} ({len(connection['tools'])} skills)")


def full():
    """The topics with a "full" part get everything the assistant offers - asked by the general chat on their way."""
    admin = stack.admin()
    state = json.loads(STATE.read_text())
    general_id = state["general"]["tenantId"]
    stop_agent_answering(admin, general_id, state)
    state["full"] = {}
    extras = {topic["connection"]: topic["full"] for topic in TOPICS if "full" in topic}
    for topic in state["topics"]:
        extra = extras.get(topic["connection"])
        if extra:
            state["full"][topic["connection"]] = equip(admin, topic, extra)
            connection = call(admin, "POST", f"/admin/tenants/{general_id}/mcp-connections/"
                                             f"{state['general']['connections'][topic['connection']]}/reload")
            enable_tools(admin, general_id, connection, offered(topic, connection))
            state["full"][topic["connection"]]["generalTools"] = sorted(
                tool["name"] for tool in connection["tools"] if tool["name"] in offered(topic, connection))
    asked = list(state["general"]["connections"].values()) + [state["general"].get("agentConnection")]
    set_levels(admin, general_id, "Allgemeiner Chat",
               {connection_id: "READ" for connection_id in asked if connection_id}, replace=True)
    state["oauth"] = oauth_software(admin, next(t for t in state["topics"] if t["connection"] == OAUTH_TOPIC))
    STATE.write_text(json.dumps(state, indent=2, ensure_ascii=False))
    for name, equipped in state["full"].items():
        print(f"{name} in full (sandbox, software {equipped['software']}, web search, template) - the general chat "
              f"uses {', '.join(equipped['generalTools'])}")
    print(f"{OAUTH_SOFTWARE} in {OAUTH_TOPIC}: sign-in {state['oauth']['status']} - page Software, button Anmelden")


def oauth_software(admin, topic):
    """The software that signs in with OAuth, connected without a key - kept when it is there already. Its people read
    its tickets in the chat once someone signed in."""
    tenant_id = topic["tenantId"]
    connection = next((c for c in call(admin, "GET", f"/admin/tenants/{tenant_id}/mcp-connections")
                       if c["name"] == OAUTH_SOFTWARE), None)
    if connection is None:
        connection = call(admin, "POST", f"/admin/tenants/{tenant_id}/mcp-connections",
                          {"name": OAUTH_SOFTWARE, "baseUrl": OAUTH_SOFTWARE_URL})
    if not connection.get("signIn"):
        raise SystemExit(f"{OAUTH_SOFTWARE} was not taken for a software that signs in: {connection.get('toolsError')}")
    if connection["tools"]:
        enable_tools(admin, tenant_id, connection, ["list_tickets"])
    set_levels(admin, tenant_id, "Mitarbeitende", {connection["id"]: "READ"})
    return {"tenantId": tenant_id, "connectionId": connection["id"], "status": connection["signIn"]["status"]}


def equip(admin, topic, extra):
    """Its own sandbox, the software (reading tools, files, a changing one), a template, the web search - and its key
    opens them to the general chat (the MCP way passes the software's reading tools through)."""
    tenant_id = topic["tenantId"]
    call(admin, "PUT", f"/admin/tenants/{tenant_id}",
         {"name": topic["tenant"], "sandboxUrls": extra["sandbox"], "sandboxToken": extra["token"]})
    # Kept when it is there already: `full` may run again (after `a2a`, say).
    software = connection_named(admin, tenant_id, extra["software"]) \
        or call(admin, "POST", f"/admin/tenants/{tenant_id}/mcp-connections",
                {"name": extra["software"], "baseUrl": SOFTWARE_URL})
    if software.get("toolsError"):
        raise SystemExit(f"the topic's software could not be read at {SOFTWARE_URL}: {software['toolsError']}")
    enable_tools(admin, tenant_id, software, SOFTWARE_TOOLS)
    if topic["way"] == "mcp":
        call(admin, "PUT", f"/admin/tenants/{tenant_id}/mcp-connections/{software['id']}/pass-through",
             {"passesThrough": True})
    # The topic's own people use the software in its chat (the changing call after their decision) and the web search.
    set_levels(admin, tenant_id, "Mitarbeitende", {software["id"]: "WRITE"}, web_search=True)
    templates = f"/admin/tenants/{tenant_id}/assistant/templates"
    template = next((kept for kept in call(admin, "GET", templates) if kept["name"] == extra["template"]), None) \
        or call(admin, "POST", templates, {"name": extra["template"], "instructions": extra["instructions"]})
    web_search_for(admin, tenant_id)
    call(admin, "PUT", f"/admin/tenants/{tenant_id}/mcp-keys/{topic['mcpKeyId']}",
         {"webSearch": True, "connectionIds": [software["id"]]})
    return {"software": extra["software"], "softwareId": software["id"], "templateId": template["id"]}


def connection_named(admin, tenant_id, name):
    """The tenant's connection of that name, or None."""
    return next((connection for connection in call(admin, "GET", f"/admin/tenants/{tenant_id}/mcp-connections")
                 if connection["name"] == name), None)


def enable_tools(admin, tenant_id, connection, names):
    for tool in connection["tools"]:
        if tool["name"] in names and not tool.get("enabled"):
            call(admin, "PUT", f"/admin/tenants/{tenant_id}/mcp-connections/{connection['id']}/tools/{tool['id']}",
                 {"enabled": True})


def set_levels(admin, tenant_id, group_name, levels, replace=False, web_search=None):
    """The group's level per connection - added to its levels, or (replace) its levels from now on."""
    group = next(group for group in call(admin, "GET", f"/admin/tenants/{tenant_id}/groups")
                 if group["name"] == group_name)
    kept = {} if replace else {level["connectionId"]: level["level"] for level in group.get("levels") or []}
    kept.update(levels)
    call(admin, "PUT", f"/admin/tenants/{tenant_id}/groups/{group['id']}",
         {"name": group["name"], "webSearch": web_search,
          "levels": [{"connectionId": connection_id, "level": level} for connection_id, level in kept.items()]})


def web_search_for(admin, tenant_id):
    """The catalog's web search (made once), the admin's global Tavily key - the fake's - and the tenant allowed it."""
    entry = next((model for model in call(admin, "GET", "/admin/models") if model["kind"] == "WEB_SEARCH"), None)
    if entry is None:
        entry = call(admin, "POST", "/admin/models",
                     {"provider": "TAVILY", "kind": "WEB_SEARCH", "modelName": "tavily-basic",
                      "displayName": "Websuche (Fake)", "inputPrice": 8, "outputPrice": 0})
    call(admin, "PUT", "/admin/provider-keys/TAVILY", {"apiKey": TAVILY_KEY})
    allow_models(admin, tenant_id, [entry["id"]])


def allow_models(admin, tenant_id, model_ids):
    """Adds models to those the tenant may use - never takes one away (models and full in either order)."""
    assigned = call(admin, "GET", f"/admin/tenants/{tenant_id}/models")["modelIds"]
    call(admin, "PUT", f"/admin/tenants/{tenant_id}/models",
         {"modelIds": list(dict.fromkeys(assigned + model_ids))})


def stop_agent_answering(admin, general_id, state):
    """An agent of `setup.py a2a` would answer every question itself - it stays, as a tool of the chat."""
    agent_id = state["general"].get("agentConnection")
    state["general"]["agentAnswers"] = False
    if not agent_id:
        return
    agent = next((connection for connection in call(admin, "GET", f"/admin/tenants/{general_id}/mcp-connections")
                  if connection["id"] == agent_id), None)
    if agent and agent.get("answersChat"):
        call(admin, "PUT", f"/admin/tenants/{general_id}/mcp-connections/{agent_id}",
             {"name": agent["name"], "baseUrl": agent["baseUrl"], "protocol": "A2A", "answersChat": False})
        print(f"{agent['name']} no longer answers the general chat - it is one of its tools now")


def agent_off():
    """The agent of `a2a` taken away again - for when its demo stops: check.py expects every connection to be reached."""
    admin = stack.admin()
    state = json.loads(STATE.read_text())
    general_id = state["general"]["tenantId"]
    agent = connection_named(admin, general_id, AGENT)
    if agent:
        call(admin, "DELETE", f"/admin/tenants/{general_id}/mcp-connections/{agent['id']}")
    state["general"].pop("agentConnection", None)
    state["general"].pop("agentAnswers", None)
    STATE.write_text(json.dumps(state, indent=2, ensure_ascii=False))
    print(f"{AGENT} removed from the general chat" if agent else f"no {AGENT} in the general chat")


def oauth():
    """
    The Ticketsystem connected anew: the OAuth fake keeps its registered clients in memory only, so after its container
    restarted (mode.sh, a rebuild) it refuses the stored client ("unbekannter Client"). Signed in by check.py again.
    """
    admin = stack.admin()
    state = json.loads(STATE.read_text())
    topic = next(t for t in state["topics"] if t["connection"] == OAUTH_TOPIC)
    kept = connection_named(admin, topic["tenantId"], OAUTH_SOFTWARE)
    if kept:
        call(admin, "DELETE", f"/admin/tenants/{topic['tenantId']}/mcp-connections/{kept['id']}")
    state["oauth"] = oauth_software(admin, topic)
    STATE.write_text(json.dumps(state, indent=2, ensure_ascii=False))
    print(f"{OAUTH_SOFTWARE} connected anew in {OAUTH_TOPIC}: sign-in {state['oauth']['status']}")


def models():
    state = json.loads(STATE.read_text())
    provider = os.environ["PROVIDER"]
    embedding_provider = os.environ.get("EMBEDDING_PROVIDER", provider) if os.environ.get("EMBEDDING_MODEL") else None
    admin = stack.admin()
    for name in dict.fromkeys(filter(None, [provider, embedding_provider])):
        key = provider_key(name)
        if key:
            call(admin, "PUT", f"/admin/provider-keys/{name}", {"apiKey": key})
    chat = call(admin, "POST", "/admin/models",
                {"provider": provider, "kind": "CHAT", "modelName": os.environ["CHAT_MODEL"],
                 "displayName": os.environ["CHAT_MODEL"], "inputPrice": float(os.environ.get("CHAT_INPUT_PRICE", "1")),
                 "outputPrice": float(os.environ.get("CHAT_OUTPUT_PRICE", "5")),
                 # Claude Sonnet 5.5 refuses a temperature (HTTP 400, measured 6 November 2026) - CHAT_TEMPERATURE=1 sends one.
                 "takesTemperature": os.environ.get("CHAT_TEMPERATURE") == "1"})
    for tenant_id in tenant_ids(state):
        allow_models(admin, tenant_id, [chat["id"]])
        call(admin, "PUT", f"/admin/tenants/{tenant_id}/assistant",
             {"instructions": "", "answerSource": "DOCUMENTS_FIRST", "creativity": "LOW", "reasoning": "LOW",
              "searchResults": 5, "defaultModelId": chat["id"]})
    state["chatModelId"] = chat["id"]
    state["chatModelName"] = chat["displayName"]
    STATE.write_text(json.dumps(state, indent=2, ensure_ascii=False))
    print(f"models set up - chat model {chat['id']}; {STATE.name} updated")
    if embedding_provider:
        knowledge({"provider": embedding_provider, "kind": "EMBEDDING", "modelName": os.environ["EMBEDDING_MODEL"],
                   "displayName": os.environ["EMBEDDING_MODEL"], "dimension": int(os.environ["EMBEDDING_DIMENSION"]),
                   "inputPrice": 0.02, "outputPrice": 0})


def knowledge(model=None):
    """
    The embedding model of every tenant - the stack's own (bge-m3, the container embeddings) unless `models` names
    another -, the topics' documents pushed (their path as externalId: pushed again they replace themselves, nothing
    is embedded twice), and the client software's keys of Thema Wartung and Thema Einkauf that check.py uses.
    """
    state = json.loads(STATE.read_text())
    admin = stack.admin()
    model = model or {"provider": "OLLAMA", "kind": "EMBEDDING", "modelName": EMBEDDINGS_MODEL,
                      "displayName": "bge-m3 (Stack)", "dimension": EMBEDDINGS_DIMENSION, "inputPrice": 0,
                      "outputPrice": 0, "baseUrl": EMBEDDINGS_URL}
    embedding = next((entry for entry in call(admin, "GET", "/admin/models")
                      if entry["kind"] == "EMBEDDING" and entry["modelName"] == model["modelName"]), None) \
        or call(admin, "POST", "/admin/models", model)
    for tenant_id in tenant_ids(state):
        allow_models(admin, tenant_id, [embedding["id"]])
        tenant = call(admin, "GET", f"/admin/tenants/{tenant_id}")
        if tenant.get("embeddingModelId") != embedding["id"]:
            call(admin, "PUT", f"/admin/tenants/{tenant_id}/embedding-model", {"modelId": embedding["id"]})
    machines = state.setdefault("machines", {})
    for topic in state["topics"] + [state["general"]]:
        machines.update(upload(admin, topic, machines))
    for connection_id in state["general"]["connections"].values():
        call(admin, "POST", f"/admin/tenants/{state['general']['tenantId']}/mcp-connections/{connection_id}/reload")
    state["embeddingModelId"] = embedding["id"]
    STATE.write_text(json.dumps(state, indent=2, ensure_ascii=False))
    print(f"knowledge set up - embedding model {embedding['displayName']} for every tenant, documents embedded; "
          f"{STATE.name} updated")


def tenant_ids(state):
    return [topic["tenantId"] for topic in state["topics"]] + [state["general"]["tenantId"]]


def stores():
    """Thema Wartung's file store, its knowledge source and - with a chat model - its scheduled task."""
    state = json.loads(STATE.read_text())
    admin = stack.admin()
    topic = next(t for t in state["topics"] if t["connection"] == STORE_TOPIC)
    tenant_id = topic["tenantId"]
    machines = state.get("machines") or {}
    if STORE_TOPIC not in machines:
        raise SystemExit("run setup.py knowledge first")
    stack.sftp(f"mkdir -p {stack.STORE_PATH}/wissen {stack.STORE_PATH}/logs && touch {stack.STORE_PATH}/logs/p200.log")
    stack.sftp(f"cat > {stack.STORE_PATH}/{SOURCE_FILE[0]} <<'END'\n{SOURCE_FILE[1]}END")
    path = f"/admin/tenants/{tenant_id}/file-stores"
    store = next((s for s in call(admin, "GET", path) if s["name"] == STORE["name"]), None) \
        or call(admin, "POST", path, STORE)
    checked = call(admin, "POST", f"{path}/{store['id']}/check")
    if checked["status"] != "OK":
        # A new image has a new host key: confirmed here - a test stack trusts its own container.
        call(admin, "PUT", f"{path}/{store['id']}/fingerprint", {"fingerprint": checked["fingerprint"]})
        checked = call(admin, "POST", f"{path}/{store['id']}/check")
    if checked["status"] != "OK":
        raise SystemExit(f"the file store answered {checked}")
    sources = f"/admin/tenants/{tenant_id}/knowledge-sources"
    source = next((s for s in call(admin, "GET", sources) if s["fileStoreId"] == store["id"]), None) \
        or call(admin, "POST", sources, {"name": "Ablage Wissen", "fileStoreId": store["id"], "folder": "wissen",
                                         "schedule": "MANUAL"})
    call(admin, "PUT", f"/admin/tenants/{tenant_id}", {"name": topic["tenant"], "tasksAllowed": True})
    state["stores"] = {"tenantId": tenant_id, "storeId": store["id"], "sourceId": source["id"]}
    if state.get("chatModelId"):
        state["stores"]["taskId"] = scheduled_task(admin, state, topic, store)
    STATE.write_text(json.dumps(state, indent=2, ensure_ascii=False))
    print(f"{STORE['name']} in {topic['tenant']}: fingerprint confirmed, knowledge source {source['name']}"
          + (f", task {TASK_NAME}" if "taskId" in state["stores"] else " - the task comes with `models`, then "
             "`stores` again"))


def scheduled_task(admin, state, topic, store):
    """The log check of the press: ERROR lines of the store's logs, judged by the chat model, mailed when it finds one.
    Assigned to the client software's key and to the topic's MCP key (A2A: then also a skill of its card)."""
    tenant_id = topic["tenantId"]
    model = next(m for m in call(admin, "GET", "/admin/models") if m["id"] == state["chatModelId"])
    source = f"""version: 1
name: {TASK_NAME}
notify: [instandhaltung@example.com]
steps:
  - id: logs
    type: read_files
    store: {store['name']}
    folder: logs
    pattern: "*.log"
    include: [ERROR]
  - id: check
    type: agent
    model: {model['displayName']}
    input: [logs]
    checks:
      - name: Hydraulik
        severity: error
        instruction: Did the press P200 report a hydraulic error? Name its code.
  - id: mail
    type: send_mail
    if: check.errors > 0
    input: [check]
    subject: Befund Presse P200
"""
    tasks = f"/admin/tenants/{tenant_id}/tasks"
    existing = next((t for t in call(admin, "GET", tasks) if t["name"] == TASK_NAME), None)
    task = call(admin, "PUT", f"{tasks}/{existing['id']}", {"source": source}) if existing \
        else call(admin, "POST", tasks, {"source": source})
    machine = state["machines"][topic["connection"]]
    call(admin, "PUT", f"/admin/tenants/{tenant_id}/api-keys/{machine['keyId']}", {"taskIds": [task["id"]]})
    call(admin, "PUT", f"/admin/tenants/{tenant_id}/mcp-keys/{topic['mcpKeyId']}", {"taskIds": [task["id"]]})
    return task["id"]


def walk():
    """The catalog as the browser walk needs it - every key from the environment or KEYS_FILE, never typed in a page."""
    state = json.loads(STATE.read_text())
    if not state.get("chatModelId"):
        raise SystemExit("run setup.py models first")
    admin = stack.admin()
    catalog = call(admin, "GET", "/admin/models")
    chat = next(model for model in catalog if model["id"] == state["chatModelId"])
    call(admin, "PUT", f"/admin/models/{chat['id']}", model_request(chat) | {"readsPdf": True, "readsScans": True})
    ready = {"chatModel": chat["displayName"], "scans": chat["displayName"]}

    image_provider = os.environ.get("IMAGE_PROVIDER", "GEMINI")
    image_model = os.environ.get("IMAGE_MODEL", "gemini-2.5-flash-image")
    if optional_key(admin, image_provider):
        ready["imageModel"] = catalog_entry(admin, catalog, {
            "provider": image_provider, "kind": "IMAGE", "modelName": image_model, "displayName": image_model,
            "inputPrice": 0.3, "outputPrice": 30})["displayName"]

    transcriber, transcribe_model = os.environ.get("TRANSCRIBE_PROVIDER"), os.environ.get("TRANSCRIBE_MODEL")
    if transcriber and transcribe_model and optional_key(admin, transcriber):
        ready["transcriber"] = catalog_entry(admin, catalog, {
            "provider": transcriber, "kind": "CHAT", "modelName": transcribe_model, "displayName": transcribe_model,
            "inputPrice": 1, "outputPrice": 5, "readsAudio": True, "transcribes": True})["displayName"]

    state["walk"] = ready
    STATE.write_text(json.dumps(state, indent=2, ensure_ascii=False))
    for part in ("imageModel", "transcriber"):
        if part not in ready:
            print(f"left out: {part} - no key or no model named; the walk reports its steps as not run")
    print(f"the walk's catalog is ready: {ready}; {STATE.name} updated")


def model_request(model):
    """A catalog entry as its update takes it."""
    fields = ("provider", "kind", "modelName", "displayName", "dimension", "inputPrice", "outputPrice")
    return {name: model[name] for name in fields}


def catalog_entry(admin, catalog, entry):
    """The catalog's entry of that provider and model name - made once."""
    existing = next((model for model in catalog if model["provider"] == entry["provider"]
                     and model["modelName"] == entry["modelName"]), None)
    return existing or call(admin, "POST", "/admin/models", entry)


def optional_key(admin, provider):
    """Stores the provider's key as the admin's global one when the environment or KEYS_FILE has it - true when the
    stack then has a key of that provider (one entered by hand before counts)."""
    key = None
    try:
        key = provider_key(provider)
    except SystemExit:
        pass
    if key:
        call(admin, "PUT", f"/admin/provider-keys/{provider}", {"apiKey": key})
        return True
    stored = call(admin, "GET", "/admin/provider-keys")
    return any(entry.get("provider") == provider and entry.get("keyHint") for entry in stored)


def provider_key(provider):
    """
    The provider's key: <PROVIDER>_KEY from the environment (e.g. ANTHROPIC_KEY), else the line <PROVIDER>_KEY=... or
    <PROVIDER>_API_KEY=... of the file KEYS_FILE (KEY=value lines, # comments; a name may carry a prefix) - else none: the key was entered by hand
    in the stack (the admin's page "Globale API-Keys"). The value only goes to the stack, it is never printed.
    """
    names = [f"{provider}_KEY", f"{provider}_API_KEY"]
    for name in names:
        if os.environ.get(name):
            return os.environ[name]
    path = os.environ.get("KEYS_FILE")
    if not path:
        return None
    for line in pathlib.Path(path).expanduser().read_text().splitlines():
        name, _, value = line.strip().removeprefix("export ").partition("=")
        if any(name.strip().endswith(known) for known in names) and value.strip():
            return value.strip().strip('"').strip("'")
    raise SystemExit(f"{path} has no line {names[0]}=... or {names[1]}=...")


def upload(admin, topic, machines):
    """
    The folder's documents through an API key of the tenant (the restricted one into its area, by the key with that
    area), each with its path as externalId. The keys are kept for check.py: "keyId"/"key" the client software's key
    without an area, "onceKey" one that keeps no history (it may send files with a question), "areaKey" the one
    with the area (Thema Wartung). Returns {connection or "general": keys}.
    """
    tenant_id = topic["tenantId"]
    name = topic.get("connection", "general")
    keys = machines.get(name)
    if keys is None:
        plain = call(admin, "POST", f"/admin/tenants/{tenant_id}/api-keys", {"name": "Client-Software"})
        once = call(admin, "POST", f"/admin/tenants/{tenant_id}/api-keys",
                    {"name": "Client-Software ohne Verlauf", "keepsHistory": False})
        keys = {"keyId": plain["key"]["id"], "key": plain["plainKey"], "onceKey": once["plainKey"]}
        if topic.get("areaId"):
            restricted = call(admin, "POST", f"/admin/tenants/{tenant_id}/api-keys",
                              {"name": "Client-Software Leitung", "areaIds": [topic["areaId"]]})
            keys |= {"areaKeyId": restricted["key"]["id"], "areaKey": restricted["plainKey"]}
    files = sorted((HERE / "documents" / topic["folder"]).glob("*.md"))
    # A stack set up before 9 November 2026 pushed them without an externalId - those would stay beside the new ones.
    sees_all = keys.get("areaKey") or keys["key"]
    for document in stack.with_key(sees_all, "GET", "/api/v1/documents")[2]:
        if document["externalId"] is None and document["fileName"] in {path.name for path in files}:
            stack.with_key(sees_all, "DELETE", f"/api/v1/documents/{document['id']}")
    for path in files:
        restricted = path.name == RESTRICTED["file"]
        status, answer = stack.push(keys["areaKey"] if restricted else keys["key"], path.name, path.read_bytes(),
                                    external_id=f"{topic['folder']}/{path.name}",
                                    area=RESTRICTED["area"] if restricted else None)
        if status not in (200, 201):
            raise SystemExit(f"uploading {path.name} answered {status}: {answer}")
    for key in (keys["key"], keys.get("areaKey")):
        if key and any(d["status"] == "FAILED" for d in stack.wait_embedded(key)):
            raise SystemExit(f"a document of {topic['tenant']} failed: {stack.wait_embedded(key)}")
    return {name: keys}


if __name__ == "__main__":
    {"base": base, "knowledge": knowledge, "models": models, "stores": stores, "a2a": a2a, "agent-off": agent_off,
     "full": full, "oauth": oauth, "walk": walk}.get(sys.argv[1] if len(sys.argv) > 1 else "", lambda: sys.exit(__doc__))()
