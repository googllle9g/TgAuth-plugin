package net.millyland.auth.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.millyland.auth.TgAuthPlugin;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Storage for linked accounts and admin-panel PINs, on SQLite, MySQL or MariaDB. Every backend is
 * accessed through a HikariCP connection pool; each method borrows a connection for one statement
 * and returns it immediately.
 *
 * <ul>
 *   <li><b>SQLite</b>: WAL mode with a busy timeout (SQLite allows one writer at a time).</li>
 *   <li><b>MySQL</b>: the Connector/J driver that Paper ships with.</li>
 *   <li><b>MariaDB</b>: the MariaDB driver bundled inside the TgAuth jar.</li>
 * </ul>
 */
public class Database {

    public enum Type {
        SQLITE("SQLite"),
        MYSQL("MySQL"),
        MARIADB("MariaDB");

        private final String displayName;

        Type(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public static Type parse(String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "", "sqlite" -> SQLITE;
                case "mysql" -> MYSQL;
                case "mariadb" -> MARIADB;
                default -> throw new IllegalArgumentException(
                        "Unknown storage.type \"" + raw + "\" - use sqlite, mysql or mariadb.");
            };
        }
    }

    /** Outcome of {@link #importFromSqlite(File)}. */
    public record ImportResult(int accounts, int accountsSkipped, int pins, int pinsSkipped) {
    }

    private static final int SQLITE_POOL_MAX_SIZE = 4;
    private static final int SQLITE_POOL_MIN_IDLE = 1;
    private static final long SQLITE_CONNECTION_TIMEOUT_MS = 5_000L;
    private static final int SQLITE_BUSY_TIMEOUT_MS = 5_000;

    private static final Pattern TABLE_PREFIX = Pattern.compile("[A-Za-z0-9_]{0,32}");
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z0-9_$.\\-]{1,64}");

    private static final String COLUMNS = "uuid, telegram_id, username, linked_at, telegram_username, premium";

    private final TgAuthPlugin plugin;
    private volatile HikariDataSource dataSource;
    private volatile Type type = Type.SQLITE;
    private volatile String linkedTable = "linked_accounts";
    private volatile String pinsTable = "admin_pins";

    public Database(TgAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public Type type() {
        return type;
    }

    // ------------------------------------------------------------------ connecting

    public void connect() throws SQLException {
        Type configured = Type.parse(plugin.cfg().storageType());
        this.type = configured;

        if (configured == Type.SQLITE) {
            this.linkedTable = "linked_accounts";
            this.pinsTable = "admin_pins";
            this.dataSource = createSqlitePool();
        } else {
            String prefix = plugin.cfg().mysqlTablePrefix();
            if (!TABLE_PREFIX.matcher(prefix).matches()) {
                throw new IllegalArgumentException(
                        "storage.mysql.table-prefix may only contain letters, digits and underscores (max 32).");
            }
            this.linkedTable = prefix + "linked_accounts";
            this.pinsTable = prefix + "admin_pins";
            this.dataSource = createRemotePool(configured);
        }

        try (Connection c = dataSource.getConnection()) {
            createTables(c);
            if (configured == Type.SQLITE) {
                migrateSqliteSchema(c);
            } else {
                migrateRemoteSchema(c);
            }
            createTelegramIdIndex(c);
        }
        plugin.getLogger().info("Storage: " + describe());
    }

    private String describe() {
        if (type == Type.SQLITE) {
            return "SQLite file " + plugin.cfg().storageFile() + " (HikariCP pool, max " + SQLITE_POOL_MAX_SIZE + ")";
        }
        return type.displayName() + " at " + plugin.cfg().mysqlHost() + ":" + plugin.cfg().mysqlPort() + "/"
                + plugin.cfg().mysqlDatabase() + ", tables " + linkedTable + " / " + pinsTable
                + " (HikariCP pool, max " + plugin.cfg().mysqlPoolMaxSize() + ")";
    }

    private HikariDataSource createSqlitePool() {
        File dbFile = new File(plugin.getDataFolder(), plugin.cfg().storageFile());
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqliteConfig.setBusyTimeout(SQLITE_BUSY_TIMEOUT_MS);

        SQLiteDataSource sqliteDataSource = new SQLiteDataSource(sqliteConfig);
        sqliteDataSource.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("TgAuth-SQLite");
        hikari.setDataSource(sqliteDataSource);
        hikari.setMaximumPoolSize(SQLITE_POOL_MAX_SIZE);
        hikari.setMinimumIdle(SQLITE_POOL_MIN_IDLE);
        hikari.setConnectionTimeout(SQLITE_CONNECTION_TIMEOUT_MS);

        // Throws PoolInitializationException (a RuntimeException) if the first connection can't be opened.
        return new HikariDataSource(hikari);
    }

    /** MySQL / MariaDB: a standard HikariCP setup (jdbcUrl + driverClassName + credentials + properties). */
    private HikariDataSource createRemotePool(Type remoteType) throws SQLException {
        var cfg = plugin.cfg();

        String host = cfg.mysqlHost().trim();
        String database = cfg.mysqlDatabase().trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("storage.mysql.host is empty.");
        }
        if (!DATABASE_NAME.matcher(database).matches()) {
            throw new IllegalArgumentException(
                    "storage.mysql.database is empty or contains characters other than letters, digits and _ $ . -");
        }
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]"; // IPv6 literal
        }

        boolean mariadb = remoteType == Type.MARIADB;
        String driverClass = mariadb ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver";
        ClassLoader pluginLoader = Database.class.getClassLoader();

        // Loading the driver class registers it with DriverManager, which is where Hikari looks first.
        try {
            Class.forName(driverClass, true, pluginLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new SQLException(mariadb
                    ? "The MariaDB JDBC driver (" + driverClass + ") is missing from the TgAuth jar."
                    : "The MySQL JDBC driver (" + driverClass + ") is not available on this server. Paper normally "
                            + "provides it; if yours doesn't, set storage.type to \"mariadb\" (that driver is built "
                            + "into TgAuth and also works with MySQL servers).", e);
        }

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("TgAuth-" + remoteType.displayName());
        hikari.setDriverClassName(driverClass);
        hikari.setJdbcUrl("jdbc:" + (mariadb ? "mariadb" : "mysql") + "://" + host + ":" + cfg.mysqlPort() + "/" + database);
        if (!cfg.mysqlUsername().isEmpty()) {
            hikari.setUsername(cfg.mysqlUsername());
        }
        hikari.setPassword(cfg.mysqlPassword());

        hikari.setMaximumPoolSize(cfg.mysqlPoolMaxSize());
        hikari.setMinimumIdle(cfg.mysqlPoolMinIdle());
        hikari.setConnectionTimeout(cfg.mysqlConnectionTimeoutMs());
        hikari.setMaxLifetime(cfg.mysqlMaxLifetimeMs());

        // Pool threads open connections too; give them the plugin's class loader as context loader so
        // the driver can find its own service files (e.g. MariaDB authentication plugins).
        hikari.setThreadFactory(new PluginThreadFactory("TgAuth-" + remoteType.displayName() + "-Hikari", pluginLoader));

        // Defaults first, then the user's storage.mysql.properties override them.
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("connectTimeout", "5000");   // ms, same name in both drivers
        properties.put("socketTimeout", "20000");   // ms; bounds a stuck query instead of hanging forever
        if (mariadb) {
            properties.put("sslMode", cfg.mysqlUseSsl() ? "trust" : "disable");
        } else {
            properties.put("sslMode", cfg.mysqlUseSsl() ? "REQUIRED" : "DISABLED");
            properties.put("cachePrepStmts", "true");
            properties.put("prepStmtCacheSize", "250");
            properties.put("prepStmtCacheSqlLimit", "2048");
        }
        properties.putAll(cfg.mysqlProperties());
        properties.forEach(hikari::addDataSourceProperty);

        // The first connection is opened on this thread inside the HikariDataSource constructor.
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(pluginLoader);
        try {
            return new HikariDataSource(hikari); // PoolInitializationException (runtime) if the DB is unreachable
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static final class PluginThreadFactory implements ThreadFactory {
        private final String prefix;
        private final ClassLoader contextLoader;
        private final AtomicInteger counter = new AtomicInteger();

        PluginThreadFactory(String prefix, ClassLoader contextLoader) {
            this.prefix = prefix;
            this.contextLoader = contextLoader;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setContextClassLoader(contextLoader);
            return thread;
        }
    }

    // ------------------------------------------------------------------ schema

    private String sql(String template) {
        return template.replace("{linked}", linkedTable).replace("{pins}", pinsTable);
    }

    private void createTables(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            if (type == Type.SQLITE) {
                st.execute(sql("""
                    CREATE TABLE IF NOT EXISTS {linked} (
                        uuid TEXT PRIMARY KEY,
                        telegram_id INTEGER NOT NULL,
                        username TEXT,
                        linked_at INTEGER NOT NULL
                    )
                """));
                st.execute(sql("""
                    CREATE TABLE IF NOT EXISTS {pins} (
                        telegram_id INTEGER PRIMARY KEY,
                        salt TEXT NOT NULL,
                        pin_hash TEXT NOT NULL,
                        iterations INTEGER NOT NULL,
                        set_at INTEGER NOT NULL
                    )
                """));
            } else {
                // MySQL and MariaDB share this syntax. New databases get the complete, current schema.
                st.execute(sql("""
                    CREATE TABLE IF NOT EXISTS {linked} (
                        uuid VARCHAR(36) NOT NULL,
                        telegram_id BIGINT NOT NULL,
                        username VARCHAR(64) NULL,
                        linked_at BIGINT NOT NULL,
                        telegram_username VARCHAR(64) NULL,
                        premium TINYINT NOT NULL DEFAULT 0,
                        last_confirmed_ip VARCHAR(64) NULL,
                        last_confirmed_at BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (uuid),
                        KEY idx_telegram_id (telegram_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """));
                st.execute(sql("""
                    CREATE TABLE IF NOT EXISTS {pins} (
                        telegram_id BIGINT NOT NULL,
                        salt VARCHAR(64) NOT NULL,
                        pin_hash VARCHAR(128) NOT NULL,
                        iterations INT NOT NULL,
                        set_at BIGINT NOT NULL,
                        PRIMARY KEY (telegram_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """));
            }
        }
    }

    /** Upgrades SQLite files created by older TgAuth versions (the remote schema is always created complete). */
    private void migrateSqliteSchema(Connection c) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + linkedTable + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name").toLowerCase(Locale.ROOT));
            }
        }

        if (!existing.contains("telegram_username")) {
            try (Statement st = c.createStatement()) {
                st.execute(sql("ALTER TABLE {linked} ADD COLUMN telegram_username TEXT"));
            }
            plugin.getLogger().info("Database schema updated: added telegram_username column.");
        }

        if (!existing.contains("premium")) {
            try (Statement st = c.createStatement()) {
                st.execute(sql("ALTER TABLE {linked} ADD COLUMN premium INTEGER NOT NULL DEFAULT 0"));
            }
            plugin.getLogger().info("Database schema updated: added premium column.");
        }

        if (!existing.contains("last_confirmed_ip")) {
            try (Statement st = c.createStatement()) {
                st.execute(sql("ALTER TABLE {linked} ADD COLUMN last_confirmed_ip TEXT"));
                st.execute(sql("ALTER TABLE {linked} ADD COLUMN last_confirmed_at INTEGER NOT NULL DEFAULT 0"));
            }
            plugin.getLogger().info("Database schema updated: added last_confirmed_ip/last_confirmed_at columns.");
        }

        dropSqliteTelegramUnique(c);
    }

    public void close() {
        HikariDataSource ds = this.dataSource;
        if (ds != null && !ds.isClosed()) {
            ds.close();
        }

        // Drivers loaded from the TgAuth jar (the bundled MariaDB one) register themselves with the
        // JVM-wide DriverManager; unregister them so a plugin reload doesn't leak the class loader.
        ClassLoader mine = Database.class.getClassLoader();
        List<Driver> toDeregister = new ArrayList<>();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == mine) {
                toDeregister.add(driver);
            }
        }
        for (Driver driver : toDeregister) {
            try {
                DriverManager.deregisterDriver(driver);
            } catch (SQLException ignored) {
                // nothing useful to do while shutting down
            }
        }
    }

    private Connection conn() throws SQLException {
        HikariDataSource ds = this.dataSource;
        if (ds == null) {
            throw new SQLException("Database is not connected");
        }
        return ds.getConnection();
    }

    private void createTelegramIdIndex(Connection c) throws SQLException {
        if (type != Type.SQLITE) return; // MySQL/MariaDB get it from CREATE TABLE / migrateRemoteSchema
        try (Statement st = c.createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS idx_" + linkedTable + "_telegram_id ON " + linkedTable + "(telegram_id)");
        }
    }

    /**
     * Older versions allowed only one game account per Telegram ID (UNIQUE telegram_id). That limit is now
     * a setting (auth.max-accounts-per-telegram), so the unique index is replaced by a plain one.
     */
    private void migrateRemoteSchema(Connection c) throws SQLException {
        String uniqueKey = null;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW INDEX FROM " + linkedTable + " WHERE Column_name = 'telegram_id'")) {
            while (rs.next()) {
                if (rs.getInt("Non_unique") == 0 && !"PRIMARY".equalsIgnoreCase(rs.getString("Key_name"))) {
                    uniqueKey = rs.getString("Key_name");
                }
            }
        }
        if (uniqueKey != null) {
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE " + linkedTable + " DROP INDEX `" + uniqueKey.replace("`", "")
                        + "`, ADD INDEX idx_telegram_id (telegram_id)");
            }
            plugin.getLogger().info("Database schema updated: telegram_id is no longer unique (multi-account linking).");
        }
    }

    /** SQLite can't drop a UNIQUE constraint in place, so the table is rebuilt when it still has one. */
    private void dropSqliteTelegramUnique(Connection c) throws SQLException {
        boolean hasUnique = false;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA index_list(" + linkedTable + ")")) {
            List<String> uniqueIndexes = new ArrayList<>();
            while (rs.next()) {
                if (rs.getInt("unique") == 1) uniqueIndexes.add(rs.getString("name"));
            }
            for (String idx : uniqueIndexes) {
                try (Statement st2 = c.createStatement();
                     ResultSet cols = st2.executeQuery("PRAGMA index_info(" + idx + ")")) {
                    int n = 0;
                    boolean tg = false;
                    while (cols.next()) {
                        n++;
                        if ("telegram_id".equalsIgnoreCase(cols.getString("name"))) tg = true;
                    }
                    if (n == 1 && tg) hasUnique = true;
                }
            }
        }
        if (!hasUnique) return;

        String tmp = linkedTable + "_new";
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + tmp);
            st.execute("CREATE TABLE " + tmp + " (uuid TEXT PRIMARY KEY, telegram_id INTEGER NOT NULL, "
                    + "username TEXT, linked_at INTEGER NOT NULL, telegram_username TEXT, "
                    + "premium INTEGER NOT NULL DEFAULT 0, last_confirmed_ip TEXT, "
                    + "last_confirmed_at INTEGER NOT NULL DEFAULT 0)");
            st.execute("INSERT INTO " + tmp + " (uuid, telegram_id, username, linked_at, telegram_username, premium, "
                    + "last_confirmed_ip, last_confirmed_at) SELECT uuid, telegram_id, username, linked_at, "
                    + "telegram_username, premium, last_confirmed_ip, last_confirmed_at FROM " + linkedTable);
            st.execute("DROP TABLE " + linkedTable);
            st.execute("ALTER TABLE " + tmp + " RENAME TO " + linkedTable);
            c.commit();
            plugin.getLogger().info("Database schema updated: telegram_id is no longer unique (multi-account linking).");
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    // ------------------------------------------------------------------ linked accounts

    public Optional<LinkedAccount> findByUuid(UUID uuid) {
        String sql = sql("SELECT " + COLUMNS + " FROM {linked} WHERE uuid = ?");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (findByUuid): " + e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<LinkedAccount> findByTelegramId(long telegramId) {
        List<LinkedAccount> all = findAllByTelegramId(telegramId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** Every game account linked to this Telegram ID, oldest link first. */
    public List<LinkedAccount> findAllByTelegramId(long telegramId) {
        String sql = sql("SELECT " + COLUMNS + " FROM {linked} WHERE telegram_id = ? ORDER BY linked_at ASC, uuid ASC");
        List<LinkedAccount> out = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (findAllByTelegramId): " + e.getMessage());
        }
        return out;
    }

    /** @return how many game accounts are linked to this Telegram ID, or Integer.MAX_VALUE if the DB failed */
    public int countByTelegramId(long telegramId) {
        String sql = sql("SELECT COUNT(*) FROM {linked} WHERE telegram_id = ?");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (countByTelegramId): " + e.getMessage());
        }
        return Integer.MAX_VALUE; // fail closed: don't allow another link when we can't tell
    }

    public Optional<LinkedAccount> findByUsername(String username) {
        String sql = sql("SELECT " + COLUMNS + " FROM {linked} WHERE LOWER(username) = LOWER(?)");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (findByUsername): " + e.getMessage());
        }
        return Optional.empty();
    }

    public boolean link(UUID uuid, long telegramId, String username, String telegramUsername) {
        String sql = sql("INSERT INTO {linked} (uuid, telegram_id, username, linked_at, telegram_username, premium) "
                + "VALUES (?, ?, ?, ?, ?, 0)");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, telegramId);
            ps.setString(3, username);
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, telegramUsername);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (link): " + e.getMessage());
            return false;
        }
    }

    public boolean unlink(UUID uuid) {
        String sql = sql("DELETE FROM {linked} WHERE uuid = ?");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (unlink): " + e.getMessage());
            return false;
        }
    }

    public boolean setPremium(UUID uuid, boolean premium) {
        String sql = sql("UPDATE {linked} SET premium = ? WHERE uuid = ?");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, premium ? 1 : 0);
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (setPremium): " + e.getMessage());
            return false;
        }
    }

    public boolean migrateUuid(UUID oldUuid, UUID newUuid, String newUsername) {
        String sql = sql("UPDATE {linked} SET uuid = ?, username = ? WHERE uuid = ?");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newUuid.toString());
            ps.setString(2, newUsername);
            ps.setString(3, oldUuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (migrateUuid): " + e.getMessage());
            return false;
        }
    }

    private LinkedAccount map(ResultSet rs) throws SQLException {
        return new LinkedAccount(
                UUID.fromString(rs.getString("uuid")),
                rs.getLong("telegram_id"),
                rs.getString("username"),
                rs.getLong("linked_at"),
                rs.getString("telegram_username"),
                rs.getInt("premium") != 0
        );
    }

    public List<LinkedAccount> findPage(int offset, int limit) {
        List<LinkedAccount> results = new ArrayList<>();
        String sql = sql("SELECT " + COLUMNS + " FROM {linked} ORDER BY linked_at DESC LIMIT ? OFFSET ?");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(map(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (findPage): " + e.getMessage());
        }
        return results;
    }

    public int countAll() {
        String sql = sql("SELECT COUNT(*) FROM {linked}");
        try (Connection c = conn(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (countAll): " + e.getMessage());
        }
        return 0;
    }

    public boolean matchesRecentIp(UUID uuid, String ip, int cooldownSeconds) {
        String sql = sql("SELECT last_confirmed_ip, last_confirmed_at FROM {linked} WHERE uuid = ?");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String storedIp = rs.getString("last_confirmed_ip");
                long storedAt = rs.getLong("last_confirmed_at");
                if (storedIp == null || !storedIp.equals(ip)) return false;
                return System.currentTimeMillis() - storedAt <= cooldownSeconds * 1000L;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (matchesRecentIp): " + e.getMessage());
            return false;
        }
    }

    public void recordConfirmedIp(UUID uuid, String ip) {
        String sql = sql("UPDATE {linked} SET last_confirmed_ip = ?, last_confirmed_at = ? WHERE uuid = ?");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (recordConfirmedIp): " + e.getMessage());
        }
    }

    public void revokeTrustedIpIfUsedByOtherAccount(UUID excludeUuid, String ip) {
        String sql = sql("UPDATE {linked} SET last_confirmed_ip = NULL, last_confirmed_at = 0 "
                + "WHERE last_confirmed_ip = ? AND uuid != ?");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setString(2, excludeUuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (revokeTrustedIpIfUsedByOtherAccount): " + e.getMessage());
        }
    }

    public void revokeTrustedIpIfMismatched(UUID uuid, String currentIp) {
        String sql = sql("UPDATE {linked} SET last_confirmed_ip = NULL, last_confirmed_at = 0 "
                + "WHERE uuid = ? AND last_confirmed_ip IS NOT NULL AND last_confirmed_ip != ?");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, currentIp);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (revokeTrustedIpIfMismatched): " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ admin PINs

    public Optional<AdminPinRecord> findAdminPin(long telegramId) {
        String sql = sql("SELECT telegram_id, salt, pin_hash, iterations, set_at FROM {pins} WHERE telegram_id = ?");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new AdminPinRecord(
                            rs.getLong("telegram_id"),
                            rs.getString("salt"),
                            rs.getString("pin_hash"),
                            rs.getInt("iterations"),
                            rs.getLong("set_at")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (findAdminPin): " + e.getMessage());
        }
        return Optional.empty();
    }

    public boolean saveAdminPin(long telegramId, String salt, String hash, int iterations) {
        String insert = "INSERT INTO {pins} (telegram_id, salt, pin_hash, iterations, set_at) VALUES (?, ?, ?, ?, ?) ";
        String sql = sql(type == Type.SQLITE
                ? insert + "ON CONFLICT(telegram_id) DO UPDATE SET salt = excluded.salt, pin_hash = excluded.pin_hash, "
                        + "iterations = excluded.iterations, set_at = excluded.set_at"
                : insert + "ON DUPLICATE KEY UPDATE salt = VALUES(salt), pin_hash = VALUES(pin_hash), "
                        + "iterations = VALUES(iterations), set_at = VALUES(set_at)");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            ps.setString(2, salt);
            ps.setString(3, hash);
            ps.setInt(4, iterations);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (saveAdminPin): " + e.getMessage());
            return false;
        }
    }

    public boolean deleteAdminPin(long telegramId) {
        String sql = sql("DELETE FROM {pins} WHERE telegram_id = ?");
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error (deleteAdminPin): " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ SQLite -> MySQL/MariaDB import

    /**
     * Copies linked accounts and admin PINs from an SQLite file into the active MySQL/MariaDB
     * database. Rows that already exist (same uuid or Telegram ID) are left untouched, so running it
     * twice is harmless. Blocking: call from an async thread.
     */
    public ImportResult importFromSqlite(File sqliteFile) throws SQLException {
        if (type == Type.SQLITE) {
            throw new IllegalStateException("The active storage is already SQLite.");
        }

        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        SQLiteDataSource source = new SQLiteDataSource(readOnly);
        source.setUrl("jdbc:sqlite:" + sqliteFile.getAbsolutePath());

        int accounts = 0;
        int accountsSkipped = 0;
        int pins = 0;
        int pinsSkipped = 0;

        try (Connection from = source.getConnection(); Connection to = conn()) {
            to.setAutoCommit(false);
            try {
                if (tableExists(from, "linked_accounts")) {
                    String insert = sql("INSERT IGNORE INTO {linked} (uuid, telegram_id, username, linked_at, "
                            + "telegram_username, premium, last_confirmed_ip, last_confirmed_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
                    try (Statement st = from.createStatement();
                         ResultSet rs = st.executeQuery("SELECT * FROM linked_accounts");
                         PreparedStatement ps = to.prepareStatement(insert)) {
                        Set<String> columns = columnNames(rs.getMetaData());
                        while (rs.next()) {
                            ps.setString(1, rs.getString("uuid"));
                            ps.setLong(2, rs.getLong("telegram_id"));
                            ps.setString(3, rs.getString("username"));
                            ps.setLong(4, rs.getLong("linked_at"));
                            ps.setString(5, columns.contains("telegram_username") ? rs.getString("telegram_username") : null);
                            ps.setInt(6, columns.contains("premium") ? rs.getInt("premium") : 0);
                            ps.setString(7, columns.contains("last_confirmed_ip") ? rs.getString("last_confirmed_ip") : null);
                            ps.setLong(8, columns.contains("last_confirmed_at") ? rs.getLong("last_confirmed_at") : 0L);
                            if (ps.executeUpdate() > 0) accounts++; else accountsSkipped++;
                        }
                    }
                }

                if (tableExists(from, "admin_pins")) {
                    String insert = sql("INSERT IGNORE INTO {pins} (telegram_id, salt, pin_hash, iterations, set_at) "
                            + "VALUES (?, ?, ?, ?, ?)");
                    try (Statement st = from.createStatement();
                         ResultSet rs = st.executeQuery("SELECT telegram_id, salt, pin_hash, iterations, set_at FROM admin_pins");
                         PreparedStatement ps = to.prepareStatement(insert)) {
                        while (rs.next()) {
                            ps.setLong(1, rs.getLong("telegram_id"));
                            ps.setString(2, rs.getString("salt"));
                            ps.setString(3, rs.getString("pin_hash"));
                            ps.setInt(4, rs.getInt("iterations"));
                            ps.setLong(5, rs.getLong("set_at"));
                            if (ps.executeUpdate() > 0) pins++; else pinsSkipped++;
                        }
                    }
                }

                to.commit();
            } catch (SQLException | RuntimeException e) {
                to.rollback();
                throw e;
            } finally {
                to.setAutoCommit(true);
            }
        }
        return new ImportResult(accounts, accountsSkipped, pins, pinsSkipped);
    }

    private boolean tableExists(Connection c, String table) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    private Set<String> columnNames(ResultSetMetaData meta) throws SQLException {
        Set<String> names = new HashSet<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            names.add(meta.getColumnName(i).toLowerCase(Locale.ROOT));
        }
        return names;
    }
}
