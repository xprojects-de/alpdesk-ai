# MCP test server

A small MCP server for trying out MCP clients by hand: Zirmo's own client (a tenant's connected software, the
chat), Claude Code and GitHub Copilot. Each tool shows one way a server can answer - text, files, images, links,
errors, questions to the user - with invented data. Built for roadmap points 29 (files from our MCP server) and 30
(elicitation), kept for every later MCP test.

Not part of the product: outside `Backend/` and `Frontend/`, never in a release. The
specs do not use it - they have `FakeMcpSoftware` (Groovy, in the test sources), which needs no container.

## Start, check, stop

```bash
devtools/mcp-test-server/start.sh                              # http://127.0.0.1:19190/mcp, no key
MCP_TEST_KEY=test-key-123 devtools/mcp-test-server/start.sh    # the same, a bearer key required
podman exec alpdesk-mcp-test python -I /app/check.py http://127.0.0.1:8000/mcp [key]   # every scenario once
podman logs -f alpdesk-mcp-test                                # every call the clients make
podman rm -f alpdesk-mcp-test                                  # stop
```

`start.sh` builds the image (Python 3.13, the official SDK pinned in `requirements.txt`) and mounts `server.py` and
`check.py`: after a change, `podman restart alpdesk-mcp-test` is enough. Run `check.py` after every change of
`server.py`.

## The scenarios

| Tool / prompt | Answers with | Shows |
|---|---|---|
| `echo` | text | the client reaches the server |
| `code_snippet` | a Java class as text, no file | the client's model writes the file itself |
| `slow(seconds)` | text after a wait | the client's timeout |
| `text_file` | an embedded text resource (`Main.java`) | text files |
| `zip_archive(project_name, package_name, padding_mb)` | an embedded blob, a ZIP of a Java project; `padding_mb` makes it larger | archives, size limits |
| `pdf_report` | an embedded blob, a PDF with a text layer | documents |
| `image` | MCP image content (no name) | images |
| `image_file` | an embedded blob `diagram.png` | named images |
| `audio` | MCP audio content (WAV) | audio |
| `html_file` | `page.html` with a script, `logo.svg` | types a client must not show inline |
| `many_files(count)` | `count` ZIPs | files per answer |
| `broken_file` | a blob and an image with broken base64 | broken content |
| `file_with_error` | `isError` with `error.log` | a file with a failed call |
| `file_link` | a `resource_link` to `test://files/report.csv` (read with `resources/read`) | links instead of content |
| `cancel_order(number)` | text; **no `readOnlyHint`** | a changing tool - the client asks first |
| `list_invoices(since)` | a JSON array of invented invoices written since the date | a task's `call_tool` source of rows |
| `list_incoming_documents(since)` | a JSON array of `{id}` | a `call_tool` source of documents |
| `get_document(id)` | one PDF as an embedded blob | a source's file tool - one file per answer |
| `create_booking(date, gross, partner, number, source, journal)` | text; **no `readOnlyHint`**; a `source` booked before is refused (`isError`) | a task's `call_tool` target - the software recognising a row sent again |
| `create_project(package_name, project_name)` | a ZIP; values checked against a `pattern` | values the model must not make up |
| `create_project_interactive` | asks the user for package, project, build tool and tests (**elicitation**), then a ZIP | a form from the server |
| `delete_item(name)` | asks the user to confirm (elicitation, yes/no) | a confirmation from the server |
| prompt `code_review(language)` | a prompt text | prompts as slash commands |
| prompt `project_skeleton` | a prompt text | prompts as slash commands |
| prompt `new_project(package_name, project_name)` | a prompt text; a JSON-RPC error for a value against its rules (e.g. upper case) | a prompt refusing values |

All tools but `cancel_order`, `create_booking` and `delete_item` carry `readOnlyHint`.

## Connect a client

**Claude Code**:

```bash
claude mcp add --transport http mcp-test http://127.0.0.1:19190/mcp
claude mcp add --transport http mcp-test http://127.0.0.1:19190/mcp --header "Authorization: Bearer test-key-123"
```

`/mcp` in Claude Code shows the server and its tools; the prompts are `/mcp__mcp-test__code_review`. Remove it with
`claude mcp remove mcp-test`.

**GitHub Copilot (VS Code)**, `.vscode/mcp.json` of any workspace:

```json
{
  "servers": {
    "mcp-test": {
      "type": "http",
      "url": "http://127.0.0.1:19190/mcp"
    }
  }
}
```

With a key: `"headers": {"Authorization": "Bearer test-key-123"}`. The prompts appear as `/mcp.mcp-test.code_review`.

**Zirmo** (a backend of your own, see CLAUDE.md "The developer's dev database"): the server is on the loopback
address, which a connection refuses unless the backend is started with `APP_AI_PLAIN_HTTP_HOSTS=127.0.0.1`. Then a
tenant admin adds the connection on the page "Software" with the address `http://127.0.0.1:19190/mcp` (and the key,
if one was set), turns the tools on and gives a user a level. A backend in a container reaches the server as
`http://host.containers.internal:19190/mcp` (and needs that host in `APP_AI_PLAIN_HTTP_HOSTS`).

## Passed through Zirmo (`proxy_check.py`)

The test server as a tenant's connected software switched "Direkt an MCP-Clients", reached through Zirmo's own
MCP server: `proxy_check.py` connects with the SDK's client twice - with an MCP key given the software and with one
not - and checks the instructions, the passed-through tools (`<connection>__<tool>`, at most 32 characters, read-only,
never `cancel_order`), text, files, the software's errors, an unknown name as a tool error, and the prompts. On a
backend of your own (never the dev database): enter the test server at a test tenant (its address as seen from the
backend, the host in `APP_AI_PLAIN_HTTP_HOSTS`), read its tools, switch it on, create the two keys, then

```bash
podman run --rm -v $PWD/devtools/mcp-test-server/proxy_check.py:/app/proxy_check.py:ro,Z localhost/alpdesk-mcp-test:latest python -I /app/proxy_check.py http://host.containers.internal:29100/mcp <key with> <key without> [connection name]
```

## A new scenario

One tool in `server.py` - named after what it shows, its docstring the description the client's model reads, invented
data only (`example.com`, "Musterfirma") - a check of it in `check.py`, a row in the table above.
