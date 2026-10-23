#!/usr/bin/env python3
# //! 生成供人工审阅的源码快照和散列清单；不操作 Git 暂存区或远端。
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[3]
ROOT_FILES = {"Cargo.toml", "Cargo.lock", "LICENSE", ".gitignore", "rust-toolchain.toml"}
ANDROID_DIRS = ("apps/android/app/src/main/", "apps/android/app/src/androidTest/",
                "apps/android/native/", "apps/android/text-native/", "apps/android/gradle/",
                "apps/android/release/")
ANDROID_FILES = {"apps/android/" + name for name in (
    "build.gradle.kts", "app/build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
    "scripts/generate-feedback.py")}


def safe_path(name):
    path = Path(name)
    return not (path.is_absolute() or ".." in path.parts or any(p in {
        ".git", ".tools", "build", "target", "jniLibs", "__pycache__", ".gradle", ".kotlin"
    } for p in path.parts) or path.suffix.lower() in {
        ".apk", ".aar", ".so", ".onnx", ".gguf", ".jks", ".keystore", ".p12", ".pk8", ".log", ".pyc"
    } or path.name in {"local.properties", ".env"} or path.name.startswith("hs_err_"))


def selected(name, tracked):
    if not safe_path(name):
        return False
    if name in ROOT_FILES or name in ANDROID_FILES or name.startswith(ANDROID_DIRS):
        return True
    if name.startswith("crates/"):
        return True
    return name in tracked and (name.startswith(("assets/", "tools/", "apps/cli/", "apps/macos/", "apps/windows/", "apps/linux/"))
                                or name == "docs/design/landscape.md")


def export(destination):
    if destination.exists():
        raise RuntimeError("目标已存在；请使用新的目录，不覆盖或清空已有内容")
    tracked = set(subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0"))
    candidates = set(subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT).decode().split("\0"))
    selected_files = sorted(name for name in candidates if name and selected(name, tracked))
    findings = []
    for name in selected_files:
        path = ROOT / name
        if path.is_symlink() or not path.is_file():
            raise RuntimeError(f"非普通文件：{name}")
        if path.stat().st_size > 95 * 1024 * 1024:
            raise RuntimeError(f"源码中存在过大文件：{name}")
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeError:
            continue
        for pattern in (r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", r"gh[pousr]_[A-Za-z0-9]{30,}",
                        r"github_pat_[A-Za-z0-9_]{40,}", r"AKIA[A-Z0-9]{16}", r"(?i)C:[\\/]Users[\\/]corlin"):
            if re.search(pattern, text):
                findings.append({"file": name, "pattern": pattern})
    if findings:
        # 只报告路径与类型，不回显匹配内容或潜在凭据。
        print(json.dumps(findings, ensure_ascii=False, indent=2))
        raise RuntimeError("筛选扫描有待处理项；未导出")
    destination.mkdir(parents=True)
    for name in selected_files:
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / name, target)
    docs = ROOT / "apps/android/release/docs"
    for source in docs.glob("*.md"):
        shutil.copy2(source, destination / source.name)
    workflow = destination / ".github/workflows/android.yml"
    workflow.parent.mkdir(parents=True)
    shutil.copy2(ROOT / "apps/android/release/android-ci.yml.template", workflow)
    upstream = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip()
    files = [{"path": path.relative_to(destination).as_posix(), "bytes": path.stat().st_size,
              "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
             for path in sorted(destination.rglob("*")) if path.is_file()]
    report = {"status": "review-only-not-published", "upstream": "https://github.com/qingjian-team/qingjian",
              "upstream_commit": upstream, "files": files, "scope": "current source snapshot; no Git history or private artifacts"}
    (destination / "SOURCE-MANIFEST.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"files": len(files), "bytes": sum(f["bytes"] for f in files), "destination": str(destination)}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("destination", type=Path)
    export(parser.parse_args().destination.resolve())
