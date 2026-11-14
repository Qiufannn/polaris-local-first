# 手机首次安装与订阅连接验收

本清单用于秋凡手机手动验收。最初手机没有安装过 Polaris，目前已安装并打开，正在处理首条订阅消息报错。GitHub 下载使用 Via；与阿昼的会话使用 Edge。

## 下载入口

- 当前覆盖更新：[第 10 版 signed ZIP](https://github.com/Qiufannn/polaris-local-first/actions/runs/43607482701/artifacts/14119511124)，修正响应格式判断并增加安全错误分类。已有个人版直接覆盖安装，沿用账号和聊天，实际结果待反馈。
- 以下第 8 / 9 版保留为最初签名连续性验证记录；已有安装无需为测试而回退或重装旧版。

- 首次安装：[第 8 版 signed ZIP](https://github.com/Qiufannn/polaris-local-first/actions/runs/43605081112/artifacts/14117853395)。
- 覆盖升级：[第 9 版 signed ZIP](https://github.com/Qiufannn/polaris-local-first/actions/runs/43605621872/artifacts/14117578766)。
- 两版个人证书已下载独立验签并比对一致；都从 ZIP 解压后安装 `polaris.apk`。Actions artifact 保留 14 天。

## 安装和连接（已有安装直接覆盖更新）

1. 在 Via 打开指定 GitHub Actions run，找到带 `signed` 的 artifact，下载 ZIP 并解压。不要选择 `unsigned`。
2. 打开解压出的 `polaris.apk`，按 Android 正常安装提示操作；若系统拦截或报签名/解析错误，记录原提示并停止，不关闭系统防护来强行安装。
3. 启动 Polaris。打开设置 → API / 供应商，找到“ChatGPT 订阅”卡。
4. 点击 Continue with ChatGPT，在本人系统浏览器完成官方授权，再手动返回 Polaris。不要把授权网址、验证码、密码、token 或签名恢复值发到聊天。
5. 查看是否显示“已连接”“已授予订阅使用权限”，并出现模型。选择一个返回的订阅模型，点击“使用此订阅模型”。若没有模型或提示权限不足，报告普通错误文字，先不配置其他付费线路。
6. 回到聊天，新建一段对话并发送“请用一句中文回答：连接测试成功。”。检查回复是否完成，退出并重新打开 Polaris，再确认聊天与账号状态保留。

## 同签名覆盖升级

用当前第 10 版直接覆盖现有个人安装，不先卸载。确认第一段聊天仍在、账号连接仍有效，再发送第二条消息。这才构成手机上的覆盖升级和数据留存证据；CI 对两个 APK 的证书一致性检查不能替代此步骤。

## 验收记录

- CI 签名与来源核对：见 signing-setup.md。
- 手机安装并打开已由本人截图确认；已进入订阅发送路径，模型报告为“5.6sol”。首条回复失败，见 [响应格式修正记录](stream-response-fix.md)。官方授权完整验收、成功回复、重启留存、覆盖升级仍未完成；失败时仅记录状态与普通提示，不记录秘密值。

本清单不启动后续主题、气泡、群聊或语音阶段。
