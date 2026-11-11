package de.alpdesk.dto.filestore;

import de.alpdesk.entities.filestore.FileStoreType;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/**
 * A file store - its password or secret never goes out. host, port, username and fingerprint belong to SFTP and FTPS
 * (fingerprint: the server's key or certificate a tenant admin confirmed, null: none); endpoint, region, bucket and
 * accessKeyId to S3 (endpoint null: Amazon S3); directoryId, clientId, siteUrl and library to SharePoint (library null:
 * the site's default one). checkedAt and checkError: the last check (checkError null: it worked).
 */
@Serdeable
public record FileStoreResponse(
        Long id,
        String name,
        FileStoreType type,
        @Nullable String host,
        @Nullable Integer port,
        @Nullable String username,
        String basePath,
        @Nullable String fingerprint,
        @Nullable String endpoint,
        @Nullable String region,
        @Nullable String bucket,
        @Nullable String accessKeyId,
        @Nullable String directoryId,
        @Nullable String clientId,
        @Nullable String siteUrl,
        @Nullable String library,
        @Nullable Instant checkedAt,
        @Nullable String checkError,
        boolean taskWrite,
        @Nullable String security,
        @Nullable String mailbox,
        @Nullable Integer days
) {
}
