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

在线完整回归和个人签名等待本次 push 的 CI。已固定个人证书不变；产物验证完成后回填下载入口。手机重试后才可判断原问题是否解决；如果显示新的安全分类，应继续按实际提示定位，不能把本次格式兼容性改进当作已确认服务根因。
