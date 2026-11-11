"""Checks the MCP test server passed through Zirmo's MCP server (roadmap point 41) with the official SDK's client.

The test server runs as a tenant's connected software ("Direkt an MCP-Clients" on); two MCP keys of the tenant: one
given the software, one not. Run inside the test server's image (it has the SDK):

    podman run --rm -v devtools/mcp-test-server/proxy_check.py:/app/proxy_check.py:ro,Z localhost/alpdesk-mcp-test:latest \
        python -I /app/proxy_check.py http://host.containers.internal:29100/mcp <key with the software> <key without> [prefix]

prefix: the connection's name as it starts the passed-through names (default "Testserver").
"""
import asyncio
import base64
import io
import sys
import zipfile

from mcp import ClientSession, types
from mcp.client.streamable_http import streamablehttp_client

URL = sys.argv[1]
WITH = sys.argv[2]
WITHOUT = sys.argv[3]
PREFIX = sys.argv[4] if len(sys.argv) > 4 else "Testserver"

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
    check("initialize: our instructions first", instructions.split("\n")[0].endswith(
        "ask_assistant for a template, files to check against the company's rules, or an answer that needs the "
        "company's connected software."), instructions[:200])
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


async def without_software(session: ClientSession, initialized: types.InitializeResult) -> None:
    check("without: no software in the instructions", f"Software {PREFIX}" not in (initialized.instructions or ""))
    tools = [tool.name for tool in (await session.list_tools()).tools]
    check("without: none of the software's tools", not any(name.startswith(PREFIX + "__") for name in tools), str(tools))
    unknown = await session.call_tool(f"{PREFIX}__echo", {"text": "x"})
    check("without: calling the software by name is an unknown tool",
          unknown.isError and unknown.content[0].text.startswith("Unknown tool"), str(unknown.content))
    prompts = [prompt.name for prompt in (await session.list_prompts()).prompts]
    check("without: none of the software's prompts", not any(name.startswith(PREFIX + "__") for name in prompts))


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
    print("ALL OK" if not failures else f"{len(failures)} FAILED: {failures}")
    sys.exit(1 if failures else 0)


asyncio.run(main())
