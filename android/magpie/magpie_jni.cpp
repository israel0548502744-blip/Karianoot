// JNI bridge for magpie-tts.cpp (MIT): Magpie TTS Hebrew (NVIDIA Open Model License) on the phone, no Python.
// Kotlin side: com.karianoot.engine.MagpieNative / MagpieTts. One context per loaded model; calls on a context are
// serialized by the caller. Every C++ exception becomes a Java exception carrying its message.
#include <jni.h>

#include <atomic>
#include <cstdlib>
#include <cstdint>
#include <exception>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "magpie_tts.h"

static std::atomic<bool> g_cancel{false};

// Every graph magpie computes (encoder, each decoder / local-transformer step, the codec) goes through
// ggml_backend_graph_compute; the link step (--wrap) routes it here, so a cancel stops the synthesis at the next
// graph (tens of milliseconds) instead of after the whole piece. magpie then throws "graph compute failed".
extern "C" enum ggml_status __real_ggml_backend_graph_compute(ggml_backend_t backend, struct ggml_cgraph * cgraph);
extern "C" enum ggml_status __wrap_ggml_backend_graph_compute(ggml_backend_t backend, struct ggml_cgraph * cgraph) {
    if (g_cancel.load(std::memory_order_relaxed)) return GGML_STATUS_ABORTED;
    return __real_ggml_backend_graph_compute(backend, cgraph);
}

namespace {

void throw_java(JNIEnv * env, const char * cls, const std::string & msg) {
    if (env->ExceptionCheck()) return;
    jclass c = env->FindClass(cls);
    if (c != nullptr) env->ThrowNew(c, msg.c_str());
}

// Turns whatever the call throws into a Java exception (a cancel into java.util.concurrent.CancellationException).
template <class F, class R>
R guarded(JNIEnv * env, R fallback, F && f) {
    try {
        return f();
    } catch (const std::bad_alloc &) {
        throw_java(env, "java/lang/OutOfMemoryError", "magpie: out of memory");
    } catch (const std::exception & e) {
        if (g_cancel.load()) throw_java(env, "java/util/concurrent/CancellationException", "cancelled");
        else throw_java(env, "java/lang/RuntimeException", std::string("magpie: ") + e.what());
    } catch (...) {
        throw_java(env, "java/lang/RuntimeException", "magpie: unknown error");
    }
    return fallback;
}

// Java string -> standard UTF-8 (GetStringUTFChars gives "modified" UTF-8, which differs outside the BMP).
std::string utf8(JNIEnv * env, jstring s) {
    std::string out;
    if (s == nullptr) return out;
    const jsize n = env->GetStringLength(s);
    const jchar * c = env->GetStringChars(s, nullptr);
    if (c == nullptr) return out;
    out.reserve((size_t) n * 2);
    for (jsize i = 0; i < n; ++i) {
        uint32_t cp = c[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n && c[i + 1] >= 0xDC00 && c[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (c[i + 1] - 0xDC00);
            ++i;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;  // a lone surrogate
        }
        if (cp < 0x80) {
            out += (char) cp;
        } else if (cp < 0x800) {
            out += (char) (0xC0 | (cp >> 6));
            out += (char) (0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += (char) (0xE0 | (cp >> 12));
            out += (char) (0x80 | ((cp >> 6) & 0x3F));
            out += (char) (0x80 | (cp & 0x3F));
        } else {
            out += (char) (0xF0 | (cp >> 18));
            out += (char) (0x80 | ((cp >> 12) & 0x3F));
            out += (char) (0x80 | ((cp >> 6) & 0x3F));
            out += (char) (0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(s, c);
    return out;
}

magpie_tts_context * ctx_of(jlong h) {
    if (h == 0) throw std::runtime_error("model not loaded");
    return reinterpret_cast<magpie_tts_context *>(h);
}

}  // namespace

extern "C" {

// GGUF path -> handle (never 0: a failure throws).
JNIEXPORT jlong JNICALL Java_com_karianoot_engine_MagpieNative_load(JNIEnv * env, jclass, jstring path) {
    return guarded(env, (jlong) 0, [&]() -> jlong {
        g_cancel = false;
        // Attention prior off unless the caller's environment says otherwise: with it the Hebrew CER rose 0.9% -> 7.8%.
        setenv("MAGPIE_PRIOR", "0", 0);
        return (jlong) magpie_tts_load(utf8(env, path));
    });
}

JNIEXPORT void JNICALL Java_com_karianoot_engine_MagpieNative_free(JNIEnv *, jclass, jlong h) {
    magpie_tts_free(reinterpret_cast<magpie_tts_context *>(h));
}

JNIEXPORT jint JNICALL Java_com_karianoot_engine_MagpieNative_sampleRate(JNIEnv * env, jclass, jlong h) {
    return guarded(env, (jint) 0, [&]() -> jint { return magpie_tts_sample_rate(*ctx_of(h)); });
}

// Stops the running synthesize (it then throws CancellationException). The next synthesize starts fresh.
JNIEXPORT void JNICALL Java_com_karianoot_engine_MagpieNative_cancel(JNIEnv *, jclass) { g_cancel = true; }

// IPA text -> mono float PCM at sampleRate (22050 Hz), in baked voice `speaker`.
// Negative temperature / topk / cfgScale / maxFrames = the model's defaults; cfgScale 0 = no guidance;
// seed <= 0 = a random take; threads <= 0 = automatic.
JNIEXPORT jfloatArray JNICALL Java_com_karianoot_engine_MagpieNative_synthesize(JNIEnv * env, jclass, jlong h, jstring ipa,
        jstring language, jint speaker, jfloat temperature, jint topk, jfloat cfgScale, jlong seed, jint threads, jint maxFrames) {
    return guarded(env, (jfloatArray) nullptr, [&]() -> jfloatArray {
        g_cancel = false;
        magpie_tts_context * ctx = ctx_of(h);
        magpie_tts_options o;
        o.language      = utf8(env, language);
        o.speaker       = "";
        o.speaker_index = speaker;
        o.temperature   = temperature;
        o.topk          = topk;
        o.use_cfg       = cfgScale != 0.0f;
        o.cfg_scale     = cfgScale;
        o.seed          = seed > 0 ? (uint64_t) seed : 0;
        o.n_threads     = threads > 0 ? threads : 0;
        o.max_frames    = maxFrames;
        std::vector<float> pcm = magpie_tts_synthesize(*ctx, utf8(env, ipa), o);
        if (g_cancel.load()) throw std::runtime_error("cancelled");
        jfloatArray out = env->NewFloatArray((jsize) pcm.size());
        if (out == nullptr) return nullptr;  // OutOfMemoryError is pending
        env->SetFloatArrayRegion(out, 0, (jsize) pcm.size(), pcm.data());
        return out;
    });
}

}  // extern "C"
