/**
 * @file    android/android_pad.h
 *
 * The Android virtual pad: the state the touch overlay drives, read by the game's XInput path.
 *
 * One global, lock-free-ish state (a seqlock around a small struct, because a touch arrives on the UI thread and the
 * game reads it on its own). The port's XInputGetState hook reads it with AndroidPadState::Read() and ORs the result
 * into whatever a real Bluetooth/HID pad reported, so both work at once.
 */
#pragma once

#include <cstdint>

namespace shank1::android {

/** XInput's button bits, the ones the recompiled game's XInputGetState answers with. */
inline constexpr uint16_t kPadUp = 0x0001;
inline constexpr uint16_t kPadDown = 0x0002;
inline constexpr uint16_t kPadLeft = 0x0004;
inline constexpr uint16_t kPadRight = 0x0008;
inline constexpr uint16_t kPadStart = 0x0010;
inline constexpr uint16_t kPadBack = 0x0020;
inline constexpr uint16_t kPadLeftThumb = 0x0040;
inline constexpr uint16_t kPadRightThumb = 0x0080;
inline constexpr uint16_t kPadLeftShoulder = 0x0100;
inline constexpr uint16_t kPadRightShoulder = 0x0200;
inline constexpr uint16_t kPadA = 0x1000;
inline constexpr uint16_t kPadB = 0x2000;
inline constexpr uint16_t kPadX = 0x4000;
inline constexpr uint16_t kPadY = 0x8000;

struct PadState {
  uint16_t buttons = 0;
  /** -1..1, the way the game's XInput reads them: up is positive. */
  float left_x = 0;
  float left_y = 0;
  float right_x = 0;
  float right_y = 0;
  /** 0..1. */
  float left_trigger = 0;
  float right_trigger = 0;
  /** How many fingers are on the overlay: while any is, the pad answers even if a real pad is connected too. */
  uint32_t pointers = 0;
};

namespace android_pad {

/** Called from the JNI bridge on the UI thread. */
void Write(uint16_t buttons, float left_x, float left_y, float right_x, float right_y, float left_trigger,
           float right_trigger, uint32_t pointers);

/** Called by the game: a consistent copy of the overlay's state. */
PadState Read();

/** True while the overlay is holding the pad (any finger down) or reported buttons. The XInput hook uses it to decide
 *  whether to claim the pad slot for the touch controls. */
bool Active();

/** Drops everything: the activity went to the background with fingers on the screen. */
void Clear();

} // namespace android_pad
} // namespace shank1::android
