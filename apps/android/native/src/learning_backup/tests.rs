//! 学习备份只包含允许的表，失败不覆盖目标文件。
use super::export;
use serde_json::Value;

#[test]
fn snapshot_waits_for_a_complete_saved_revision() {
    let root = std::env::temp_dir().join(format!("qingstudy-backup-lock-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let guard = std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(root.join(".snapshot.lock"))
        .unwrap();
    guard.lock().unwrap();
    std::fs::write(root.join("frequency.tsv"), "新版\t2\n").unwrap();
    let thread_root = root.clone();
    let (tx, rx) = std::sync::mpsc::channel();
    let worker = std::thread::spawn(move || {
        tx.send(export(&thread_root, &thread_root.join("backup.json")))
            .unwrap();
    });
    let waiting = rx
        .recv_timeout(std::time::Duration::from_millis(150))
        .is_err();
    std::fs::write(root.join("usage.tsv"), "2026-10-16\t2\t1\t0\t1\n").unwrap();
    drop(guard);
    worker.join().unwrap();
    assert!(waiting, "不能读取保存到一半的数据");
    rx.recv().unwrap().unwrap();
    let snapshot: Value =
        serde_json::from_str(&std::fs::read_to_string(root.join("backup.json")).unwrap()).unwrap();
    assert_eq!(snapshot["files"]["frequency.tsv"], "新版\t2\n");
    assert_eq!(snapshot["files"]["usage.tsv"], "2026-10-16\t2\t1\t0\t1\n");
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn snapshot_preserves_saved_tables_and_excludes_unrelated_files() {
    let root = std::env::temp_dir().join(format!("qingstudy-backup-{}", std::process::id()));
    std::fs::create_dir_all(root.join("dicts")).unwrap();
    std::fs::write(root.join("frequency.tsv"), "你好\t12\n").unwrap();
    std::fs::write(
        root.join("vocabulary.tsv"),
        "en\thello\t2\t1\t1\t2026-10-16\t2026-10-16\n",
    )
    .unwrap();
    std::fs::write(root.join("settings.json"), "not a learning table").unwrap();
    std::fs::write(root.join("input-log.jsonl"), "do not export").unwrap();
    std::fs::write(root.join("dicts/private.qj"), "not included").unwrap();
    let destination = root.join("snapshot.json");
    let result = export(&root, &destination).expect("learning export must be supported");
    assert_eq!(result["files"], 2);
    let snapshot: Value =
        serde_json::from_str(&std::fs::read_to_string(destination).unwrap()).unwrap();
    assert_eq!(snapshot["format"], "qingstudy-learning-backup");
    assert_eq!(snapshot["version"], 1);
    let files = snapshot["files"].as_object().unwrap();
    assert_eq!(files.len(), 8);
    assert_eq!(files["frequency.tsv"], "你好\t12\n");
    assert_eq!(
        files["vocabulary.tsv"],
        "en\thello\t2\t1\t1\t2026-10-16\t2026-10-16\n"
    );
    assert!(files["usage.tsv"].is_null());
    assert!(!files.contains_key("settings.json"));
    assert!(!files.contains_key("input-log.jsonl"));
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn corrupt_or_oversized_table_does_not_replace_backup() {
    let root =
        std::env::temp_dir().join(format!("qingstudy-backup-invalid-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let destination = root.join("snapshot.json");
    std::fs::write(&destination, "existing backup").unwrap();
    std::fs::write(root.join("frequency.tsv"), [0xff, 0xfe]).unwrap();
    assert!(export(&root, &destination).is_err());
    assert_eq!(
        std::fs::read_to_string(&destination).unwrap(),
        "existing backup"
    );
    std::fs::write(root.join("frequency.tsv"), vec![b'x'; 4 * 1024 * 1024 + 1]).unwrap();
    assert!(export(&root, &destination).is_err());
    assert_eq!(
        std::fs::read_to_string(&destination).unwrap(),
        "existing backup"
    );
    std::fs::remove_dir_all(root).unwrap();
}
