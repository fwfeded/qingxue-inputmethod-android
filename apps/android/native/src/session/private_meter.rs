//! 安卓隐私开关的统计适配器，计数算法仍由上游实现。
use qingjian_core::{Usage, UsageMeter, UsageSummary};
use qingjian_learning::UsageStats;
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
pub(super) struct PrivateMeter {
    pub stats: UsageStats,
    pub enabled: Arc<AtomicBool>,
}
impl UsageMeter for PrivateMeter {
    fn record(&mut self, usage: Usage) {
        if self.enabled.load(Ordering::Relaxed) {
            self.stats.record(usage);
        }
    }
    fn flush(&mut self) {
        self.stats.flush();
    }
    fn summary(&self) -> UsageSummary {
        self.stats.summary()
    }
}
