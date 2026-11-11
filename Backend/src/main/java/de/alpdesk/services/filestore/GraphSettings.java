package de.alpdesk.services.filestore;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.net.URI;
import java.util.Objects;

/**
 * What a SharePoint store needs that is no secret - stored as JSON in file_store.settings; the app's client secret is
 * the store's secret.
 *
 * @param directoryId the customer's Entra ID (its "tenant" at Microsoft - called directory so it is never mistaken for
 *                    ours), a GUID
 * @param clientId    the app registration's ID, a GUID
 * @param siteUrl     the site's address, "https://company.sharepoint.com/sites/projects" - a OneDrive's is
 *                    "https://company-my.sharepoint.com/personal/someone_company_com"
 * @param library     the document library's name; null: the site's default one
 * @param basePath    the folder in the library the store stands for, as an absolute path ("/" the whole library)
 */
@Serdeable
public record GraphSettings(String directoryId, String clientId, String siteUrl, @Nullable String library,
                            String basePath) {

    /**
     * Another app than before: the secret is not sent for it unasked. Another site, library or folder is the same app -
     * its secret goes to Microsoft's login only, whatever site is read.
     */
    public boolean sameApp(GraphSettings other) {
        return directoryId.equals(other.directoryId) && clientId.equals(other.clientId);
    }

    /** The same files as before: the same site, library and folder - whichever app reads them. */
    public boolean sameFiles(GraphSettings other) {
        return siteUrl.equals(other.siteUrl) && Objects.equals(library, other.library)
                && basePath.equals(other.basePath);
    }

    /** The site's host - named in a failure. */
    public String host() {
        return URI.create(siteUrl).getHost();
    }
}
