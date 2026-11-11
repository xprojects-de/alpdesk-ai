"""
Calls every scenario of the running MCP test server once and checks what comes back - after a change of server.py,
before a test with a real client. Answers an elicitation like a user would (accept, then decline).

    python -I devtools/mcp-test-server/check.py [http://127.0.0.1:19190/mcp] [key]

Needs the SDK of requirements.txt (in a venv, or: podman exec alpdesk-mcp-test python -I /app/check.py
http://127.0.0.1:8000/mcp - start.sh mounts this file too).
"""

import asyncio
import base64
import io
import sys
import zipfile

from mcp import ClientSession, types
from mcp.client.streamable_http import streamablehttp_client

URL = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:19190/mcp"
KEY = sys.argv[2] if len(sys.argv) > 2 else ""

failures = []
answers = iter(["accept", "decline", "accept"])


def check(name: str, ok: bool, detail: str = "") -> None:
    print(("ok   " if ok else "FAIL ") + name + (f" - {detail}" if detail and not ok else ""))
    if not ok:
        failures.append(name)


async def elicit(context, params: types.ElicitRequestParams) -> types.ElicitResult:
    """Answers like a user: the first form with values, the second declined, the third (delete) confirmed."""
    action = next(answers)
    print(f"     elicitation: {params.message!r}, fields {list(params.requestedSchema['properties'])} -> {action}")
    if "build_tool" in params.requestedSchema["properties"]:
        check("elicitation: the choice as an enum",
              params.requestedSchema["properties"]["build_tool"].get("enum") == ["gradle", "maven"])
    if action != "accept":
        return types.ElicitResult(action=action)
    if "confirm" in params.requestedSchema["properties"]:
        return types.ElicitResult(action="accept", content={"confirm": True})
    return types.ElicitResult(action="accept", content={
        "package_name": "com.example.shop", "project_name": "shop-service", "build_tool": "maven", "with_tests": True})


def kinds(result: types.CallToolResult) -> list[str]:
    return [part.type for part in result.content]


def blob_of(result: types.CallToolResult, index: int) -> bytes:
    return base64.b64decode(result.content[index].resource.blob)


async def main() -> None:
    headers = {"Authorization": f"Bearer {KEY}"} if KEY else None
    async with streamablehttp_client(URL, headers=headers) as (read, write, _):
        async with ClientSession(read, write, elicitation_callback=elicit) as session:
            await session.initialize()
            tools = {tool.name: tool for tool in (await session.list_tools()).tools}
            check("tools listed", len(tools) >= 17, str(sorted(tools)))
            check("readOnlyHint on reading tools", tools["zip_archive"].annotations.readOnlyHint is True)
            check("no hint on cancel_order", tools["cancel_order"].annotations is None
                  or tools["cancel_order"].annotations.readOnlyHint is not True)
            check("pattern in create_project's schema",
                  "pattern" in tools["create_project"].inputSchema["properties"]["package_name"])

            booked = await session.call_tool("create_booking", {"date": "2026-03-01", "gross": 119.0,
                                                                "source": "check/a.pdf#000000000001"})
            again = await session.call_tool("create_booking", {"date": "2026-03-01", "gross": 119.0,
                                                               "source": "check/a.pdf#000000000001"})
            check("create_booking books once, refuses the repeat", not booked.isError and again.isError,
                  again.content[0].text)

            listed = await session.call_tool("list_invoices", {"since": "2026-11-01"})
            check("list_invoices lists since a date", '"RE-2026-0042"' in listed.content[0].text
                  and '"RE-2026-0041"' not in listed.content[0].text, listed.content[0].text)
            document = await session.call_tool("get_document", {"id": "7"})
            check("get_document gives one PDF", kinds(document) == ["resource"], str(kinds(document)))

            result = await session.call_tool("echo", {"text": "hello"})
            check("echo", result.content[0].text == "Echo: hello")

            result = await session.call_tool("text_file", {})
            check("text_file: embedded text", kinds(result) == ["text", "resource"]
                  and "class Main" in result.content[1].resource.text)

            result = await session.call_tool("zip_archive", {"project_name": "demo-project"})
            names = zipfile.ZipFile(io.BytesIO(blob_of(result, 1))).namelist()
            check("zip_archive: a ZIP", kinds(result) == ["text", "resource"]
                  and "demo-project/src/main/java/com/example/demo/Main.java" in names, str(names))

            result = await session.call_tool("zip_archive", {"padding_mb": 2})
            check("zip_archive: padded to about 2 MB", len(blob_of(result, 1)) > 2 * 1024 * 1024)

            result = await session.call_tool("pdf_report", {})
            check("pdf_report: a PDF", blob_of(result, 1).startswith(b"%PDF-1.4"))

            result = await session.call_tool("image", {})
            check("image: image content", kinds(result) == ["text", "image"]
                  and base64.b64decode(result.content[1].data).startswith(b"\x89PNG"))

            result = await session.call_tool("image_file", {})
            check("image_file: PNG as resource", str(result.content[0].resource.uri) == "file:///diagram.png")

            result = await session.call_tool("audio", {})
            check("audio: audio content", kinds(result) == ["audio"])

            result = await session.call_tool("html_file", {})
            check("html_file: HTML and SVG", [p.resource.mimeType for p in result.content] == ["text/html", "image/svg+xml"])

            result = await session.call_tool("many_files", {"count": 6})
            check("many_files: six", len(result.content) == 6)

            result = await session.call_tool("broken_file", {})
            check("broken_file: broken base64 passed on", result.content[1].resource.blob == "no base64!")

            result = await session.call_tool("file_with_error", {})
            check("file_with_error: isError with a file", result.isError and kinds(result) == ["text", "resource"])

            result = await session.call_tool("file_link", {})
            link = result.content[1]
            check("file_link: resource link", link.type == "resource_link" and str(link.uri) == "test://files/report.csv")
            read = await session.read_resource(link.uri)
            check("resources/read the link", read.contents[0].text.startswith("Month;Orders"))

            result = await session.call_tool("cancel_order", {"number": "4711"})
            check("cancel_order", result.content[0].text == "Order 4711 cancelled.")

            result = await session.call_tool("create_project", {"package_name": "Com.Example", "project_name": "x"})
            check("create_project: refuses a value against the pattern", result.isError is True)
            result = await session.call_tool("create_project", {"package_name": "com.example.demo",
                                                                "project_name": "demo-project"})
            check("create_project: valid values", kinds(result) == ["text", "resource"])

            result = await session.call_tool("create_project_interactive", {})
            check("elicitation accepted: the user's values used", "shop-service" in result.content[0].text
                  and str(result.content[1].resource.uri) == "file:///shop-service.zip", result.content[0].text)
            result = await session.call_tool("create_project_interactive", {})
            check("elicitation declined: nothing generated", "decline" in result.content[0].text
                  and len(result.content) == 1, result.content[0].text)
            result = await session.call_tool("delete_item", {"name": "Entwurf"})
            check("delete_item: confirmed", result.content[0].text == "\"Entwurf\" deleted.", result.content[0].text)

            result = await session.call_tool("slow", {"seconds": 1})
            check("slow", result.content[0].text.startswith("Done after"))

            prompts = [prompt.name for prompt in (await session.list_prompts()).prompts]
            check("prompts listed", set(prompts) >= {"code_review", "project_skeleton"}, str(prompts))
            prompt = await session.get_prompt("code_review", {"language": "Kotlin"})
            check("prompt filled", "Kotlin" in prompt.messages[0].content.text)
            try:
                await session.get_prompt("new_project", {"package_name": "com.example.demo", "project_name": "AiApp"})
                check("prompt refuses a wrong value", False, "no error")
            except Exception as e:
                check("prompt refuses a wrong value", "project_name must be lowercase" in str(e), str(e))


asyncio.run(main())
print(f"\n{'All scenarios passed' if not failures else str(len(failures)) + ' failed: ' + ', '.join(failures)}")
sys.exit(1 if failures else 0)
