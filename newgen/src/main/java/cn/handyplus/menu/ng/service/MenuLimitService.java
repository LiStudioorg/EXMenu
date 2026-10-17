package cn.handyplus.menu.ng.service;

import cn.handyplus.menu.ng.enter.MenuLimit;
import cn.handyplus.menu.ng.storage.Storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 菜单限制数据访问对象。
 *
 * <p>本类刻意避开了"先查再写"的模式。原版 PlayerMenu 在这里有一个真实的竞态：
 * 两个并发点击都读到 {@code number = 4}，都写回 {@code 5}，于是限制为 5 次的按钮
 * 实际被点了 6 次。本实现改用两条原子路径：
 * <ul>
 *   <li><b>累加计数</b>：直接 {@code UPDATE ... SET number = number + 1}，
 *       由数据库保证读改写原子性</li>
 *   <li><b>首次写入</b>：{@code INSERT} 撞唯一索引后转为 {@code UPDATE}，
 *       不依赖任何前置查询</li>
 * </ul>
 *
 * <p>所有方法都是同步阻塞的，必须在异步线程调用。
 */
public final class MenuLimitService {

    private final Storage storage;
    private final Logger logger;

    public MenuLimitService(Storage storage, Logger logger) {
        this.storage = storage;
        this.logger = logger;
    }

    /**
     * 查询某玩家在某个菜单按钮上的记录。
     *
     * @param playerUuid 玩家 UUID
     * @param menuItemId 菜单按钮 id
     * @return 记录，未找到或失败返回 null
     */
    public MenuLimit find(UUID playerUuid, Integer menuItemId) {
        if (playerUuid == null || menuItemId == null) {
            return null;
        }
        String sql = "SELECT * FROM menu_limit WHERE player_uuid = ? AND menu_item_id = ?";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setInt(2, menuItemId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "查询限制记录失败: uuid=" + playerUuid + ", id=" + menuItemId, e);
            return null;
        }
    }

    /**
     * 批量查询某玩家在所有菜单按钮上的记录。
     *
     * <p>菜单渲染路径上的关键方法：渲染一个菜单要判断每个按钮的冷却与剩余次数，
     * 逐按钮查询会产生 N+1。
     *
     * @param playerUuid 玩家 UUID
     * @return 以 {@code menuItemId} 为键的映射，永不返回 null
     */
    public Map<Integer, MenuLimit> findMapByPlayerUuid(UUID playerUuid) {
        Map<Integer, MenuLimit> result = new HashMap<>();
        if (playerUuid == null) {
            return result;
        }
        String sql = "SELECT * FROM menu_limit WHERE player_uuid = ?";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    MenuLimit limit = map(rs);
                    result.put(limit.getMenuItemId(), limit);
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "批量查询限制记录失败: uuid=" + playerUuid, e);
        }
        return result;
    }

    /**
     * 记录一次点击时间（冷却限制用）。
     *
     * <p>时间戳直接覆盖写入，不依赖读取旧值。记录不存在时自动创建。
     *
     * @param playerName 玩家名
     * @param playerUuid 玩家 UUID
     * @param menuItemId 菜单按钮 id
     * @param clickTime  点击时间戳，毫秒
     * @return true 表示写入成功
     */
    public boolean setClickTime(String playerName, UUID playerUuid, Integer menuItemId, long clickTime) {
        if (playerUuid == null || menuItemId == null) {
            return false;
        }
        String update = "UPDATE menu_limit SET click_time = ?, player_name = ? "
                + "WHERE player_uuid = ? AND menu_item_id = ?";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setLong(1, clickTime);
            statement.setString(2, playerName);
            statement.setString(3, playerUuid.toString());
            statement.setInt(4, menuItemId);

            if (statement.executeUpdate() > 0) {
                return true;
            }
            // 没有命中任何行 —— 记录尚不存在，插入之。
            // 若此时另一线程刚插入，唯一索引会让本插入失败，属于预期内的正常竞态，
            // 此时再回退到 UPDATE 即可保证最终一致。
            return insertRecord(playerName, playerUuid, menuItemId, 0, clickTime)
                    || forceUpdateClickTime(playerName, playerUuid, menuItemId, clickTime);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "写入冷却时间失败: uuid=" + playerUuid + ", id=" + menuItemId, e);
            return false;
        }
    }

    /**
     * 原子地"占一次使用名额"：仅当已用次数小于上限时才自增。
     *
     * <p>这是消除限购超发的关键。<b>为什么不能在 Java 里先判断再自增</b>：
     * {@code SELECT} 出门槛、{@code UPDATE} 写记录是两个独立语句，两个并发点击
     * 可能都读到 {@code number = 4}（上限 5）而双双放行，最终写成 6。
     * 把判断塞进 {@code WHERE} 里由数据库裁决，就没有这个窗口。
     *
     * @param playerName 玩家名
     * @param playerUuid 玩家 UUID
     * @param menuItemId 菜单按钮 id
     * @param max        允许的最大次数，必须大于 0
     * @return true 表示占用成功（未超限）；false 表示已达上限或写入失败
     */
    public boolean tryIncrementWithinLimit(String playerName, UUID playerUuid,
                                           Integer menuItemId, int max) {
        if (playerUuid == null || menuItemId == null || max <= 0) {
            return false;
        }
        String update = "UPDATE menu_limit SET number = number + 1, player_name = ? "
                + "WHERE player_uuid = ? AND menu_item_id = ? AND number < ?";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setString(1, playerName);
            statement.setString(2, playerUuid.toString());
            statement.setInt(3, menuItemId);
            statement.setInt(4, max);
            if (statement.executeUpdate() > 0) {
                return true;
            }
            // 没有命中：要么已到上限，要么记录还不存在。
            // 试着插入一条 number = 1 的新记录，插入成功即视为占位成功。
            // 若另一线程同时插入，唯一索引会挡下；那说明记录已存在且已达上限，
            // 此时直接返回 false —— 再 UPDATE 一次会绕过上限。
            return insertRecord(playerName, playerUuid, menuItemId, 1, 0L);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "占用限购名额失败: uuid=" + playerUuid + ", id=" + menuItemId, e);
            return false;
        }
    }

    /**
     * 累加一次点击计数（次数限制用）。
     *
     * <p>这个操作是完全原子的：{@code number = number + 1} 由数据库执行，
     * 不存在"读到旧值再写回"的窗口。
     *
     * @param playerName 玩家名
     * @param playerUuid 玩家 UUID
     * @param menuItemId 菜单按钮 id
     * @param delta      增量，通常为 1
     * @return true 表示写入成功
     */
    public boolean addNumber(String playerName, UUID playerUuid, Integer menuItemId, int delta) {
        if (playerUuid == null || menuItemId == null) {
            return false;
        }
        String update = "UPDATE menu_limit SET number = number + ?, player_name = ? "
                + "WHERE player_uuid = ? AND menu_item_id = ?";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setInt(1, delta);
            statement.setString(2, playerName);
            statement.setString(3, playerUuid.toString());
            statement.setInt(4, menuItemId);

            if (statement.executeUpdate() > 0) {
                return true;
            }
            return insertRecord(playerName, playerUuid, menuItemId, delta, 0L)
                    || forceAddNumber(playerName, playerUuid, menuItemId, delta);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "累加点击次数失败: uuid=" + playerUuid + ", id=" + menuItemId, e);
            return false;
        }
    }

    /**
     * 插入一条新记录。
     *
     * @param playerName 玩家名
     * @param playerUuid 玩家 UUID
     * @param menuItemId 菜单按钮 id
     * @param number     初始计数
     * @param clickTime  初始点击时间
     * @return true 表示插入成功
     */
    private boolean insertRecord(String playerName, UUID playerUuid, Integer menuItemId,
                                 int number, long clickTime) {
        String sql = "INSERT INTO menu_limit (player_name, player_uuid, menu_item_id, number, click_time) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName);
            statement.setString(2, playerUuid.toString());
            statement.setInt(3, menuItemId);
            statement.setInt(4, number);
            statement.setLong(5, clickTime);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            // 唯一索引冲突是并发下的正常情况，交由调用方回退到 UPDATE
            return false;
        }
    }

    /**
     * 兜底：强制覆盖点击时间。
     *
     * @param playerName 玩家名
     * @param playerUuid 玩家 UUID
     * @param menuItemId 菜单按钮 id
     * @param clickTime  时间戳
     * @return true 表示成功
     */
    private boolean forceUpdateClickTime(String playerName, UUID playerUuid, Integer menuItemId, long clickTime) {
        String sql = "UPDATE menu_limit SET click_time = ?, player_name = ? WHERE player_uuid = ? AND menu_item_id = ?";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, clickTime);
            statement.setString(2, playerName);
            statement.setString(3, playerUuid.toString());
            statement.setInt(4, menuItemId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 兜底：强制累加计数。
     *
     * @param playerName 玩家名
     * @param playerUuid 玩家 UUID
     * @param menuItemId 菜单按钮 id
     * @param delta      增量
     * @return true 表示成功
     */
    private boolean forceAddNumber(String playerName, UUID playerUuid, Integer menuItemId, int delta) {
        String sql = "UPDATE menu_limit SET number = number + ?, player_name = ? WHERE player_uuid = ? AND menu_item_id = ?";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, delta);
            statement.setString(2, playerName);
            statement.setString(3, playerUuid.toString());
            statement.setInt(4, menuItemId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 清空某玩家在某个按钮上的计数，使其恢复未使用状态。
     *
     * @param playerUuid 玩家 UUID
     * @param menuItemId 菜单按钮 id
     * @return true 表示删除成功
     */
    public boolean reset(UUID playerUuid, Integer menuItemId) {
        if (playerUuid == null || menuItemId == null) {
            return false;
        }
        String sql = "DELETE FROM menu_limit WHERE player_uuid = ? AND menu_item_id = ?";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setInt(2, menuItemId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "重置限制记录失败", e);
            return false;
        }
    }

    /**
     * 批量清空若干按钮的限制记录（不区分玩家）。
     *
     * <p>给 {@code /plm clear <菜单>} 用：一个菜单的所有按钮应当一次性重置，
     * 逐条调用 {@link #reset} 会产生 N 次连接往返，因此改用单条 {@code IN} 语句。
     *
     * @param menuItemIds 按钮 id 列表；为 null 或空时不执行任何操作
     * @return 删除的行数；失败返回 -1
     */
    public int resetAll(List<Integer> menuItemIds) {
        if (menuItemIds == null || menuItemIds.isEmpty()) {
            return 0;
        }
        String sql = "DELETE FROM menu_limit WHERE menu_item_id IN (" + placeholders(menuItemIds.size()) + ")";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindIds(statement, menuItemIds, 1);
            return statement.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "批量清空限制记录失败", e);
            return -1;
        }
    }

    /**
     * 批量清空某玩家在若干按钮上的限制记录。
     *
     * <p>{@code menuItemIds} 为 null 时退化为清空该玩家的全部记录，
     * 这样 {@code /plm clear all <玩家>} 不必再单独走一条分支。
     *
     * @param playerUuid  玩家 UUID
     * @param menuItemIds 按钮 id 列表；null 表示该玩家的全部记录
     * @return 删除的行数；失败返回 -1
     */
    public int reset(UUID playerUuid, List<Integer> menuItemIds) {
        if (playerUuid == null) {
            return 0;
        }
        boolean all = menuItemIds == null || menuItemIds.isEmpty();
        String sql = all
                ? "DELETE FROM menu_limit WHERE player_uuid = ?"
                : "DELETE FROM menu_limit WHERE player_uuid = ? AND menu_item_id IN ("
                        + placeholders(menuItemIds.size()) + ")";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            if (!all) {
                bindIds(statement, menuItemIds, 2);
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "批量重置限制记录失败: uuid=" + playerUuid, e);
            return -1;
        }
    }

    /**
     * 生成 {@code ?, ?, ?} 形式的占位符串。
     *
     * @param count 占位符个数，必须大于 0
     * @return 占位符串
     */
    private String placeholders(int count) {
        StringBuilder sb = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
        }
        return sb.toString();
    }

    /**
     * 从指定下标开始绑定 id 参数。
     *
     * @param statement 语句
     * @param ids       id 列表
     * @param startAt   起始参数下标，从 1 开始
     * @throws SQLException 绑定失败
     */
    private void bindIds(PreparedStatement statement, List<Integer> ids, int startAt) throws SQLException {
        int index = startAt;
        for (Integer id : ids) {
            statement.setInt(index++, id);
        }
    }

    /**
     * 查询全部限制记录。
     *
     * @return 列表，永不返回 null
     */
    public List<MenuLimit> findAll() {
        List<MenuLimit> list = new ArrayList<>();
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM menu_limit ORDER BY id DESC");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "查询全部限制记录失败", e);
        }
        return list;
    }

    /**
     * 清空全部限制记录。
     *
     * @return 删除的行数；失败返回 -1
     */
    public int deleteAll() {
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM menu_limit")) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "清空限制记录失败", e);
            return -1;
        }
    }

    /**
     * 把结果集一行映射为实体。
     *
     * @param rs 结果集，当前行有效
     * @return 实体
     * @throws SQLException 读取失败
     */
    private MenuLimit map(ResultSet rs) throws SQLException {
        MenuLimit limit = new MenuLimit();
        limit.setId(rs.getInt("id"));
        limit.setPlayerName(rs.getString("player_name"));
        limit.setPlayerUuid(rs.getString("player_uuid"));
        limit.setMenuItemId(rs.getInt("menu_item_id"));
        limit.setNumber(rs.getInt("number"));
        limit.setClickTime(rs.getLong("click_time"));
        return limit;
    }
}
