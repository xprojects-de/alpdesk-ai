package de.alpdesk.security;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Refuses to start outside development and tests when a secret would fall back to the default
 * committed in application.properties: anyone could then forge an admin JWT, log in or decrypt the
 * stored API keys. The container sets all of them (Backend/podman); {@code ./gradlew run} runs in the
 * "dev" environment and the specs in "test", where the defaults are fine. A database password left as the placeholder of the production
 * .env (podman/public/.env.example) is refused as well.
 */
@Context
public class ProductionSecretsCheck {

    private static final Logger LOG = LoggerFactory.getLogger(ProductionSecretsCheck.class);
    private static final Set<String> DEFAULTS_ALLOWED = Set.of(Environment.DEVELOPMENT, Environment.TEST);
    private static final List<String> REQUIRED = List.of("APP_USERNAME", "APP_PASSWORD", "APP_SECRET_KEY",
            "JWT_GENERATOR_SIGNATURE_SECRET");
    private static final String PLACEHOLDER = "CHANGE_ME";
    /**
     * Values that are in the repository (application.properties, application-dev.properties, docker-compose.yml) -
     * known to anyone who can read it, so as good as no secret.
     */
    private static final Set<String> PUBLISHED = Set.of("alpdesk",
            "Pjux3Kv2gJTINYEmJmjFtWy/opbGEuVfOLXhEeija4Y=",
            "y/KKZEFOKOx5HWtBKW6dTGgB/IfQy94uR/m+tqjOL+w=",
            "Erl0nIWS5qd226RZ0E0stgq47uWP1ddAfILRGwCTkMeZSSg0ZAFRBldEL09McKP8zy-bE5Mc9GhnhJ8rXaSzfA",
            "local-test-secret-change-me-local-test-secret-change-me",
            "local-db-password");
    /** Minimum lengths: a guessable admin password or a short JWT key can be brute-forced. */
    private static final Map<String, Integer> MIN_LENGTH = Map.of("APP_PASSWORD", 12, "JWT_GENERATOR_SIGNATURE_SECRET", 32);

    public ProductionSecretsCheck(Environment environment, @Value("${app.password}") String password) {
        this(environment.getActiveNames(), System::getenv, password);
    }

    /** For tests: the active environments and where the environment variables come from. */
    ProductionSecretsCheck(Set<String> activeEnvironments, Function<String, String> env, String password) {
        if (activeEnvironments.stream().anyMatch(DEFAULTS_ALLOWED::contains)) {
            return;
        }
        List<String> problems = new ArrayList<>();
        for (String name : REQUIRED) {
            String value = env.apply(name);
            if (value == null || value.isBlank() || value.equals(PLACEHOLDER) || PUBLISHED.contains(value.trim())
                    || value.trim().length() < MIN_LENGTH.getOrDefault(name, 1)) {
                problems.add(name);
            }
        }
        if (PLACEHOLDER.equals(password)) {
            problems.add("APP_PASSWORD");
        }
        // The database password may come another way (or be none, e.g. a trusted socket) - but never the placeholder.
        String databasePassword = env.apply("DATASOURCES_DEFAULT_PASSWORD");
        if (databasePassword != null && (databasePassword.equals(PLACEHOLDER) || PUBLISHED.contains(databasePassword.trim()))) {
            problems.add("DATASOURCES_DEFAULT_PASSWORD");
        }
        if (!problems.isEmpty()) {
            String message = "Refusing to start: " + String.join(", ", problems.stream().distinct().toList())
                    + " must be set (not empty, not " + PLACEHOLDER + ", not a value from the repository; the password at "
                    + "least 12, the JWT secret at least 32 characters) - see the installation guide (README.md, "
                    + "\"Configuration\"). For local development start with MICRONAUT_ENVIRONMENTS=dev.";
            LOG.error(message);
            throw new IllegalStateException(message);
        }
    }

    static ProductionSecretsCheck forTest(Set<String> environments, Map<String, String> env, String password) {
        return new ProductionSecretsCheck(environments, env::get, password);
    }
}
