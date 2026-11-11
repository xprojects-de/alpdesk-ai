package de.alpdesk.services.filestore;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.net.URI;
import java.util.Objects;

/**
 * What an S3 store needs that is no secret - stored as JSON in file_store.settings; the secret access key is the
 * store's secret.
 *
 * @param endpoint    the address of a compatible store ("https://s3.example.com", plain http only to an internal host
 *                    the installation names); null: Amazon S3 in the region
 * @param region      Amazon's region, or what a compatible store expects ("us-east-1" when it was left out)
 * @param bucket      the bucket the store reads
 * @param basePath    the folder in the bucket the store stands for, as an absolute path ("/" the whole bucket)
 * @param accessKeyId the access key's ID - shown, unlike its secret
 */
@Serdeable
public record S3Settings(@Nullable String endpoint, String region, String bucket, String basePath, String accessKeyId) {

    /** Another store than before: the secret is not sent to it unasked. Another folder of the bucket is the same. */
    public boolean sameStore(S3Settings other) {
        return Objects.equals(endpoint, other.endpoint) && region.equals(other.region)
                && bucket.equals(other.bucket) && accessKeyId.equals(other.accessKeyId);
    }

    /** The same files as before: the same bucket and folder - another access key (a rotated one) reads the same ones. */
    public boolean sameFiles(S3Settings other) {
        return Objects.equals(endpoint, other.endpoint) && region.equals(other.region)
                && bucket.equals(other.bucket) && basePath.equals(other.basePath);
    }

    /** The address the requests go to - Amazon's own for its region when no endpoint was given. */
    public URI address() {
        return URI.create(endpoint != null ? endpoint : "https://s3." + region + ".amazonaws.com");
    }

    /** The host the store is reached at - named in a failure. */
    public String host() {
        return address().getHost();
    }
}
