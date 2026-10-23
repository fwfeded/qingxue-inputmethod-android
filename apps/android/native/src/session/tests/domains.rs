//! 随包领域词库默认、启停与全部加载必须实际改变 Engine 的附加词库。
use super::session;
use serde_json::json;

#[test]
fn bundled_domains_default_toggle_and_all_load() {
    let mut s = session();
    s.step("configure", "{}").unwrap();
    assert_eq!(s.engine.extra_dictionaries().len(), 1, "默认启用成语");
    s.step("configure", r#"{"domains":[]}"#).unwrap();
    assert!(s.engine.extra_dictionaries().is_empty());
    let ids = [
        "animals",
        "automotive",
        "finance",
        "food",
        "historical_figures",
        "idioms",
        "it_computing",
        "law",
        "medicine",
        "places",
        "poetry_lines",
    ];
    for id in ids {
        s.step("configure", &json!({"domains":[id]}).to_string())
            .unwrap();
        assert_eq!(s.engine.extra_dictionaries().len(), 1, "{id}");
        assert!(!s.engine.extra_dictionaries()[0].is_empty());
    }
    s.step("configure", &json!({"domains":ids}).to_string())
        .unwrap();
    assert_eq!(s.engine.extra_dictionaries().len(), 11);
    s.step(
        "configure",
        r#"{"domains":["../dict","unknown","idioms","idioms"]}"#,
    )
    .unwrap();
    assert_eq!(s.engine.extra_dictionaries().len(), 1);
}

#[test]
fn domain_words_glosses_and_disable_follow_core_candidates() {
    use qingjian_core::CandidateKind;
    let mut s = session();
    let mut evidence = Vec::new();
    for (id, pinyin, word, gloss) in [
        ("idioms", "yiqiuyihe", "一丘一壑", "secluded"),
        (
            "it_computing",
            "yizhixinghaxi",
            "一致性哈希",
            "consistent hashing",
        ),
        (
            "medicine",
            "shenxiaoqiulvguolv",
            "肾小球滤过率",
            "glomerular filtration rate",
        ),
    ] {
        s.step("reset", "").unwrap();
        s.step("configure", r#"{"domains":[]}"#).unwrap();
        let before = s.step("input", pinyin).unwrap();
        assert!(
            !s.candidates
                .items
                .iter()
                .any(|c| c.text == word && c.kind == CandidateKind::Chinese)
        );
        s.step("reset", "").unwrap();
        s.step("configure", &json!({"domains":[id]}).to_string())
            .unwrap();
        let after = s.step("input", pinyin).unwrap();
        assert_eq!(after["candidates"][0]["text"], word);
        assert!(
            after["candidates"][0]["gloss"]
                .as_str()
                .unwrap()
                .contains(gloss)
        );
        assert_eq!(s.step("space", "").unwrap()["commit"], word);
        evidence.push(json!({"domain":id,"input":pinyin,"disabledFirst":before["candidates"][0],"enabledFirst":after["candidates"][0],"queryMs":after["elapsedMs"]}));
    }
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../output/android/evidence/domains-query-matrix.json");
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(path, serde_json::to_string_pretty(&evidence).unwrap()).unwrap();
}

#[test]
fn damaged_domain_is_reported_without_breaking_basic_input() {
    let mut s = session();
    let root =
        std::env::temp_dir().join(format!("qingstudy-domain-damaged-{}", std::process::id()));
    let domains = root.join("domains");
    std::fs::create_dir_all(&domains).unwrap();
    std::fs::write(domains.join("idioms.tsv"), [0xff, 0xfe]).unwrap();
    std::fs::copy(
        s.directory.join("domains/medicine.tsv"),
        domains.join("medicine.tsv"),
    )
    .unwrap();
    let info = crate::domains::info(&domains);
    let rows = info.as_array().unwrap();
    assert_eq!(
        rows.iter().find(|r| r["id"] == "idioms").unwrap()["available"],
        false
    );
    assert_eq!(
        rows.iter().find(|r| r["id"] == "medicine").unwrap()["available"],
        true
    );
    s.directory = root.clone();
    s.step("configure", r#"{"domains":["idioms","medicine"]}"#)
        .unwrap();
    assert_eq!(s.engine.extra_dictionaries().len(), 1);
    assert_eq!(
        s.step("input", "nihao").unwrap()["candidates"][0]["text"],
        "你好"
    );
    drop(s);
    std::fs::remove_dir_all(root).unwrap();
}
