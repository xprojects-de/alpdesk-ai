package de.alpdesk.entities.filestore;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/** A server the app reads files from and writes files to for the tenant (docs/architecture/file-stores.md). */
@MappedEntity("file_store")
public record FileStore(

        @Id
        @GeneratedValue
        Long id,

        Long tenantId,

        String name,

        FileStoreType type,

        /**
         * What the type needs that is no secret, as JSON - for SFTP and FTPS a services.filestore.ServerSettings, for S3
         * a services.filestore.S3Settings, for SharePoint a services.filestore.GraphSettings.
         */
        String settings,

        /**
         * The password (S3: the secret access key, SharePoint: the app's client secret), encrypted
         * (security.SecretCipher) - never sent out.
         */
        String secret,

        /** When it was checked last; null: never. */
        @Nullable
        Instant checkedAt,

        /** The error code the last check ended with; null: it worked (or it was never checked). */
        @Nullable
        String checkError,

        /** Scheduled tasks may write into it (a CSV of rows) - off unless a tenant admin turns it on. */
        boolean taskWrite,

        Instant createdAt,

        Instant updatedAt
) {
}
