package cn.listudio.exmenu.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

/**
 * 基于 HikariCP 的 SQLite 存储实现。
 *
 * <p>SQLite 是默认后端，适合单机服。几个刻意的设置：
 * <ul>
 *   <li>连接池上限 1 —— SQLite 的写锁是库级的，多个写连接只会互相阻塞并提高
 *       {@code SQLITE_BUSY} 的概率。串行化反而更快更稳。</li>
 *   <li>开启 WAL 日志模式 —— 允许读与写并发，显著降低开菜单时的卡顿。</li>
 *   <li>{@code busy_timeout=5000} —— 遇到锁时等待而非立刻失败。</li>
 * </ul>
 */
public final class SqliteStorage implements Storage {

    /** 连接池上限。SQLite 写锁是库级的，多连接只会互相阻塞。 */
    private static final int POOL_SIZE = 1;

    /** 遇到锁时的等待毫秒数。 */
    private static final int BUSY_TIMEOUT_MS = 5000;

    private HikariDataSource dataSource;
    private File databaseFile;

    @Override
    public void init(Plugin plugin, YamlConfiguration config) throws SQLException {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new SQLException("无法创建插件数据目录: " + dataFolder.getAbsolutePath());
        }

        String fileName = config.getString("sqlite.file", "menu.db");
        this.databaseFile = new File(dataFolder, fileName);

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("EXMenu-SQLite");
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        hikari.setMaximumPoolSize(POOL_SIZE);
        hikari.setMinimumIdle(POOL_SIZE);
        hikari.setConnectionTimeout(10_000L);
        // SQLite 的 WAL 与 busy_timeout 通过连接参数下发
        hikari.addDataSourceProperty("journal_mode", "WAL");
        hikari.addDataSourceProperty("busy_timeout", String.valueOf(BUSY_TIMEOUT_MS));
        // 连接存活检查：SQLite 几乎不会失联，但代价极低的检查能避免极端情况下的坏连接
        hikari.setConnectionTestQuery("SELECT 1");

        this.dataSource = new HikariDataSource(hikari);

        createTables(plugin);
    }

    /**
     * 建表。
     *
     * @param plugin 插件实例，用于打日志
     * @throws SQLException 建表失败时抛出
     */
    private void createTables(Plugin plugin) throws SQLException {
        String menuItemTable = createTableIfNotExists("menu_item",
                autoIncrementPrimaryKey()
                        + ", menu_name VARCHAR(64)"
                        + ", item_md5 VARCHAR(64)"
                        + ", item_json TEXT"
                        + ", create_time BIGINT"
                        + ", UNIQUE (item_md5)");

        String menuLimitTable = createTableIfNotExists("menu_limit",
                autoIncrementPrimaryKey()
                        + ", player_name VARCHAR(64) NOT NULL"
                        + ", player_uuid VARCHAR(64) NOT NULL"
                        + ", menu_item_id INT NOT NULL"
                        + ", number INT NOT NULL"
                        + ", click_time BIGINT"
                        + ", UNIQUE (player_uuid, menu_item_id)");

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(menuItemTable);
            statement.executeUpdate(menuLimitTable);
            plugin.getLogger().info("SQLite 存储已就绪: " + databaseFile.getName());
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
            } catch (Exception e) {
                // 关闭阶段的异常无需惊动使用者
            }
        }
    }

    @Override
    public String dialect() {
        return "sqlite";
    }

    @Override
    public String autoIncrementPrimaryKey() {
        return "id INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    @Override
    public File dataDirectory() {
        return databaseFile == null ? null : databaseFile.getParentFile();
    }
}
