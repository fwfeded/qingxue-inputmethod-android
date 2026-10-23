"""按已批准预览公式生成九个短 PCM 键音；原创合成，不使用参考 APK 音频。"""
from pathlib import Path
import math
import struct
import wave

root = Path(__file__).resolve().parents[1] / "app/src/main/assets/feedback"
root.mkdir(parents=True, exist_ok=True)
for tone, pitch, duration in [("soft", .65, .018), ("crisp", 1.6, .032), ("low", .32, .045)]:
    for kind, freq in [("letter", 1250), ("delete", 620), ("action", 880)]:
        phase, data = 0., []
        for i in range(round(48000 * duration)):
            t = i / 48000
            f = freq * pitch * (120 / freq) ** min(1, t / (duration * .72))
            phase += f / 48000
            sample = math.sin(2 * math.pi * phase) if tone == "crisp" else 2 / math.pi * math.asin(math.sin(2 * math.pi * phase))
            gain = .6 * math.exp(math.log(.0001 / .6) * t / duration) * min(1, i / 24)
            data.append(struct.pack("<h", round(32767 * sample * gain)))
        with wave.open(str(root / f"{tone}_{kind}.wav"), "wb") as out:
            out.setparams((1, 2, 48000, 0, "NONE", "not compressed")); out.writeframes(b"".join(data))
