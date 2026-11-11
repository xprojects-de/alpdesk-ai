package de.alpdesk.services.tenant;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every tenant's own database on the PostgreSQL server of the management database (docs/architecture/tenancy.md):
 * creates, migrates (Flyway, {@code db/tenant/}) and drops it, and hands out its connection pool. The management
 * database's role owns the tenant databases, so its credentials reach them.
 * <p>
 * CREATE and DROP DATABASE cannot run inside a transaction: callers must not hold one.
 */
@Singleton
public class TenantDatabases {

    /** The server, the management database's name, and the parameters every connection to the server needs. */
    private static final Pattern POSTGRES_URL = Pattern.compile("^(jdbc:postgresql://[^/]+/)[^?]*(\\?.*)?$");
    private static final Pattern DATABASE_NAME = Pattern.compile("^tenant_[a-z0-9]+$");
    private static final String NAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final DataSource managementDataSource;
    private final String username;
    private final String password;
    private final String connectionOptions;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    /** jdbc:postgresql://host:port/ of the management database - read from a live connection on first use. */
    private volatile String managementUrl;

    public TenantDatabases(DataSource managementDataSource,
                           @Value("${datasources.default.username}") String username,
                           @Value("${datasources.default.password}") String password,
                           @Value("${datasources.default.data-source-properties.options}") String connectionOptions) {
        this.managementDataSource = DelegatingDataSource.unwrapDataSource(managementDataSource);
        this.username = username;
        this.password = password;
        this.connectionOptions = connectionOptions;
    }

    /** A new, unused name: {@code tenant_} and 16 random characters. */
    public String newDatabaseName() {
        StringBuilder name = new StringBuilder("tenant_");
        for (int i = 0; i < 16; i++) {
            name.append(NAME_ALPHABET.charAt(random.nextInt(NAME_ALPHABET.length())));
        }
        return name.toString();
    }

    /** Creates the database if it does not exist yet and migrates it to the current tenant schema. */
    public void ensure(String databaseName) {
        requireValidName(databaseName);
        if (!exists(databaseName)) {
            executeOnServer("CREATE DATABASE \"" + databaseName + "\"");
        }
        migrate(databaseName);
    }

    /** Not through the pool: on start every tenant is migrated, and a pool per tenant would keep a connection each. */
    protected void migrate(String databaseName) {
        Flyway.configure()
                .dataSource(migrationDataSource(databaseName))
                .locations("classpath:db/tenant")
                .load()
                .migrate();
    }

    /**
     * Flyway's connections to a tenant database. A migration that fails on a tenant's data is logged with the
     * database's message, which must not name the row's values. Without the other connections' options: Flyway keeps
     * a transaction idle on one connection while a migration runs on another, and a long one (an index over a large
     * table) would be ended after a minute.
     */
    DataSource migrationDataSource(String databaseName) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(tenantUrl(databaseName));
        dataSource.setUser(username);
        dataSource.setPassword(password);
        dataSource.setLogServerErrorDetail(false);
        return dataSource;
    }

    /**
     * Closes the database's pool and drops it - also while a connection of another process is still open. The pool is
     * closed again afterwards: work still running for the tenant (a chat answer, the indexer) may have opened it anew in
     * between, and it would stay in the map until shutdown.
     */
    public void drop(String databaseName) {
        requireValidName(databaseName);
        closePool(databaseName);
        executeOnServer("DROP DATABASE IF EXISTS \"" + databaseName + "\" WITH (FORCE)");
        closePool(databaseName);
    }

    public boolean exists(String databaseName) {
        try (Connection connection = managementDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            statement.setString(1, databaseName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not look up database " + databaseName, e);
        }
    }

    /**
     * The tenant database's pool, opened on first use. It keeps no connection idle for longer than half a minute
     * (minimumIdle 0), so hundreds of tenants do not hold hundreds of connections - only the ones in use.
     */
    public DataSource dataSource(String databaseName) {
        requireValidName(databaseName);
        return pools.computeIfAbsent(databaseName, this::openPool);
    }

    /**
     * A connection of its own to the tenant database, outside its pool - the caller closes it. For reading every tenant
     * one after the other (the daily report, the indexer on start): a pool per tenant would keep a connection each for
     * half a minute, and hundreds of them would take the server's last connections.
     */
    public Connection openConnection(String databaseName) throws SQLException {
        return openConnection(databaseName, null);
    }

    /**
     * As {@link #openConnection(String)}, waiting for a lock as long as given instead of the connections' usual 10 s
     * (datasources.default.data-source-properties.options): for building an index next to the tenant's work.
     */
    public Connection openConnection(String databaseName, @Nullable Duration lockTimeout) throws SQLException {
        requireValidName(databaseName);
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("logServerErrorDetail", "false");
        // The later -c wins.
        properties.setProperty("options", lockTimeout == null ? connectionOptions
                : connectionOptions + " -c lock_timeout=" + lockTimeout.toMillis() + "ms");
        return DriverManager.getConnection(tenantUrl(databaseName), properties);
    }

    @PreDestroy
    void closePools() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }

    private void closePool(String databaseName) {
        HikariDataSource pool = pools.remove(databaseName);
        if (pool != null) {
            pool.close();
        }
    }

    private HikariDataSource openPool(String databaseName) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(databaseName);
        config.setJdbcUrl(tenantUrl(databaseName));
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setIdleTimeout(30_000);
        // The same as the management database's pool (application.properties): no row values in error messages,
        // bounded lock waits, no transaction left open for long.
        config.addDataSourceProperty("logServerErrorDetail", "false");
        config.addDataSourceProperty("options", connectionOptions);
        return new HikariDataSource(config);
    }

    /**
     * The management database's URL with the tenant's database in place of its name. Read from a connection, not from
     * datasources.default.url: the tests' Testcontainers URL (jdbc:tc:...) names no server, and another database name
     * in it would start another container.
     */
    private String tenantUrl(String databaseName) {
        if (managementUrl == null) {
            try (Connection connection = managementDataSource.getConnection()) {
                managementUrl = connection.getMetaData().getURL();
            } catch (SQLException e) {
                throw new IllegalStateException("Could not read the database server's address", e);
            }
        }
        return tenantUrl(managementUrl, databaseName);
    }

    /**
     * The URL's parameters (sslmode, a certificate, ...) are kept: an operator who verifies the server's certificate
     * for the management database gets the same for every tenant database.
     */
    static String tenantUrl(String managementUrl, String databaseName) {
        Matcher matcher = POSTGRES_URL.matcher(managementUrl);
        if (!matcher.matches()) {
            throw new IllegalStateException("The management database is not reached through jdbc:postgresql://");
        }
        return matcher.group(1) + databaseName + (matcher.group(2) == null ? "" : matcher.group(2));
    }

    private void executeOnServer(String sql) {
        try (Connection connection = managementDataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not run: " + sql, e);
        }
    }

    /** The names go into SQL unquoted by a driver - only the generated form is accepted. */
    private static void requireValidName(String databaseName) {
        if (databaseName == null || !DATABASE_NAME.matcher(databaseName).matches()) {
            throw new IllegalArgumentException("Not a tenant database name: " + databaseName);
        }
    }
}
