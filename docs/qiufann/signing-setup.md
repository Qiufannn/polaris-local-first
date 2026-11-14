# 稳定个人签名的配置与验证

当前状态：已准备手机本地生成随机恢复值、GitHub Secrets 保管和 CI 内恢复签名的路径。尚未配置真实签名材料，也没有发布可安装的个人签名 APK。既有阶段 B 自动验证不等于真机授权和覆盖升级验收。

## 手机手动配置：一个新 Secret

GitHub 设置使用 **Via（mark.via）**；聊天使用 **Edge（com.microsoft.emmx）**。手机自动化连接反复中断，现由秋凡手动操作，不再反复请求恢复连接。

1. 在本仓库 Settings → Secrets and variables → Actions → New repository secret，把 Name 填成 `POLARIS_ANDROID_SIGNING_SEED`。
2. 在 Via 的新标签页打开本仓库 `tools/android-signing/generate-seed.html` 对应的离线页面。可将该文件编码成 `data:text/html;charset=utf-8,` 地址粘进地址栏打开；代码只包含公开生成器，不包含任何个人随机值。
3. 点击一次“生成并复制”。页面调用手机浏览器的 `crypto.getRandomValues` 生成 32 个随机字节，显示为 64 个十六进制字符。页面禁止网络请求、不上传值、不主动写存储；刷新会生成另一份，配置完成后不要重新生成并覆盖。
4. 回到 GitHub 标签页，在 Secret 框粘贴，然后点 Add secret。若复制失败，长按生成值手动复制。若地址无法打开，先停在此处报告页面提示，不要使用普通密码或网上示例替代。
5. 只需在聊天回复“保存好了”；不要发送值或包含值的截图。若需备份，仅保管到本人受信任的密码管理器。此值可恢复完整签名身份，应按签名私钥保护。

不需要 API Key、GitHub token、现有密码或已有签名密钥。GitHub 连接的 Secrets 公钥接口返回 403，现有连接不能安全代写该字段；本人在 GitHub 官方页面保存即可。

## 构建和签名规则

- 网页完整回归、原生测试和 unsigned release 构建继续执行。CI 使用本 workflow 的 `github.run_number` 作为 versionCode，versionName 为 `1.0-qiufann.<run_number>`。不要删除重建 workflow 来重置计数；必要时先核对已安装的最高版本。
- 新增独立无秘密自检作业：使用明确公开的固定测试向量恢复证书，临时签 APK 并用 Android apksigner 验证后立即删除。测试签名 APK 不上传，也不能当作个人安装包。
- 实际签名只在本人仓库定制分支 push，且网页、Android 和签名自检全部成功后运行；PR 不读取签名材料。未配置时明确跳过实际签名。
- 配置完整后，下载同一个 run 的 unsigned artifact，核对 APK SHA256、source_sha、run_id 后签名，不重建应用。下载使用 Android 作业输出的原 artifact 名，以支持仅重试签名作业。
- 由恢复值通过固定 HKDF-SHA256 / P-256 配置恢复稳定密钥；证书采用 RFC 6979 确定性签名，确保每次证书字节完全一致。加密库版本与 SHA256 固定，公开测试向量也固定证书 SHA256。技术说明见 [签名工具说明](../../tools/android-signing/README.md)。
- 临时 PKCS12 只在 runner 内创建，文件权限 0600；临时密码由独立 HKDF 域导出，经 stdin 交给 apksigner，退出清理。实际 seed、私钥、临时密码均不提交 Git、不打印、不放入 artifact。
- 实际产物仅包含 APK、SHA256、源码回执和公开证书，名为 `polaris-android-signed-<run>-<attempt>`。必须通过 apksigner verify；第一次个人签名成功后，将公开证书 SHA256 固定到 `tools/android-signing/expected-certificate.sha256`，后续不匹配直接停止发布。
- 签名配置是永久更新身份，第一次安装后不能随意更改恢复值或算法配置。第一次个人签名与原作者证书不同，不能假定能覆盖原作者安装包；不得为了安装而直接卸载并丢失已有数据。

## 已有 keystore 的兼容路径

只为已有稳定个人 keystore 保留旧四字段：`POLARIS_ANDROID_KEYSTORE_BASE64`、`POLARIS_ANDROID_STORE_PASSWORD`、`POLARIS_ANDROID_KEY_ALIAS`、`POLARIS_ANDROID_KEY_PASSWORD`。四个字段须完整配置，不能与新恢复值混用；部分配置或两种模式并存会失败，避免默默换签名。当前手机配置只需上面一个新 Secret。

## 仍待实际验证

新恢复值安全保管、GitHub Secrets 配置、真实个人签名成功、Android 10/较新设备安装、第二个同签名版本覆盖升级和数据留存。配置完成前不得把绿色 CI 当作实际签名成功。未推进气泡、群聊、语音或主题阶段。

## 新路径的本地验证

- 签名配置自检通过：同值密钥和证书一致、不同值证书不同、签名验签、PKCS12 恢复、无效输入拒绝；公开固定证书 SHA256 为 `d822d0ad4dfd8c955f61f23bc592b208836273335d80677412ae51feae669368`，它仅属于公开测试向量。
- 用公开测试向量临时签既有 unsigned APK，并通过 Android 10+ apksigner 验证。另行检查正式入口：正确公开证书固定值可签名，错误固定值在生成输出前被拒绝。测试文件已清理。
- 手机生成页在移动视口浏览器中测试格式为 64 位十六进制、单次点击后禁用重复生成、两次加载生成不同值，且没有 HTTP 请求。托管浏览器策略阻止直接导航 data 地址，故手机 Via 地址栏兼容性仍由本人操作确认；未绕过策略。

## 已完成的无密钥检查

- `actionlint .github/workflows/qiufann-android.yml` 通过，`git diff --check` 通过。
- 指定 `POLARIS_ANDROID_VERSION_CODE=5` 的 `testReleaseUnitTest assembleRelease` 通过（39 秒、471 个任务）；`aapt dump badging` 确认为同包名 `com.alyssa.polaris`、versionCode `5`、versionName `1.0-qiufann.5`。
- 上述检查未提供签名材料，未创建 keystore；签名路径的真实成功验收仍未进行。

## 旧路径的在线验证（CI #6）

- 源码 `9776ce06d1e539aaac89d51fbaa84352528c733e` 的 [CI #6](https://github.com/Qiufannn/polaris-local-first/actions/runs/43598332100) 三个作业均成功。
- 网页层 489 个文件、3100 项测试通过；原生 JUnit XML 合计 18 项、0 failures / 0 errors；Web/Android 构建通过。
- 签名作业仅执行了配置检查。四个 Secrets 均未配置，实际签名、下载待签 APK 和上传 signed artifact 的步骤全部 skipped。**CI 成功不代表已签名。**
- 仅有 [unsigned artifact](https://github.com/Qiufannn/polaris-local-first/actions/runs/43598332100/artifacts/14115640390)，名称 `polaris-android-unsigned-6-1`；已实际下载核对源码 SHA、APK SHA256、18 项测试 XML 和 ZIP 完整性。
- 线上包名 `com.alyssa.polaris`，versionCode `6`，versionName `1.0-qiufann.6`，说明 CI 的递增版本配置已生效。APK 大小 15,646,720 bytes，SHA256 `cbbe4270b9a8d53dc7a21fab6a3994f2eeb3c79d06ee0243711436af36d7be8c`。
- ZIP SHA256 `23fac2e868c2db2fe158e9ad49eb049067ded8959c3d912c18a5e8a4c3ba8192`，与 GitHub digest 一致。该包仍不能直接安装。
- 该次回填提交仅更新文档并使用 `[skip ci]`；该次已验证源码明确为 `9776ce0`。没有在手机或仓库配置实际 Secrets，也未生成真实 APK 签名密钥。
