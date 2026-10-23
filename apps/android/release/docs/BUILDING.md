# Android 构建与签名

需要 Python 3.11+、JDK 17、Android SDK 35 / Build Tools 35.0.0、NDK r27d（27.3.13750724）、Rust 1.96.0（clippy、rustfmt 与两个 Android target）、CMake 3.31.6、Ninja 1.11.1.4。Gradle Wrapper 固定 8.11.1 与 SHA-256。安装 SDK 需自行接受对应许可。

```sh
rustup target add aarch64-linux-android x86_64-linux-android
python -m pip install cmake==3.31.6 ninja==1.11.1.4
python apps/android/release/build.py --sdk /path/to/sdk --ndk /path/to/ndk --java /path/to/jdk --ninja /path/to/ninja
```

Windows 使用同一 Python 入口。默认使用 PATH 的 Cargo；本项目原开发环境可额外传 `--project-rust --tools /path/to/verified-tools`。可通过 `--cmake`、`--ninja`、`--gradle` 指定工具。Windows 路径含空格时需加引号。

手机发行使用 ARM64 基础 NEON；x86_64 沿用已验证模拟器的 AVX2/FMA/F16C 配置，不作为通用旧款 x86 设备安装包。

`prepare.py` 按 `dependencies.json` 下载、校验 sherpa AAR、llama.cpp b11750 源码和青简 data-v7；仅提取整句 lm.qj，不获取可选语音/文字权重。`--offline` 要求这些缓存及 Gradle 依赖已存在。第一次构建需要联网。SDK/编译器安装与模型下载不混为一谈。

构建执行 native fmt/test、两个 ABI 的 clippy/build、文字 JNI 编译以及 assembleRelease/lintRelease。输出位于 `apps/android/app/build/outputs/apk/release/`，**未签名、不可直接安装**。`--skip-checks` 仅适合本地排查，发布验证不用此参数。只构建一个 ABI 时不要把其他残留 ABI 当作本轮产物；公开发行构建使用默认两个 ABI。

正式签名使用 `apps/android/release/sign.ps1`。私钥放仓库外，环境变量为 `QINGSTUDY_KEYSTORE`、`QINGSTUDY_KEY_ALIAS`、`QINGSTUDY_STORE_PASSWORD`、`QINGSTUDY_KEY_PASSWORD`。脚本要求显式传入固定证书 SHA-256，拒绝调试包、调试证书与错误包名；密码不放命令行或源码。示意：

```powershell
./apps/android/release/sign.ps1 -Apk ./unsigned.apk -Out ./release.apk -Sdk $env:ANDROID_HOME -Java $env:JAVA_HOME -ExpectedCertificate <64位正式证书SHA256>
```

正式密钥和旧开发包迁移尚未确定；不要用临时测试密钥给用户发版，也不要让用户先卸载丢失数据。Android 9+ 的签名轮换需要证明链及兼容验证；Android 8 等旧设备需要另外处理。参见 [Android 签名文档](https://developer.android.com/tools/apksigner)。

本地证据与 CI 实际执行结果应分开记录。本次准备的 CI 是手动、只读、不签名、不发布的构建模板，推上 GitHub 后需实际跑通，不能把模板存在当作 CI 已通过。
