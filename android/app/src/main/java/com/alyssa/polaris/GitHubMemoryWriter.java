package com.alyssa.polaris;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Conditional, serialized writes to the same memory paths exposed by the reader. */
final class GitHubMemoryWriter {
    private final MemorySourceSession session;
    GitHubMemoryWriter(MemorySourceSession session) { this.session = session; }

    static String digest(byte[] bytes, String algorithm) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance(algorithm).digest(bytes)) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    static String blob(byte[] bytes, int length) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(length == 64 ? "SHA-256" : "SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII)); digest.update(bytes);
        StringBuilder result = new StringBuilder(); for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return result.toString();
    }
    private static boolean validSha(String sha) { return sha != null && sha.matches("(?i)(?:[a-f0-9]{40}|[a-f0-9]{64})"); }
    private static boolean matches(JSONObject file, JSONObject receipt) {
        if (file == null || receipt == null) return false;
        String sha = file.optString("blob_sha");
        return sha.equalsIgnoreCase(receipt.optString(sha.length() == 64 ? "target_sha256" : "target_sha1"));
    }
    private JSONObject readOrMissing(String path) throws Exception {
        try { return session.githubFile(path); }
        catch (MemorySourceHttp.Failure failure) { if (failure.status == 404) return null; throw failure; }
    }
    private JSONObject result(String path, String status, boolean verified, Object createdCommit, String blobSha, String commit, String message) throws Exception {
        String repo = session.endpoint.substring("https://api.github.com/repos/".length());
        JSONObject result = new JSONObject().put("provider", "github").put("path", path).put("status", status).put("verified", verified)
            .put("created_commit", createdCommit).put("blob_sha", blobSha == null ? JSONObject.NULL : blobSha)
            .put("commit_sha", commit == null ? JSONObject.NULL : commit).put("message", message);
        if (validSha(commit)) result.put("commit_url", "https://github.com/" + repo + "/commit/" + commit)
            .put("file_url", "https://github.com/" + repo + "/blob/" + commit + "/" + MemorySourcePolicy.file(path));
        return result;
    }
    private void cacheResult(JSONObject receipt, String commit, boolean verified) {
        try { receipt.put("commit_sha", commit == null ? JSONObject.NULL : commit).put("verified", verified); session.saveWriteReceipt(receipt); }
        catch (Exception ignored) { /* The pre-submit receipt already protects against duplicate writes. */ }
    }
    private JSONObject reconcile(String path, JSONObject receipt) throws Exception {
        try {
            JSONObject current = readOrMissing(path);
            if (matches(current, receipt)) {
                cacheResult(receipt, null, true);
                return result(path, "verified_without_commit_receipt", true, JSONObject.NULL, current.getString("blob_sha"), null,
                    "已回读确认目标内容，但未取得本次提交回执；不声称创建了新 commit，也不重复提交。");
            }
        } catch (Exception ignored) { /* An interrupted PUT has an unknown outcome until a later read. */ }
        return result(path, "outcome_unknown", false, JSONObject.NULL, null, null,
            "GitHub 记忆提交结果尚未确认。请先重新读取该文件核对，不要盲目重复追加或声称保存成功。");
    }
    JSONObject write(JSONObject args, boolean userWriteRequested) throws Exception {
        if (!userWriteRequested) throw new Exception("GitHub 记忆写回需要当前用户明确要求记住、更正或写入记忆。");
        MemorySourcePolicy.validateWrite(args);
        synchronized (session.writeLock) {
            session.requireWritingEnabled();
            String path = args.getString("path"), operation = args.getString("operation"), expected = args.getString("expected_sha");
            String content = args.getString("content"), oldText = args.optString("old_text", "");
            String message = args.optString("commit_message", "Update continuity memory via Polaris");
            String fingerprint = digest(MemorySourcePolicy.utf8(new JSONArray().put(path).put(operation).put(expected).put(content).put(oldText).put(message).toString()), "SHA-256");
            JSONObject current = readOrMissing(path), prior = session.writeReceipt(fingerprint);
            if (matches(current, prior)) return result(path, "already_current", true, false, current.getString("blob_sha"),
                prior.optBoolean("verified") && validSha(prior.optString("commit_sha")) ? prior.getString("commit_sha") : null,
                "文件现状与这次写回请求一致，无需再次提交。");
            if (prior != null && prior.optBoolean("verified")) return result(path, "conflict", false, false,
                current == null ? null : current.optString("blob_sha"), null,
                "此前同一写回已验证，但文件现状后来改变。本次没有重放旧提交，请重新读取并确认当前需要的更正。");
            boolean create = "create".equals(operation);
            if (create ? current != null : current == null || !expected.equalsIgnoreCase(current.optString("blob_sha"))) {
                return result(path, "conflict", false, false, current == null ? null : current.optString("blob_sha"), null,
                    "GitHub 记忆文件状态或版本已变化，本次没有提交。请重新读取后，只重新应用用户要求的新增或更正。");
            }
            String next = content;
            if (!create) {
                String previous = current.getString("text");
                if ("append".equals(operation)) next = previous + (previous.isEmpty() || previous.endsWith("\n") ? "" : "\n") + content;
                else {
                    int at = previous.indexOf(oldText);
                    if (at < 0 || previous.indexOf(oldText, at + 1) >= 0) return result(path, "patch_mismatch", false, false, current.getString("blob_sha"), null,
                        "GitHub 记忆更正原文未唯一匹配，本次没有提交。请重新读取并选择准确、唯一的原文片段。");
                    next = previous.substring(0, at) + content + previous.substring(at + oldText.length());
                }
                if (next.equals(previous)) return result(path, "already_current", true, false, current.getString("blob_sha"), null, "目标内容没有变化，无需提交。");
            }
            byte[] bytes = MemorySourcePolicy.utf8(next);
            if (bytes.length > MemorySourcePolicy.WRITE_LIMIT) throw new Exception("GitHub 记忆写回后文件过大，未提交；请使用较小的连续性文档。");
            JSONObject receipt = new JSONObject().put("fingerprint", fingerprint).put("path", path).put("expected_sha", expected)
                .put("target_sha1", blob(bytes, 40)).put("target_sha256", blob(bytes, 64)).put("verified", false);
            try { session.saveWriteReceipt(receipt); }
            catch (Exception storage) { throw new Exception("安全写回状态未能保存，尚未发出 GitHub 提交。"); }
            JSONObject body = new JSONObject().put("message", message).put("content", Base64.getEncoder().encodeToString(bytes));
            if (!create) body.put("sha", expected);
            MemorySourceHttp.Result response;
            try { response = session.sourceRequest(MemorySourcePolicy.contentUrl(session.endpoint, path, null), "PUT", body.toString(), new HashMap<>()); }
            catch (Exception interrupted) { return reconcile(path, receipt); }
            if (response.status >= 500 || response.status == 408) return reconcile(path, receipt);
            if (response.status == 403 || response.status == 404) return result(path, "permission_required", false, false, null, null,
                "GitHub 记忆写回未获权限。请将原 GitHub App 的 Contents 改为 Read and write，批准安装权限更新，并重新设备登录。");
            if (response.status < 200 || response.status >= 300) return result(path, response.status == 409 ? "conflict" : "rejected", false, false, null, null,
                "GitHub 记忆提交未获接受（HTTP " + response.status + "）。请重新读取并检查分支规则；本次没有自动重试提交。");
            String commit = null, after = null;
            try {
                JSONObject accepted = new JSONObject(response.body), file = accepted.getJSONObject("content");
                commit = accepted.getJSONObject("commit").getString("sha"); after = file.getString("sha");
                if (!validSha(commit) || !validSha(after) || !path.equals(file.optString("path")) || !after.equalsIgnoreCase(blob(bytes, after.length()))) return reconcile(path, receipt);
            } catch (Exception malformed) { return reconcile(path, receipt); }
            cacheResult(receipt, commit, false);
            try {
                JSONObject verified = session.githubFile(path, commit);
                if (after.equalsIgnoreCase(verified.optString("blob_sha")) && next.equals(verified.optString("text"))) {
                    cacheResult(receipt, commit, true);
                    return result(path, "committed", true, true, after, commit, "已提交，并按此 commit 回读确认记忆内容。");
                }
            } catch (Exception readback) { /* Preserve a known submitted commit without claiming successful verification. */ }
            return result(path, "submitted_unverified", false, true, after, commit,
                "GitHub 已接受提交，但回读尚未确认。请通过该 commit 检查内容，不要重复追加，也不要报告已完成验证。");
        }
    }
}
