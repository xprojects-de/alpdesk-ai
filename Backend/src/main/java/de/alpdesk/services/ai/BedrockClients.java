package de.alpdesk.services.ai;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The AWS SDK's clients for the catalog's Amazon Bedrock models (docs/architecture/ai-features.md "Amazon Bedrock"):
 * one pair per address and access key, kept for the next call - an SDK client holds threads of its own. The region is
 * read from the address the admin entered (bedrock-runtime.REGION.amazonaws.com), and the requests go to that address
 * only. Like S3StoreClient, the SDK takes nothing of the server it runs on: no credentials from its environment, ~/.aws
 * or an instance role, no profile file, no HTTP client found by service loading. The secret never goes over the wire:
 * the requests are signed with it.
 */
@Singleton
public class BedrockClients {

    /** The region in a Bedrock runtime address - also of a FIPS or a VPC endpoint (vpce-....bedrock-runtime.REGION.vpce...). */
    private static final Pattern REGION = Pattern.compile("(?:^|\\.)bedrock-runtime(?:-fips)?\\.([a-z]{2}(?:-[a-z]+)+-\\d+)\\.");
    /** An IAM access key as the catalog keeps it: its id, a colon, its secret. */
    private static final Pattern ACCESS_KEY = Pattern.compile("([A-Z0-9]{16,128}):(\\S+)");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final ProfileFile NO_PROFILE = ProfileFile.builder()
            .content(new ByteArrayInputStream(new byte[0]))
            .type(ProfileFile.Type.CONFIGURATION)
            .build();

    private final Duration timeout;
    /** Where every request goes instead of the model's address: null in the application, the fake's in ModelServerSpec. */
    private final @Nullable URI testAddress;
    /** Built at the first Bedrock call - an installation without Bedrock starts no threads for it. */
    private @Nullable SdkHttpClient http;
    private @Nullable SdkAsyncHttpClient streamingHttp;
    private final Map<Account, BedrockRuntimeClient> clients = new ConcurrentHashMap<>();
    private final Map<Account, BedrockRuntimeAsyncClient> streamingClients = new ConcurrentHashMap<>();

    @Inject
    public BedrockClients(@Value("${app.ai.timeout}") Duration timeout) {
        this(timeout, null);
    }

    BedrockClients(Duration timeout, @Nullable String testAddress) {
        this.timeout = timeout;
        this.testAddress = testAddress == null ? null : URI.create(testAddress);
    }

    @PreDestroy
    synchronized void close() {
        clients.values().forEach(BedrockRuntimeClient::close);
        streamingClients.values().forEach(BedrockRuntimeAsyncClient::close);
        if (http != null) {
            http.close();
        }
        if (streamingHttp != null) {
            streamingHttp.close();
        }
    }

    /** The region named in a Bedrock runtime address - empty for any other address, and for one that is no URL. */
    public static Optional<String> region(String address) {
        String host;
        try {
            host = URI.create(address).getHost();
        } catch (IllegalArgumentException notAUrl) {
            return Optional.empty();
        }
        Matcher matcher = REGION.matcher(host + ".");
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** Whether the key is an IAM access key as the catalog keeps it: "ACCESS_KEY_ID:SECRET_ACCESS_KEY". */
    public static boolean isAccessKey(String key) {
        return ACCESS_KEY.matcher(key).matches();
    }

    BedrockRuntimeClient client(String address, String key) {
        return clients.computeIfAbsent(new Account(address, key), account -> BedrockRuntimeClient.builder()
                .httpClient(http())
                .region(account.region())
                .credentialsProvider(account.credentials())
                .endpointOverride(endpoint(account))
                // Asked again by langchain4j where wanted (AiModels' retries) - not by the SDK as well
                .overrideConfiguration(configuration(timeout))
                .build());
    }

    BedrockRuntimeAsyncClient streamingClient(String address, String key) {
        return streamingClients.computeIfAbsent(new Account(address, key), account -> BedrockRuntimeAsyncClient.builder()
                .httpClient(streamingHttp())
                .region(account.region())
                .credentialsProvider(account.credentials())
                .endpointOverride(endpoint(account))
                // No time limit for the whole call: a streamed answer runs as long as it sends (StreamStop watches it)
                .overrideConfiguration(configuration(null))
                .build());
    }

    // A model thinks for minutes before its first word: a read waits as long as a whole answer may take.
    private synchronized SdkHttpClient http() {
        if (http == null) {
            http = UrlConnectionHttpClient.builder().connectionTimeout(CONNECT_TIMEOUT).socketTimeout(timeout).build();
        }
        return http;
    }

    private synchronized SdkAsyncHttpClient streamingHttp() {
        if (streamingHttp == null) {
            streamingHttp = NettyNioAsyncHttpClient.builder().connectionTimeout(CONNECT_TIMEOUT).readTimeout(timeout)
                    .build();
        }
        return streamingHttp;
    }

    private URI endpoint(Account account) {
        return testAddress != null ? testAddress : URI.create(account.address());
    }

    private static ClientOverrideConfiguration configuration(@Nullable Duration callTimeout) {
        ClientOverrideConfiguration.Builder configuration = ClientOverrideConfiguration.builder()
                .defaultProfileFile(NO_PROFILE)
                .retryStrategy(retry -> retry.maxAttempts(1));
        return callTimeout == null ? configuration.build() : configuration.apiCallTimeout(callTimeout).build();
    }

    /** The address and key of a catalog entry - checked by AiModelService when it was saved. */
    private record Account(String address, String key) {

        /** Never the key - a record would show it. */
        @Override
        public String toString() {
            return "Account[" + address + "]";
        }

        Region region() {
            return Region.of(BedrockClients.region(address).orElseThrow(
                    () -> new IllegalStateException("A Bedrock address without a region")));
        }

        StaticCredentialsProvider credentials() {
            Matcher matcher = ACCESS_KEY.matcher(key == null ? "" : key);
            if (!matcher.matches()) {
                throw new IllegalStateException("A Bedrock key that is no access key");
            }
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(matcher.group(1), matcher.group(2)));
        }
    }
}
