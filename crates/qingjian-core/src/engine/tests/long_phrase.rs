//! 只把同段拼音反复分次确认的较长短语作为强学习信号。

use super::WordLearner;
use crate::{CandidateKind, Engine};
use qingjian_dictionary::Dictionary;

const WORDS: &str = "青学\tqing xue\t1000\n输入法\tshu ru fa\t1000\n工具\tgong ju\t1000\n体验\tti yan\t1000\n优化\tyou hua\t1000\n测试\tce shi\t1000\n记录\tji lu\t1000\n";

fn learner() -> Engine {
    Engine::new(Dictionary::parse(WORDS).unwrap()).with_learner(Box::new(WordLearner::default()))
}

fn pick(engine: &mut Engine, text: &str) {
    let candidate = engine
        .query()
        .unwrap()
        .candidates
        .items
        .into_iter()
        .find(|item| item.text == text && item.kind == CandidateKind::Chinese)
        .unwrap_or_else(|| panic!("missing prefix {text}"));
    assert_eq!(engine.commit(&candidate), text);
}

fn split(engine: &mut Engine, input: &str, words: &[&str]) {
    engine.set_input(input);
    for word in words {
        pick(engine, word);
    }
    assert!(engine.composition().is_empty());
    engine.note_passthrough('\n');
}

#[test]
fn repeated_same_buffer_long_phrase_becomes_a_queryable_user_word() {
    let mut engine = learner();
    let input = "qingxueshurufa";
    split(&mut engine, input, &["青学", "输入法"]);
    assert_eq!(engine.learner().weight("青学输入法"), 0);
    split(&mut engine, input, &["青学", "输入法"]);
    assert_eq!(engine.learner().weight("青学输入法"), 1);
    engine.set_input(input);
    let first = engine.query().unwrap().candidates.items.remove(0);
    assert_eq!(first.text, "青学输入法");
    assert_eq!(first.kind, CandidateKind::Chinese);
}

#[test]
fn same_buffer_phrase_supports_twelve_chars_but_caps_longer_sentences() {
    let cases = [
        (
            "qingxuegongjutiyanyouhuaceshijilu",
            vec!["青学", "工具", "体验", "优化", "测试", "记录"],
            true,
        ),
        (
            "qingxueshurufagongjutiyanyouhuajilu",
            vec!["青学", "输入法", "工具", "体验", "优化", "记录"],
            false,
        ),
    ];
    for (input, words, learned) in cases {
        let mut engine = learner();
        for _ in 0..2 {
            split(&mut engine, input, &words);
        }
        assert_eq!(
            engine.learner().weight(&words.concat()) > 0,
            learned,
            "{input}"
        );
    }
}

#[test]
fn separate_buffers_do_not_auto_join_long_phrases() {
    let mut engine = learner();
    for _ in 0..4 {
        engine.set_input("qingxue");
        pick(&mut engine, "青学");
        engine.set_input("shurufa");
        pick(&mut engine, "输入法");
        engine.note_passthrough('\n');
    }
    assert_eq!(engine.learner().weight("青学输入法"), 0);
}

#[test]
fn private_and_paused_learning_do_not_accumulate_long_phrases() {
    for private in [true, false] {
        let mut engine = learner();
        if private {
            engine.set_private(true);
        } else {
            engine.set_learning(false);
        }
        for _ in 0..3 {
            split(&mut engine, "qingxueshurufa", &["青学", "输入法"]);
        }
        engine.set_private(false);
        engine.set_learning(true);
        assert_eq!(engine.learner().weight("青学输入法"), 0);
        // 恢复后第一次输入仍不足以达到两次确认门槛。
        split(&mut engine, "qingxueshurufa", &["青学", "输入法"]);
        assert_eq!(engine.learner().weight("青学输入法"), 0);
    }
}
