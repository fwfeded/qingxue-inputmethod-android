//! 用真实 TSV、Rime、QJ 验证往返及失败不破坏已有词库。
use super::manage;
use qingjian_dictionary::Dictionary;
use serde_json::json;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};

fn root() -> PathBuf {
    static NEXT: AtomicUsize = AtomicUsize::new(0);
    let root = std::env::temp_dir().join(format!(
        "qingstudy-dicts-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    std::fs::create_dir_all(&root).unwrap();
    root
}

#[test]
fn dictionary_round_trip_toggle_and_remove() {
    let root = root();
    let source = root.join("terms.tsv");
    std::fs::write(&source, "星河实验室\txing he shi yan shi\t9000\n").unwrap();
    let result = manage(&root, "import", &json!({"path":source}).to_string())
        .expect("import must be supported");
    let id = result["id"].as_str().unwrap();
    assert_eq!(result["entries"], 1);
    let exported = root.join("roundtrip.tsv");
    manage(
        &root,
        "export",
        &json!({"id":id,"path":exported,"format":"tsv"}).to_string(),
    )
    .unwrap();
    assert!(
        std::fs::read_to_string(&exported)
            .unwrap()
            .contains("星河实验室\txing he shi yan shi\t9000")
    );
    let reimported = manage(&root, "import", &json!({"path":exported}).to_string()).unwrap();
    assert_eq!(reimported["entries"], 1);
    let binary = root.join("backup.qj");
    manage(
        &root,
        "export",
        &json!({"id":id,"path":binary,"format":"qj"}).to_string(),
    )
    .unwrap();
    assert_eq!(
        manage(&root, "import", &json!({"path":binary}).to_string()).unwrap()["entries"],
        1
    );
    manage(
        &root,
        "toggle",
        &json!({"id":id,"enabled":false}).to_string(),
    )
    .unwrap();
    let list = manage(&root, "list", "{}").unwrap();
    assert_eq!(
        list.as_array()
            .unwrap()
            .iter()
            .find(|row| row["id"] == id)
            .unwrap()["enabled"],
        false
    );
    manage(&root, "remove", &json!({"id":id}).to_string()).unwrap();
    assert_eq!(
        manage(&root, "list", "{}")
            .unwrap()
            .as_array()
            .unwrap()
            .len(),
        2
    );
    assert!(root.join("dicts/removed").join(id).exists());
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn malformed_import_and_path_escape_do_not_modify_existing_dictionary() {
    let root = root();
    let source = root.join("terms.dict.yaml");
    std::fs::write(
        &source,
        "---\nname: 星河术语\n...\n星河实验室\txing he shi yan shi\t9000\n",
    )
    .unwrap();
    let result = manage(&root, "import", &json!({"path":source}).to_string()).unwrap();
    assert_eq!(result["name"], "星河术语");
    std::fs::write(&source, "---\nname: empty\n...\n").unwrap();
    assert!(manage(&root, "import", &json!({"path":source}).to_string()).is_err());
    assert!(manage(&root, "remove", r#"{"id":"../terms.dict.yaml"}"#).is_err());
    std::fs::write(
        &source,
        "---\nimport_tables: [missing]\n...\n你好\tni hao\n",
    )
    .unwrap();
    assert!(manage(&root, "import", &json!({"path":source}).to_string()).is_err());
    assert_eq!(
        manage(&root, "list", "{}")
            .unwrap()
            .as_array()
            .unwrap()
            .len(),
        1
    );
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn utf8_bom_import_preserves_first_word_rime_name_and_rejects_unsupported_headers() {
    let root = root();
    for (name, body, expected_name) in [
        ("plain.tsv", "你好\tni hao\t9000\r\n", "plain"),
        (
            "rime.dict.yaml",
            "---\r\nname: 星河术语\r\n...\r\n你好\tni hao\t9000\r\n",
            "星河术语",
        ),
    ] {
        let source = root.join(name);
        let bytes = format!("\u{feff}{body}");
        std::fs::write(&source, &bytes).unwrap();
        let row = manage(&root, "import", &json!({"path":source}).to_string()).unwrap();
        assert_eq!(row["name"], expected_name);
        let dictionary =
            Dictionary::from_path(root.join("dicts").join(row["id"].as_str().unwrap())).unwrap();
        assert_eq!(dictionary.lookup(&["ni", "hao"], false)[0].text, "你好");
        assert_eq!(
            std::fs::read_to_string(source).unwrap(),
            bytes,
            "source must stay unchanged"
        );
    }
    let before = manage(&root, "list", "{}").unwrap();
    for header in ["import_tables: [missing]", "columns: [text, code, weight]"] {
        let source = root.join("unsupported.dict.yaml");
        std::fs::write(
            &source,
            format!("\u{feff}---\nname: bad\n{header}\n...\n你好\tni hao\t9000\n"),
        )
        .unwrap();
        let error = manage(&root, "import", &json!({"path":source}).to_string()).unwrap_err();
        assert!(error.contains("import_tables 或 columns"), "{error}");
        assert_eq!(manage(&root, "list", "{}").unwrap(), before);
    }
    std::fs::remove_dir_all(root).unwrap();
}
