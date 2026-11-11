package de.alpdesk.entities.oauth;

/** Where a connection's sign-in stands (docs/architecture/mcp.md "Signing in to the software's server"). */
public enum GrantStatus {
    /** The server asks for a sign-in; nobody signed in yet. */
    AVAILABLE,
    /** A tenant admin is at the server's sign-in page - the state and the verifier wait for the callback. */
    PENDING,
    /** Tokens are there; they renew themselves when used. */
    SIGNED_IN,
    /** The server no longer takes the refresh token - someone must sign in again. */
    SIGN_IN_NEEDED
}
