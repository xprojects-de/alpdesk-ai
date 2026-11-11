# A2A test agent

An A2A agent on the **official Python `a2a-sdk`** (A2A 1.0, JSON-RPC) with invented data - to try out Zirmo's A2A
client by hand against a real server (docs/architecture/a2a.md). `FakeA2aAgent` in the specs is our reading of the
specification; whether the client understands a real server only a real server shows. A local helper, not used
by the specs. It also shows how an A2A facade in front of a platform that speaks REST or chat only can
look like this.

## Start

```bash
devtools/a2a-test-agent/start.sh
```

Makes a virtual environment in this folder once (`requirements.txt`) and serves the card at
`http://127.0.0.1:19195/.well-known/agent-card.json`, JSON-RPC at `http://127.0.0.1:19195/a2a`. Settings:

| Variable | |
|---|---|
| `TEST_AGENT_DEMO` | `1`: the demo below instead of the scenarios |
| `TEST_AGENT_KEY` | a bearer key the agent asks for (none: open) |
| `TEST_AGENT_PORT` / `TEST_AGENT_ADDRESS` | another port, and the address its card names |
| `TEST_AGENT_SLOW_SECONDS` | how long `[[slow]]` waits (default 30) |
| `ALPDESK_MCP_URL` / `ALPDESK_MCP_KEY` | the way back: the tenant's MCP server and an MCP key, for `[[search ...]]` |

`check.py` runs every scenario against the agent itself (with `TEST_AGENT_DEMO=1` the demo's questions):

```bash
devtools/a2a-test-agent/.venv/bin/python -I devtools/a2a-test-agent/check.py
```

## Scenarios

One skill per scenario - it picks the scenario when it is the only skill named (the tool way names one; a chat with
every skill on names them all); a marker in the question picks it too, so the chat's tool (which names no skill) reaches
each: none - a complete message; `[[stream]]` - a task answered word by word; `[[ask-back <text>]]` - input-required,
the next message in the context finishes it; `[[files-out]]` - a text file and a PNG as artifacts; `[[files-in]]` -
names the files sent; `[[fail]]` - a JSON-RPC error; `[[slow]]` - the timeout; `[[echo-metadata]]` - the asker's
metadata (`urn:alpdesk-ai:a2a:asker:1`); `[[search <query>]]` - `search_knowledge` at the tenant's MCP server.

## The demo (`TEST_AGENT_DEMO=1`)

To show the chat as a user sees it: the agent is the "Auftragsassistent" of the invented Musterfirma GmbH, with the
skills Aufträge, Rechnungen and Berichte, and takes plain questions - no markers. It answers as a task, its steps as
status lines (the chat's working row: "Suche Auftrag 4711 im ERP …") and the answer streamed in Markdown with tables:

- "Wie steht Auftrag 4711?" (4712, 4713) - the order's state; "Welche Aufträge sind offen?" - all three;
- "Welche Rechnungen sind offen?"; "Buche die Rechnung R-2026-118" - asks back for the account, the next question
  ("auf 4200") books it; with the account in the question it books at once;
- "Erstelle den Monatsbericht" - a CSV of the orders;
- an order number it does not know ("Auftrag 27") - says so and names the ones it knows; "zeig mir deine Agent-Card" -
  its card with the skills;
- a file sent with the question - what it read; anything else - a greeting with the user's name and what it can do.

The topic comes from the question; the asker's `skills` are what it may do (the chat's topics left on, the tool way's
one): a question to a topic switched off is declined ("das Thema ist abgewählt"), never answered with another.

```bash
TEST_AGENT_DEMO=1 devtools/a2a-test-agent/start.sh
```

## Against the dev stack

The admin allows A2A for a test tenant (tenant dialog, "Darf A2A-Agenten anbinden"). Its tenant admin connects the
agent on the page "Software": protocol A2A, address `http://127.0.0.1:19195/a2a` (`127.0.0.1` must be in
`APP_AI_PLAIN_HTTP_HOSTS`), the key if one is set. Then:

- **the tool way**: a group with a level for the connection, a question in the chat naming a marker, e.g.
  "Frag den Testagenten [[stream]] nach dem Stand";
- **the answering way**: "Beantwortet den Chat" on the connection - every question goes to the agent, `[[ask-back
  Konto?]]` shows the waiting chip, `[[echo-metadata]]` shows the groups and skills that arrive;
- **the tasks**: a `call_agent` step and a `prompt` step with `assistant: true` naming `Testagent`.

Run it with every change of `services.a2a` and before a release.
