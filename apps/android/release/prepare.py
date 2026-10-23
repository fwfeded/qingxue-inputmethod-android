#!/usr/bin/env python3
# //! 下载并校验构建输入；不下载语音或文字模型权重。
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[3]


def digest(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fetch(entry, tools, offline):
    path = tools / entry["path"]
    if not path.is_file():
        if offline:
            raise RuntimeError(f"缺少缓存：{entry['path']}")
        path.parent.mkdir(parents=True, exist_ok=True)
        partial = path.with_suffix(path.suffix + ".partial")
        with urllib.request.urlopen(entry["url"], timeout=60) as response, open(partial, "wb") as out:
            shutil.copyfileobj(response, out)
        if digest(partial) != entry["sha256"]:
            raise RuntimeError(f"下载校验失败：{entry['path']}")
        partial.replace(path)
    if digest(path) != entry["sha256"]:
        raise RuntimeError(f"缓存校验失败：{entry['path']}")
    print(f"SHA-256 OK: {entry['path']}", flush=True)
    return path


def prepare(tools, offline):
    lock = json.loads(Path(__file__).with_name("dependencies.json").read_text())
    inputs = {key: fetch(value, tools, offline) for key, value in lock.items() if isinstance(value, dict)}
    # 每次覆盖 archive 内的源文件，防止缓存里的手工修改被当作固定版本。
    with zipfile.ZipFile(inputs["llama"]) as archive:
        for item in archive.infolist():
            parts = PurePosixPath(item.filename).parts
            if not parts or parts[0] != "llama.cpp-b11750" or ".." in parts or "\\" in item.filename:
                raise RuntimeError("源码压缩包路径异常")
            target = tools / "text-models" / item.filename
            if item.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                data = archive.read(item)
                if not target.is_file() or target.read_bytes() != data:
                    target.write_bytes(data)
    with tarfile.open(inputs["data"]) as archive:
        member = archive.getmember("data/generated/lm.qj")
        if not member.isfile():
            raise RuntimeError("整句数据不是普通文件")
        target = ROOT / member.name
        target.parent.mkdir(parents=True, exist_ok=True)
        with archive.extractfile(member) as source, open(target, "wb") as out:
            shutil.copyfileobj(source, out)
    print("已准备运行库、源码与整句数据；未下载语音/文字模型。", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--tools", type=Path, default=Path(os.environ.get("QINGSTUDY_TOOLS", ROOT / ".tools")))
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    prepare(args.tools.resolve(), args.offline)
