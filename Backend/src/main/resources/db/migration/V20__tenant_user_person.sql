-- Whose user it is (docs/architecture/tenancy.md "One person, users in several tenants"): set when the admin adds a
-- person's user to another tenant ("Benutzer aus anderem Mandanten") - the id of the user it was first taken from, on
-- every user of that person. NULL for a user created by name. Without the WAM proxy only users of the same person
-- are listed in the account and switched to: the same username alone may be another person's, created by another
-- tenant's admin (security review 12 November 2026).
ALTER TABLE tenant_user ADD COLUMN person BIGINT;

-- Wrong passwords lock a tenant user's account for a while (docs/architecture/tenancy.md "Password login", roadmap
-- point 70): failed_logins counts the attempts since the last right password, locked_until is set when they reach
-- app.login.max-attempts - until then the account takes no password. Counted per account, not per address: behind
-- the reverse proxy every request has the proxy's address.
ALTER TABLE tenant_user ADD COLUMN failed_logins INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tenant_user ADD COLUMN locked_until TIMESTAMPTZ;
