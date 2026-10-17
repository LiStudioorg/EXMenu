package cn.listudio.exmenu.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 保留注释的 YAML 标量补丁器。
 *
 * <p>为什么不用 {@code YamlConfiguration.set() + save()}：Bukkit 的保存会重写
 * 整个文件，把 {@code config.yml} 里逐键的中文说明全部丢掉 —— 对"看得懂配置"的
 * 用户无所谓，对新手用户等于毁掉了他们唯一能对照的说明书。
 *
 * <p>本类按行做最小替换：只动目标键的那一行（或那个小节的子行），
 * 其余行原样保留。支持形如 {@code language}、{@code clock.enable}、
 * {@code shift.F.enable} 的点路径（前缀必须是键路径的段）。
 *
 * <p>局限（刻意接受）：
 * <ul>
 *   <li>只处理标量（string/number/boolean），不处理列表与配置节结构</li>
 *   <li>键第一次出现的位置生效；若同一文档中该路径出现两次（不同节下同名片段），
 *       只替换首个匹配 —— {@code config.yml} 的实际结构保证这够用</li>
 *   <li>目标小节不存在时，把 {@code parent.child} 形式的行插到父节末尾；
 *       父节也不存在则追加到文件末尾（顶级键）</li>
 * </ul>
 */
public final class YamlPatch {

    private YamlPatch() {
    }

    /**
     * 把点路径的标量键写为指定文本值。
     *
     * <p>字符串值会被单引号包裹（YAML 安全），数字/布尔按原样写。
     *
     * @param file 目标文件
     * @param path 点分隔路径，如 {@code clock.enable}
     * @param value 新值
     * @return true 表示写入成功
     */
    public static boolean put(File file, String path, String value) {
        String rendered = render(value);
        return putRaw(file, path, rendered);
    }

    /**
     * 把点路径的标量键写为已渲染的原始文本（不加引号处理）。
     *
     * @param file 目标文件
     * @param path 点分隔路径
     * @param rawValue 已渲染的值文本
     * @return true 表示写入成功
     */
    public static boolean putRaw(File file, String path, String rawValue) {
        if (file == null || !file.exists() || TextUtil.isEmpty(path)) {
            return false;
        }
        List<String> lines;
        try {
            lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }

        String[] segments = path.split("\\.");
        String key = segments[segments.length - 1];
        // 父路径的段集合（用于校验"这一行确实在目标节内"）
        java.util.Set<String> parents = new java.util.HashSet<>();
        for (int i = 0; i < segments.length - 1; i++) {
            parents.add(segments[i]);
        }

        if (parents.isEmpty()) {
            // 顶级键：找第一行形如 "key:" 的行
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (matchesTopKey(line, key)) {
                    String indent = leadingWhitespace(line);
                    String trailing = trailingComment(line, key);
                    lines.set(i, indent + key + ": " + rawValue + trailing);
                    return writeLines(file, lines);
                }
            }
            // 不存在：追加
            lines.add(key + ": " + rawValue);
            return writeLines(file, lines);
        }

        // 带父路径：先定位父节起始行（"parent:" 恰好缩进 0 或链式匹配），
        // 再在其"子行区段"里找目标键。逐行扫描，用缩进判断区段边界。
        int parentStart = findParentStart(lines, segments);
        if (parentStart < 0) {
            // 父节不存在：在文件末尾补一个两行的小节（顶级键路径只有一层父）
            if (segments.length > 2) {
                return false;
            }
            lines.add(segments[0] + ":");
            lines.add("  " + key + ": " + rawValue);
            return writeLines(file, lines);
        }

        int parentIndent = indentOf(lines.get(parentStart));
        for (int i = parentStart + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                continue;
            }
            int indent = indentOf(line);
            if (indent <= parentIndent) {
                // 离开了父节区段且没找到 —— 在父节末尾插入
                lines.add(i, " ".repeat(parentIndent + 2) + key + ": " + rawValue
                        + " # 由配置中心添加");
                return writeLines(file, lines);
            }
            String trimmed = line.trim();
            if (matchesChildKey(trimmed, key)) {
                String indent2 = leadingWhitespace(line);
                String trailing = trailingComment(line, key);
                lines.set(i, indent2 + key + ": " + rawValue + trailing);
                return writeLines(file, lines);
            }
        }
        // 扫到文件尾仍在父节内：追加
        lines.add(" ".repeat(parentIndent + 2) + key + ": " + rawValue);
        return writeLines(file, lines);
    }

    /**
     * 定位多级父路径的最终节起始行。
     *
     * <p>逐级下降：先在缩进 0 找第一段的节行，再在其子行里找第二段……
     *
     * @param lines 全部行
     * @param segments 路径段
     * @return 最后一段父节点所在行号；找不到返回 -1
     */
    private static int findParentStart(List<String> lines, String[] segments) {
        int start = -1;
        int parentIndent = -1;
        for (int s = 0; s < segments.length - 1; s++) {
            String seg = segments[s];
            int found = -1;
            int from = start + 1;
            int to = lines.size();
            for (int i = from; i < to; i++) {
                String line = lines.get(i);
                if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                    continue;
                }
                int indent = indentOf(line);
                if (start >= 0 && indent <= parentIndent) {
                    break; // 离开了上一级节
                }
                if (start < 0 ? matchesTopKey(line, seg)
                        : indent > parentIndent && matchesChildKey(line.trim(), seg)) {
                    String rest = line.trim().substring(seg.length());
                    if (rest.startsWith(":")) {
                        found = i;
                        break;
                    }
                }
            }
            if (found < 0) {
                return -1;
            }
            start = found;
            parentIndent = indentOf(lines.get(found));
        }
        return start;
    }

    /**
     * 顶级键匹配：行首即键名（无缩进）。
     *
     * @param line 行
     * @param key 键
     * @return true 表示形如 {@code key: value}
     */
    private static boolean matchesTopKey(String line, String key) {
        if (line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
            return false;
        }
        return matchesChildKey(line, key);
    }

    /**
     * 键值行匹配（对已 trim 或顶级行都适用）。
     *
     * @param line 行文本
     * @param key 键
     * @return true 表示以 {@code key:} 开头
     */
    private static boolean matchesChildKey(String line, String key) {
        return line.startsWith(key + ":") || line.startsWith(key + " :");
    }

    /**
     * 提取行尾注释（含前导空格）。
     *
     * <p>只在"值之后"出现独立的 {@code #} 时视为注释 —— 引号包裹的值里的
     * {@code #} 不算（配置中心写的值都是简单标量，规则够用）。
     *
     * @param line 行
     * @param key 键名
     * @return 形如 {@code "  # xxx"} 的注释文本；无注释返回空串
     */
    private static String trailingComment(String line, String key) {
        int keyColon = line.indexOf(':');
        if (keyColon < 0) {
            return "";
        }
        String afterKey = line.substring(keyColon + 1);
        int hash = afterKey.indexOf('#');
        if (hash < 0) {
            return "";
        }
        // 统一为"单空格 + 原注释文本"
        return " " + afterKey.substring(hash);
    }

    /**
     * 行首缩进宽度。
     *
     * @param line 行
     * @return 空格数
     */
    private static int indentOf(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    /**
     * 行首空白原文。
     *
     * @param line 行
     * @return 前导空白
     */
    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    /**
     * 渲染标量值：字符串加单引号，其余原样。
     *
     * @param value 值
     * @return YAML 文本
     */
    private static String render(String value) {
        if (value == null) {
            return "''";
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return "''";
        }
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)
                || isNumber(text)) {
            return text;
        }
        return "'" + text.replace("'", "''") + "'";
    }

    /**
     * 简单数字判断（整数与小数）。
     *
     * @param text 文本
     * @return true 表示是数字
     */
    private static boolean isNumber(String text) {
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 写回文件（UTF-8，保留行尾风格：统一 LF→系统换行）。
     *
     * @param file 目标
     * @param lines 行
     * @return true 表示成功
     */
    private static boolean writeLines(File file, List<String> lines) {
        try {
            Files.write(file.toPath(), String.join(System.lineSeparator(), lines)
                    .getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
