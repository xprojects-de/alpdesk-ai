package de.alpdesk.services.common;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/** A retention setting (the chat's files, the scheduled tasks' runs): a number of hours or days, or empty to keep. */
public final class Retention {

    private Retention() {
    }

    /** Empty: off. Anything but a positive number stops the start - a typo must neither delete nor keep silently. */
    public static Optional<Duration> parse(String name, String setting, ChronoUnit unit) {
        if (setting == null || setting.isBlank()) {
            return Optional.empty();
        }
        try {
            long amount = Long.parseLong(setting.strip());
            if (amount > 0) {
                return Optional.of(Duration.of(amount, unit));
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IllegalArgumentException(name + " must be a number of " + unit.toString().toLowerCase()
                + " above 0, or empty to keep them - not \"" + setting + "\"");
    }
}
