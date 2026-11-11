/**
 * An area of a tenant's knowledge ("Vertrieb", "Werkstatt"): its documents are found only by the users and keys
 * assigned to it, and by the tenant admins. A document without an area is company knowledge everyone finds.
 */
export interface KnowledgeArea {
    id: number;
    name: string;
    createdAt: string;
}
