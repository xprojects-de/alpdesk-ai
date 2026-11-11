/** The look the frontend applies: name, primary colour ("#rrggbb") and whether there is a logo. */
export interface Branding {
    name: string;
    primaryColor: string;
    hasLogo: boolean;
}

/** What the admin set for a tenant - null = the installation's default - and those defaults. */
export interface TenantBranding {
    name: string | null;
    primaryColor: string | null;
    hasLogo: boolean;
    defaultName: string;
    defaultPrimaryColor: string;
    /** The tenant's own login page is /login/<loginKey> - utils/login-page.util.ts builds the address. */
    loginKey: string;
}

/** A tenant's name and primary colour; null = the installation's default. */
export interface TenantBrandingRequest {
    name: string | null;
    primaryColor: string | null;
}

/** Shown while the installation's look is loading or when it cannot be loaded - the build's own theme. */
export const DEFAULT_BRANDING: Branding = {name: "Zirmo", primaryColor: "#49732a", hasLogo: false};

/**
 * Before the login on /login: no brand at all - one login page for every tenant, so none sees the operator's or another
 * company's look (a tenant's own login page /login/<key> shows its look). No logo and no name; the page's title is NEUTRAL_TITLE_KEY in the user's language (BrandingEffects).
 * The login page sets its own greys (a palette computed from a grey would still be tinted), so the colour here is the
 * build's: nothing is computed or set for it.
 */
export const NEUTRAL_BRANDING: Branding = {name: "", primaryColor: "#49732a", hasLogo: false};

/** The page's title before the login - a translation key. */
export const NEUTRAL_TITLE_KEY = "login.pageTitle";
