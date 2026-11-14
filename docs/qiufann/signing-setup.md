# 稳定个人签名的准备与当前停点

2026-11-14（中国时间）。订阅实现的已验证源码见阶段 B 回执。本文件只记录签名构建准备；**尚未生成实际签名密钥，尚未配置任何 Secrets，尚无可安装签名 APK**。

## 已准备的构建路径

1. 原有完整回归、原生测试和 unsigned release 构建继续执行。
2. CI 使用本 workflow 的 `github.run_number` 作为 versionCode，versionName 为 `1.0-qiufann.<run_number>`，避免后续个人版本一直固定为 1。未指定环境变量的本地构建仍沿用上游 1 / 1.0。不要删除并重建该 workflow 来重置计数；需要重建时先核对已安装的最高版本号。
3. 新签名作业只在本人仓库的定制分支 push 且两个检查作业成功后执行；PR 不读取签名材料。
4. 四个签名 Secrets 全空时明确报告“未配置”，只保留 unsigned 产物；部分缺失时失败。它不会自动生成或替换密钥。
5. 配置完整后，签名作业下载同一 run 的已测试 unsigned artifact，检查 APK SHA256、source_sha、run_id，再用稳定 keystore 签名，不重建应用。
   产物名从成功的 Android 作业输出取得，避免仅重试签名作业时误找不存在的新 attempt 产物；禁用额外的 v4 idsig 附件，手机只需安装 APK。
6. 密钥只临时解码到 GitHub runner 的受限临时文件，密码通过进程环境变量交给 apksigner；退出即清理。密钥不上传为 artifact，不提交 Git，不出现在命令参数或日志里。
7. 签名产物只包含 APK、校验值、源提交回执和公开证书信息，名称 `polaris-android-signed-<run>-<attempt>`。必须通过 apksigner verify；配置完成前不能宣称这条分支已实际跑通。

## 需要的安全存储字段（只有名字，没有值）

- `POLARIS_ANDROID_KEYSTORE_BASE64`
- `POLARIS_ANDROID_STORE_PASSWORD`
- `POLARIS_ANDROID_KEY_ALIAS`
- `POLARIS_ANDROID_KEY_PASSWORD`

这些材料应新生成一次后由秋凡控制的安全存储保管，之后更新 APK 重用。不要把已有 API Key、OAuth token、GitHub token、密码或签名材料发到聊天。第一次个人签名与原作者签名不同，不能假定它能覆盖原作者安装包；不得为了安装而直接卸载并丢失已有数据。

## 当前实际阻挡

- 浏览器分工已由秋凡明确纠正：GitHub 与 Secrets 设置在 **Via（mark.via）**，聊天会话在 **Edge（com.microsoft.emmx）**。后续不得把 GitHub 登录页打开到 Edge；需要秋凡接话或本人验证时返回当前聊天所用 Edge，无法确认返回成功时须明说。
- 本次已用按名称筛选确认 Via 安装，并收到启动 mark.via 成功的返回；之后整屏读取再次 Internal error，后续目标节点查找和返回 Edge 的操作均返回 Session terminated。尚未读到 Via 中的 GitHub 设置内容，未点击或填写 Secrets。
- 已请求一次恢复连接，下一次优先只查找 Via 中的目标按钮，避免请求整个浏览器页面；保留 Privacy Mode。连接不可用时不盲点、不更改安全设置，也不把“App 显示连接”当作工具会话可用的证据。
- GitHub 连接能推送代码和运行 CI，但仓库 Actions Secrets 的加密公钥接口返回 HTTP 403，无法由现有 API 连接安全配置 Secrets。
- 秋凡已明确同意操作手机里的 GitHub 设置。手机工具两次读取屏幕及一次查询 GitHub App 均返回 MCP `-32603 Internal error`；没有读取到屏幕，也没有点击或修改手机设置。
- 已请秋凡打开手机连接服务、确认在线并保持解锁；这是恢复连接，**不需要再次授权手机操作**。
- 秋凡回复“好啦”后，手机工具返回 GitHub Secrets 设置网址已打开，但后续读取屏幕仍返回 Internal error，接着返回 `Session terminated`。因此未确认页面内容，未点击、未输入、未改动任何 Secrets，也未读取密码。
- 再次刷新后恢复了一次读屏，确认系统弹出“Edge / 双开 Edge”的选择框。尝试选择普通 Edge 时工具再次 Internal error，下一次读屏为 Session terminated；无法确认该次点击是否生效。没有继续点击或填写安全设置。用户已说明需要时应返回 Edge 的本会话；通道断开后无法代替她确认返回，因此已请她手动返回。
- 手机连接恢复后先检查 GitHub 官方安全设置入口。如果要求本人验证，由秋凡在手机本地完成，不索取验证密码或 token。

## 仍待实际验证

新 keystore 的安全保管、Secrets 配置、签名成功、Android 10/较新设备安装、第二个同签名版本覆盖升级和数据留存。本轮没有用“构建成功”替代这些验收，也未推进气泡、群聊、语音或主题阶段。

## 已完成的无密钥检查

- `actionlint .github/workflows/qiufann-android.yml` 通过，`git diff --check` 通过。
- 指定 `POLARIS_ANDROID_VERSION_CODE=5` 的 `testReleaseUnitTest assembleRelease` 通过（39 秒、471 个任务）；`aapt dump badging` 确认为同包名 `com.alyssa.polaris`、versionCode `5`、versionName `1.0-qiufann.5`。
- 上述检查未提供签名材料，未创建 keystore；签名路径的真实成功验收仍未进行。

## 最终在线验证

- 源码 `9776ce06d1e539aaac89d51fbaa84352528c733e` 的 [CI #6](https://github.com/Qiufannn/polaris-local-first/actions/runs/43598332100) 三个作业均成功。
- 网页层 489 个文件、3100 项测试通过；原生 JUnit XML 合计 18 项、0 failures / 0 errors；Web/Android 构建通过。
- 签名作业仅执行了配置检查。四个 Secrets 均未配置，实际签名、下载待签 APK 和上传 signed artifact 的步骤全部 skipped。**CI 成功不代表已签名。**
- 仅有 [unsigned artifact](https://github.com/Qiufannn/polaris-local-first/actions/runs/43598332100/artifacts/14115640390)，名称 `polaris-android-unsigned-6-1`；已实际下载核对源码 SHA、APK SHA256、18 项测试 XML 和 ZIP 完整性。
- 线上包名 `com.alyssa.polaris`，versionCode `6`，versionName `1.0-qiufann.6`，说明 CI 的递增版本配置已生效。APK 大小 15,646,720 bytes，SHA256 `cbbe4270b9a8d53dc7a21fab6a3994f2eeb3c79d06ee0243711436af36d7be8c`。
- ZIP SHA256 `23fac2e868c2db2fe158e9ad49eb049067ded8959c3d912c18a5e8a4c3ba8192`，与 GitHub digest 一致。该包仍不能直接安装。
- 最后回填提交仅更新文档并使用 `[skip ci]`；已验证源码仍明确为 `9776ce0`。没有在手机或仓库配置实际 Secrets，也未生成真实 APK 签名密钥。
