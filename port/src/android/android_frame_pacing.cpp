/**
 * @file    android/android_frame_pacing.cpp
 */

#include "android/android_frame_pacing.h"

#include <atomic>
#include <chrono>

namespace shank1::android::pacing {
namespace {

std::atomic<float> g_refresh_hz{60.0f};
std::atomic<int64_t> g_period_nanos{16'666'667};
std::atomic<int32_t> g_thermal{0};
std::atomic<bool> g_active{true};

// Frame-time history: eight frames, so one expensive level-load frame does not change the scale.
constexpr int kHistory = 8;
std::atomic<int64_t> g_frames[kHistory]{};
std::atomic<int> g_frame_index{0};

// The scale the port decided on, in quarters. 4 = 1x (the game's own resolution), 3 = 0.75x, 2 = 0.5x.
std::atomic<uint32_t> g_scale_quarters{4};
std::atomic<int64_t> g_last_step_nanos{0};

int64_t NowNanos();

} // namespace

void SetDisplayTiming(float refresh_hz, int64_t period_nanos) {
  if (refresh_hz > 1.0f && refresh_hz < 1000.0f) {
    g_refresh_hz.store(refresh_hz, std::memory_order_relaxed);
    // 0.5 % tolerance: a panel that says 59.94 Hz still gets a 16.68 ms budget, not 16.6.
    if (period_nanos > 500'000 && period_nanos < 100'000'000)
      g_period_nanos.store(period_nanos, std::memory_order_relaxed);
    else
      g_period_nanos.store(int64_t(1e9 / double(refresh_hz)), std::memory_order_relaxed);
  }
}

float RefreshHz() { return g_refresh_hz.load(std::memory_order_relaxed); }

int64_t FrameBudgetNanos() { return g_period_nanos.load(std::memory_order_relaxed); }

void SetThermalStatus(int32_t status) {
  if (status < 0 || status > 6)
    return;
  const int32_t previous = g_thermal.exchange(status, std::memory_order_relaxed);
  if (previous == status)
    return;
  // Thermals walk the scale down (and back up) without waiting for the frame-time check: the phone is telling us
  // before it starts throttling.
  uint32_t quarters = 4;
  if (status >= 4) // SEVERE and worse: the phone is at its limit
    quarters = 2;
  else if (status == 3) // CRITICAL
    quarters = 2;
  else if (status == 2) // MODERATE
    quarters = 3;
  else if (status == 1) // LIGHT
    quarters = 4;
  if (status >= 2)
    g_scale_quarters.store(quarters, std::memory_order_relaxed);
  else
    g_scale_quarters.store(4, std::memory_order_relaxed);
  g_last_step_nanos.store(0, std::memory_order_relaxed);
}

int32_t ThermalStatus() { return g_thermal.load(std::memory_order_relaxed); }

void ReportFrameTime(int64_t frame_nanos) {
  if (frame_nanos <= 0 || frame_nanos > 1'000'000'000)
    return;
  const int index = g_frame_index.fetch_add(1, std::memory_order_relaxed) % kHistory;
  g_frames[index].store(frame_nanos, std::memory_order_relaxed);

  // Once a second at most, look at the average: if the game is consistently over its budget and the phone is warm,
  // step the scale down; if it is comfortably under and the phone is cool, step back up.
  const int64_t now = NowNanos();
  const int64_t last = g_last_step_nanos.load(std::memory_order_relaxed);
  if (last != 0 && now - last < 1'000'000'000)
    return;
  g_last_step_nanos.store(now, std::memory_order_relaxed);

  int64_t sum = 0;
  int count = 0;
  for (int i = 0; i < kHistory; ++i) {
    const int64_t value = g_frames[i].load(std::memory_order_relaxed);
    if (value > 0) {
      sum += value;
      ++count;
    }
  }
  if (count < kHistory)
    return;

  const double average = double(sum) / double(count);
  const double budget = double(FrameBudgetNanos());
  const int32_t thermal = g_thermal.load(std::memory_order_relaxed);
  uint32_t quarters = g_scale_quarters.load(std::memory_order_relaxed);

  if (thermal >= 2) {
    // The thermals already decided; only the frame time can make it worse.
    if (average > budget * 1.25 && quarters > 2)
      quarters = quarters == 4 ? 3 : 2;
  } else if (average > budget * 1.35 && quarters > 2) {
    quarters = quarters == 4 ? 3 : 2;
  } else if (average < budget * 0.85 && thermal == 0 && quarters < 4) {
    quarters = quarters == 3 ? 4 : 3;
  }
  g_scale_quarters.store(quarters, std::memory_order_relaxed);
}

uint32_t DesiredScaleQuarters() { return g_scale_quarters.load(std::memory_order_relaxed); }

void SetAppActive(bool active) { g_active.store(active, std::memory_order_relaxed); }

bool AppActive() { return g_active.load(std::memory_order_relaxed); }

namespace {

int64_t NowNanos() {
#if defined(__ANDROID__)
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
#else
  return 0;
#endif
}

} // namespace
} // namespace shank1::android::pacing
