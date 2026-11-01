/**
 * @file    android/android_frame_pacing.h
 *
 * What the phone tells the engine about the display and about itself, and what the engine does with it.
 *
 * The display: Android reports the refresh rate as a float that moves (variable refresh, LTPO panels that drop to
 * 10 Hz on a still picture). The engine needs a frame budget that divides it and a decision for a frame that misses
 * it. Both come from here.
 *
 * The heat: `PowerManager`'s thermal status is the platform's own opinion - at LIGHT the phone is warm, at MODERATE
 * it is about to slow down. Rather than let the governor halve the clocks and make the picture stutter, the port
 * lowers its own render scale (and drops the expensive extras) first: a lower resolution looks better than 20 fps.
 */
#pragma once

#include <cstdint>

namespace shank1::android::pacing {

/** Called from the JNI bridge. [refresh_hz] is what the display is doing now, [period_nanos] its measured period. */
void SetDisplayTiming(float refresh_hz, int64_t period_nanos);

float RefreshHz();
/** The frame budget in nanoseconds: the display's period, or the multiple of it the game can hold. */
int64_t FrameBudgetNanos();

/** 0..6, `PowerManager.THERMAL_STATUS_*`. Called from the JNI bridge. */
void SetThermalStatus(int32_t status);
int32_t ThermalStatus();

/**
 * The render scale the port should use, in quarters (the same unit as the game's own render mode): 1x, 0.75x, 0.5x
 * as the phone heats up, back up when it cools. The Switch port's display sync used the same idea with the console's
 * clock changes; on a phone the input is the thermal status and a dip in the measured frame time.
 */
uint32_t DesiredScaleQuarters();

/** Samples the frame time; call once per presented frame with the frame's own duration. */
void ReportFrameTime(int64_t frame_nanos);

/** True while the app is in the foreground: the frame limiter sleeps instead of burning cycles when it is not. */
void SetAppActive(bool active);
bool AppActive();

} // namespace shank1::android::pacing
