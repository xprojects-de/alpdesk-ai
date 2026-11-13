"""Checks the MCP test server passed through Zirmo's MCP server (roadmap point 41) with the official SDK's client.

The test server runs as a tenant's connected software ("Direkt an MCP-Clients" and "Ressourcen an MCP-Clients" on, its
tools read again after 11 November 2026); two MCP keys of the tenant: one given the software, one not - and optionally a
third that may change in it ("Darf ändern in", roadmap point 68). What the software sends reaches the client unchanged:
long text, images, files with their type, structured content, _meta, links to a web address; a link to a resource of
its own is embedded. Run inside the test server's image (it has the SDK):

    podman run --rm -v devtools/mcp-test-server/proxy_check.py:/app/proxy_check.py:ro,Z localhost/alpdesk-mcp-test:latest \
        python -I /app/proxy_check.py http://host.containers.internal:29100/mcp <key with the software> <key without> \
        [prefix] [key that may change]

prefix: the connection's name as it starts the passed-through names (default "Testserver"); its resources' addresses
start with it in lower case and a dot. Turn on the software's cancel_order at the connection for the third key's checks.
"""
import asyncio
import base64
import io
import sys
import zipfile

import httpx

from mcp import ClientSession, types
from mcp.client.streamable_http import streamablehttp_client

URL = sys.argv[1]
WITH = sys.argv[2]
WITHOUT = sys.argv[3]
PREFIX = sys.argv[4] if len(sys.argv) > 4 else "Testserver"
CHANGING = sys.argv[5] if len(sys.argv) > 5 else None

failures = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(("ok   " if ok else "FAIL ") + name + (f" - {detail}" if detail and not ok else ""))
    if not ok:
        failures.append(name)


async def session_for(key: str, use) -> None:
    async with streamablehttp_client(URL, headers={"Authorization": f"Bearer {key}"}) as (read, write, _):
        async with ClientSession(read, write) as session:
            initialized = await session.initialize()
            await use(session, initialized)


async def with_software(session: ClientSession, initialized: types.InitializeResult) -> None:
    instructions = initialized.instructions or ""
    ours = instructions.find("Zirmo is the AI assistant of the company")
    overview = instructions.find(f"Connected software: {PREFIX} - ")
    check("initialize: our instructions first, then the line naming the key's software",
          0 <= ours < overview, instructions[:300])
    check("initialize: the software's instructions after ours",
          f"Software {PREFIX} (its tools' descriptions start with [{PREFIX}]): A test server with invented data"
          in instructions, instructions[-300:])

    tools = {tool.name: tool for tool in (await session.list_tools()).tools}
    passed = sorted(name for name in tools if name.startswith(PREFIX + "__"))
    print("     passed through:", ", ".join(passed))
    check("tools/list: our own tools", {"ask_assistant", "search_knowledge", "read_document"} <= set(tools))
    check("tools/list: the software's reading tools", f"{PREFIX}__echo" in tools and f"{PREFIX}__zip_archive" in tools)
    check("tools/list: never its changing tool", f"{PREFIX}__cancel_order" not in tools)
    check("tools/list: names within 32 characters", all(len(name) <= 32 for name in passed),
          str([name for name in passed if len(name) > 32]))
    check("tools/list: description after the software's name",
          tools[f"{PREFIX}__echo"].description.startswith(f"[{PREFIX}] "), tools[f"{PREFIX}__echo"].description)
    check("tools/list: marked read-only", tools[f"{PREFIX}__echo"].annotations.readOnlyHint is True)
    check("tools/list: the software's own annotations kept",
          tools[f"{PREFIX}__echo"].annotations.idempotentHint is True, str(tools[f"{PREFIX}__echo"].annotations))
    structured = tools.get(f"{PREFIX}__structured_invoice")
    check("tools/list: title and output schema as the software says",
          structured is not None and structured.title == "Find an invoice (structured)"
          and structured.outputSchema is not None and "total" in structured.outputSchema.get("properties", {}),
          str(structured)[:300])
    meta_tool = tools.get(f"{PREFIX}__with_meta")
    check("tools/list: the tool's _meta", meta_tool is not None and (meta_tool.meta or {}).get(
        "com.example/category") == "test", str(meta_tool)[:300])

    echo = await session.call_tool(f"{PREFIX}__echo", {"text": "Hallo Modul"})
    check("tools/call: text comes back as it is", not echo.isError and "Hallo Modul" in echo.content[0].text,
          str(echo.content))

    text_file = await session.call_tool(f"{PREFIX}__text_file", {})
    resources = [part.resource for part in text_file.content if part.type == "resource"]
    check("tools/call: a text file as a text resource",
          len(resources) == 1 and resources[0].uri.path.endswith("Main.java") and "class" in resources[0].text,
          str(text_file.content)[:300])

    archive = await session.call_tool(f"{PREFIX}__zip_archive", {"project_name": "shop", "package_name": "com.example.shop"})
    blobs = [part.resource for part in archive.content if part.type == "resource" and hasattr(part.resource, "blob")]
    names = []
    if blobs:
        with zipfile.ZipFile(io.BytesIO(base64.b64decode(blobs[0].blob))) as zip_file:
            names = zip_file.namelist()
    check("tools/call: a ZIP as a blob resource the client can unpack", any(n.endswith(".java") for n in names),
          str(names)[:200])

    long = await session.call_tool(f"{PREFIX}__long_text", {"characters": 150_000})
    check("tools/call: long text whole", len(long.content[0].text) == 150_000 and long.content[0].text.endswith("END"),
          str(len(long.content[0].text)))

    image = await session.call_tool(f"{PREFIX}__image", {})
    check("tools/call: an image as an image", [part.type for part in image.content] == ["text", "image"],
          str([part.type for part in image.content]))

    audio = await session.call_tool(f"{PREFIX}__audio", {})
    check("tools/call: audio as audio", [part.type for part in audio.content] == ["audio"])

    html = await session.call_tool(f"{PREFIX}__html_file", {})
    types_ = [part.resource.mimeType for part in html.content if part.type == "resource"]
    check("tools/call: files with their own type", types_ == ["text/html", "image/svg+xml"], str(types_))

    invoice = await session.call_tool(f"{PREFIX}__structured_invoice", {"number": "4711"})
    check("tools/call: structured content", (invoice.structuredContent or {}).get("total") == 412.5,
          str(invoice.structuredContent))

    meta = await session.call_tool(f"{PREFIX}__with_meta", {})
    check("tools/call: the result's _meta", (meta.meta or {}).get("com.example/trace") == "trace-4711", str(meta.meta))

    web = await session.call_tool(f"{PREFIX}__web_link", {})
    links = [part for part in web.content if part.type == "resource_link"]
    check("tools/call: a web link stays a link", len(links) == 1
          and str(links[0].uri) == "https://files.example.com/handbuch.pdf", str(web.content)[:300])

    linked = await session.call_tool(f"{PREFIX}__file_link", {})
    embedded = [part.resource for part in linked.content if part.type == "resource"]
    check("tools/call: a link to an own resource embedded with its address",
          len(embedded) == 1 and str(embedded[0].uri) == "test://files/report.csv" and "Month" in embedded[0].text,
          str(linked.content)[:300])

    refused = await session.call_tool(f"{PREFIX}__file_with_error", {})
    check("tools/call: the software's error stays an error", refused.isError, str(refused.content)[:200])

    changing = await session.call_tool(f"{PREFIX}__cancel_order", {"number": "1"})
    check("tools/call: the changing tool is unknown - a tool error, the session goes on",
          changing.isError and changing.content[0].text.startswith("Unknown tool"), str(changing.content))

    prompts = {prompt.name: prompt for prompt in (await session.list_prompts()).prompts}
    check("prompts/list: the software's turned-on prompt", f"{PREFIX}__new_project" in prompts, str(list(prompts)))
    got = await session.get_prompt(f"{PREFIX}__new_project", {"package_name": "com.example.shop",
                                                                "project_name": "shop-service"})
    check("prompts/get: the software's text as a user message",
          got.messages[0].role == "user" and "com.example.shop" in got.messages[0].content.text, str(got.messages)[:300])

    part = PREFIX.lower()
    resources = (await session.list_resources()).resources
    uris = [str(resource.uri) for resource in resources]
    check("resources/list: the software's resources, the connection in front", f"{part}.test://files/report.csv" in uris,
          str(uris))
    templates = [template.uriTemplate for template in (await session.list_resource_templates()).resourceTemplates]
    check("resources/templates/list: with the connection in front", f"{part}.test://tickets/{{number}}/log" in templates,
          str(templates))
    read = await session.read_resource(f"{part}.test://tickets/4711/log")
    check("resources/read: an address following the template", "Ticket 4711" in read.contents[0].text
          and str(read.contents[0].uri) == f"{part}.test://tickets/4711/log", str(read.contents)[:300])
    # "Not found" is a JSON-RPC error - sent with HTTP 200 (Zirmo's ResourceErrorStatus; Micronaut MCP's transport
    # alone would send 500, on which this SDK's client ends its session). Asked on its own, to see the status.
    answer = httpx.post(URL, headers={"Authorization": f"Bearer {WITH}", "Accept": "application/json, text/event-stream"},
                        json={"jsonrpc": "2.0", "id": 1, "method": "resources/read",
                              "params": {"uri": "test://files/report.csv"}})
    error = answer.json().get("error") or {}
    check("resources/read: an address without the prefix is not found, with the hint",
          answer.status_code == 200 and error.get("code") == -32002 and "list the resources" in error.get("message", ""),
          f"{answer.status_code} {answer.text[:300]}")
    try:
        await session.read_resource("test://files/report.csv")
        check("resources/read: not found raises in the SDK's client", False)
    except Exception as e:
        check("resources/read: not found raises in the SDK's client", "Resource not found" in str(e), str(e))
    still = await session.call_tool(f"{PREFIX}__echo", {"text": "noch da"})
    check("resources/read: the session goes on after not found", not still.isError and "noch da" in still.content[0].text)


async def without_software(session: ClientSession, initialized: types.InitializeResult) -> None:
    check("without: no software in the instructions", f"Software {PREFIX}" not in (initialized.instructions or ""))
    tools = [tool.name for tool in (await session.list_tools()).tools]
    check("without: none of the software's tools", not any(name.startswith(PREFIX + "__") for name in tools), str(tools))
    unknown = await session.call_tool(f"{PREFIX}__echo", {"text": "x"})
    check("without: calling the software by name is an unknown tool",
          unknown.isError and unknown.content[0].text.startswith("Unknown tool"), str(unknown.content))
    prompts = [prompt.name for prompt in (await session.list_prompts()).prompts]
    check("without: none of the software's prompts", not any(name.startswith(PREFIX + "__") for name in prompts))


async def may_change(session: ClientSession, initialized: types.InitializeResult) -> None:
    tools = {tool.name: tool for tool in (await session.list_tools()).tools}
    cancel = tools.get(f"{PREFIX}__cancel_order")
    check("changing: the changing tool is listed", cancel is not None, str(list(tools)))
    if cancel is None:
        return
    check("changing: marked as not read-only",
          cancel.annotations is not None and cancel.annotations.readOnlyHint is False, str(cancel.annotations))
    check("changing: a reading tool stays read-only", tools[f"{PREFIX}__echo"].annotations.readOnlyHint is True)
    cancelled = await session.call_tool(f"{PREFIX}__cancel_order", {"number": "4711"})
    check("changing: the call reaches the software", not cancelled.isError and "4711" in cancelled.content[0].text,
          str(cancelled.content))


async def refused_prompt(session: ClientSession, initialized: types.InitializeResult) -> None:
    refused = await session.get_prompt(f"{PREFIX}__new_project", {"package_name": "Not Valid", "project_name": "shop"})
    check("prompts/get: a value the software refuses is the prompt's text saying so",
          refused.messages[0].content.text.startswith(f"The prompt {PREFIX}__new_project could not be fetched: "),
          str(refused.messages)[:300])
    echo = await session.call_tool(f"{PREFIX}__echo", {"text": "weiter"})
    check("prompts/get: the session goes on after it", not echo.isError and "weiter" in echo.content[0].text,
          str(echo.content))


async def main() -> None:
    await session_for(WITH, with_software)
    await session_for(WITHOUT, without_software)
    # Never a JSON-RPC error: Micronaut MCP's transport sends those with HTTP 400 or 500, and the SDK's client ends
    # the session on it.
    await session_for(WITH, refused_prompt)
    if CHANGING:
        await session_for(CHANGING, may_change)
    print("ALL OK" if not failures else f"{len(failures)} FAILED: {failures}")
    sys.exit(1 if failures else 0)


asyncio.run(main())
