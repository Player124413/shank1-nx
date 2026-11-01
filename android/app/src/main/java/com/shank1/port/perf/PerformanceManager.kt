package com.shank1.port.perf

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.view.Choreographer
import android.view.Display
import android.view.WindowManager
import com.shank1.port.Native
import com.shank1.port.Prefs

/**
 * What the phone can be asked for, and what it tells back.
 *
 * Going down:
 *  - **the display mode**: the panel's fastest mode (or the one the player picked), so a 120 Hz phone runs the game
 *    at 120 rather than the system's default 60;
 *  - **sustained performance**: the governor's promise that the clocks will not drop mid-level;
 *  - **game mode** (API 31+): the platform's own "this is a game, prioritise it" hint;
 *  - **the window flags** that keep the picture from being scaled by the compositor.
 *
 * Coming up:
 *  - **the refresh rate and its period**, measured with a [Choreographer] callback: the engine picks a frame budget
 *    that divides it, so 60 Hz gets 16.6 ms, 90 Hz 11.1 ms, and a missed frame is skipped rather than doubled;
 *  - **the thermal status**: as the phone warms (THERMAL_STATUS_LIGHT onwards) the engine lowers its render scale and
 *    turns off the extras before the hardware starts throttling the whole app (sa2_render_mode + SA2_ENHANCEMENT).
 *
 * All of it is a request: an OEM that ignores sustained performance or a panel with one mode still runs.
 */
class PerformanceManager(
    private val activity: Activity,
    private val prefs: Prefs,
) : DisplayManager.DisplayListener {

    private val displayManager =
        activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    private var lastFrameNanos = 0L
    private var smoothedPeriod = 0L
    private var framesSinceReport = 0
    private var reportedRefresh = 0f
    private var display: Display? = null

    fun start() {
        val window = activity.window
        display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            window.windowManager.defaultDisplay
        }
        applyDisplayMode()
        applyWindowFlags()
        applySustainedPerformance()
        applyGameMode()
        displayManager?.registerDisplayListener(this, null)
        startThermalListener()
        startFramePacing()
        reportDisplay()
    }

    fun stop() {
        try {
            displayManager?.unregisterDisplayListener(this)
        } catch (e: Exception) {
            // Already gone.
        }
        thermalListener?.let { listener ->
            try {
                (activity.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.removeThermalStatusListener(listener)
            } catch (e: Exception) {
                // API 29 guard: the listener was never added.
            }
        }
        thermalListener = null
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
    }

    // --- Display ----------------------------------------------------------------------------------------------------

    private fun applyDisplayMode() {
        val wanted = when (prefs.refreshRate) {
            "auto" -> Float.MAX_VALUE
            else -> prefs.refreshRate.toFloatOrNull() ?: Float.MAX_VALUE
        }
        val current = display ?: return
        val modes = runCatching { current.supportedModes }.getOrNull() ?: return
        val best = modes
            .filter { mode -> mode.physicalWidth > 0 }
            .maxByOrNull { mode ->
                // The fastest mode at the panel's own resolution; a fixed rate picks the closest mode to it.
                val rate = mode.refreshRate
                if (wanted == Float.MAX_VALUE) rate else rate - kotlin.math.abs(rate - wanted) * 2f
            } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.window.attributes = activity.window.attributes.apply {
                preferredDisplayModeId = best.modeId
            }
        }
    }

    private fun applyWindowFlags() {
        val window = activity.window
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    private fun applySustainedPerformance() {
        if (!prefs.sustainedPerformance) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { activity.window.setSustainedPerformanceMode(true) }
        }
    }

    /** API 31's game mode, reached by reflection: the call is absent on older platforms and in stubs. */
    private fun applyGameMode() {
        if (!prefs.gameMode) return
        if (Build.VERSION.SDK_INT < 31) return
        runCatching {
            val gameModeClass = Class.forName("android.app.GameMode")
            val performance = gameModeClass.getField("GAME_MODE_PERFORMANCE").getInt(null)
            val method = Activity::class.java.getMethod("setGameMode", Int::class.javaPrimitiveType)
            method.invoke(activity, performance)
        }
    }

    // --- Refresh rate and frame pacing ------------------------------------------------------------------------------

    private fun reportDisplay() {
        val current = display ?: return
        val hz = runCatching { current.refreshRate }.getOrDefault(60f)
        val period = (1e9 / hz.coerceAtLeast(1f)).toLong()
        reportedRefresh = hz
        Native.setDisplayTiming(hz, if (smoothedPeriod > 0) smoothedPeriod else period)
    }

    private fun startFramePacing() {
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastFrameNanos != 0L) {
                    val delta = frameTimeNanos - lastFrameNanos
                    if (delta in 1_000_000L..100_000_000L) {
                        smoothedPeriod = if (smoothedPeriod == 0L) delta else (smoothedPeriod * 7 + delta) / 8
                        framesSinceReport++
                        if (framesSinceReport >= 120) {
                            framesSinceReport = 0
                            val hz = 1e9f / smoothedPeriod
                            if (kotlin.math.abs(hz - reportedRefresh) > 1.5f) {
                                reportedRefresh = hz
                                Native.setDisplayTiming(hz, smoothedPeriod)
                            }
                        }
                    }
                }
                lastFrameNanos = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    // --- Thermal ----------------------------------------------------------------------------------------------------

    private fun startThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val power = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            Native.setThermalStatus(status)
        }
        thermalListener = listener
        try {
            power.addThermalStatusListener(activity.mainExecutor, listener)
        } catch (e: Exception) {
            thermalListener = null
            return
        }
        // The first status may already be warm: ask for it now instead of waiting for a change.
        Native.setThermalStatus(power.currentThermalStatus)
    }

    // --- DisplayManager ---------------------------------------------------------------------------------------------

    override fun onDisplayAdded(displayId: Int) {}

    override fun onDisplayRemoved(displayId: Int) {}

    override fun onDisplayChanged(displayId: Int) {
        if (display?.displayId == displayId) reportDisplay()
    }
}
