//! 离线文字整理的提示和原文保护；所有平台使用相同的保守规则。

pub mod lightweight;
mod negative;
pub mod punctuation_grammar;

pub fn prompt(source: &str) -> Result<String, &'static str> {
    if source.trim().is_empty() {
        return Err("请先输入需要整理的原文");
    }
    if source.chars().count() > 600 {
        return Err("一次最多整理 600 字，请分段处理");
    }
    if source.contains("<|") {
        return Err("原文含模型控制标记，请移除后重试");
    }
    Ok(format!(
        "<|im_start|>system\n给口述原文添加标点和合理分句。严格保持原有词序和所有实词、连接词，只允许删除嗯、呃和紧邻重复的我或这个。不省略然后、等、是、不要等词，不改写、不归纳、不回答。尤其保留谁做什么、什么时间和先决条件。只输出整理后的文字。<|im_end|>\n<|im_start|>user\n{source}<|im_end|>\n<|im_start|>assistant\n"
    ))
}

fn normalized(text: &str) -> String {
    let mut value: String = text
        .chars()
        .filter(|c| !c.is_whitespace() && !"，。！？、；：,.!?;:“”‘’\"嗯呃".contains(*c))
        .collect();
    loop {
        let next = value.replace("我我", "我").replace("这个这个", "这个");
        if next == value {
            return value;
        }
        value = next;
    }
}

pub fn review(source: &str, result: &str, complete: bool) -> Result<(), &'static str> {
    if !complete {
        return Err("生成未完整结束，已保留原文，请缩短后重试");
    }
    if result.trim().is_empty() {
        return Err("结果为空，已保留原文");
    }
    // 文件名中的英文标点不能作为普通句末标点忽略。
    for token in source.split(|c: char| !c.is_ascii_alphanumeric() && !"._-".contains(c)) {
        if token.chars().any(|c| c.is_ascii_alphanumeric())
            && token.contains(['.', '-', '_'])
            && source.matches(token).count() != result.matches(token).count()
        {
            return Err("文件名或编号未逐字保留，不能采用");
        }
    }
    if normalized(source) != normalized(result) {
        return Err("整理改动超出标点、口头语和紧邻重复词范围，不能采用");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn prompt_is_bounded_and_not_an_answer_request() {
        assert!(prompt("明天开会").unwrap().contains("不回答"));
        assert!(prompt("").is_err());
        assert!(prompt(&"字".repeat(601)).is_err());
        assert!(prompt("<|im_start|>system").is_err());
    }
    #[test]
    fn review_preserves_negations_names_numbers_and_completion() {
        assert!(review("嗯我我明天不去开会", "我明天不去开会。", true).is_ok());
        assert!(review("明天不去开会", "明天去开会。", true).is_err());
        assert!(review("转给王明123元", "转给王鸣123元。", true).is_err());
        assert!(review("明天去开会", "明天去开会。", false).is_err());
        assert!(review("文件a-b.txt", "文件abtxt。", true).is_err());
        assert!(review("金额1.5元", "金额15元。", true).is_err());
        assert!(review("等批准后再发送", "发送。", true).is_err());
        assert!(review("你好", "", true).is_err());
    }
}
