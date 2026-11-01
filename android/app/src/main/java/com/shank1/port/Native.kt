package com.shank1.port

/**
 * Everything the Kotlin side tells the game: the pad state, the display's refresh rate and the thermal status.
 *
 * The library is `libmain.so`, the entry point SDL3's SDLActivity loads (see AndroidManifest: the game activity's
 * `android.app.lib_name` meta-data). The functions are in port/src/android/android_jni.cpp; the pad bits are the
 * XInput ones, which is what the recompiled game reads through XInputGetState.
 */
object Native {

    /**
     * Whether `libmain.so` is loaded. SDL's activity loads it by absolute path when it starts the game; the launcher
     * never does. Loading it by name here is the same library, so the first call from either side is safe and a
     * missing library (a Kotlin-only build) degrades to `false` instead of a crash.
     */
    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("main")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /** Kept for GameActivity: the lazy load above does the work, so this only needs to exist. */
    fun markReady() { /* no-op */ }

    /**
     * The virtual pad's state. [pointers] is how many fingers are on the overlay: the game keeps the pad's
     * `XInputGetState` answering while any is, so the touch controls and a Bluetooth pad coexist.
     */
    fun setPadState(
        buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, pointers: Int,
    ) {
        if (!loaded) return
        try {
            nativeSetPadState(buttons, lx, ly, rx, ry, lt, rt, pointers)
        } catch (e: UnsatisfiedLinkError) {
            // A build of the native side without the touch bridge: the controls simply have nothing to drive.
        }
    }

    /** The display's current refresh and its nominal period, so the engine can choose a frame budget that divides it. */
    fun setDisplayTiming(refreshHz: Float, periodNanos: Long) {
        if (!loaded) return
        try {
            nativeSetDisplayTiming(refreshHz, periodNanos)
        } catch (e: UnsatisfiedLinkError) {
        }
    }

    /** `PowerManager.THERMAL_STATUS_*`: the engine drops its render scale when the phone is warm. */
    fun setThermalStatus(status: Int) {
        if (!loaded) return
        try {
            nativeSetThermalStatus(status)
        } catch (e: UnsatisfiedLinkError) {
        }
    }

    /** The app went to the background (SDL's own pause also fires; this is for the frame pacer). */
    fun setAppActive(active: Boolean) {
        if (!loaded) return
        try {
            nativeSetAppActive(active)
        } catch (e: UnsatisfiedLinkError) {
        }
    }

    /** Set by [GameActivity]: the native side calls [onFrameStats] about once a second. */
    @Volatile
    var sink: ((fps: Float, cpuMs: Float, thermal: Int) -> Unit)? = null

    /**
     * Called from the native side's frame loop (port/src/android/android_jni.cpp) once a second with the frame rate, the
     * CPU time per frame and `PowerManager.THERMAL_STATUS_*`. `@JvmStatic` is what lets the C++ side find it with
     * GetStaticMethodID on this class.
     */
    @JvmStatic
    fun onFrameStats(fps: Float, cpuMs: Float, thermal: Int) {
        sink?.invoke(fps, cpuMs, thermal)
    }

    @JvmStatic
    private external fun nativeSetPadState(
        buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, pointers: Int,
    )

    @JvmStatic
    private external fun nativeSetDisplayTiming(refreshHz: Float, periodNanos: Long)

    @JvmStatic
    private external fun nativeSetThermalStatus(status: Int)

    @JvmStatic
    private external fun nativeSetAppActive(active: Boolean)
}
