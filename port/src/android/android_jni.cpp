/**
 * @file    android/android_jni.cpp
 *
 * The Kotlin side's door into the game (android/app/src/main/java/com/shank1/port/Native.kt):
 *  - the touch overlay's pad state;
 *  - the display's refresh rate and period;
 *  - the platform's thermal status;
 *  - foreground/background;
 *  - and one call back the other way, once a second, with the frame rate for the on-screen counter.
 *
 * Nothing here touches the renderer: the functions only publish values into the pad and pacing modules, which the
 * game's own threads read. That is why a JNI call on the UI thread cannot stall a frame.
 */

#if defined(__ANDROID__)

#include <jni.h>

#include <atomic>
#include <chrono>

#include "android/android_frame_pacing.h"
#include "android/android_pad.h"
#include "android/android_paths.h"

namespace {

JavaVM *g_vm = nullptr;
jclass g_native_class = nullptr;
jmethodID g_on_frame_stats = nullptr;

// Frame-rate accounting for the counter: the game's present loop calls AndroidJni::ReportFrame().
std::atomic<int64_t> g_frame_count{0};
std::atomic<int64_t> g_frame_nanos{0};
std::atomic<int64_t> g_window_start{0};

int64_t NowNanos() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

} // namespace

namespace shank1::android::jni {

/** Called by the game once per presented frame (port/src/main.cpp's Android loop, or the renderer's present hook). */
void ReportFrame(int64_t frame_nanos) {
  shank1::android::pacing::ReportFrameTime(frame_nanos);
  g_frame_count.fetch_add(1, std::memory_order_relaxed);
  g_frame_nanos.fetch_add(frame_nanos, std::memory_order_relaxed);

  const int64_t now = NowNanos();
  int64_t start = g_window_start.load(std::memory_order_relaxed);
  if (start == 0) {
    g_window_start.store(now, std::memory_order_relaxed);
    return;
  }
  if (now - start < 1'000'000'000)
    return;

  const int64_t frames = g_frame_count.exchange(0, std::memory_order_relaxed);
  const int64_t nanos = g_frame_nanos.exchange(0, std::memory_order_relaxed);
  g_window_start.store(now, std::memory_order_relaxed);
  if (frames == 0)
    return;

  const float fps = float(double(frames) * 1e9 / double(now - start));
  const float cpu_ms = float(double(nanos) / double(frames) / 1e6);
  const int32_t thermal = shank1::android::pacing::ThermalStatus();

  JNIEnv *env = nullptr;
  if (g_vm == nullptr || g_native_class == nullptr || g_on_frame_stats == nullptr)
    return;
  const bool attached = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK;
  if (!attached) {
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK)
      return;
  }
  env->CallStaticVoidMethod(g_native_class, g_on_frame_stats, jfloat(fps), jfloat(cpu_ms), jint(thermal));
  if (env->ExceptionCheck())
    env->ExceptionClear();
  if (!attached)
    g_vm->DetachCurrentThread();
}

} // namespace shank1::android::jni

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
  g_vm = vm;
  JNIEnv *env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
    return JNI_ERR;
  jclass local = env->FindClass("com/shank1/port/Native");
  if (local != nullptr) {
    g_native_class = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_on_frame_stats = env->GetStaticMethodID(g_native_class, "onFrameStats", "(FFI)V");
    if (g_on_frame_stats == nullptr)
      env->ExceptionClear();
  }
  return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *, void *) {
  g_native_class = nullptr;
  g_on_frame_stats = nullptr;
  g_vm = nullptr;
}

/** com.shank1.port.Native.nativeSetPadState */
JNIEXPORT void JNICALL Java_com_shank1_port_Native_nativeSetPadState(JNIEnv *, jclass, jint buttons, jfloat left_x,
                                                                    jfloat left_y, jfloat right_x, jfloat right_y,
                                                                    jfloat left_trigger, jfloat right_trigger,
                                                                    jint pointers) {
  shank1::android::android_pad::Write(uint16_t(buttons), left_x, left_y, right_x, right_y, left_trigger, right_trigger,
                                     uint32_t(pointers));
}

/** com.shank1.port.Native.nativeSetDisplayTiming */
JNIEXPORT void JNICALL Java_com_shank1_port_Native_nativeSetDisplayTiming(JNIEnv *, jclass, jfloat refresh_hz,
                                                                         jlong period_nanos) {
  shank1::android::pacing::SetDisplayTiming(refresh_hz, int64_t(period_nanos));
}

/** com.shank1.port.Native.nativeSetThermalStatus */
JNIEXPORT void JNICALL Java_com_shank1_port_Native_nativeSetThermalStatus(JNIEnv *, jclass, jint status) {
  shank1::android::pacing::SetThermalStatus(int32_t(status));
}

/** com.shank1.port.Native.nativeSetAppActive */
JNIEXPORT void JNICALL Java_com_shank1_port_Native_nativeSetAppActive(JNIEnv *, jclass, jboolean active) {
  shank1::android::pacing::SetAppActive(active == JNI_TRUE);
  if (active != JNI_TRUE)
    shank1::android::android_pad::Clear();
}

} // extern "C"

#endif // __ANDROID__
