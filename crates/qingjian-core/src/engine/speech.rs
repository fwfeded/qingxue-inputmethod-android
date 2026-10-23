use super::Engine;

impl Engine {
    /// 听写选区的同音候选；只读查询，不改变拼音组合态、不记学习或曝光。
    /// 只扫描现有词库，不为语音常驻另一份反查大表。
    pub fn speech_alternatives(&self, word: &str) -> Vec<(String, String)> {
        if word.is_empty() || word.chars().count() > 12 {
            return Vec::new();
        }
        let dictionaries = std::iter::once(&self.dictionary).chain(self.extra_dictionaries.iter());
        let readings: std::collections::BTreeSet<&str> = dictionaries
            .clone()
            .flat_map(|dict| dict.entries())
            .filter(|entry| entry.text == word)
            .map(|entry| entry.pinyin)
            .take(4)
            .collect();
        let mut matches = Vec::new();
        for reading in readings {
            let syllables: Vec<_> = reading.split_whitespace().collect();
            for dictionary in dictionaries.clone() {
                matches.extend(
                    dictionary
                        .lookup(&syllables, false)
                        .into_iter()
                        .filter(|entry| entry.exact),
                );
            }
        }
        matches.sort_by(|a, b| {
            b.frequency
                .cmp(&a.frequency)
                .then_with(|| a.text.cmp(b.text))
        });
        let mut seen = std::collections::HashSet::new();
        matches
            .into_iter()
            .filter(|entry| seen.insert(entry.text))
            .take(8)
            .map(|entry| {
                let gloss = self
                    .translator
                    .translate(entry.text)
                    .map(|translation| {
                        translation
                            .senses()
                            .iter()
                            .map(|sense| sense.text.as_str())
                            .collect::<Vec<_>>()
                            .join("; ")
                    })
                    .unwrap_or_default();
                (entry.text.to_owned(), gloss)
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn spoken_selection_alternatives_do_not_mutate_composition() {
        let dict = qingjian_dictionary::Dictionary::parse("公园\tgong yuan\t100\n公元\tgong yuan\t90\n公园路\tgong yuan lu\t200\n你好\tni hao\t50\n").unwrap();
        let mut engine = Engine::new(dict);
        engine.push('n');
        let result = engine.speech_alternatives("公园");
        assert_eq!(
            result.iter().map(|x| x.0.as_str()).collect::<Vec<_>>(),
            ["公园", "公元"]
        );
        assert_eq!(engine.composition().text(), "n");
        assert!(engine.speech_alternatives("不存在").is_empty());
    }
}
