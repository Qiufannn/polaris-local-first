# 稳定个人签名的配置与验证

当前状态：秋凡在手机 GitHub 官方页面保存恢复值后，CI #8 / #9 已真实完成个人签名；已下载独立核对两份 APK、源码回执、证书及完整性，两版证书均匹配仓库固定值。没有读取、索取或接收恢复值、密码或私钥。真机安装、官方授权和覆盖升级仍待本人验收。

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

GitHub 配置和真实个人签名已通过 CI #8 验证。仍待 Android 10/较新设备安装、官方订阅授权与真实回复、第二个同签名版本覆盖升级和数据留存。手机没有装过 Polaris，按首次安装流程处理。未推进气泡、群聊、语音或主题阶段。

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

## 新路径的在线验证（CI #7）

- 源码 `4d8ffbb21aaa428aa66efa5c18f1e349ae1ab9ab` 的 [CI #7](https://github.com/Qiufannn/polaris-local-first/actions/runs/43604676245) 四个作业均成功，包含新增公开测试向量签名/验签作业。
- 该 run 排队时尚未保存个人 Secret，实际恢复签名和 signed artifact 上传步骤全部 skipped。
- 秋凡随后报告手机保存完成；本次文档更新将触发新 run，实际个人签名结果以新 run 的步骤和产物为准。

## 首份真实个人签名（CI #8）

- [CI #8](https://github.com/Qiufannn/polaris-local-first/actions/runs/43605081112)，源码 `c6b0b7f91656840bafbccfd8848b97c02e7e3d69`。网页 489 个文件 / 3100 项测试通过；下载核对原生 XML 为 18 项，0 failures / errors / skipped。四个作业成功，恢复值签名、证书核对和 signed artifact 上传步骤均实际执行。
- [signed artifact](https://github.com/Qiufannn/polaris-local-first/actions/runs/43605081112/artifacts/14117853395)：`polaris-android-signed-8-1`。ZIP SHA256 `711692ff91fc8265881c3dab2b7c32a60c266d0e5cd97f05232dc5f35cbfdcc2`，与 GitHub digest 相同。
- APK 15,744,714 bytes，SHA256 `2c64d405d36892a98ead592be794cfc77cdabca065ca0abdfc173daf2a0110a8`；包名 `com.alyssa.polaris`、versionCode `8`、versionName `1.0-qiufann.8`。
- 公开个人证书 SHA256 `f703307d2ca9936df2bc928f03f3e49f9793c77d25ce24079e8baed75d80e3a5`，与公开测试证书不同。已独立运行 `apksigner verify --min-sdk-version 29 --verbose --print-certs` 成功，使用 v3 签名；所有 unsigned APK ZIP 条目字节均保持一致。
- ZIP 仅含 APK、校验值、源码回执和公开证书信息，未包含私钥或恢复值。源提交 SHA / run_id / versionCode 全部匹配。
- 本次将上面公开证书固定到 `tools/android-signing/expected-certificate.sha256`，触发下一次 CI 验证同签名更新。该第二次构建的实际结果另行记录；手机验收步骤见 [手机检查清单](device-check.md)。

## 固定证书后的第二版（CI #9）

- [CI #9](https://github.com/Qiufannn/polaris-local-first/actions/runs/43605621872)，源码 `5539d24524e243569e7eacf35e96774d1558bf9f`。四个作业全部成功，网页 489 个文件 / 3100 项测试，原生 XML 18 项、0 failures / errors / skipped。
- [signed artifact](https://github.com/Qiufannn/polaris-local-first/actions/runs/43605621872/artifacts/14117578766)：`polaris-android-signed-9-1`，ZIP SHA256 `4ce983b20e9d223fc030bf324f019d3e815e93d7d675465d41b172bf6d2c7a1b`，与 GitHub digest 一致。
- APK 15,744,714 bytes，SHA256 `e381867628d388fff5a67be780fc0df83f8acaac6828ac02d9cef43d32521419`；同包名、versionCode `9`、versionName `1.0-qiufann.9`。
- 已独立验签，证书与第 8 版及仓库固定值 `f703307d2ca9936df2bc928f03f3e49f9793c77d25ce24079e8baed75d80e3a5` 一致；正式签名入口和上传前两处固定值核对均实际通过。源码回执、run_id、APK / ZIP 校验值和 unsigned payload 字节均已核对。
- 这证明 CI 能恢复同一签名身份并生成递增版本；尚不能替代手机覆盖升级。两份包已交付，按 [手机检查清单](device-check.md) 先用第 8 版建立聊天，再用第 9 版直接覆盖安装，实际结果仍待本人报告。
- 本次回填仅更新文档并使用 `[skip ci]`，没有改变已验证应用源码或签名工具。未进入后续功能阶段。

## 最新错误修正版（CI #10）

手机首条回复报错后，已生成并独立验签第 10 版；同一固定证书，versionCode 10。完整回执与当前下载入口见 [响应格式修正记录](stream-response-fix.md)。3100 项网页和 25 项原生测试通过，手机实际回复结果仍待反馈。
