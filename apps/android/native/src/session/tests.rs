//! 对实际随包词库验证按键、译词、部分选词与会话重置。

use super::Session;
use std::path::Path;

fn session() -> Session {
    static ASSETS: std::sync::OnceLock<std::path::PathBuf> = std::sync::OnceLock::new();
    let assets = ASSETS.get_or_init(|| {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let output = root.join("output/android/test-assets");
        std::fs::create_dir_all(&output).unwrap();
        for (folder, file) in [
            ("lexicon", "dict.tsv"),
            ("lexicon", "english.tsv"),
            ("glossary", "glossary-en.tsv"),
            ("glossary", "glossary-zh.tsv"),
            ("emoji", "emoji-zh.tsv"),
            ("emoji", "emoji-en.tsv"),
            ("levels", "levels-en.tsv"),
        ] {
            std::fs::copy(
                root.join("assets").join(folder).join(file),
                output.join(file),
            )
            .unwrap();
        }
        std::fs::copy(root.join("data/generated/lm.qj"), output.join("lm.qj")).unwrap();
        std::fs::create_dir_all(output.join("domains")).unwrap();
        for file in std::fs::read_dir(root.join("assets/lexicon/dicts")).unwrap() {
            let path = file.unwrap().path();
            if path.extension().is_some_and(|e| e == "tsv") {
                std::fs::copy(
                    &path,
                    output.join("domains").join(path.file_name().unwrap()),
                )
                .unwrap();
            }
        }
        output
    });
    Session::open(assets).unwrap()
}

#[test]
fn real_dictionary_candidates_have_english_and_commit() {
    let mut session = session();
    for (pinyin, chinese) in [("nihao", "你好"), ("kaifa", "开发"), ("yingyu", "英语")] {
        session.step("reset", "").unwrap();
        let frame = session.step("input", pinyin).unwrap();
        let items = frame["candidates"].as_array().unwrap();
        let index = items
            .iter()
            .position(|item| item["text"] == chinese)
            .expect("expected real candidate");
        assert!(!items[index]["gloss"].as_str().unwrap().is_empty());
        let frame = session.step("select", &index.to_string()).unwrap();
        assert_eq!(frame["commit"], chinese);
        assert_eq!(frame["input"], "");
    }
}

#[test]
fn deletion_invalid_selection_and_reset_do_not_corrupt_session() {
    let mut session = session();
    session.step("input", "nihao").unwrap();
    assert!(session.step("select", "9999").is_err());
    assert_eq!(session.step("delete", "").unwrap()["input"], "niha");
    assert!(session.step("input", "你好").is_err());
    assert_eq!(session.step("reset", "").unwrap()["input"], "");
    assert_eq!(session.step("delete", "").unwrap()["delete"], true);
}

#[test]
fn partial_candidate_preserves_unconsumed_pinyin() {
    let mut session = session();
    let frame = session.step("input", "nihaoshijie").unwrap();
    let index = frame["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .position(|item| item["text"] == "你好")
        .unwrap();
    let frame = session.step("select", &index.to_string()).unwrap();
    assert_eq!(frame["commit"], "你好");
    assert_eq!(frame["input"], "shijie");
}

mod dictionary_reload;
mod domains;
mod habits;
mod learning_language;
mod levels;
mod mobile_panel;
mod parity;
mod personalization;
mod phone_feedback;
mod phrases;
mod restore;
mod sentence;
mod zhuyin;
