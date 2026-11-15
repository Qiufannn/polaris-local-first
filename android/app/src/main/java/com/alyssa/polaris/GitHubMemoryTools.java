package com.alyssa.polaris;
import org.json.JSONArray;
import org.json.JSONObject;

final class GitHubMemoryTools {
    private static JSONObject string(String description) throws Exception { return new JSONObject().put("type", "string").put("description", description); }
    static JSONObject catalog(boolean writing) throws Exception {
        JSONArray tools = new JSONArray().put(new JSONObject().put("name", "github_memory_read")
            .put("description", "读取 GitHub 主记忆。明确要求接续时先读 continuity/identity.md，再读 README.md、continuity/README.md、continuity/index.md，随后按需读取；普通闲聊不自动扫描。返回真实路径、文件 SHA 与正文。未开放 github_memory_write 时仅可读取，不可把本地协作者记忆当作 GitHub 写回成功。")
            .put("inputSchema", new JSONObject().put("type", "object").put("required", new JSONArray().put("path"))
                .put("properties", new JSONObject().put("path", string("根 README.md 或 continuity/ 下的 Markdown 路径"))).put("additionalProperties", false)));
        if (writing) tools.put(new JSONObject().put("name", "github_memory_write")
            .put("description", "仅当当前用户明确说记住、更正或写入 GitHub 主记忆时使用。追加和更正先读目标文件，使用实际 SHA；只追加新事实或替换唯一原文，保留其他事实和索引。新文档用 create/new，由工具预检不存在，无须先读取不存在的文件；必要时另行维护索引。不上传每轮聊天原文，不把推测写成事实。以 verified/status/commit_sha 为准；冲突先重读，未确认就如实说明，不盲目重复追加。")
            .put("inputSchema", new JSONObject().put("type", "object").put("required", new JSONArray().put("path").put("operation").put("expected_sha").put("content"))
                .put("properties", new JSONObject().put("path", string("允许的主记忆 Markdown 路径"))
                    .put("operation", new JSONObject().put("type", "string").put("enum", new JSONArray().put("create").put("append").put("replace_text")))
                    .put("expected_sha", string("追加/更正填刚读取的 blob_sha；新建填 new"))
                    .put("content", string("新文档正文、要追加的新段落，或更正后的新片段；不是整篇旧聊天"))
                    .put("old_text", string("replace_text 时必填：刚读取文件中唯一匹配的原文片段"))
                    .put("commit_message", string("可选，简短单行提交说明"))).put("additionalProperties", false)));
        return new JSONObject().put("tools", tools);
    }
}
