//! TSV 备份改名再导入时保留元数据，损坏的声明不得静默丢失。
use super::manage;
use qingjian_dictionary::Dictionary;
use serde_json::json;

#[test]
fn renamed_tsv_preserves_metadata_including_legacy_exports() {
    let root = std::env::temp_dir().join(format!("qingstudy-meta-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let metadata = json!({"name":"星河术语", "license":"MIT", "source":"https://example.org/terms",
        "attribution":"测试作者", "version":"2026-10", "generator":"fixture", "entries":999});
    for (index, prefix) in [
        "# ",
        "# qingjian-dictionary-metadata-v1: ",
        "\u{feff}# ",
        "\u{feff}# qingjian-dictionary-metadata-v1: ",
    ]
    .iter()
    .enumerate()
    {
        let source = root.join(format!("renamed-{index}.tsv"));
        std::fs::write(
            &source,
            format!("{prefix}{metadata}\n星河实验室\txing he shi yan shi\t9000\n"),
        )
        .unwrap();
        let imported = manage(&root, "import", &json!({"path":source}).to_string()).unwrap();
        assert_eq!(imported["name"], "星河术语");
        assert_eq!(imported["license"], "MIT");
        let exported = root.join(format!("renamed-again-{index}.tsv"));
        manage(
            &root,
            "export",
            &json!({"id":imported["id"],"path":exported,"format":"tsv"}).to_string(),
        )
        .unwrap();
        manage(&root, "remove", &json!({"id":imported["id"]}).to_string()).unwrap();
        let restored = manage(&root, "import", &json!({"path":exported}).to_string()).unwrap();
        let dictionary =
            Dictionary::from_path(root.join("dicts").join(restored["id"].as_str().unwrap()))
                .unwrap();
        let mut expected = metadata.clone();
        expected["entries"] = json!(1);
        assert_eq!(
            serde_json::to_value(dictionary.metadata().unwrap()).unwrap(),
            expected
        );
        assert_eq!(
            dictionary.lookup(&["xing", "he", "shi", "yan", "shi"], false)[0].text,
            "星河实验室"
        );
    }
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn invalid_declared_metadata_does_not_install_dictionary() {
    let root = std::env::temp_dir().join(format!("qingstudy-bad-meta-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let source = root.join("bad.tsv");
    let existing = root.join("existing.tsv");
    std::fs::write(&existing, "# {\"name\":\"普通注释\"}\n原词\tyuan ci\t10\n").unwrap();
    let original = manage(&root, "import", &json!({"path":existing}).to_string()).unwrap();
    assert_eq!(original["name"], "existing");
    for header in [
        "# qingjian-dictionary-metadata-v1: {broken",
        "\u{feff}# qingjian-dictionary-metadata-v1: {broken",
        "\u{feff}# qingjian-dictionary-metadata-v2: {}",
        "# qingjian-dictionary-metadata-v1: {\"name\":12}",
        "# qingjian-dictionary-metadata-v1: {\"name\":\"\"}",
        "# qingjian-dictionary-metadata-v2: {\"name\":\"未来格式\"}",
    ] {
        std::fs::write(&source, format!("{header}\n星河\txing he\t9000\n")).unwrap();
        assert!(manage(&root, "import", &json!({"path":source}).to_string()).is_err());
        assert_eq!(manage(&root, "list", "{}").unwrap(), json!([original]));
    }
    std::fs::remove_dir_all(root).unwrap();
}
