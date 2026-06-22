package com.github.claudecodegui.provider.claude;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 缺陷「AI 分析」prompt 组装(纯函数,无外部依赖)。
 *
 * <p>协调者-子智能体编排:让分析会话作为【协调者】,用 Task 子智能体【并发】分析缺陷
 * (最多同时 {@code concurrency} 个,批内并发、批间串行),收齐后由协调者做关联分组,
 * 最后只吐一个 ```json 围栏块(契约见下方 schema)。无法用 Task 时退化为顺序分析。
 *
 * <p>仅依赖 gson 读取入参 bug 列表,不触碰任何 daemon/bridge 状态,可独立编译。
 */
public final class BugAnalysisPrompt {

    private BugAnalysisPrompt() {
    }

    /**
     * 组 prompt 全文。
     *
     * @param bugs        webview 提交的快照数组,每个元素含 serialNumber / identifier / subject。
     * @param concurrency 最大并发子智能体数(选项 3/4/5;夹紧到 [1,5])。
     */
    public static String build(JsonArray bugs, int concurrency) {
        int n = bugs == null ? 0 : bugs.size();
        int conc = Math.max(1, Math.min(5, concurrency));
        StringBuilder sb = new StringBuilder();
        sb.append("你是缺陷分析【协调者】。下面是我选中的 ").append(n)
          .append(" 个云效缺陷,请用【子智能体并发】的方式分析,最后由你汇总输出结构化结果。\n\n");
        sb.append("缺陷清单(每行:编号 / 内部 identifier / 标题):\n");
        for (int i = 0; i < n; i++) {
            JsonObject b = asObject(bugs.get(i));
            String serial = optString(b, "serialNumber");
            String identifier = optString(b, "identifier");
            String subject = optString(b, "subject");
            sb.append(i + 1).append(". BUG-").append(serial)
              .append("  (id「").append(identifier).append("」)  ")
              .append(subject).append('\n');
        }
        sb.append('\n');
        sb.append(buildBody(conc));
        return sb.toString();
    }

    /** 向后兼容:无并发参数时默认 3。 */
    public static String build(JsonArray bugs) {
        return build(bugs, 3);
    }

    private static String buildBody(int conc) {
        return
            "【并发编排(关键)】\n" +
            "你只做【编排 + 汇总】,不要自己直接分析缺陷——把每个缺陷的分析下放给一个 Task 子智能体。\n" +
            "1. 用 Task 工具启动子智能体,**任何时刻最多同时 " + conc + " 个**:在同一条消息里一次性并发派出至多 " + conc +
            " 个 Task(它们会并发执行),等这一批【全部返回】后再派下一批,直到所有缺陷分析完。\n" +
            "   例:10 个缺陷、并发 " + conc + " → 分批进行(如并发 3 则 3+3+3+1 四批)。严格不要一次派出超过 " + conc + " 个。\n" +
            "2. **每个子智能体只负责一个缺陷**,给它的任务描述务必带上该缺陷的 BUG-编号 + 内部 identifier,并要求它:\n" +
            "   - 调用 query_bug_details 工具,bug_id 传该缺陷的 identifier,拉取 基础信息 + 描述 + 所有评论;\n" +
            "   - 默认仅依据【标题 + 描述 + 评论文本】判断是否「明确」:\n" +
            "       · 明确(clear):有清晰现象/复现路径/期望结果,足以直接动手定位;\n" +
            "       · 不明确(unclear):缺关键信息,在 reason 写为什么不明确,在 missing 列缺什么\n" +
            "         (如:复现步骤 / 期望结果 / 涉及账号或权限 / 截图 / 接口或页面 等);\n" +
            "   - 仅当文本不足以判断时,才用 Read 查看 query_bug_details 返回的截图本地路径;能用文本判断就不要读图;\n" +
            "   - 只读分析:禁止改任何文件、禁止执行命令;\n" +
            "   - 把该缺陷的结论以 JSON 返回:{ \"serialNumber\":\"...\",\"identifier\":\"...\",\"subject\":\"...\"," +
            "\"clarity\":\"clear|unclear\",\"reason\":\"...\",\"missing\":[\"...\"] }。\n" +
            "3. 收齐【所有】子智能体的结论后,你(协调者)再做【关联分组】:把缺陷按关联性归组,每个缺陷**只归入一个最贴切的主组**,\n" +
            "   dimension ∈ page(同一页面)/ api(同一接口)/ feature(同一功能点或同一处根因);每组给 label(如「整改单列表/筛选页」)、\n" +
            "   members(该组的 serialNumber 列表)、rootCauseGuess(一句话疑似共同根因)。\n" +
            "\n" +
            "【降级】若当前环境无法使用 Task 子智能体工具,则退化为你自己逐个缺陷顺序分析(query_bug_details + 文本优先,判定与分组要求同上)。\n" +
            "\n" +
            "【约束】整个过程只读:禁止 Write/Edit、禁止执行 Bash 命令、禁止修改任何文件。\n" +
            "\n" +
            "【最终输出】所有缺陷分析 + 分组完成后,**只输出一个 ```json 围栏代码块**,不要任何额外解释文字,schema 如下:\n" +
            "```json\n" +
            "{\n" +
            "  \"bugs\": [\n" +
            "    { \"serialNumber\": \"AAXE-1060\", \"identifier\": \"<id>\", \"subject\": \"...\",\n" +
            "      \"clarity\": \"clear\" | \"unclear\",\n" +
            "      \"reason\": \"明确依据 / 不明确原因\",\n" +
            "      \"missing\": [\"复现步骤\", \"期望结果\"] }     // 仅 unclear;clear 可省略或空数组\n" +
            "  ],\n" +
            "  \"groups\": [\n" +
            "    { \"dimension\": \"page\" | \"api\" | \"feature\",\n" +
            "      \"label\": \"整改单列表/筛选页\",\n" +
            "      \"members\": [\"AAXE-1060\", \"AAXE-1052\"],     // serialNumber\n" +
            "      \"rootCauseGuess\": \"整改单列表数据源/筛选条件拼装\" }\n" +
            "  ]\n" +
            "}\n" +
            "```\n";
    }

    private static JsonObject asObject(JsonElement el) {
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
    }

    /** 安全取字符串:缺键/null 返回 ""；数字也兜成字符串(serialNumber 可能是数字)。 */
    private static String optString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return "";
        }
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return o.get(key).toString();
        }
    }
}
