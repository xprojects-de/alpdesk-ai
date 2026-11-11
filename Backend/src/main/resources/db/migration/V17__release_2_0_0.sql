-- The one migration of the management database for release 2.0.0: its blocks were separate migrations while 2.0.0
-- was worked on and are folded here before the release (nothing of it was released).

-- Whether scheduled tasks may write into the store (docs/architecture/file-stores.md "Writing", roadmap point 50): a
-- task's write_file step appends rows to a CSV there. Off for every store - a tenant admin turns it on.
ALTER TABLE file_store ADD COLUMN task_write BOOLEAN NOT NULL DEFAULT false;

-- A mailbox as a file store (docs/architecture/file-stores.md "Mailbox", roadmap point 50): IMAP, read only - its
-- messages' attachments read by the tasks and the knowledge sources.
ALTER TABLE file_store DROP CONSTRAINT file_store_type_check;
ALTER TABLE file_store ADD CONSTRAINT file_store_type_check CHECK (type IN ('SFTP', 'FTPS', 'S3', 'SHAREPOINT', 'IMAP'));

-- One person with a user in several tenants (docs/architecture/tenancy.md "Who logs in", roadmap point 51): a
-- username is unique per tenant, no longer per installation. An access request stays one per username.
DROP INDEX tenant_user_username_ci_uq;
CREATE UNIQUE INDEX tenant_user_tenant_username_ci_uq ON tenant_user (tenant_id, LOWER(username));
CREATE INDEX tenant_user_username_ci_idx ON tenant_user (LOWER(username));

-- What an MCP key releases, in the tenant admin's words (docs/architecture/mcp.md "MCP keys", roadmap point 51): sent
-- to every client of the key in initialize and in the descriptions of search_knowledge and ask_assistant, so a general
-- chat asking several topics tells them apart. NULL: none.
ALTER TABLE tenant_mcp_key ADD COLUMN description TEXT;

-- A connected software speaking A2A instead of MCP (docs/architecture/a2a.md, roadmap point 54): an A2A agent whose
-- card is read like a software's tools, its skills kept as the connection's tools. Existing connections are MCP.
ALTER TABLE mcp_connection ADD COLUMN protocol TEXT NOT NULL DEFAULT 'MCP' CHECK (protocol IN ('MCP', 'A2A'));

-- Whether the tenant may connect A2A agents (docs/architecture/a2a.md) - the admin's decision whose questions may leave
-- the installation. Off for every tenant.
ALTER TABLE tenant ADD COLUMN a2a_allowed BOOLEAN NOT NULL DEFAULT false;

-- The A2A agent that answers every question of the tenant's chat (docs/architecture/a2a.md "A tenant answering through
-- its agent"): at most one per tenant, only an A2A connection (checked by McpConnectionService).
ALTER TABLE mcp_connection ADD COLUMN answers_chat BOOLEAN NOT NULL DEFAULT false;
CREATE UNIQUE INDEX mcp_connection_answers_chat_uq ON mcp_connection (tenant_id) WHERE answers_chat;

-- The doors an MCP key opens (docs/architecture/a2a.md "The two doors of a key"): /mcp and /a2a - at least one.
-- Existing keys stay MCP keys.
ALTER TABLE tenant_mcp_key ADD COLUMN mcp BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE tenant_mcp_key ADD COLUMN a2a BOOLEAN NOT NULL DEFAULT false;

-- Which tenant an A2A task of the tenant's assistant belongs to (docs/architecture/a2a.md "Tasks"): the SDK names a task
-- by a random id and asks for it without its caller, the task itself lives in the tenant's database (a2a_task). Deleted
-- with the tenant and with its task.
CREATE TABLE a2a_task_index (
    task_id    TEXT        PRIMARY KEY,
    tenant_id  BIGINT      NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    mcp_key_id BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX a2a_task_index_tenant_idx ON a2a_task_index (tenant_id);

-- The language of the browser a waiting person signed in with (docs/architecture/tenancy.md "Sign-in through a WAM
-- proxy"): the admin's assignment takes it unless it names one. Empty for requests from before - English.
ALTER TABLE access_request ADD COLUMN language TEXT;

-- Access needs a group (decided 7 November 2026, docs/architecture/tenancy.md "User groups"): a tenant user who is no
-- tenant admin gets in only through a group that counts for it. Every tenant has a group "Standard" without rights
-- (the chat and the company knowledge) - new ones get it when created (TenantService). Its members are assigned by
-- hand after the update (CHANGELOG): nobody is put into it here.
INSERT INTO user_group (tenant_id, name, created_at)
SELECT t.id, 'Standard', now()
FROM tenant t
WHERE NOT EXISTS (SELECT 1 FROM user_group g WHERE g.tenant_id = t.id AND LOWER(g.name) = 'standard');

-- Amazon Bedrock's chat models (docs/architecture/ai-features.md "Amazon Bedrock", roadmap point 60): Claude in an EU
-- region with the installation's own AWS account. Kept like a model on an own server: the region's address
-- (https://bedrock-runtime.<region>.amazonaws.com) and an IAM access key, encrypted, with the entry.
ALTER TABLE ai_model DROP CONSTRAINT ai_model_provider_check;
ALTER TABLE ai_model ADD CONSTRAINT ai_model_provider_check
    CHECK (provider IN ('ANTHROPIC', 'OPENAI', 'GEMINI', 'OLLAMA', 'OPENAI_COMPATIBLE', 'BEDROCK', 'TAVILY', 'TEI'));
ALTER TABLE ai_model DROP CONSTRAINT ai_model_server;
ALTER TABLE ai_model ADD CONSTRAINT ai_model_server
    CHECK ((provider IN ('OLLAMA', 'OPENAI_COMPATIBLE', 'BEDROCK', 'TEI')) = (base_url IS NOT NULL));

-- Connected software that signs in with OAuth (roadmap point 61, docs/architecture/mcp.md "Signing in to the
-- software's server"): a connection's grant - the authorization server found, our client id there, the pending
-- sign-in and the tokens. One grant per connection, deleted with it; secrets and tokens encrypted (SecretCipher).
CREATE TABLE oauth_grant (
    id                          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id                   BIGINT NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    connection_id               BIGINT NOT NULL UNIQUE REFERENCES mcp_connection (id) ON DELETE CASCADE,
    status                      TEXT NOT NULL CHECK (status IN ('AVAILABLE', 'PENDING', 'SIGNED_IN', 'SIGN_IN_NEEDED')),
    resource                    TEXT NOT NULL,            -- the resource indicator (RFC 8707): the connection's address
    issuer                      TEXT NOT NULL,
    authorization_endpoint      TEXT NOT NULL,
    token_endpoint              TEXT NOT NULL,
    registration_endpoint       TEXT,
    metadata_document_supported BOOLEAN NOT NULL DEFAULT FALSE, -- the server reads a client id metadata document
    issuer_parameter_required   BOOLEAN NOT NULL DEFAULT FALSE, -- the server sends iss with the callback (RFC 9207)
    scopes_offered              TEXT,                     -- space-separated, as the server lists them
    client_id                   TEXT,                     -- our metadata document's URL, a registered id, or one entered by hand
    client_id_source            TEXT CHECK (client_id_source IN ('METADATA_DOCUMENT', 'REGISTERED', 'ENTERED')),
    client_secret               TEXT,                     -- encrypted (SecretCipher); null for a public client ("none")
    token_endpoint_auth_method  TEXT,                     -- none | client_secret_basic | client_secret_post
    redirect_uri                TEXT,                     -- the callback address registered
    state                       TEXT UNIQUE,              -- PENDING only
    code_verifier               TEXT,                     -- PENDING only, encrypted
    return_url                  TEXT,                     -- PENDING only: the frontend page to come back to
    started_by_user_id          BIGINT,                   -- the tenant admin who clicked (null: the admin)
    pending_until               TIMESTAMPTZ,              -- PENDING only
    access_token                TEXT,                     -- encrypted
    refresh_token               TEXT,                     -- encrypted
    expires_at                  TIMESTAMPTZ,              -- of the access token; null: the server named none
    scopes_granted              TEXT,
    last_error                  TEXT,                     -- why the last refresh or sign-in failed (no secret, no address)
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL
);
CREATE INDEX oauth_grant_tenant_idx ON oauth_grant (tenant_id);
