//! llama.cpp 的受控 Android JNI：单工作线程、取消令牌、F16 KV、逐次释放上下文。
#include "llama.h"
#include "nlohmann/json.hpp"
#include <jni.h>
#include <atomic>
#include <memory>
#include <string>
#include <vector>
#include <stdexcept>
#include <fstream>

static std::atomic<long long> active{0};
static llama_model * model = nullptr;
static bool initialized = false;
static std::string bytes(JNIEnv *env, jbyteArray data) {
    std::string value(env->GetArrayLength(data), '\0');
    env->GetByteArrayRegion(data, 0, value.size(), reinterpret_cast<jbyte *>(value.data()));
    return value;
}
static bool aborted(void *id) { return active.load() != *static_cast<long long *>(id); }
static bool loading(float, void *id) { return !aborted(id); }
static void quiet(enum ggml_log_level, const char *, void *) {}
extern "C" JNIEXPORT void JNICALL Java_dev_qingstudy_ime_TextNative_select(JNIEnv *, jobject, jlong id) { active.store(id); }
extern "C" JNIEXPORT void JNICALL Java_dev_qingstudy_ime_TextNative_release(JNIEnv *, jobject) {
    if (model) { llama_model_free(model); model = nullptr; }
}
static jbyteArray generate(JNIEnv *env, jbyteArray path, jbyteArray input, jbyteArray grammar, jlong request) {
    nlohmann::json result;
    const int64_t began = ggml_time_us();
    long long id = request;
    try {
        if (aborted(&id)) throw std::runtime_error("已取消");
        if (!initialized) { llama_log_set(quiet, nullptr); llama_backend_init(); initialized = true; }
        if (!model) {
            auto p = llama_model_default_params();
            p.n_gpu_layers = 0; p.use_extra_bufts = false; p.load_mode = LLAMA_LOAD_MODE_MMAP;
            p.progress_callback = loading; p.progress_callback_user_data = &id;
            model = llama_model_load_from_file(bytes(env, path).c_str(), p);
        }
        if (!model || aborted(&id)) throw std::runtime_error("模型加载未完成或已取消");
        const auto vocab = llama_model_get_vocab(model);
        auto prompt = bytes(env, input);
        int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (count <= 0 || count > 1200) throw std::runtime_error("原文过长，请分段整理");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) < 0) throw std::runtime_error("文字编码失败");
        auto p = llama_context_default_params();
        p.n_ctx = 2048; p.n_batch = 128; p.n_ubatch = 64; p.n_threads = 2; p.n_threads_batch = 2;
        p.type_k = GGML_TYPE_F16; p.type_v = GGML_TYPE_F16; p.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        p.abort_callback = aborted; p.abort_callback_data = &id;
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(model, p), llama_free);
        if (!ctx) throw std::runtime_error("无法分配推理内存，请关闭其他应用后重试");
        result["loadMs"] = (ggml_time_us() - began) / 1000;
        std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(ctx.get()), llama_batch_ext_free);
        auto feed = [&](const llama_token *ids, int n, int pos) {
            if (aborted(&id)) throw std::runtime_error("已取消");
            if (ggml_time_us() - began > 120000000) throw std::runtime_error("整理超时，请缩短原文");
            llama_batch_ext_clear(batch.get());
            for (int i = 0; i < n; ++i) {
                int idx = llama_batch_ext_add_token(batch.get(), 0, ids[i]);
                llama_pos at = pos + i; llama_batch_ext_set_pos(batch.get(), idx, &at);
            }
            llama_batch_ext_set_output_logits(batch.get(), n - 1, true);
            if (llama_process(ctx.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get())) throw std::runtime_error("推理中断，已保留原文");
        };
        for (int pos = 0; pos < count; pos += 128) feed(tokens.data() + pos, std::min(128, count - pos), pos);
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (grammar) {
            auto constrained = llama_sampler_init_grammar(vocab, bytes(env, grammar).c_str(), "root");
            if (!constrained) throw std::runtime_error("原文保护规则初始化失败");
            llama_sampler_chain_add(sampler.get(), constrained);
        }
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        std::string output; bool complete = false;
        for (int i = 0; i < 768 && count + i < 2048; ++i) {
            auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { complete = true; break; }
            char piece[512]; int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (n < 0) throw std::runtime_error("结果编码失败");
            output.append(piece, n);
            if (i == 0) result["firstMs"] = (ggml_time_us() - began) / 1000;
            feed(&token, 1, count + i);
        }
        if (aborted(&id)) throw std::runtime_error("已取消");
        result["text"] = output; result["complete"] = complete;
        // 活跃上下文仍在内存时取一次 PSS；这是采样值，不宣称整轮峰值。
        std::ifstream memory("/proc/self/smaps_rollup"); std::string line;
        while (std::getline(memory, line)) if (line.rfind("Pss:", 0) == 0) result["activePssKb"] = std::stoll(line.substr(4));
    } catch (const std::exception &error) { result["error"] = error.what(); }
    result["totalMs"] = (ggml_time_us() - began) / 1000;
    auto encoded = result.dump(-1, ' ', false, nlohmann::json::error_handler_t::replace);
    auto array = env->NewByteArray(encoded.size());
    env->SetByteArrayRegion(array, 0, encoded.size(), reinterpret_cast<const jbyte *>(encoded.data()));
    return array;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_qingstudy_ime_TextNative_generate(
    JNIEnv *env, jobject, jbyteArray path, jbyteArray input, jlong request) {
    return generate(env, path, input, nullptr, request);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_qingstudy_ime_TextNative_generateConstrained(
    JNIEnv *env, jobject, jbyteArray path, jbyteArray input, jbyteArray grammar, jlong request) {
    return generate(env, path, input, grammar, request);
}
