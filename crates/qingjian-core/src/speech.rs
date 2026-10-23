//! 离线听写的确定性格式与口令策略，不推断语义，不影响候选学习。
use serde::{Deserialize, Serialize};

#[derive(Default, Deserialize)]
#[serde(default)]
pub struct SpeechOptions {
    pub punctuation: String,
    pub blank_lines: bool,
    pub aggressive: bool,
    pub commands: Vec<String>,
}

#[derive(Debug, Serialize)]
pub struct SpeechText {
    pub text: String,
    pub send: bool,
}

pub fn format_speech(source: &str, options: &SpeechOptions, final_result: bool) -> SpeechText {
    let mut text = source.trim().to_owned();
    let mut send = false;
    if final_result {
        let tail = text.trim_end_matches(|c: char| c.is_whitespace() || punctuation(c));
        for command in &options.commands {
            if command.is_empty() {
                continue;
            }
            if let Some(prefix) = tail.strip_suffix(command) {
                // Latin commands must have a word boundary, not match inside a word.
                if command.is_ascii()
                    && prefix
                        .chars()
                        .last()
                        .is_some_and(|c| c.is_ascii_alphanumeric())
                {
                    continue;
                }
                text = prefix
                    .trim_end_matches(|c: char| c.is_whitespace() || punctuation(c))
                    .to_owned();
                send = true;
                break;
            }
        }
    }
    if options.aggressive {
        // Only complete hesitation tokens followed by punctuation; no semantic deletion.
        for filler in ["嗯，", "呃，", "额，", "嗯,", "呃,", "额,"] {
            if let Some(rest) = text.strip_prefix(filler) {
                text = rest.trim_start().to_owned();
            }
        }
    }
    match options.punctuation.as_str() {
        "end" => text = text.trim_end_matches(punctuation).to_owned(),
        "space" => {
            let chars: Vec<char> = text.chars().collect();
            text = chars
                .iter()
                .enumerate()
                .map(|(i, &c)| {
                    let numeric = matches!(c, '.' | ':' | ',' | '-')
                        && i > 0
                        && i + 1 < chars.len()
                        && chars[i - 1].is_ascii_digit()
                        && chars[i + 1].is_ascii_digit();
                    if punctuation(c) && !numeric { ' ' } else { c }
                })
                .collect::<String>();
            text = text.trim().to_owned();
        }
        _ => {}
    }
    let separator = if options.blank_lines { "\n\n" } else { "\n" };
    text = text
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .collect::<Vec<_>>()
        .join(separator);
    SpeechText { text, send }
}

fn punctuation(c: char) -> bool {
    "，。！？；：、,.!?;:…".contains(c)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn send_only_for_enabled_trailing_command_in_final_result() {
        let o = SpeechOptions {
            commands: vec!["帮我发送".into(), "overover".into()],
            ..Default::default()
        };
        assert!(!format_speech("明天见，帮我发送", &o, false).send);
        let result = format_speech("明天见，帮我发送。", &o, true);
        assert!(result.send);
        assert_eq!(result.text, "明天见");
        assert!(!format_speech("帮我发送这个文件", &o, true).send);
        assert!(!format_speech("coverover", &o, true).send);
        assert!(!format_speech("帮我发送", &SpeechOptions::default(), true).send);
    }
    #[test]
    fn format_keeps_numbers_and_original_words() {
        let o = SpeechOptions {
            punctuation: "space".into(),
            aggressive: true,
            ..Default::default()
        };
        assert_eq!(
            format_speech("嗯，10:30见，带3.14元。", &o, true).text,
            "10:30见 带3.14元"
        );
        assert_eq!(
            format_speech("那个文件，然后打开。", &o, true).text,
            "那个文件 然后打开"
        );
    }
    #[test]
    fn end_punctuation_and_paragraphs() {
        let o = SpeechOptions {
            punctuation: "end".into(),
            blank_lines: true,
            ..Default::default()
        };
        assert_eq!(
            format_speech("第一句。\n第二句！", &o, true).text,
            "第一句。\n\n第二句"
        );
    }
}
