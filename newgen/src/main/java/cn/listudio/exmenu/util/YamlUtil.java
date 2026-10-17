package cn.listudio.exmenu.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YAML 文件读写工具。
 *
 * <p>相比直接使用 {@link YamlConfiguration}，本类处理了三件容易出错的事：
 * <ol>
 *   <li>读取时显式指定 UTF-8 —— Bukkit 默认编码随平台变化，中文配置会乱码</li>
 *   <li>从 jar 内读取默认资源时同样按 UTF-8 解码</li>
 *   <li>保存时自动创建父目录</li>
 * </ol>
 */
public final class YamlUtil {

    private YamlUtil() {
    }

    /**
     * 加载磁盘上的 YAML 文件。
     *
     * @param file 目标文件
     * @return 配置对象；文件不存在或读取失败时返回一个空配置而非 null
     */
    public static YamlConfiguration load(File file) {
        if (file == null || !file.exists()) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    /**
     * 加载 jar 内的默认资源。
     *
     * @param plugin   插件实例
     * @param resource 资源路径，例如 {@code menu/example.yml}
     * @return 配置对象；资源不存在时返回空配置
     */
    public static YamlConfiguration loadResource(Plugin plugin, String resource) {
        try (InputStream stream = plugin.getResource(resource)) {
            if (stream == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    /**
     * 保存配置到磁盘，必要时创建父目录。
     *
     * @param config 配置对象
     * @param file   目标文件
     * @return true 表示保存成功
     */
    public static boolean save(YamlConfiguration config, File file) {
        if (config == null || file == null) {
            return false;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        try {
            config.save(file);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 把一个目录下所有 {@code .yml} 文件读成映射。
     *
     * <p>键为文件名（含扩展名），值即解析后的配置。目录不存在时返回空映射。
     *
     * @param directory 目标目录
     * @return 文件名到配置的映射，永不返回 null
     */
    public static Map<String, YamlConfiguration> loadDirectory(File directory) {
        Map<String, YamlConfiguration> result = new HashMap<>();
        if (directory == null || !directory.isDirectory()) {
            return result;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return result;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase().endsWith(".yml")) {
                result.put(file.getName(), load(file));
            }
        }
        return result;
    }

    /**
     * 读取字符串列表，兼容"单个字符串"与"字符串列表"两种写法。
     *
     * <p>配置文件里 {@code commands: "say hi"} 与 {@code commands: ["say hi"]} 都应被接受，
     * 这是玩家最容易写错的地方之一。
     *
     * @param section 配置节点
     * @param path    键路径
     * @return 字符串列表，永不返回 null
     */
    public static List<String> getStringList(ConfigurationSection section, String path) {
        List<String> result = new ArrayList<>();
        if (section == null) {
            return result;
        }
        Object raw = section.get(path);
        if (raw == null) {
            return result;
        }
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        // 单个标量：整体作为一项
        result.add(String.valueOf(raw));
        return result;
    }

    /**
     * 读取配置节，不存在时返回空节而非 null。
     *
     * @param section 配置节点
     * @param path    键路径
     * @return 配置节，永不返回 null
     */
    public static ConfigurationSection getSection(ConfigurationSection section, String path) {
        if (section == null) {
            return new YamlConfiguration();
        }
        ConfigurationSection child = section.getConfigurationSection(path);
        return child == null ? new YamlConfiguration() : child;
    }

    /**
     * 读取字符串，缺失时返回默认值。
     *
     * @param section      配置节点
     * @param path         键路径
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    public static String getString(ConfigurationSection section, String path, String defaultValue) {
        if (section == null) {
            return defaultValue;
        }
        return section.getString(path, defaultValue);
    }

    /**
     * 读取整数，缺失或格式非法时返回默认值。
     *
     * @param section      配置节点
     * @param path         键路径
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    public static int getInt(ConfigurationSection section, String path, int defaultValue) {
        if (section == null) {
            return defaultValue;
        }
        return section.getInt(path, defaultValue);
    }

    /**
     * 读取布尔值，缺失时返回默认值。
     *
     * @param section      配置节点
     * @param path         键路径
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    public static boolean getBoolean(ConfigurationSection section, String path, boolean defaultValue) {
        if (section == null) {
            return defaultValue;
        }
        return section.getBoolean(path, defaultValue);
    }
}
