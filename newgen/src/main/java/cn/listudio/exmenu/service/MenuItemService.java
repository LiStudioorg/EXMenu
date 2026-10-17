package cn.listudio.exmenu.service;

import cn.listudio.exmenu.enter.MenuItem;
import cn.listudio.exmenu.storage.Storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 物品库数据访问对象。
 *
 * <p>物品库的读远多于写（每次开菜单要读该菜单引用的全部 id），因此这里做了两件事：
 * <ol>
 *   <li>{@link #findMapByIds} 用单条 {@code IN} 查询取回一批，避免按按钮逐个查的 N+1</li>
 *   <li>{@link #save} 用"先更新、影响 0 行则插入"的方式实现 upsert，
 *       而不是先 SELECT 再决定 INSERT/UPDATE —— 后者在并发下会产生重复行</li>
 * </ol>
 *
 * <p>所有方法都是同步阻塞的，必须在异步线程调用。
 */
public final class MenuItemService {

    private final Storage storage;
    private final Logger logger;

    public MenuItemService(Storage storage, Logger logger) {
        this.storage = storage;
        this.logger = logger;
    }

    /**
     * 保存物品，按 MD5 去重。
     *
     * <p>upsert 语义：MD5 已存在则更新内容并保留原 id，否则插入新行。
     *
     * <p><b>为什么是"先插后查"而不是"先查后插"</b>：先 {@code SELECT} 再决定
     * 插入或更新的写法有竞态 —— 两个线程可能都查到"不存在"而双双插入，
     * 唯一索引会让其中一个失败并返回 null（调用方看到"存储失败"，但物品其实已入库）。
     * 这里改为直接尝试插入：插入成功即完成；撞唯一索引说明别人先插了，
     * 此时再查回那个 id 即可。整个过程不依赖"读到旧值"这个前提。
     *
     * @param item 待保存物品
     * @return 保存后的 id；失败时返回 null
     */
    public Integer save(MenuItem item) {
        if (item == null || item.getItemMd5() == null) {
            return null;
        }
        Integer inserted = insert(item);
        if (inserted != null) {
            return inserted;
        }
        // 插入没成功：要么是 MD5 冲突（正常竞态），要么是真的写库失败。
        // 查一次即可区分 —— 查得到说明是前者，查不到说明是后者。
        Integer existing = findIdByMd5(item.getItemMd5());
        if (existing != null) {
            update(existing, item);
        }
        return existing;
    }

    /**
     * 按 MD5 查 id。
     *
     * @param md5 物品 MD5
     * @return id，未找到返回 null
     */
    public Integer findIdByMd5(String md5) {
        String sql = "SELECT id FROM menu_item WHERE item_md5 = ?";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, md5);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "查询物品库 id 失败: md5=" + md5, e);
        }
        return null;
    }

    /**
     * 插入新物品。
     *
     * @param item 物品
     * @return 新生成的 id；失败返回 null
     */
    private Integer insert(MenuItem item) {
        String sql = "INSERT INTO menu_item (menu_name, item_md5, item_json, create_time) VALUES (?, ?, ?, ?)";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, item.getMenuName());
            statement.setString(2, item.getItemMd5());
            statement.setString(3, item.getItemJson());
            statement.setLong(4, item.getCreateTime() == null ? System.currentTimeMillis() : item.getCreateTime());
            if (statement.executeUpdate() <= 0) {
                return null;
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "写入物品库失败", e);
        }
        return null;
    }

    /**
     * 更新已有物品的内容。
     *
     * @param id   主键
     * @param item 新内容
     * @return true 表示更新成功
     */
    private boolean update(Integer id, MenuItem item) {
        String sql = "UPDATE menu_item SET menu_name = ?, item_json = ? WHERE id = ?";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, item.getMenuName());
            statement.setString(2, item.getItemJson());
            statement.setInt(3, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "更新物品库失败: id=" + id, e);
            return false;
        }
    }

    /**
     * 按 id 查询单个物品。
     *
     * @param id 主键
     * @return 物品，未找到或失败返回 null
     */
    public MenuItem findById(Integer id) {
        if (id == null) {
            return null;
        }
        String sql = "SELECT * FROM menu_item WHERE id = ?";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "查询物品失败: id=" + id, e);
            return null;
        }
    }

    /**
     * 批量按 id 查询，返回 id 到物品的映射。
     *
     * <p>这是菜单渲染路径上的关键方法：一个菜单可能有几十个按钮引用物品库，
     * 逐个查询会造成明显的开菜单延迟。
     *
     * @param ids 主键集合，可为空
     * @return 映射，永不返回 null
     */
    public Map<Integer, MenuItem> findMapByIds(Collection<Integer> ids) {
        Map<Integer, MenuItem> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        List<Integer> distinct = new ArrayList<>();
        for (Integer id : ids) {
            if (id != null && id > 0 && !distinct.contains(id)) {
                distinct.add(id);
            }
        }
        if (distinct.isEmpty()) {
            return result;
        }

        StringBuilder sql = new StringBuilder("SELECT * FROM menu_item WHERE id IN (");
        for (int i = 0; i < distinct.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(')');

        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < distinct.size(); i++) {
                statement.setInt(i + 1, distinct.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    MenuItem item = map(rs);
                    result.put(item.getId(), item);
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "批量查询物品失败", e);
        }
        return result;
    }

    /**
     * 查询全部物品，用于管理界面。
     *
     * @return 列表，永不返回 null
     */
    public List<MenuItem> findAll() {
        List<MenuItem> list = new ArrayList<>();
        String sql = "SELECT * FROM menu_item ORDER BY id DESC";
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "查询全部物品失败", e);
        }
        return list;
    }

    /**
     * 删除指定 id 的物品。
     *
     * @param ids 主键集合
     * @return 实际删除的行数
     */
    public int deleteByIds(Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("DELETE FROM menu_item WHERE id IN (");
        List<Integer> distinct = new ArrayList<>();
        for (Integer id : ids) {
            if (id != null) {
                distinct.add(id);
            }
        }
        if (distinct.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < distinct.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(')');

        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < distinct.size(); i++) {
                statement.setInt(i + 1, distinct.get(i));
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "删除物品失败", e);
            return 0;
        }
    }

    /**
     * 清空物品库。
     *
     * @return 删除的行数；失败返回 -1
     */
    public int deleteAll() {
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM menu_item")) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "清空物品库失败", e);
            return -1;
        }
    }

    /**
     * 统计物品总数。
     *
     * @return 总数，失败返回 0
     */
    public int count() {
        try (Connection connection = storage.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM menu_item");
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "统计物品数失败", e);
            return 0;
        }
    }

    /**
     * 把结果集一行映射为实体。
     *
     * @param rs 结果集，当前行有效
     * @return 实体
     * @throws SQLException 读取失败
     */
    private MenuItem map(ResultSet rs) throws SQLException {
        MenuItem item = new MenuItem();
        item.setId(rs.getInt("id"));
        item.setMenuName(rs.getString("menu_name"));
        item.setItemMd5(rs.getString("item_md5"));
        item.setItemJson(rs.getString("item_json"));
        item.setCreateTime(rs.getLong("create_time"));
        return item;
    }

    /**
     * 空结果常量，避免调用方判空。
     *
     * @return 空映射
     */
    public static Map<Integer, MenuItem> emptyMap() {
        return Collections.emptyMap();
    }
}
