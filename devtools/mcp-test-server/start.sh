#!/bin/sh
# Builds and starts the MCP test server in Podman: http://127.0.0.1:19190/mcp (README.md).
#   devtools/mcp-test-server/start.sh            without a key
#   MCP_TEST_KEY=test-key-123 devtools/mcp-test-server/start.sh
# Stop: podman rm -f alpdesk-mcp-test. Log (every call): podman logs -f alpdesk-mcp-test
set -eu
here=$(cd "$(dirname "$0")" && pwd)
podman build -q -t localhost/alpdesk-mcp-test:latest -f "$here/Containerfile" "$here" >/dev/null
podman rm -f alpdesk-mcp-test >/dev/null 2>&1 || true
podman run -d --name alpdesk-mcp-test -p 127.0.0.1:19190:8000 -e MCP_TEST_KEY="${MCP_TEST_KEY:-}" \
  -v "$here/server.py:/app/server.py:ro,Z" -v "$here/check.py:/app/check.py:ro,Z" \
  localhost/alpdesk-mcp-test:latest >/dev/null
echo "MCP test server: http://127.0.0.1:19190/mcp - log: podman logs -f alpdesk-mcp-test"
