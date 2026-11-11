"""An A2A agent for trying out Zirmo's A2A client by hand (README.md) - on the official Python a2a-sdk, with
invented data. One skill per scenario; the scenario is also picked by a marker in the question, so the chat's tool
way (no skill named) reaches every one of them:

  (none)              a complete message at once: "Antwort des Testagenten: <question>"
  [[stream]]          a Task: working status, the answer appended to an artifact word by word, completed
  [[ask-back <text>]] input-required with <text>; the next message in the context finishes it ("Fortgesetzt: ...")
  [[files-out]]       a text file and a small PNG as artifacts
  [[files-in]]        names the files (raw parts) the message brought
  [[fail]]            a JSON-RPC error
  [[slow]]            answers after TEST_AGENT_SLOW_SECONDS (default 30) - for the timeout
  [[echo-metadata]]   the metadata of urn:alpdesk-ai:a2a:asker:1 it got, as JSON
  [[search <query>]]  the way back: search_knowledge at ALPDESK_MCP_URL with ALPDESK_MCP_KEY, the hits as the answer

With TEST_AGENT_DEMO=1 it is the "Auftragsassistent" of the invented Musterfirma instead (DemoAgent below): no
markers, plain questions about orders, invoices and reports, answered as a real agent would - for showing the chat.

Started by start.sh at http://127.0.0.1:19195 (the card at /.well-known/agent-card.json, JSON-RPC at /a2a); a bearer
key is checked when TEST_AGENT_KEY is set. Every request is logged (method, whether a key came, the metadata).
"""
import asyncio
import base64
import json
import logging
import os
import re
import urllib.request

import uvicorn
from starlette.applications import Starlette
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse

from a2a.helpers.proto_helpers import (get_message_text, new_raw_part, new_task, new_text_message,
                                       new_text_part)
from a2a.server.agent_execution import AgentExecutor, RequestContext
from a2a.server.request_handlers import DefaultRequestHandlerV2
from a2a.server.routes import create_agent_card_routes, create_jsonrpc_routes
from a2a.server.tasks import InMemoryTaskStore, TaskUpdater
from a2a.types import (AgentCapabilities, AgentCard, AgentInterface, AgentProvider, AgentSkill, InvalidParamsError,
                       Role, TaskState)

ASKER = "urn:alpdesk-ai:a2a:asker:1"
HOST = os.environ.get("TEST_AGENT_HOST", "127.0.0.1")
PORT = int(os.environ.get("TEST_AGENT_PORT", "19195"))
ADDRESS = os.environ.get("TEST_AGENT_ADDRESS", f"http://127.0.0.1:{PORT}")
KEY = os.environ.get("TEST_AGENT_KEY", "")
SLOW_SECONDS = float(os.environ.get("TEST_AGENT_SLOW_SECONDS", "30"))
DEMO = os.environ.get("TEST_AGENT_DEMO", "") not in ("", "0")
# A tiny PNG (1x1, invented) for the files going out.
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=")

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("a2a-test-agent")

SKILLS = [
    ("antwort", "Antwort", "Beantwortet eine Frage sofort mit einer Nachricht"),
    ("stream", "Gestreamt", "Antwortet als Aufgabe, Wort für Wort"),
    ("rueckfrage", "Rückfrage", "Fragt zurück und macht mit der nächsten Nachricht weiter"),
    ("dateien", "Dateien", "Gibt eine Textdatei und ein Bild zurück"),
    ("metadaten", "Metadaten", "Nennt, wer gefragt hat (Mandant, Benutzer, Gruppen, Fähigkeiten)"),
    ("suche", "Suche im Firmenwissen", "Sucht über den MCP-Server des Mandanten zurück"),
]


def card() -> AgentCard:
    name, description, skills = (DEMO_NAME, DEMO_DESCRIPTION, DEMO_SKILLS) if DEMO else (
        "Testagent", "Ein Agent mit erfundenen Daten zum Ausprobieren des A2A-Clients von Zirmo", SKILLS)
    return AgentCard(
        name=name,
        description=description,
        version="1.0.0",
        provider=AgentProvider(organization="Musterfirma", url="https://example.com"),
        capabilities=AgentCapabilities(streaming=True),
        default_input_modes=["text/plain"],
        default_output_modes=["text/plain"],
        skills=[AgentSkill(id=id, name=name, description=description, tags=["demo" if DEMO else "test"])
                for id, name, description in skills],
        supported_interfaces=[AgentInterface(protocol_binding="JSONRPC", url=f"{ADDRESS}/a2a",
                                             protocol_version="1.0")],
    )


def asker_of(message) -> dict:
    """Our extension's entry of the message's metadata, as a plain dict."""
    from google.protobuf.json_format import MessageToDict
    metadata = MessageToDict(message.metadata) if message.HasField("metadata") else {}
    return metadata.get(ASKER, {})


def skills_of(message) -> list:
    return list(asker_of(message).get("skills", []))


def search_back(query: str) -> str:
    """The way back: the tenant's MCP server, search_knowledge with the key kept here."""
    url, key = os.environ.get("ALPDESK_MCP_URL"), os.environ.get("ALPDESK_MCP_KEY")
    if not url or not key:
        return "Kein Rückweg eingerichtet (ALPDESK_MCP_URL, ALPDESK_MCP_KEY)."
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                       "params": {"name": "search_knowledge", "arguments": {"query": query}}}).encode()
    request = urllib.request.Request(url, data=body, headers={
        "Authorization": f"Bearer {key}", "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream"})
    with urllib.request.urlopen(request, timeout=60) as response:
        answer = json.loads(response.read().decode())
    return "\n".join(item.get("text", "") for item in answer.get("result", {}).get("content", []))


class TestAgent(AgentExecutor):

    async def execute(self, context: RequestContext, event_queue) -> None:
        message = context.message
        question = context.get_user_input()
        skills = skills_of(message)
        log.info("question with skills %s and metadata %s", skills, json.dumps(asker_of(message)))
        # One skill named is a pick (the tool way's, or a chat with one skill left on); several are only what is allowed.
        skill = skills[0] if len(skills) == 1 else None
        current = context.current_task
        if current is not None and current.status.state == TaskState.TASK_STATE_INPUT_REQUIRED:
            updater = TaskUpdater(event_queue, context.task_id, context.context_id)
            await updater.start_work()
            await updater.add_artifact([new_text_part(f"Fortgesetzt: {question}")], name="answer")
            await updater.complete()
            return
        if "[[slow]]" in question:
            await asyncio.sleep(SLOW_SECONDS)
        if "[[fail]]" in question:
            raise InvalidParamsError(message="Der Testagent lehnt diese Frage ab")
        if "[[echo-metadata]]" in question or skill == "metadaten":
            await event_queue.enqueue_event(new_text_message(json.dumps(asker_of(message), ensure_ascii=False),
                                                             context_id=context.context_id))
            return
        search = re.search(r"\[\[search ([^\]]+)\]\]", question)
        if search or skill == "suche":
            query = search.group(1) if search else question
            # On a thread of its own: a blocking call would hold every other request of the agent.
            hits = await asyncio.to_thread(search_back, query)
            await event_queue.enqueue_event(new_text_message(hits, context_id=context.context_id))
            return
        if "[[files-in]]" in question:
            names = [part.filename for part in message.parts if part.HasField("raw")]
            await event_queue.enqueue_event(new_text_message("Dateien erhalten: " + ", ".join(names),
                                                             context_id=context.context_id))
            return
        ask_back = re.search(r"\[\[ask-back ([^\]]+)\]\]", question)
        if ask_back or skill == "rueckfrage":
            await event_queue.enqueue_event(new_task(context.task_id, context.context_id, TaskState.TASK_STATE_SUBMITTED))
            updater = TaskUpdater(event_queue, context.task_id, context.context_id)
            text = ask_back.group(1) if ask_back else "Für welches Konto?"
            await updater.requires_input(updater.new_agent_message([new_text_part(text)]))
            return
        clean = re.sub(r"\[\[[^\]]*\]\]", "", question).strip()
        if "[[files-out]]" in question or skill == "dateien":
            await event_queue.enqueue_event(new_task(context.task_id, context.context_id, TaskState.TASK_STATE_SUBMITTED))
            updater = TaskUpdater(event_queue, context.task_id, context.context_id)
            await updater.add_artifact([new_text_part("Hier sind die Dateien.")], name="answer")
            await updater.add_artifact([new_raw_part(f"Bericht zu: {clean}\n".encode(), "text/plain", "bericht.txt")],
                                       name="bericht.txt")
            await updater.add_artifact([new_raw_part(PNG, "image/png", "punkt.png")], name="punkt.png")
            await updater.complete()
            return
        if "[[stream]]" in question or skill == "stream":
            await event_queue.enqueue_event(new_task(context.task_id, context.context_id, TaskState.TASK_STATE_SUBMITTED))
            updater = TaskUpdater(event_queue, context.task_id, context.context_id)
            await updater.start_work(updater.new_agent_message([new_text_part("Der Testagent sucht")]))
            words = f"Gestreamte Antwort des Testagenten: {clean}".split(" ")
            for i, word in enumerate(words):
                await updater.add_artifact([new_text_part(("" if i == 0 else " ") + word)], artifact_id="answer",
                                           name="answer", append=i > 0, last_chunk=i == len(words) - 1)
                await asyncio.sleep(0.05)
            await updater.complete()
            return
        await event_queue.enqueue_event(new_text_message(f"Antwort des Testagenten: {clean}",
                                                         context_id=context.context_id))

    async def cancel(self, context: RequestContext, event_queue) -> None:
        log.info("cancel of task %s", context.task_id)
        updater = TaskUpdater(event_queue, context.task_id, context.context_id)
        await updater.cancel()


# --- The demo: the order assistant of the invented Musterfirma --------------------------------------------------------

DEMO_NAME = "Auftragsassistent"
DEMO_DESCRIPTION = ("Kennt die Aufträge, Rechnungen und Monatsberichte der Musterfirma GmbH - nennt den Stand eines "
                    "Auftrags, offene Rechnungen, bucht Rechnungen und erstellt Berichte.")
DEMO_SKILLS = [
    ("auftraege", "Aufträge", "Stand, Liefertermin und Positionen eines Auftrags"),
    ("rechnungen", "Rechnungen", "Offene Rechnungen nennen und Rechnungen buchen"),
    ("berichte", "Berichte", "Die Monatsübersicht der Aufträge als Datei"),
]
ORDERS = {
    "4711": {"customer": "Beispiel AG", "item": "Schaltschrank SK-200", "quantity": 3, "state": "in Fertigung",
             "progress": "Gehäuse fertig, Verdrahtung zu 60 %", "delivery": "20.11.2026", "value": "18.450,00 €"},
    "4712": {"customer": "Beispiel Nord GmbH", "item": "Wartung Förderanlage FA-3", "quantity": 1, "state": "versandt",
             "progress": "Techniker-Einsatz am 04.11. abgeschlossen, Bericht verschickt", "delivery": "04.11.2026",
             "value": "2.340,00 €"},
    "4713": {"customer": "Muster & Söhne KG", "item": "Ersatzteilpaket Antrieb", "quantity": 12,
             "state": "wartet auf Material", "progress": "Getriebemotoren beim Lieferanten bestellt, Eingang KW 48",
             "delivery": "03.12.2026", "value": "6.912,00 €"},
}
INVOICES = {
    "R-2026-117": {"customer": "Beispiel AG", "amount": "1.250,00 €", "due": "16.11.2026"},
    "R-2026-118": {"customer": "Beispiel Nord GmbH", "amount": "2.340,00 €", "due": "18.11.2026"},
}
SKILL_OF = {"auftraege": "auftraege", "rechnungen": "rechnungen", "booking": "rechnungen", "berichte": "berichte"}
ACCOUNTS = {"4400": "Erlöse 19 % USt", "4300": "Erlöse 7 % USt", "4200": "Erlöse Wartung"}


async def stream_text(updater: TaskUpdater, text: str) -> None:
    """The answer piece by piece into the artifact "answer" - as a model writes it, Markdown and line breaks kept."""
    pieces = re.findall(r"\S+\s*", text)
    for i, piece in enumerate(pieces):
        await updater.add_artifact([new_text_part(piece)], artifact_id="answer", name="answer", append=i > 0,
                                   last_chunk=i == len(pieces) - 1)
        await asyncio.sleep(0.03)


async def working(updater: TaskUpdater, text: str, seconds: float = 0.8) -> None:
    """A status line the chat shows as a step of the agent - and the time such a lookup takes."""
    await updater.update_status(TaskState.TASK_STATE_WORKING, updater.new_agent_message([new_text_part(text)]))
    await asyncio.sleep(seconds)


def order_answer(number: str) -> str:
    order = ORDERS[number]
    return (f"**Auftrag {number}** für die {order['customer']} - Stand: **{order['state']}**.\n\n"
            f"| Angabe | Wert |\n|---|---|\n| Position | {order['quantity']} × {order['item']} |\n"
            f"| Stand | {order['progress']} |\n| Liefertermin | {order['delivery']} |\n"
            f"| Auftragswert | {order['value']} |\n\n"
            + ("Der Liefertermin ist nach heutigem Stand zu halten. " if order["state"] == "in Fertigung" else "")
            + ("Der Termin hängt am Materialeingang - ich melde mich, sobald die Motoren da sind. "
               if order["state"] == "wartet auf Material" else "")
            + "Soll ich der Kundin oder dem Kunden eine Statusmeldung vorbereiten?")


def orders_overview() -> str:
    rows = "".join(f"| {number} | {order['customer']} | {order['item']} | {order['state']} | {order['delivery']} |\n"
                   for number, order in ORDERS.items())
    return ("Aktuell sind drei Aufträge offen oder gerade abgeschlossen:\n\n"
            "| Auftrag | Kunde | Gegenstand | Stand | Liefertermin |\n|---|---|---|---|---|\n" + rows
            + "\nKritisch ist **4713**: Das Material fehlt noch, der Liefertermin 03.12. ist knapp.")


def invoices_overview() -> str:
    rows = "".join(f"| {number} | {invoice['customer']} | {invoice['amount']} | {invoice['due']} |\n"
                   for number, invoice in INVOICES.items())
    return ("Offen sind zwei Rechnungen:\n\n| Rechnung | Kunde | Betrag | Fällig |\n|---|---|---|---|\n" + rows
            + "\nBeide sind noch nicht fällig. Soll ich eine davon buchen?")


def booked(invoice_number: str, account: str) -> str:
    invoice = INVOICES[invoice_number]
    return (f"Erledigt: Die Rechnung **{invoice_number}** der {invoice['customer']} über {invoice['amount']} ist auf "
            f"Konto **{account}** ({ACCOUNTS.get(account, 'Sachkonto')}) gebucht. Buchungsnummer **B-2026-"
            f"{int(account) % 1000 + 3000}**.")


def monthly_report() -> bytes:
    lines = ["Auftrag;Kunde;Gegenstand;Menge;Stand;Liefertermin;Auftragswert"]
    for number, order in ORDERS.items():
        lines.append(";".join([number, order["customer"], order["item"], str(order["quantity"]), order["state"],
                               order["delivery"], order["value"]]))
    return ("\n".join(lines) + "\n").encode()


class DemoAgent(AgentExecutor):
    """
    Picks the topic from the question's words, as an agent behind a workflow would. The asker's skills are what it may
    do here (the chat's topics left on; the tool way names one): a topic switched off is declined, not answered with
    another. Without metadata (a client of its own) everything is allowed.
    """

    # The invoice waiting for its account, by the task that asked back.
    waiting: dict[str, str] = {}

    async def execute(self, context: RequestContext, event_queue) -> None:
        message = context.message
        question = context.get_user_input()
        asker = asker_of(message)
        allowed = list(asker["skills"]) if "skills" in asker else [skill for skill, _, _ in DEMO_SKILLS]
        log.info("demo question with metadata %s", json.dumps(asker))
        current = context.current_task
        if current is None:
            await event_queue.enqueue_event(new_task(context.task_id, context.context_id,
                                                     TaskState.TASK_STATE_SUBMITTED))
        updater = TaskUpdater(event_queue, context.task_id, context.context_id)
        if current is not None and current.status.state == TaskState.TASK_STATE_INPUT_REQUIRED:
            await self.book(updater, self.waiting.pop(context.task_id, "R-2026-117"), question, context.task_id)
            return
        files = [part for part in message.parts if part.HasField("raw")]
        topic = "files" if files else self.topic(question)
        if topic is None and len(allowed) == 1:
            topic = allowed[0]
        if topic in SKILL_OF and SKILL_OF[topic] not in allowed:
            await self.declined(updater, SKILL_OF[topic], allowed)
        elif topic == "files":
            await self.read_files(updater, files)
        elif topic == "booking":
            await self.book(updater, self.invoice_named(question), question, context.task_id)
        elif topic == "berichte":
            await self.report(updater)
        elif topic == "rechnungen":
            await working(updater, "Lese die offenen Posten aus der Buchhaltung …")
            await stream_text(updater, invoices_overview())
            await updater.complete()
        elif topic == "auftraege":
            await self.order(updater, question)
        elif topic == "card":
            await self.describe(updater, allowed)
        else:
            await self.introduce(updater, asker)

    @staticmethod
    def topic(question: str) -> str | None:
        lower = question.lower()
        if re.search(r"\bbuch(e|en|st|t)?\b|verbuch", lower):
            return "booking"
        if "bericht" in lower or "export" in lower or "übersicht" in lower and "auftr" in lower:
            return "berichte"
        if "rechnung" in lower or "offene posten" in lower:
            return "rechnungen"
        if re.search(r"auf?t?r?a?g|order|lieferung|liefertermin", lower) or re.search(r"\b\d{1,6}\b", lower):
            return "auftraege"
        if re.search(r"karte|card|fähigkeit|faehigkeit|was kannst|wer bist", lower):
            return "card"
        return None

    async def declined(self, updater: TaskUpdater, skill: str, allowed: list) -> None:
        name = next(label for id, label, _ in DEMO_SKILLS if id == skill)
        open_ones = [label for id, label, _ in DEMO_SKILLS if id in allowed]
        await stream_text(updater, f"Zu **{name}** darf ich in diesem Chat nichts sagen - das Thema ist abgewählt. "
                          + (f"Freigegeben sind: {', '.join(open_ones)}." if open_ones
                             else "Gerade ist kein Thema freigegeben.")
                          + " Sie schalten es unten bei „Themen“ wieder ein.")
        await updater.complete()

    async def describe(self, updater: TaskUpdater, allowed: list) -> None:
        lines = "\n".join(f"- **{label}** - {description}" + ("" if id in allowed else " *(hier abgewählt)*")
                          for id, label, description in DEMO_SKILLS)
        await stream_text(updater, f"Ich bin der **{DEMO_NAME}**, ein A2A-Agent der Musterfirma GmbH. {DEMO_DESCRIPTION}"
                          f"\n\nMeine Agent-Card nennt diese Fähigkeiten:\n\n{lines}\n\nDie Karte liegt unter "
                          f"`{ADDRESS}/.well-known/agent-card.json`.")
        await updater.complete()

    async def order(self, updater: TaskUpdater, question: str) -> None:
        number = next((n for n in ORDERS if n in question), None)
        asked = re.search(r"\b\d{1,6}\b", question)
        if number is None and asked:
            await working(updater, f"Suche Auftrag {asked.group(0)} im ERP …")
            await stream_text(updater, f"Einen Auftrag **{asked.group(0)}** finde ich im ERP nicht. Offen oder gerade "
                              f"abgeschlossen sind {', '.join(ORDERS)} - meinten Sie einen davon?")
        elif number is None:
            await working(updater, "Suche die offenen Aufträge im ERP …")
            await stream_text(updater, orders_overview())
        else:
            await working(updater, f"Suche Auftrag {number} im ERP …")
            await working(updater, "Lese den Fertigungsstand und den Liefertermin …")
            await stream_text(updater, order_answer(number))
        await updater.complete()

    @staticmethod
    def invoice_named(question: str) -> str:
        named = re.search(r"R-\d{4}-\d+", question)
        return named.group(0) if named and named.group(0) in INVOICES else "R-2026-117"

    async def book(self, updater: TaskUpdater, invoice_number: str, text: str, task_id: str = "") -> None:
        account = re.search(r"\b(4\d{3})\b", text)
        if account is None:
            invoice = INVOICES[invoice_number]
            if task_id:
                self.waiting[task_id] = invoice_number
            await updater.requires_input(updater.new_agent_message([new_text_part(
                f"Auf welches Konto soll ich die Rechnung {invoice_number} der {invoice['customer']} über "
                f"{invoice['amount']} buchen? Üblich sind 4400 (Erlöse 19 % USt) oder 4200 (Erlöse Wartung).")]))
            return
        await working(updater, f"Buche {invoice_number} auf Konto {account.group(1)} …", 1.2)
        await stream_text(updater, booked(invoice_number, account.group(1)))
        await updater.complete()

    async def report(self, updater: TaskUpdater) -> None:
        await working(updater, "Sammle die Aufträge des Monats …")
        await working(updater, "Erstelle die Übersicht als CSV …")
        await stream_text(updater, "Hier ist die Monatsübersicht November 2026 mit allen drei Aufträgen "
                                   "(Auftragswert zusammen **27.702,00 €**). Die Datei lässt sich direkt in Excel "
                                   "öffnen.")
        await updater.add_artifact([new_raw_part(monthly_report(), "text/csv", "auftraege-2026-11.csv")],
                                   name="auftraege-2026-11.csv")
        await updater.complete()

    async def read_files(self, updater: TaskUpdater, files: list) -> None:
        await working(updater, "Lese die mitgeschickten Dateien …")
        notes = []
        for part in files:
            text = part.raw.decode("utf-8", errors="replace").strip()
            first = text.splitlines()[0] if text else "(leer)"
            notes.append(f"- **{part.filename}**: {len(text)} Zeichen, beginnt mit „{first[:80]}“")
        await stream_text(updater, "Ich habe die Dateien gelesen:\n\n" + "\n".join(notes)
                          + "\n\nSoll ich daraus einen Auftrag anlegen oder eine Rechnung buchen?")
        await updater.complete()

    async def introduce(self, updater: TaskUpdater, asker: dict) -> None:
        user = asker.get("user")
        greeting = f"Guten Tag, {user}! " if user else "Guten Tag! "
        await stream_text(updater, greeting + "Ich bin der Auftragsassistent der Musterfirma. Ich kann zum Beispiel:\n\n"
                                              "- den **Stand eines Auftrags** nennen („Wie steht Auftrag 4711?“)\n"
                                              "- die **offenen Rechnungen** zeigen und eine **Rechnung buchen**\n"
                                              "- die **Monatsübersicht** der Aufträge als Datei erstellen\n\n"
                                              "Womit kann ich helfen?")
        await updater.complete()

    async def cancel(self, context: RequestContext, event_queue) -> None:
        log.info("cancel of task %s", context.task_id)
        await TaskUpdater(event_queue, context.task_id, context.context_id).cancel()


class BearerKey(BaseHTTPMiddleware):
    """The key, when TEST_AGENT_KEY is set - the card stays anonymous, as A2A's public card is."""

    async def dispatch(self, request, call_next):
        if KEY and request.url.path.startswith("/a2a") and request.headers.get("authorization") != f"Bearer {KEY}":
            log.info("refused a request without the key")
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        return await call_next(request)


def app() -> Starlette:
    agent_card = card()
    handler = DefaultRequestHandlerV2(agent_executor=DemoAgent() if DEMO else TestAgent(), task_store=InMemoryTaskStore(), agent_card=agent_card)
    application = Starlette(routes=create_agent_card_routes(agent_card) + create_jsonrpc_routes(handler, "/a2a"))
    application.add_middleware(BearerKey)
    return application


if __name__ == "__main__":
    uvicorn.run(app(), host=HOST, port=PORT)
