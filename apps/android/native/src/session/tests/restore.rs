//! 活会话跨恢复切换时不得回写，重新配置必须加载当前代。
use super::session;
use serde_json::json;

#[test]
fn stale_session_cannot_overwrite_restored_learning() {
    let root = std::env::temp_dir().join(format!("qingstudy-live-restore-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    std::fs::write(root.join("frequency.tsv"), "开发\t7\n").unwrap();
    let backup = root.join("backup.json");
    crate::learning_backup::export(&root, &backup).unwrap();
    let configure = json!({"directory":root,"learning":true}).to_string();
    let mut old = session();
    old.step("configure", &configure).unwrap();
    old.step("input", "kaifa").unwrap();
    old.step("space", "").unwrap();
    let previous = std::fs::read(root.join("frequency.tsv")).unwrap();
    crate::learning_backup::restore(&root, &backup).unwrap();
    let active = crate::learning_backup::active_directory(&root).unwrap();
    old.step("input", "kaifa").unwrap();
    old.step("space", "").unwrap();
    old.step("flush", "").unwrap();
    assert_eq!(std::fs::read(root.join("frequency.tsv")).unwrap(), previous);
    assert_eq!(
        std::fs::read_to_string(active.join("frequency.tsv")).unwrap(),
        "开发\t7\n"
    );
    old.step("configure", &configure).unwrap();
    old.step("input", "kaifa").unwrap();
    old.step("space", "").unwrap();
    assert!(
        std::fs::read_to_string(active.join("frequency.tsv"))
            .unwrap()
            .contains("开发\t8\n")
    );
    let mut reopened = session();
    reopened.step("configure", &configure).unwrap();
    assert_eq!(
        old.engine.learner().weight("开发"),
        reopened.engine.learner().weight("开发")
    );
    std::fs::remove_dir_all(root).unwrap();
}
