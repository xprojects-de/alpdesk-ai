package de.alpdesk.repositories.monitoring;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.jdbc.runtime.JdbcOperations;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only queries for the daily report ({@code services.monitoring.DailyReportJob}) across other features' tables:
 * the management database through Micronaut Data's connection, a tenant database by plain JDBC on its DataSource
 * (docs/architecture/tenancy.md). Only active tenants count. The queries run once a day, so a scan over a table that
 * keeps growing (usage_record, message) is acceptable - no index for them.
 */
@Singleton
public class DailyReportRepository {

    /** An active tenant with its number of active users. */
    public record TenantRow(Long id, String name, String databaseName, int users) {
    }

    /**
     * A tenant's model calls in the period, and what they cost in the period and in the month so far - each with the
     * part of the admin's global keys (billed).
     */
    public record Usage(int calls, BigDecimal billed, BigDecimal amount, BigDecimal monthBilled,
                        BigDecimal monthAmount) {

        public static final Usage NONE = new Usage(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * What a tenant database holds and did in the period.
     *
     * @param waiting      documents PENDING or PROCESSING
     * @param waitingSince when the longest waiting of them was uploaded or replaced; null when none waits
     * @param failed       documents whose embedding failed, {@code failedInPeriod} of them in the period
     * @param questions    the questions asked in stored conversations (an API key without history stores none)
     * @param askers       the users who asked them
     */
    public record TenantActivity(int documents, int ready, int waiting, @Nullable Instant waitingSince, int failed,
                                 int failedInPeriod, int added, int questions, int askers) {
    }

    /** The access requests of people the WAM proxy sent and nobody assigned yet; {@code oldest} null when none. */
    public record AccessRequests(int count, @Nullable Instant oldest) {
    }

    private final JdbcOperations jdbc;

    public DailyReportRepository(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    /** PostgreSQL's version, without its build details. */
    @Transactional
    public String databaseVersion() {
        String version = query("SELECT current_setting('server_version')", rs -> rs.getString(1)).getFirst();
        return version.split(" ")[0];
    }

    /** The management database's name. */
    @Transactional
    public String managementDatabase() {
        return query("SELECT current_database()", rs -> rs.getString(1)).getFirst();
    }

    /**
     * The size in bytes of the app's own databases - the management database and every tenant's - by name. Only these:
     * the server may hold another application's database, which the app's role may not be
     * allowed to measure and which is no part of the app's size.
     */
    @Transactional
    public Map<String, Long> databaseSizes() {
        Map<String, Long> sizes = new HashMap<>();
        for (Map.Entry<String, Long> size : query("""
                SELECT datname, pg_database_size(datname) FROM pg_database
                WHERE datname = current_database() OR datname IN (SELECT database_name FROM tenant)""",
                rs -> Map.entry(rs.getString(1), rs.getLong(2)))) {
            sizes.put(size.getKey(), size.getValue());
        }
        return sizes;
    }

    @Transactional
    public List<TenantRow> activeTenants() {
        return query("""
                SELECT t.id, t.name, t.database_name,
                       (SELECT COUNT(*) FROM tenant_user u WHERE u.tenant_id = t.id AND u.active)
                FROM tenant t
                WHERE t.active
                ORDER BY LOWER(t.name), t.id""",
                rs -> new TenantRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
    }

    /** Every tenant's usage since {@code since} and since {@code monthStart}, by tenant id; a tenant without calls is missing. */
    @Transactional
    public Map<Long, Usage> usage(Instant since, Instant monthStart) {
        Map<Long, Usage> usage = new HashMap<>();
        Instant earliest = since.isBefore(monthStart) ? since : monthStart;
        List<Map.Entry<Long, Usage>> rows = query("""
                SELECT tenant_id,
                       COUNT(*) FILTER (WHERE created_at >= ?),
                       COALESCE(SUM(amount) FILTER (WHERE created_at >= ? AND key_source = 'GLOBAL'), 0),
                       COALESCE(SUM(amount) FILTER (WHERE created_at >= ?), 0),
                       COALESCE(SUM(amount) FILTER (WHERE created_at >= ? AND key_source = 'GLOBAL'), 0),
                       COALESCE(SUM(amount) FILTER (WHERE created_at >= ?), 0)
                FROM usage_record
                WHERE created_at >= ?
                GROUP BY tenant_id""",
                rs -> Map.entry(rs.getLong(1), new Usage(rs.getInt(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6))),
                since, since, since, monthStart, monthStart, earliest);
        for (Map.Entry<Long, Usage> row : rows) {
            usage.put(row.getKey(), row.getValue());
        }
        return usage;
    }

    /** A connection of an active tenant whose OAuth sign-in has to be repeated (docs/architecture/mcp.md). */
    public record SignInNeeded(String tenant, String connection) {
    }

    @Transactional
    public List<SignInNeeded> signInsNeeded() {
        return query("""
                SELECT t.name, c.name FROM oauth_grant g
                JOIN mcp_connection c ON c.id = g.connection_id
                JOIN tenant t ON t.id = g.tenant_id
                WHERE g.status = 'SIGN_IN_NEEDED' AND t.active
                ORDER BY LOWER(t.name), LOWER(c.name)""",
                rs -> new SignInNeeded(rs.getString(1), rs.getString(2)));
    }

    @Transactional
    public AccessRequests accessRequests() {
        return query("SELECT COUNT(*), MIN(requested_at) FROM access_request",
                rs -> new AccessRequests(rs.getInt(1), instant(rs, 2))).getFirst();
    }

    /** Read from the tenant's own database - not in the management database's transaction; the caller closes it. */
    public TenantActivity tenantActivity(Connection tenantDatabase, Instant since) {
        String sql = """
                SELECT (SELECT COUNT(*) FROM document),
                       (SELECT COUNT(*) FROM document WHERE status = 'READY'),
                       (SELECT COUNT(*) FROM document WHERE status IN ('PENDING', 'PROCESSING')),
                       (SELECT MIN(updated_at) FROM document WHERE status IN ('PENDING', 'PROCESSING')),
                       (SELECT COUNT(*) FROM document WHERE status = 'FAILED'),
                       (SELECT COUNT(*) FROM document WHERE status = 'FAILED' AND updated_at >= ?),
                       (SELECT COUNT(*) FROM document WHERE created_at >= ?),
                       (SELECT COUNT(*) FROM message WHERE role = 'USER' AND created_at >= ?),
                       (SELECT COUNT(DISTINCT c.user_id) FROM conversation c JOIN message m ON m.conversation_id = c.id
                        WHERE m.role = 'USER' AND m.created_at >= ?)""";
        try (PreparedStatement statement = tenantDatabase.prepareStatement(sql)) {
            setParameters(statement, since, since, since, since);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return new TenantActivity(rs.getInt(1), rs.getInt(2), rs.getInt(3), instant(rs, 4), rs.getInt(5),
                        rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the tenant database", e);
        }
    }

    /** One row of a result set. */
    @FunctionalInterface
    private interface Row<T> {
        T read(ResultSet rs) throws SQLException;
    }

    private <T> List<T> query(String sql, Row<T> row, Object... parameters) {
        return jdbc.prepareStatement(sql, statement -> {
            setParameters(statement, parameters);
            List<T> rows = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(row.read(rs));
                }
            }
            return rows;
        });
    }

    private static void setParameters(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            if (!(parameters[i] instanceof Instant instant)) {
                throw new IllegalArgumentException("Unsupported parameter " + parameters[i].getClass());
            }
            statement.setTimestamp(i + 1, Timestamp.from(instant));
        }
    }

    @Nullable
    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
