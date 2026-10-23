//! 用户显式管理与确认事件；去重窗口不保存听写文本。
use super::Session;
use serde_json::Value;

impl Session {
    pub(super) fn change_block(&mut self, action: &str, argument: &str) -> Result<(), String> {
        if self.private_input {
            return Err("私密输入不记录候选屏蔽".into());
        }
        if action == "unblock" {
            self.engine.unblock_word(argument);
        } else {
            let index: usize = argument.parse().map_err(|_| "invalid candidate index")?;
            let candidate = self
                .candidates
                .items
                .get(index)
                .ok_or("candidate out of range")?;
            self.engine.block_candidate(candidate)?;
        }
        Ok(())
    }

    pub(super) fn learn_voice(&mut self, argument: &str) -> Result<usize, String> {
        if argument.len() > 8192 {
            return Err("语音纠正事件过长".into());
        }
        let event: Value = serde_json::from_str(argument).map_err(|e| e.to_string())?;
        let id = event["id"]
            .as_str()
            .filter(|id| !id.is_empty() && id.len() <= 1024)
            .ok_or("缺少语音确认标识")?;
        if self.voice_events.iter().any(|old| old == id) {
            return Ok(0);
        }
        // 即使学习暂停，也消费标识，不能在之后开启学习时补记私密事件。
        self.voice_events.push_back(id.to_owned());
        if self.voice_events.len() > 32 {
            self.voice_events.pop_front();
        }
        if self.voice_learning && !self.private_input {
            return Ok(self.engine.learn_voice_correction(
                event["original"].as_str().unwrap_or(""),
                event["edited"].as_str().unwrap_or(""),
            ));
        }
        Ok(0)
    }
}
