package de.alpdesk.services.branding;

/** The look the frontend applies: name, primary colour ("#rrggbb") and whether there is a logo. */
public record Branding(String name, String primaryColor, boolean hasLogo) {
}
