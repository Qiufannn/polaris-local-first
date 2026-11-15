# 自然聊天与花园原账号连接

## 本轮范围

本人已确认 19 版双方能发送表情包，本轮按手机反馈调整显示，并加入花园独立连接。GitHub 主记忆和 Notion 日记继续使用原有连接，不改成花园存储。

表情包按 124px 小贴图显示，待发送为 68px，不再显示文件名、大小或收藏卡。本人旧表情包通过既有附件标记识别，协作者旧图通过表情包工具回执识别；普通照片仍保留原有附件界面。图片原字节、聊天记录和备份路径不变。

默认折叠/隐藏模式不显示成功的 MCP 行动记录；图片、文件和卡片产物继续显示。失败、待确认及 MCP `isError` 仍保留，完整模式可检查成功回执。普通已完成回复按空行分成独立气泡；代码、标题、列表、表格等结构化正文保持整体。复制、编辑、重试仍操作原来的一条消息，不拆写历史数据。

## 花园手机步骤

1. 覆盖安装本轮个人签名包，不卸载。
2. 当前房间/协作者 → 请求 → MCP → **连接花园（原账号）**。
3. 点 **连接花园（官方网页登录）**，在花园官网批准 Polaris Android Garden，选择原有小机，然后返回北极星。
4. 点 **核对花园身份并同步工具**，核对返回的小机名称、编号和所属人是原账号，再保存。

此入口会复用已有的官方花园连接；新连接在授权与身份读取前不启用。当前协作者自选 MCP 时只追加花园，保留其他选择。核对只调用 `get_self`，不发帖、回复或启动游戏。身份不符先不要保存，可断开本机连接再重新授权；本机断开不宣称已撤销远端授权。

## 授权实现与边界

已实际核对官网公开资源元数据 `/.well-known/oauth-protected-resource/mcp` 和授权元数据 `/.well-known/oauth-authorization-server`。官网支持 `/oauth/register` 公共客户端注册、`/oauth/authorize` 授权码和 `/oauth/token` 兑换。一次匿名兼容检查实际获得 HTTP 200 公共客户端注册响应，确认 loopback redirect 和 `token_endpoint_auth_method=none`；未使用用户凭证，未完成用户授权。

Android 使用 PKCE S256、一次性 state、固定 issuer/resource/回调路径及原生加密安全存储。资源固定 `https://galatea.abysslumina.com/mcp`，凭证仅随原生请求发给该端点，不进入聊天、请求头编辑器或导出。Notion 的签发者检查保持原规则；花园元数据未声明 RFC 9207，允许不返回 `iss`，若返回则必须匹配官网。花园未声明 refresh grant，因此注册仅请求 authorization_code；到期按实际授权状态重新登录，不要求用户提供密钥。

网页登录复用原生内部的 `notionLogin` 桥接方法名，来源和存储命名空间独立为 `garden-oauth`，不会替换 Notion、GitHub 或 ChatGPT 官端花园连接。Web 缺少原生桥时明确提示安装 Android 更新，不降级到匿名或手填凭证。

## 验证

- 本地生产构建、主类型检查及额外类型检查通过。定向 Web 4 文件 / 27 测试通过，覆盖正常照片、旧图、成功与失败回执、原消息不变和受保护原生通道。
- 原生 88 测试通过；新增花园测试覆盖公共客户端、PKCE/resource、取消与错误来源、凭证端点绑定、来源隔离及本机断开。
- 390px 实际浏览器交互检查通过：双方小贴图无文件卡/成功回执/空气泡，正文独立气泡，完整诊断和失败仍可见。花园 UI 用合成授权验证身份核对后启用、工具同步与断开；不把合成 UI 测试当真实花园授权。
- 本人已确认手机浏览器登录花园。**新版在手机上的真实授权、原账号核对和工具调用仍待覆盖安装后验收。**

## 20 版交付凭据

- 源码 `05d226afb04132493e7ea9959b2e053bda557f01`；[CI #20](https://github.com/Qiufannn/polaris-local-first/actions/runs/43720295426) 四作业成功。完整 Web 为 498 文件 / 3183 测试；原生 artifact 的测试 XML 独立核对 88 项，0 failures / errors / skipped。主、额外及 Worker 类型检查、生产构建和签名自检通过。
- [个人签名包 polaris-android-signed-20-1](https://github.com/Qiufannn/polaris-local-first/actions/runs/43720295426/artifacts/14164700918)。ZIP SHA256 `49cfc5e914cb97e254c3e13593c53b9eb112aa68c54f67eed395831e16ec2736` 与服务端 digest 一致；APK SHA256 `36dbe3328a85f42dfc409a29661a050b9fdff33baabb2dae7c9f8b6954e47814`，16347118 字节。
- 包名 `com.alyssa.polaris`、versionCode 20、个人证书 SHA256 `f703307d2ca9936df2bc928f03f3e49f9793c77d25ce24079e8baed75d80e3a5` 已核对。与同次 CI unsigned APK 原条目逐字节一致，仅新增三项签名元数据；101 张图库原字节一致，花园授权与身份核对、贴图和分段气泡的新 Web/原生代码均已核验入包。
- 只推送定制分支，未合并 main、未发布 Release。安装与真实花园账号验收状态仍以上述手机验收说明为准。
