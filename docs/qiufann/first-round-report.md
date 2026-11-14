# 第一轮施工回执

日期：2026-11-14（中国时间）。范围严格限于施工纸条第 13 节；登录实现与其后所有阶段均未开始。

## 七项状态

1. **获取上游：完成。** 克隆 `Aevella/polaris-local-first`，前后两次 `git ls-remote upstream refs/heads/main` 均为 `a510f9ae68a70dead5ad344829466d3e5770f600`。上游提交标题 `Harden LocalData import and recovery safety (#6)`，提交时间 `2026-08-02T23:57:14+08:00`。
2. **受控仓库/分支：本地完成，GitHub 受阻。** 创建本地受控裸仓库 `origin`，`upstream` 指向原作者；`main` 保留原始基线，定制放在 `qiufann/chatgpt-plan-android`。当前 GitHub 连接对 fork 和创建个人仓库均返回 `403 Resource not accessible by integration`。没有远端 fork/个人仓库，不能声称远端托管已完成。
3. **基线检查：Web/TypeScript/测试/Android unsigned release 全部完成。** 未改动业务代码或 lockfile；不等于完成手机运行验收。
4. **APK CI：配置完成并通过静态验证；在线执行受第 2 项阻挡。** 两个独立作业：现有类型/测试，以及 Web → Capacitor → unsigned release APK → artifact。无签名、无 Secrets、无发布动作。GitHub 在线运行结果与 artifact URL 暂无。
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

## APK 与未完成的验收

- 本地文件：`android/app/build/outputs/apk/release/app-release-unsigned.apk`。
- 大小：15,412,816 bytes。
- SHA-256：`95801535fc401a6f99d710773dfe2a1e0308e7f30cf464dca1b6473859f08138`。
- `aapt dump badging`：包名 `com.alyssa.polaris`，versionCode `1`，versionName `1.0`，minSdk `24`，targetSdk `36`；包配置保留上游。
- `apksigner verify --verbose`：exit 1，`DOES NOT VERIFY / Missing META-INF/MANIFEST.MF`，符合本轮未签名构建的预期，不是可安装 APK。
- 工作区扫描未发现新建 `.jks` / `.keystore` 文件。
- 在线 GitHub Actions artifact URL：**无，远端建仓受阻**。本地产物不能冒充在线 CI artifact。
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
最短可解阻动作是在手机 GitHub 中为上游创建本人 fork 并提供仓库链接；若现有连接无权写该仓库，需要在 GitHub 插件设置里授权该仓库，仍不需要把 token 发到聊天。

远端未解阻时，本轮保留在本地受控仓库。在线 Actions 链接、可下载安装的签名 APK、真实手机重启留存、同签名升级和订阅授权闭环全部未验收。未签名 APK 即使成功构建也不能直接安装；阶段 A 整体验收不能标为完成。

本轮停止点：把实际证据和方案交给秋凡；后续登录实现、签名与后续阶段都等待她新的指示。
