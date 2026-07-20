#include <jni.h>

// The stable JNI surface is in place first; Android-version ART engines will be
// selected here by SDK level. Returning no capabilities prevents partially
// ported patches from running against an unsafe backend.

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_khoirevanced_runtime_agent_NativeHookBackend_nativeInitialize(
    JNIEnv*, jobject, jstring) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_khoirevanced_runtime_agent_NativeHookBackend_nativeCapabilities(
    JNIEnv* env, jobject) {
    return env->NewIntArray(0);
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_khoirevanced_runtime_agent_NativeHookBackend_nativeHook(
    JNIEnv*, jobject, jobject, jobject) {
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_khoirevanced_runtime_agent_NativeHookBackend_nativeUnhook(
    JNIEnv*, jobject, jlong) {}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_khoirevanced_runtime_agent_NativeHookBackend_nativeInvokeOriginal(
    JNIEnv* env, jobject, jobject, jobject, jobjectArray) {
    jclass unsupported = env->FindClass("java/lang/UnsupportedOperationException");
    env->ThrowNew(unsupported, "ART invokeOriginal backend is not installed");
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_khoirevanced_runtime_agent_NativeHookBackend_nativeDeoptimize(
    JNIEnv*, jobject, jobject) {
    return JNI_FALSE;
}
