package de.alpdesk.security;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sign-in through a WAM proxy (docs/architecture/tenancy.md "Sign-in through a WAM proxy"): the proxy in front of the
 * app has authenticated the person and sends their user name in a header. Off unless app.wam.enabled is set - the
 * header is then ignored everywhere. On, the app trusts the header completely: the proxy must remove it from every
 * request coming from outside, and the app must be reachable through the proxy only (decided 7 October 2026 - no
 * address or secret check of our own).
 */
@Singleton
public class WamProxy {

    private final boolean enabled;
    private final String userHeader;
    private final String nameHeader;
    private final String groupsHeader;
    private final String groupsSeparator;

    public WamProxy(@Value("${app.wam.enabled}") boolean enabled, @Value("${app.wam.user-header}") String userHeader,
                    @Value("${app.wam.name-header}") String nameHeader,
                    @Value("${app.wam.groups-header}") String groupsHeader,
                    @Value("${app.wam.groups-separator}") String groupsSeparator) {
        this.enabled = enabled;
        this.userHeader = userHeader;
        this.nameHeader = nameHeader;
        this.groupsHeader = groupsHeader.strip();
        this.groupsSeparator = groupsSeparator.isEmpty() ? "," : groupsSeparator;
    }

    public boolean enabled() {
        return enabled;
    }

    public String userHeader() {
        return userHeader;
    }

    /** The user the proxy sent, as a username ({@link #asUsername}); null without the header (or with an empty one). */
    public @Nullable String user(HttpRequest<?> request) {
        String user = header(request, userHeader);
        return user == null ? null : asUsername(user);
    }

    /**
     * A Windows domain account ({@code DOMAIN\name}) becomes {@code name@domain}: unique across domains - cutting the
     * domain off could merge two people of different domains into one account (decided 7 October 2026) - and a username
     * may hold "@". Anything else stays as it is; a value that is no username either way is refused where it is used.
     */
    static String asUsername(String proxyUser) {
        int backslash = proxyUser.indexOf('\\');
        if (backslash <= 0 || backslash == proxyUser.length() - 1) {
            return proxyUser;
        }
        return proxyUser.substring(backslash + 1) + "@" + proxyUser.substring(0, backslash).toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a user group's WAM group is checked against the IDM groups the proxy sends: only with WAM on and a groups
     * header configured - otherwise every group counts as it is.
     */
    public boolean checksGroups() {
        return enabled && !groupsHeader.isEmpty();
    }

    /**
     * The IDM groups the proxy sent, trimmed, without empty ones - none without the header (fail closed). A proxy may
     * send the header once or once per group.
     */
    public List<String> groups(HttpRequest<?> request) {
        List<String> groups = new ArrayList<>();
        if (groupsHeader.isEmpty()) {
            return groups;
        }
        for (String value : request.getHeaders().getAll(groupsHeader)) {
            for (String group : value.split(Pattern.quote(groupsSeparator))) {
                if (!group.isBlank()) {
                    groups.add(group.strip());
                }
            }
        }
        return groups;
    }

    /** The person's name, if the proxy sends one in the configured header. */
    public @Nullable String displayName(HttpRequest<?> request) {
        return nameHeader.isBlank() ? null : header(request, nameHeader);
    }

    private static @Nullable String header(HttpRequest<?> request, String name) {
        String value = request.getHeaders().get(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
