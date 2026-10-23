//! 与已验证引擎配套的固定模型清单；仓库修订固定，网络内容必须通过散列校验。
package dev.qingstudy.ime

internal object VoiceModelManifest {
    private const val repository = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"
    const val version = "sensevoice-2024-07-17-silero-v1"
    val files = listOf(
        VoiceModelFile("model.int8.onnx", 239233841L, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51", "$repository/model.int8.onnx"),
        VoiceModelFile("tokens.txt", 315894L, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc", "$repository/tokens.txt"),
        VoiceModelFile("silero_vad.onnx", 643854L, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"),
    )
}
