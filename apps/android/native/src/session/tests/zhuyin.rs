//! 大千注音沿用 Core 解码；数字/标点入码与空格一声不能误触选词。
use super::session;
use serde_json::json;

#[test]
fn zhuyin_keys_tones_and_english_switch_use_core() {
    let mut s = session();
    let frame = s.step("configure", r#"{"scheme":"zhuyin"}"#).unwrap();
    assert_eq!(frame["scheme"], "大千注音");
    assert_eq!(frame["phoneticKeys"]["1"], "ㄅ");
    assert_eq!(frame["phoneticKeys"]["."], "ㄡ");
    s.step("input", "su").unwrap();
    assert_eq!(s.step("digit", "2").unwrap()["commit"], "");
    s.step("input", "cl").unwrap();
    let frame = s.step("graphic", "3").unwrap();
    assert_eq!(frame["marked"], "ㄋㄧˇㄏㄠˇ");
    let first = &frame["candidates"][0];
    assert_eq!(first["text"], "你好");
    assert!(first["gloss"].as_str().unwrap().contains("hello"));
    assert_eq!(s.step("space", "").unwrap()["commit"], "你好");
    s.step("graphic", "1").unwrap();
    s.step("input", "j").unwrap();
    assert_eq!(s.step("digit", "12").unwrap()["marked"], "ㄅㄨˋ");
    assert_eq!(s.step("raw", "").unwrap()["commit"], "ㄅㄨˋ");
    let panel = s.step("panel", "").unwrap();
    let symbol = panel["candidates"][0]["text"].clone();
    assert_eq!(s.step("digit", "0").unwrap()["commit"], symbol);
    s.step("panel-close", "").unwrap();
    s.step("english", "").unwrap();
    assert_eq!(s.step("graphic", "1").unwrap()["commit"], "1");
    let frame = s.step("input", "recieve").unwrap();
    assert_eq!(frame["candidates"][0]["text"], "receive");
    s.step("reset", "").unwrap();
    s.step("english", "").unwrap();
    s.step("configure", r#"{"scheme":"pinyin"}"#).unwrap();
    let frame = s.step("input", "nihao").unwrap();
    assert_eq!(frame["scheme"], "全拼");
    assert_eq!(frame["phoneticKeys"], json!({}));
    assert_eq!(s.step("digit", "0").unwrap()["commit"], "你好");
}

#[test]
fn first_space_finishes_tone_then_next_space_commits() {
    let mut s = session();
    s.step("configure", r#"{"scheme":"zhuyin"}"#).unwrap();
    s.step("input", "wu").unwrap();
    s.step("graphic", "0").unwrap();
    let frame = s.step("space", "").unwrap();
    assert_eq!(frame["commit"], "");
    assert_eq!(frame["input"], "wu0 ");
    assert_eq!(frame["marked"], "ㄊㄧㄢ");
    assert_eq!(frame["candidates"][0]["text"], "天");
    assert_eq!(s.step("space", "").unwrap()["commit"], "天");
    assert_eq!(s.step("space", "").unwrap()["commit"], " ");
}

#[test]
fn preedit_preserves_keys_while_showing_core_symbols_and_partial_remainder() {
    let mut s = session();
    s.step("configure", r#"{"scheme":"zhuyin"}"#).unwrap();
    for (action, argument, keys, shown) in [
        ("input", "s", "s", "ㄋ"),
        ("input", "u", "su", "ㄋㄧ"),
        ("digit", "2", "su3", "ㄋㄧˇ"),
        ("delete", "", "su", "ㄋㄧ"),
        ("space", "", "su ", "ㄋㄧ"),
        ("delete", "", "su", "ㄋㄧ"),
        ("delete", "", "s", "ㄋ"),
    ] {
        let frame = s.step(action, argument).unwrap();
        assert_eq!(frame["input"], keys);
        assert_eq!(frame["preedit"], shown);
        assert_eq!(frame["commit"], "");
    }
    s.step("cancel", "").unwrap();
    let frame = s.step("input", "su3cl3").unwrap();
    let index = frame["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .position(|c| c["text"] == "你")
        .unwrap();
    let frame = s.step("select", &index.to_string()).unwrap();
    assert_eq!(frame["commit"], "你");
    assert_eq!(frame["input"], "cl3");
    assert_eq!(frame["preedit"], "ㄏㄠˇ");
    assert_eq!(s.step("raw", "").unwrap()["commit"], "ㄏㄠˇ");
    let frame = s.step("input", "7su").unwrap();
    assert_eq!(frame["preedit"], "ㄋㄧ˙");
    s.step("cancel", "").unwrap();
    s.step("english", "").unwrap();
    assert_eq!(s.step("input", "recieve").unwrap()["preedit"], "recieve");
    s.step("cancel", "").unwrap();
    s.step("english", "").unwrap();
    s.step("configure", r#"{"scheme":"pinyin"}"#).unwrap();
    assert_eq!(s.step("input", "nihao").unwrap()["preedit"], "nihao");
}
