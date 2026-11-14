# 个人安卓分支与上游同步

上游：<https://github.com/Aevella/polaris-local-first>，许可证 AGPL-3.0-only。

## 当前状态

- 基线 `main`：`a510f9ae68a70dead5ad344829466d3e5770f600`。
- 定制分支：`qiufann/chatgpt-plan-android`；本轮只增加 CI 和文档。
- `upstream` 始终指向原仓库；`main` 只做上游镜像。
- 本地已把 `upstream` 的 push URL 设为 `DISABLED`，防止误推原作者仓库；fetch URL 保留原址。
- 当前 `origin` 是 <https://github.com/Qiufannn/polaris-local-first.git>。初始 GitHub 建仓请求被集成以 HTTP 403 拒绝，随后秋凡本人创建 fork，现有连接已成功读取该仓库和权限，无需提供新凭证。
- 原本的本地裸仓库保留为 `local-backup`。远端 `main` 已核对与基线一致；定制提交只推独立分支，在线 Actions 结果见第一轮回执。

## 已完成的 fork 接入与分支推送方式

秋凡已在手机浏览器创建本人 fork，代码会话已接入 `origin`。以下是同一分支策略的推送方式：

```sh
git remote set-url origin https://github.com/Qiufannn/polaris-local-first.git
git fetch origin
# 先核实 origin/main 没有额外改动；若已分叉，先停下审阅。
git push -u origin main
git push -u origin qiufann/chatgpt-plan-android
```

当前本人 fork 的默认分支仍为 `main`；修改默认分支的 API 被现有集成以 HTTP 403 拒绝，未强行改变。GitHub 的手动 workflow_dispatch 要求 workflow 文件在默认分支，因此当前使用已验证可触发的定制分支 push；不要宣称网页手动运行按钮已可用。若日后本人把默认分支设为 `qiufann/chatgpt-plan-android`，`main` 仍可保持纯上游镜像，并可启用网页手动运行。

无须向会话发送 token 或密码。不要重建已有 fork；重启任务时先核对远端分支和本地差异。

## 后续同步顺序

工作区必须干净。先 fetch，镜像分支只允许快进；再 merge 到定制分支。任何冲突都保留双方内容进行审阅，不使用 force push、hard reset 或整体覆盖。

```sh
git status --short
git fetch upstream main
git switch main
git merge --ff-only upstream/main
git push origin main
git switch qiufann/chatgpt-plan-android
git merge --no-edit main
```

合并后按 CI 顺序验证类型、测试、Web 构建、Capacitor sync 和 Android `assembleRelease`，更新 README 的同步 SHA、时间与冲突列表，再推送定制分支。验证失败不发布手机更新。

## CI 和签名边界

`.github/workflows/qiufann-android.yml` 提供手动、定制分支 push 和 PR 触发。
CI 使用 npm lockfile、Node 24、JDK 21、SDK 36 / Build Tools 35.0.0。
Android 安装动作显式选择命令行工具 `13114758` 和 `platform-tools`，避免默认安装已退役的 `tools` 包；这是第一次在线运行暴露的问题，已在 CI 配置中修正。
`baseline` 和 `android` 是独立作业：测试失败仍会显示失败，Android 作业可单独生成检查产物。
基线作业内检查顺序执行，首个失败会使后续步骤跳过；完整本地基线以交付报告为准。

本轮只执行 `assembleRelease`，保留上游 applicationId、versionCode 和 versionName；不运行 `assembleDebug`，不生成 debug keystore，不配置 signingConfig，不读取或引用 Secrets。产物名 `polaris-android-unsigned-<run>-<attempt>`，含未签名 APK、SHA256SUMS 和源提交回执，保留 14 天。

**未签名 APK 不能直接安装**。后续取得秋凡确认后，再单独规划稳定签名、递增 versionCode、手机安装和同签名覆盖升级验证。本轮没有完成阶段 A 的手机验收；不得把未签名构建说成可安装发布。

潜在同步冲突：README.md；同名 workflow 若上游后来添加；`docs/qiufann/` 同名文件。当前没有修改业务、数据格式或原生运行时代码。
