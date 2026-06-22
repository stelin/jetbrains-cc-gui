package com.github.claudecodegui.provider.claude;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 三级容错 JSON 抽取(设计 §6.1)，专为从模型流式文本中取出 ```json 围栏块而设计。
 *
 * <p>三个层次：
 * <ol>
 *   <li>取文本中<b>最后一个</b> ```json … ``` 围栏块，尝试解析为 JsonObject。</li>
 *   <li>失败则退取<b>最后一个</b>平衡的 {@code { … }} 子串，再解析。</li>
 *   <li>再失败则返回 {@code null}，调用方走兜底原文路径。</li>
 * </ol>
 *
 * <p>纯工具类，无外部依赖，可独立编译。
 */
public final class JsonExtract {

    private JsonExtract() {
    }

    // ── 公开 API ──────────────────────────────────────────────────────

    /**
     * 三级容错提取。
     *
     * @param text 模型输出全文（可含工具调用过程、思考文本、最终 json 块）
     * @return 解析成功则返回 {@link AnalysisResult}（持有 {bugs, groups} JsonObject）；
     *         三级均失败则返回 {@code null}
     */
    public static AnalysisResult fromFenced(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // Level 1: 最后一个 ```json … ``` 围栏块
        String fenced = extractLastFencedJson(text);
        if (fenced != null) {
            JsonObject obj = parseObject(fenced);
            if (obj != null) {
                return new AnalysisResult(obj);
            }
        }

        // Level 2: 最后一个平衡 { … } 子串
        String braced = extractLastBalancedBrace(text);
        if (braced != null) {
            JsonObject obj = parseObject(braced);
            if (obj != null) {
                return new AnalysisResult(obj);
            }
        }

        // Level 3: 全部失败
        return null;
    }

    // ── 结果模型 ───────────────────────────────────────────────────────

    /**
     * 解析成功的结果。持有原始 JsonObject（含 "bugs" 与 "groups" 数组），
     * 供 BugAnalysisCollector §6.2 序列化为 {@code "result": {...}} 字段。
     *
     * <p>字段对应 §7 schema：
     * <pre>
     *   bugs[]:  serialNumber / identifier / subject / clarity / reason / missing
     *   groups[]: dimension / label / members / rootCauseGuess
     * </pre>
     */
    public static final class AnalysisResult {

        private final JsonObject json;

        AnalysisResult(JsonObject json) {
            this.json = json;
        }

        /**
         * 返回持有的 JsonObject（{bugs:[…], groups:[…]}），可直接作为 §6.2 result 字段值。
         */
        public JsonObject toJson() {
            return json;
        }
    }

    // ── 私有提取逻辑 ──────────────────────────────────────────────────

    /**
     * Level 1: 取文本中最后一个 ```json…``` 围栏块的内容字符串。
     *
     * <p>对「最后一个」的定义：循环找所有 {@code ```json} 出现位置，取下标最大的那个；
     * 然后找其后第一个"纯关闭围栏"（即 {@code ```} 之后紧跟换行/空白/末尾，而非
     * {@code ```json} / {@code ```java} 等语言标识符）。
     */
    private static String extractLastFencedJson(String text) {
        final String OPEN_MARKER = "```json";
        int lastOpen = -1;
        int search = 0;
        while (true) {
            int found = text.indexOf(OPEN_MARKER, search);
            if (found == -1) {
                break;
            }
            lastOpen = found;
            search = found + OPEN_MARKER.length();
        }
        if (lastOpen == -1) {
            return null;
        }

        // contentStart: 跳过 ```json 后可能跟着的换行
        int contentStart = lastOpen + OPEN_MARKER.length();
        if (contentStart < text.length() && text.charAt(contentStart) == '\r') {
            contentStart++;
        }
        if (contentStart < text.length() && text.charAt(contentStart) == '\n') {
            contentStart++;
        }

        // 找关闭围栏: 第一个 ``` 后面不紧跟字母/数字(即不是 ```json/```java 等开启围栏)
        int closeSearch = contentStart;
        while (closeSearch < text.length()) {
            int tick = text.indexOf("```", closeSearch);
            if (tick == -1) {
                break;
            }
            int afterTick = tick + 3;
            boolean isClosing = afterTick >= text.length()
                    || text.charAt(afterTick) == '\n'
                    || text.charAt(afterTick) == '\r'
                    || text.charAt(afterTick) == ' '
                    || text.charAt(afterTick) == '\t';
            if (isClosing) {
                return text.substring(contentStart, tick).trim();
            }
            closeSearch = afterTick;
        }
        return null;
    }

    /**
     * Level 2: 扫描全文取最后一个深度平衡的 {@code { … }} 子串，
     * 正确跳过字符串字面量内的大括号（逐字符状态机，与 ClaudeJsonOutputExtractor 同款）。
     */
    private static String extractLastBalancedBrace(String text) {
        String last = null;
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = inString;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (ch == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    String candidate = text.substring(start, i + 1).trim();
                    // 只记录合法 JsonObject（parse 成功且 isJsonObject）
                    if (isValidJsonObject(candidate)) {
                        last = candidate;
                    }
                    start = -1;
                }
            }
        }
        return last;
    }

    /** 尝试将文本解析为 JsonObject；失败返回 null。 */
    private static JsonObject parseObject(String text) {
        try {
            var el = JsonParser.parseString(text);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isValidJsonObject(String text) {
        return parseObject(text) != null;
    }
}
