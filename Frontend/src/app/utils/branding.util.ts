import {
    argbFromHex,
    DynamicColor,
    Hct,
    hexFromArgb,
    MaterialDynamicColors,
    SchemeContent,
} from "@material/material-color-utilities";
import {DEFAULT_BRANDING, TenantBrandingRequest} from "../models/branding.model";

const HEX_COLOR = /^#[0-9a-fA-F]{6}$/;

/**
 * The colour roles of Angular Material 3 (its --mat-sys-* variables), computed from one colour with Material's
 * "content" scheme: it keeps a brand colour as it is (the primary container is the colour itself) - "tonal spot" would
 * tone it down. Error colours are left to the theme: red stays red, and so are the surfaces and the text on them:
 * neutral, whatever the brand colour (surfaces computed from it are tinted - they looked violet), set in styles.scss.
 */
const ROLES: Record<string, DynamicColor> = {
    primary: MaterialDynamicColors.primary,
    "on-primary": MaterialDynamicColors.onPrimary,
    "primary-container": MaterialDynamicColors.primaryContainer,
    "on-primary-container": MaterialDynamicColors.onPrimaryContainer,
    "inverse-primary": MaterialDynamicColors.inversePrimary,
    "primary-fixed": MaterialDynamicColors.primaryFixed,
    "primary-fixed-dim": MaterialDynamicColors.primaryFixedDim,
    "on-primary-fixed": MaterialDynamicColors.onPrimaryFixed,
    "on-primary-fixed-variant": MaterialDynamicColors.onPrimaryFixedVariant,
    secondary: MaterialDynamicColors.secondary,
    "on-secondary": MaterialDynamicColors.onSecondary,
    "secondary-container": MaterialDynamicColors.secondaryContainer,
    "on-secondary-container": MaterialDynamicColors.onSecondaryContainer,
    "secondary-fixed": MaterialDynamicColors.secondaryFixed,
    "secondary-fixed-dim": MaterialDynamicColors.secondaryFixedDim,
    "on-secondary-fixed": MaterialDynamicColors.onSecondaryFixed,
    "on-secondary-fixed-variant": MaterialDynamicColors.onSecondaryFixedVariant,
    tertiary: MaterialDynamicColors.tertiary,
    "on-tertiary": MaterialDynamicColors.onTertiary,
    "tertiary-container": MaterialDynamicColors.tertiaryContainer,
    "on-tertiary-container": MaterialDynamicColors.onTertiaryContainer,
    "tertiary-fixed": MaterialDynamicColors.tertiaryFixed,
    "tertiary-fixed-dim": MaterialDynamicColors.tertiaryFixedDim,
    "on-tertiary-fixed": MaterialDynamicColors.onTertiaryFixed,
    "on-tertiary-fixed-variant": MaterialDynamicColors.onTertiaryFixedVariant,
    "on-surface-variant": MaterialDynamicColors.onSurfaceVariant,
    "inverse-surface": MaterialDynamicColors.inverseSurface,
    "inverse-on-surface": MaterialDynamicColors.inverseOnSurface,
    outline: MaterialDynamicColors.outline,
    "outline-variant": MaterialDynamicColors.outlineVariant,
    "surface-tint": MaterialDynamicColors.surfaceTint,
    shadow: MaterialDynamicColors.shadow,
    scrim: MaterialDynamicColors.scrim,
};

export function isHexColor(value: string): boolean {
    return HEX_COLOR.test(value);
}

/**
 * The --mat-sys-* colour variables for this primary colour ("#rrggbb"), each as light-dark(light, dark) - the page's
 * color-scheme picks one - plus the app's own --app-brand (the colour itself, in both) and the text on it. None for
 * the build's own colour: its theme (theme-colors.scss, styles.scss) stays exactly as built.
 */
export function themeVariables(primaryColor: string): Record<string, string> {
    if (primaryColor.toLowerCase() === DEFAULT_BRANDING.primaryColor) {
        return {};
    }
    const source = Hct.fromInt(argbFromHex(primaryColor));
    const light = new SchemeContent(source, false, 0);
    const dark = new SchemeContent(source, true, 0);
    const variables: Record<string, string> = {
        "--app-brand": primaryColor.toLowerCase(),
        // The primary container is the colour itself, so the text on it fits the colour.
        "--app-on-brand": hexFromArgb(MaterialDynamicColors.onPrimaryContainer.getArgb(light)),
    };
    for (const [role, color] of Object.entries(ROLES)) {
        variables[`--mat-sys-${role}`] =
            `light-dark(${hexFromArgb(color.getArgb(light))}, ${hexFromArgb(color.getArgb(dark))})`;
    }
    return variables;
}

export interface TenantBrandingFormValue {
    name: string;
    primaryColor: string;
}

/** The request of the branding dialog: trimmed values, an empty one = the installation's default, colours in lower case. */
export function buildTenantBrandingRequest(value: TenantBrandingFormValue): TenantBrandingRequest {
    const name = value.name.trim();
    const color = value.primaryColor.trim();
    return {name: name || null, primaryColor: color ? color.toLowerCase() : null};
}
