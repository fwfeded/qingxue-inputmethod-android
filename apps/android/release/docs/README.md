# 青学输入法 · qingxue-inputmethod-android

打中文，也认识一个英文词。青学是基于 [青简](https://github.com/qingjian-team/qingjian) Rust 引擎的独立 Android 输入法，保留候选英文释义、本地学习与词库管理。

当前源码版本为 Android 0.25.0-beta（versionCode 36）。[公开仓库](https://github.com/fwfeded/qingxue-inputmethod-android)提供整理后的源码；尚未发布正式签名 APK，不要把未签名构建产物当作可安装正式版。

已完成 [GitHub Linux 构建与 Lint 验证](https://github.com/fwfeded/qingxue-inputmethod-android/actions/runs/39923833321)，产出两个架构的未签名构建材料。本地模拟器已验证签名索引、真实系统安装与取消、断网恢复，以及英文释义和学习排序；不代表实体手机或正式签名迁移已验证。

支持拼音与候选、符号表情、主题和按键反馈、个人学习、语音输入、文字整理核对/采用/撤回。语音与文字模型按需下载，不预装在 APK 内。系统语音服务可用性随手机而异；自行配置的电脑语音服务依赖电脑在线。

目前不应宣称功能全部等同于任何商业输入法。长段落文字整理可能误改，必须核对原文；手机震感、耗电、厂商底部适配仍需真实设备反馈。已接入签名版本清单与应用更新入口：自动检查默认关闭，下载前确认，安装需系统确认。当前清单为空，等待正式 APK 发行；旧开发证书到正式证书的迁移尚未完成。

- [构建与签名](BUILDING.md)
- [许可与来源](THIRD-PARTY.md)
- [隐私说明](PRIVACY.md)
- [发布前核对](RELEASING.md)
- [应用更新说明](UPDATES.md)

源码采用 GPL-3.0-or-later，完整协议见 [LICENSE](LICENSE)。第三方数据、运行库、可选模型按各自许可。青学与青简、微信、豆包无官方隶属或授权关系。

上游基线：`7d88dd698b422baebe0b98735a45d58a29b4d9df`。保留上游 Rust workspace 中的其他平台源码以维持依赖结构；本快照只准备 Android 发行，不启动上游桌面发布流程。导出清单见 `SOURCE-MANIFEST.json`，它记录当前源码内容，不代替 Git 历史。
