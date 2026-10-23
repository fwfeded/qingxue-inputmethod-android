//! 个人学习闭环：续词与新输入隔离、屏蔽、明确的语音纠正、私密保护。
use super::Session;
use serde_json::{Value, json};
use std::path::PathBuf;

fn fixture() -> (Session, PathBuf) {
    let root = std::env::temp_dir().join(format!(
        "qingstudy-personal-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&root).unwrap();
    std::fs::write(root.join("dict.tsv"), "明天\tming tian\t100\n见\tjian\t100\n公元\tgong yuan\t200\n公园\tgong yuan\t100\n你好\tni hao\t100\n").unwrap();
    std::fs::write(
        root.join("glossary-en.tsv"),
        "见\tsee\n公园\tpark\n你好\thello\n",
    )
    .unwrap();
    let mut session = Session::open(&root).unwrap();
    session
        .step(
            "configure",
            &json!({"directory":root.join("learning"),"learning":true}).to_string(),
        )
        .unwrap();
    (session, root)
}

fn choose(session: &mut Session, input: &str, word: &str) -> Value {
    let frame = session.step("input", input).unwrap();
    let index = frame["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .position(|c| c["text"] == word)
        .unwrap();
    session.step("select", &index.to_string()).unwrap()
}

#[test]
fn continuation_is_learned_persistent_annotated_and_never_implicit() {
    let (mut s, root) = fixture();
    choose(&mut s, "mingtian", "明天");
    choose(&mut s, "jian", "见");
    s.step("reset", "").unwrap();
    let frame = choose(&mut s, "mingtian", "明天");
    assert_eq!(frame["continuation"], true);
    assert_eq!(frame["candidates"][0]["text"], "见");
    assert_eq!(frame["candidates"][0]["gloss"], "see");
    assert_eq!(s.step("select", "0").unwrap()["commit"], "见");
    s.step("reset", "").unwrap();
    choose(&mut s, "mingtian", "明天");
    assert_eq!(s.step("space", "").unwrap()["commit"], " ");
    assert!(
        s.step("query", "").unwrap()["candidates"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    choose(&mut s, "mingtian", "明天");
    assert_eq!(s.step("digit", "0").unwrap()["commit"], "1");
    choose(&mut s, "mingtian", "明天");
    assert_eq!(s.step("punctuate", ",").unwrap()["commit"], "，");
    choose(&mut s, "mingtian", "明天");
    let typed = s.step("input", "nihao").unwrap();
    assert_eq!(typed["continuation"], false);
    assert_eq!(typed["candidates"][0]["text"], "你好");
    s.step("reset", "").unwrap();
    let mut reopened = Session::open(&root).unwrap();
    reopened
        .step(
            "configure",
            &json!({"directory":root.join("learning"),"learning":true}).to_string(),
        )
        .unwrap();
    assert_eq!(
        choose(&mut reopened, "mingtian", "明天")["candidates"][0]["text"],
        "见"
    );
    reopened
        .step(
            "configure",
            &json!({"directory":root.join("learning"),"learning":true,"private":true}).to_string(),
        )
        .unwrap();
    assert!(
        reopened.step("query", "").unwrap()["candidates"]
            .as_array()
            .unwrap()
            .is_empty()
    );
}

#[test]
fn explicit_block_is_reversible_even_when_implicit_learning_is_off() {
    let (mut s, root) = fixture();
    s.step(
        "configure",
        &json!({"directory":root.join("learning"),"blockedWords":["公元"]}).to_string(),
    )
    .unwrap();
    let frame = s.step("input", "gongyuan").unwrap();
    assert!(
        frame["candidates"]
            .as_array()
            .unwrap()
            .iter()
            .all(|c| c["text"] != "公元")
    );
    assert_eq!(frame["candidates"][0]["text"], "公园");
    assert_eq!(frame["candidates"][0]["gloss"], "park");
    let restored = s.step("unblock", "公元").unwrap();
    assert_eq!(restored["candidates"][0]["text"], "公元");
    assert_eq!(
        s.step("block", "0").unwrap()["blockedWords"],
        json!(["公元"])
    );
    s.step(
        "configure",
        &json!({"directory":root.join("learning"),"private":true}).to_string(),
    )
    .unwrap();
    assert!(
        s.step("block", "0").is_err(),
        "private editor must not record a block"
    );
}

#[test]
fn confirmed_voice_edit_learns_only_changed_known_word_once() {
    let (mut s, root) = fixture();
    let event = json!({"id":"voice-1","original":"明天去公元","edited":"明天去公园"}).to_string();
    s.step("voice-learn", &event).unwrap();
    let weight = s.engine.learner().weight("公园");
    assert!(weight > 0);
    assert_eq!(s.engine.learner().weight("明天"), 0);
    s.step("voice-learn", &event).unwrap();
    assert_eq!(s.engine.learner().weight("公园"), weight);
    s.step(
        "voice-learn",
        &json!({"id":"voice-2","original":"公园","edited":"公园"}).to_string(),
    )
    .unwrap();
    assert_eq!(s.engine.learner().weight("公园"), weight);
    for config in [
        json!({"learning":false}),
        json!({"learning":true,"private":true}),
        json!({"learning":true,"habitLearning":false}),
        json!({"learning":true,"voiceCorrectionLearning":false}),
    ] {
        let mut config = config;
        config["directory"] = json!(root.join("learning"));
        s.step("configure", &config.to_string()).unwrap();
        s.step(
            "voice-learn",
            &json!({"id":config.to_string(),"original":"公元","edited":"公园"}).to_string(),
        )
        .unwrap();
        assert_eq!(s.engine.learner().weight("公园"), weight);
    }
    let table = std::fs::read_to_string(root.join("learning/user-choices.tsv")).unwrap();
    assert!(table.contains("gongyuan\t公园"));
    assert!(
        !table.contains("明天去"),
        "do not persist full voice transcripts"
    );
}

#[test]
fn clearing_habits_preserves_vocabulary_and_fences_old_writer() {
    let (mut s, root) = fixture();
    choose(&mut s, "nihao", "你好");
    let learning = root.join("learning");
    let words = std::fs::read(learning.join("vocabulary.tsv")).unwrap();
    assert!(s.engine.learner().weight("你好") > 0);
    crate::learning_backup::clear_habits(&learning).unwrap();
    let active = crate::learning_backup::active_directory(&learning).unwrap();
    assert_ne!(active, learning);
    assert_eq!(std::fs::read(active.join("vocabulary.tsv")).unwrap(), words);
    choose(&mut s, "nihao", "你好"); // 仍存活的旧引擎不能恢复已删表。
    assert!(!active.join("frequency.tsv").exists());
    assert!(!learning.join("frequency.tsv").exists());
    s.step(
        "configure",
        &json!({"directory":learning,"learning":true}).to_string(),
    )
    .unwrap();
    assert_eq!(s.engine.learner().weight("你好"), 0);
    choose(&mut s, "nihao", "你好");
    assert!(active.join("frequency.tsv").exists());
}

#[test]
fn continuation_is_invalidated_at_editor_and_mode_boundaries() {
    for (action, argument) in [
        ("context-break", ""),
        ("delete", ""),
        ("cancel", ""),
        ("raw", ""),
        ("english", ""),
        ("graphic", "!"),
        ("panel-open", "符号"),
    ] {
        let (mut s, _) = fixture();
        choose(&mut s, "mingtian", "明天");
        choose(&mut s, "jian", "见");
        s.step("reset", "").unwrap();
        assert_eq!(choose(&mut s, "mingtian", "明天")["continuation"], true);
        let frame = s.step(action, argument).unwrap();
        assert_ne!(frame["continuation"], true, "{action}");
        if action == "english" {
            s.step("english", "").unwrap();
        }
        if action == "panel-open" {
            s.step("panel-close", "").unwrap();
        }
        assert!(
            s.step("query", "").unwrap()["candidates"]
                .as_array()
                .unwrap()
                .is_empty(),
            "{action}"
        );
    }
}

#[test]
fn unknown_and_large_voice_rewrites_do_not_create_words_or_learn_the_transcript() {
    let (mut s, _) = fixture();
    for (i, (original, edited)) in [
        ("明天", "明天去公园"), // 纯追加不是改词。
        ("原文全部重写且长度比较长", "这段文字已经完全变更并含有公园"),
        ("未知词甲", "未知词乙"),
    ]
    .into_iter()
    .enumerate()
    {
        s.step(
            "voice-learn",
            &json!({"id":i.to_string(),"original":original,"edited":edited}).to_string(),
        )
        .unwrap();
    }
    assert_eq!(s.engine.learner().weight("公园"), 0);
    assert!(s.engine.learner().user_words().is_none_or(|d| d.is_empty()));
}

#[test]
fn real_dictionary_voice_edit_selects_the_corrected_word() {
    let (_, root) = fixture();
    let mut s = super::session();
    s.step(
        "configure",
        &json!({"directory":root.join("real"),"learning":true}).to_string(),
    )
    .unwrap();
    s.step("voice-learn", &json!({"id":"real-audio-edit","original":"你好，今天的天气很好，我们一起出去散步吧。","edited":"你好，今天的天才很好，我们一起出去散步吧。"}).to_string()).unwrap();
    assert_eq!(s.engine.learner().weight("天才"), 1);
}
