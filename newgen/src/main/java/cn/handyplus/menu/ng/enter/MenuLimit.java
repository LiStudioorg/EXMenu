package cn.handyplus.menu.ng.enter;

import java.util.Objects;

/**
 * 玩家对某个菜单按钮的使用记录。
 *
 * <p>一条记录同时承载两种限制的数据，由 {@code number} 与 {@code clickTime} 分工：
 * <ul>
 *   <li><b>次数限制</b>：{@code number} 累加点击次数，每次点击 +1</li>
 *   <li><b>冷却限制</b>：{@code clickTime} 记录上次点击时间戳，{@code number} 固定写 0</li>
 * </ul>
 *
 * <p>两者互不干扰，因此同一个按钮可以同时配冷却与次数限制。
 *
 * <p>唯一性由 {@code (playerUuid, menuItemId)} 复合唯一索引保证 —— 这也意味着
 * 并发写入必须依赖数据库的约束，而不是"先查再写"。
 */
public class MenuLimit {

    /** 自增主键。 */
    private Integer id;

    /** 玩家名，仅作追溯（玩家可能改名）。 */
    private String playerName;

    /** 玩家 UUID 字符串。 */
    private String playerUuid;

    /** 菜单按钮的 {@code id} 值（非 0）。 */
    private Integer menuItemId;

    /** 次数限制的计数器；冷却限制时固定为 0。 */
    private Integer number;

    /** 上次点击时间戳，毫秒；次数限制时固定为 0。 */
    private Long clickTime;

    public MenuLimit() {
    }

    public MenuLimit(String playerName, String playerUuid, Integer menuItemId, Integer number, Long clickTime) {
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.menuItemId = menuItemId;
        this.number = number;
        this.clickTime = clickTime;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public String getPlayerUuid() {
        return playerUuid;
    }

    public void setPlayerUuid(String playerUuid) {
        this.playerUuid = playerUuid;
    }

    public Integer getMenuItemId() {
        return menuItemId;
    }

    public void setMenuItemId(Integer menuItemId) {
        this.menuItemId = menuItemId;
    }

    public Integer getNumber() {
        return number;
    }

    public void setNumber(Integer number) {
        this.number = number;
    }

    public Long getClickTime() {
        return clickTime;
    }

    public void setClickTime(Long clickTime) {
        this.clickTime = clickTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MenuLimit)) {
            return false;
        }
        MenuLimit that = (MenuLimit) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MenuLimit{playerUuid='" + playerUuid + "', menuItemId=" + menuItemId
                + ", number=" + number + ", clickTime=" + clickTime + "}";
    }
}
