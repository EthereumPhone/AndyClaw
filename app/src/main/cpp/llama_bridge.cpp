/**
 * JNI bridge for llama.cpp — exposes model loading, chat completion, and
 * streaming chat completion to Kotlin via the LlamaCpp class.
 *
 * The bridge produces OpenAI-compatible JSON responses so the Kotlin layer
 * can use OpenAiFormatAdapter for conversion to the internal Anthropic format.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <android/log.h>

#ifdef __has_include
#if __has_include("llama.h")
#include "llama.h"
#include "common.h"
#define LLAMA_AVAILABLE 1
#else
#define LLAMA_AVAILABLE 0
#endif
#else
#define LLAMA_AVAILABLE 0
#endif

#define TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#if LLAMA_AVAILABLE

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;

#endif

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_ethereumphone_andyclaw_llm_LlamaCpp_loadModel(
    JNIEnv *env, jobject /* this */, jstring modelPath, jint nCtx) {

#if LLAMA_AVAILABLE
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s (nCtx=%d)", path, nCtx);

    // Initialize llama backend
    llama_backend_init();

    // Load model
    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only on mobile
    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    // Create context
    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = 4;
    g_ctx = llama_init_from_model(g_model, ctx_params);

    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully");
    return JNI_TRUE;
#else
    LOGE("llama.cpp not available in this build");
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_org_ethereumphone_andyclaw_llm_LlamaCpp_unloadModel(
    JNIEnv * /* env */, jobject /* this */) {

#if LLAMA_AVAILABLE
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
    LOGI("Model unloaded");
#endif
}

/**
 * Non-streaming chat completion.
 *
 * Takes an OpenAI-format request JSON string, runs inference,
 * and returns an OpenAI-format response JSON string.
 *
 * For the initial integration this is a simplified implementation.
 * A full implementation would parse the messages array, apply the
 * chat template, tokenize, sample, and produce proper tool_calls.
 */
JNIEXPORT jstring JNICALL
Java_org_ethereumphone_andyclaw_llm_LlamaCpp_chatCompletion(
    JNIEnv *env, jobject /* this */, jstring requestJson) {

#if LLAMA_AVAILABLE
    if (!g_model || !g_ctx) {
        return env->NewStringUTF("{\"error\":\"Model not loaded\"}");
    }

    const char *req = env->GetStringUTFChars(requestJson, nullptr);
    std::string request(req);
    env->ReleaseStringUTFChars(requestJson, req);

    // TODO: Full implementation — parse messages, apply chat template,
    // tokenize, run inference loop, decode tokens, build response.
    // For now, return a placeholder acknowledging the request was received.

    std::string response = R"({
        "id": "local-0",
        "object": "chat.completion",
        "model": "qwen3-4b",
        "choices": [{
            "index": 0,
            "message": {
                "role": "assistant",
                "content": "Local model inference is not yet fully implemented."
            },
            "finish_reason": "stop"
        }],
        "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
    })";

    return env->NewStringUTF(response.c_str());
#else
    return env->NewStringUTF("{\"error\":\"llama.cpp not available in this build\"}");
#endif
}

/**
 * Streaming chat completion.
 *
 * Takes an OpenAI-format request JSON and a LlamaStreamCallback.
 * Calls onToken() for each generated token and onComplete() when done.
 */
JNIEXPORT void JNICALL
Java_org_ethereumphone_andyclaw_llm_LlamaCpp_chatCompletionStream(
    JNIEnv *env, jobject /* this */, jstring requestJson, jobject callback) {

#if LLAMA_AVAILABLE
    if (!g_model || !g_ctx) {
        jclass cls = env->GetObjectClass(callback);
        jmethodID onError = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
        env->CallVoidMethod(callback, onError, env->NewStringUTF("Model not loaded"));
        return;
    }

    jclass cls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    jmethodID onComplete = env->GetMethodID(cls, "onComplete", "(Ljava/lang/String;)V");

    // TODO: Full implementation — parse messages, apply chat template,
    // tokenize, run inference loop calling onToken() per token.

    // Placeholder: emit a single token and complete
    env->CallVoidMethod(callback, onToken,
        env->NewStringUTF("Local model streaming is not yet fully implemented."));

    // Build a complete OpenAI response for the final callback
    std::string response = R"({
        "id": "local-stream-0",
        "object": "chat.completion",
        "model": "qwen3-4b",
        "choices": [{
            "index": 0,
            "message": {
                "role": "assistant",
                "content": "Local model streaming is not yet fully implemented."
            },
            "finish_reason": "stop"
        }]
    })";

    env->CallVoidMethod(callback, onComplete, env->NewStringUTF(response.c_str()));
#else
    jclass cls = env->GetObjectClass(callback);
    jmethodID onError = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
    env->CallVoidMethod(callback, onError, env->NewStringUTF("llama.cpp not available"));
#endif
}

} // extern "C"
