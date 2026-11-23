// llama.cpp bridge for the Android tablet build.
// Each Context owns one model + KV cache so translation and assistant models
// can be loaded independently.

#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct LlamaContext {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    common_chat_templates_ptr templates;
    bool has_template = false;
    std::mutex mutex;
    std::atomic<bool> cancel{false};
    int32_t n_ctx = 2048;
    int32_t n_batch = 512;
    llama_batch prompt_batch{};
    llama_batch single_batch{};
    bool batches_ready = false;
    std::vector<llama_token> cached_tokens;
};

static bool llama_abort_cb(void *data) {
    auto *h = static_cast<LlamaContext *>(data);
    return h && h->cancel.load(std::memory_order_relaxed);
}

// Returns how many leading bytes of `s` can be emitted immediately.
// Only an incomplete multi-byte UTF-8 sequence at the very end of `s` is held back;
// invalid bytes are allowed through so utf8_to_utf16 turns them into U+FFFD instead of
// stalling all subsequent streaming tokens forever.
static size_t utf8_flushable_len(const std::string &s) {
    size_t i = 0;
    const size_t len = s.size();
    while (i < len) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        size_t need = 0;
        if (c < 0x80) {
            i += 1;
            continue;
        } else if ((c & 0xE0) == 0xC0) {
            need = 2;
        } else if ((c & 0xF0) == 0xE0) {
            need = 3;
        } else if ((c & 0xF8) == 0xF0) {
            need = 4;
        } else {
            // Invalid lead byte: advance past it so it is replaced rather than stalling the stream.
            i += 1;
            continue;
        }
        if (i + need > len) {
            // Check whether existing trailing bytes are valid continuation bytes; if so, wait for more.
            bool partial_ok = true;
            for (size_t k = i + 1; k < len; ++k) {
                if ((static_cast<unsigned char>(s[k]) & 0xC0) != 0x80) {
                    partial_ok = false;
                    break;
                }
            }
            if (partial_ok) {
                return i;
            }
            // Broken sequence even before end: let it flush as U+FFFD.
            i += 1;
            continue;
        }
        bool valid = true;
        for (size_t k = 1; k < need; ++k) {
            if ((static_cast<unsigned char>(s[i + k]) & 0xC0) != 0x80) {
                valid = false;
                break;
            }
        }
        i += valid ? need : 1;
    }
    return len;
}

static std::u16string utf8_to_utf16(const std::string &s) {
    std::u16string out;
    out.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        uint32_t cp = 0;
        unsigned char c = s[i];
        if (c < 0x80) {
            cp = c;
            i += 1;
        } else if ((c >> 5) == 0x6 && i + 1 < s.size()) {
            cp = ((c & 0x1F) << 6) | (s[i + 1] & 0x3F);
            i += 2;
        } else if ((c >> 4) == 0xE && i + 2 < s.size()) {
            cp = ((c & 0x0F) << 12) | ((s[i + 1] & 0x3F) << 6) | (s[i + 2] & 0x3F);
            i += 3;
        } else if ((c >> 3) == 0x1E && i + 3 < s.size()) {
            cp = ((c & 0x07) << 18) | ((s[i + 1] & 0x3F) << 12) |
                 ((s[i + 2] & 0x3F) << 6) | (s[i + 3] & 0x3F);
            i += 4;
        } else {
            // Invalid byte: replace and advance.
            cp = 0xFFFD;
            i += 1;
        }
        if (cp <= 0xFFFF) {
            out.push_back((char16_t) cp);
        } else {
            cp -= 0x10000;
            out.push_back((char16_t) (0xD800 + (cp >> 10)));
            out.push_back((char16_t) (0xDC00 + (cp & 0x3FF)));
        }
    }
    return out;
}

static jstring to_jstring(JNIEnv *env, const std::string &s) {
    std::u16string u = utf8_to_utf16(s);
    return env->NewString(reinterpret_cast<const jchar *>(u.data()), (jsize) u.size());
}

static std::string jstring_to_string(JNIEnv *env, jstring js) {
    if (!js) return {};
    const jchar *chars = env->GetStringChars(js, nullptr);
    jsize len = env->GetStringLength(js);
    std::u16string u(reinterpret_cast<const char16_t *>(chars), (size_t) len);
    env->ReleaseStringChars(js, chars);
    std::string out;
    out.reserve(u.size());
    for (size_t i = 0; i < u.size(); ++i) {
        uint32_t cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < u.size()) {
            uint32_t lo = u[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                i++;
            }
        }
        if (cp < 0x80) {
            out.push_back((char) cp);
        } else if (cp < 0x800) {
            out.push_back((char) (0xC0 | (cp >> 6)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back((char) (0xE0 | (cp >> 12)));
            out.push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        } else {
            out.push_back((char) (0xF0 | (cp >> 18)));
            out.push_back((char) (0x80 | ((cp >> 12) & 0x3F)));
            out.push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        }
    }
    return out;
}

static void android_log_callback(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
    else if (level == GGML_LOG_LEVEL_WARN) LOGW("%s", text);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localinterpreter_tablet_llm_LlamaNative_init(JNIEnv *env, jclass, jstring libDir) {
    llama_log_set(android_log_callback, nullptr);
    if (libDir) {
        const char *path = env->GetStringUTFChars(libDir, nullptr);
        LOGI("loading backends from %s", path);
        ggml_backend_load_all_from_path(path);
        env->ReleaseStringUTFChars(libDir, path);
    }
    llama_backend_init();
    size_t dev_count = ggml_backend_dev_count();
    LOGI("llama backend initialized: %zu device(s)", dev_count);
    for (size_t i = 0; i < dev_count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        LOGI("  backend[%zu]: %s (%s)", i, ggml_backend_dev_name(dev), ggml_backend_dev_description(dev));
    }
}

static bool has_gpu_backend_internal() {
    const size_t dev_count = ggml_backend_dev_count();
    for (size_t i = 0; i < dev_count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        enum ggml_backend_dev_type t = ggml_backend_dev_type(dev);
        if (t == GGML_BACKEND_DEVICE_TYPE_GPU || t == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            return true;
        }
    }
    return false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localinterpreter_tablet_llm_LlamaNative_nativeHasGpuBackend(JNIEnv *, jclass) {
    return has_gpu_backend_internal() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localinterpreter_tablet_llm_LlamaNative_nativeBackendSummary(JNIEnv *env, jclass) {
    const size_t dev_count = ggml_backend_dev_count();
    std::string out;
    for (size_t i = 0; i < dev_count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const char *name = ggml_backend_dev_name(dev);
        const char *desc = ggml_backend_dev_description(dev);
        if (!out.empty()) out += " + ";
        if (name && name[0]) out += name;
        if (desc && desc[0] && (!name || std::strcmp(name, desc) != 0)) {
            out += " (";
            out += desc;
            out += ")";
        }
    }
    if (out.empty()) out = "CPU";
    return to_jstring(env, out);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_localinterpreter_tablet_llm_LlamaNative_create(JNIEnv *env, jclass, jstring modelPath,
                                                        jint nCtx, jint nThreads, jint nGpuLayers) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    const std::string path_str(path ? path : "");
    env->ReleaseStringUTFChars(modelPath, path);

    const bool want_gpu = nGpuLayers > 0 && has_gpu_backend_internal();
    const int32_t requested_gpu_layers = want_gpu ? nGpuLayers : 0;

    auto *h = new LlamaContext();
    h->n_ctx = nCtx < 512 ? 512 : nCtx;
    h->n_batch = h->n_ctx < 512 ? h->n_ctx : 512;

    const int32_t threads = nThreads > 0 ? nThreads : 4;
    const int32_t online = std::max(1, (int32_t) sysconf(_SC_NPROCESSORS_ONLN));
    // Prefill is compute-bound while token decode is memory-bandwidth-bound; when user allows >=4 threads,
    // use up to (online - 1, capped at 6) for faster prompt prefill without hurting UI responsiveness.
    const int32_t batch_threads = threads >= 4
        ? std::min(6, std::max(threads, online - 1))
        : threads;

    auto try_init = [&](int32_t gpu_layers) -> bool {
        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = gpu_layers;
        mparams.use_extra_bufts = true;

        LOGI("loading model %s (gpu_layers=%d)", path_str.c_str(), gpu_layers);
        llama_model *model = llama_model_load_from_file(path_str.c_str(), mparams);
        if (!model) {
            LOGE("failed to load model (gpu_layers=%d)", gpu_layers);
            return false;
        }

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = h->n_ctx;
        cparams.n_batch = h->n_batch;
        cparams.n_ubatch = h->n_batch;
        cparams.n_threads = threads;
        cparams.n_threads_batch = batch_threads;
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        cparams.offload_kqv = gpu_layers > 0;
        cparams.no_perf = true;
        cparams.abort_callback = llama_abort_cb;
        cparams.abort_callback_data = h;
        llama_context *ctx = llama_init_from_model(model, cparams);
        if (!ctx) {
            LOGE("failed to create context (gpu_layers=%d)", gpu_layers);
            llama_model_free(model);
            return false;
        }
        h->model = model;
        h->ctx = ctx;
        return true;
    };

    int32_t active_gpu_layers = requested_gpu_layers;
    if (!try_init(requested_gpu_layers)) {
        if (requested_gpu_layers > 0) {
            LOGW("GPU initialization failed, seamlessly falling back to CPU backend");
            active_gpu_layers = 0;
            if (!try_init(0)) {
                delete h;
                return 0;
            }
        } else {
            delete h;
            return 0;
        }
    }

    h->prompt_batch = llama_batch_init(h->n_batch, 0, 1);
    h->single_batch = llama_batch_init(1, 0, 1);
    h->batches_ready = true;

    try {
        h->templates = common_chat_templates_init(h->model, "");
        h->has_template = h->templates != nullptr;
    } catch (...) {
        h->has_template = false;
    }
    LOGI("model ready, ctx=%d threads=%d batch_threads=%d gpu_layers=%d",
         h->n_ctx, threads, batch_threads, active_gpu_layers);
    return reinterpret_cast<jlong>(h);
}

static std::string format_prompt(LlamaContext *h, const std::string &system,
                                 const std::string &user) {
    if (h->has_template) {
        try {
            common_chat_templates_inputs inputs;
            inputs.use_jinja = true;
            inputs.add_generation_prompt = true;
            inputs.enable_thinking = false;
            if (!system.empty()) {
                common_chat_msg m;
                m.role = "system";
                m.content = system;
                inputs.messages.push_back(m);
            }
            common_chat_msg um;
            um.role = "user";
            um.content = user;
            inputs.messages.push_back(um);
            auto params = common_chat_templates_apply(h->templates.get(), inputs);
            return params.prompt.str();
        } catch (const std::exception &e) {
            LOGW("chat template failed: %s", e.what());
        } catch (...) {
            LOGW("chat template failed");
        }
    }
    std::string out;
    if (!system.empty()) out += "<|im_start|>system\n" + system + "<|im_end|>\n";
    out += "<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localinterpreter_tablet_llm_LlamaNative_generate(
        JNIEnv *env, jclass, jlong handle, jstring jsystem, jstring juser,
        jint maxTokens, jfloat temperature, jobject callback) {
    auto *h = reinterpret_cast<LlamaContext *>(handle);
    if (!h) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lock(h->mutex);
    h->cancel.store(false, std::memory_order_relaxed);

    std::string system = jstring_to_string(env, jsystem);
    std::string user = jstring_to_string(env, juser);
    if (user.empty()) {
        return env->NewStringUTF("");
    }
    std::string full;
    full.reserve((size_t) std::max(64, maxTokens) * 3);

    const llama_vocab *vocab = llama_model_get_vocab(h->model);
    int32_t reserve = maxTokens + 16;
    int32_t max_prompt = h->n_ctx - reserve;
    if (max_prompt < 128) max_prompt = h->n_ctx / 2;

    auto tokenize_prompt = [&](const std::string &u) {
        std::string p = format_prompt(h, system, u);
        return common_tokenize(h->ctx, p, false, true);
    };

    std::vector<llama_token> tokens = tokenize_prompt(user);
    int guard = 0;
    while ((int32_t) tokens.size() > max_prompt && user.size() > 64 && guard < 8) {
        size_t over = (size_t) ((int32_t) tokens.size() - max_prompt);
        size_t cut = over * 2 + 32;
        if (cut > user.size() - 64) cut = user.size() - 64;
        // Never cut in the middle of a UTF-8 sequence: advance to the next lead byte.
        while (cut < user.size() && (((unsigned char) user[cut]) & 0xC0) == 0x80) cut++;
        LOGW("prompt too long (%zu tokens), dropping %zu user chars", tokens.size(), cut);
        user.erase(0, cut);
        tokens = tokenize_prompt(user);
        guard++;
    }
    if (tokens.empty()) {
        return env->NewStringUTF("");
    }
    if ((int32_t) tokens.size() > max_prompt) {
        LOGE("prompt still too long after trimming: %zu > %d", tokens.size(), max_prompt);
        return env->NewStringUTF("");
    }

    llama_memory_t mem = llama_get_memory(h->ctx);
    // Reuse KV cache for matching prompt prefix (e.g. identical system prompt + glossary
    // across consecutive translations), leaving at least 1 token to decode for fresh logits.
    size_t common_prefix = 0;
    const size_t max_reuse = tokens.size() > 1 ? tokens.size() - 1 : 0;
    const size_t limit = std::min(h->cached_tokens.size(), max_reuse);
    while (common_prefix < limit && h->cached_tokens[common_prefix] == tokens[common_prefix]) {
        common_prefix++;
    }
    if (common_prefix > 0) {
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) common_prefix, -1)) {
            llama_memory_clear(mem, false);
            h->cached_tokens.clear();
            common_prefix = 0;
        }
    } else {
        llama_memory_clear(mem, false);
        h->cached_tokens.clear();
    }

    const int32_t n_batch = h->n_batch;
    llama_batch &batch = h->prompt_batch;
    int32_t rc = 0;
    bool cancelled = false;
    for (int32_t off = (int32_t) common_prefix; off < (int32_t) tokens.size(); off += n_batch) {
        if (h->cancel.load(std::memory_order_relaxed)) {
            cancelled = true;
            break;
        }
        int32_t n = std::min(n_batch, (int32_t) tokens.size() - off);
        for (int32_t j = 0; j < n; ++j) {
            int32_t idx = off + j;
            batch.token[j] = tokens[idx];
            batch.pos[j] = idx;
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = (idx == (int32_t) tokens.size() - 1);
        }
        batch.n_tokens = n;
        rc = llama_decode(h->ctx, batch);
        if (rc != 0) {
            if (h->cancel.load(std::memory_order_relaxed)) cancelled = true;
            break;
        }
    }
    if (cancelled || rc != 0) {
        if (rc != 0 && !cancelled) LOGE("prompt decode failed: %d", rc);
        // Only preserve cached_tokens up to common_prefix that was already intact before prefill.
        if (common_prefix > 0) {
            llama_memory_seq_rm(mem, 0, (llama_pos) common_prefix, -1);
            h->cached_tokens.resize(common_prefix);
        } else {
            h->cached_tokens.clear();
            llama_memory_clear(mem, false);
        }
        return env->NewStringUTF("");
    }
    h->cached_tokens = tokens;

    const llama_token eos_token = llama_vocab_eos(vocab);
    const llama_token eot_token = llama_vocab_eot(vocab);

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.05f) {
        // 实时翻译使用确定性贪心解码：跳过 15 万词表全排序/softmax/随机数，既更快又更稳。
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                llama_vocab_n_tokens(vocab), 32, 1.05f, 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                llama_vocab_n_tokens(vocab), 64, 1.05f, 0.0f, 0.0f));
        // 先用 top_k(24) 裁掉绝大部分候选，把 top_p 排序规模从 ~15 万降到 24。
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(24));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    llama_pos n_past = (llama_pos) tokens.size();

    jclass cbClass = nullptr;
    jmethodID cbMethod = nullptr;
    if (callback) {
        cbClass = env->GetObjectClass(callback);
        cbMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    }

    std::string pending;
    pending.reserve(32);
    int32_t generated = 0;
    llama_batch &single = h->single_batch;
    while (generated < maxTokens) {
        if (h->cancel.load(std::memory_order_relaxed)) break;
        if (n_past >= h->n_ctx - 1) break;

        llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
        if (id == eos_token || id == eot_token || llama_vocab_is_eog(vocab, id)) {
            llama_sampler_accept(smpl, id);
            break;
        }
        llama_sampler_accept(smpl, id);

        char stackbuf[256];
        std::vector<char> dyn;
        const char *src = stackbuf;
        int32_t n = llama_token_to_piece(vocab, id, stackbuf, sizeof(stackbuf), 0, false);
        if (n < 0) {
            dyn.resize((size_t) (-n) + 1);
            n = llama_token_to_piece(vocab, id, dyn.data(), (int32_t) dyn.size(), 0, false);
            src = dyn.data();
        }
        if (n > 0) {
            pending.append(src, (size_t) n);
            const size_t flush_len = utf8_flushable_len(pending);
            if (flush_len > 0) {
                std::string piece = pending.substr(0, flush_len);
                pending.erase(0, flush_len);
                full += piece;
                if (callback && cbMethod) {
                    jstring js = to_jstring(env, piece);
                    env->CallVoidMethod(callback, cbMethod, js);
                    env->DeleteLocalRef(js);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        callback = nullptr;
                    }
                }
            }
        }

        if (h->cancel.load(std::memory_order_relaxed)) break;
        single.token[0] = id;
        single.pos[0] = n_past;
        single.n_seq_id[0] = 1;
        single.seq_id[0][0] = 0;
        single.logits[0] = true;
        single.n_tokens = 1;
        rc = llama_decode(h->ctx, single);
        if (rc != 0) {
            if (!h->cancel.load(std::memory_order_relaxed)) {
                LOGE("token decode failed: %d", rc);
            }
            // Keep intact prompt prefix tokens so the next translation still hits KV cache.
            if (!h->cached_tokens.empty()) {
                llama_memory_seq_rm(mem, 0, (llama_pos) h->cached_tokens.size(), -1);
            } else {
                llama_memory_clear(mem, false);
            }
            break;
        }
        n_past++;
        generated++;
    }

    if (!pending.empty()) {
        full += pending;
        if (callback && cbMethod) {
            jstring js = to_jstring(env, pending);
            env->CallVoidMethod(callback, cbMethod, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    llama_sampler_free(smpl);
    if (cbClass) env->DeleteLocalRef(cbClass);
    return to_jstring(env, full);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localinterpreter_tablet_llm_LlamaNative_cancel(JNIEnv *, jclass, jlong handle) {
    auto *h = reinterpret_cast<LlamaContext *>(handle);
    if (h) h->cancel.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localinterpreter_tablet_llm_LlamaNative_destroy(JNIEnv *, jclass, jlong handle) {
    auto *h = reinterpret_cast<LlamaContext *>(handle);
    if (!h) return;
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        h->cached_tokens.clear();
        h->templates.reset();
        if (h->batches_ready) {
            llama_batch_free(h->prompt_batch);
            llama_batch_free(h->single_batch);
            h->batches_ready = false;
        }
        if (h->ctx) llama_free(h->ctx);
        if (h->model) llama_model_free(h->model);
        h->ctx = nullptr;
        h->model = nullptr;
    }
    delete h;
}
