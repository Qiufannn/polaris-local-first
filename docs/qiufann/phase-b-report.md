# ChatGPT 订阅最小实现：代码与测试回执

日期：2026-11-14（中国时间）。本轮承接“继续吧”，实现阶段 B 的代码和自动检查；不推进气泡、长期数据、群聊、语音或主题阶段。

**状态：原生授权、专用订阅请求和账号卡的代码已实现，自动检查通过；不是已经完成手机端订阅登录验收。** 沿用“不索取或写入真实密钥、token、密码或签名密钥”的边界，未运行本人授权或真实模型请求，仍只构建 unsigned release。Android Keystore 的代码在未来本人登录时才会在手机上执行，本轮没有在开发环境创建该安全存储。

## 实现与文件

| 文件 | 本轮实现 |
| --- | --- |
| `android/app/src/main/java/com/alyssa/polaris/ChatGptOAuth.java` | 官方端点、fresh state/nonce、S256 PKCE、回调客户端绑定；Nimbus JOSE JWT 验签及 issuer/audience/exp/nbf/nonce/sub 校验；原生侧第二次过滤不支持请求字段 |
| 同目录 `ChatGptLoopbackListener.java` | 每次登录独立 loopback 端口，只绑定 127.0.0.1 和 /auth/callback；检查 Host/state/重复参数；限制请求长度和等待时间；成功、取消、销毁后关闭，不把授权码回显到网页 |
| 同目录 `ChatGptCredentialStore.java` | Android Keystore AES-GCM、绑定 AAD、noBackupFilesDir 密文文件、AtomicFile 替换；安装 host ID 独立保存；不写一般 SQLite/IndexedDB 或主题/聊天备份 |
| 同目录 `ChatGptSession.java` | 已签发 client_id + 已校验 subject 隔离账号；先验证再替换；单飞刷新与旋转凭证原子替换；撤销/过期/丢失权限处理；实时账号模型目录；本地断开和远端撤销确认 |
| 同目录 `ChatGptAuthPlugin.java`、`MainActivity.java` | 系统浏览器登录、账号摘要/切换/重登/取消/断开；仅把账号摘要传给 WebView；损坏本机凭证时提供单独重置入口；注册两个新原生插件 |
| 同目录 `ChatGptPlanHttpPlugin.java` | 固定官方 Responses HTTPS 地址；原生注入 Bearer，不接收 JS headers/URL；最多一次无进度 401 刷新；账号切换停止旧请求；有界连接/读取超时；不跟随重定向 |
| `android/app/build.gradle` | 固定 Nimbus JOSE JWT 10.10；JVM 测试使用 org.json；不增加 signingConfig，不生成 APK 签名密钥 |
| `src/native/chatgptAuth.ts`、`src/native/providerHttp.ts` | 账号桥仅返回摘要；专用流桥仅接收 requestId/body，不传 token、API Key 或任意 URL；复用已有 UTF-8 解码、取消和监听器清理 |
| `src/engines/chatgptPlan.ts`、`provider-runtime/providerRuntimeChatgptPlanAdapter.ts` | 独立认证判别与适配器；强制 store:false / stream:true / input 数组；developer 提示；本地函数 namespace 和结构化调用/结果；忽略普通线路的 customBody/customHeaders/预算覆盖 |
| `src/engines/chat-api/chatApiTransport.ts` | 订阅只走 Android 专用原生桥；无桥、Web、强制 relay 均失败而不是回落付费线路；严格区分 completed、failed、incomplete、EOF 和 DONE；保留已显示的部分回复 |
| `src/engines/provider-runtime/providerCapability.ts`、`providerRuntimeAdapters.ts`、`providerRuntimeTypes.ts` | 注册独立适配器；只允许 native-direct、强制流、禁止普通预算/兼容回退，保留旧线路 |
| `src/types/domain/runtime.ts`、`src/stores/runtimeStoreProviders.ts`、`src/engines/providerRouteCard.ts` | 只持久化 authKind 和普通模型配置；订阅 apiKey 强制为空；导入过滤未知凭证/身份字段；线路卡仍能识别订阅类型但不包含账号 |
| `src/engines/chatApi.ts`、`providerConnectionTest.ts`、`providerModelDiscovery.ts` | 订阅不要求填写 API Key；使用原生实时目录的 models[].slug/display_name；普通 API 线路保留原逻辑 |
| `src/ui/shell/ChatgptSubscriptionCard.tsx`、`ApiProviderSheet.tsx`、`ApiProviderListView.tsx` | Continue with ChatGPT、账号/工作区绑定、权限、重登/断开/取消、模型选择；摘要只驻组件内存，订阅线路不展示 API Key 配置表单 |
| `src/i18n/locales/zh-CN/apiProvider.ts`、`src/i18n/locales/en-US/apiProvider.ts` | 双语账号卡文案；资格、额度、刷新、取消、未确认撤销提示；不读取 ChatGPT 历史的说明 |
| `.github/workflows/qiufann-android.yml` | 在线增加 testReleaseUnitTest；APK artifact 附原生 JUnit XML、源提交与 SHA256 |

新增测试：`android/app/src/test/java/com/alyssa/polaris/ChatGptOAuthTest.java`、`ChatGptSessionTest.java`、`ChatGptLoopbackListenerTest.java`、`src/engines/provider-runtime/providerRuntimeChatgptPlanAdapter.test.ts`、`src/native/chatgptPlanTransport.test.ts`。现有适配器注册断言增加新适配器，其余旧 provider 测试保留。

## 实际验证

- 三组类型检查：`npm run typecheck`、`npm run typecheck:extra`、`npm run typecheck:worker` 全部 exit 0。
- `npm test -- --maxWorkers=2`：**489 个测试文件、3099 项测试全部通过**。首次完整运行仅失败于旧注册数量断言；补上新增适配器后完整重跑通过，没有跳过旧测试。随后新增“空 API Key 从完整聊天入口成功走订阅桥”的集成用例，所在文件 6 项测试再次通过；线上会对最终源码全量验证。
- `npm run build`：生产构建通过。保留既有大 chunk 警告。
- `npx --no-install cap sync android`：通过。生成的 config.xml 纯空白变化已恢复，不提交。
- `./gradlew testReleaseUnitTest assembleRelease`：最终源码再次通过，471 个任务（11 executed / 460 up-to-date），44 秒；**18 项原生 JVM 测试，0 failures / 0 errors**。含真实本机 TCP 回调监听、错误 state/Host 不消耗正确尝试、成功/取消后端口关闭、真实 JWT 验签、身份字段、并发刷新只执行一次、权限撤销、网络失败不覆盖旧记录、账号摘要不含凭证及重置使旧尝试失效。
- `actionlint .github/workflows/qiufann-android.yml` 与 `git diff --check`：通过。
- 浏览器 App 启动及切换房间/对话无 pageerror。账号卡单独使用假的原生返回值检查：Continue → 实时模型列表 → 激活 authKind=chatgpt-plan 且 apiKey 为空 → 断开连接，全部通过，无 pageerror；390 px 视口无横向溢出。这不是真实 Android 授权，也不是本人账号登录。
- 本地最终 unsigned APK：15,646,704 bytes，SHA256 `2f3c716ef80e4443368d42e79835c04f52ec02e86b462d8dd2817588661dd202`；`apksigner verify` 按预期不通过，**不能直接安装**。线上运行与产物结果另在本报告末尾回填。

所有自动测试使用测试替身或仅在内存生成的测试验签材料，没有向真实 OAuth/token/Responses 服务提交个人凭证，没有把测试验签材料用于 APK 签名。

## 手机验收还缺什么

本轮没有改签名策略，故没有能供秋凡安装的已签名 APK，也没有宣称 stage A/B 整体完成。后续若允许生成并妥善保存稳定 APK 签名密钥，才可准备真机安装包；不需要把密码或密钥明文发到聊天。

已实际检查签名保管入口：当前 GitHub 连接调用 `GET /repos/Qiufannn/polaris-local-first/actions/secrets/public-key` 返回 `403 Resource not accessible by integration`。这一步只读取仓库用于加密上传 Secrets 的公开公钥，不读取任何秘密值；连这一前置接口都无法访问，因此本轮没有尝试写入 Secrets 或生成实际签名密钥。现有连接读写代码、运行 Actions 正常，不能把此权限缺口说成 GitHub 密码失效。后续需要可安全写入本仓库 Actions Secrets 的连接/操作入口，不能用公开仓库文件、普通 artifact 或聊天明文代替秘密保管。

签名产物具备后，最短本人验证顺序：打开“供应商 → ChatGPT 订阅” → Continue with ChatGPT → 在系统浏览器本人授权 → 返回 Polaris，确认权限与可用模型 → 发一条中文 → 关闭并重开 App → 继续聊天。取消授权、真实过期刷新、撤销、网络中断、Android 10/较新系统、系统杀进程以及同签名覆盖升级仍须独立验证；不要卸载已有 App 来替代升级验证。

未验证/限制：

- Android Keystore 与 noBackupFilesDir 在真实手机上的加密、恢复和系统备份边界尚未做设备测试；当前 JVM 测试覆盖协议和会话，不能代替硬件安全存储验收。
- 从系统浏览器自动回到 App 受 Android 前后台限制；代码会尝试回到主界面，回调网页也提供手动返回说明，真机行为尚未验证。
- 服务没有提供可显示的工作区名称；UI 显示授权绑定的 issued client_id，**不解码或猜测 opaque access-token metadata**。支持按已保存注册切换，重新授权保持原工作区绑定；新工作区应从新注册入口授权。
- 模型目录和 subscription eligibility 以本人后续授权结果为准；没有假设秋凡已合资格，也没有硬编码某个模型为必可用。
- 函数工具请求/历史与流式 call_id 有结构测试；真实模型的工具闭环仍待账号授权后验证。音频、托管 MCP、图像生成等不支持的能力未接入订阅线路。
- 第一轮观察到的原有闹钟 receiver 声明问题未混入本轮修改。

## 同步与来源

`main` 继续保留上游基线；开发仍在 `qiufann/chatgpt-plan-android`，`upstream` 禁止 push，未覆盖上游代码。主要冲突候选为 MainActivity、Android app/build.gradle、provider 适配器注册/能力/传输、runtime provider 归一化和 ApiProviderSheet。原 Responses / Anthropic / Gemini / API Key 线路回归保留，聊天数据库、附件、主题和记忆格式没有迁移。

官方依据：[注册与登录](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)、[账号与刷新](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)、[模型与推理](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)、[预览限制](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)。Nimbus 版本来自 Maven Central 当日元数据。未复制 Aru 代码或专有素材。

## 最终在线结果与产物

- 已验证源码提交：`401dbb898125ce85d34f83cf94971efb6c5a28b1`。实现提交为 `51874b2`，随后补齐 AtomicFile 在只有 `.bak` 时的恢复、写后读取校验和安全记录大小上限；没有新增签名或真实账号操作。
- [在线 CI #4](https://github.com/Qiufannn/polaris-local-first/actions/runs/43589680282)：**completed / success，两个作业均 success**。
- 在线网页层：三组类型检查通过，489 个测试文件、**3100 项测试全部通过**，测试耗时 63.71 秒。
- 在线 Android：JVM 原生测试通过（1 分 13 秒），release APK 构建通过（1 分 21 秒）；已下载的三份 JUnit XML 合计 **18 tests / 0 failures / 0 errors**。
- [下载本轮 unsigned artifact](https://github.com/Qiufannn/polaris-local-first/actions/runs/43589680282/artifacts/14111509388)，名称 `polaris-android-unsigned-4-1`，含 APK、SHA256SUMS、build-info 和三份原生测试 XML。GitHub 保留至 2026-11-28；下载通常需要登录 GitHub。
- ZIP 大小 14,286,529 bytes，SHA256 `7904d17a52f4e21a9934dfd1f13ab95ea0811a9513d1d4b30bc1163b3340d521`，与 GitHub artifact digest 一致。
- 内含 APK 大小 15,646,704 bytes，SHA256 `2f3c716ef80e4443368d42e79835c04f52ec02e86b462d8dd2817588661dd202`，与本地最终构建一致。实际核对过 build-info 的源码 SHA、run_id、signing=unsigned、ZIP 完整性及 APK 内的 manifest/dex/Web 入口。
- 在线 APK 的 `apksigner verify` 按预期 exit 1（未签名），**不能直接安装**。不能把 CI 通过写成真人登录、Keystore 真机验证或覆盖升级已验收。
- 本回执回填提交仅更新文档并标记 `[skip ci]`，避免为了写回验证结果再跑一遍构建；上述验证明确对应 `401dbb8`。

当前停点：订阅代码与自动验证交付，安全签名保管入口受 GitHub Secrets 403 阻挡。未开始阶段 C–G，也没有以普通聊天、公开 Git 仓库或 public-repo artifact 保存任何实际秘密。
