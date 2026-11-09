// JNI bridge for qwentts.cpp (MIT): QwenTTS-he on the phone, no Python.
// Kotlin side: com.karianoot.engine.QwenNative. One context per process; calls are serialized by the caller.
#include <jni.h>
#include <atomic>
#include <cstdlib>
#include <string>
#include <vector>
#include "qwen.h"

#ifdef __ANDROID__
#include <android/log.h>
static void log_cb(enum qt_log_level level, const char * msg, void *) {
    int p = level >= QT_LOG_ERROR ? ANDROID_LOG_ERROR : level >= QT_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    if (level >= QT_LOG_INFO) __android_log_print(p, "qwentts", "%s", msg);
}
#endif

static std::atomic<bool> g_cancel{false};
static bool cancel_cb(void *) { return g_cancel.load(); }

struct Progress { JNIEnv * env; jobject cb; jmethodID m; };

extern "C" {

JNIEXPORT jlong JNICALL Java_com_karianoot_engine_QwenNative_init(JNIEnv * env, jclass, jstring talker, jstring codec, jint threads) {
#ifdef __ANDROID__
    qt_log_set(log_cb, nullptr);
#endif
    if (threads > 0) setenv("QT_N_THREADS", std::to_string(threads).c_str(), 1);
    const char * t = env->GetStringUTFChars(talker, nullptr);
    const char * c = env->GetStringUTFChars(codec, nullptr);
    qt_init_params p;
    qt_init_default_params(&p);
    p.talker_path = t;
    p.codec_path  = c;
    p.device      = "CPU";
    qt_context * q = qt_init(&p);
    env->ReleaseStringUTFChars(talker, t);
    env->ReleaseStringUTFChars(codec, c);
    return (jlong) q;
}

JNIEXPORT void JNICALL Java_com_karianoot_engine_QwenNative_free(JNIEnv *, jclass, jlong h) {
    qt_free((qt_context *) h);
}

JNIEXPORT jstring JNICALL Java_com_karianoot_engine_QwenNative_lastError(JNIEnv * env, jclass) {
    const char * e = qt_last_error();
    return env->NewStringUTF(e ? e : "");
}

JNIEXPORT void JNICALL Java_com_karianoot_engine_QwenNative_cancel(JNIEnv *, jclass) { g_cancel = true; }

// Voice print (speaker embedding) from 24 kHz mono audio: what a voice made from a recording keeps.
JNIEXPORT jfloatArray JNICALL Java_com_karianoot_engine_QwenNative_voicePrint(JNIEnv * env, jclass, jlong h, jfloatArray audio) {
    jsize n = env->GetArrayLength(audio);
    std::vector<float> a((size_t) n);
    env->GetFloatArrayRegion(audio, 0, n, a.data());
    qt_voice_ref ref = {};
    if (qt_extract_voice_ref((qt_context *) h, a.data(), (int) n, &ref) != QT_STATUS_OK || !ref.ref_spk_emb) {
        qt_voice_ref_free(&ref);
        return nullptr;
    }
    jfloatArray out = env->NewFloatArray(ref.ref_spk_dim);
    env->SetFloatArrayRegion(out, 0, ref.ref_spk_dim, ref.ref_spk_emb);
    qt_voice_ref_free(&ref);
    return out;
}

// Stressed IPA -> 24 kHz mono samples, in the voice of `spk` (x-vector clone mode, as tested).
// temperature 0 = greedy (the tested setting); seed -1 = random.
JNIEXPORT jfloatArray JNICALL Java_com_karianoot_engine_QwenNative_synthesize(JNIEnv * env, jclass, jlong h, jstring ipa,
                                                                          jfloatArray spk, jfloat temperature, jlong seed, jint maxFrames) {
    g_cancel = false;
    const char * text = env->GetStringUTFChars(ipa, nullptr);
    jsize dim = env->GetArrayLength(spk);
    std::vector<float> s((size_t) dim);
    env->GetFloatArrayRegion(spk, 0, dim, s.data());
    qt_tts_params p;
    qt_tts_default_params(&p);
    p.text = text;
    p.lang = "auto";
    p.ref_spk_emb = s.data();
    p.ref_spk_dim = (int) dim;
    p.seed = (int64_t) seed;
    // A cap from the text length (12.5 frames per second of speech), so a run that misses its end stops instead of
    // talking for minutes.
    p.max_new_tokens = maxFrames > 0 ? maxFrames : 2048;
    p.temperature = temperature;
    p.subtalker_temperature = temperature;
    p.repetition_penalty = temperature > 0 ? 1.05f : 1.0f;   // upstream default when sampling; greedy as tested
    p.cancel = cancel_cb;
    qt_audio a = {};
    qt_status st = qt_synthesize((qt_context *) h, &p, &a);
    env->ReleaseStringUTFChars(ipa, text);
    if (st != QT_STATUS_OK || !a.samples) { qt_audio_free(&a); return nullptr; }
    jfloatArray out = env->NewFloatArray(a.n_samples);
    env->SetFloatArrayRegion(out, 0, a.n_samples, a.samples);
    qt_audio_free(&a);
    return out;
}

}  // extern "C"
