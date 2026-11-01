/**
 * @file    android/android_pad.cpp
 */

#include "android/android_pad.h"

#include <atomic>

namespace shank1::android::android_pad {
namespace {

// One writer (the UI thread) and one reader (the game's input poll), so a seqlock is enough: the writer bumps the
// counter to odd, writes, bumps it to even; the reader retries while the value is odd or changed under it. No locks,
// no priority inversion with a real-time renderer.
std::atomic<uint32_t> g_sequence{0};
PadState g_state{};
std::atomic<bool> g_active{false};

} // namespace

void Write(uint16_t buttons, float left_x, float left_y, float right_x, float right_y, float left_trigger,
           float right_trigger, uint32_t pointers) {
  g_sequence.fetch_add(1, std::memory_order_acq_rel); // odd: writing
  g_state.buttons = buttons;
  g_state.left_x = left_x;
  g_state.left_y = left_y;
  g_state.right_x = right_x;
  g_state.right_y = right_y;
  g_state.left_trigger = left_trigger;
  g_state.right_trigger = right_trigger;
  g_state.pointers = pointers;
  g_sequence.fetch_add(1, std::memory_order_release); // even: done
  g_active.store(pointers != 0 || buttons != 0, std::memory_order_relaxed);
}

PadState Read() {
  PadState copy;
  for (int attempt = 0; attempt < 8; ++attempt) {
    const uint32_t before = g_sequence.load(std::memory_order_acquire);
    if (before & 1u)
      continue;
    copy = g_state;
    if (g_sequence.load(std::memory_order_acquire) == before)
      return copy;
  }
  return copy;
}

bool Active() { return g_active.load(std::memory_order_relaxed); }

void Clear() { Write(0, 0, 0, 0, 0, 0, 0, 0); }

} // namespace shank1::android::android_pad
