"""Checks a company's MCP server (company.py, the "Entwicklungsportal") passed through Zirmo's MCP server, with the
official SDK's client: it connects to the server directly and through Zirmo, and compares both ends - what the company
wrote must be what an MCP client gets.

Every tool's entry (description after "[<connection>] ", every parameter's description, title, output schema,
annotations), every call's result (text, structured content, files with their type, an image, a long log, an error),
the prompts with their arguments and messages, the resources and templates with the connection in front - and the
rights of three keys: one that reads, one that may change in the software, one without it. Run inside the test
server's image (it has the SDK) on the test stack's network - devtools/general-chat-test/company.py sets everything up
and starts it:

    python -I /app/company_check.py <Zirmo's /mcp> <the server's /mcp> <key reading> <key changing> <key without> \
        [connection name]
"""
import asyncio
import sys

from mcp import ClientSession, types
from mcp.client.streamable_http import streamablehttp_client

ZIRMO, DIRECT, READING, CHANGING, WITHOUT = sys.argv[1:6]
PREFIX = sys.argv[6] if len(sys.argv) > 6 else "Entwicklung"
PART = PREFIX.lower() + "."
CHANGING_TOOL = "create_review_ticket"
# Claude Code reads this much of a server's instructions.
CLIENT_INSTRUCTIONS = 4096

failures = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(("ok   " if ok else "FAIL ") + name + (f" - {detail}" if detail and not ok else ""))
    if not ok:
        failures.append(name)


def note(text: str) -> None:
    print("     " + text)


class Connected:
    """A session kept open for the whole check."""

    def __init__(self, url: str, key: str | None):
        self.url = url
        self.headers = {"Authorization": f"Bearer {key}"} if key else {}

    async def __aenter__(self):
        self.transport = streamablehttp_client(self.url, headers=self.headers)
        read, write, _ = await self.transport.__aenter__()
        self.client = ClientSession(read, write)
        self.session = await self.client.__aenter__()
        self.initialized = await self.session.initialize()
        return self

    async def __aexit__(self, *error):
        await self.client.__aexit__(*error)
        await self.transport.__aexit__(*error)


def own(uri) -> str:
    """A resource's address without the connection in front."""
    text = str(uri)
    return text[len(PART):] if text.startswith(PART) else text


def parts(result: types.CallToolResult) -> list:
    """A result's content as plain values to compare."""
    values = []
    for part in result.content:
        if part.type == "text":
            values.append(("text", part.text))
        elif part.type in ("image", "audio"):
            values.append((part.type, part.mimeType, part.data))
        elif part.type == "resource":
            resource = part.resource
            values.append(("resource", own(resource.uri), resource.mimeType,
                           getattr(resource, "text", None) or getattr(resource, "blob", None)))
        elif part.type == "resource_link":
            values.append(("resource_link", own(part.uri), part.name, part.mimeType))
    return values


def via_names(theirs: dict, ours: dict) -> dict:
    """Which passed-through tool is which of the server's - by its title, as a cut name no longer says."""
    by_title = {tool.title: name for name, tool in ours.items() if name.startswith(PREFIX + "__")}
    return {name: by_title.get(tool.title) for name, tool in theirs.items()}


CALLS = {
    "search_guidelines": {"query": "Review Freigaben", "limit": 3},
    "get_guideline": {"id": "RL-019"},
    "list_repositories": {"team": "vertrieb"},
    "get_file": {"repository": "shop-backend",
                 "path": "src/main/java/de/musterfirma/shop/service/preis/PreisService.java"},
    "search_code": {"repository": "shop-backend", "pattern": "BigDecimal"},
    "get_build_log": {"number": 4711},
    "export_review_report_as_pdf": {"repository": "shop-backend"},
    "download_sources": {"repository": "lager-app"},
    "architecture_diagram": {"repository": "abrechnung-core"},
}


async def entries(direct: Connected, zirmo: Connected) -> dict:
    theirs = {tool.name: tool for tool in (await direct.session.list_tools()).tools}
    ours = {tool.name: tool for tool in (await zirmo.session.list_tools()).tools}
    names = via_names(theirs, ours)
    passed = sorted(name for name in ours if name.startswith(PREFIX + "__"))
    for name, via in names.items():
        note(f"{name} -> {via}" + ("   (cut to fit 32 characters)" if via and via != f"{PREFIX}__{name}" else ""))
    check("tools/list: our own tools beside the software's", {"ask_assistant", "search_knowledge", "read_document"} <= set(ours))
    check("tools/list: the nine reading tools, not the changing one",
          len(passed) == 9 and names[CHANGING_TOOL] is None, str(passed))
    check("tools/list: every name within 32 characters, with the connection and __ in front",
          all(len(name) <= 32 for name in passed), str([name for name in passed if len(name) > 32]))
    for name, tool in theirs.items():
        via = ours.get(names[name] or "")
        if via is None:
            continue
        # Zirmo leaves off white space around a description (a docstring's last line break) - nothing else.
        expected = f"[{PREFIX}] {tool.description.strip()}"
        check(f"{name}: the description whole, after [{PREFIX}] ({len(tool.description.strip())} characters)",
              via.description == expected, f"{len(via.description)} characters, ends {via.description[-60:]!r}")
        check(f"{name}: the parameters with their descriptions and rules", via.inputSchema == tool.inputSchema,
              str(via.inputSchema)[:300])
        check(f"{name}: title and output schema", via.title == tool.title and via.outputSchema == tool.outputSchema,
              f"{via.title!r} {str(via.outputSchema)[:200]}")
        given, got = tool.annotations, via.annotations
        check(f"{name}: read-only, the server's other hints kept",
              got is not None and got.readOnlyHint is True and (got.destructiveHint, got.idempotentHint, got.openWorldHint)
              == (given.destructiveHint, given.idempotentHint, given.openWorldHint), str(got))
    umlauts = ours.get(names["get_file"] or "")
    check("tools/list: umlauts and ß arrive as written",
          umlauts is not None and "Schrägstrich" in str(umlauts.inputSchema) and "Für Quellcode" in umlauts.description)
    return names


async def calls(direct: Connected, zirmo: Connected, names: dict) -> None:
    for name, arguments in CALLS.items():
        theirs = await direct.session.call_tool(name, arguments)
        ours = await zirmo.session.call_tool(names[name], arguments)
        if name == "get_guideline":
            # The link to the server's own resource is fetched for the client: embedded, the text beside it as it was.
            embedded = [part for part in ours.content if part.type == "resource"]
            check("get_guideline: the text as it is, the link to the server's resource embedded",
                  parts(ours)[0] == parts(theirs)[0] and len(embedded) == 1
                  and "RL-019" in (getattr(embedded[0].resource, "text", "") or "")
                  and "„so wie hier“" in embedded[0].resource.text, str(ours.content)[:300])
            continue
        check(f"{name}: the result as the server sent it ({', '.join(part[0] for part in parts(theirs))})",
              not ours.isError and parts(ours) == parts(theirs)
              and ours.structuredContent == theirs.structuredContent,
              f"{str(parts(ours))[:200]} / structured {str(ours.structuredContent)[:120]}")
    log = await zirmo.session.call_tool(names["get_build_log"], {"number": 4711})
    note(f"get_build_log: {len(log.content[0].text):,} characters through the proxy")
    check("get_build_log: a long log whole, to its last line", log.content[0].text.endswith("BUILD END"))

    arguments = {"repository": "shop-backend", "path": "gibt/es/nicht.java"}
    theirs = await direct.session.call_tool("get_file", arguments)
    ours = await zirmo.session.call_tool(names["get_file"], arguments)
    check("get_file: the server's error stays its error, in its words",
          ours.isError and theirs.isError and parts(ours) == parts(theirs), str(ours.content)[:200])
    refused = await zirmo.session.call_tool(names["get_guideline"], {"id": "falsch"})
    check("get_guideline: a value against the parameter's pattern is a tool error - the session goes on",
          refused.isError, str(refused.content)[:200])
    unknown = await zirmo.session.call_tool(f"{PREFIX}__{CHANGING_TOOL}"[:32],
                                            {"repository": "shop-backend", "title": "x", "findings": []})
    check("the changing tool by name is unknown to a key that reads",
          unknown.isError and unknown.content[0].text.startswith("Unknown tool"), str(unknown.content)[:200])
    again = await zirmo.session.call_tool(names["list_repositories"], {})
    check("the session goes on after errors", not again.isError
          and len(again.structuredContent["repositories"]) == 3, str(again.structuredContent)[:200])


async def prompts(direct: Connected, zirmo: Connected) -> None:
    theirs = {prompt.name: prompt for prompt in (await direct.session.list_prompts()).prompts}
    ours = {prompt.name: prompt for prompt in (await zirmo.session.list_prompts()).prompts}
    note("prompts: " + ", ".join(sorted(ours)))
    for name, prompt in theirs.items():
        via = next((got for got in ours.values() if got.title == prompt.title), None)
        check(f"prompt {name}: listed, the description after [{PREFIX}], its arguments with their descriptions",
              via is not None and via.name.startswith(PREFIX + "__") and len(via.name) <= 32
              and via.description == f"[{PREFIX}] {prompt.description}" and via.arguments == prompt.arguments,
              str(via)[:300])
    review = next(got for got in ours.values() if got.title == "Merge Request prüfen")
    arguments = {"repository": "shop-backend", "number": "17"}
    theirs_text = await direct.session.get_prompt("review_merge_request", arguments)
    ours_text = await zirmo.session.get_prompt(review.name, arguments)
    check("prompt review_merge_request: all three messages, each with its role",
          [(message.role, message.content.text) for message in ours_text.messages]
          == [(message.role, message.content.text) for message in theirs_text.messages]
          and len(ours_text.messages) == 3, str(ours_text.messages)[:300])
    explain = next(got for got in ours.values() if got.title == "Richtlinie erklären")
    refused = await zirmo.session.get_prompt(explain.name, {"id": "RL-999"})
    check("prompt explain_guideline: a value the server refuses is the prompt's text saying so",
          "could not be fetched" in refused.messages[0].content.text, str(refused.messages)[:300])


async def resources(direct: Connected, zirmo: Connected) -> None:
    theirs = {str(resource.uri): resource for resource in (await direct.session.list_resources()).resources}
    ours = {str(resource.uri): resource for resource in (await zirmo.session.list_resources()).resources}
    check("resources/list: the server's four resources, the connection in front of each address",
          set(ours) == {PART + uri for uri in theirs}, str(sorted(ours)))
    for uri, resource in theirs.items():
        via = ours.get(PART + uri)
        check(f"resource {uri}: name and title after [{PREFIX}], description and type as the server says",
              via is not None and via.name == f"[{PREFIX}] {resource.name}"
              and via.title == f"[{PREFIX}] {resource.title}" and via.description == resource.description
              and via.mimeType == resource.mimeType, str(via)[:300])
    theirs_templates = [template.uriTemplate for template in (await direct.session.list_resource_templates()).resourceTemplates]
    ours_templates = [template.uriTemplate for template in (await zirmo.session.list_resource_templates()).resourceTemplates]
    check("resources/templates/list: both templates, the connection in front",
          sorted(ours_templates) == sorted(PART + template for template in theirs_templates), str(ours_templates))

    for uri in ("dev://guidelines/RL-019", "dev://diagrams/shop-backend.png", "dev://builds/4711/log",
                "dev://repositories/lager-app/readme"):
        theirs_read = (await direct.session.read_resource(uri)).contents[0]
        ours_read = (await zirmo.session.read_resource(PART + uri)).contents[0]
        same = (getattr(ours_read, "text", None), getattr(ours_read, "blob", None), ours_read.mimeType) \
            == (getattr(theirs_read, "text", None), getattr(theirs_read, "blob", None), theirs_read.mimeType)
        check(f"resources/read {uri}: the content and type as the server sent them", same and own(ours_read.uri) == uri,
              str(ours_read)[:200])
    try:
        await zirmo.session.read_resource(PART + "dev://repositories/gibt-es-nicht/readme")
        check("resources/read: the server's own error reaches the client", False, "no error")
    except Exception as error:
        check("resources/read: the server's own error reaches the client", "unbekannt" in str(error).lower()
              or "error" in str(error).lower(), str(error)[:200])
    still = await zirmo.session.list_resources()
    check("resources: the session goes on after a refused read", len(still.resources) == 4)


def instructions(zirmo: Connected, direct: Connected, description: str) -> None:
    text = zirmo.initialized.instructions or ""
    theirs = direct.initialized.instructions or ""
    note(f"initialize: Zirmo's instructions {len(text):,} characters, the server's own {len(theirs):,}")
    check("initialize: the server's name is the tenant's brand, not the software's",
          zirmo.initialized.serverInfo.name != direct.initialized.serverInfo.name, zirmo.initialized.serverInfo.name)
    check("initialize: what the key knows, from its description", description in text, text[:300])
    start = text.find(f"Software {PREFIX} ")
    check("initialize: the software's instructions after ours, from their first word",
          0 < start and theirs[:200] in text[start:], text[start:start + 300])
    whole = theirs in text
    note("initialize: the software's instructions " + ("arrive whole" if whole else
         f"are cut - {len(theirs):,} characters are more than Zirmo keeps (2,000)"))
    check("initialize: cut instructions say so", whole or "..." in text[start:] or "cut" in text[start:].lower(),
          text[-200:])
    check(f"initialize: all within the {CLIENT_INSTRUCTIONS:,} characters Claude Code reads",
          len(text) <= CLIENT_INSTRUCTIONS, f"{len(text):,} characters")
    check("initialize: the resources capability announced", zirmo.initialized.capabilities.resources is not None)


async def may_change(direct: Connected, zirmo: Connected) -> None:
    theirs = {tool.name: tool for tool in (await direct.session.list_tools()).tools}
    ours = {tool.name: tool for tool in (await zirmo.session.list_tools()).tools}
    name = via_names(theirs, ours)[CHANGING_TOOL]
    check("changing key: the changing tool is listed", name is not None, str(sorted(ours)))
    if name is None:
        return
    note(f"{CHANGING_TOOL} -> {name}")
    ticket = ours[name]
    check("changing key: marked as not read-only, its description whole",
          ticket.annotations.readOnlyHint is False
          and ticket.description == f"[{PREFIX}] {theirs[CHANGING_TOOL].description.strip()}", str(ticket)[:300])
    check("changing key: the reading tools stay read-only",
          all(tool.annotations.readOnlyHint is True for got, tool in ours.items()
              if got.startswith(PREFIX + "__") and got != name))
    created = await zirmo.session.call_tool(name, {"repository": "shop-backend", "title": "Rundung prüfen",
                                                   "findings": ["PreisService.java:14 Rundung vor der Steuer"]})
    check("changing key: the call reaches the server, its answer comes back",
          not created.isError and "REV-1042" in created.content[0].text and "Rundung prüfen" in created.content[0].text,
          str(created.content)[:200])


async def without(zirmo: Connected) -> None:
    tools = [tool.name for tool in (await zirmo.session.list_tools()).tools]
    check("key without the software: none of its tools, prompts or instructions",
          not any(name.startswith(PREFIX + "__") for name in tools)
          and f"Software {PREFIX}" not in (zirmo.initialized.instructions or "")
          and not any(prompt.name.startswith(PREFIX + "__") for prompt in (await zirmo.session.list_prompts()).prompts),
          str(tools))


async def main() -> None:
    description = sys.argv[7] if len(sys.argv) > 7 else ""
    async with Connected(DIRECT, None) as direct:
        async with Connected(ZIRMO, READING) as zirmo:
            instructions(zirmo, direct, description)
            names = await entries(direct, zirmo)
            await calls(direct, zirmo, names)
            await prompts(direct, zirmo)
            await resources(direct, zirmo)
        async with Connected(ZIRMO, CHANGING) as zirmo:
            await may_change(direct, zirmo)
        async with Connected(ZIRMO, WITHOUT) as zirmo:
            await without(zirmo)
    print("ALL OK" if not failures else f"{len(failures)} FAILED: {failures}")
    sys.exit(1 if failures else 0)


asyncio.run(main())
