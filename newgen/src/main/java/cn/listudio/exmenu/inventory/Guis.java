package cn.listudio.exmenu.inventory;

import com.github.stefvanschie.inventoryframework.gui.type.AnvilGui;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import org.bukkit.plugin.Plugin;

/**
 * IFramework 0.12.1 构造器崩溃的<b>强制绕行工厂</b> —— 删掉这里的 {@code {}} 会让整个
 * GUI 系统在真实服务端上崩，改动前务必读完下面的说明。
 *
 * <p><b>上游缺陷</b>（Inventory-Framework 0.12.1 引入，已发布版本中无修复版）：
 * {@code Gui.processMethodAnnotations} 在检查方法是否带注解<b>之前</b>，
 * 无条件读取 {@code method.getParameterTypes()[0]}（Gui.java:817）——
 * 只要运行时类里存在任何零参方法就抛
 * {@code ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0}。
 * 而 {@code ChestGui} 自身声明了 {@code update()} / {@code getInventory()} 等零参方法，
 * 因此任何 {@code new ChestGui(...)}（含普通菜单渲染）都会崩溃。
 * 真实服务端首个反馈（Leaves 1.21.11，/exmenu edit）即命中此路径。
 *
 * <p><b>规避原理</b>：该扫描只读 {@code getClass().getDeclaredMethods()}，
 * 即运行时类<b>自身声明</b>的方法，不含父类。用"无声明方法、无静态块"的
 * 匿名子类实例化，扫描得到空集合，行为与 0.11.x 完全一致。
 * 已用无头复现验证：直接 new 稳定抛 AIOOBE；匿名子类可完整构造。
 *
 * <p><b>升级路径</b>：当 IF 发布修复版本后，把本工厂的实现改回直接 {@code new}
 * （或直接删掉本类、恢复调用点），并同步删除文档中的绕行注记。
 *
 * <p>约束：本项目所有 Gui 实例化<b>必须</b>经由此工厂，不允许再出现裸的
 * {@code new ChestGui(...)} / {@code new AnvilGui(...)}；新增 GUI 类型时在此处补工厂方法。
 * 同样<b>不得调用 {@code Gui#copy()}</b> —— 它内部重建的是原生类实例，
 * 会绕过本工厂重新踩坑（本项目代码没有任何 Gui 复制需求）。
 */
final class Guis {

    private Guis() {
    }

    /**
     * 创建箱子界面（见类注释的空匿名子类规避原理）。
     *
     * @param rows  行数
     * @param title 已转色的标题
     * @return 界面
     */
    static ChestGui chest(int rows, String title) {
        // {} 是规避手段本体，不是笔误，勿删
        return new ChestGui(rows, title) {
        };
    }

    /**
     * 创建铁砧界面（同上）。
     *
     * @param title  已转色的标题
     * @param plugin 所属插件
     * @return 界面
     */
    static AnvilGui anvil(String title, Plugin plugin) {
        // {} 是规避手段本体，不是笔误，勿删
        return new AnvilGui(title, plugin) {
        };
    }
}
