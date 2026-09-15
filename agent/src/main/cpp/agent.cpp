#include <jni.h>
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>

#define LOG_TAG "KhoiRevancedAgent"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_dwngkhoi_khoirevanced_AgentDiagnostics_nativeAgentVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("khoirevanced-agent/0.2.0 arm64-v8a");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dwngkhoi_khoirevanced_AgentDiagnostics_nativeWriteMarker(JNIEnv* env, jclass, jstring path) {
    const char* markerPath = env->GetStringUTFChars(path, nullptr);
    if (markerPath == nullptr) return JNI_FALSE;
    FILE* file = fopen(markerPath, "we");
    if (file == nullptr) {
        env->ReleaseStringUTFChars(path, markerPath);
        return JNI_FALSE;
    }
    fprintf(file, "agent_loaded pid=%d\\n", getpid());
    fclose(file);
    chmod(markerPath, 0600);
    env->ReleaseStringUTFChars(path, markerPath);
    LOGI("diagnostic agent loaded");
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM*, char*, void*) {
    LOGI("Agent_OnAttach reached");
    return JNI_OK;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM*, char*, void*) {
    LOGI("Agent_OnLoad reached");
    return JNI_OK;
}