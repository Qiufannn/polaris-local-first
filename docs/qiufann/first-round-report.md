# 第一轮施工回执

日期：2026-11-14（中国时间）。范围严格限于施工纸条第 13 节；登录实现与其后所有阶段均未开始。

## 七项状态

1. **获取上游：完成。** 克隆 `Aevella/polaris-local-first`，前后两次 `git ls-remote upstream refs/heads/main` 均为 `a510f9ae68a70dead5ad344829466d3e5770f600`。上游提交标题 `Harden LocalData import and recovery safety (#6)`，提交时间 `2026-08-02T23:57:14+08:00`。
2. **受控仓库/分支：完成。** 初始 GitHub 连接对 fork 和创建个人仓库均返回 `403 Resource not accessible by integration`；随后秋凡本人创建 <https://github.com/Qiufannn/polaris-local-first>。现有连接仍有效，已成功推送定制分支；`origin` 接本人 fork，原本地裸仓库保留为 `local-backup`，`upstream` 接原作者且禁用 push。远端 `main` 与基线一致，定制仅在 `qiufann/chatgpt-plan-android`。
3. **基线检查：Web/TypeScript/测试/Android unsigned release 全部完成。** 未改动业务代码或 lockfile；不等于完成手机运行验收。
4. **APK CI：完成并通过在线验证。** 两个独立作业：现有类型/测试，以及 Web → Capacitor → unsigned release APK → artifact。第二次在线运行全部通过；产物已下载并核对源提交、SHA256、ZIP 完整性、包信息和未签名状态。无签名、无 Secrets、无发布动作。
5. **代码审阅：完成。** Provider、Responses、Android 网络/SQLite 桥、普通配置与备份管线均已审阅。具体差距见 `chatgpt-plan-design.md`。
6. **登录文件清单与最小顺序：完成方案，尚未实施。** 见 `chatgpt-plan-design.md` 的新建/修改文件表和六个最小步骤。
7. **不索取/写入凭证：遵守。** 未向秋凡索取或新建写入 API Key、OAuth token、密码或签名密钥；未运行授权、推理、签名、debug keystore 生成或收费线路测试。GitHub 请求仅使用平台已配置的连接。

## 实际命令与结果

| 命令 | 结果 |
| --- | --- |
| `git clone --origin upstream --branch main https://github.com/Aevella/polaris-local-first.git` | 成功；工作树初始干净 |
| `git ls-remote upstream refs/heads/main` | 与本地 main/HEAD 的完整 SHA 一致 |
| `npm ci --no-audit --no-fund`（默认缓存） | 环境失败：默认 HOME 下 npm cache 不可写；不是代码基线失败 |
| 同命令，缓存改到工作区 | 环境失败：Electron 桌面二进制下载直连失败 |
| `ELECTRON_SKIP_BINARY_DOWNLOAD=1 npm ci --no-audit --no-fund`，工作区 npm cache | 通过，419 packages；保留 lockfile，无改依赖 |
| `npm run typecheck` | 通过，exit 0 |
| `npm run typecheck:extra` | 通过，exit 0 |
| `npm run typecheck:worker` | 通过，exit 0 |
| `npm test -- --maxWorkers=2` | **487 个文件 / 3083 项测试全部通过**，exit 0，170.76 秒 |
| `npm run build` | 通过，exit 0，Vite 生产构建成功；有超过 500 kB chunk 警告 |
| `npx --no-install cap sync android` | 通过，exit 0，检测到 8 个 Android 插件 |
| `./gradlew --no-daemon --console=plain --max-workers=2 assembleRelease`（本机额外指定系统 CA truststore） | **通过**，exit 0，2 分 43 秒，446 个任务（424 executed / 22 up-to-date） |
| `actionlint .github/workflows/qiufann-android.yml` | 通过，actionlint 1.7.12，exit 0 |
| `git diff --check` | 通过；Capacitor 自动生成的 config.xml 空白差异已恢复，不纳入定制提交 |

环境：Node 24.22.1、npm 11.9.0、JDK 21、Gradle wrapper 8.14.3、Android platform 36、Build Tools 35.0.0。CI 同样选择 Node 24 / JDK 21。初始环境只有 Java 运行时，编译器另在工作区准备；初次 Android 失败不能算作上游 Java 编译错误。

Android 构建使用继承的网络代理及环境已有的系统 CA truststore，未禁用证书校验；未把代理或机器专用路径写入项目/CI。设置工作区 Gradle cache 和 Android 用户目录。环境配置试验失败的日志保留，与最终基线结果区分。

## 在线 CI 验证

- [第一次运行 #1](https://github.com/Qiufannn/polaris-local-first/actions/runs/43584685149)，源提交 `10a471427e6bce0a224dd4a2af551299de83b70a`：类型与测试作业通过；Android 作业在 setup-android 默认安装已退役的 `tools` 包时失败，尚未到应用编译。这是实际失败记录，保留可查。
- 修正：指定命令行工具 `13114758`，仅安装 `platform-tools`，静默接受 SDK 许可证；本地 actionlint 通过，提交 `012743cdb7220e6e381eb6264fc4c850e29fa28c`。
- [第二次运行 #2](https://github.com/Qiufannn/polaris-local-first/actions/runs/43584821521)：源提交 `012743cdb7220e6e381eb6264fc4c850e29fa28c`，由定制分支 push 自动触发，**最终 conclusion=success，两个作业均 success**。三组类型检查通过，487 个测试文件 / 3083 项测试通过（47.94 秒）；Android release 构建通过（1 分 35 秒，446 个任务全部执行）。
- [在线 artifact：polaris-android-unsigned-2-1](https://github.com/Qiufannn/polaris-local-first/actions/runs/43584821521/artifacts/14110211143)。包含 `polaris-unsigned.apk`、`SHA256SUMS`、`build-info.txt`。已实际下载并验证全部文件、来源 SHA 和完整性，未签名状态符合预期；不是仅根据上传步骤推测。
- 下载 ZIP 为 14,043,237 bytes，SHA256 `09db5372e60d35942b438a078b1e3ee8b7f2fd08e987bae4e35adada323d7f4f`，与 GitHub artifact digest 一致。内含 APK 为 15,412,816 bytes，SHA256 与下述本地构建完全一致。产物保留至 2026-11-28，过期后可由定制分支新 push 重新生成。
- 本回执的最终补充提交仅更新文档，使用 `[skip ci]` 避免为回填结果再次完整构建；上述在线验证对应的源码 SHA 明确为 `012743cdb7220e6e381eb6264fc4c850e29fa28c`。
- 尝试为手机网页手动运行设置定制默认分支时，GitHub API 返回 HTTP 403；默认分支仍是纯镜像 `main`。自动 push 触发已实际成立，手动 workflow_dispatch 的默认分支条件尚未满足；这不影响当前在线构建。

## APK 与未完成的验收

- 本地文件：`android/app/build/outputs/apk/release/app-release-unsigned.apk`。
- 大小：15,412,816 bytes。
- SHA-256：`95801535fc401a6f99d710773dfe2a1e0308e7f30cf464dca1b6473859f08138`。
- `aapt dump badging`：包名 `com.alyssa.polaris`，versionCode `1`，versionName `1.0`，minSdk `24`，targetSdk `36`；包配置保留上游。
- `apksigner verify --verbose`：exit 1，`DOES NOT VERIFY / Missing META-INF/MANIFEST.MF`，符合本轮未签名构建的预期，不是可安装 APK。
- 工作区扫描未发现新建 `.jks` / `.keystore` 文件。
- 在线 GitHub Actions artifact URL：<https://github.com/Qiufannn/polaris-local-first/actions/runs/43584821521/artifacts/14110211143>；已下载验证，并与本地产物区分来源。
- 未执行 Android 10/较新系统真机测试、首次启动、重启保留、系统杀进程或覆盖升级。未修改 versionCode 或签名策略。

当前没有检查出阻止 Web/Android 编译或单元测试通过的上游代码失败。既有构建警告包括 Web chunk 体积、Android flatDir/弃用 API；环境初次安装与证书错误已处理。另有静态观察：manifest 声明 `.PolarisTriggerAlarmReceiver`，上游 Android Java 源码中未发现该类；本轮未修改该既有声明，通知/闹钟运行能力仍待手机或后续原生检查，不能由 assembleRelease 成功推定。

## 修改文件与同步影响

- `README.md`：上游版本、同步日期、定制状态、远端阻挡、冲突候选。
- `.github/workflows/qiufann-android.yml`：npm 安装、类型检查、测试、Web build、Capacitor sync、Android release APK、SHA256 与构建回执上传。
- `docs/qiufann/upstream-sync.md`：origin/upstream、镜像 main、合并更新策略、默认分支与手动 CI 触发条件、未签名边界。
- `docs/qiufann/chatgpt-plan-design.md`：当前代码审阅、拟议改动文件与最小顺序、官方参考、验收条件。
- `docs/qiufann/first-round-report.md`：本回执。

无业务代码、数据库、角色/聊天/主题格式、provider 原线路或包签名变更；AGPL-3.0-only 与原作者内容保留。
未来合并冲突候选仅上述 README、同名 workflow 和 docs/qiufann 文件；本轮没有实际合并冲突。

## 手机端与下一步边界

当前不要求秋凡安装或卸载 App，不要求登录，不要求填写密钥。
秋凡已完成手机创建 fork 并提供链接；改过 GitHub 密码后，当前连接仍能访问该仓库并读取写入权限，无需向聊天发送密码或重新提供 token。

本轮已补齐远端分支和在线 Actions，并停止在七项范围。可下载安装的签名 APK、真实手机重启留存、同签名升级和订阅授权闭环全部未验收。未签名 APK 即使成功构建也不能直接安装；阶段 A 整体验收不能标为完成。

本轮停止点：把实际证据和方案交给秋凡；后续登录实现、签名与后续阶段都等待她新的指示。
