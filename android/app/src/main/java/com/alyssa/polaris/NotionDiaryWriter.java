package com.alyssa.polaris;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Append-only diary adapter. Provider mutations are never exposed to the model. */
final class NotionDiaryWriter {
    private final MemorySourceSession session;
    private final Map<String, String> headers;
    private final long generation;
    NotionDiaryWriter(MemorySourceSession session, Map<String, String> headers) {
        this.session = session; this.headers = headers; this.generation = session.generation();
    }
    static JSONObject tool() throws Exception {
        JSONObject properties = new JSONObject();
        for (String field : new String[]{"date", "title", "shared_memory", "private_diary"}) properties.put(field, new JSONObject().put("type", "string"));
        properties.put("memory_anchors", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string")).put("minItems", 3).put("maxItems", 8));
        return new JSONObject().put("name", MemorySourcePolicy.NOTION_DIARY)
            .put("description", "仅在用户明确要求写/补日记时使用。先读原模板与当前聊天事实。date 为 Asia/Shanghai 的 YYYY-MM-DD；title 为一句当天小标题。shared_memory 写本轮实际共同经历；memory_anchors 留 3–8 条真实事实；private_diary 为当前窗口第一人称私记，两层不要互抄。只写本次新增片段：同日自动追加原页，旧文与私记原样保留；异日分开。补写须在正文注明补写日期和依据。不接收任意页面或删除操作。只有 verified:true 才能说已保存；未核验时不得重复提交或声称完成。")
            .put("inputSchema", new JSONObject().put("type", "object").put("properties", properties).put("required", new JSONArray(new String[]{"date", "title", "shared_memory", "memory_anchors", "private_diary"})).put("additionalProperties", false));
    }
    private void permitted(boolean userRequested) throws Exception {
        if (!userRequested || !session.status().optBoolean("connected") || !session.diarySettings().optBoolean("enabled") || generation != session.generation())
            throw new Exception("Notion 日记写回未开启或本轮用户没有明确要求写日记。");
    }
    private JSONObject rpc(String method, JSONObject params) throws Exception {
        JSONObject request = new JSONObject().put("jsonrpc", "2.0").put("id", "diary-" + UUID.randomUUID()).put("method", method).put("params", params);
        MemorySourceHttp.Result response = session.authorized(session.endpoint, "POST", request.toString(), headers);
        for (Map.Entry<String, String> entry : response.headers.entrySet()) headers.put(entry.getKey(), entry.getValue());
        JSONObject envelope = new JSONObject(response.body);
        if (envelope.has("error") || !envelope.has("result")) throw new Exception("Notion 日记工具未返回成功结果，请先核验原页面。");
        JSONObject result = envelope.getJSONObject("result");
        if (result.optBoolean("isError")) throw new Exception("Notion 日记工具返回错误，请先核验原页面；未自动重发。");
        return result;
    }
    private JSONObject call(String name, JSONObject args) throws Exception {
        return rpc("tools/call", new JSONObject().put("name", name).put("arguments", args));
    }
    static String resultText(JSONObject result) {
        JSONObject structured = result.optJSONObject("structuredContent");
        if (structured != null && structured.opt("text") instanceof String) return structured.optString("text");
        StringBuilder text = new StringBuilder(); JSONArray content = result.optJSONArray("content");
        if (content != null) for (int i = 0; i < content.length(); i++) {
            JSONObject entry = content.optJSONObject(i);
            if (entry != null && "text".equals(entry.optString("type"))) {
                String value = entry.optString("text");
                try { JSONObject json = new JSONObject(value); if (json.opt("text") instanceof String) value = json.getString("text"); } catch (Exception ignored) { }
                text.append(value).append('\n');
            }
        }
        return text.toString();
    }
    static final class Page {
        final String id, title, body, parent;
        Page(String id, String title, String body, String parent) { this.id = id; this.title = title; this.body = body; this.parent = parent; }
    }
    static Page parsePage(String text, String expectedId) throws Exception {
        Matcher page = Pattern.compile("<page\\s[^>]*url=\"([^\"]+)\"[^>]*>").matcher(text);
        Matcher body = Pattern.compile("<content>\\s*([\\s\\S]*?)\\s*</content>").matcher(text);
        Matcher properties = Pattern.compile("<properties>\\s*([\\s\\S]*?)\\s*</properties>").matcher(text);
        if (!page.find() || !MemorySourcePolicy.page(page.group(1)).equals(expectedId) || !body.find() || !properties.find()
            || text.contains("<unknown") || text.matches("(?s).*<(?:truncated|unloaded|inaccessible)[ >].*")) throw new Exception("Notion 页面内容或父级未能完整核验，未再次写入。");
        String parent = ""; Matcher ancestors = Pattern.compile("<ancestor-path>([\\s\\S]*?)</ancestor-path>").matcher(text);
        if (!ancestors.find()) throw new Exception("Notion 页面缺少父级信息，未写入。");
        Matcher parents = Pattern.compile("<parent-page\\s[^>]*url=\"([^\"]+)\"[^>]*/>").matcher(ancestors.group(1));
        while (parents.find()) parent = MemorySourcePolicy.page(parents.group(1));
        return new Page(expectedId, new JSONObject(properties.group(1)).getString("title"), body.group(1), parent);
    }
    private String fetchText(String id, JSONObject fetchSchema) throws Exception {
        JSONObject properties = fetchSchema.getJSONObject("properties");
        String field = properties.has("id") ? "id" : properties.has("url") ? "url" : "";
        if (field.isEmpty()) throw new Exception("Notion 读取参数版本暂不支持，未写入。");
        return resultText(call("notion-fetch", new JSONObject().put(field, "url".equals(field) && !id.startsWith("notion://") ? "https://www.notion.so/" + id : id)));
    }
    private Page fetch(String id, JSONObject fetchSchema) throws Exception {
        return parsePage(fetchText(id, fetchSchema), id);
    }
    static Map<String, String> dates(Page parent, String date) throws Exception {
        Map<String, String> found = new LinkedHashMap<>();
        Matcher child = Pattern.compile("<page\\s[^>]*url=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</page>").matcher(parent.body);
        while (child.find()) if (child.group(2).trim().startsWith(date + "｜") || child.group(2).trim().startsWith(date + " |")) found.put(MemorySourcePolicy.page(child.group(1)), child.group(2).trim());
        if (found.size() > 1) throw new Exception("同一天已有多篇日记，请先核对原目录；未选择或覆盖任何一篇。");
        return found;
    }
    private static String nonempty(JSONObject args, String key) throws Exception {
        if (!(args.opt(key) instanceof String) || args.getString(key).trim().isEmpty()) throw new Exception("日记两层、日期和小标题都要填写完整。");
        String value = args.getString(key).trim();
        if (value.contains("<page") || value.contains("<database") || value.contains("<unknown") || value.contains("</content>")) throw new Exception("日记正文不支持嵌入页面、数据库或不完整内容。");
        return value;
    }
    static String fragment(JSONObject args) throws Exception {
        String shared = nonempty(args, "shared_memory"), personal = nonempty(args, "private_diary");
        JSONArray anchors = args.getJSONArray("memory_anchors");
        if (anchors.length() < 3 || anchors.length() > 8) throw new Exception("日记需要 3–8 条记忆锚点。");
        StringBuilder body = new StringBuilder("## 我们共同经历的这一天\n\n").append(shared).append("\n\n### 记忆锚点\n\n");
        for (int i = 0; i < anchors.length(); i++) {
            if (!(anchors.get(i) instanceof String) || anchors.getString(i).trim().isEmpty() || anchors.getString(i).contains("\n")) throw new Exception("每条记忆锚点应是一条完整短句。");
            body.append("- ").append(anchors.getString(i).trim()).append('\n');
        }
        body.append("\n## 阿昼自己的日记\n\n").append(personal);
        String value = body.toString();
        if (Pattern.compile("</?[a-zA-Z][^>]*>").matcher(value).find()) throw new Exception("日记片段请使用普通 Markdown，不嵌入 XML 或 HTML 页面标签。");
        if (MemorySourcePolicy.utf8(value).length > 48 * 1024) throw new Exception("单次日记片段过长，请分段补写。");
        return value;
    }
    static String normalized(String value) {
        return value.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replaceAll("(?m)^\\s{0,3}(?:#{1,6}\\s+|[-*+]\\s+)", "").replace("**", "").replace("__", "").replaceAll("\\s+", "").trim();
    }
    private static String hash(String value) throws Exception {
        StringBuilder out = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
    private static JSONObject schema(JSONObject tool) throws Exception {
        JSONObject schema = tool.getJSONObject("inputSchema"), properties = schema.getJSONObject("properties");
        return properties.has("data") ? properties.getJSONObject("data") : schema;
    }
    private static JSONObject wrap(JSONObject tool, JSONObject args) throws Exception {
        return tool.getJSONObject("inputSchema").getJSONObject("properties").has("data") ? new JSONObject().put("data", args) : args;
    }
    private static boolean enumHas(JSONObject property, String value) {
        JSONArray values = property == null ? null : property.optJSONArray("enum");
        if (values == null) return false;
        for (int i = 0; i < values.length(); i++) if (value.equals(values.optString(i))) return true;
        return false;
    }
    static JSONObject appendArgs(JSONObject tool, Page current, String fragment) throws Exception {
        JSONObject properties = schema(tool).getJSONObject("properties"), args = new JSONObject().put("page_id", current.id);
        if (enumHas(properties.optJSONObject("command"), "insert_content") && properties.has("position") && properties.has("content")) {
            args.put("command", "insert_content").put("position", new JSONObject().put("type", "end")).put("content", fragment);
        } else if (enumHas(properties.optJSONObject("command"), "insert_content_after") && properties.has("selection_with_ellipsis") && properties.has("new_str")) {
            String[] lines = current.body.trim().split("\n"); String last = lines[lines.length - 1].trim();
            if (last.isEmpty() || last.contains("…") || last.contains("...") || current.body.indexOf(last) != current.body.lastIndexOf(last)) throw new Exception("日记末尾定位不唯一，未补写；请升级 Notion 工具后重试。");
            args.put("command", "insert_content_after").put("selection_with_ellipsis", last).put("new_str", "\n\n" + fragment);
        } else throw new Exception("Notion 当前工具版本没有支持的末尾追加操作，未修改原文。");
        return wrap(tool, args);
    }
    JSONObject write(JSONObject args, boolean userRequested) throws Exception {
        synchronized (session.writeLock) {
            permitted(userRequested);
            String date = nonempty(args, "date"), title = nonempty(args, "title");
            if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !LocalDate.parse(date).toString().equals(date) || title.contains("\n") || title.length() > 140) throw new Exception("日记日期应为 YYYY-MM-DD，小标题应为一行短句。");
            JSONObject settings = session.diarySettings(); String parentId = settings.getString("parent"), templateId = settings.getString("template");
            String fragment = fragment(args), fingerprint = hash(parentId + "|" + date + "|" + title + "|" + fragment);
            JSONObject previous = session.diaryReceipt(fingerprint);
            if (previous == null && session.pendingDiaryDate(parentId, date)) throw new Exception("当天有一份日记尚未核验，请先用原请求核验；未提交新的片段，避免重复。");
            JSONArray tools = rpc("tools/list", new JSONObject()).getJSONArray("tools"); Map<String, JSONObject> catalog = new HashMap<>();
            for (int i = 0; i < tools.length(); i++) catalog.put(tools.getJSONObject(i).optString("name"), tools.getJSONObject(i));
            if (!catalog.containsKey("notion-fetch")) throw new Exception("Notion 日记缺少读取工具，未写入。");
            JSONObject fetchSchema = schema(catalog.get("notion-fetch"));
            if (!fetchText(MemorySourcePolicy.NOTION_MARKDOWN, fetchSchema).contains("Markdown")) throw new Exception("Notion Markdown 规范未读取成功，未写入。");
            Page template = fetch(templateId, fetchSchema);
            if (!template.body.contains("我们共同经历的这一天") || !template.body.contains("阿昼自己的日记") || !template.body.contains("记忆锚点")) throw new Exception("原模板没有识别到双层日记结构，未写入。");
            Page parent = fetch(parentId, fetchSchema); Map<String, String> sameDay = dates(parent, date);
            String pageId = previous != null ? previous.optString("page_id") : sameDay.isEmpty() ? "" : sameDay.keySet().iterator().next();
            if (previous != null && pageId.isEmpty() && !sameDay.isEmpty()) pageId = sameDay.keySet().iterator().next();
            String stamp = previous == null ? java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).toString() : previous.getString("stamp");
            String staged = "阶段补记｜" + stamp + "\n\n" + fragment;
            Page current = pageId.isEmpty() ? null : fetch(pageId, fetchSchema);
            if (current != null && (!sameDay.containsKey(current.id) || (!current.parent.isEmpty() && !parentId.equals(current.parent)) || !current.title.startsWith(date + "｜"))) throw new Exception("目标日记不在选定的当天北极星目录下，未写入。");
            if (previous != null) {
                String normalizedBody = current == null ? "" : normalized(current.body);
                int originalLength = previous.optInt("original_length");
                boolean originalPreserved = originalLength == 0 || (normalizedBody.length() >= originalLength && hash(normalizedBody.substring(0, originalLength)).equals(previous.optString("original_hash")));
                if (current != null && originalPreserved && normalizedBody.contains(normalized(staged))) {
                    previous.put("page_id", current.id).put("verified", true); session.saveDiaryReceipt(previous); return receipt(previous, current, "verified_existing");
                }
                previous.put("verified", false);
                return receipt(previous, current, "pending_readback_do_not_resubmit");
            }
            JSONObject mutation; String name;
            if (current == null) {
                name = "notion-create-pages"; JSONObject tool = catalog.get(name);
                if (tool == null || !schema(tool).getJSONObject("properties").has("pages") || !schema(tool).getJSONObject("properties").has("parent")) throw new Exception("Notion 尚未开放支持的日记新建工具。");
                mutation = wrap(tool, new JSONObject().put("parent", new JSONObject().put("page_id", parentId))
                    .put("pages", new JSONArray().put(new JSONObject().put("properties", new JSONObject().put("title", date + "｜" + title)).put("content", staged))));
            } else {
                name = "notion-update-page"; JSONObject tool = catalog.get(name);
                if (tool == null) throw new Exception("Notion 尚未开放日记追加工具。");
                mutation = appendArgs(tool, current, "\n\n---\n\n" + staged);
            }
            JSONObject record = new JSONObject().put("fingerprint", fingerprint).put("parent", parentId).put("date", date).put("stamp", stamp).put("page_id", pageId).put("verified", false);
            String original = current == null ? "" : normalized(current.body);
            record.put("original_length", original.length()).put("original_hash", hash(original));
            permitted(userRequested);
            JSONObject latest = session.diarySettings();
            if (!parentId.equals(latest.optString("parent")) || !templateId.equals(latest.optString("template"))) throw new Exception("日记目录设置已改变，请重新读取后再写。");
            session.saveDiaryReceipt(record); // Persist before the only mutation; never auto-replay an uncertain POST.
            try {
                permitted(userRequested); call(name, mutation);
                if (pageId.isEmpty()) {
                    Map<String, String> created = dates(fetch(parentId, fetchSchema), date);
                    if (created.isEmpty()) return receipt(record, null, "submitted_pending_readback");
                    pageId = created.keySet().iterator().next(); record.put("page_id", pageId); session.saveDiaryReceipt(record);
                }
                Page after = fetch(pageId, fetchSchema);
                Map<String, String> afterChildren = dates(fetch(parentId, fetchSchema), date);
                boolean verified = afterChildren.containsKey(after.id) && (after.parent.isEmpty() || parentId.equals(after.parent)) && after.title.startsWith(date + "｜") && normalized(after.body).contains(normalized(staged))
                    && (current == null || normalized(after.body).contains(normalized(current.body)));
                record.put("verified", verified); session.saveDiaryReceipt(record);
                return receipt(record, after, verified ? "verified" : "submitted_pending_readback");
            } catch (Exception uncertain) {
                return receipt(record, null, "outcome_unknown_check_original_do_not_resubmit");
            }
        }
    }
    private static JSONObject receipt(JSONObject record, Page page, String state) throws Exception {
        String id = page == null ? record.optString("page_id") : page.id;
        return new JSONObject().put("provider", "notion").put("verified", page != null && record.optBoolean("verified")).put("state", state)
            .put("date", record.getString("date")).put("page_url", id.isEmpty() ? "" : "https://www.notion.so/" + id)
            .put("title", page == null ? "" : page.title).put("detail", "只追加本次双层片段，旧文不覆盖；未核验时请先读取原目录和当天页面，不要重复提交。Notion 不提供 GitHub 式 commit/SHA 原子版本保证。");
    }
}
