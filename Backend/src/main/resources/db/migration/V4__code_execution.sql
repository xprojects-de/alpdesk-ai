-- Code execution (docs/architecture/code-execution.md "One sandbox per tenant"): the tenant's own sandbox instances for
-- the chat's Python and shell (run_python, run_shell), comma separated, and the token they were started with - entered
-- by the admin; code execution is on while both are set. The token is encrypted (security.SecretCipher), its hint
-- shows its last characters.
ALTER TABLE tenant
    ADD COLUMN sandbox_urls       TEXT,
    ADD COLUMN sandbox_token      TEXT,
    ADD COLUMN sandbox_token_hint TEXT;

-- The language of a tenant user's frontend (docs/architecture/i18n.md): set by the admin or a tenant admin when the user
-- is created, changed by the user in the frontend's menu. A language tag the frontend offers ("en", "de") - the
-- backend keeps no list of languages and only checks the tag's form. NULL only for users from before this column and
-- means English.
ALTER TABLE tenant_user
    ADD COLUMN language TEXT;
