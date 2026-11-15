# GitHub 主记忆：新增、追加和更正

GitHub 是主要记忆，Notion 是日记及补充。本人已报告两处原有内容均读取成功。本次为原 Android GitHub 连接补写回能力，不迁移或重建记忆库；原 Notion 连接保持读取功能，Notion 写日记另行实现。

## 手机启用

1. 覆盖安装含此功能的个人签名更新包。
2. 在 GitHub Settings → Developer settings → GitHub Apps 打开原 App。Permissions & events → Repository permissions → Contents 改为 **Read and write**，保存。无需新建 App、Client Secret、私钥或 PAT。
3. 在 Settings → Applications → Installed GitHub Apps 批准此 App 的权限更新（如有 Review request）；安装范围仍仅选择目标记忆仓库。
4. 在原 Polaris GitHub 主记忆服务卡断开本机连接，再完成一次设备登录，以取得更新后的授权。普通配置中的仓库地址和公开 Client ID 留用。
5. 开启 **允许 GitHub 新增和更正记忆**，同步后应出现 `github_memory_read` 和 `github_memory_write`，保存。该开关是本机写入许可，不表示服务商写权限已验证；实际提交才能验收。

## 写入触发和工具

当前真实用户明确说“记住”“更正”或要求写入 GitHub 记忆时，协作者可调用 `github_memory_write`。普通闲聊不逐轮上传。短句“继续”“重试”可沿用紧邻的明确写回要求；新的无关用户消息、否定指令和后台触发不会借用旧请求。

客户端使用真实用户消息做一层粗粒度意图检查，排除代码围栏、引用行及否定表达；它不是完美的自然语言权限证明，模型仍须只保存用户实际要求的事实。允许写入的标志通过调用选项传入原生桥，不取自模型的工具参数。原生层独立检查此标志及本机开关。调用写工具时不依靠失败后的旧目录缓存来获权。

工具仅支持根 `README.md` 和 `continuity/` 下 Markdown：

| operation | 版本要求与行为 |
| --- | --- |
| create | `expected_sha: "new"`；原生预检文件不存在，再创建。 |
| append | 先读取实际 `blob_sha`，填入 `expected_sha`；仅在旧文件末尾追加新段落。 |
| replace_text | 使用刚读取的 SHA；`old_text` 必须唯一匹配，替换成 `content`，保留其余内容。 |

不暴露删除、任意分支、其他仓库、GitHub Actions 或工作流写入。内容与结果文件最多 128 KiB UTF-8；空内容和缺失版本拒绝提交。新增文件需要按用途维护索引，测试文档不必进入正式接续索引。

## 版本、回读和中断

- 原生先读当前文件，核对 SHA；GitHub PUT 再带同一 SHA，阻止并发覆盖。冲突不自动强行重放，必须重新读当前内容。
- 每个连接在本机串行提交，提前在加密 noBackup 凭证库保存有界的写回指纹与目标 blob 哈希。最多保留 12 条元数据，不重复保存正文，不进入普通设置导出。
- 已验证的同一请求若又出现，先核对当前内容，匹配则不创建第二个 commit；若文件后来变化或删除，则停止重放旧提交。
- 成功的 GitHub 返回必须给出匹配的文件 SHA 与 commit SHA。随后以 `GET contents?ref=<commit SHA>` 回读该提交的文件，校验 Git blob 哈希、大小、UTF-8 和目标内容。
- PUT 断网、HTTP 408/5xx 或回执格式异常时，先读文件现状，不盲目重发 PUT。若目标内容匹配但 commit 回执未知，只报告“内容已读回匹配，提交回执未知”，不编造 commit。
- 已接受提交但回读未确认时保留 commit 链接和 `submitted_unverified`；此类未验证结果在聊天执行层作为失败行动保留，隐藏常规记录时也可查看。

返回字段包含 `status`、`verified`、`created_commit`、`blob_sha`、`commit_sha` 和可验证的提交链接。`created_commit: null` 表示提交是否发生尚不能确定；只有真实提交回执和按版本回读均成功才返回 `status: committed`。无变更、已存在或中断后内容匹配不等于本次创建新 commit。

## 验收和边界

新增 15 项原生写回测试，完整原生单元测试 66 项通过：本机开关和用户请求、创建及版本回读、追加保留与跨会话去重、更正范围、版本冲突、不唯一匹配、网络与 500 中断、未知结果、不匹配回读、错误回执、权限失效、提交前磁盘失败、路径/来源边界、旧请求不复活被删除文件。相关 Web 定向 37 项通过，涵盖真实用户请求、继续/否定/后台边界、调用选项隔离、未验证行动显错与原读取路径。TypeScript 与生产 Web 构建通过。

合成手机 UI 验证通过：写入默认关闭；开启仅同步两个工具，不提交任何记忆；重开后开关恢复；读取仍可验证；关闭后只保留读取工具。原真实记忆未在开发或测试中改动。

完整 CI、个人签名包与本人实际手机写入回执待补记。可由本人明确要求创建 `continuity/polaris-write-check.md`，写一行中性的测试文字，验收真实 commit、文件 SHA 和按版本回读，再测试唯一片段更正；不要把自动测试说成本人的实际写入。
