package cn.handyplus.menu.ng.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 基于 HikariCP 的 MySQL 存储实现。
 *
 * <p>适合多服共享数据（例如 BungeeCord 网络下所有子服共用一份菜单限制记录）。
 *
 * <p>连接串刻意打开了几个对中文与稳定性必要的参数：
 * <ul>
 *   <li>{@code useUnicode=true&characterEncoding=utf8} —— 否则玩家名/菜单名里的中文会乱码</li>
 *   <li>{@code useSSL=false} —— 内网服多未配证书，开启会导致连接被拒</li>
 *   <li>{@code serverTimezone=Asia/Shanghai} —— MySQL 8 驱动强制要求时区，缺失会直接连接失败</li>
 * </ul>
 */
public final class MysqlStorage implements Storage {

    private HikariDataSource dataSource;

    @Override
    public void init(Plugin plugin, YamlConfiguration config) throws SQLException {
        String host = config.getString("mysql.host", "127.0.0.1");
        int port = config.getInt("mysql.port", 3306);
        String database = config.getString("mysql.database", "minecraft");
        String username = config.getString("mysql.username", "root");
        String password = config.getString("mysql.password", "");
        String parameters = config.getString("mysql.parameters",
                "useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + (parameters.isEmpty() ? "" : "?" + parameters);

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("PlayerMenuNG-MySQL");
        hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikari.setJdbcUrl(url);
        hikari.setUsername(username);
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(config.getInt("mysql.pool-size", 8));
        hikari.setMinimumIdle(config.getInt("mysql.min-idle", 2));
        hikari.setConnectionTimeout(config.getLong("mysql.connection-timeout", 10_000L));
        // MySQL 有 wait_timeout，长时间空闲的连接会被服务端掐断，
        // 保活检查可以避免取到死连接。
        hikari.setMaxLifetime(config.getLong("mysql.max-lifetime", 1_800_000L));
        hikari.setKeepaliveTime(config.getLong("mysql.keepalive-time", 300_000L));

        this.dataSource = new HikariDataSource(hikari);

        createTables(plugin);
    }

    /**
     * 建表。
     *
     * @param plugin 插件实例
     * @throws SQLException 建表失败时抛出
     */
    private void createTables(Plugin plugin) throws SQLException {
        String menuItemTable = createTableIfNotExists("menu_item",
                autoIncrementPrimaryKey()
                        + ", menu_name VARCHAR(64)"
                        + ", item_md5 VARCHAR(64)"
                        + ", item_json TEXT"
                        + ", create_time BIGINT"
                        + ", UNIQUE KEY uk_menu_item_md5 (item_md5)");

        String menuLimitTable = createTableIfNotExists("menu_limit",
                autoIncrementPrimaryKey()
                        + ", player_name VARCHAR(64) NOT NULL"
                        + ", player_uuid VARCHAR(64) NOT NULL"
                        + ", menu_item_id INT NOT NULL"
                        + ", number INT NOT NULL"
                        + ", click_time BIGINT"
                        + ", UNIQUE KEY uk_menu_limit_player_item (player_uuid, menu_item_id)");

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(menuItemTable);
            statement.executeUpdate(menuLimitTable);
            plugin.getLogger().info("MySQL 存储已就绪");
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("存储尚未初始化");
        }
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            try {
                dataSource.close();
            } catch (Exception ignored) {
                // 关闭阶段的异常无需惊动使用者
            }
        }
    }

    @Override
    public String dialect() {
        return "mysql";
    }

    @Override
    public String autoIncrementPrimaryKey() {
        return "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY";
    }

    @Override
    public String createTableIfNotExists(String table, String body) {
        return "CREATE TABLE IF NOT EXISTS " + table + " (" + body
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    }
}
