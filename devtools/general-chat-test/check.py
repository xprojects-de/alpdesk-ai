#!/usr/bin/env python3
"""Checks what the test stack does without a model (README.md) - after `setup.py base`, the agent's part after
`setup.py a2a` with the demo agent running (TEST_AGENT_DEMO=1). In the mode of mode.sh: with WAM as below; plain -
password login at the common page and a tenant's own (its login key for a name in several tenants), a wrong password,
the switch only with the other user's password and only between a person's users (a user created under another
tenant's user's name sees nothing of that tenant), five wrong passwords locking an account until the admin sets a
new one, clara in no group refused, the WAM headers ignored; in both modes the admin's account is never locked:

  - the sign-in through Authelia: a person in one IDM group gets its user there and is signed in at once,
  - a person in two IDM groups gets a user in both tenants and the choice, chooses, sees the topics, switches,
  - a person in no IDM group of a group waits as an access request - with the browser's language,
  - the language: a new account speaks the browser's, a choice in one tenant holds in every tenant of the person,
  - a header sent from outside never reaches the app: Caddy replaces Remote-User and Remote-Groups,
  - without a session at Authelia nothing reaches the app - but machines with a key do: the agent card, /a2a, /mcp,
  - the general chat answered by the A2A agent: its skills, the streamed answer with its steps, a question back and
    its continuation, a skill switched off declined,
  - the eight topics, each on its way: over MCP with its tools (search, ask_assistant or both), over A2A with its skills,
  - after `setup.py full`: Wartung (A2A) and Qualitaet (MCP) each with its sandbox, software (a changing tool among
    them), the web search and a template - Qualitaet's software passed through, its changing tool only while
    the key may change in it (given, called, taken back),
  - after `setup.py full`: the Ticketsystem (the OAuth fake) signs in through its consent page - the browser's way,
    clicked here - its tools read with the token, a revoked token renewed once, a refused renewal "sign-in needed",
    signed in again,
  - after `setup.py knowledge`: the client software at /api/v1 with its keys - its tenant and areas, a push with
    externalId (new, unchanged, replaced, deleted), a key writes and lists only where it reads, another tenant's
    document 404; an MCP client searching and reading itself (search_knowledge, read_document) - never the area its key
    does not release -, the templates as prompts, a key without the A2A door refused at /a2a,
  - after `setup.py stores`: the file store proves itself by its fingerprint, the knowledge source syncs a file in,
    replaces it when it changes, deletes it when it vanishes (and takes it in again), a task's mistake is told with its
    line; the daily report arrives at MailHog, the application log answers,
  - what the machines' checks above leave out: the key's software and tasks, a file for a conversation of the key (sent,
    listed, read, deleted - never another key's), list_templates and list_tasks, each key only at its own door, the A2A
    extensions' descriptions; the watchdog with every answer's headers, the version, the API documentation, a deep link,
  - the admin's password session does not depend on the person at the proxy (decided: the admin logs out when done).

Prints one line per check and exits 1 at the first that fails. Runs again and again on the same stack.
"""
import json
import re
import sys
import time
import urllib.parse

import setup
import stack

state = json.loads(stack.STATE_FILE.read_text())
general_id = state["general"]["tenantId"]
tenant_of = {topic["connection"]: topic["tenantId"] for topic in state["topics"]}
full = state.get("full")
# `setup.py a2a` makes the agent answer the chat, `full` makes it a tool again - the topics are then asked by the chat's
# own model. A state from before "agentAnswers" was kept: the agent answers unless `full` ran.
answered_by_agent = state["general"].get("agentAnswers", "agentConnection" in state["general"] and not full)


def check(what, condition, detail=""):
    print(("ok    " if condition else "FAIL  ") + what + ("" if condition else f" - {detail}"))
    if not condition:
        sys.exit(1)


def mcp_result(bearer, method, params):
    """The result of one request to the tenant's MCP server with a key ({} when it failed)."""
    status, _, text = stack.request(stack.APP_HOST, "POST", "/mcp",
                                    {"jsonrpc": "2.0", "id": 1, "method": method, "params": params},
                                    bearer | {"Accept": "application/json, text/event-stream"}, raw=True)
    return json.loads(text[text.index("{"):]).get("result", {}) if status == 200 else {}


def users_of(admin, tenant_id):
    return {user["username"]: user for user in admin.app("GET", f"/admin/tenants/{tenant_id}/users")[2]}


def left_over(admin, tenant_id, username):
    """Deletes a user a check creates for itself, should a run that broke off have left it."""
    user = users_of(admin, tenant_id).get(username)
    if user:
        admin.app("DELETE", f"/admin/tenants/{tenant_id}/users/{user['id']}")


admin = stack.admin()
wrong_admin = stack.Person(stack.ADMIN_PERSON)
refused = [wrong_admin.app("POST", "/login", {"username": stack.ADMIN_USER, "password": "Falsch-2026-Falsch"})[0]
           for _ in range(6)]
check("six wrong passwords for the admin are refused - and its account is not locked by them",
      refused == [401] * 6 and stack.admin().token, refused)

# --- The sign-in ----------------------------------------------------------------------------------------------------

if stack.WAM:
    anna_was_new = "anna" not in users_of(admin, tenant_of["Wartung"])
    anna = stack.Person("anna")
    status, body = anna.sign_in(language="de")
    check("anna (AI-Thema-Wartung) gets her user in Thema Wartung and is signed in at once",
          status == 200 and anna.token, f"{status} {body}")
    account = anna.app("GET", "/tenant/account")[2]
    check("her account is Thema Wartung's, created from the IDM", account.get("tenantId") == tenant_of["Wartung"],
          account)
    if anna_was_new:
        check("her new account speaks the browser's language", account.get("language") == "de", account)

    bernd = stack.Person("bernd")
    status, body = bernd.sign_in()
    choice = {tenant["id"] for tenant in (body or {}).get("tenants", [])} if isinstance(body, dict) else set()
    check("bernd (AI-Thema-Einkauf, AI-Allgemein) gets users in both and the choice",
          status == 200 and choice == {tenant_of["Einkauf"], general_id}, f"{status} {body}")
    status, body = bernd.sign_in(general_id)
    check("he signs in at the general chat", status == 200 and bernd.token, f"{status} {body}")
else:
    status, _, wam = stack.request(stack.APP_HOST, "GET", "/login/wam")
    check("the app runs without WAM", status == 200 and not wam["enabled"], wam)
    anna = stack.Person("anna")
    status, body = anna.sign_in()
    check("anna (a user in Thema Wartung only) signs in with name and password at the common login page",
          status == 200 and anna.token, f"{status} {body}")
    check("her account is Thema Wartung's", anna.app("GET", "/tenant/account")[2].get("tenantId") ==
          tenant_of["Wartung"])
    check("a wrong password is refused", stack.Person("anna", "Falsch-2026").sign_in()[0] == 401)

    bernd = stack.Person("bernd")
    status, body = bernd.sign_in()
    check("bernd (users in Thema Einkauf and the general chat) is refused at the common page - the name alone "
          "does not tell which", status == 401, f"{status} {body}")
    status, _, page = stack.request(stack.APP_HOST, "GET", f"/branding/login/{stack.login_key(general_id)}")
    check("the general chat's own login page answers without a sign-in", status == 200 and page.get("name"), page)
    status, body = bernd.sign_in(general_id)
    check("he signs in at the general chat's own login page", status == 200 and bernd.token, f"{status} {body}")
    tenants = {tenant["id"] for tenant in bernd.app("GET", "/tenant/account")[2].get("tenants", [])}
    check("his account lists both tenants to switch to", tenants == {tenant_of["Einkauf"], general_id}, tenants)
software = bernd.app("GET", "/tenant/chat/software")[2]
if answered_by_agent:
    check("the general chat answered by its agent offers the agent's skills, not the topics",
          software and all(entry["id"].startswith("skill:") for entry in software), software)
else:
    expected = sorted(list(tenant_of) + (["Auftragsassistent"] if "agentConnection" in state["general"] else []))
    check(f"the general chat offers him the {len(expected)} topics", sorted(s["name"] for s in software) == expected,
          software)
    over_mcp = {topic["connection"] for topic in state["topics"] if topic["way"] == "mcp"}
    check("each topic over MCP tells what it knows (the key's description in the instructions)",
          all("This access knows:" in (s.get("instructions") or "") for s in software if s["name"] in over_mcp),
          software)
    general = {c["name"]: c for c in admin.app("GET", f"/admin/tenants/{general_id}/mcp-connections")[2]}
    for topic in state["topics"]:
        connection = general[topic["connection"]]
        enabled = sorted(tool["name"] for tool in connection["tools"] if tool["enabled"])
        if topic["way"] == "a2a":
            check(f"{topic['connection']} is asked as an A2A agent, its skills turned on",
                  connection["protocol"] == "A2A" and "answer" in enabled, connection)
        else:
            passed = [name for name in (full or {}).get(topic["connection"], {}).get("generalTools", []) if "__" in name]
            wanted = sorted(topic["tools"] + passed)
            check(f"{topic['connection']} is asked over MCP with {', '.join(topic['tools'])}"
                  + (" and its software's reading tools" if topic["connection"] in (full or {}) else ""),
                  connection["protocol"] != "A2A" and enabled == wanted, f"{enabled} != {wanted}")
in_general = bernd.token
if stack.WAM:
    status, _, switched = bernd.app("POST", "/tenant/account/switch", {"tenantId": tenant_of["Einkauf"]})
    check("he switches to Thema Einkauf without a password", status == 200 and switched.get("access_token"), switched)
else:
    status, _, refused = bernd.app("POST", "/tenant/account/switch",
                                   {"tenantId": tenant_of["Einkauf"], "password": "Falsch-2026"})
    check("without WAM a switch with a wrong password is refused - a name proves no person",
          status == 400 and refused.get("code") == "account.switchPasswordWrong", f"{status} {refused}")
    status, _, switched = bernd.app("POST", "/tenant/account/switch",
                                    {"tenantId": tenant_of["Einkauf"], "password": stack.PERSON_PASSWORD})
    check("he switches to Thema Einkauf with his password there", status == 200 and switched.get("access_token"),
          switched)
    # A user created by name is nobody else's: Thema Einkauf gets an "anna" of its own, as its tenant admin could
    # create one under a guessed name - she must learn nothing of Thema Wartung, where the name is another user's.
    left_over(admin, tenant_of["Einkauf"], "anna")
    status, _, namesake = admin.app("POST", f"/admin/tenants/{tenant_of['Einkauf']}/users",
                                    {"username": "anna", "displayName": "Anna Namensgleich",
                                     "password": stack.PERSON_PASSWORD})
    check("Thema Einkauf gets a user of anna's name, created by name", status == 201, f"{status} {namesake}")
    try:
        other_anna = stack.Person("anna")
        status, body = other_anna.sign_in(tenant_of["Einkauf"])
        check("she signs in at Thema Einkauf's own login page", status == 200 and other_anna.token, f"{status} {body}")
        tenants = {tenant["id"] for tenant in other_anna.app("GET", "/tenant/account")[2].get("tenants", [])}
        check("her account lists Thema Einkauf alone - not Thema Wartung, where the name is another user's",
              tenants == {tenant_of["Einkauf"]}, tenants)
        status, _, refused = other_anna.app("POST", "/tenant/account/switch",
                                            {"tenantId": tenant_of["Wartung"], "password": stack.PERSON_PASSWORD})
        check("and she cannot switch there, although the password would be right - the name proves no person",
              status == 404, f"{status} {refused}")
    finally:
        if isinstance(namesake, dict) and namesake.get("id"):
            admin.app("DELETE", f"/admin/tenants/{tenant_of['Einkauf']}/users/{namesake['id']}")
    # Wrong passwords lock the account for a while (the stack keeps the defaults: 5 in a row, 15 minutes) - a user of
    # its own, so nobody else of the stack is locked, deleted again.
    left_over(admin, tenant_of["Wartung"], "sperre")
    status, _, locked = admin.app("POST", f"/admin/tenants/{tenant_of['Wartung']}/users",
                                  {"username": "sperre", "displayName": "Sigrid Sperre",
                                   "password": stack.PERSON_PASSWORD})
    check("Thema Wartung gets a user to lock", status == 201, f"{status} {locked}")
    try:
        check("she signs in with her password", stack.Person("sperre").sign_in()[0] == 200)
        refused = [stack.Person("sperre", "Falsch-2026").sign_in()[0] for _ in range(5)]
        check("five wrong passwords are each refused like any wrong password", refused == [401] * 5, refused)
        status, body = stack.Person("sperre").sign_in()
        check("her right password is refused now - answered like a wrong one, nothing tells the lock",
              status == 401 and "lock" not in str(body).lower(), f"{status} {body}")
        status, _, _ = admin.app("PUT", f"/admin/tenants/{tenant_of['Wartung']}/users/{locked.get('id')}",
                                 {"username": "sperre", "displayName": "Sigrid Sperre", "password": "Neu-und-lang-2026"})
        check("the admin sets her a new password", status == 200, status)
        check("the new password gets her in at once - it lifts the lock",
              stack.Person("sperre", "Neu-und-lang-2026").sign_in()[0] == 200)
    finally:
        if isinstance(locked, dict) and locked.get("id"):
            admin.app("DELETE", f"/admin/tenants/{tenant_of['Wartung']}/users/{locked['id']}")
bernd.token = switched["access_token"]
check("the switched token is Thema Einkauf's", bernd.app("GET", "/tenant/account")[2].get("tenantId") ==
      tenant_of["Einkauf"])

# --- The language follows the person ---------------------------------------------------------------------------------

for language in ("de", "en"):
    bernd.app("PUT", "/tenant/account/language", {"language": language})
    in_einkauf = bernd.app("GET", "/tenant/account")[2].get("language")
    general_account = stack.Person("bernd")
    general_account.token = in_general
    check(f"his choice of '{language}' in Thema Einkauf holds in the general chat too",
          in_einkauf == language and general_account.app("GET", "/tenant/account")[2].get("language") == language)

clara = stack.Person("clara")
if stack.WAM:
    status, body = clara.sign_in(language="de")
    check("clara (no IDM group of a group) waits as an access request", status == 409, f"{status} {body}")
    requests = [r for r in admin.app("GET", "/admin/access-requests")[2] if r["username"] == "clara"]
    check("her request keeps the browser's language for the admin's assignment",
          requests and requests[0].get("language") == "de", requests)
else:
    status, body = clara.sign_in(general_id)
    check("clara (a user of the general chat in no group) does not get in - told why after her right password",
          status == 409 and (body or {}).get("code") == "user.noGroup", f"{status} {body}")
    status, body = stack.Person("clara", "Falsch-2026").sign_in(general_id)
    check("with a wrong password she hears nothing about groups", status == 401, f"{status} {body}")

# --- What comes from outside -----------------------------------------------------------------------------------------

if stack.WAM:
    status, _, body = anna.app("GET", "/tenant/account",
                               headers={"Remote-User": "bernd", "Remote-Groups": "AI-Allgemein"})
    check("a forged Remote-User from outside is replaced by Caddy - anna stays anna",
          status == 200 and body.get("username") == "anna", f"{status} {body}")
    forged = stack.Person("anna")
    status, _, body = forged.app("POST", "/login/wam", {}, {"Remote-Groups": "AI-Allgemein"})
    check("a forged Remote-Groups gives anna no user in the general chat", status == 200 and body.get("access_token")
          and "tenants" not in body, f"{status} {body}")
    status, _, _ = stack.request(stack.APP_HOST, "GET", "/tenant/account", raw=True)
    check("without a session at Authelia nothing reaches the app", status in (302, 401, 403), status)
else:
    # Caddy passes the headers on in this mode (Caddyfile.plain) - the app must ignore them.
    status, _, _ = stack.request(stack.APP_HOST, "GET", "/tenant/account", headers={"Remote-User": "bernd"}, raw=True)
    check("without WAM a Remote-User header signs nobody in", status == 401, status)
    status, _, body = stack.request(stack.APP_HOST, "POST", "/login/wam", {}, {"Remote-User": "bernd"}, raw=True)
    check("and /login/wam takes no one", status != 200, f"{status} {body[:200]}")
    status, _, body = anna.app("GET", "/tenant/account", headers={"Remote-User": "bernd"})
    check("a Remote-User beside a token changes nothing - anna stays anna",
          status == 200 and body.get("username") == "anna", f"{status} {body}")

status, _, card = stack.request(stack.APP_HOST, "GET", "/.well-known/agent-card.json")
check("the public agent card passes the proxy without a person and names the https address",
      status == 200 and card["supportedInterfaces"][0]["url"] == f"https://{stack.APP_HOST}:{stack.PORT}/a2a",
      f"{status} {card}")
status, _, _ = stack.request(stack.APP_HOST, "POST", "/a2a", {}, raw=True)
check("/a2a without a key is the app's 401, not Authelia's sign-in", status == 401, status)
status, _, body = stack.request(stack.APP_HOST, "POST", "/mcp",
                                {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                                 "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                                            "clientInfo": {"name": "check", "version": "1"}}},
                                {"Authorization": "Bearer " + state["topics"][0]["mcpKey"],
                                 "Accept": "application/json, text/event-stream"}, raw=True)
check("an MCP key reaches /mcp through the proxy without a person", status == 200 and "serverInfo" in body,
      f"{status} {body[:200]}")

# --- The general chat answered by the A2A agent ----------------------------------------------------------------------

if answered_by_agent:
    asker = stack.Person("bernd")
    asker.token = in_general
    order = asker.ask(None, "Wie steht Auftrag 4711?")
    check("the agent answers a question of the general chat, streamed with its steps",
          "4711" in order["answer"] and order["tools"] and not order["error"], order)
    asked = asker.ask(None, "Buche bitte die Rechnung R-2026-118.")
    check("the agent asks back", "Auf welches Konto" in asked["answer"], asked)
    booked = asker.ask(None, "Auf 4200 bitte.", conversation_id=(asked["conversation"] or {}).get("id"))
    check("the next question in that conversation answers it", "Erledigt" in booked["answer"], booked)
    declined = asker.ask(None, "Wie steht Auftrag 4711?", excluded_skills=["auftraege"])
    check("a skill switched off for the question reaches the agent - it declines", "abgewählt" in declined["answer"],
          declined)
else:
    print("skip  the agent's part - run `setup.py a2a` (with the demo agent running) for it")

# --- The topic Wartung in full, asked over A2A -------------------------------------------------------------------------

if full:
    general = {c["name"]: c for c in admin.app("GET", f"/admin/tenants/{general_id}/mcp-connections")[2]}
    for name, equipped in full.items():
        topic = next(t for t in state["topics"] if t["connection"] == name)
        tenant_id = topic["tenantId"]
        sandbox = admin.app("GET", f"/admin/tenants/{tenant_id}/sandbox")[2]
        check(f"{name}: its own sandbox answers its token", [i["status"] for i in sandbox["instances"]] == ["reachable"],
              sandbox)
        connections = admin.app("GET", f"/admin/tenants/{tenant_id}/mcp-connections")[2]
        own = next(c for c in connections if c["id"] == equipped["softwareId"])
        tools = {tool["name"]: tool for tool in own["tools"]}
        check(f"{name}: its software's tools are on, cancel_order counted as changing",
              all(tools.get(n, {}).get("enabled") for n in ("list_invoices", "pdf_report", "cancel_order"))
              and tools["cancel_order"]["changes"] and not tools["list_invoices"]["changes"], tools)
        check(f"{name}: the software passes through to MCP clients exactly on the MCP way",
              own["passesThrough"] == (topic["way"] == "mcp"), own)
        key = next(k for k in admin.app("GET", f"/admin/tenants/{tenant_id}/mcp-keys")[2] if k["id"] == topic["mcpKeyId"])
        check(f"{name}: its MCP key gives the software and the web search, its door the way's - not the restricted area",
              key["mcp"] and key["a2a"] == (topic["way"] == "a2a") and key["webSearch"]
              and key["connectionIds"] == [equipped["softwareId"]] and not key["areaIds"], key)
        searches = {m["id"] for m in admin.app("GET", "/admin/models")[2] if m["kind"] == "WEB_SEARCH"}
        assigned = admin.app("GET", f"/admin/tenants/{tenant_id}/models")[2]["modelIds"]
        check(f"{name}: the tenant has the web search (the catalog's entry, the fake's key)",
              len(searches & set(assigned)) == 1, assigned)
        bearer = {"Authorization": "Bearer " + topic["mcpKey"]}
        if topic["way"] == "a2a":
            status, _, extended = stack.request(stack.APP_HOST, "POST", "/a2a",
                                                {"jsonrpc": "2.0", "id": 1, "method": "GetExtendedAgentCard",
                                                 "params": {}}, bearer | {"A2A-Version": "1.0"})
            skills = [skill["id"] for skill in ((extended or {}).get("result") or {}).get("skills", [])]
            task_id = (state.get("stores") or {}).get("taskId") if name == "Wartung" else None
            wanted = ["answer", f"template:{equipped['templateId']}"] + ([f"task:{task_id}"] if task_id else [])
            check(f"{name}: its extended agent card offers the answer, the template"
                  + (" and the task assigned to the key" if task_id else "") + " - through the proxy, with the key",
                  status == 200 and skills == wanted, f"{status} {extended}")
        else:
            status, _, listed = stack.request(stack.APP_HOST, "POST", "/mcp",
                                              {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
                                              bearer | {"Accept": "application/json, text/event-stream"}, raw=True)
            tools_listed = json.loads(listed[listed.index("{"):])["result"]["tools"] if status == 200 else []
            names = {tool["name"] for tool in tools_listed}
            software_name = equipped["software"]
            check(f"{name}: its MCP server passes the software's reading tools through, never cancel_order",
                  {f"{software_name}__list_invoices", f"{software_name}__pdf_report"} <= names
                  and not any(n.endswith("cancel_order") for n in names), f"{status} {sorted(names)}")
            # A key that may change in the software ("Darf ändern in", roadmap point 68): its changing tool comes
            # too, marked as not read-only, and is called - then the key reads only again, as the general chat needs it.
            key_path = f"/admin/tenants/{tenant_id}/mcp-keys/{topic['mcpKeyId']}"
            cancel = f"{software_name}__cancel_order"
            changed = admin.app("PUT", key_path, {"changingConnectionIds": [equipped["softwareId"]]})[2]
            changing = {tool["name"]: tool for tool in mcp_result(bearer, "tools/list", {}).get("tools", [])}
            cancelled = mcp_result(bearer, "tools/call", {"name": cancel, "arguments": {"number": "4711"}})
            back = admin.app("PUT", key_path, {"changingConnectionIds": []})[2]
            reading = {tool["name"] for tool in mcp_result(bearer, "tools/list", {}).get("tools", [])}
            check(f"{name}: a key that may change in the software gets cancel_order, marked as not read-only",
                  changed["changingConnectionIds"] == [equipped["softwareId"]] and cancel in changing
                  and changing[cancel]["annotations"]["readOnlyHint"] is False
                  and changing[f"{software_name}__list_invoices"]["annotations"]["readOnlyHint"] is True,
                  sorted(changing))
            check(f"{name}: its client calls cancel_order itself - the software answers",
                  not cancelled.get("isError") and "4711" in cancelled["content"][0]["text"], cancelled)
            check(f"{name}: taken back, the key reads only again",
                  back["changingConnectionIds"] == [] and cancel not in reading
                  and f"{software_name}__list_invoices" in reading, sorted(reading))
        check(f"{name}: the general chat's connection reads it again with its new offer",
              general[name]["toolsError"] is None and len(general[name]["tools"]) >= 2, general[name])
    offers = {"Wartung": "It can also search the web, run Python and shell code and read the connected software "
                         "Auftragsverwaltung",
              "Qualitaet": "It can also search the web, run Python and shell code and read the connected software "
                           "Pruefsoftware"}
    check("Wartung's card (A2A) says what it can do beyond its documents",
          offers["Wartung"] in (general["Wartung"].get("instructions") or ""), general["Wartung"].get("instructions"))
    paragraphs = (general["Qualitaet"].get("instructions") or "").split("\n\n")
    check("Qualitaet's instructions (MCP) start with what the key knows, then what it can do",
          paragraphs[0].startswith("This access knows: ") and paragraphs[1].startswith(offers["Qualitaet"]), paragraphs[:2])
    plain = [name for name in general if name in tenant_of and name not in full]
    check("a topic with nothing but its documents offers nothing more",
          all("It can also" not in (general[name].get("instructions") or "") for name in plain), plain)
    if answered_by_agent:
        check("the agent of `setup.py a2a` answers the general chat - until `setup.py full` makes it a tool again",
              any(c.get("answersChat") for c in general.values()), general)
    else:
        check("no agent answers the general chat any more - its own model asks the topics",
              not any(c.get("answersChat") for c in general.values()), general)
else:
    print("skip  the topics in full - run `setup.py full` for them")

# --- A software that signs in with OAuth ------------------------------------------------------------------------------

SIGNIN_HOST = "signin.general-chat.localhost"


def fake(method, path, body=None):
    """The OAuth fake's test switches and counters, through Caddy."""
    return stack.request(SIGNIN_HOST, method, path, body)[2]


def ticket_connection(oauth):
    connections = admin.app("GET", f"/admin/tenants/{oauth['tenantId']}/mcp-connections")[2]
    return next(c for c in connections if c["id"] == oauth["connectionId"])


def sign_in_like_a_browser(oauth, decision="allow"):
    """
    Anmelden: our sign-in address, the fake's consent page, its button, our callback - which only sends the browser on
    to the page with the server's answer -, and the page completing it as the admin who started it. Returns what the
    page then knows: "<page>?connection=<id>&signIn=<ok or the error's code>".
    """
    path = f"/admin/tenants/{oauth['tenantId']}/mcp-connections/{oauth['connectionId']}/sign-in"
    page = f"https://{stack.APP_HOST}:{stack.PORT}/dashboard/tenants/{oauth['tenantId']}/software"
    status, _, started = admin.app("POST", path, {"returnUrl": page})
    check("Anmelden answers the address of the server's sign-in page",
          status == 200 and started["authorizationUrl"].startswith(f"https://{SIGNIN_HOST}:"), f"{status} {started}")
    address = urllib.parse.urlsplit(started["authorizationUrl"])
    status, _, consent = stack.request(SIGNIN_HOST, "GET", f"{address.path}?{address.query}", raw=True)
    check("the server's consent page names the installation and the rights asked for",
          status == 200 and "möchte auf das Ticketsystem zugreifen" in consent and "tickets:read" in consent,
          f"{status} {consent[:300]}")
    form = dict(urllib.parse.parse_qsl(address.query)) | {"decision": decision}
    connection = stack._Local(SIGNIN_HOST)
    connection.request("POST", "/authorize", body=urllib.parse.urlencode(form),
                       headers={"Content-Type": "application/x-www-form-urlencoded"})
    response = connection.getresponse()
    response.read()
    callback = urllib.parse.urlsplit(response.getheader("Location") or "")
    check("the server sends the browser to the installation's callback, through Caddy",
          response.status == 302 and callback.netloc == f"{stack.APP_HOST}:{stack.PORT}"
          and callback.path == "/oauth/connections/callback", f"{response.status} {callback}")
    status, headers, _ = admin.app("GET", f"{callback.path}?{callback.query}", raw=True)
    back = dict((name.lower(), value) for name, value in headers).get("location", "")
    marker = "&signIn=complete&"
    start = f"{page}?connection={oauth['connectionId']}"
    check("the callback sends the browser on to the page it came from, with the server's answer - it signs in no one",
          status == 302 and back.startswith(start + marker)
          and ticket_connection(oauth)["signIn"]["status"] == "PENDING", f"{status} {back}")
    status, _, completed = admin.app("POST", path + "/complete", {"callback": back[len(start + marker):]})
    if status == 200:
        return start + "&signIn=ok"
    return start + "&signIn=" + str((completed or {}).get("code", "oauth.unknown")).split(".", 1)[-1]


oauth = state.get("oauth")
if oauth:
    fake("POST", "/test/refuse-refresh", {"on": False})
    ticket = ticket_connection(oauth)
    check("the Ticketsystem (no key) is kept as a software that signs in - its server named, no error",
          ticket.get("signIn") and ticket["signIn"]["issuer"] == "oauth-fake" and ticket["toolsError"] is None, ticket)
    back = sign_in_like_a_browser(oauth)
    check("signed in: the page hears it went well", back.endswith(f"connection={oauth['connectionId']}&signIn=ok"), back)
    ticket = ticket_connection(oauth)
    check("the connection is signed in and its tools were read with the token",
          ticket["signIn"]["status"] == "SIGNED_IN" and {t["name"] for t in ticket["tools"]} >=
          {"list_tickets", "create_ticket"} and ticket["signIn"]["scopesGranted"] == ["tickets:read", "tickets:write"],
          ticket)
    check("the installation registered itself at the server (its address is not public)",
          ticket["signIn"]["clientIdSource"] == "REGISTERED", ticket["signIn"])
    check("no token in the answer", "access_token" not in json.dumps(ticket) and "refresh" not in json.dumps(ticket),
          ticket)
    find = next(t for t in ticket["tools"] if t["name"] == "list_tickets")
    if not find["enabled"]:
        admin.app("PUT", f"/admin/tenants/{oauth['tenantId']}/mcp-connections/{oauth['connectionId']}/tools/{find['id']}",
                  {"enabled": True})
    reload = f"/admin/tenants/{oauth['tenantId']}/mcp-connections/{oauth['connectionId']}/reload"

    before = fake("GET", "/test/state")
    fake("POST", "/test/revoke")
    reloaded = admin.app("POST", reload)[2]
    after = fake("GET", "/test/state")
    check("a token the server no longer takes is renewed once and the call repeated - the reload reads the tools",
          # The fake counts across runs: what this reload added.
          reloaded["toolsError"] is None and after["refreshes"] == before["refreshes"] + 1
          and after["refusedRefreshes"] == before["refusedRefreshes"],
          f"{reloaded['toolsError']} {after}")

    fake("POST", "/test/refuse-refresh", {"on": True})
    fake("POST", "/test/revoke")
    refused = admin.app("POST", reload)[2]
    check("a refused renewal: the reload says a sign-in is needed, the connection shows it",
          "needs a sign-in" in (refused["toolsError"] or "")
          and ticket_connection(oauth)["signIn"]["status"] == "SIGN_IN_NEEDED", refused)
    fake("POST", "/test/refuse-refresh", {"on": False})

    status, _, page = admin.app("GET", "/oauth/connections/callback?state=unknown&code=x", raw=True)
    check("a callback with a state nobody started is refused with a plain page", status == 400
          and "no longer valid" in page, f"{status} {page[:200]}")
    denied = sign_in_like_a_browser(oauth, decision="deny")
    check("refused at the consent page: the page hears it, the connection still needs a sign-in",
          denied.endswith("signIn=denied") and ticket_connection(oauth)["signIn"]["status"] == "SIGN_IN_NEEDED", denied)
    sign_in_like_a_browser(oauth)
    check("signed in again it works - and stays signed in for the next person at the page",
          ticket_connection(oauth)["signIn"]["status"] == "SIGNED_IN")
else:
    print("skip  the software that signs in with OAuth - run `setup.py full` for it")

# --- Machines with a key: the client software at /api/v1 ----------------------------------------------------------------

stores = state.get("stores")
RESTRICTED_AREA = "Leitung Instandhaltung"
RESTRICTED_FILE = "intern-personalplanung.md"
machines = state.get("machines")
topic_of = {topic["connection"]: topic for topic in state["topics"]}


def names_listed(key):
    return {document["fileName"] for document in stack.with_key(key, "GET", "/api/v1/documents")[2]}


def pushed(key, external_id):
    return [d for d in stack.with_key(key, "GET", "/api/v1/documents")[2] if d.get("externalId") == external_id]


if machines:
    wartung, einkauf = machines["Wartung"], machines["Einkauf"]
    status, _, _ = stack.request(stack.APP_HOST, "GET", "/api/v1/me", raw=True)
    check("/api/v1 without a key is the app's 401, not Authelia's sign-in", status == 401, status)
    status, _, me = stack.with_key(wartung["key"], "GET", "/api/v1/me")
    check("the client software's key knows its tenant, no area", status == 200 and me["id"] == tenant_of["Wartung"]
          and me["areas"] == [], f"{status} {me}")
    me = stack.with_key(wartung["areaKey"], "GET", "/api/v1/me")[2]
    check("the key with the area names it", me.get("areas") == [RESTRICTED_AREA], me)
    check("only the key with the area lists the area's document",
          RESTRICTED_FILE in names_listed(wartung["areaKey"]) and RESTRICTED_FILE not in names_listed(wartung["key"]))
    status, refused = stack.push(wartung["key"], "fremd.md", b"# Fremd\n", area=RESTRICTED_AREA)
    check("a key without the area cannot push into it (400)", status == 400, f"{status} {refused}")

    external = "check/maschinen-notiz"
    stack.with_key(wartung["key"], "DELETE", "/api/v1/documents?externalId=" + urllib.parse.quote(external, safe=""))
    note = b"# Maschinennotiz\n\nDie Presse P200 laeuft im Zweischichtbetrieb. Pruefmarke MASCHINE-1.\n"
    status, created = stack.push(wartung["key"], "maschinen-notiz.md", note, external_id=external)
    check("a push with an externalId not known yet is a new document (201)", status == 201, f"{status} {created}")
    stack.wait_embedded(wartung["key"])
    status, again = stack.push(wartung["key"], "maschinen-notiz.md", note, external_id=external)
    check("pushed again unchanged: the same document, not embedded again",
          status == 200 and again["id"] == created["id"] and again["status"] == "READY", f"{status} {again}")
    status, changed = stack.push(wartung["key"], "maschinen-notiz-v2.md", note + b"Ab 2027 im Dreischichtbetrieb.\n",
                                 external_id=external)
    check("pushed changed: replaced in place, embedded again", status == 200 and changed["id"] == created["id"]
          and changed["fileName"] == "maschinen-notiz-v2.md", f"{status} {changed}")
    stack.wait_embedded(wartung["key"])
    check("one document for the externalId, READY", [d["status"] for d in pushed(wartung["key"], external)] == ["READY"])
    status, _, _ = stack.with_key(einkauf["key"], "DELETE", f"/api/v1/documents/{created['id']}")
    check("another tenant's key does not reach it (404)", status == 404, status)
    status, _, _ = stack.with_key(wartung["key"], "DELETE",
                                  "/api/v1/documents?externalId=" + urllib.parse.quote(external, safe=""))
    check("deleted by its externalId", status == 204 and not pushed(wartung["key"], external), status)
    status, _, models = stack.with_key(wartung["key"], "GET", "/api/v1/models")
    check("the key lists the chat models", status == 200 and isinstance(models, list), f"{status} {models}")
    if full:
        templates = [t["name"] for t in stack.with_key(wartung["key"], "GET", "/api/v1/templates")[2]]
        check("the key lists the tenant's templates", "Störungsbericht" in templates, templates)

    # --- An MCP client searching and reading itself ---------------------------------------------------------------------

    mcp_key = topic_of["Wartung"]["mcpKey"]
    found, error = stack.tool(mcp_key, "search_knowledge",
                              {"query": "Wie oft wird der Hydraulikölfilter der Presse P200 gewechselt?"})
    match = re.search(r'"wartungsplan-presse-p200\.md" \(document (\d+)', found)
    check("search_knowledge finds the maintenance plan (bge-m3 in the stack)", match and not error, found[:400])
    text, error = stack.tool(mcp_key, "read_document", {"document": int(match.group(1))})
    check("read_document reads it by the id the search gave", "400" in text and not error, text[:400])
    found, _ = stack.tool(mcp_key, "search_knowledge", {"query": "Kennwort der Personalplanung der Instandhaltung"})
    check("the area the key does not release is never found", "INTERN-7731" not in found and RESTRICTED_FILE not in found,
          found[:400])
    if full:
        prompts = [p["name"] for p in (stack.mcp(mcp_key, "prompts/list")[1].get("result") or {}).get("prompts", [])]
        check("the tenant's template is a prompt of its MCP server", "stoerungsbericht" in prompts, prompts)
    status, _ = stack.a2a(topic_of["Einkauf"]["mcpKey"], "GetExtendedAgentCard", {})
    check("an MCP key without the A2A door is refused at /a2a", status == 401, status)
else:
    print("skip  the client software and the MCP client - run `setup.py knowledge` for them")

# --- Machines with a key: what the checks above leave out - still no chat model ----------------------------------------

if machines:
    key = machines["Wartung"]["key"]
    status, _, software = stack.with_key(key, "GET", "/api/v1/software")
    check("the key lists the software it may leave out per question", status == 200 and isinstance(software, list),
          f"{status} {software}")
    status, _, tasks = stack.with_key(key, "GET", "/api/v1/tasks")
    check("the key lists the tasks assigned to it", status == 200 and isinstance(tasks, list), f"{status} {tasks}")

    note = b"Notiz der Client-Software: Pruefmarke DATEI-2204.\n"
    status, uploaded = stack.upload_with_key(key, "client-notiz.txt", note)
    check("the client software sends a file for a conversation (201) - a conversation of the key begins",
          status == 201 and uploaded["conversation"]["id"], f"{status} {uploaded}")
    conversation_id = uploaded["conversation"]["id"]
    files = stack.with_key(key, "GET", f"/api/v1/chat/conversations/{conversation_id}/files")[2]
    check("the conversation lists the file", [file["fileName"] for file in files] == ["client-notiz.txt"], files)
    status, _, content = stack.with_key(key, "GET", f"/api/v1/chat/files/{files[0]['id']}/content", raw=True)
    check("the file comes back as it was sent", status == 200 and content.encode() == note, f"{status} {content[:80]}")
    listed = {c["id"] for c in stack.with_key(key, "GET", "/api/v1/chat/conversations")[2]}
    check("the key lists its conversation", conversation_id in listed, listed)
    status, _, messages = stack.with_key(key, "GET", f"/api/v1/chat/conversations/{conversation_id}/messages")
    check("and reads its messages - none yet", status == 200 and messages == [], f"{status} {messages}")
    other = machines["Wartung"]["areaKey"]
    check("another key of the tenant sees neither the conversation nor the file (404)",
          stack.with_key(other, "GET", f"/api/v1/chat/conversations/{conversation_id}/files")[0] == 404
          and stack.with_key(other, "GET", f"/api/v1/chat/files/{files[0]['id']}/content", raw=True)[0] == 404)
    check("the file is deleted",
          stack.with_key(key, "DELETE", f"/api/v1/chat/files/{files[0]['id']}")[0] == 204
          and stack.with_key(key, "GET", f"/api/v1/chat/conversations/{conversation_id}/files")[2] == [])
    check("the conversation is deleted",
          stack.with_key(key, "DELETE", f"/api/v1/chat/conversations/{conversation_id}")[0] == 204
          and conversation_id not in {c["id"] for c in stack.with_key(key, "GET", "/api/v1/chat/conversations")[2]})

    mcp_key = topic_of["Wartung"]["mcpKey"]
    names = {tool["name"] for tool in (stack.mcp(mcp_key, "tools/list")[1].get("result") or {}).get("tools", [])}
    check("the MCP server offers its own tools to the key",
          {"ask_assistant", "list_templates", "search_knowledge", "read_document"} <= names, sorted(names))
    templates, error = stack.tool(mcp_key, "list_templates", {})
    check("list_templates answers", not error and (not full or "Störungsbericht" in templates), templates[:300])
    if (stores or {}).get("taskId"):
        listed, error = stack.tool(mcp_key, "list_tasks", {})
        check("list_tasks names the task assigned to the key", not error and setup.TASK_NAME in listed, listed[:300])
    status, _, _ = stack.with_key(key, "POST", "/mcp", {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, raw=True)
    check("the client software's key is no MCP key (403)", status == 403, status)
    status, _, _ = stack.with_key(mcp_key, "GET", "/api/v1/me", raw=True)
    check("and an MCP key no key of /api/v1", status in (401, 403), status)
    for name in ("assistant", "asker"):
        status, _, described = stack.request(stack.APP_HOST, "GET", f"/a2a/extensions/{name}/1",
                                               headers={"Accept": "text/markdown"}, raw=True)
        check(f"the A2A extension '{name}' is described without a key", status == 200 and described, status)

# --- The installation itself --------------------------------------------------------------------------------------------

status, headers, _ = admin.app("GET", "/watchdog", raw=True)
sent = {name.lower(): value for name, value in headers}
check("the watchdog answers, with the headers every answer carries",
      status == 200 and sent.get("x-frame-options") == "DENY" and sent.get("x-content-type-options") == "nosniff"
      and "frame-ancestors 'none'" in sent.get("content-security-policy", "") and "noindex" in sent.get("x-robots-tag", ""),
      f"{status} {sent}")
status, _, version = admin.app("GET", "/version")
check("the admin reads the version", status == 200 and version, f"{status} {version}")
check("a tenant user does not", anna.app("GET", "/version")[0] in (401, 403))
status, headers, _ = admin.app("GET", "/swagger", raw=True)
target = dict((name.lower(), value) for name, value in headers).get("location", "")
status, _, page = admin.app("GET", urllib.parse.urlsplit(target).path or "/swagger", raw=True)
check("the API documentation is served", status == 200 and "swagger" in page.lower(), f"{status} {target} {page[:120]}")
status, _, page = admin.app("GET", "/app/chat", raw=True)
check("a deep link of the frontend is answered with the page", status == 200 and "<base href" in page, f"{status} {page[:120]}")
status, _, branding = admin.app("GET", "/branding")
check("the installation's look is public", status == 200 and branding.get("name"), f"{status} {branding}")

# --- The file store, its knowledge source, a task's check, the mails ------------------------------------------------------

if stores:
    tenant_path = f"/admin/tenants/{stores['tenantId']}"
    source_path = f"{tenant_path}/knowledge-sources/{stores['sourceId']}"
    checked = admin.app("POST", f"{tenant_path}/file-stores/{stores['storeId']}/check")[2]
    check("the file store proves itself by its confirmed fingerprint and lists its folder",
          checked["status"] == "OK" and {"wissen", "logs"} <= set(checked["entries"]), checked)

    def source():
        return next(s for s in admin.app("GET", f"{tenant_path}/knowledge-sources")[2] if s["id"] == stores["sourceId"])

    def sync():
        """A run by hand, waited for: the source as it ended."""
        before = source()["lastFinishedAt"]
        status, _, body = admin.app("POST", source_path + "/run")
        check("the knowledge source's run starts", status in (200, 202, 204), f"{status} {body}")
        for _ in range(150):
            now = source()
            if not now["running"] and now["lastFinishedAt"] != before:
                return now
            time.sleep(2)
        check("the knowledge source's run ends", False, source())

    def synced():
        """The source's documents, once none waits for its embedding."""
        for _ in range(150):
            documents = [d for d in admin.app("GET", f"{tenant_path}/documents")[2] if d["sourceId"] == stores["sourceId"]]
            if all(d["status"] in ("READY", "FAILED") for d in documents):
                return documents
            time.sleep(2)
        check("the source's documents are embedded", False)

    path, original = setup.SOURCE_FILE
    stack.sftp(f"cat > {stack.STORE_PATH}/{path} <<'END'\n{original}END")
    ran = sync()
    documents = synced()
    check("the source syncs its file in, embedded", ran["lastError"] is None and
          [(d["fileName"], d["status"]) for d in documents] == [("schmierplan-band-f3.md", "READY")], f"{ran} {documents}")
    document_id = documents[0]["id"]
    if machines:
        found, _ = stack.tool(topic_of["Wartung"]["mcpKey"], "search_knowledge",
                              {"query": "Womit werden die Lager des Förderbands F3 geschmiert?"})
        check("the synced file is found like any document", "SCHMIER-5150" in found, found[:400])
    stack.sftp(f"echo 'Ab 2027 alle 100 Betriebsstunden.' >> {stack.STORE_PATH}/{path}")
    ran = sync()
    documents = synced()
    check("a changed file replaces its document in place", ran["lastChanged"] == 1
          and [d["id"] for d in documents] == [document_id], f"{ran} {documents}")
    stack.sftp(f"rm {stack.STORE_PATH}/{path}")
    ran = sync()
    check("a vanished file's document is deleted after the complete listing", ran["lastDeleted"] == 1 and not synced(),
          ran)
    stack.sftp(f"cat > {stack.STORE_PATH}/{path} <<'END'\n{original}END")
    ran = sync()
    check("put back, it is taken in again", ran["lastAdded"] == 1 and len(synced()) == 1, ran)

    wrong = "version: 1\nname: Falsch\ncolour: blau\nsteps: []\n"
    status, _, validated = admin.app("POST", f"{tenant_path}/tasks/validate", {"source": wrong})
    check("a task's mistake is told with its line", status == 200 and not validated["valid"]
          and any(error["line"] == 3 for error in validated["errors"]), f"{status} {validated}")

    before = len(stack.mails())
    status, _, sent = admin.app("POST", "/admin/daily-report/send")
    check("the daily report is sent now", status == 200 and sent["recipients"] == 1, f"{status} {sent}")
    for _ in range(30):
        if len(stack.mails()) > before:
            break
        time.sleep(1)
    reports = [mail for mail in stack.mails()[:len(stack.mails()) - before] if "betrieb@example.com" in mail[0]]
    check("it arrives at MailHog", reports, stack.mails()[:3])
    status, _, log = admin.app("GET", "/admin/application-log")
    check("the application log answers", status == 200, f"{status} {log}")
else:
    print("skip  the file store, its source, the task's check and the mails - run `setup.py stores` for them")

# --- The admin -------------------------------------------------------------------------------------------------------

if stack.WAM:
    clara_browser = stack.Person("clara")
    clara_browser.token = admin.token
    check("the admin's password session does not depend on the person at the proxy - the admin logs out when done",
          clara_browser.app("GET", "/admin/tenants")[0] == 200)
print(f"all checks passed ({stack.MODE})")
