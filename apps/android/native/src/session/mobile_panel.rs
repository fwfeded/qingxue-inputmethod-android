//! 安卓面板手势到 Core 的桥接：直接分组入口、分类元数据、连续选择。
use super::Session;

impl Session {
    pub(super) fn open_mobile_panel(&mut self, group: &str) -> Result<String, String> {
        if !matches!(group, "符号" | "表情") {
            return Err("invalid panel group".into());
        }
        let mut committed = String::new();
        if self.engine.symbol_panel().is_none() {
            if !self.engine.english_mode()
                && let Some(candidate) = self.candidates.items.first()
            {
                committed = self.engine.commit(candidate);
            }
            committed.push_str(&self.engine.take_raw());
            self.engine.open_symbol_panel();
        }
        if let Some(panel) = self.engine.symbol_panel_mut()
            && let Some(index) = panel.groups().iter().position(|g| g.label() == group)
        {
            panel.select_group(index);
        }
        Ok(committed)
    }

    pub(super) fn select_mobile_symbol(&mut self, argument: &str) -> Result<String, String> {
        let panel = self.engine.symbol_panel().ok_or("panel is closed")?.clone();
        let index: usize = argument.parse().map_err(|_| "invalid symbol index")?;
        let candidate = self
            .candidates
            .items
            .get(index)
            .ok_or("symbol out of range")?;
        let committed = self.engine.commit(candidate);
        self.engine.open_symbol_panel();
        if let Some(current) = self.engine.symbol_panel_mut() {
            *current = panel;
        }
        Ok(committed)
    }

    pub(super) fn panel_metadata(&self) -> (String, Vec<String>, usize, usize) {
        self.engine.symbol_panel().map_or_else(
            || (String::new(), Vec::new(), 0, 6),
            |panel| {
                let (sections, index) = panel.group_sections();
                (
                    panel.group().label().into(),
                    sections.into_iter().map(str::to_owned).collect(),
                    index,
                    panel.section().columns(),
                )
            },
        )
    }
}
