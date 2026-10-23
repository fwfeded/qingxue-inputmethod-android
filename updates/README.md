# 青学 Android 更新源

仓库：[fwfeded/qingxue-inputmethod-android](https://github.com/fwfeded/qingxue-inputmethod-android)。

`index.json` 是 ECDSA P-256 / SHA-256 签名封装，`payload.json` 是供维护者核对的明文。
客户端只信任 APK 内固定公钥，先验证封装，再使用负载。当前列表为空，表示**尚无正式签名安装包发行**，不是下载服务错误。

每份清单最长有效 93 天，应在过期前重新签署。应用签名密钥与此更新清单密钥分别保管，私钥不得上传。

发版条目字段：`versionCode`、`versionName`、`channel`（stable/beta）、`abi`（arm64-v8a/x86_64）、`minSdk`、`url`、`bytes`、`sha256`、`certificateSha256`、`notes`。
URL 必须是本仓库的 `https://github.com/fwfeded/qingxue-inputmethod-android/releases/download/<tag>/<file>.apk`。
APK 必须不可调试，包名为 `dev.qingstudy.ime`，版本递增，与设备当前安装证书一致。更换证书不可用卸载清数据代替迁移。

使用 JDK 17 签署（路径由维护者指定）：

```sh
java apps/android/release/SignUpdateIndex.java sign /secure/update-index-private.pk8 updates/payload.json updates/index.json
```

此命令不发布文件；先校验 APK，再上传对应 Release，最后提交签名索引。模型仍单独按需下载。
