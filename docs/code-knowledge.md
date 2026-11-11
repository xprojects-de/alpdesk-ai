# Teaching the assistant your code

How a company puts the knowledge about its own code into Zirmo - its frameworks, libraries and conventions - so
that Claude Code and GitHub Copilot (through the tenant's MCP server, [mcp.md](architecture/mcp.md)) and the people in
the chat write code the company's way instead of guessing.

## What the assistant needs - and what not

Claude Code and Copilot read the repository they work in themselves, better than any search. What they do not see is
everything outside it: the company's own framework, a shared library, the rules of how things are done. That is what
goes into Zirmo, in two kinds of Markdown files:

1. **How-tos** - one file per thing people build: "writing a plugin", "adding a REST endpoint", "a new test step". The
   rules, one complete example, how it is tested, the typical mistakes. Written by people (a first draft by Claude Code
   from the existing code, then reviewed), kept up to date like code.
2. **An API overview** - one file per public class or module: what it is for, its public methods with their
   signatures and doc comments, without the method bodies. Generated from the code, so it is never out of date.

The how-to says *how* to do it; the API overview says *what exists*. Without the overview the model fills the gaps with
methods that sound right and do not exist (`isActive()` where the class has `isEnabled()`) - in a first test two of
three generated code answers did.

Raw source files are not worth pushing: a piece of 1,000 characters cut from the middle of a method rarely answers
"how do I do X", and the implementation's details drown the rules.

## How the assistant reads them

Knowing this helps writing files that come across whole ([ai-features.md](architecture/ai-features.md) "Reading on in
a document"):

- A document is split into pieces of about 1,000 characters; a search returns the five that fit the question best.
- When a piece is cut off or the question asks for more ("all rules", "a complete example", "all steps"), the model
  reads on: a document of up to **12,000 characters is read whole**, a longer one in parts of 5,000 around the piece
  found - at most 30,000 characters per answer.
- Every piece carries the file name and the document's first line, so the search finds it by its subject too.

## Writing the Markdown

- **One topic per file, every rule in one place.** A rule repeated in a handbook and a how-to takes two of the five
  search results, and the two copies drift apart. Link instead of repeating.
- **Short files**: a how-to below 12,000 characters (some three to four pages) is read in one go.
- **The first line is the title** and says what the file is about: `# Writing a plugin for the order module`.
- **A telling, unique file name**: `plugin-howto.md`, `api-OrderService.md` - not `README.md`, which every folder has.
  The answers name it as their source.
- **Headings** for the parts (rules, example, testing, mistakes) and **fenced code blocks with the language**
  (` ```java `).
- **Complete examples**: imports, annotations, the registration - everything a model would otherwise have to guess.
- **Exact names**: the real class, method and annotation names, written as code.
- Only text: no images of code, no links as the only content.

A how-to, shortened:

````markdown
# Writing a plugin for the order module

## Rules
1. A plugin extends `OrderPlugin` and carries `@Plugin("order-...")`, the name in kebab-case.
2. `apply(OrderContext ctx)` returns a `PluginResult` - it never throws.
3. Every plugin is registered in `Plugins.register(...)` of the project.

## Example
```java
@Plugin("order-free-shipping")
public class FreeShippingPlugin extends OrderPlugin {
    @Override
    public PluginResult apply(OrderContext ctx) {
        ...
    }
}
```

## Testing
...

## Typical mistakes
...
````

An API overview, generated:

```markdown
# PluginResult (com.example.orders.plugins)
The result of a plugin.

- `static PluginResult applied()` - the plugin changed the order.
- `static PluginResult skipped(String reason)` - the plugin did not apply; reason shows in the log.
- `boolean isApplied()`
- `String reason()` - why it was skipped, else null.
```

Generating the overview is the company's choice of tool - for Java a small doclet on the JDK's doclet API
(`jdk.javadoc.doclet`), for TypeScript TypeDoc with `typedoc-plugin-markdown`, for Python `pydoc-markdown`. Keep only
public members and their doc comments; one file per class or module.

## Pushing the files

The tenant admin creates:

- a **knowledge area** for the files (page "Firmenwissen", e.g. "Entwicklung"), so only the developers assigned to it
  and the MCP server find them;
- an **API key** for the pushing job, assigned to that area (page "API-Keys");
- the **MCP key** for Claude Code and Copilot, with the area assigned to the MCP server (page "API-Keys").

Then every file goes to `POST /api/v1/documents` (documented at `/swagger`):

```bash
curl --fail-with-body -sS -H "Authorization: Bearer ai_..." \
  -F "file=@docs/ai/plugin-howto.md" \
  -F "externalId=order-module/plugin-howto.md" \
  -F "area=Entwicklung" \
  https://ai.example.com/api/v1/documents
```

- `externalId` is the file's key - its path is a good one. Pushed again with the same key, the file **replaces** the
  previous version; with unchanged text nothing is embedded again, so pushing everything on every run costs nothing
  extra.
- `area` is the knowledge area's name. Left out, a new file goes into the company knowledge everyone finds.
- `DELETE /api/v1/documents?externalId=...` removes a file that is gone from the repository - otherwise the assistant
  keeps finding methods that no longer exist.

### A sync script

Pushes every Markdown file below a folder and deletes the documents of that prefix that are gone (needs `curl` and
`jq`):

```bash
#!/usr/bin/env bash
# push-to-alpdesk.sh <folder> <prefix> - pushes every *.md below <folder> as <prefix>/<path>, deletes the rest.
set -euo pipefail
: "${ALPDESK_URL:?}" "${ALPDESK_API_KEY:?}"
folder="${1:?folder}"
prefix="${2:?prefix}/"
auth=(-H "Authorization: Bearer $ALPDESK_API_KEY")
area=()
if [ -n "${ALPDESK_AREA:-}" ]; then
  area=(-F "area=$ALPDESK_AREA")
fi
pushed="$(mktemp)"

find "$folder" -name '*.md' -type f | while IFS= read -r file; do
  key="$prefix${file#"$folder"/}"
  curl --fail-with-body -sS "${auth[@]}" ${area[@]+"${area[@]}"} -F "file=@$file" -F "externalId=$key" \
    "$ALPDESK_URL/api/v1/documents" > /dev/null
  echo "$key" >> "$pushed"
  echo "pushed $key"
done

curl --fail-with-body -sS "${auth[@]}" "$ALPDESK_URL/api/v1/documents" \
  | jq -r --arg prefix "$prefix" '.[].externalId // empty | select(startswith($prefix))' \
  | while IFS= read -r key; do
      if ! grep -qxF "$key" "$pushed"; then
        curl --fail-with-body -sS -X DELETE "${auth[@]}" -G --data-urlencode "externalId=$key" \
          "$ALPDESK_URL/api/v1/documents" > /dev/null
        echo "deleted $key"
      fi
    done
rm -f "$pushed"
```

### On every push (GitHub Actions)

```yaml
name: Knowledge for Zirmo
on:
  push:
    branches: [main]
  workflow_dispatch:
jobs:
  push:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      # - run: ./gradlew apiMarkdown        # your generator, writing docs/ai/api/
      - run: ./scripts/push-to-alpdesk.sh docs/ai order-module
        env:
          ALPDESK_URL: ${{ secrets.ALPDESK_URL }}
          ALPDESK_API_KEY: ${{ secrets.ALPDESK_API_KEY }}
          ALPDESK_AREA: Entwicklung
```

The key and the address go into the repository's secrets, never into the workflow file. GitLab, Jenkins or any other
CI run the same script.

## Using it from Claude Code and Copilot

Connect the MCP server as the MCP key card shows it ([mcp.md](architecture/mcp.md) "Connecting Claude Code"). Then a
line in the project's `CLAUDE.md` or Copilot instructions makes the model ask before it guesses:

```markdown
Before writing code against the order module's plugins, ask <server name> for the rules and the API.
```

And in the conversation: "Ask <server name> how plugins for the order module are written, then build one that ...".

To have examples taken over as they are rather than rewritten, say so in the assistant's instructions (page
"Assistent"): e.g. "Take code examples from the documents unchanged; mark your own changes."
