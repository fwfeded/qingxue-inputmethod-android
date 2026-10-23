//! 真机反馈的原词与长句回放，区分拼音解析失败和英文模式无词匹配。
use super::session;
use serde_json::json;

#[test]
fn switching_unmatched_english_back_to_chinese_keeps_the_composition() {
    let mut session = session();
    for (input, expected) in [
        ("baqianli", "八千里"),
        ("wanshang", "晚上"),
        ("wanshangwomenyiqichifan", "晚上我们一起吃饭"),
    ] {
        session.step("english", "").unwrap();
        let english = session.step("input", input).unwrap();
        assert!(english["candidates"].as_array().unwrap().is_empty());
        let chinese = session.step("english", "").unwrap();
        assert_eq!(chinese["commit"], "", "切回中文不能提前提交无匹配字母");
        assert_eq!(chinese["input"], input);
        assert_eq!(chinese["candidates"][0]["text"], expected);
        assert_eq!(session.step("space", "").unwrap()["commit"], expected);
    }
}

#[test]
fn switching_a_matched_english_word_still_commits_raw_text() {
    let mut session = session();
    session.step("english", "").unwrap();
    let english = session.step("input", "hello").unwrap();
    assert!(!english["candidates"].as_array().unwrap().is_empty());
    assert_eq!(session.step("english", "").unwrap()["commit"], "hello");
}

#[test]
fn records_phone_feedback_candidates_in_both_modes() {
    let mut session = session();
    let mut rows = Vec::new();
    for english in [false, true] {
        if english {
            session.step("english", "").unwrap();
        }
        for input in [
            "baqianli",
            "wanshang",
            "wanshangwomenyiqichifan",
            "mingtianzaoshangjiudianjian",
            "baqianliluyunheyue",
            "wozhengzaikaifayigeanzhuoyingyong",
            "wanshang;",
        ] {
            session.step("reset", "").unwrap();
            let mut steps = Vec::new();
            for character in input.chars() {
                let frame = session.step("input", &character.to_string()).unwrap();
                steps.push(json!({"input":frame["input"],"marked":frame["marked"],
                    "count":frame["candidates"].as_array().unwrap().len(),
                    "top":frame["candidates"][0],"ms":frame["elapsedMs"]}));
            }
            rows.push(json!({"english":english,"input":input,"steps":steps}));
        }
    }
    let output = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../output/android/phone-feedback-r5");
    std::fs::create_dir_all(&output).unwrap();
    std::fs::write(
        output.join("candidate-baseline.json"),
        serde_json::to_vec_pretty(&rows).unwrap(),
    )
    .unwrap();
}
