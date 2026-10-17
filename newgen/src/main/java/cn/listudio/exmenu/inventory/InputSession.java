package cn.listudio.exmenu.inventory;

import cn.listudio.exmenu.param.MenuButtonParam;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;

/**
 * 一次等待输入的会话。
 *
 * <p>玩家点了带 {@code input} 的按钮后，界面关闭、进入聊天输入状态。
 * 本对象保存"输入完了要做什么"所需的全部上下文 —— 没有它，玩家打完字
 * 之后插件就不知道该执行哪个按钮、该不该弹确认框了。
 *
 * <p>字段做成不可变的：会话在创建后不会被修改，因此不存在
 * "输入还没来，上下文已经被下一次点击覆盖"的问题。原版把上下文存在
 * 一个共享的 Map 里并允许覆盖，导致玩家连点两个输入按钮时行为错乱。
 */
public final class InputSession {

    /** 触发输入的按钮（已经复制过，带本次点击类型）。 */
    private final MenuButtonParam param;

    /** 输入前的界面，用于输入完成后返回。 */
    private final ChestGui sourceGui;

    /** 创建时刻，用于超时判断。 */
    private final long createTime = System.currentTimeMillis();

    /**
     * 构造会话。
     *
     * @param param    按钮
     * @param sourceGui 来源界面
     */
    public InputSession(MenuButtonParam param, ChestGui sourceGui) {
        this.param = param;
        this.sourceGui = sourceGui;
    }

    /**
     * 触发输入的按钮。
     *
     * @return 按钮
     */
    public MenuButtonParam getParam() {
        return param;
    }

    /**
     * 来源界面。
     *
     * @return 界面，可能为 null
     */
    public ChestGui getSourceGui() {
        return sourceGui;
    }

    /**
     * 会话是否已超时。
     *
     * @param timeoutSeconds 超时秒数
     * @return true 表示超时
     */
    public boolean isExpired(int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            return false;
        }
        return System.currentTimeMillis() - createTime > timeoutSeconds * 1000L;
    }

    /**
     * 已经等待的秒数。
     *
     * @return 秒数
     */
    public long elapsedSeconds() {
        return (System.currentTimeMillis() - createTime) / 1000L;
    }
}
