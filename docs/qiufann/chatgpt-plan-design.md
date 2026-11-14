# ChatGPT 订阅登录：代码审阅与下一轮最小方案

审阅基线：`a510f9ae68a70dead5ad344829466d3e5770f600`。本文保留第一轮的原始方案与当时差距；后续实现与实际验收状态见 [订阅实现报告](phase-b-report.md)。文中“拟议/尚未实现”描述的是第一轮停止时的状态。

## 已读代码与差距

| 范围 | 当前文件与行为 | 对订阅线路的影响 |
| --- | --- | --- |
| Provider 数据 | `src/types/domain/runtime.ts` 的 `ProviderProfile` 必须有 `apiKey`，协议只有现有四种；`src/stores/runtimeStoreProviders.ts` 负责归一化 | 添加独立认证种类，账号引用只驻内存并从原生恢复，旧配置保持兼容；OAuth 不能装进 apiKey |
| Provider 选择 | `src/engines/providerProtocol.ts`、`src/engines/provider-runtime/providerRuntimeAdapters.ts` 根据协议选择适配器 | 订阅认证必须明确路由，避免仅根据 `/responses` 与普通 API 线路混用 |
| Responses 请求 | `src/engines/provider-runtime/providerRuntimeResponsesAdapter.ts` 会发温度、top_p、max_output_tokens；stream 按配置；无强制 store:false；工具历史转为文本标记 | 新建订阅专用请求构造器；强制 store:false/stream:true、input 数组、系统提示转 developer/instructions；保留 function call/output 结构 |
| 参数覆盖 | `src/engines/provider-runtime/requestShared/transportSanitize.ts` 在基础 body 后合入 customBody/bodyOverrides | 订阅线路需在所有覆盖后做最终白名单校验，不能允许用户设置重新开启 store 或不支持字段 |
| 请求传输 | `src/engines/chat-api/chatApiTransport.ts` 把 headers/body 传给原生桥；`src/native/providerHttp.ts` 通过 base64 chunk 流式解码 | 新增只传 accountRef 的订阅原生传输；原生侧注入授权，不向 JS 返回 token，也不经过中继服务器 |
| Android 桥 | `android/app/src/main/java/com/alyssa/polaris/NativeProviderHttpPlugin.java` 通用 URL、POST、流事件、取消；连接和读取 timeout 为 0 | 可复用流桥模式；订阅桥固定 HTTPS 官方端点、有限超时、拒绝重定向、独立刷新，避免把 Bearer 发给任意 URL |
| 桥注册 | `android/app/src/main/java/com/alyssa/polaris/MainActivity.java` 手动注册三个插件 | 新插件必须显式注册；登录取消、后台、进程死亡需要清理待授权状态 |
| SSE | `src/engines/provider-runtime/providerRuntimeStreamEvents.ts` 识别文本增量、工具和 completed；没有 failed/incomplete 专门分支 | 订阅适配器明确终态，额度错误即使在开始出字后也必须显式失败 |
| SSE 收尾 | `src/engines/chat-api/chatApiStreamingCollector.ts` 有断流标志和通用兼容回退 | 订阅不能把 EOF、[DONE] 或有部分文本视作 completed；部分内容保留并标记未完成 |
| 重试 | `src/engines/provider-runtime/providerRuntimeRetryPolicy.ts` 有非流式、输出预算和中继兼容回退 | 订阅必须禁用非流式/收费线路回退；刷新最多一次，禁止无限重试/重复执行工具 |
| 模型目录 | `src/engines/providerModelDiscovery.ts` 普通 OpenAI 读取 data[].id | 订阅按账号原生 GET /v1/models，读取 models[]、visibility=list、slug/display_name，换账号清目录 |
| 本地配置 | `src/stores/runtime/index.ts`、`src/stores/runtime/localData.ts` 持久化完整 provider；`src/infrastructure/persistence.ts` 接 IndexedDB/原生后端 | 这些是一般本地数据管线，不是已经存在的 OAuth 安全存储；本次审阅未发现 Android Keystore OAuth 实现 |
| 完整备份 | `src/app/shell/completeBackupExport.ts`、`src/stores/storeExportPackage.ts` 写 stores/runtime.json | token、账号身份和 refresh 数据必须完全不进入这个对象；备份只保留无身份线路类型，恢复后重新登录 |
| Android 系统备份 | `android/app/src/main/AndroidManifest.xml` 的 allowBackup=true | 后续安全凭证文件用 noBackupFilesDir，并排除系统/设备迁移；不能只依赖聊天导出过滤 |
| UI | `src/ui/shell/ApiProviderSheet.tsx`、`src/ui/AppShellOverlays.tsx` 管理现有线路设置 | 增加订阅卡和登录动作，保留所有原线路；不显示 API Key 输入作为订阅前提 |

## 下一轮具体文件清单（拟议，尚未创建/修改）

| 顺序 | 文件 | 最小职责 |
| --- | --- | --- |
| 1 | 新增 `android/app/src/main/java/com/alyssa/polaris/ChatGptCredentialStore.java` | Android Keystore AES-GCM；noBackupFilesDir 原子密文记录；独立账号/issued-client/workspace 绑定；设备 host ID；不输出凭证 |
| 2 | 新增同目录 `ChatGptAuthPlugin.java`、`ChatGptLoopbackListener.java`、`ChatGptIdTokenVerifier.java` | 系统浏览器、仅 127.0.0.1 临时 listener、PKCE/state/nonce、官方 code exchange、JWKS 验签和 token granted scopes |
| 3 | 修改 `MainActivity.java`；按实际依赖修改 `android/app/build.gradle`；审阅 `AndroidManifest.xml` 的恢复策略 | 注册插件；选维护中的 JWT/JWKS 库；不增加外部回调 scheme 替代 loopback |
| 4 | 新增 `src/native/chatgptAuth.ts`、`src/stores/chatgptAccountStore.ts` | JS 仅接收登录状态、身份摘要和不敏感 accountRef；账号摘要只驻内存、重启从原生取；不会把账号存入普通导出 |
| 5 | 新增同原生目录 `ChatGptPlanHttpPlugin.java` 和 `src/native/chatgptPlanHttp.ts` | 固定端点模型 GET/Responses POST；原生取凭证和单飞刷新；取消、超时、前后台切换；断开连接清理并在支持时撤销 |
| 6 | 修改 `src/types/domain/runtime.ts`、`src/engines/providerProtocol.ts`、`src/stores/runtimeStoreProviders.ts` | 独立订阅认证判别；旧 provider 无新字段时仍按原逻辑；不把 token 写入 apiKey |
| 7 | 新增 `src/engines/provider-runtime/providerRuntimeChatgptPlanAdapter.ts`；修改 `providerRuntimeAdapters.ts`、`providerRuntimeRequestTypes.ts`、`providerRuntimeTypes.ts`、`providerCapability.ts` | 专用请求白名单/工具能力/异步认证边界；强制官方端点、上下文和流式约束 |
| 8 | 修改 `src/engines/chat-api/chatApiTransport.ts`、`chatApiStreamingCollector.ts`、`src/engines/provider-runtime/providerRuntimeStreamEvents.ts`、`providerRuntimeRetryPolicy.ts`、`providerRuntimeErrorPayload.ts` | 严格 completed；failed/incomplete/EOF；订阅资格和额度中文提示；禁用非流式、任意中继和自动收费回退 |
| 9 | 修改 `src/engines/providerModelDiscovery.ts`；新增 `src/ui/shell/ChatgptSubscriptionCard.tsx`；接入 `ApiProviderSheet.tsx`、`src/ui/AppShellOverlays.tsx` | Continue with ChatGPT、账号/workspace、授权范围、重登/断开；模型跟随账号；不读取 ChatGPT 历史说明 |
| 10 | 修改 `src/i18n/locales/zh-CN/apiProvider.ts`、`src/i18n/locales/en-US/apiProvider.ts`；审阅 `src/stores/runtime/index.ts`、`src/stores/storeExportPackage.ts`、`src/app/shell/completeBackupExport.ts` | 文案和普通备份/主题/请求日志的隐私边界；若新类型影响类型守卫，限定修复到订阅字段 |
| 11 | 增加对应 TypeScript 与 Android 测试文件（下一轮） | state/nonce/PKCE/重放/JWKS/刷新并发/终态/导出/旧 provider 回归；使用本地测试替身，不调用收费 API |

`src/engines/provider-runtime/providerRuntimeResponsesAdapter.ts` 保留为普通 Responses 线路。新适配器可抽取共用的纯序列化函数，但不能全局收紧并破坏旧 provider。

## 最小实现顺序与停点

1. **先做原生认证和安全存储测试**。新授权不替换正在使用的账号，直到 state、签名、issuer、audience、exp、nonce、身份与 granted scopes 全部通过。listener 先启动，绑定 `127.0.0.1` 的空闲端口，路径固定 `/auth/callback`；匹配尝试后关闭，取消/超时/销毁都关闭；错误 state 不得兑换 code。Android 浏览器回到前台需要独立真机验证。
2. **再做最小登录 UI 与恢复**。首次 dynamic_agent_client + 固定应用名；后续使用该账号 issued client_id；设备 host ID 不随重试变化。回调与兑换的 redirect URI 完全一致。无 client secret，无 API Key。成功后检查 plan scope，未授权只显示身份登录状态，不允许推理。
3. **实现刷新与模型目录**。刷新互斥，access/refresh/expiry/scopes 一次原子替换；处理撤销、失效和账号切换，保留旧账号直到新账号验证成功。模型从当前账号目录实时获取，不能假定某个型号必可用。
4. **接入一条中文纯文本流**。专用 provider/transport；`POST https://api.openai.com/v1/responses`；`store:false`、`stream:true`、每次 input 带必要历史；只在 response.completed 后成功。中断保留部分正文，failed/incomplete 明确显示，可恢复但不静默切换线路。
5. **补齐工具与上下文回归**。function/custom 工具按官方当前限制组织 namespace 或 additional_tools；call_id/参数跨 chunk 关联；回传工具结果结构化。没有验证工具闭环前不宣称工具支持；禁用该线路的托管 MCP、图像生成等不支持能力。
6. **回归隐私和本地数据**。日志/导出/主题/系统备份中检查凭证与身份泄漏；原线路、角色、工具、聊天重启保持。真机验证首次登录、取消、换账号/workspace、杀进程、过期刷新、额度不足、断网。

以上均需要第一轮汇报后的下一步确认。本轮不创建登录按钮，不生成 OAuth 状态、不执行授权或真实推理，不动聊天、主题或数据库迁移。

## 已核实的官方要求与限制

- [注册与登录](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)：动态注册、127.0.0.1、issued client_id、PKCE/OIDC、受保护本地保存；Android 原生回调仍需真实设备验证，文档不是完成适配的证据。
- [模型与推理](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)：账号模型目录、官方 Responses、流到 completed。
- [预览限制](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)：当前 unsupported 字段必须在最终请求统一排除；system 输入转 developer/instructions；HTTP 不使用 previous_response_id；音频、托管工具等能力不能沿用一般 API 的假设。

核对日期：2026-11-14（中国时间）。账号资格、workspace 权限和额度只能在本人后续实际授权后判断；本轮没有验证秋凡的账号是否合资格。
