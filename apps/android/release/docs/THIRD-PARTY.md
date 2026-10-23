# 许可与来源清单

本清单用于发布审阅，不把不同数据与模型统一改成 GPL，也不替代完整许可正文。

| 内容 | 来源与许可 | 随包证据 |
| --- | --- | --- |
| 青简引擎及青学 Android 修改 | qingjian-team/qingjian，GPL-3.0-or-later | 根 LICENSE、源码 copyright、上游基线 |
| 整句数据 | 青简 data-v7，LCCC 来源及 CC BY-SA 4.0 要求 | app/src/main/notices/SENTENCE-MODEL.txt |
| 词库、释义、词汇等级、emoji | 各表来源不同，含 GPL、MIT、CC BY-SA、CEFR-J 与 Unicode | assets 中 README/LICENSE/NOTICE 及 app/src/main/notices/levels |
| sherpa-onnx / ONNX Runtime / Silero | Apache-2.0 / MIT / MIT，含传递依赖声明 | app/src/main/speech-licenses |
| llama.cpp、nlohmann、xxHash | MIT / MIT / BSD | app/src/main/notices/text |
| OkHttp、Okio | Apache-2.0 | app/src/main/notices/OkHttp-Okio-APACHE-2.0.txt |
| Catppuccin、Nord 配色 | MIT，保留原作者声明 | app/src/main/notices/themes.txt |
| 按键音效 | 独立合成，GPL-3.0-or-later | scripts/generate-feedback.py、main/assets/feedback |
| 可选 SenseVoice 权重 | FunASR Model License 1.1；不是运行库的 MIT/Apache 许可 | speech-licenses/SenseVoice-FunASR-MODEL-LICENSE.txt |
| 可选 Qwen2.5 权重 | Apache-2.0，以固定官方模型版本为准 | notices/text/models.txt、Qwen-APACHE-2.0.txt |

上表路径相对 `apps/android`，assets 相对仓库根。下载构建依赖的 URL 与 SHA-256 见 `release/dependencies.json`。模型权重不放源码或 APK；若未来提供镜像，需再次核定模型分发条件。

参考商业输入法只作为功能行为研究。公开快照排除参考 APK、反编译结果、用户截图、私有模型、录音、主题素材与其品牌。仍需在最终发行文件上完成素材来源核对和传递依赖清单审查，不能将本表视为已完成全部授权审计。
