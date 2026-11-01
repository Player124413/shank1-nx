package com.shank1.port

import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.RelativeLayout
import android.widget.TextView
import com.shank1.port.input.LayoutStore
import com.shank1.port.input.TouchControlsView
import com.shank1.port.input.VirtualPad
import com.shank1.port.perf.PerformanceManager
import org.libsdl.app.SDLActivity

/**
 * The game: SDL3's activity (the native side is `libmain.so`, its entry point `android_main`) with the touch overlay on
 * top of the Vulkan surface.
 *
 * The overlay is an ordinary view drawn over SDL's surface view. Touches go to it, never to SDL: the game reads a pad,
 * so the overlay is a pad - its buttons and sticks become XInput state in the native side (android_jni.cpp), and a
 * Bluetooth controller plugged in at the same time just ORs its own buttons in.
 *
 * The back gesture opens the in-game menu rather than killing the app: a phone's back button is not a keyboard Escape,
 * and losing twenty minutes of a level to a stray swipe would be the port's fault, not the player's.
 */
class GameActivity : SDLActivity() {

    private lateinit var overlay: TouchControlsView
    private lateinit var fpsView: TextView
    private lateinit var prefs: Prefs
    private lateinit var store: LayoutStore
    private var performance: PerformanceManager? = null
    private var inputManager: InputManager? = null
    private var controlsHidden = false

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateOverlayVisibility()
        override fun onInputDeviceRemoved(deviceId: Int) = updateOverlayVisibility()
        override fun onInputDeviceChanged(deviceId: Int) = updateOverlayVisibility()
    }

    /**
     * SDL's argv: the data root and the cvar file, which the native side reads in android_main
     * (port/src/main.cpp's Android entry). `getArguments` is SDL3's hook for exactly this.
     */
    override fun getArguments(): Array<String> {
        val arguments = ArrayList<String>()
        arguments.add("shank1")
        intent?.getStringExtra(EXTRA_DATA_ROOT)?.takeIf { it.isNotEmpty() }?.let {
            arguments.add("--game-root=$it")
        }
        intent?.getStringExtra(EXTRA_CONFIG)?.takeIf { it.isNotEmpty() }?.let {
            arguments.add("--config=$it")
        }
        return arguments.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        store = LayoutStore(this)
        super.onCreate(savedInstanceState)
        // super.onCreate started the native thread: from here the library's touch entry points are live.
        Native.markReady()
        VirtualPad.haptics = prefs.haptics

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        window.insetsController?.systemBarsBehavior =
            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        installOverlay()

        performance = PerformanceManager(this, prefs).also { it.start() }
        inputManager = getSystemService(INPUT_SERVICE) as? InputManager
        inputManager?.registerInputDeviceListener(deviceListener, null)
        updateOverlayVisibility()

        Native.sink = { fps, cpuMs, thermal ->
            runOnUiThread {
                if (prefs.showFps) {
                    fpsView.text = String.format(java.util.Locale.US, "%.0f fps  %.1f ms  t%d", fps, cpuMs, thermal)
                }
            }
        }
        fpsView.visibility = if (prefs.showFps) View.VISIBLE else View.GONE
    }

    private fun installOverlay() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val layout = store.load(landscape, prefs.layoutProfile.takeIf { it == "landscape" || it == "portrait" })

        overlay = TouchControlsView(this).apply {
            this.layout = layout
            editMode = false
            controlsVisible = prefs.overlayEnabled
            alpha = prefs.overlayAlpha
            VirtualPad.haptics = prefs.haptics
        }

        fpsView = TextView(this).apply {
            setTextColor(android.graphics.Color.parseColor("#E9E2D5"))
            textSize = 11f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(120, 7, 8, 10))
                cornerRadius = dp(6).toFloat()
            }
            visibility = View.GONE
        }

        // SDL's own content root (a RelativeLayout holding the SDL surface view): the overlay is a sibling of the
        // surface, so there is no second window to fight with and no surface to recreate.
        val content = (getContentView() as? ViewGroup)
            ?: (findViewById<ViewGroup>(android.R.id.content) ?: return)
        content.addView(
            overlay,
            RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        content.addView(
            fpsView,
            RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP); addRule(RelativeLayout.ALIGN_PARENT_START) },
        )
        content.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            overlay.setSafeArea(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** The overlay shows itself unless the player hid it, or a real pad is connected and they asked it to stand down. */
    private fun updateOverlayVisibility() {
        if (!::overlay.isInitialized) return
        val pad = prefs.hideWithGamepad && VirtualPad.gamepadConnected()
        val visible = prefs.overlayEnabled && !controlsHidden && !pad
        overlay.controlsVisible = visible
        if (!visible) overlay.releaseAll()
    }

    override fun onResume() {
        super.onResume()
        Native.setAppActive(true)
        Native.markReady()
        updateOverlayVisibility()
    }

    override fun onPause() {
        Native.setAppActive(false)
        if (::overlay.isInitialized) {
            overlay.releaseAll()
            VirtualPad.reset()
        }
        super.onPause()
    }

    override fun onDestroy() {
        inputManager?.unregisterInputDeviceListener(deviceListener)
        performance?.stop()
        Native.sink = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlay.isInitialized) {
            val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            overlay.layout = store.load(landscape, prefs.layoutProfile.takeIf { it == "landscape" || it == "portrait" })
            overlay.releaseAll()
        }
    }

    /** Back opens the menu: resume, edit the controls, hide them, or leave the game properly. */
    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        if (overlay.editMode) {
            overlay.editMode = false
            return
        }
        val items = arrayOf(
            getString(R.string.game_resume),
            getString(R.string.game_hide_controls) + if (controlsHidden) " ✓" else "",
            getString(R.string.menu_controls),
            getString(R.string.game_quit),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.game_paused)
            .setItems(items) { _, which ->
                when (which) {
                    1 -> {
                        controlsHidden = !controlsHidden
                        updateOverlayVisibility()
                    }
                    2 -> startActivity(Intent(this, ControlsActivity::class.java))
                    3 -> finishAndRemoveTask()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_DATA_ROOT = "com.shank1.port.DATA_ROOT"
        const val EXTRA_CONFIG = "com.shank1.port.CONFIG"
    }
}
