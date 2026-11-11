"""Runs every scenario of the test agent against the agent itself (README.md) - plain HTTP and JSON, as A2A 1.0's
JSON-RPC binding writes them, so a broken scenario shows before Zirmo is pointed at it.

    .venv/bin/python -I check.py                      the agent at http://127.0.0.1:19195
    TEST_AGENT_ADDRESS=http://127.0.0.1:19196 .venv/bin/python -I check.py
    TEST_AGENT_DEMO=1 .venv/bin/python -I check.py    the demo (an agent started with TEST_AGENT_DEMO=1)
"""
import json
import os
import urllib.request
import uuid

ADDRESS = os.environ.get("TEST_AGENT_ADDRESS", "http://127.0.0.1:19195")
KEY = os.environ.get("TEST_AGENT_KEY", "")
ASKER = "urn:alpdesk-ai:a2a:asker:1"
DEMO = os.environ.get("TEST_AGENT_DEMO", "") not in ("", "0")


def post(method: str, params: dict, stream: bool = False):
    body = json.dumps({"jsonrpc": "2.0", "id": str(uuid.uuid4()), "method": method, "params": params}).encode()
    headers = {"Content-Type": "application/json", "A2A-Version": "1.0",
               "Accept": "text/event-stream" if stream else "application/json"}
    if KEY:
        headers["Authorization"] = f"Bearer {KEY}"
    request = urllib.request.Request(f"{ADDRESS}/a2a", data=body, headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        text = response.read().decode()
    if not stream:
        return json.loads(text)
    return [json.loads(line[5:]) for line in text.splitlines() if line.startswith("data:")]


def message(text: str, parts: list = None, metadata: dict = None, context_id: str = None, task_id: str = None) -> dict:
    result = {"role": "ROLE_USER", "messageId": str(uuid.uuid4()), "parts": [{"text": text}] + (parts or [])}
    if metadata:
        result["metadata"] = metadata
    if context_id:
        result["contextId"] = context_id
    if task_id:
        result["taskId"] = task_id
    return result


def texts(parts: list) -> str:
    return "".join(part.get("text", "") for part in parts)


def check(name: str, condition: bool, detail: object) -> None:
    print(("ok    " if condition else "FAILED") + "  " + name + ("" if condition else f": {detail}"))
    if not condition:
        raise SystemExit(1)


def streamed_answer(events: list) -> str:
    return "".join(texts(event["result"]["artifactUpdate"]["artifact"]["parts"])
                   for event in events if "artifactUpdate" in event["result"]
                   and event["result"]["artifactUpdate"]["artifact"].get("name") == "answer")


def main_demo() -> None:
    """The demo's questions as a user of the chat asks them - no markers."""
    events = post("SendStreamingMessage", {"message": message("Wie steht Auftrag 4711?")}, stream=True)
    steps = [texts(event["result"]["statusUpdate"]["status"]["message"]["parts"]) for event in events
             if "statusUpdate" in event["result"] and "message" in event["result"]["statusUpdate"]["status"]]
    check("an order's state, streamed with its steps", "in Fertigung" in streamed_answer(events)
          and "Suche Auftrag 4711 im ERP …" in steps, events)

    asked = post("SendMessage", {"message": message("Buche bitte die Rechnung R-2026-118")})["result"]["task"]
    check("a booking asks for the account", asked["status"]["state"] == "TASK_STATE_INPUT_REQUIRED"
          and "R-2026-118" in texts(asked["status"]["message"]["parts"]), asked)
    finished = post("SendMessage", {"message": message("Auf 4200 bitte", context_id=asked["contextId"],
                                                       task_id=asked["id"])})["result"]["task"]
    check("the booking done with the account given", finished["status"]["state"] == "TASK_STATE_COMPLETED"
          and "Konto **4200**" in texts(finished["artifacts"][0]["parts"]), finished)

    report = post("SendMessage", {"message": message("Erstelle mir den Monatsbericht")})["result"]["task"]
    names = [part.get("filename") for artifact in report["artifacts"] for part in artifact["parts"] if "raw" in part]
    check("the monthly report as a CSV", names == ["auftraege-2026-11.csv"], report)

    hello = post("SendMessage", {"message": message("Hallo", metadata={ASKER: {"user": "m.muster"}})})
    greeting = texts(hello["result"]["task"]["artifacts"][0]["parts"])
    check("a greeting naming the user", greeting.startswith("Guten Tag, m.muster!"), hello)
    unknown = post("SendMessage", {"message": message("wie steht es um aufrag 27")})["result"]["task"]
    check("an unknown order said so", "finde ich im ERP nicht" in texts(unknown["artifacts"][0]["parts"]), unknown)

    off = {"skills": ["rechnungen"]}
    declined = post("SendMessage", {"message": message("Wie steht Auftrag 4711?", metadata={ASKER: off})})["result"]["task"]
    check("a topic switched off declined, not answered with another",
          "abgewählt" in texts(declined["artifacts"][0]["parts"]), declined)

    card_answer = post("SendMessage", {"message": message("zeig mir deine agent card")})["result"]["task"]
    check("its card described", "Agent-Card" in texts(card_answer["artifacts"][0]["parts"]), card_answer)
    print("the demo answered as expected")


def main() -> None:
    with urllib.request.urlopen(f"{ADDRESS}/.well-known/agent-card.json", timeout=10) as response:
        card = json.loads(response.read().decode())
    check("the card names its skills and /a2a", len(card["skills"]) == 6
          and card["supportedInterfaces"][0]["url"].endswith("/a2a"), card)

    answer = post("SendMessage", {"message": message("Hallo")})
    check("a complete message", texts(answer["result"]["message"]["parts"]) == "Antwort des Testagenten: Hallo", answer)

    events = post("SendStreamingMessage", {"message": message("Wie geht es [[stream]]")}, stream=True)
    streamed = "".join(texts(event["result"]["artifactUpdate"]["artifact"]["parts"])
                       for event in events if "artifactUpdate" in event["result"])
    check("a streamed task", streamed == "Gestreamte Antwort des Testagenten: Wie geht es"
          and events[-1]["result"]["statusUpdate"]["status"]["state"] == "TASK_STATE_COMPLETED", events)

    asked = post("SendMessage", {"message": message("Buchen [[ask-back Welches Konto?]]")})["result"]["task"]
    check("a question back", asked["status"]["state"] == "TASK_STATE_INPUT_REQUIRED"
          and texts(asked["status"]["message"]["parts"]) == "Welches Konto?", asked)
    finished = post("SendMessage", {"message": message("4711", context_id=asked["contextId"], task_id=asked["id"])})
    task = finished["result"]["task"]
    check("the question back answered", task["status"]["state"] == "TASK_STATE_COMPLETED"
          and "Fortgesetzt: 4711" in json.dumps(task["artifacts"]), finished)

    files = post("SendMessage", {"message": message("Bericht [[files-out]]")})["result"]["task"]
    names = [part.get("filename") for artifact in files["artifacts"] for part in artifact["parts"] if "raw" in part]
    check("files out", names == ["bericht.txt", "punkt.png"], files)

    raw = {"raw": "SW5oYWx0", "filename": "notiz.txt", "mediaType": "text/plain"}
    received = post("SendMessage", {"message": message("Lies [[files-in]]", parts=[raw])})
    check("files in", texts(received["result"]["message"]["parts"]) == "Dateien erhalten: notiz.txt", received)

    refused = post("SendMessage", {"message": message("Nein [[fail]]")})
    check("a JSON-RPC error", refused.get("error", {}).get("code") == -32602, refused)

    asker = {"tenant": "Musterfirma", "user": "m.muster", "groups": ["Vertrieb"], "skills": ["antwort"]}
    echoed = post("SendMessage", {"message": message("Wer [[echo-metadata]]", metadata={ASKER: asker})})
    check("the asker's metadata", json.loads(texts(echoed["result"]["message"]["parts"])) == asker, echoed)

    print("every scenario answered as expected ([[slow]] and [[search ...]] are tried by hand)")


if __name__ == "__main__":
    main_demo() if DEMO else main()
