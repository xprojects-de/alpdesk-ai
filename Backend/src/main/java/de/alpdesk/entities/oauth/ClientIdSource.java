package de.alpdesk.entities.oauth;

/** Where our client id at an authorization server came from (docs/architecture/mcp.md "Our client identity"). */
public enum ClientIdSource {
    /** Our client id metadata document's URL - the server reads the document from our address. */
    METADATA_DOCUMENT,
    /** Registered dynamically (RFC 7591). */
    REGISTERED,
    /** Entered by a tenant admin from the server's portal. */
    ENTERED
}
