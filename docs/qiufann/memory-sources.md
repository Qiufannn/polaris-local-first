# GitHub 主记忆与 Notion 日记：独立 Android 连接

> 本文保留最初只读接通阶段的报告。当前 GitHub 读取/新增/更正已由本人手机验收，见 [写回与回读](github-memory-write.md)；Notion 读取已验收，[北极星独立日记写回](notion-diary-write.md)本次开发，手机写入仍待验收。Chat / Work / Polaris 分别保留私记，最新顺序见 [Aru 对照](aru-feature-audit.md)。

> 第 12 版本人验收发现“GitHub 网页成功、Polaris 仍失败”，以及本地目录被误报为连接成功。当前修复与新版证据见 [设备授权轮询恢复](github-device-recovery.md)。下方第 12 版回执保留作历史构建证据，不能当作本人读取成功。

## 当前范围

按本人最新决定，GitHub 是主要连续性记忆，Notion 是日记与补充资料，小家作为展示与可选外挂。此优先级取代旧迁入计划中的“小家优先”。本次交付两个独立登录入口和真实内容验证按钮；新增、更正记忆与日记写入尚未实现，不把读取成功当作双向同步完成。

入口：协作者 → 请求 → MCP → 新增服务 → 连接方式。先保存配置，再重新打开服务连接。ChatGPT 订阅授权仅供模型使用；GitHub、Notion 分别由本人在各自官方网页授权，不继承 ChatGPT 已连接应用的凭证。

## GitHub 主记忆

- 连接方式：`GitHub 主记忆（设备登录，先验证读取）`。
- 地址：`https://api.github.com/repos/OWNER/REPOSITORY`，必须指向本人选择安装的记忆仓库。
- `公开 Client ID（Iv 开头）` 填本人 GitHub App 的公开 Client ID；这是公共客户端标识，不是 App ID、Client Secret、PAT 或私钥。程序会拒绝常见 token 前缀，普通 MCP 导出也不会保留误填的 token。
- GitHub App 需开启 Device Flow，安装范围仅选目标记忆仓库，Contents 先设 Read-only。无需生成客户端密钥或安装私钥。
- “连接 GitHub”取得短期用户验证码；“复制验证码并打开 GitHub”优先使用 Via 打开固定的 `https://github.com/login/device`。本人在 GitHub 批准，返回 Polaris 后完成原生轮询。
- “验证真实内容并同步工具”读取 `continuity/identity.md`，显示文件路径、Git blob SHA 与开头预览。客户端计算 Git blob 哈希并核对大小，不跟随文件返回的 `download_url`。

虚拟 MCP 工具 `github_memory_read` 使用 GitHub Contents API，可读取根 `README.md` 与 `continuity/` 下 Markdown，拒绝越界、编码穿越与其他类型。目录路径不是一键全库下载。`read_at` 仅为本机读取时间，不是 GitHub 服务端审计凭据。

GitHub App Device Flow 使用公开 Client ID、`offline_access`、原生设备码轮询及刷新；不请求经典 OAuth App 的宽泛 `repo` scope。设备码不返回网页层，用户验证码不写入普通持久化配置。轮询遵守服务端间隔及 `slow_down`，取消后不提交晚到的授权结果。

## Notion 日记与补充

- 连接方式：`Notion 日记（官方网页登录）`，自动使用 `https://mcp.notion.com/mcp`。
- `日记入口页面链接` 填原日记本入口，保留原页面结构；私人页面 ID 不硬编码进公开仓库。
- 保存、重开服务，点“连接 Notion”，在官方授权页面选择正确工作区及页面，再回到 Polaris。
- 点“验证真实内容并同步工具”，调用官方 `notion-fetch` 并显示返回内容开头。本人需核对标题与内容属于原日记本；未执行本人真实授权前不能说已连接。

使用官方 OAuth 元数据公布的公共客户端动态注册、系统浏览器、PKCE S256、state、回环回调、精确 issuer 与资源校验；不索取 integration secret 或 Client Secret。当前只暴露 `notion-search`、`notion-fetch`，原生层也拒绝写工具。提供方的 `default` 授权范围不等于本客户端已经实现所有写操作。

## 凭证和配置边界

- 每个服务 ID、提供方、资源及 GitHub 公开 Client ID 共同确定独立加密存储，使用 Android Keystore 与 noBackup；不读取模型凭证，不跨服务转发 bearer。
- 普通设置只保存公开连接信息与页面入口；受保护连接的 headers 不参与请求且不进入导出。Web、旧版 Android、旧 SSE 或缺失原生插件时明确失败，不匿名或付费回退。
- 仅访问固定官方 OAuth 地址和指定 API 资源，拒绝网络重定向。Notion 会话头只透传两个允许的协议头；SSE 读到匹配 RPC 结果即可返回，并限制时间与响应大小。
- 断开连接清除本机该来源凭证，不能声称远端已撤销；远端授权可由本人在服务商设置中删除。
- 不自动上传聊天原文，不导入 ChatGPT 内置 Memory，不自动同步两个客户端的对话历史。

## 后续写入如何做

读取验收后，GitHub 新增与更正应使用同一 GitHub App 的 Contents 写权限，重新批准安装权限，然后实现：读当前文件与 SHA → 仅修改相关事实 → 以当前 SHA 条件提交 → 处理冲突 → 回读确认并返回 commit/文件来源。当前版本没有这些写工具。明确“记住”“更正”可以成为写入触发，不把每轮闲聊都提交进仓库。Notion 写日记另行实现，并保持原双窗口目录和私记隔离。

## 验证记录

- 新增 6 项 Web 测试，覆盖两个提供方的发现/执行与元数据传递、协议会话头、凭证隔离、缺失桥接/网页/旧 SSE 禁止回退和安全导出；相关 5 个测试文件共 30 项通过。
- Android 本地及 CI XML 完整单元测试均为 43 项通过、0 失败；包括新提供方策略、公共注册、设备码间隔、取消、刷新失效、Git blob 校验和无须等待 EOF 的 SSE 读取。
- 合成资料的手机尺寸 UI 验证通过：公开 ID 输入、复制并打开登录不打断轮询、取消后不启用、GitHub SHA 预览、Notion 页面预览、保存与断开。没有用合成测试声称本人已授权或私人记忆已读取。
- TypeScript 检查与生产 Web 构建已通过。CI 的主检查、额外工具/API 与 Worker 类型检查通过，完整 Web 测试为 491 个文件 / 3,111 项通过。

### 第 12 版安装包证据

- 实现 commit：`c805735e84fa3fedb1cf5946c98538dcc7a1e4b6`。
- [CI #12](https://github.com/Qiufannn/polaris-local-first/actions/runs/43630009079) 的四个 job 全部成功。
- 推荐安装的 [signed-12-1 artifact](https://github.com/Qiufannn/polaris-local-first/actions/runs/43630009079/artifacts/14127927200)，包内为 `polaris.apk`。版本 `1.0-qiufann.12`，versionCode `12`，包名 `com.alyssa.polaris`。
- 签名 ZIP SHA-256：`0e7e014a9849d5ba3db49ce9da7eeb569c45f88304faeaeb85b36d7436bdc3d5`。
- APK SHA-256：`2a76c879e14cef5573eadeb2d0cc753e5159788d5ab0b589ed99959068d08713`。
- 个人证书 SHA-256：`f703307d2ca9936df2bc928f03f3e49f9793c77d25ce24079e8baed75d80e3a5`，与已安装版本的固定证书一致。独立 `apksigner verify` 成功，回执中的源码、run ID、版本及 APK 校验和相符。
- 已下载 unsigned artifact `14128006849`，核对 CI 测试 XML、源代码回执及 APK 摘要；签名 APK 的原始 ZIP 文件内容逐项相同，仅新增 v1 的三个签名条目。
- 可以覆盖安装；本人实际升级保留数据、两处 OAuth、GitHub 私密文件及 Notion 原日记本读取仍待手机验收。没有宣称读写同步已完成。

官方协议依据：

- [GitHub App 用户访问令牌与 Device Flow](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app)
- [GitHub App 访问令牌刷新](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens)
- [Notion MCP 资源元数据](https://mcp.notion.com/.well-known/oauth-protected-resource)
- [Notion OAuth 服务元数据](https://mcp.notion.com/.well-known/oauth-authorization-server)
