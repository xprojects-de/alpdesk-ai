/** Light or dark: "system" follows the operating system (prefers-color-scheme), the others are the user's choice. */
export type ColorScheme = "system" | "light" | "dark";

/** Remembered in this browser, for every login in it - a choice of the person at the screen, not of an account. */
export const COLOR_SCHEME_KEY = "color_scheme";

const CHOICES: readonly ColorScheme[] = ["system", "light", "dark"];

export function readColorScheme(storage: Pick<Storage, "getItem"> | null): ColorScheme {
    try {
        const stored = storage?.getItem(COLOR_SCHEME_KEY);
        return CHOICES.find(choice => choice === stored) ?? "system";
    } catch {
        return "system";
    }
}

export function storeColorScheme(storage: Pick<Storage, "setItem"> | null, scheme: ColorScheme): void {
    try {
        storage?.setItem(COLOR_SCHEME_KEY, scheme);
    } catch {
        // Storage disabled: the choice holds until the page is reloaded.
    }
}

/**
 * The value of the CSS property color-scheme: Material's colours and the app's tokens are light-dark() pairs
 * (styles.scss), so this one property switches the whole page.
 */
export function cssColorScheme(scheme: ColorScheme): string {
    return scheme === "system" ? "light dark" : scheme;
}

export function applyColorScheme(root: HTMLElement, scheme: ColorScheme): void {
    root.style.colorScheme = cssColorScheme(scheme);
}

/** The browser's localStorage - null where reading it alone throws (blocked site data). */
export function localStorageOf(view: Window | null): Storage | null {
    try {
        return view?.localStorage ?? null;
    } catch {
        return null;
    }
}
