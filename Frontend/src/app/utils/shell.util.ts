/** An entry of the shell's menu; the path is absolute ("/app/chat"). */
export interface ShellNavItem {
    path: string;
    label: string;
    icon: string;
}

/** Entries under a small heading - null: no heading (the first group). */
export interface ShellNavGroup {
    label: string | null;
    items: ShellNavItem[];
}

/**
 * Where the page at this URL belongs in the menu: its entry and group - a detail page counts to its list. A group
 * named like its entry is left out ("Mandanten › Mandanten").
 */
export function shellNavItem(groups: ShellNavGroup[], url: string): {group: string | null; label: string} | null {
    const path = url.split(/[?#]/)[0];
    for (const group of groups) {
        for (const item of group.items) {
            if (path === item.path || path.startsWith(item.path + "/")) {
                return {group: group.label === item.label ? null : group.label, label: item.label};
            }
        }
    }
    return null;
}

/** Up to two initials of a name ("Anna Berger" → "AB", "admin" → "A"). */
export function initials(name: string): string {
    return name
        .trim()
        .split(/[\s._-]+/)
        .filter(part => part.length > 0)
        .slice(0, 2)
        .map(part => part.charAt(0).toUpperCase())
        .join("");
}
