// llama.cpp bridge for the Android tablet build.
// Each Context owns one model + KV cache so translation and assistant models
// can be loaded independently.

#include <android/log.h>
#include <jni.h>

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
};

static bool is_valid_utf8(const char *string) {
    if (!string) return true;
    const auto *bytes = (const unsigned char *) string;
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) num = 1;
        else if ((*bytes & 0xE0) == 0xC0) num = 2;
        else if ((*bytes & 0xF0) == 0xE0) num = 3;
        else if ((*bytes & 0xF8) == 0xF0) num = 4;
        else return false;
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
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
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_localinterpreter_tablet_llm_LlamaNative_create(JNIEnv *env, jclass, jstring modelPath,
                                                        jint nCtx, jint nThreads) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    LOGI("loading model %s", path);
    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!model) {
        LOGE("failed to load model");
        return 0;
    }

    auto *h = new LlamaContext();
    h->model = model;
    h->n_ctx = nCtx < 512 ? 512 : nCtx;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = h->n_ctx;
    cparams.n_batch = h->n_ctx < 1024 ? h->n_ctx : 1024;
    cparams.n_ubatch = 512;
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = nThreads > 0 ? nThreads : 4;
    cparams.offload_kqv = false;
    h->ctx = llama_init_from_model(model, cparams);
    if (!h->ctx) {
        LOGE("failed to create context");
        llama_model_free(model);
        delete h;
        return 0;
    }

    try {
        h->templates = common_chat_templates_init(model, "");
        h->has_template = h->templates != nullptr;
    } catch (...) {
        h->has_template = false;
    }
    LOGI("model ready, ctx=%d", h->n_ctx);
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
    h->cancel.store(false);

    std::string system = jstring_to_string(env, jsystem);
    std::string user = jstring_to_string(env, juser);
    std::string full;
    if (user.empty()) {
        return env->NewStringUTF("");
    }

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

    llama_memory_clear(llama_get_memory(h->ctx), true);

    const int32_t n_batch = (int32_t) llama_n_batch(h->ctx);
    llama_batch batch = llama_batch_init(n_batch, 0, 1);
    int32_t rc = 0;
    for (int32_t off = 0; off < (int32_t) tokens.size(); off += n_batch) {
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
        if (rc != 0) break;
    }
    llama_batch_free(batch);
    if (rc != 0) {
        LOGE("prompt decode failed: %d", rc);
        return env->NewStringUTF("");
    }

    const llama_token eos_token = llama_vocab_eos(vocab);
    const llama_token eot_token = llama_vocab_eot(vocab);

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            llama_vocab_n_tokens(vocab), 64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature <= 0.f ? 0.3f : temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_pos n_past = (llama_pos) tokens.size();

    jclass cbClass = nullptr;
    jmethodID cbMethod = nullptr;
    if (callback) {
        cbClass = env->GetObjectClass(callback);
        cbMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    }

    std::string pending;
    int32_t generated = 0;
    llama_batch single = llama_batch_init(1, 0, 1);
    while (generated < maxTokens) {
        if (h->cancel.load()) break;
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
            if (is_valid_utf8(pending.c_str())) {
                full += pending;
                if (callback && cbMethod) {
                    jstring js = to_jstring(env, pending);
                    env->CallVoidMethod(callback, cbMethod, js);
                    env->DeleteLocalRef(js);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        callback = nullptr;
                    }
                }
                pending.clear();
            }
        }

        // 复用同一个 batch：每 token 新建/释放一次在小模型上也是可观的固定开销。
        single.token[0] = id;
        single.pos[0] = n_past;
        single.n_seq_id[0] = 1;
        single.seq_id[0][0] = 0;
        single.logits[0] = true;
        single.n_tokens = 1;
        rc = llama_decode(h->ctx, single);
        if (rc != 0) {
            LOGE("token decode failed: %d", rc);
            break;
        }
        n_past++;
        generated++;
    }

    if (!pending.empty() && is_valid_utf8(pending.c_str())) {
        full += pending;
        if (callback && cbMethod) {
            jstring js = to_jstring(env, pending);
            env->CallVoidMethod(callback, cbMethod, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    llama_batch_free(single);
    llama_sampler_free(smpl);
    if (cbClass) env->DeleteLocalRef(cbClass);
    return to_jstring(env, full);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localinterpreter_tablet_llm_LlamaNative_cancel(JNIEnv *, jclass, jlong handle) {
    auto *h = reinterpret_cast<LlamaContext *>(handle);
    if (h) h->cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localinterpreter_tablet_llm_LlamaNative_destroy(JNIEnv *, jclass, jlong handle) {
    auto *h = reinterpret_cast<LlamaContext *>(handle);
    if (!h) return;
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        h->templates.reset();
        if (h->ctx) llama_free(h->ctx);
        if (h->model) llama_model_free(h->model);
        h->ctx = nullptr;
        h->model = nullptr;
    }
    delete h;
}
