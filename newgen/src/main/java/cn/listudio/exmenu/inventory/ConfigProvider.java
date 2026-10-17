package cn.listudio.exmenu.inventory;

import cn.listudio.exmenu.config.MenuConfig;

/**
 * 配置快照提供者。
 *
 * <p>之所以单独定义一个接口而不是让界面管理器直接持有 {@code MenuConfig} 引用，
 * 是因为配置在 reload 时会被<b>整体替换</b>。持有旧引用会让界面一直渲染旧菜单，
 * 直到玩家重新连接。通过提供者每次现取，reload 立刻生效。
 */
public interface ConfigProvider {

    /**
     * 取当前配置快照。
     *
     * @return 配置，永不返回 null
     */
    MenuConfig get();
}
