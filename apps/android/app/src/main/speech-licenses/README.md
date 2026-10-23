# 本机语音组件来源

- 识别运行时：k2-fsa/sherpa-onnx 1.13.8，Apache-2.0，https://github.com/k2-fsa/sherpa-onnx
- 推理运行时：Microsoft ONNX Runtime，MIT；第三方声明见同目录。
- 识别模型：Alibaba / FunAudioLLM SenseVoice Small，2024-07-17 int8 ONNX 导出，中英日韩粤五种语言，FunASR Model Open Source License 1.1。https://github.com/FunAudioLLM/SenseVoice
- 模型分发：https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
- 语音活动检测：Silero VAD，MIT，https://github.com/snakers4/silero-vad

模型权重与运行时代码许可不同；完整协议随安装包保留在本目录。模型只在设备本地运行，不保存录音或识别文本。
