package de.alpdesk.entities.ai;

/** Whose API key a model call used. */
public enum KeySource {
    /** The tenant's own key - not billed. */
    OWN,
    /** The admin's global key - billed to the tenant. */
    GLOBAL
}
