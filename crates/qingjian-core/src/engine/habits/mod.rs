//! 个人续词与显式屏蔽；只使用已有学习表，不加载额外模型。
use super::Engine;
use super::learning::Learner;
use crate::{Candidate, CandidateKind, CandidateList};
use std::collections::BTreeSet;
mod voice;

#[derive(Default)]
pub(super) struct Habits {
    next_words: bool,
    blocked: BTreeSet<String>,
}

impl Engine {
    /// 续词单独开关；总学习关闭及私密输入仍优先禁止。
    pub fn set_next_words(&mut self, enabled: bool) {
        self.habits.next_words = enabled;
    }

    /// 明确的用户偏好，独立于隐式学习；过长、控制字符及超量条目拒绝。
    pub fn set_blocked_words(&mut self, words: Vec<String>) -> Result<(), String> {
        if words.len() > 256 || words.iter().any(|w| !valid_block(w)) {
            return Err("屏蔽列表最多 256 项，每项 1–64 字且不能含控制字符".into());
        }
        self.habits.blocked = words
            .into_iter()
            .map(|word| self.traditional.restore(&word).unwrap_or(word))
            .collect();
        Ok(())
    }

    pub fn blocked_words(&self) -> Vec<String> {
        self.habits.blocked.iter().cloned().collect()
    }

    pub fn can_block(candidate: &Candidate) -> bool {
        matches!(
            candidate.kind,
            CandidateKind::Chinese
                | CandidateKind::Sentence
                | CandidateKind::English
                | CandidateKind::Code
                | CandidateKind::Cloud
        ) && valid_block(&candidate.text)
    }

    pub fn block_candidate(&mut self, candidate: &Candidate) -> Result<(), String> {
        if !Self::can_block(candidate) {
            return Err("此候选不能屏蔽".into());
        }
        let word = self
            .traditional
            .restore(&candidate.text)
            .unwrap_or_else(|| candidate.text.clone());
        if self.habits.blocked.len() >= 256 && !self.habits.blocked.contains(&word) {
            return Err("屏蔽列表已满，请先在个人学习中恢复部分词".into());
        }
        self.habits.blocked.insert(word);
        Ok(())
    }

    pub fn unblock_word(&mut self, word: &str) {
        let word = self
            .traditional
            .restore(word)
            .unwrap_or_else(|| word.to_owned());
        self.habits.blocked.remove(&word);
    }

    pub(super) fn filter_blocked(&self, candidates: &mut CandidateList) {
        if self.habits.blocked.is_empty() {
            return;
        }
        candidates.items.retain(|candidate| {
            let word = self
                .traditional
                .restore(&candidate.text)
                .unwrap_or_else(|| candidate.text.clone());
            !Self::can_block(candidate) || !self.habits.blocked.contains(&word)
        });
    }

    /// 空组合串时提供有真实个人词序依据的中文候选；开始打字立即停止续词。
    pub fn next_words(&self) -> CandidateList {
        if !self.habits.next_words
            || self.learner.muted()
            || self.english_mode
            || !self.composition.is_empty()
            || self.panel.is_some()
        {
            return CandidateList::default();
        }
        let Some(ngram) = self.learner.user_ngram() else {
            return CandidateList::default();
        };
        let mut candidates = CandidateList {
            items: ngram
                .successors(self.chain.context())
                .into_iter()
                .filter(|word| word.chars().count() <= 12 && word.chars().all(is_hanzi))
                .filter(|word| !self.habits.blocked.contains(*word))
                .take(8)
                .map(|word| Candidate {
                    text: word.to_owned(),
                    kind: CandidateKind::Chinese,
                    syllables: Vec::new(),
                    reading: None,
                    translation: None,
                    aux_code: None,
                })
                .collect(),
        };
        self.traditional.apply(&mut candidates);
        self.filter_blocked(&mut candidates);
        candidates
    }
}

fn valid_block(word: &str) -> bool {
    !word.trim().is_empty() && word.chars().count() <= 64 && !word.chars().any(char::is_control)
}

fn is_hanzi(c: char) -> bool {
    matches!(c, '\u{3400}'..='\u{9fff}' | '\u{20000}'..='\u{2fa1f}')
}
