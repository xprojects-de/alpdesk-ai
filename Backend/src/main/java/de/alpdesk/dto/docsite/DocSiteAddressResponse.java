package de.alpdesk.dto.docsite;

import io.micronaut.serde.annotation.Serdeable;

/** Where the website is read for the next hour, below the API's base: "doc-sites/<token>/". */
@Serdeable
public record DocSiteAddressResponse(String path) {
}
