//! 将原文编译为 llama.cpp 标点语法；字词、引语、文件名及否定连接不能生成改写。

fn punctuation(c: char) -> bool {
    "，。！？、；：,.!?;:".contains(c)
}

fn literal(text: &str) -> String {
    let mut out = String::from("\"");
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

pub fn build(source: &str) -> String {
    let mut units = Vec::new();
    let mut rest = source;
    while let Some(c) = rest.chars().next() {
        let closing = match c {
            '“' => Some('”'),
            '‘' => Some('’'),
            '「' => Some('」'),
            '『' => Some('』'),
            '"' => Some('"'),
            '\'' => Some('\''),
            _ => None,
        };
        let bytes = if let Some(close) = closing {
            rest[c.len_utf8()..]
                .find(close)
                .map(|at| c.len_utf8() + at + close.len_utf8())
                .unwrap_or(rest.len())
        } else if c.is_ascii_alphanumeric() {
            rest.char_indices()
                .find(|(_, ch)| !ch.is_ascii_alphanumeric() && !"._-".contains(*ch))
                .map(|(at, _)| at)
                .unwrap_or(rest.len())
        } else {
            c.len_utf8()
        };
        units.push(&rest[..bytes]);
        rest = &rest[bytes..];
    }
    let forbidden = super::negative::forbidden(source);
    let mut grammar = String::from("root ::=");
    let mut consumed = 0;
    let mut position = 0;
    for (index, unit) in units.iter().enumerate() {
        grammar.push(' ');
        grammar.push_str(&literal(unit));
        consumed += unit.len();
        position += unit
            .chars()
            .filter(|c| !c.is_whitespace() && !punctuation(*c))
            .count();
        if let Some(next) = units.get(index + 1) {
            let temporal_suffix = ["以后", "之后", "之前", "以前", "时"]
                .iter()
                .any(|s| source[consumed..].starts_with(s));
            if !forbidden.contains(&position)
                && !temporal_suffix
                && !unit.ends_with(|c: char| punctuation(c) || c.is_whitespace())
                && !next.starts_with(punctuation)
            {
                grammar.push_str(" sep");
            }
        } else if !unit.ends_with(punctuation) {
            grammar.push_str(" end");
        }
    }
    grammar.push_str("\nsep ::= [，。！？、；：,.!?;:]?\nend ::= [。！？.!?]?\n");
    grammar
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn identifiers_quotes_and_negations_are_atomic() {
        let grammar = build("他说“嗯我我”不要删除a-v2.1.txt");
        assert!(grammar.contains("\"“嗯我我”\""));
        assert!(grammar.contains("\"a-v2.1.txt\""));
        assert!(grammar.contains("\"不\" \"要\" \"删\""));
        assert!(build("完成以后再发").contains("\"成\" \"以\""));
        assert_eq!(literal("\"\\\n"), "\"\\\"\\\\\\n\"");
    }
}
