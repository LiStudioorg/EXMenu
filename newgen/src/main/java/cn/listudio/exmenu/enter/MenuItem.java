package cn.listudio.exmenu.enter;

import java.util.Objects;

/**
 * 物品库条目。
 *
 * <p>玩家用 {@code /exmenu addItem} 把手上物品存入菜单物品库后，菜单按钮即可用
 * {@code id: <正数>} 引用它。这是让"任意 NBT 物品"能进菜单的唯一途径 ——
 * YAML 本身无法表达复杂的物品数据。
 *
 * <p>去重依据是 {@code itemMd5}：同一件物品重复添加只会更新，不会产生多条记录。
 */
public class MenuItem {

    /** 自增主键，对应菜单按钮里的 {@code id}。 */
    private Integer id;

    /** 添加时所在菜单的文件名，仅作追溯用，不参与业务判断。 */
    private String menuName;

    /** 物品内容的 MD5，用于去重。 */
    private String itemMd5;

    /** 物品的序列化数据（base64 编码的 Bukkit 序列化结果）。 */
    private String itemJson;

    /** 创建时间戳，毫秒。 */
    private Long createTime;

    public MenuItem() {
    }

    public MenuItem(String menuName, String itemMd5, String itemJson, Long createTime) {
        this.menuName = menuName;
        this.itemMd5 = itemMd5;
        this.itemJson = itemJson;
        this.createTime = createTime;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getMenuName() {
        return menuName;
    }

    public void setMenuName(String menuName) {
        this.menuName = menuName;
    }

    public String getItemMd5() {
        return itemMd5;
    }

    public void setItemMd5(String itemMd5) {
        this.itemMd5 = itemMd5;
    }

    public String getItemJson() {
        return itemJson;
    }

    public void setItemJson(String itemJson) {
        this.itemJson = itemJson;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MenuItem)) {
            return false;
        }
        MenuItem that = (MenuItem) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MenuItem{id=" + id + ", menuName='" + menuName + "', itemMd5='" + itemMd5 + "'}";
    }
}
