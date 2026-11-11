package de.alpdesk.dto.filestore;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A file store; which fields count depends on its type. The password (S3: the secret access key, SharePoint: the client
 * secret) left out: unchanged - but another server, bucket or app than before needs it again, it is not sent elsewhere
 * unasked. Base path left out: "/".
 */
@Serdeable
public record FileStoreRequest(
        String name,
        /** SFTP, FTPS or S3. */
        String type,
        /** SFTP, FTPS: a host name or an IP address - no protocol, port, user or path. */
        @Nullable String host,
        /** SFTP, FTPS: left out the type's (22, 21). */
        @Nullable Integer port,
        /** SFTP, FTPS. */
        @Nullable String username,
        /** SFTP, FTPS: the password; S3: the secret access key; SharePoint: the app's client secret. */
        @Nullable String password,
        /** The folder the store stands for, e.g. "/logs" - for S3 a folder in the bucket. */
        @Nullable String basePath,
        /** S3: a compatible store's address, e.g. "https://s3.example.com"; left out: Amazon S3. */
        @Nullable String endpoint,
        /** S3: the region - required for Amazon S3, left out "us-east-1" for a compatible store. */
        @Nullable String region,
        /** S3. */
        @Nullable String bucket,
        /** S3: the access key's ID. */
        @Nullable String accessKeyId,
        /** SharePoint: the customer's Entra ID (Microsoft's tenant ID), a GUID. */
        @Nullable String directoryId,
        /** SharePoint: the app registration's client ID, a GUID. */
        @Nullable String clientId,
        /** SharePoint: the site's address, e.g. "https://company.sharepoint.com/sites/projects" - or a OneDrive's. */
        @Nullable String siteUrl,
        /** SharePoint: the document library's name; left out: the site's default one. */
        @Nullable String library,
        /** Whether scheduled tasks may write into the store; left out on create: no, on update: unchanged. */
        @Nullable Boolean taskWrite,
        /** IMAP: SSL (993, the default), STARTTLS (143) or NONE - unencrypted only to an internal host the installation names. */
        @Nullable String security,
        /** IMAP: the mailbox folder; left out: INBOX. */
        @Nullable String mailbox,
        /** IMAP: the messages of the last days listed (1-3650); left out: 365. */
        @Nullable Integer days
) {
}
