//! 语音最终人工纠正只反馈已知词；不保存原文、不训练自动识别/整理草稿。
use super::{Engine, is_hanzi};
use crate::engine::learning::Learner;
use crate::{Candidate, CandidateKind};

impl Engine {
    /// 壳保证用户明确确认且事件去重；这里只接受一个连续替换范围。
    /// 在既有词库里寻找覆盖改动的最短完整词，避免把整段听写学成个人短语。
    pub fn learn_voice_correction(&mut self, original: &str, edited: &str) -> usize {
        if self.learner.muted() || original == edited {
            return 0;
        }
        let before: Vec<_> = original.chars().collect();
        let after: Vec<_> = edited.chars().collect();
        if before.len() > 512 || after.len() > 512 || before.is_empty() || after.is_empty() {
            return 0;
        }
        let start = before
            .iter()
            .zip(&after)
            .take_while(|(a, b)| a == b)
            .count();
        let suffix = before[start..]
            .iter()
            .rev()
            .zip(after[start..].iter().rev())
            .take_while(|(a, b)| a == b)
            .count();
        let end = after.len() - suffix;
        // 只收改词，不从纯插入/删除或较大改写猜测词义。
        if end <= start
            || before.len() - suffix <= start
            || end - start > 8
            || !after[start..end].iter().copied().all(is_hanzi)
        {
            return 0;
        }
        let dictionaries = std::iter::once(&self.dictionary)
            .chain(self.extra_dictionaries.iter())
            .chain(self.learner.user_words());
        let mut best: Option<(usize, u32, String, Vec<String>)> = None;
        for entry in dictionaries.flat_map(|d| d.entries()) {
            let len = entry.text.chars().count();
            if !(2..=12).contains(&len) || len < end - start || !entry.text.chars().all(is_hanzi) {
                continue;
            }
            let covers_edit = (end.saturating_sub(len)..=start).any(|offset| {
                offset + len <= after.len()
                    && offset <= start
                    && offset + len >= end
                    && after[offset..offset + len]
                        .iter()
                        .copied()
                        .eq(entry.text.chars())
            });
            if !covers_edit {
                continue;
            }
            let syllables: Vec<_> = entry.pinyin.split_whitespace().map(str::to_owned).collect();
            if syllables.len() != len {
                continue;
            }
            if best.as_ref().is_none_or(|b| {
                len < b.0
                    || (len == b.0
                        && (entry.frequency > b.1
                            || (entry.frequency == b.1 && entry.text < b.2.as_str())))
            }) {
                best = Some((len, entry.frequency, entry.text.to_owned(), syllables));
            }
        }
        let Some((_, _, text, syllables)) = best else {
            return 0;
        };
        let key = syllables.concat();
        let candidate = Candidate {
            text,
            syllables,
            kind: CandidateKind::Chinese,
            reading: None,
            translation: None,
            aux_code: None,
        };
        self.learner.record(&candidate);
        self.learner.record_choice(&key, &candidate.text);
        self.forget_span_cache();
        1
    }
}
