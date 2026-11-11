#!/usr/bin/env python3
"""Conversations with a real model (README.md) - after `setup.py base`, `full` and `models`.

Where measure.py asks single questions, a scenario is a conversation of the general chat: questions that build on the
one before, a question the topic asks back, a file the person sends with it, a file the topic sends back - over A2A
(Wartung) and over MCP (Qualitaet, Einkauf). Each step checks the topics asked, a text the answer must hold, the files
that came back, and where it says so that a web search went out (the Tavily fake's log).

The machines' scenarios (MACHINES, after `setup.py knowledge` - the task's after `setup.py stores` with a model) ask
without a person: the client software at /api/v1 (a file sent with a key that keeps no history, a streamed question
and its follow-up, a conversation another key never sees), an MCP client's ask_assistant, an A2A agent's SendMessage,
and the scheduled task - a new ERROR line in the store's log, a run started by the client software (findings, the
mail at MailHog), a second run reading nothing new, a run through the MCP server's run_task.

Writes local-data/measurements/general-chat/scenarios-<date>.md (ignored by git).

  python3 scenarios.py [--person bernd] [--only id1,id2]
"""
import argparse
import datetime
import json
import pathlib
import subprocess
import time
import uuid

import setup
import stack

HERE = pathlib.Path(__file__).parent
UPLOADS = HERE / "uploads"
REPORT_DIR = HERE.parent.parent / "local-data" / "measurements" / "general-chat"
TAVILY_FAKE = "general-chat-test-tavily-fake"

# Each step: the question, optionally a file of uploads/ sent with it, the topics it must ask, a text the answer must
# hold (any of several, case ignored), the type of a file that must come back, whether a web search must go out.
SCENARIOS = [
    {"id": "a2a-follow-up", "about": "A2A: a question building on the one before",
     "steps": [{"ask": "Wie oft wird der Hydraulikölfilter der Presse P200 gewechselt?",
                "topics": ["Wartung"], "expect": ["400"]},
               {"ask": "Und welches Ersatzteil brauche ich dafür?", "topics": ["Wartung"], "expect": ["HF-200-04"]}]},
    {"id": "a2a-asked-back", "about": "A2A: too little said - asked back (or both cases answered), then the details",
     "steps": [{"ask": "Das Band steht schon wieder mit einem Fehler. Was soll ich tun?",
                "expect": ["Fehlercode", "welches Band", "welcher Fehler", "?"]},
               {"ask": "Band F3, Fehler E17, heute schon viermal in dieser Schicht.", "topics": ["Wartung"],
                "expect": ["Instandhaltung"]}]},
    {"id": "a2a-file-in", "about": "A2A: a file sent to the topic and checked against its knowledge",
     "steps": [{"upload": "stoerprotokoll-f3.csv",
                "ask": "Schau dir mit Wartung das angehängte Störprotokoll an: In welcher Schicht hätte man die "
                       "Instandhaltung rufen müssen?", "topics": ["Wartung"], "expect": ["Spät", "Spaet"]}]},
    {"id": "a2a-file-out", "about": "A2A: the topic creates a file with its template",
     "steps": [{"ask": "Lass Wartung einen Störungsbericht zu Fehler E17 am Band F3 als Word-Datei schreiben.",
                "topics": ["Wartung"], "file": ".docx"}]},
    {"id": "a2a-web", "about": "A2A: a web search handed to the topic - without saying how",
     "steps": [{"ask": "Gibt es neue gesetzliche Prüffristen für Hydraulikpressen wie unsere P200?",
                "topics": ["Wartung"], "web": True}]},
    {"id": "a2a-web-and-code", "about": "A2A: web search and a calculation in one question - without saying how",
     "steps": [{"ask": "Gibt es neue gesetzliche Prüffristen für Hydraulikpressen wie unsere P200? Und wie viele "
                       "Tage liegen bei zwei Schichten à 8 Stunden zwischen zwei Filterwechseln?",
                "topics": ["Wartung"], "expect": ["25"], "web": True}]},
    {"id": "mcp-file-in", "about": "MCP: a complaint sent with the question, checked against the inspection instruction",
     "steps": [{"upload": "reklamation-kunde.txt",
                "ask": "Prüf die angehängte Reklamation gegen unsere Prüfanweisung: Liegt die Abweichung außerhalb "
                       "der Toleranz?", "topics": ["Qualitaet"], "expect": ["0,2"]}]},
    {"id": "mcp-pdf-in", "about": "MCP: a PDF sent with the question - read by Tika - checked against the instruction",
     "steps": [{"upload": "pruefprotokoll-bt220.pdf",
                "ask": "Prüf das angehängte Prüfprotokoll mit der Qualität gegen unsere Prüfanweisung: Welche Messungen "
                       "liegen außerhalb der Toleranz?", "topics": ["Qualitaet"], "expect": ["M-03", "M-06"]}]},
    {"id": "a2a-pdf-in", "about": "A2A: the same PDF to Wartung - a file the topic is sent as it is",
     "steps": [{"upload": "pruefprotokoll-bt220.pdf",
                "ask": "Frag Wartung: Könnten die Abweichungen im angehängten Prüfprotokoll vom Werkzeug der Presse "
                       "kommen, und wann ist die nächste große Wartung der P200?", "topics": ["Wartung"],
                "expect": ["Quartal"]}]},
    {"id": "mcp-file-out-and-again", "about": "MCP: a report as a file, then changed and sent again",
     "steps": [{"upload": "reklamation-kunde.txt",
                "ask": "Lass die Qualität aus der angehängten Reklamation einen 8D-Bericht als PDF erstellen.",
                "topics": ["Qualitaet"], "file": ".pdf"},
               # The general chat may change its own file itself - only the file must come back.
               {"ask": "Ergänze im Bericht noch, dass die Messmittel geprüft wurden, und schick ihn mir noch einmal.",
                "file": ".pdf"}]},
    {"id": "mcp-software-and-code", "about": "MCP: the passed-through software and Python in the topic's sandbox",
     "steps": [{"ask": "Wie hoch ist die Summe aller Rechnungen in der Prüfsoftware seit dem 1. Oktober 2026?",
                "topics": ["Qualitaet"], "expect": ["850,85", "850.85"]}]},
    {"id": "mcp-search-file-in", "about": "MCP (search only): an offer sent with the question, checked against the rules",
     "steps": [{"upload": "angebot-lieferant.txt",
                "ask": "Darf ich das angehängte Angebot so bestellen?", "topics": ["Einkauf"],
                "expect": ["Geschäftsleitung", "drei Angebote"]}]},
    {"id": "mixed-follow-up", "about": "Two ways in one conversation: MCP, then A2A on the answer before",
     "steps": [{"ask": "Welche Toleranz gilt für Bohrungsabstände bei Blechteilen?", "topics": ["Qualitaet"],
                "expect": ["0,2"]},
               {"ask": "Und wer ist bei der Wartung für die Presse P200 zuständig, falls das Werkzeug schuld ist?",
                "topics": ["Wartung"], "expect": ["4711"]}]},
]


# --- The machines: the client software, an MCP client, an A2A agent, the scheduled task ------------------------------------


def machine_step(ask, answer, checks, started, error=None, asked=None):
    return {"ask": ask, "upload": None, "asked": asked or [], "files": [], "checks": checks,
            "seconds": time.monotonic() - started, "error": error, "answer": answer}


def holds(answer, *texts):
    return any(text.lower() in (answer or "").lower() for text in texts)


def stream_answer(text):
    """The answer of /api/v1/chat/stream's events: (answer, conversation id, whether it ended with done)."""
    done = next((data for event, data in stack._events(text) if event == "done"), None) or {}
    return ((done.get("answer") or {}).get("content", ""), (done.get("conversation") or {}).get("id"), bool(done))


def api_file(state):
    key = state["machines"]["Wartung"]["onceKey"]
    question = "In welcher Schicht hätte man laut unseren Regeln die Instandhaltung rufen müssen?"
    started = time.monotonic()
    status, _, body = stack.with_key(key, "POST", "/api/v1/chat", {
        "message": question,
        "files": [{"name": "stoerprotokoll-f3.csv", "content": (UPLOADS / "stoerprotokoll-f3.csv").read_text()}]})
    answer = ((body or {}).get("answer") or {}).get("content", "") if status == 200 else ""
    return [machine_step(question + " (with stoerprotokoll-f3.csv)", answer,
                         {"holds": holds(answer, "Spät", "Spaet"), "nothing kept": status == 200
                          and body.get("conversation") is None}, started, None if status == 200 else f"{status} {body}")]


def api_conversation(state):
    keys = state["machines"]["Wartung"]
    first = "Wie oft wird der Hydraulikölfilter der Presse P200 gewechselt?"
    started = time.monotonic()
    # A key that keeps history names its model - only one that keeps none may leave it to the tenant's default.
    model_id = state["chatModelId"]
    status, _, text = stack.with_key(keys["key"], "POST", "/api/v1/chat/stream", {"modelId": model_id, "message": first},
                                     {"Accept": "text/event-stream, application/json"}, raw=True)
    answer, conversation_id, done = stream_answer(text) if status == 200 else ("", None, False)
    steps = [machine_step(first + " (streamed)", answer or text[:300],
                          {"done": done, "holds": holds(answer, "400"), "kept": conversation_id is not None}, started)]
    second = "Und welches Ersatzteil brauche ich dafür?"
    started = time.monotonic()
    status, _, body = stack.with_key(keys["key"], "POST", "/api/v1/chat",
                                     {"modelId": model_id, "message": second, "conversationId": conversation_id})
    answer = ((body or {}).get("answer") or {}).get("content", "") if status == 200 else ""
    other, _, _ = stack.with_key(keys["areaKey"], "GET", f"/api/v1/chat/conversations/{conversation_id}/messages")
    steps.append(machine_step(second, answer or f"{status} {body}",
                              {"holds": holds(answer, "HF-200-04"), "another key sees nothing": other == 404}, started))
    return steps


def mcp_ask(state):
    key = next(t for t in state["topics"] if t["connection"] == "Qualitaet")["mcpKey"]
    question = "Welche Toleranz gilt für Bohrungsabstände bei Blechteilen?"
    started = time.monotonic()
    answer, error = stack.tool(key, "ask_assistant", {"question": question})
    return [machine_step(question + " (MCP ask_assistant)", answer, {"holds": holds(answer, "0,2"), "no error": not error},
                         started)]


def a2a_send(state):
    key = next(t for t in state["topics"] if t["connection"] == "Wartung")["mcpKey"]
    question = "Das Förderband F3 steht mit E17 - was tun?"
    started = time.monotonic()
    status, message = stack.a2a(key, "SendMessage", {"message": {"role": "ROLE_USER", "messageId": str(uuid.uuid4()),
                                                                "parts": [{"text": question}]}})
    answer = json.dumps((message or {}).get("result"), ensure_ascii=False)
    return [machine_step(question + " (A2A SendMessage)", answer,
                         {"answered": status == 200 and "result" in (message or {}),
                          "holds": holds(answer, "Lichtschranke")}, started)]


def append_error(code):
    line = f"{datetime.datetime.now():%Y-%m-%d %H:%M:%S} ERROR P200 Hydraulikdruck zu niedrig, Code {code}"
    stack.sftp(f"echo '{line}' >> {stack.STORE_PATH}/logs/p200.log")


def api_run(key, task_id):
    """A run started by the client software and waited for: the run as /api/v1 reports it."""
    status, _, started = stack.with_key(key, "POST", f"/api/v1/tasks/{task_id}/runs")
    if status != 202:
        return {"state": f"not started: {status} {started}", "findings": []}
    for _ in range(300):
        run = stack.with_key(key, "GET", f"/api/v1/tasks/{task_id}/runs/{started['runId']}")[2]
        if run["state"] not in ("QUEUED", "RUNNING"):
            return run
        time.sleep(2)
    return run


def task_runs(state):
    task_id = (state.get("stores") or {}).get("taskId")
    if not task_id:
        return []
    key = state["machines"]["Wartung"]["key"]
    code = f"HY-{datetime.datetime.now():%H%M%S}"
    append_error(code)
    mails_before = len(stack.mails())
    started = time.monotonic()
    run = api_run(key, task_id)
    errors = [f for f in run["findings"] if f["status"] != "ok" and f["severity"].lower() == "error"]
    evidence = json.dumps(errors, ensure_ascii=False)
    time.sleep(5)
    mailed = [m for m in stack.mails()[:len(stack.mails()) - mails_before] if m[1] == "Befund Presse P200"]
    steps = [machine_step(f"run of {setup.TASK_NAME} via /api/v1 after an ERROR line with {code}", evidence,
                          {"done": run["state"] == "DONE", "finding": code in evidence, "mailed": bool(mailed)}, started)]
    mails_before = len(stack.mails())
    started = time.monotonic()
    again = api_run(key, task_id)
    time.sleep(5)
    steps.append(machine_step("the same task again, nothing new in the log", json.dumps(again["findings"]),
                              {"done": again["state"] == "DONE", "no finding": code not in json.dumps(again["findings"]),
                               "no mail": len(stack.mails()) == mails_before}, started))
    code = f"HY-{datetime.datetime.now():%H%M%S}-M"
    append_error(code)
    mcp_key = next(t for t in state["topics"] if t["connection"] == "Wartung")["mcpKey"]
    started = time.monotonic()
    answer, error = stack.tool(mcp_key, "run_task", {"task": setup.TASK_NAME})
    steps.append(machine_step(f"run_task over MCP after an ERROR line with {code}", answer,
                              {"no error": not error, "finding": code in answer}, started))
    return steps


MACHINES = [
    {"id": "api-file", "about": "/api/v1/chat: a file sent with the question by a key that keeps no history",
     "run": api_file},
    {"id": "api-conversation", "about": "/api/v1/chat/stream, then a follow-up - a conversation of the key only",
     "run": api_conversation},
    {"id": "mcp-ask", "about": "An MCP client asks the topic's assistant (ask_assistant)", "run": mcp_ask},
    {"id": "a2a-send", "about": "An A2A agent asks the topic's assistant (SendMessage)", "run": a2a_send},
    {"id": "task-runs", "about": "The scheduled task: started by the client software, again, and through run_task",
     "run": task_runs},
]


def web_searches():
    """The searches the Tavily fake has logged so far."""
    log = subprocess.run(["podman", "logs", TAVILY_FAKE], capture_output=True, text=True)
    return sum(1 for line in (log.stdout + log.stderr).splitlines() if line.startswith('{"search"'))


def run_step(person, model_id, step, conversation_id):
    if step.get("upload"):
        conversation_id = person.upload(UPLOADS / step["upload"], conversation_id)["id"]
    searches = web_searches()
    result = person.ask(model_id, step["ask"], conversation_id=conversation_id)
    asked = sorted({software for _, software in result["tools"] if software})
    answer = result.get("answer") or ""
    checks = {
        "topics": set(step.get("topics", [])) <= set(asked),
        "holds": not step.get("expect") or any(text.lower() in answer.lower() for text in step["expect"]),
        "file": not step.get("file") or any(name.lower().endswith(step["file"]) for name in result["files"]),
        "web": not step.get("web") or web_searches() > searches,
    }
    conversation = (result.get("conversation") or {}).get("id") or conversation_id
    return conversation, {"ask": step["ask"], "upload": step.get("upload"), "asked": asked, "files": result["files"],
                          "checks": checks, "seconds": result["seconds"], "error": result.get("error"),
                          "answer": answer}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--person", default="bernd")
    parser.add_argument("--only", default="")
    arguments = parser.parse_args()
    state = json.loads(stack.STATE_FILE.read_text())
    model_id = state.get("chatModelId")
    if not model_id:
        raise SystemExit("run setup.py models first")
    person = stack.Person(arguments.person)
    status, body = person.sign_in(state["general"]["tenantId"])
    if status != 200:
        raise SystemExit(f"{arguments.person} could not sign in at the general chat: {status} {body}")
    only = {item for item in arguments.only.split(",") if item}
    results = []
    for scenario in SCENARIOS:
        if only and scenario["id"] not in only:
            continue
        conversation_id, steps = None, []
        for step in scenario["steps"]:
            conversation_id, done = run_step(person, model_id, step, conversation_id)
            steps.append(done)
            failed = [name for name, ok in done["checks"].items() if not ok]
            print(f"{scenario['id']} step {len(steps)}: asked {done['asked'] or '-'}, files {done['files'] or '-'}, "
                  f"{'ok' if not failed else 'CHECK ' + ', '.join(failed)}, {done['seconds']:.1f} s")
        results.append({**scenario, "results": steps})
    for scenario in MACHINES if state.get("machines") else []:
        if only and scenario["id"] not in only:
            continue
        steps = scenario["run"](state)
        for number, done in enumerate(steps, 1):
            failed = [name for name, ok in done["checks"].items() if not ok]
            print(f"{scenario['id']} step {number}: {'ok' if not failed else 'CHECK ' + ', '.join(failed)}, "
                  f"{done['seconds']:.1f} s")
        if steps:
            results.append({**scenario, "results": steps})
    write(results, arguments.person)


def write(results, person):
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    now = datetime.datetime.now()
    path = REPORT_DIR / f"scenarios-{now:%Y-%m-%d-%H%M}.md"
    lines = [f"# General chat over topic tenants - scenarios {now:%Y-%m-%d %H:%M}", "",
             f"Asked as {person} in the test stack (devtools/general-chat-test), one conversation per scenario.", "",
             "| Scenario | Step | Sent | Topics asked | Files back | Checks | Seconds |",
             "|---|---|---|---|---|---|---|"]
    passed = total = 0
    for scenario in results:
        for number, step in enumerate(scenario["results"], 1):
            checks = step["checks"]
            total += 1
            passed += all(checks.values())
            failed = [name for name, ok in checks.items() if not ok]
            lines.append(f"| {scenario['id']} | {number} | {step['upload'] or '-'} | {', '.join(step['asked']) or '-'} | "
                         f"{', '.join(step['files']) or '-'} | {'ok' if not failed else 'NO: ' + ', '.join(failed)} | "
                         f"{step['seconds']:.1f} |")
    lines += ["", f"Steps passed: {passed}/{total}.", "", "## Conversations", ""]
    for scenario in results:
        lines += [f"### {scenario['id']} - {scenario['about']}", ""]
        for step in scenario["results"]:
            lines += [f"**Question{' (with ' + step['upload'] + ')' if step['upload'] else ''}:** {step['ask']}", "",
                      step["error"] or step["answer"], ""]
    path.write_text("\n".join(lines))
    print(f"written: {path}")


if __name__ == "__main__":
    main()
