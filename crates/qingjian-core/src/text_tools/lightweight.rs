//! 轻量整理流水线：保守清理句首停顿与口吃，分段标点，逐字核对后合并。

fn boundary(c: char) -> bool {
    "，。！？；：,.!?;:\n".contains(c)
}

fn quote_end(c: char) -> Option<char> {
    match c {
        '“' => Some('”'),
        '‘' => Some('’'),
        '「' => Some('」'),
        '『' => Some('』'),
        '"' => Some('"'),
        '\'' => Some('\''),
        _ => None,
    }
}

/// 引号内和独立应答“嗯，……”保持原样，不把任意重复都视为口吃。
pub fn clean(source: &str) -> String {
    let mut rest = source;
    let mut out = String::new();
    let mut quote = None;
    let mut at_start = true;
    while !rest.is_empty() {
        let c = rest.chars().next().unwrap();
        if quote == Some(c) {
            quote = None;
        } else if quote.is_none() {
            quote = quote_end(c);
            if quote.is_none() && at_start {
                let trimmed = rest.trim_start_matches(['嗯', '呃']);
                if trimmed.len() != rest.len()
                    && [
                        "我", "你", "他", "她", "这", "那", "请", "先", "今天", "明天", "现在",
                        "然后", "其实",
                    ]
                    .iter()
                    .any(|prefix| trimmed.starts_with(prefix))
                    && trimmed
                        .chars()
                        .next()
                        .is_some_and(|next| !boundary(next) && !next.is_whitespace())
                    && trimmed.chars().count() >= 2
                {
                    rest = trimmed;
                    continue;
                }
                for word in ["我", "这个"] {
                    let double = word.repeat(2);
                    while rest.starts_with(&double) {
                        rest = &rest[word.len()..];
                    }
                }
                if !rest.starts_with(c) {
                    continue;
                }
            }
        }
        out.push(c);
        rest = &rest[c.len_utf8()..];
        if !c.is_whitespace() {
            at_start = boundary(c);
        }
    }
    out
}

pub fn parts(source: &str) -> Result<Vec<String>, &'static str> {
    super::prompt(source)?;
    let cleaned = clean(source);
    if cleaned.chars().count() <= 90 {
        return Ok(vec![cleaned]);
    }
    let mut parts = Vec::new();
    let mut start = 0;
    let mut count = 0;
    let mut quote = None;
    for (offset, c) in cleaned.char_indices() {
        if quote == Some(c) {
            quote = None;
        } else if quote.is_none() {
            quote = quote_end(c);
        }
        let rest = &cleaned[offset..];
        let transition = [
            "如果",
            "要是",
            "最后",
            "另外",
            "接下来",
            "下午再",
            "上午再",
            "随后",
            "暂时不要",
        ]
        .iter()
        .any(|prefix| rest.starts_with(prefix));
        if quote.is_none()
            && count >= 24
            && (transition || cleaned[..offset].chars().last().is_some_and(boundary))
        {
            parts.push(cleaned[start..offset].to_string());
            start = offset;
            count = 0;
        }
        count += 1;
    }
    parts.push(cleaned[start..].to_string());
    Ok(parts)
}

pub fn prompt(source: &str, long: bool) -> String {
    let example = if long {
        "<|im_start|>user\n晚上先听录音导出文件时需要选择格式导出完成以后再保存<|im_end|>\n<|im_start|>assistant\n晚上先听录音。导出文件时，需要选择格式。导出完成以后，再保存。<|im_end|>\n"
    } else {
        ""
    };
    format!(
        "<|im_start|>system\n给原文加标点，按语义划分不同事项。保持所有文字原样，不改写不回答，不在动词和宾语之间断句。只输出全文。<|im_end|>\n<|im_start|>user\n请先保存记录等检查通过以后再发送不要提前发送<|im_end|>\n<|im_start|>assistant\n请先保存记录，等检查通过以后再发送，不要提前发送。<|im_end|>\n<|im_start|>user\n不是小李没告诉我是我忘了<|im_end|>\n<|im_start|>assistant\n不是小李没告诉我，是我忘了。<|im_end|>\n{example}<|im_start|>user\n{source}<|im_end|>\n<|im_start|>assistant\n"
    )
}

fn content(source: &str) -> String {
    source
        .chars()
        .filter(|c| !c.is_whitespace() && !"，。！？、；：,.!?;:".contains(*c))
        .collect()
}

pub fn finish(source: &str, outputs: &[String], complete: bool) -> Result<String, &'static str> {
    let inputs = parts(source)?;
    if !complete || inputs.len() != outputs.len() {
        return Err("生成未完整结束，已保留原文，请缩短后重试");
    }
    for (input, output) in inputs.iter().zip(outputs) {
        super::review(input, output, true)?;
        if content(input) != content(output) {
            return Err("整理改变了原文字词，不能采用");
        }
        super::negative::check(input, output)?;
    }
    let joined = outputs
        .iter()
        .map(|text| text.trim())
        .collect::<Vec<_>>()
        .join("\n");
    super::negative::check(&clean(source), &joined)?;
    Ok(joined)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cleanup_is_conservative() {
        assert_eq!(
            clean("嗯呃我我今天先去银行然后去医院"),
            "我今天先去银行然后去医院"
        );
        assert_eq!(clean("嗯这个这个项目先测"), "这个项目先测");
        for text in [
            "嗯哼，这个没问题",
            "呃逆不是口头语",
            "他说‘好，嗯我我不去’",
            "他说\"好，我我不去\"",
        ] {
            assert_eq!(clean(text), text);
        }
        for text in [
            "嗯，我知道了。",
            "嗯",
            "他说“嗯我我不去”",
            "不要不要",
            "一周一周以后",
            "人人都有",
            "版本aaa_111.txt",
            "我问他这个这个词是什么意思",
        ] {
            assert_eq!(clean(text), text);
        }
    }
    #[test]
    fn no_missing_words_or_quoted_cleanup_accepted() {
        assert!(finish("嗯我我今天不去", &["我今天不去。".into()], true).is_ok());
        assert!(finish("等检查以后再发", &["等检查再发。".into()], true).is_err());
        assert!(finish("他说“我我”", &["他说“我”。".into()], true).is_err());
        assert!(finish("金额1.5元", &["金额15元。".into()], true).is_err());
        assert!(finish("今天开会", &[], true).is_err());
        assert!(finish("今天开会", &["今天开会。".into()], false).is_err());
    }
}
