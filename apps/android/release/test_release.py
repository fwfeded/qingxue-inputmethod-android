# //! 发布材料的拒绝路径测试：私钥/实验材料、敏感文本与篡改缓存不得通过。
import hashlib
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import export
import prepare


class ReleaseBoundaryTests(unittest.TestCase):
    def test_build_entry_files_are_kept_without_git_tracking(self):
        for name in ("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradlew", "gradle/wrapper/gradle-wrapper.jar", "native/Cargo.toml", "native/Cargo.lock"):
            with self.subTest(name=name):
                self.assertTrue(export.selected("apps/android/" + name, set()))

    def test_private_and_generated_material_never_selected(self):
        for path in (".tools/reference/base.apk", "output/screenshots/phone.jpg",
                     "apps/android/app/src/main/jniLibs/arm64-v8a/test.so",
                     "apps/android/release/key.jks", "apps/android/native/target/test.json",
                     "apps/android/app/src/main/.env", "../escape.kt"):
            with self.subTest(path=path):
                self.assertFalse(export.selected(path, {path}))

    def test_untracked_experiment_not_public(self):
        self.assertFalse(export.selected("tools/text-model-lab/customer.wav", set()))
        self.assertFalse(export.selected("apps/android/text-lab/src/Main.kt", set()))
        self.assertTrue(export.selected("apps/android/app/src/main/java/ime/Settings.kt", set()))
        self.assertTrue(export.selected("crates/qingjian-core/src/engine/habits/mod.rs", set()))

    def test_existing_output_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            marker = target / "keep.txt"
            marker.write_text("keep")
            with self.assertRaisesRegex(RuntimeError, "目标已存在"):
                export.export(target)
            self.assertEqual(marker.read_text(), "keep")

    def test_secret_stops_export_before_any_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "apps/android/release/accident.txt"
            source.parent.mkdir(parents=True)
            source.write_text("-" * 5 + "BEGIN PRIVATE KEY" + "-" * 5 + "\ntest-only-not-a-key")
            filename = source.relative_to(root).as_posix().encode() + b"\0"
            target = root / "review"
            with patch.object(export, "ROOT", root), patch.object(export.subprocess, "check_output", return_value=filename):
                with self.assertRaisesRegex(RuntimeError, "未导出"):
                    export.export(target)
            self.assertFalse(target.exists())

    def test_corrupted_cache_is_rejected_without_network(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sample.aar").write_bytes(b"tampered")
            entry = {"path": "sample.aar", "sha256": hashlib.sha256(b"expected").hexdigest()}
            with patch.object(prepare.urllib.request, "urlopen") as network:
                with self.assertRaisesRegex(RuntimeError, "缓存校验失败"):
                    prepare.fetch(entry, root, True)
                network.assert_not_called()

    def test_offline_missing_dependency_does_not_download(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(prepare.urllib.request, "urlopen") as network:
                with self.assertRaisesRegex(RuntimeError, "缺少缓存"):
                    prepare.fetch({"path": "missing.aar"}, Path(directory), True)
                network.assert_not_called()


if __name__ == "__main__":
    unittest.main()
