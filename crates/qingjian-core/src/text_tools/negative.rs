//! 防止仅增加标点就把否定词与后续动作断开；歧义时保留原文而不猜测。

use std::collections::BTreeSet;

fn punctuation(c: char) -> bool {
    "，。！？、；：,.!?;:".contains(c)
}

fn count(text: &str) -> usize {
    text.chars()
        .filter(|c| !c.is_whitespace() && !punctuation(*c))
        .count()
}

pub(super) fn forbidden(source: &str) -> BTreeSet<usize> {
    let mut forbidden = BTreeSet::new();
    for (at, _) in source.char_indices() {
        for word in [
            "不", "没", "别", "勿", "未", "不要", "不能", "不许", "不得", "不用", "没有",
        ] {
            if !source[at..].starts_with(word) {
                continue;
            }
            let end = at + word.len();
            if source[end..]
                .chars()
                .next()
                .is_some_and(|c| !punctuation(c) && !c.is_whitespace())
            {
                forbidden.insert(count(&source[..end]));
            }
        }
    }
    forbidden
}

pub(super) fn check(source: &str, output: &str) -> Result<(), &'static str> {
    let forbidden = forbidden(source);
    let mut position = 0;
    for c in output.chars() {
        if (punctuation(c) || c == '\n') && forbidden.contains(&position) {
            return Err("标点可能把否定与动作拆开，请保留原文并手动核对");
        }
        if !c.is_whitespace() && !punctuation(c) {
            position += 1;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::check;
    #[test]
    fn inserted_pause_cannot_reverse_prohibition() {
        assert!(
            check(
                "不要不要现在发我还没检查完",
                "不要不要，现在发，我还没检查完。"
            )
            .is_err()
        );
        assert!(check("不要今天转账", "不要，今天转账。").is_err());
        assert!(check("不要今天转账", "不要\n今天转账。").is_err());
        assert!(
            check(
                "不要不要现在发我还没检查完",
                "不要不要现在发，我还没检查完。"
            )
            .is_ok()
        );
        assert!(check("没有，请重试", "没有，请重试。").is_ok());
        assert!(check("请先保存不要删除", "请先保存，不要删除。").is_ok());
    }
}
