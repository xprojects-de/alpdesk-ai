-- What an MCP key may do with each connected software it may use (docs/architecture/mcp.md "Connected software through
-- to MCP clients", roadmap point 68): READ - its reading tools, WRITE - its changing tools too, passed through to the
-- key's MCP client, which asks its user before each call. A file of its own, not folded into V17: the release
-- candidate of 2.0.0 is installed and has applied V17. Every key keeps what it had: READ.
ALTER TABLE tenant_mcp_key_connection
    ADD COLUMN level TEXT NOT NULL DEFAULT 'READ' CHECK (level IN ('READ', 'WRITE'));
