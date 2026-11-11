/**
 * The path the app is served under, without a trailing slash: "" at the root, "/alpdesk-ai" behind a reverse proxy
 * at /alpdesk-ai. The backend writes it into index.html's base href (micronaut.server.context-path), and every API
 * address is built on it - the production build is served by the same backend it talks to.
 */
export function appBasePath(baseUri: string): string {
    return new URL(baseUri).pathname.replace(/\/+$/, "");
}
