//! 整句模型装配的回归与固定语句基线；不改上游排序算法。
use super::session;
use serde_json::json;

#[test]
fn sentence_model_is_loaded_and_sentence_commits_atomically() {
    let mut session = session();
    let frame = session.step("input", "wojintianxiangqubeijing").unwrap();
    assert_eq!(
        frame["sentenceModel"],
        json!(true),
        "Android must load the bundled bigram model"
    );
    assert_eq!(frame["candidates"][0]["text"], "我今天想去北京");
    assert_eq!(
        session.step("space", "").unwrap()["commit"],
        "我今天想去北京"
    );
}

#[test]
fn records_unigram_sentence_baseline() {
    let mut session = session();
    let mut rows = Vec::new();
    for (pinyin, expected) in [
        ("wojintianxiangqubeijing", "我今天想去北京"),
        ("womenmingtianjian", "我们明天见"),
        ("jintiantianqihenhao", "今天天气很好"),
        ("woxuexizhongwen", "我学习中文"),
        ("qingbangwodakaidiannao", "请帮我打开电脑"),
        ("wozaihuochezhanshangban", "我在火车站上班"),
        ("mingtianzaoshangjiudianjian", "明天早上九点见"),
        (
            "wozhengzaikaifayigeanzhuoyingyong",
            "我正在开发一个安卓应用",
        ),
        ("rengongzhinengkeyibangzhuwomen", "人工智能可以帮助我们"),
        ("zhegewentixuyaojiejue", "这个问题需要解决"),
    ] {
        session.step("reset", "").unwrap();
        let result = session.step("input", pinyin).unwrap();
        rows.push(json!({"pinyin":pinyin,"expected":expected,"top":result["candidates"][0]["text"],"elapsedMs":result["elapsedMs"]}));
    }
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../output/android/sentence-matrix.json");
    std::fs::write(path, serde_json::to_string_pretty(&rows).unwrap()).unwrap();
}

#[test]
fn correcting_a_tail_after_a_committed_sentence_keeps_the_new_composition() {
    let mut session = session();
    for c in "qingbangwodakaidiannao".chars() {
        session.step("input", &c.to_string()).unwrap();
    }
    assert_eq!(
        session.step("space", "").unwrap()["commit"],
        "请帮我打开电脑"
    );
    for c in "womenmingtianjiax".chars() {
        session.step("input", &c.to_string()).unwrap();
    }
    assert_eq!(
        session.step("delete", "").unwrap()["input"],
        "womenmingtianjia"
    );
    let frame = session.step("input", "n").unwrap();
    assert_eq!(frame["input"], "womenmingtianjian");
    assert_eq!(frame["candidates"][0]["text"], "我们明天见", "{frame}");
    assert_eq!(session.step("space", "").unwrap()["commit"], "我们明天见");
}
