-- A tenant's own login page (docs/architecture/branding.md "The tenant's login page"): /login/<login_key> shows the
-- tenant's look before the login. The key is random so nobody can find out by trying which companies use the
-- installation; the admin can renew it, which makes the old address show the neutral page again.
ALTER TABLE tenant ADD COLUMN login_key TEXT;
UPDATE tenant SET login_key = replace(gen_random_uuid()::text, '-', '');
ALTER TABLE tenant ALTER COLUMN login_key SET NOT NULL;
CREATE UNIQUE INDEX tenant_login_key_uq ON tenant (login_key);
