//! 安卓引擎会话：复用上游输入、翻译、符号与学习，向壳提供展示帧。
use qingjian_core::{CandidateList, EmojiTable, Engine, Language};
use qingjian_dictionary::{Dictionary, WordList};
use qingjian_translate::Glossary;
use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::time::Instant;
mod mobile_panel;
mod personalization;
mod private_meter;
mod settings;

pub struct Session {
    engine: Engine,
    candidates: CandidateList,
    directory: PathBuf,
    user_directory: Option<PathBuf>,
    learning_directory: Option<PathBuf>,
    full_punctuation: bool,
    sentence_model: bool,
    dictionaries_signature: Option<String>,
    meter_enabled: std::sync::Arc<std::sync::atomic::AtomicBool>,
    private_input: bool,
    voice_learning: bool,
    voice_events: std::collections::VecDeque<String>,
}

impl Session {
    pub fn open(directory: &Path) -> Result<Self, String> {
        let dictionary =
            Dictionary::from_path(directory.join("dict.tsv")).map_err(|e| e.to_string())?;
        let glossary = Glossary::from_path(Language::English, directory.join("glossary-en.tsv"))
            .map_err(|e| e.to_string())?;
        let mut engine = Engine::new(dictionary).with_translator(Box::new(glossary));
        let sentence_model = directory.join("lm.qj").exists();
        if sentence_model {
            engine = engine.with_language_model(Box::new(
                qingjian_lm::BigramModel::from_path(&directory.join("lm.qj"))
                    .map_err(|e| e.to_string())?,
            ));
        }
        if directory.join("english.tsv").exists() {
            engine = engine.with_english(
                WordList::from_path(directory.join("english.tsv")).map_err(|e| e.to_string())?,
            );
        }
        if directory.join("glossary-zh.tsv").exists() {
            engine = engine.with_english_translator(Box::new(
                Glossary::from_path(Language::Chinese, directory.join("glossary-zh.tsv"))
                    .map_err(|e| e.to_string())?,
            ));
        }
        if directory.join("emoji-zh.tsv").exists() {
            let mut emoji =
                EmojiTable::from_path(directory.join("emoji-zh.tsv")).map_err(|e| e.to_string())?;
            emoji.merge(
                EmojiTable::from_path(directory.join("emoji-en.tsv")).map_err(|e| e.to_string())?,
            );
            engine = engine.with_emoji(emoji);
        }
        engine.set_private(true);
        Ok(Self {
            engine,
            candidates: CandidateList::default(),
            directory: directory.to_owned(),
            user_directory: None,
            learning_directory: None,
            full_punctuation: true,
            sentence_model,
            dictionaries_signature: None,
            meter_enabled: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
            private_input: true,
            voice_learning: false,
            voice_events: Default::default(),
        })
    }

    pub fn step(&mut self, action: &str, argument: &str) -> Result<Value, String> {
        if action == "voice-alternatives" {
            return Ok(json!({"alternatives": self.engine.speech_alternatives(argument)}));
        }
        let start = Instant::now();
        let mut committed = String::new();
        let mut delete = false;
        let mut learned_voice_words = 0;
        match action {
            "input" => {
                if !argument.chars().all(|c| c.is_ascii_graphic()) {
                    return Err("input must contain printable ASCII keys".into());
                }
                self.engine.close_symbol_panel();
                if self.engine.composition().text().len() + argument.len() <= 128 {
                    for c in argument.chars() {
                        self.engine.push(c);
                    }
                }
            }
            "digit" => {
                let index: usize = argument.parse().map_err(|_| "invalid digit")?;
                if self.engine.is_zhuyin_mode()
                    && !self.engine.english_mode()
                    && self.engine.symbol_panel().is_none()
                {
                    // 壳传的是当前页候选索引；还原实体数字键，注音中数字优先入码。
                    if self.engine.composition().text().len() < 128 {
                        self.engine.push(char::from(b'1' + (index % 9) as u8));
                    }
                } else if self.engine.expression_mode() || self.engine.question_mode() {
                    self.engine.push(char::from(b'1' + (index % 9) as u8));
                } else if let Some(candidate) = self.candidates.items.get(index).filter(|_| {
                    !self.engine.composition().is_empty() || self.engine.symbol_panel().is_some()
                }) {
                    committed = self.engine.commit(candidate);
                } else {
                    committed = self.engine.take_raw();
                    committed.push(char::from(b'1' + (index % 9) as u8));
                    self.engine.break_chain();
                }
            }
            "graphic" => {
                let c = argument.chars().next().ok_or("empty graphic key")?;
                if !c.is_ascii_graphic() || argument.len() != 1 {
                    return Err("invalid graphic key".into());
                }
                if self.engine.expression_mode()
                    || self.engine.question_mode()
                    || (self.engine.is_zhuyin_mode()
                        && !self.engine.english_mode()
                        && qingjian_core::zhuyin::map_key(c).is_some())
                    || (c == ';' && self.engine.takes_semicolon())
                    || self.engine.takes_mode_letter(c)
                    || (self.engine.english_mode() && c.is_ascii_alphabetic())
                {
                    if self.engine.composition().text().len() < 128 {
                        self.engine.push(c);
                    }
                } else {
                    committed = self.engine.take_raw();
                    committed.push(c);
                    self.engine.note_passthrough(c);
                    self.engine.break_chain();
                }
            }
            "delete" => {
                delete = !self.engine.backspace();
                if delete {
                    self.engine.note_backspace();
                    self.engine.break_chain();
                }
            }
            "select" | "translate" | "forget" => {
                let mut parts = argument.split(':');
                let index: usize = parts
                    .next()
                    .unwrap_or("")
                    .parse()
                    .map_err(|_| "invalid candidate index")?;
                let candidate = self
                    .candidates
                    .items
                    .get(index)
                    .ok_or("candidate out of range")?;
                if action == "translate" {
                    let sense = parts
                        .next()
                        .unwrap_or("0")
                        .parse()
                        .map_err(|_| "invalid sense index")?;
                    committed = self
                        .engine
                        .commit_translation(candidate, sense)
                        .unwrap_or_default();
                    self.engine.break_chain();
                } else if action == "forget" {
                    self.engine.forget(candidate);
                } else {
                    committed = self.engine.commit(candidate);
                }
            }
            "space" if !self.engine.english_mode() && self.engine.zhuyin_needs_tone() => {
                if self.engine.composition().text().len() < 128 {
                    self.engine.push(' ');
                }
            }
            "space" => {
                let index = if argument.is_empty() {
                    0
                } else {
                    argument.parse().map_err(|_| "invalid page index")?
                };
                if let Some(candidate) = self.candidates.items.get(index).filter(|_| {
                    !self.engine.composition().is_empty() || self.engine.symbol_panel().is_some()
                }) {
                    committed = self.engine.commit(candidate);
                    if self.engine.english_mode() {
                        committed.push(' ');
                    }
                } else if self.engine.composition().is_empty() {
                    committed.push(' ');
                    self.engine.break_chain();
                } else {
                    committed = self.engine.take_raw();
                }
            }
            "raw" => {
                committed = self.engine.take_raw();
                self.engine.break_chain();
            }
            "punctuate" => {
                // 手机标点键先确认首选，再使用上游全半角与成对引号规则。
                if self.engine.symbol_panel().is_none()
                    && !self.engine.composition().is_empty()
                    && !self.engine.english_mode()
                    && let Some(candidate) = self.candidates.items.first()
                {
                    committed = self.engine.commit(candidate);
                }
                committed.push_str(&self.engine.take_raw());
                for c in argument.chars() {
                    if let Some(text) = self.engine.punctuate(c) {
                        committed.push_str(text);
                    } else {
                        committed.push(c);
                        self.engine.note_passthrough(c);
                    }
                }
                self.engine.break_chain();
            }
            "english" => {
                // 英文无匹配时保留组合区，允许用户直接切回中文纠正模式误触。
                // 已有英文建议及中文转英文仍沿用原样上屏，避免改变正常选词语义。
                if !self.engine.english_mode() || !self.candidates.items.is_empty() {
                    committed = self.engine.take_raw();
                }
                self.engine.set_english_mode(!self.engine.english_mode());
                self.engine.break_chain();
                self.engine.set_full_width_punctuation(
                    self.full_punctuation && !self.engine.english_mode(),
                );
            }
            "configure" => self.configure(argument)?,
            "reset" => {
                self.engine.clear();
                self.engine.break_chain();
                self.persist();
            }
            "cancel" => {
                self.engine.clear();
                self.engine.break_chain();
            }
            "context-break" => self.engine.break_chain(),
            "voice-learn" => learned_voice_words = self.learn_voice(argument)?,
            "block" | "unblock" => self.change_block(action, argument)?,
            "panel" => {
                self.engine.break_chain();
                self.engine.open_symbol_panel();
            }
            "panel-open" => {
                self.engine.break_chain();
                committed = self.open_mobile_panel(argument)?;
            }
            "panel-select" => committed = self.select_mobile_symbol(argument)?,
            "panel-category" => {
                let index = argument.parse().map_err(|_| "invalid category")?;
                if let Some(panel) = self.engine.symbol_panel_mut() {
                    panel.select_section(index);
                }
            }
            "panel-space" => {
                committed.push(' ');
                self.engine.break_chain();
            }
            "panel-delete" => {
                delete = true;
                self.engine.note_backspace();
                self.engine.break_chain();
            }
            "panel-close" => self.engine.close_symbol_panel(),
            "panel-group" | "panel-section" => {
                let delta = argument.parse().map_err(|_| "invalid panel direction")?;
                if let Some(panel) = self.engine.symbol_panel_mut() {
                    if action == "panel-group" {
                        panel.switch_group(delta);
                    } else {
                        panel.switch_section(delta);
                    }
                }
            }
            "displayed" => {
                let indexes: Vec<usize> =
                    serde_json::from_str(argument).map_err(|e| e.to_string())?;
                self.engine
                    .note_displayed(indexes.iter().filter_map(|i| self.candidates.items.get(*i)));
                // 不重新查询，避免把触屏正在浏览的候选页跳回开头。
                return Ok(json!({"input":"", "candidates":[], "commit":"", "delete":false}));
            }
            "query" | "flush" => {}
            _ => return Err("unknown action".into()),
        }
        if !committed.is_empty() || matches!(action, "flush" | "forget" | "voice-learn") {
            self.persist();
        }
        // 输入框使用 Core 的只读原文展示；原始键码继续单独保留用于状态判断。
        let preedit = self.engine.raw_preedit().text;
        let mut marked = preedit.clone();
        self.candidates = if let Some(panel) = self.engine.symbol_panel() {
            CandidateList {
                items: panel.section().items.clone(),
            }
        } else if self.engine.composition().is_empty() {
            self.engine.next_words()
        } else {
            self.engine
                .query()
                .map(|query| {
                    marked = query.marked_text();
                    query.candidates
                })
                .unwrap_or_default()
        };
        self.engine.annotate(&mut self.candidates);
        let candidates: Vec<Value> = self
            .candidates
            .items
            .iter()
            .map(|candidate| {
                let senses: Vec<&str> = candidate
                    .translation
                    .as_ref()
                    .map(|translation| {
                        translation
                            .senses()
                            .iter()
                            .map(|sense| sense.text.as_str())
                            .collect()
                    })
                    .unwrap_or_default();
                let fresh = !self.engine.is_private()
                    && candidate
                        .translation
                        .as_ref()
                        .is_some_and(|t| t.senses().iter().any(|s| s.fresh));
                let gloss = if senses.is_empty() {
                    candidate.reading.clone().unwrap_or_default()
                } else {
                    senses.join(" · ")
                };
                let custom = matches!(candidate.kind, qingjian_core::CandidateKind::Custom(_));
                let display = if custom { qingjian_core::CustomPhrase::preview(&candidate.text, 24) } else { candidate.text.clone() };
                json!({"text":candidate.text, "display":display, "custom":custom, "gloss":gloss, "senses":senses, "fresh":fresh,
                    "blockable":Engine::can_block(candidate)})
            })
            .collect();
        let panel = self
            .engine
            .symbol_panel()
            .map(|p| format!("{} · {}", p.group().label(), p.section().name));
        let (panel_group, panel_sections, panel_category, panel_columns) = self.panel_metadata();
        let scheme = if self.engine.is_zhuyin_mode() {
            "大千注音"
        } else {
            self.engine.shuangpin().map_or("全拼", |s| s.label())
        };
        // 字形来自 Core 的布局表；安卓仅决定按键行列，不维护另一份注音映射。
        let phonetic_keys: serde_json::Map<String, Value> = if self.engine.is_zhuyin_mode() {
            ('!'..='~')
                .filter_map(|key| {
                    qingjian_core::zhuyin::map_key(key)
                        .map(|part| (key.to_string(), json!(part.display_char().to_string())))
                })
                .collect()
        } else {
            Default::default()
        };
        Ok(
            json!({"input":self.engine.composition().text(), "preedit":preedit, "marked":marked,
            "candidates":candidates, "commit":committed, "delete":delete,
            "learnedVoiceWords":learned_voice_words,
            "continuation":self.engine.composition().is_empty() && self.engine.symbol_panel().is_none() && !self.candidates.items.is_empty(),
            "blockAllowed":!self.private_input,
            "blockedWords":if matches!(action,"block"|"unblock") { Some(self.engine.blocked_words()) } else { None },
            "english":self.engine.english_mode(), "expression":self.engine.expression_mode() || self.engine.question_mode(),
            "scheme":scheme, "phoneticKeys":phonetic_keys, "panel":panel,
            "panelGroup":panel_group, "panelSections":panel_sections, "panelCategory":panel_category, "panelColumns":panel_columns,
            "sentenceModel":self.sentence_model, "elapsedMs":start.elapsed().as_secs_f64()*1000.0}),
        )
    }
}

#[cfg(test)]
mod tests;
