//! 安卓设置与本地学习数据装配；私有数据目录由已解锁的安卓壳提供。
use super::Session;
use qingjian_core::{FuzzyRules, Language, NoTranslator, ShuangpinScheme, TraditionalVariant};
use qingjian_learning::{FrequencyLearner, UsageStats, VocabularyBook};
use qingjian_translate::{Glossary, LevelTable};
use serde_json::{Value, json};

impl Session {
    pub(super) fn configure(&mut self, argument: &str) -> Result<(), String> {
        let settings: Value = serde_json::from_str(argument).map_err(|e| e.to_string())?;
        if let Some(root) = settings["directory"].as_str().filter(|p| !p.is_empty()) {
            let root = std::path::PathBuf::from(root);
            let _guard = crate::learning_backup::lock(&root)?;
            let path = crate::learning_backup::active_directory(&root)?;
            if self.learning_directory.as_ref() != Some(&path) {
                let learner = FrequencyLearner::from_path(path.join("frequency.tsv"))
                    .map_err(|e| e.to_string())?;
                let replacement = Self::open(&self.directory)?;
                let mut vocabulary = VocabularyBook::open(path.join("vocabulary.tsv"));
                // 随包等级表可选；损坏时继续基础输入和词汇记录，不编造分级数字。
                if let Ok(table) = LevelTable::from_path(self.directory.join("levels-en.tsv")) {
                    vocabulary = vocabulary.with_levels(Language::English, table);
                }
                self.engine = replacement
                    .engine
                    .with_learner(Box::new(learner))
                    .with_usage_meter(Box::new(super::private_meter::PrivateMeter {
                        stats: UsageStats::open(path.join("usage.tsv")),
                        enabled: self.meter_enabled.clone(),
                    }))
                    .with_vocabulary_tracker(Box::new(vocabulary));
                self.user_directory = Some(root);
                self.learning_directory = Some(path);
                self.candidates = Default::default();
                self.dictionaries_signature = None;
            }
        }
        // 与上游一致：只关闭中文候选的学习语言，英文候选的中文释义不变。
        let translations = settings["learningLanguage"].as_str() != Some("off");
        if translations != (self.engine.learning_language() == Language::English) {
            if translations {
                let glossary =
                    Glossary::from_path(Language::English, self.directory.join("glossary-en.tsv"))
                        .map_err(|e| e.to_string())?;
                self.engine.set_translator(Box::new(glossary));
            } else {
                self.engine.set_translator(Box::new(NoTranslator));
            }
            // 切换前可见的译词不能在之后的提交中补记为曝光。
            self.engine.note_displayed(std::iter::empty());
        }
        let bundled = self.directory.join("domains");
        let selected = crate::domains::selected(&settings);
        let user = self.user_directory.as_ref().map(|path| path.join("dicts"));
        let signature = format!(
            "{};{}",
            crate::domains::signature(&bundled, &selected),
            user.as_ref()
                .map(|path| crate::dictionaries::signature(path))
                .transpose()?
                .unwrap_or_default()
        );
        if self.dictionaries_signature.as_ref() != Some(&signature) {
            let mut dictionaries = crate::domains::load(&bundled, &selected);
            if let Some(path) = user {
                dictionaries.extend(crate::dictionaries::load(&path)?);
            }
            self.engine.set_extra_dictionaries(dictionaries);
            self.dictionaries_signature = Some(signature);
        }
        // 手工损坏的短语配置不应阻断基础输入；设置页保留原文件并报告校验错误。
        let phrases = crate::phrases::parse(&settings["customPhrases"]).unwrap_or_default();
        self.engine.set_custom_phrases(phrases)?;
        let scheme = settings["scheme"].as_str().unwrap_or("pinyin");
        self.engine
            .set_shuangpin(ShuangpinScheme::ALL.into_iter().find(|s| s.key() == scheme));
        self.engine.set_zhuyin_mode(scheme == "zhuyin");
        self.engine.set_traditional(
            TraditionalVariant::ALL
                .into_iter()
                .find(|v| v.key() == settings["traditional"].as_str().unwrap_or("off"))
                .unwrap_or_default(),
        );
        let mut fuzzy = FuzzyRules::default();
        for name in FuzzyRules::NAMES {
            fuzzy.set(name, settings[name].as_bool().unwrap_or(false));
        }
        self.engine.set_fuzzy(fuzzy);
        self.engine
            .set_english_in_chinese(settings["mixed"].as_bool().unwrap_or(true));
        self.engine
            .set_chinese_first(settings["chineseFirst"].as_bool().unwrap_or(false));
        self.engine
            .set_auto_space(settings["autoSpace"].as_bool().unwrap_or(false));
        self.full_punctuation = settings["punctuation"].as_bool().unwrap_or(true);
        self.engine
            .set_full_width_punctuation(self.full_punctuation && !self.engine.english_mode());
        let private = settings["private"].as_bool().unwrap_or(false)
            || !settings["learning"].as_bool().unwrap_or(false)
            || self.user_directory.is_none();
        if private != self.engine.is_private() {
            self.engine.break_chain();
        }
        self.engine.set_private(private);
        // 旧 learning 键继续控制全部记录；新开关仅暂停输入习惯写入，已有排序仍可读。
        self.engine
            .set_learning(settings["habitLearning"].as_bool().unwrap_or(true));
        self.private_input = settings["private"].as_bool().unwrap_or(false);
        self.voice_learning = settings["voiceCorrectionLearning"]
            .as_bool()
            .unwrap_or(true);
        self.engine
            .set_next_words(settings["nextWordSuggestions"].as_bool().unwrap_or(true));
        let blocked = settings["blockedWords"]
            .as_array()
            .map(|words| {
                words
                    .iter()
                    .map(|word| word.as_str().map(str::to_owned).ok_or("屏蔽词必须为文本"))
                    .collect::<Result<Vec<_>, _>>()
            })
            .transpose()?
            .unwrap_or_default();
        self.engine.set_blocked_words(blocked)?;
        self.meter_enabled.store(
            !self.engine.is_private(),
            std::sync::atomic::Ordering::Relaxed,
        );
        let emoji = settings["emoji"].as_bool().unwrap_or(true);
        self.engine.set_emoji_limit(if emoji { 3 } else { 0 });
        self.engine.set_kaomoji_limit(usize::from(emoji));
        self.engine.set_symbol_limit(usize::from(emoji));
        Ok(())
    }

    pub(super) fn persist(&mut self) {
        // IME 与设置页在不同进程；让导出只看到一次完整 flush 的前态或后态。
        let _snapshot_lock = if let Some(path) = &self.user_directory {
            match crate::learning_backup::lock(path) {
                Ok(guard) => Some(guard),
                Err(_) => return,
            }
        } else {
            None
        };
        // 恢复后，仍存活的旧会话不能回写旧计数或污染保留的旧代。
        if let Some(root) = &self.user_directory
            && crate::learning_backup::active_directory(root).ok().as_ref()
                != self.learning_directory.as_ref()
        {
            return;
        }
        self.engine.flush_learning();
        if let Some(path) = &self.learning_directory {
            let usage = self.engine.usage_summary();
            let words = self.engine.vocabulary_summary();
            let levels: Vec<Value> = words
                .levels
                .iter()
                .map(|level| {
                    json!({
                        "name":level.name, "total":level.total, "seen":level.seen,
                        "familiar":level.familiar, "committed":level.committed
                    })
                })
                .collect();
            let summary = json!({"today": usage.today.hanzi, "total": usage.total.hanzi,
                "english": usage.total.english_words, "days": usage.days,
                "seen": words.seen, "familiar": words.familiar,
                "committed": words.committed, "used": words.used, "levels":levels,
                "learningLanguage": if self.engine.learning_language() == Language::English { "en" } else { "off" }});
            // 汇总可重建；写失败不影响输入，也不覆盖原始学习表。
            let _ = qingjian_core::storage::write_atomic_str(
                &path.join("summary.json"),
                &summary.to_string(),
            );
        }
    }
}
