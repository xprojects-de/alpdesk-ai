-- Whose user it is (docs/architecture/tenancy.md "One person, users in several tenants"): set when the admin adds a
-- person's user to another tenant ("Benutzer aus anderem Mandanten") - the id of the user it was first taken from, on
-- every user of that person. NULL for a user created by name. Without the WAM proxy only users of the same person
-- are listed in the account and switched to: the same username alone may be another person's, created by another
-- tenant's admin (security review 12 November 2026).
ALTER TABLE tenant_user ADD COLUMN person BIGINT;
