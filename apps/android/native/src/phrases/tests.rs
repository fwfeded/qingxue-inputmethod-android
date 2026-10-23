//! 短语备份的严格文件格式与逐字往返。
use serde_json::{Value, json};

#[test]
fn archive_roundtrip_preserves_text_positions_and_disabled_rules() {
    let rows = json!([
        {"code":"nihao","position":2,"text":"  第一行😀\r\n第二行\t  ","enabled":true},
        {"code":"qqq","position":9,"text":"停用","enabled":false}
    ]);
    let exported = super::file("export", &rows.to_string()).unwrap();
    let source = exported["document"].as_str().unwrap();
    let document: Value = serde_json::from_str(source).unwrap();
    assert_eq!(document["format"], "qingstudy-phrases");
    assert_eq!(document["version"], 1);
    let restored = super::file("inspect", source).unwrap();
    assert_eq!(restored["phrases"], rows);
    assert_eq!(restored["previews"][0], "  第一行😀↵第二行⇥  ");
    assert_eq!(
        super::file(
            "inspect",
            super::file("export", "[]").unwrap()["document"]
                .as_str()
                .unwrap()
        )
        .unwrap()["phrases"],
        json!([])
    );
}

#[test]
fn archive_rejects_unknown_duplicate_missing_and_wrong_fields() {
    for source in [
        r#"{"format":"qingstudy-phrases","version":2,"phrases":[]}"#,
        r#"{"format":"other","version":1,"phrases":[]}"#,
        r#"{"format":"qingstudy-phrases","version":1,"version":1,"phrases":[]}"#,
        r#"{"format":"qingstudy-phrases","version":1,"phrases":[],"extra":1}"#,
        r#"{"format":"qingstudy-phrases","version":1}"#,
        r#"{"format":"qingstudy-phrases","version":1,"phrases":[{"code":"a","text":"x","position":1,"enabled":true,"extra":1}]}"#,
        r#"{"format":"qingstudy-phrases","version":1,"phrases":[{"code":"a","code":"b","text":"x","position":1,"enabled":true}]}"#,
        r#"{"format":"qingstudy-phrases","version":1,"phrases":[{"code":"a","text":"x","position":1}]}"#,
        "null",
        "[]",
        "{broken",
    ] {
        assert!(super::file("inspect", source).is_err(), "{source}");
    }
}

#[test]
fn archive_reuses_core_conflict_rules_and_android_limits() {
    let rule = json!({"code":"a","text":"x","position":1,"enabled":false});
    for rows in [
        json!([rule.clone(), rule.clone()]),
        json!([{"code":"A","text":"x","position":1,"enabled":true}]),
        json!([{"code":"a","text":"x".repeat(16385),"position":1,"enabled":true}]),
        Value::Array(vec![rule; 257]),
    ] {
        let source = json!({"format":"qingstudy-phrases","version":1,"phrases":rows}).to_string();
        assert!(super::file("inspect", &source).is_err());
    }
    assert!(super::file("inspect", &" ".repeat(2 * 1024 * 1024 + 1)).is_err());
    assert!(super::file("export", "null").is_err());
    assert!(super::file("unknown", "[]").is_err());
}
