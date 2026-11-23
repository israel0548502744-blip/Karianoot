// JNI bridge for whisper.cpp (MIT): the ivrit-ai Hebrew recognizer (Apache-2.0) on the phone, for the in-app
// proofreader ("בדיקת הקריינות"). Kotlin side: com.karianoot.engine.WhisperNative. Calls on one context are serialized by
// the caller; every failure becomes a Java exception.
#include <jni.h>

#include <atomic>
#include <string>
#include <vector>

#include "whisper.h"

static std::atomic<bool> g_cancel{false};

static void throw_java(JNIEnv * env, const char * cls, const std::string & msg) {
    if (env->ExceptionCheck()) return;
    jclass c = env->FindClass(cls);
    if (c != nullptr) env->ThrowNew(c, msg.c_str());
}

static std::string utf8(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java strings from UTF-8 bytes (NewStringUTF wants "modified" UTF-8; Hebrew is in the BMP but go through bytes to be safe).
static jstring jstr(JNIEnv * env, const std::string & s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), (const jbyte *) s.data());
    jclass str = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(str, "<init>", "([BLjava/lang/String;)V");
    return (jstring) env->NewObject(str, ctor, bytes, env->NewStringUTF("UTF-8"));
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_karianoot_engine_WhisperNative_load(JNIEnv * env, jclass, jstring path) {
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context * ctx = whisper_init_from_file_with_params(utf8(env, path).c_str(), cp);
    if (ctx == nullptr) throw_java(env, "java/lang/RuntimeException", "whisper: could not load the model");
    return (jlong) ctx;
}

JNIEXPORT void JNICALL Java_com_karianoot_engine_WhisperNative_free(JNIEnv *, jclass, jlong h) {
    if (h != 0) whisper_free((whisper_context *) h);
}

JNIEXPORT void JNICALL Java_com_karianoot_engine_WhisperNative_cancel(JNIEnv *, jclass) { g_cancel = true; }

// 16 kHz mono samples -> the text heard (segments joined by spaces).
JNIEXPORT jstring JNICALL Java_com_karianoot_engine_WhisperNative_transcribe(JNIEnv * env, jclass, jlong h, jfloatArray pcm,
                                                                            jstring lang, jint threads, jint beam) {
    g_cancel = false;
    auto * ctx = (whisper_context *) h;
    const jsize n = env->GetArrayLength(pcm);
    std::vector<float> x((size_t) n);
    env->GetFloatArrayRegion(pcm, 0, n, x.data());
    whisper_full_params p = whisper_full_default_params(beam > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    std::string l = utf8(env, lang);
    p.language = l.c_str();
    p.n_threads = threads > 0 ? threads : 4;
    p.translate = false;
    p.no_timestamps = true;
    p.print_progress = false; p.print_realtime = false; p.print_special = false; p.print_timestamps = false;
    if (beam > 1) p.beam_search.beam_size = beam;
    p.abort_callback = [](void *) { return g_cancel.load(); };
    if (whisper_full(ctx, p, x.data(), n) != 0) {
        throw_java(env, g_cancel ? "java/util/concurrent/CancellationException" : "java/lang/RuntimeException", g_cancel ? "cancelled" : "whisper: transcription failed");
        return nullptr;
    }
    std::string out;
    for (int i = 0; i < whisper_full_n_segments(ctx); ++i) {
        if (!out.empty()) out += ' ';
        out += whisper_full_get_segment_text(ctx, i);
    }
    return jstr(env, out);
}

}  // extern "C"
