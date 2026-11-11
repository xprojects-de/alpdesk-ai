package de.alpdesk.services.docsite;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * The short-lived address of a handbook's website (docs/architecture/doc-sites.md "Security"): an iframe cannot send
 * an Authorization header, so the right to read one website for a while is carried in the path -
 * {@code <tenantId>.<siteId>.<expiresAt>.<signature>}. Signed with a key derived from the JWT secret, so nothing is
 * stored and a token never passes for a JWT or the other way round.
 */
@Singleton
public class DocSiteTokens {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public DocSiteTokens(@Value("${micronaut.security.token.jwt.signatures.secret.generator.secret}") String jwtSecret) {
        this.key = new SecretKeySpec(hmac(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM),
                "alpdesk doc-site tokens"), ALGORITHM);
    }

    public String create(Long tenantId, Long siteId, Instant expiresAt) {
        String payload = tenantId + "." + siteId + "." + expiresAt.getEpochSecond();
        return payload + "." + signature(payload);
    }

    /** The website the token opens; empty for a malformed, forged or expired token. */
    public Optional<Grant> verify(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        String payload = parts[0] + "." + parts[1] + "." + parts[2];
        if (!MessageDigest.isEqual(signature(payload).getBytes(StandardCharsets.US_ASCII),
                parts[3].getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        try {
            if (Instant.ofEpochSecond(Long.parseLong(parts[2])).isBefore(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(new Grant(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String signature(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, payload));
    }

    private static byte[] hmac(SecretKeySpec key, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** Who may read which website: the tenant and the website's id. */
    public record Grant(Long tenantId, Long siteId) {
    }
}
