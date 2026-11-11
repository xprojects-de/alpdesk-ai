#!/bin/sh
# Starts the A2A test agent at http://127.0.0.1:19195 (README.md) - a virtual environment in this folder, made once.
#   devtools/a2a-test-agent/start.sh
#   TEST_AGENT_KEY=test-key-123456 devtools/a2a-test-agent/start.sh
#   TEST_AGENT_DEMO=1 devtools/a2a-test-agent/start.sh   (the demo: plain questions, realistic answers)
#   ALPDESK_MCP_URL=http://localhost:19100/mcp ALPDESK_MCP_KEY=mcp_... devtools/a2a-test-agent/start.sh   (the way back)
# Check it: devtools/a2a-test-agent/.venv/bin/python -I devtools/a2a-test-agent/check.py
set -eu
here=$(cd "$(dirname "$0")" && pwd)
if [ ! -x "$here/.venv/bin/python" ]; then
  python3 -m venv "$here/.venv"
  "$here/.venv/bin/pip" install -q -r "$here/requirements.txt"
fi
exec "$here/.venv/bin/python" -I "$here/agent.py"
