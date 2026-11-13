-- The connected software passed through to MCP clients unchanged (docs/architecture/mcp.md "Connected software through
-- to MCP clients"). A file of its own, not folded into V17: the release candidate of 2.0.0 is installed and has applied
-- V17 and V18.
--
-- definition: a tool's whole entry as its server listed it (JSON) - title, annotations, _meta, output schema - which
-- tools/list passes on to MCP clients. Null until the connection's tools are read again ("Werkzeuge neu laden"); until
-- then the tool is listed as before.
ALTER TABLE mcp_tool
    ADD COLUMN definition TEXT;

-- passes_resources: MCP clients read the software's resources through the tenant's MCP server, their addresses with the
-- connection in front ("Ressourcen an MCP-Clients") - off by default.
ALTER TABLE mcp_connection
    ADD COLUMN passes_resources BOOLEAN NOT NULL DEFAULT false;
