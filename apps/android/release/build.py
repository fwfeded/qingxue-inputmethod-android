#!/usr/bin/env python3
# //! 从源码构建 unsigned release；工具路径由参数/环境提供，不创建签名密钥。
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
from prepare import ROOT, prepare


def run(command, env, cwd=ROOT):
    subprocess.run([str(x) for x in command], cwd=cwd, env=env, check=True)


def build(args):
    tools = args.tools.resolve()
    prepare(tools, args.offline)
    env = os.environ.copy()
    env.update(QINGSTUDY_TOOLS=str(tools), ANDROID_NDK_HOME=str(args.ndk.resolve()),
               ANDROID_HOME=str(args.sdk.resolve()), ANDROID_SDK_ROOT=str(args.sdk.resolve()),
               JAVA_HOME=str(args.java.resolve()), CARGO_TARGET_DIR=str(ROOT / "target"))
    windows = os.name == "nt"
    host = "windows-x86_64" if windows else "linux-x86_64"
    ndkbin = args.ndk.resolve() / "toolchains/llvm/prebuilt" / host / "bin"
    env["PATH"] = os.pathsep.join([str(args.java.resolve() / "bin"), str(ndkbin), env["PATH"]])
    if args.project_rust:
        env.update(CARGO_HOME=str(tools / "cargo"), RUSTUP_HOME=str(tools / "rustup"))
        cargo = tools / "cargo/bin/cargo.exe"
        hostbin = tools / "rustup/toolchains/1.96.0-x86_64-pc-windows-gnu/lib/rustlib/x86_64-pc-windows-gnu/bin"
        env["CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER"] = str(hostbin / "rust-lld.exe")
        env["CARGO_TARGET_X86_64_PC_WINDOWS_GNU_RUSTFLAGS"] = f"-C linker-flavor=ld.lld -C link-self-contained=yes -C dlltool={ndkbin}/llvm-dlltool.exe"
        alias = tools / "host-bin"
        alias.mkdir(exist_ok=True)
        shutil.copy2(ndkbin / "llvm-dlltool.exe", alias / "dlltool.exe")
        env["PATH"] = os.pathsep.join([str(alias), str(tools / "cargo/bin"), env["PATH"]])
    else:
        cargo = "cargo"
    manifest = ROOT / "apps/android/native/Cargo.toml"
    if not args.skip_checks:
        run([cargo, "fmt", "--manifest-path", manifest, "--", "--check"], env)
        run([cargo, "test", "--locked", "--manifest-path", manifest, "--", "--test-threads=1"], env)
    android = ROOT / "apps/android"
    for abi in args.abi:
        target = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}[abi]
        key = "CARGO_TARGET_" + target.upper().replace("-", "_")
        env[key + "_LINKER"] = str(ndkbin / (target + "26-clang" + (".cmd" if windows else "")))
        env[key + "_RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
        run([cargo, "clippy", "--locked", "--manifest-path", manifest, "--target", target, "--", "-D", "warnings"], env)
        run([cargo, "build", "--locked", "--manifest-path", manifest, "--release", "--target", target], env)
        dest = android / "app/src/main/jniLibs" / abi
        dest.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / "target" / target / "release/libqingjian_android.so", dest)
        native = android / "build/text-native" / abi
        # 与现有开发包一致：x86_64 面向已确认支持 AVX2 的模拟器，ARM64 使用基础 NEON。
        vector = ["-DGGML_SSE42=ON", "-DGGML_AVX=ON", "-DGGML_AVX2=ON", "-DGGML_FMA=ON", "-DGGML_F16C=ON"] if abi == "x86_64" else []
        run([args.cmake, "-S", android / "text-native", "-B", native, "-G", "Ninja",
             f"-DCMAKE_MAKE_PROGRAM={args.ninja}", "-DCMAKE_BUILD_TYPE=Release",
             f"-DCMAKE_TOOLCHAIN_FILE={args.ndk.resolve()}/build/cmake/android.toolchain.cmake",
             f"-DLLAMA_SOURCE={tools}/text-models/llama.cpp-b11750", f"-DANDROID_ABI={abi}",
             "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static",
             "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", *vector], env)
        run([args.cmake, "--build", native, "--target", "qingstudy_text", "-j", "3"], env)
        run([ndkbin / ("llvm-strip.exe" if windows else "llvm-strip"), "--strip-unneeded", native / "libqingstudy_text.so"], env)
        shutil.copy2(native / "libqingstudy_text.so", dest)
    gradle = args.gradle or android / ("gradlew.bat" if windows else "gradlew")
    command = [gradle, "--no-daemon", "--console=plain", "--max-workers=1",
               "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8",
               "-Pkotlin.compiler.execution.strategy=in-process", ":app:assembleRelease", ":app:lintRelease"]
    if args.offline:
        command.append("--offline")
    run(command, env, android)
    print("构建完成：app/build/outputs/apk/release/*-unsigned.apk；尚未签名或发布。")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--tools", type=Path, default=ROOT / ".tools")
    for key in ("sdk", "ndk", "java"):
        parser.add_argument("--" + key, type=Path, required=True)
    parser.add_argument("--cmake", default="cmake")
    parser.add_argument("--ninja", default="ninja")
    parser.add_argument("--gradle")
    parser.add_argument("--abi", nargs="+", choices=["arm64-v8a", "x86_64"], default=["arm64-v8a", "x86_64"])
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--project-rust", action="store_true", help="复用本项目 Windows GNU 工具链")
    parser.add_argument("--skip-checks", action="store_true")
    build(parser.parse_args())
