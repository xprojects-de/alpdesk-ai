# Changelog

What changed in each release of Alpdesk AI, newest first. Versions follow [Semantic Versioning](https://semver.org):
**major** - updating needs a step by hand (a setting, the compose file, the client software's API); **minor** - new
features, updating needs nothing by hand; **patch** - fixes and small improvements. Every release is a tag `v<version>`
with its images `ghcr.io/xprojects-de/alpdesk-ai:<version>` and `-sandbox:<version>`; updating: see the README.

## [1.4.0] - 2026-10-14

### Added
- Files returned by a tool of connected software (MCP): a file a tool sends back as an embedded resource or an image
  is offered for download below the answer, like a file the assistant created - e.g. a generated project as a ZIP
  archive.
- The assistant reads such a file like an uploaded one: the text of documents (PDF, DOCX, XLSX, ...), images, the text
  of logs, code and configuration files. An archive it opens with the code tools when the tenant has a sandbox.
- A text file a tool returns (code, a log, Markdown) is in the tool's result at once: the assistant reads it in the
  same answer - also through the MCP server - and can choose and adapt what it hands on.
- Archives get an icon of their own in the chat's file list.
- The MCP server (Claude Code, GitHub Copilot) hands over the files of its answer: files the assistant creates and
  files a connected software returns go with `ask_assistant`'s result - text files (code, Markdown, CSV, logs) as text
  the client's model can write into the project, other files as base64. Nothing is kept; at most 8 MB per answer.
- The page "Konto" shows every user how to connect Claude Code or GitHub Copilot to the company's assistant: the
  command and the VS Code configuration, ready to copy - only the MCP key comes from an administrator.
- Prompts of a connected software in the chat: a software's MCP server may offer ready-made requests. A tenant admin
  turns each on; users with access pick one next to the paperclip, fill in its values in a form, and its text lands
  in the input field - they read it and send it themselves.
- The assistant's templates are the MCP server's prompts: slash commands in the client, named after the tenant's
  server and template (e.g. `/mcp.muster-ai.code-review` in Copilot, `/mcp__muster-ai__code-review` in Claude Code).

### Changed
- A tool result of connected software may now have up to 8 MB (was 4 MB) - about 6 MB of files.

### Fixed
- A PDF that begins with a print job ticket is recognised as a PDF again instead of being refused as an unknown type
  (Apache Tika 3.3.3, which also hardens the reading of PDF forms, bookmarks and OneNote files).

### Security
- A returned file keeps its type only if it is on a fixed list (archives, documents, text, images); anything else -
  HTML, SVG - is offered as `application/octet-stream`, always as a download. Its name is cleaned of paths.
- A file returned together with a tool error is not offered.
- At most 5 files per answer, counted together with the files the assistant creates.
- No files where nothing is kept: questions through the MCP server and from the client software with an API key
  without history.
