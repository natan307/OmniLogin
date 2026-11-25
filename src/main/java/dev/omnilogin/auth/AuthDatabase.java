package dev.omnilogin.auth;

import dev.omnilogin.Settings;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Accounts of the password login, in SQLite (a local file) or MySQL/MariaDB. Plain JDBC: Paper and Spigot already
 * ship both drivers. Every call blocks, so call it off the game threads.
 */
public final class AuthDatabase {

    private static final String TABLE = "omnilogin_auth";
    private static final String CREATE = "CREATE TABLE IF NOT EXISTS `" + TABLE + "` ("
            + "`name` VARCHAR(32) NOT NULL PRIMARY KEY, "
            + "`password` VARCHAR(255) NOT NULL, "
            + "`reg_ip` VARCHAR(64), "
            + "`last_ip` VARCHAR(64), "
            + "`reg_time` BIGINT NOT NULL, "
            + "`last_login` BIGINT NOT NULL)";

    /** A stored account. */
    public record Account(String hash, String lastIp) {
    }

    private final Settings settings;
    private final File dataFolder;
    private final Object lock = new Object();

    public AuthDatabase(Settings settings, File dataFolder) {
        this.settings = settings;
        this.dataFolder = dataFolder;
    }

    public boolean isMysql() {
        return "mysql".equalsIgnoreCase(settings.storage) || "mariadb".equalsIgnoreCase(settings.storage);
    }

    public void createTable() throws SQLException {
        synchronized (lock) {
            try (Connection con = open(); Statement stmt = con.createStatement()) {
                stmt.executeUpdate(CREATE);
            }
        }
    }

    /** Returns the account, or null when the name is not registered. */
    public Account find(String name) throws SQLException {
        String sql = "SELECT `password`, `last_ip` FROM `" + TABLE + "` WHERE `name`=?";
        synchronized (lock) {
            try (Connection con = open(); PreparedStatement stmt = con.prepareStatement(sql)) {
                stmt.setString(1, key(name));
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? new Account(rs.getString("password"), rs.getString("last_ip")) : null;
                }
            }
        }
    }

    public void register(String name, String hash, String ip) throws SQLException {
        String sql = "INSERT INTO `" + TABLE + "` (`name`, `password`, `reg_ip`, `last_ip`, `reg_time`, `last_login`) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        long now = System.currentTimeMillis();
        synchronized (lock) {
            try (Connection con = open(); PreparedStatement stmt = con.prepareStatement(sql)) {
                stmt.setString(1, key(name));
                stmt.setString(2, hash);
                stmt.setString(3, ip);
                stmt.setString(4, ip);
                stmt.setLong(5, now);
                stmt.setLong(6, now);
                stmt.executeUpdate();
            }
        }
    }

    /** Returns how many accounts changed (0 when the name is not registered). */
    public int updatePassword(String name, String hash) throws SQLException {
        String sql = "UPDATE `" + TABLE + "` SET `password`=? WHERE `name`=?";
        synchronized (lock) {
            try (Connection con = open(); PreparedStatement stmt = con.prepareStatement(sql)) {
                stmt.setString(1, hash);
                stmt.setString(2, key(name));
                return stmt.executeUpdate();
            }
        }
    }

    public void updateLogin(String name, String ip) throws SQLException {
        String sql = "UPDATE `" + TABLE + "` SET `last_ip`=?, `last_login`=? WHERE `name`=?";
        synchronized (lock) {
            try (Connection con = open(); PreparedStatement stmt = con.prepareStatement(sql)) {
                stmt.setString(1, ip);
                stmt.setLong(2, System.currentTimeMillis());
                stmt.setString(3, key(name));
                stmt.executeUpdate();
            }
        }
    }

    public int delete(String name) throws SQLException {
        String sql = "DELETE FROM `" + TABLE + "` WHERE `name`=?";
        synchronized (lock) {
            try (Connection con = open(); PreparedStatement stmt = con.prepareStatement(sql)) {
                stmt.setString(1, key(name));
                return stmt.executeUpdate();
            }
        }
    }

    private Connection open() throws SQLException {
        if (isMysql()) {
            loadDriver("com.mysql.cj.jdbc.Driver");
            String url = "jdbc:mysql://" + settings.mysqlHost + ":" + settings.mysqlPort + "/" + settings.mysqlDatabase
                    + "?useSSL=" + settings.mysqlSsl + "&characterEncoding=utf8&allowPublicKeyRetrieval=true";
            return DriverManager.getConnection(url, settings.mysqlUser, settings.mysqlPassword);
        }

        loadDriver("org.sqlite.JDBC");
        File file = new File(dataFolder, settings.sqliteFile);
        return DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
    }

    private static void loadDriver(String className) throws SQLException {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException missing) {
            throw new SQLException("JDBC driver " + className + " is not available on this server", missing);
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
