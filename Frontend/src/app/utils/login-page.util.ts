/**
 * A tenant's own login page /login/<key> (docs/architecture/branding.md): its key is remembered in this browser, so a
 * logout or an expired session leads back to that page instead of the neutral one.
 */
export const LOGIN_KEY_KEY = "login_key";

export function readLoginKey(storage: Pick<Storage, "getItem"> | null): string | null {
    try {
        return storage?.getItem(LOGIN_KEY_KEY) || null;
    } catch {
        return null;
    }
}

/** null forgets it - a key nobody has any more. */
export function storeLoginKey(storage: Pick<Storage, "setItem" | "removeItem"> | null, key: string | null): void {
    try {
        if (key) {
            storage?.setItem(LOGIN_KEY_KEY, key);
        } else {
            storage?.removeItem(LOGIN_KEY_KEY);
        }
    } catch {
        // Storage disabled: a logout shows the neutral login page.
    }
}

/** Where a logout leads: the remembered tenant's login page, else /login. */
export function loginPath(key: string | null): string[] {
    return key ? ["/login", key] : ["/login"];
}

/** The address to hand on - below the base href, so it holds behind a reverse proxy under a sub-path too. */
export function loginAddress(baseUri: string, key: string): string {
    return new URL(`login/${encodeURIComponent(key)}`, baseUri).href;
}
