package de.alpdesk.services.filestore;

import de.alpdesk.services.common.ServiceException;
import io.micronaut.http.HttpStatus;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S3 stores (docs/architecture/file-stores.md "S3"): a bucket of Amazon S3 or of a compatible store, listed and read
 * with the store's access key through the AWS SDK. The SDK takes nothing of the server it runs on - no credentials from
 * its environment, ~/.aws or an instance role (a tenant admin's store would read with the operator's rights), no
 * profile file, no endpoint from the environment, no HTTP client found by service loading. The secret never goes over
 * the wire: S3 requests are signed with it.
 */
@Singleton
public class S3StoreClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    /** A store hanging must not hold one of the two task threads. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    /** Pages of one folder's listing at most - S3 answers up to 1000 keys a page. */
    private static final int MAX_PAGES = 1000;
    /** The SDK's codes of a refused access key - the store answers them with 403 mostly, some with 400. */
    private static final Set<String> KEY_REFUSED = Set.of("InvalidAccessKeyId", "SignatureDoesNotMatch",
            "AccessDenied", "InvalidSecurity", "AuthorizationHeaderMalformed");
    private static final ProfileFile NO_PROFILE = ProfileFile.builder()
            .content(new ByteArrayInputStream(new byte[0]))
            .type(ProfileFile.Type.CONFIGURATION)
            .build();

    private final FileStoreAddresses addresses;
    /** The JDK's HTTP connection - shared by every session, not closed with one. */
    private final SdkHttpClient http;

    public S3StoreClient(FileStoreAddresses addresses) {
        this.addresses = addresses;
        this.http = UrlConnectionHttpClient.builder()
                .connectionTimeout(CONNECT_TIMEOUT)
                .socketTimeout(READ_TIMEOUT)
                .build();
    }

    @PreDestroy
    void close() {
        http.close();
    }

    /**
     * Lists the store's folder - its first names. S3 knows no folders: a folder with nothing below it cannot be told
     * from a missing one, so both answer fileStore.folderNotFound (the bucket itself may be empty).
     */
    public CheckResult check(S3Settings settings, String secret) {
        try (Session session = session(settings, secret)) {
            ListObjectsV2Response page = session.page(prefix(settings.basePath()), FileStoreClient.CHECK_ENTRIES + 1,
                    null);
            boolean nothingBelow = page.contents().isEmpty() && page.commonPrefixes().isEmpty();
            if (nothingBelow && !settings.basePath().equals("/")) {
                throw folderNotFound(settings.basePath());
            }
            List<String> names = new ArrayList<>();
            for (RemoteFile entry : session.entries(page, prefix(settings.basePath()))) {
                names.add(entry.name());
            }
            names.sort(Comparator.naturalOrder());
            return CheckResult.ok(null, names.subList(0, Math.min(FileStoreClient.CHECK_ENTRIES, names.size())));
        }
    }

    /** A session for reading: the endpoint checked against the internal networks again, no request sent yet. */
    public FileStoreSession open(S3Settings settings, String secret) {
        return session(settings, secret);
    }

    private Session session(S3Settings settings, String secret) {
        if (settings.endpoint() != null) {
            addresses.requireAllowedEndpoint(settings.address());
        }
        S3Client client = S3Client.builder()
                .httpClient(http)
                .region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKeyId(), secret)))
                // Always set, also for Amazon: else the SDK would read an endpoint from the environment
                .endpointOverride(settings.address())
                // A compatible store is addressed as https://host/bucket - every one speaks that
                .forcePathStyle(settings.endpoint() != null)
                // Checksums only where S3 requires them - compatible stores do not all know the newer ones
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(config -> config
                        .defaultProfileFile(NO_PROFILE)
                        .apiCallTimeout(CALL_TIMEOUT)
                        .retryStrategy(retry -> retry.maxAttempts(1)))
                .build();
        return new Session(client, settings);
    }

    /** The key prefix of a folder: "/" the whole bucket (""), "/logs" "logs/". */
    static String prefix(String folder) {
        String key = folder.startsWith("/") ? folder.substring(1) : folder;
        return key.isEmpty() || key.endsWith("/") ? key : key + "/";
    }

    /** The object key of a file's path: "/logs/a.log" is "logs/a.log". */
    static String key(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private final class Session implements FileStoreSession {

        private final S3Client client;
        private final S3Settings settings;

        private Session(S3Client client, S3Settings settings) {
            this.client = client;
            this.settings = settings;
        }

        @Override
        public List<RemoteFile> list(String folder) {
            String prefix = prefix(folder);
            List<RemoteFile> entries = new ArrayList<>();
            String token = null;
            int pages = 0;
            do {
                ListObjectsV2Response page = page(prefix, null, token);
                entries.addAll(entries(page, prefix));
                // Empty pages without end would hold the thread without filling the memory
                if (entries.size() > MAX_ENTRIES || ++pages > MAX_PAGES) {
                    throw StoreFailures.tooManyEntries(folder);
                }
                token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
                if (Boolean.TRUE.equals(page.isTruncated()) && token == null) {
                    // More to come but no way to ask for it: the pages read are not the folder - a knowledge source
                    // must not take them as all of it.
                    throw StoreFailures.listingBroken(folder);
                }
            } while (token != null);
            entries.sort(Comparator.comparing(RemoteFile::name));
            return entries;
        }

        /**
         * At most maxBytes from offset on, asked for as a range. A store answering with the whole object (it ignores
         * the range) is read up to the offset and cut off after maxBytes - never more than that is kept.
         */
        @Override
        public byte[] read(String path, long offset, int maxBytes) {
            if (maxBytes <= 0) {
                return new byte[0];
            }
            String range = "bytes=" + offset + "-" + (offset + maxBytes - 1);
            try (ResponseInputStream<GetObjectResponse> object = client.getObject(request -> request
                    .bucket(settings.bucket()).key(key(path)).range(range))) {
                if (object.response().contentRange() == null) {
                    skip(object, offset);
                }
                byte[] read = object.readNBytes(maxBytes);
                object.abort();
                return read;
            } catch (S3Exception e) {
                if (e.statusCode() == 416) {
                    return new byte[0];
                }
                throw failure(e, path);
            } catch (SdkException e) {
                throw failure(e, path);
            } catch (IOException e) {
                throw StoreFailures.readFailed(path, e);
            }
        }

        /** An object is replaced whole by its put - nothing half written to rename. */
        @Override
        public void write(String path, byte[] content) {
            try {
                client.putObject(request -> request.bucket(settings.bucket()).key(key(path)), RequestBody.fromBytes(content));
            } catch (SdkException e) {
                ServiceException failure = failure(e, path);
                // a key that may read but not write answers 403 - said as what it is, not as a refused key
                throw failure.code().equals("fileStore.readFailed") || failure.code().equals("fileStore.accessKeyRefused")
                        ? StoreFailures.writeFailed(path, e) : failure;
            }
        }

        @Override
        public void close() {
            client.close();
        }

        private ListObjectsV2Response page(String prefix, Integer maxKeys, String token) {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(settings.bucket())
                    .prefix(prefix)
                    .delimiter("/")
                    .maxKeys(maxKeys)
                    .continuationToken(token)
                    .build();
            try {
                return client.listObjectsV2(request);
            } catch (SdkException e) {
                throw failure(e, "/" + prefix);
            }
        }

        /**
         * A page's files and subfolders, named below the prefix. The object named like the folder itself (what a
         * console creates for an empty folder) is neither.
         */
        private List<RemoteFile> entries(ListObjectsV2Response page, String prefix) {
            List<RemoteFile> entries = new ArrayList<>();
            for (S3Object object : page.contents()) {
                String name = object.key().substring(prefix.length());
                if (!name.isEmpty() && !name.endsWith("/")) {
                    entries.add(new RemoteFile(name, object.size(), object.lastModified(), false));
                }
            }
            for (CommonPrefix folder : page.commonPrefixes()) {
                String name = folder.prefix().substring(prefix.length());
                name = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                if (!name.isEmpty()) {
                    entries.add(new RemoteFile(name, 0, null, true));
                }
            }
            return entries;
        }

        private ServiceException failure(SdkException e, String path) {
            if (e instanceof S3Exception refused) {
                AwsErrorDetails details = refused.awsErrorDetails();
                String code = details == null || details.errorCode() == null ? "" : details.errorCode();
                if (code.equals("NoSuchBucket")) {
                    return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.bucketNotFound", "The bucket "
                            + settings.bucket() + " does not exist", Map.of("bucket", settings.bucket()));
                }
                if (KEY_REFUSED.contains(code) || refused.statusCode() == 401 || refused.statusCode() == 403) {
                    return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.accessKeyRefused", "The store "
                            + "refused the access key or its secret, or the key may not read the bucket ("
                            + code + ")");
                }
                return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.readFailed", "Reading " + path
                        + " failed: " + code + " " + refused.statusCode(), Map.of("path", path));
            }
            if (e instanceof SdkClientException && causedBy(e, SSLException.class)) {
                return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.tlsFailed", "The TLS connection to "
                        + settings.host() + " failed: " + e.getMessage(), Map.of("host", settings.host()));
            }
            if (e instanceof SdkClientException && causedBy(e, IOException.class)) {
                return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.unreachable", "The store "
                        + settings.host() + " could not be reached: " + e.getMessage(),
                        Map.of("host", settings.host()));
            }
            return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.readFailed", "Reading " + path
                    + " failed: " + e.getMessage(), Map.of("path", path));
        }
    }

    private static void skip(InputStream in, long bytes) throws IOException {
        long left = bytes;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    return;
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static ServiceException folderNotFound(String folder) {
        return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.folderNotFound",
                "The folder " + folder + " does not exist, holds nothing or may not be read", Map.of("path", folder));
    }
}
