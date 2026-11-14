# 手机首条回复失败：响应格式识别修正

## 已知现象与证据范围

秋凡已在手机安装并打开 Polaris，发送“测试呀”“测试！！”后显示“订阅服务未返回流式响应，请稍后重试。”；当前模型由本人报告为“5.6sol”。实际模型 slug、安装版本、响应头和响应内容没有采集，因此不能据此断言服务真的返回了 SSE、JSON 错误或网页，也不能宣称推理已成功。

旧版原生 `ChatGptPlanHttpPlugin` 仅在 HTTP 2xx 且 Content-Type 不含 text/event-stream 时抛出该提示。它在读取正文前中止，无法区分响应头不准确的 SSE 与其他响应。官方[推理文档](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)及[预览限制](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)重新核对：仍要求官方 Responses 端点、store:false、stream:true，并读到 response.completed。

## 本次变更

- 增加纯 Java `ChatGptPlanResponse`。正确 SSE 媒体类型直接使用原流；其他类型最多探查 4096 字节，只在识别 SSE 字段后进入流处理，并原样回放已读字节。第一字段识别后立即交给流解析，不等满缓冲区或 EOF；兼容 UTF-8 BOM、注释和 event/data/id/retry 字段。
- 仍由原有订阅解析器要求 response.completed。普通 JSON 完成对象、HTML 页面、空数据和无法识别的格式不会成为成功回复，不会触发非流式或付费线路回退。
- 对 HTTP 2xx 内的 JSON 错误，仅将明确列入白名单的额度/授权错误映射成固定中文提示；其他情况仅显示 HTTP 状态和固定响应类别。不回传服务正文、任意错误信息、响应头、账号信息或网址，也不将其写入日志或备份。
- 保留既有账号、凭证文件和聊天数据库；不改 OAuth 注册、模型目录、签名身份或 API 线路配置。

## 本地验证

`POLARIS_ANDROID_VERSION_CODE=10` 的 testReleaseUnitTest + assembleRelease 在 51 秒内成功。原生 25 项，0 failures / errors / skipped；其中新增 7 项覆盖正确媒体类型、错标/缺失媒体类型真实 SSE 的字节完整性、BOM/注释、首字段立即返回、JSON 错误白名单和信息不外泄、拒绝 JSON/HTML/空响应，以及探查上限。git diff --check 通过。

手机重试后才可判断原问题是否解决；如果显示新的安全分类，应继续按实际提示定位，不能把本次格式兼容性改进当作已确认服务根因。

## 第 10 版在线回执

- [CI #10](https://github.com/Qiufannn/polaris-local-first/actions/runs/43607482701)，源码 `445da649b76b7bec4733b241ab9d39969e6d8655`，四个作业成功。网页 489 个文件 / 3100 项测试；下载核对原生 XML 25 项，0 failures / errors / skipped。实际个人签名与固定证书核对均执行成功。
- [覆盖更新用 signed ZIP](https://github.com/Qiufannn/polaris-local-first/actions/runs/43607482701/artifacts/14119511124)，名称 `polaris-android-signed-10-1`。ZIP SHA256 `0faf55d4e61c5f5d88a89365f8fb66c16d1ae357d31cd4da313fdd3e891b9dc3`，与 GitHub digest 一致。
- APK 15,761,098 bytes，SHA256 `4b01029fedf0d0674d0a7253984489edaa245775cfe080de9c6b3332b9659b46`；包名 `com.alyssa.polaris`、versionCode `10`、versionName `1.0-qiufann.10`。
- 下载后独立 apksigner 验签通过；公开证书 SHA256 仍为 `f703307d2ca9936df2bc928f03f3e49f9793c77d25ce24079e8baed75d80e3a5`，与第 8 / 9 版一致。源码回执、run_id、ZIP / APK 校验值与所有 unsigned payload 条目字节均核对通过。
- 手机操作：解压后打开 polaris.apk 覆盖现有个人版本，不先卸载；沿用已有账号和原聊天，发送一条短消息。若仍失败，只反馈新提示文字。当前未确认本版在本人手机上成功回复。
- 本次回填仅更新文档，使用 [skip ci]；没有继续其他功能阶段，也未索取或接收任何账号凭证或签名恢复值。

## 手机回复成功反馈

秋凡在第 10 版交付后反馈“很完美”，并提供手机截图：原有两条失败消息仍显示，新消息“测试测试呀～”收到完整中文回复“收到啦，测试成功——这次信号很清楚～”；界面显示模型 `gpt-5.6-sol`。据此确认手机上的中文订阅回复已实际成功。截图没有展示 App 版本页，未独立核对设备安装 versionCode，也没有响应头采样，故不据此断言服务器原始 MIME 类型。重启留存、取消/过期/撤销及其他系统版本仍需分别验证。
