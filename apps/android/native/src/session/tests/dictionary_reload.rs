//! 导入词库在现有会话及新会话生效；开关不污染学习设置。
use super::session;
use serde_json::json;

#[test]
fn dictionary_reload_and_reopen_preserve_enabled_state() {
    let mut engine = session();
    let root = std::env::temp_dir().join(format!("qingstudy-reload-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let settings = json!({"directory":root,"learning":false,"domains":[]}).to_string();
    engine.step("configure", &settings).unwrap();
    let source = root.join("星穹.tsv");
    std::fs::write(&source, "青学星穹\tqing xue xing qiong\t900000\n").unwrap();
    let imported =
        crate::dictionaries::manage(&root, "import", &json!({"path":source}).to_string()).unwrap();
    let id = imported["id"].as_str().unwrap();
    engine.step("configure", &settings).unwrap();
    assert_eq!(engine.engine.extra_dictionaries().len(), 1);
    assert_eq!(
        engine.step("input", "qingxuexingqiong").unwrap()["candidates"][0]["text"],
        "青学星穹"
    );
    assert_eq!(engine.step("space", "").unwrap()["commit"], "青学星穹");
    assert!(engine.engine.is_private());
    crate::dictionaries::manage(
        &root,
        "toggle",
        &json!({"id":id,"enabled":false}).to_string(),
    )
    .unwrap();
    engine.step("configure", &settings).unwrap();
    assert!(engine.engine.extra_dictionaries().is_empty());
    let mut reopened = session();
    reopened.step("configure", &settings).unwrap();
    assert!(reopened.engine.extra_dictionaries().is_empty());
    crate::dictionaries::manage(
        &root,
        "toggle",
        &json!({"id":id,"enabled":true}).to_string(),
    )
    .unwrap();
    reopened.step("configure", &settings).unwrap();
    assert_eq!(reopened.engine.extra_dictionaries().len(), 1);
    reopened
        .step(
            "configure",
            &json!({"directory":root,"learning":false,"domains":["idioms"]}).to_string(),
        )
        .unwrap();
    assert_eq!(
        reopened.engine.extra_dictionaries().len(),
        2,
        "内置与导入词库同时生效"
    );
    drop(engine);
    drop(reopened);
    std::fs::remove_dir_all(root).unwrap();
}
