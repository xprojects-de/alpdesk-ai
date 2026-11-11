package de.alpdesk.controller.ai;

import de.alpdesk.services.common.ServiceException;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** Reads the month parameter of the usage endpoints ("2026-10"); left out = the current month. */
final class Months {

    private Months() {
    }

    static YearMonth parse(String month, ZoneId zone) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(zone);
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw ServiceException.badRequest("usage.monthInvalid", "The month must look like 2026-10");
        }
    }
}
