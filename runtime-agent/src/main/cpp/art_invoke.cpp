// A real invokespecial, through the one JNI entry point that means it.
//
// ## Why not MethodHandles, and why not ART internals
//
// `invokeSpecial` has to call a superclass implementation on a subclass
// instance, bypassing virtual dispatch. `Method.invoke` cannot express that: it
// dispatches virtually, so invoking the superclass's `onCreate` on a
// LicenseActivity lands on `LicenseActivity.onCreate`, the method the patch
// replaced. The Java-level way out is `MethodHandles.Lookup.unreflectSpecial`,
// which needs `IMPL_LOOKUP` -- and on the target device that is unobtainable, for
// a reason that has nothing to do with MethodHandles: the hidden-API policy is
// enforced in this process, and Pine cannot lift it there, because it looks its
// six ART hook targets up by name and none of the six is in this build's
// `libart.so` `.dynsym`. See the "invoker" diagnostics in RuntimeLog.
//
// The obvious replacement is to reach into ART and call `ArtMethod::Invoke` with
// a direct invoke type, after working out the `ArtMethodInvoke` layout and how a
// 64-bit object reference is packed into a `uint32_t` vreg array. That is not
// needed, and it is not what this does.
//
// `JNIEnv` has had `CallNonvirtual<Type>Method` since JNI 1.2, and it is defined
// as exactly this operation: invoke the implementation of a method declared by
// `clazz` on `obj`, without dispatch. ART implements it as a direct invoke
// internally, which is what "nonvirtual" means in the specification. Using it
// means:
//
//   - no privileged Lookup, so the hidden-API policy is irrelevant on this path;
//   - no ART struct layouts, so nothing to reverse per Android version;
//   - argument marshalling, primitive widening, return conversion and pending
//     exception propagation are the VM's own code rather than a reimplementation.
//
// What this file does have to do is name the method to JNI, because JNI
// identifies methods by (class, name, descriptor) rather than by a Method
// object. The descriptor is built from the same `Class[]` the caller already has,
// so it cannot disagree with the arguments it is about to pass.

#include <android/log.h>
#include <jni.h>

#include <cstdio>
#include <string>

namespace {

constexpr char kTag[] = "KhoiRevanced";

// JNI's type descriptor for a class: one character for a primitive, the JVM
// spelling for an array, and the slash-separated binary name for a reference.
std::string jni_type_descriptor(JNIEnv* env, jclass type) {
  if (type == nullptr) return "V";

  jclass class_class = env->FindClass("java/lang/Class");
  if (class_class == nullptr) return "Ljava/lang/Object;";

  jmethodID get_name = env->GetMethodID(class_class, "getName", "()Ljava/lang/String;");
  // isPrimitive and isArray are *instance* methods on Android's java.lang.Class.
  // They were static on the desktop JVM, where `Class.isPrimitive()` still
  // compiles, so asking for them statically looks right and is not: it returns a
  // null jmethodID, and calling through that aborts the process with
  //
  //   Throwing new exception 'no static method isArray()Z' with unexpected
  //   pending exception: NoSuchMethodError
  //
  // which is what happened before this was corrected.
  jmethodID is_primitive = env->GetMethodID(class_class, "isPrimitive", "()Z");
  jmethodID is_array = env->GetMethodID(class_class, "isArray", "()Z");
  if (get_name == nullptr || is_primitive == nullptr || is_array == nullptr) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(class_class);
    return "Ljava/lang/Object;";
  }

  jstring name = static_cast<jstring>(env->CallObjectMethod(type, get_name));
  if (name == nullptr) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(class_class);
    return "Ljava/lang/Object;";
  }
  const jboolean primitive = env->CallBooleanMethod(type, is_primitive);
  const jboolean array = env->CallBooleanMethod(type, is_array);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(class_class);
    return "Ljava/lang/Object;";
  }

  const char* chars = env->GetStringUTFChars(name, nullptr);
  std::string descriptor;
  if (primitive == JNI_TRUE) {
    descriptor.assign(1, chars[0]);
  } else if (array == JNI_TRUE) {
    descriptor.assign("[");
    descriptor.append(chars);
  } else {
    descriptor.assign("L");
    for (const char* p = chars; *p != '\0'; ++p) {
      descriptor.push_back(*p == '.' ? '/' : *p);
    }
    descriptor.push_back(';');
  }

  env->ReleaseStringUTFChars(name, chars);
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(class_class);
  return descriptor;
}

void throw_by_name(JNIEnv* env, const char* class_name, const char* message) {
  jclass failure = env->FindClass(class_name);
  if (failure == nullptr) return;
  env->ThrowNew(failure, message);
  env->DeleteLocalRef(failure);
}

// Box a primitive return value, by way of its wrapper's valueOf.
//
// The NDK's jni.h is not the JDK's: it declares NewBooleanArray and friends but
// no single-value boxers, so there is nothing to call directly. The wrapper
// types are boot class path classes and their valueOf methods are ordinary public
// statics, so this is not subject to the hidden-API policy either.
jobject box_primitive(JNIEnv* env, char kind, jvalue raw) {
  struct Boxed {
    char kind;
    const char* class_name;
    const char* signature;
  };
  static const Boxed kBoxed[] = {
      {'Z', "java/lang/Boolean", "(Z)Ljava/lang/Boolean;"},
      {'B', "java/lang/Byte", "(B)Ljava/lang/Byte;"},
      {'C', "java/lang/Character", "(C)Ljava/lang/Character;"},
      {'S', "java/lang/Short", "(S)Ljava/lang/Short;"},
      {'I', "java/lang/Integer", "(I)Ljava/lang/Integer;"},
      {'J', "java/lang/Long", "(J)Ljava/lang/Long;"},
      {'F', "java/lang/Float", "(F)Ljava/lang/Float;"},
      {'D', "java/lang/Double", "(D)Ljava/lang/Double;"},
  };
  for (const Boxed& boxed : kBoxed) {
    if (boxed.kind != kind) continue;
    jclass wrapper = env->FindClass(boxed.class_name);
    if (wrapper == nullptr) return nullptr;
    jmethodID value_of = env->GetStaticMethodID(wrapper, "valueOf", boxed.signature);
    if (value_of == nullptr) {
      env->DeleteLocalRef(wrapper);
      return nullptr;
    }
    jobject boxed_value = env->CallStaticObjectMethod(wrapper, value_of, raw);
    env->DeleteLocalRef(wrapper);
    return boxed_value;
  }
  return nullptr;
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_dev_khoirevanced_runtime_agent_ArtInvoke_nativeInvokeSpecial(
    JNIEnv* env, jclass, jobject receiver, jclass declaring_class, jstring name,
    jobjectArray parameter_types, jobjectArray arguments, jclass return_type) {
  if (receiver == nullptr || declaring_class == nullptr || name == nullptr ||
      parameter_types == nullptr) {
    throw_by_name(env, "java/lang/NullPointerException",
                  "art.invokeSpecial: receiver, declaring class, name and "
                  "parameter types are all required");
    return nullptr;
  }

  const jsize declared = env->GetArrayLength(parameter_types);
  const jsize supplied = arguments == nullptr ? 0 : env->GetArrayLength(arguments);
  if (declared != supplied) {
    char text[128];
    snprintf(text, sizeof(text),
             "art.invokeSpecial: %d parameter types but %d arguments",
             static_cast<int>(declared), static_cast<int>(supplied));
    throw_by_name(env, "java/lang/IllegalArgumentException", text);
    return nullptr;
  }
  // jvalue is a union of a 64-bit slot and two 32-bit ones, so a jvalue array can
  // hold any argument list; the local below is capped, and the cap is reported
  // rather than silently truncating the call.
  jvalue call[16];
  if (declared > static_cast<jsize>(sizeof(call) / sizeof(call[0]))) {
    throw_by_name(env, "java/lang/UnsupportedOperationException",
                  "art.invokeSpecial: more than 16 arguments");
    return nullptr;
  }

  std::string signature = "(";
  for (jsize i = 0; i < declared; ++i) {
    auto parameter = static_cast<jclass>(env->GetObjectArrayElement(parameter_types, i));
    signature += jni_type_descriptor(env, parameter);
    env->DeleteLocalRef(parameter);
  }
  signature += ")";
  signature += jni_type_descriptor(env, return_type);

  const char* name_chars = env->GetStringUTFChars(name, nullptr);
  jmethodID method = env->GetMethodID(declaring_class, name_chars, signature.c_str());
  if (method == nullptr) {
    // GetMethodID has already thrown NoSuchMethodError. The descriptor is the only
    // thing that can be wrong here and the caller cannot see it, so it is
    // reported while the name is still held.
    __android_log_print(ANDROID_LOG_ERROR, kTag, "art.invokeSpecial: no method %s%s",
                        name_chars, signature.c_str());
  }
  env->ReleaseStringUTFChars(name, name_chars);
  if (method == nullptr) return nullptr;

  for (jsize i = 0; i < declared; ++i) {
    call[i].l = static_cast<jobject>(env->GetObjectArrayElement(arguments, i));
  }

  jobject returned = nullptr;
  jvalue raw;
  const std::string return_descriptor = jni_type_descriptor(env, return_type);
  const char kind = return_descriptor.empty() ? 'V' : return_descriptor[0];
  switch (kind) {
    case 'V':
      env->CallNonvirtualVoidMethodA(receiver, declaring_class, method, call);
      break;
    case 'Z':
      raw.z = env->CallNonvirtualBooleanMethodA(receiver, declaring_class, method, call);
      returned = box_primitive(env, 'Z', raw);
      break;
    case 'B':
      raw.b = env->CallNonvirtualByteMethodA(receiver, declaring_class, method, call);
      returned = box_primitive(env, 'B', raw);
      break;
    case 'C':
      raw.c = env->CallNonvirtualCharMethodA(receiver, declaring_class, method, call);
      returned = box_primitive(env, 'C', raw);
      break;
    case 'S':
      raw.s = env->CallNonvirtualShortMethodA(receiver, declaring_class, method, call);
      returned = box_primitive(env, 'S', raw);
      break;
    case 'I':
      raw.i = env->CallNonvirtualIntMethodA(receiver, declaring_class, method, call);
      returned = box_primitive(env, 'I', raw);
      break;
    case 'J':
      raw.j = env->CallNonvirtualLongMethodA(receiver, declaring_class, method, call);
      returned = box_primitive(env, 'J', raw);
      break;
    case 'F':
      raw.f = env->CallNonvirtualFloatMethodA(receiver, declaring_class, method, call);
      returned = box_primitive(env, 'F', raw);
      break;
    case 'D':
      raw.d = env->CallNonvirtualDoubleMethodA(receiver, declaring_class, method, call);
      returned = box_primitive(env, 'D', raw);
      break;
    default:
      // A pending exception is deliberately left pending: the VM has already
      // wrapped the callee's throwable, and clearing it here would turn a real
      // failure into a silent one. The Kotlin side checks and reports it.
      returned = env->CallNonvirtualObjectMethodA(receiver, declaring_class, method, call);
      break;
  }

  for (jsize i = 0; i < declared; ++i) env->DeleteLocalRef(call[i].l);
  return returned;
}
