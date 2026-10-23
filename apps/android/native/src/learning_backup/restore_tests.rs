//! 恢复必须先完整校验，切换后保留旧记录，失败不能改变当前记录。
use super::{active_directory, export, restore};
use serde_json::{Value, json};

#[test]
fn restore_isolated_generation_roundtrips_and_rejects_bad_rows() {
    let root = std::env::temp_dir().join(format!("qingstudy-restore-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    std::fs::write(root.join("frequency.tsv"), "你好\t12\n").unwrap();
    for (name, text) in [
        ("user-words.tsv", "青学星穹\tqing xue xing qiong\t100\n"),
        ("user-ngram.tsv", "<s>\t你好\t2\n<s>\t你好\t世界\t1\n"),
        ("user-choices.tsv", "nihao\t你好\t2\n"),
        ("user-english.tsv", "hello\t2\n"),
        ("user-typos.tsv", "tain\ttian\t1\n"),
        (
            "vocabulary.tsv",
            "en\thello\t2\t1\t0\t2026-10-15\t2026-10-16\n",
        ),
        ("usage.tsv", "2026-10-16\t12\t6\t0\t6\n"),
    ] {
        std::fs::write(root.join(name), text).unwrap();
    }
    let backup = root.join("backup.json");
    export(&root, &backup).unwrap();
    std::fs::write(root.join("frequency.tsv"), "旧记录\t9\n").unwrap();
    restore(&root, &backup).expect("有效备份必须能恢复");
    let active = active_directory(&root).unwrap();
    assert_ne!(active, root);
    assert_eq!(
        std::fs::read_to_string(active.join("frequency.tsv")).unwrap(),
        "你好\t12\n"
    );
    assert_eq!(
        std::fs::read_to_string(root.join("frequency.tsv")).unwrap(),
        "旧记录\t9\n"
    );
    let exported = root.join("new.json");
    export(&root, &exported).unwrap();
    let original: Value = serde_json::from_str(&std::fs::read_to_string(&backup).unwrap()).unwrap();
    let roundtrip: Value =
        serde_json::from_str(&std::fs::read_to_string(exported).unwrap()).unwrap();
    assert_eq!(original["files"], roundtrip["files"]);
    for (name, row) in [
        ("frequency.tsv", "你好\t-1\n"),
        ("user-words.tsv", "坏词\tNOT-PINYIN\t100\n"),
        ("user-ngram.tsv", "我\t想\t4294967295\n我\t去\t1\n"),
        ("user-choices.tsv", "nihao\t你好\tx\n"),
        ("user-english.tsv", "hello\t1\nHELLO\t2\n"),
        ("user-typos.tsv", "\tni\t1\n"),
        (
            "vocabulary.tsv",
            "en\thello\t2\t1\t0\t2026-02-30\t2026-03-01\n",
        ),
        (
            "usage.tsv",
            "2026-10-16\t1\t1\t0\t1\n2026-10-16\t1\t1\t0\t1\n",
        ),
    ] {
        let mut bad = original.clone();
        bad["files"][name] = json!(row);
        std::fs::write(&backup, bad.to_string()).unwrap();
        assert!(restore(&root, &backup).is_err(), "应拒绝 {name}");
        assert_eq!(active_directory(&root).unwrap(), active);
    }
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn restore_rejects_incomplete_duplicate_and_oversized_documents_before_writing() {
    let root =
        std::env::temp_dir().join(format!("qingstudy-restore-schema-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let source = root.join("backup.json");
    export(&root, &source).unwrap();
    let original = std::fs::read_to_string(&source).unwrap();
    let document: Value = serde_json::from_str(&original).unwrap();
    let mut malformed = Vec::new();
    let mut missing = document.clone();
    missing["files"]
        .as_object_mut()
        .unwrap()
        .remove("usage.tsv");
    malformed.push(missing.to_string());
    let mut unknown = document.clone();
    unknown["files"]["../settings.json"] = json!("bad");
    malformed.push(unknown.to_string());
    malformed.push(original.replace("\"version\":1", "\"version\":1,\"version\":1"));
    malformed.push(original.replace(
        "\"usage.tsv\":null",
        "\"usage.tsv\":null,\"usage.tsv\":null",
    ));
    let mut wrong_type = document.clone();
    wrong_type["files"]["usage.tsv"] = json!([]);
    malformed.push(wrong_type.to_string());
    let mut oversized = document;
    oversized["files"]["frequency.tsv"] = json!("x".repeat(4 * 1024 * 1024 + 1));
    malformed.push(oversized.to_string());
    for bad in malformed {
        std::fs::write(&source, bad).unwrap();
        assert!(restore(&root, &source).is_err());
        assert!(!root.join("generations").exists());
        assert_eq!(active_directory(&root).unwrap(), root);
    }
    std::fs::write(&source, [0xff, 0xfe]).unwrap();
    assert!(restore(&root, &source).is_err());
    std::fs::write(root.join("active-generation"), "restore-../../outside").unwrap();
    assert!(active_directory(&root).is_err());
    std::fs::remove_dir_all(root).unwrap();
}
