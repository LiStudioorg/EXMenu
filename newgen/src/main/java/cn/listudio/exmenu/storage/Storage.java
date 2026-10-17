package cn.listudio.exmenu.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 存储后端抽象。
 *
 * <p>服务端数据层只依赖这个接口，具体是 SQLite 还是 MySQL 由 {@code storage.yml}
 * 决定。约定：
 * <ul>
 *   <li>所有方法都可能被异步线程调用，实现必须线程安全</li>
 *   <li>{@link #getConnection()} 返回的连接由调用方负责关闭（用 try-with-resources）</li>
 *   <li>建表语句在 {@link #init()} 内执行，重复调用应当是幂等的</li>
 * </ul>
 *
 * <p>注意：{@code storage.yml} 的改动需要重启服务器才能生效，与 PlayerMenu 的行为一致 ——
 * 因为连接池一旦建立，中途切换方言会导致已缓存的 PreparedStatement 失效。
 */
public interface Storage {

    /**
     * 初始化存储：建立连接池、创建表结构。
     *
     * <p>幂等，重复调用不会重复建表。
     *
     * @param plugin 插件实例
     * @param config storage.yml 的内容
     * @throws SQLException 连接或建表失败时抛出，由调用方决定是否禁用插件
     */
    void init(Plugin plugin, YamlConfiguration config) throws SQLException;

    /**
     * 获取一个数据库连接。
     *
     * <p>调用方必须关闭它。返回的连接可能来自连接池，关闭只是归还。
     *
     * @return 可用连接
     * @throws SQLException 无法获取连接时抛出
     */
    Connection getConnection() throws SQLException;

    /**
     * 关闭存储，释放连接池。
     *
     * <p>插件卸载时调用，应当容忍重复调用。
     */
    void close();

    /**
     * 方言名，用于日志与调试。
     *
     * @return 例如 {@code sqlite} 或 {@code mysql}
     */
    String dialect();

    /**
     * 生成建表 SQL 里的自增主键列定义。
     *
     * <p>SQLite 用 {@code INTEGER PRIMARY KEY AUTOINCREMENT}，
     * MySQL 用 {@code INT NOT NULL AUTO_INCREMENT}，两者不通用。
     *
     * @return 主键列定义片段
     */
    String autoIncrementPrimaryKey();

    /**
     * 生成"若不存在则建表"的语句前缀。
     *
     * <p>SQLite 支持 {@code CREATE TABLE IF NOT EXISTS}，MySQL 也支持，
     * 但 MySQL 需要额外指定引擎与字符集。默认实现直接返回通用前缀。
     *
     * @param table 表名
     * @param body  列定义
     * @return 完整的建表语句
     */
    default String createTableIfNotExists(String table, String body) {
        return "CREATE TABLE IF NOT EXISTS " + table + " (" + body + ")";
    }

    /**
     * 数据文件所在目录（SQLite 用），MySQL 返回 null。
     *
     * @return 目录，或 null
     */
    default File dataDirectory() {
        return null;
    }
}
